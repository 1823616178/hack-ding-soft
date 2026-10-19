package com.hanwang.facekey.thin.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hanwang.facekey.thin.crypto.Sm2Config;
import com.hanwang.facekey.thin.crypto.Sm2ConfigLoader;
import com.hanwang.facekey.thin.face.FaceEngine;
import com.hanwang.facekey.thin.face.KeyCodeManager;
import com.hanwang.facekey.thin.face.TemplateStore;
import com.hanwang.facekey.thin.net.AddCardResult;
import com.hanwang.facekey.thin.net.HttpApi;
import com.hanwang.facekey.thin.protocol.Protocol;
import com.hanwang.facekey.thin.tcp.PostMobileRecord;
import com.hanwang.facekey.thin.tcp.TcpPunchClient;

import java.net.Socket;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

/**
 * Camera + native face pipeline, then HTTP addCard or encrypted TCP PostMobileRecord.
 * The flow and thresholds mirror the source APK's FaceDetectActivity.
 */
public final class PunchActivity extends Activity implements Camera.PreviewCallback {
    public static final String EXTRA_BASE_URL = "baseUrl";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_EMPLOY_ID = "employId";
    public static final String EXTRA_TCP_PORT = "recordTcpPort";
    public static final String EXTRA_MAP_GAODE = "mapGaode";
    public static final String EXTRA_GPS = "gps";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_TEST_MODE = "testMode";
    public static final String EXTRA_CUSTOM_TIME = "customTime";

    private static final int REQUEST_CAMERA = 21;
    private static final int CAMERA_WIDTH = FaceEngine.CAMERA_WIDTH;
    private static final int CAMERA_HEIGHT = FaceEngine.CAMERA_HEIGHT;

    /** Immutable payload linking recognition result strictly with the frame data that passed matching. */
    private static final class RecognizedFrame {
        final long sessionId;
        final String photoBase64;
        final boolean hasMask;

        RecognizedFrame(long sessionId, String photoBase64, boolean hasMask) {
            this.sessionId = sessionId;
            this.photoBase64 = photoBase64;
            this.hasMask = hasMask;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FaceEngine engine = new FaceEngine();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicInteger locateFailures = new AtomicInteger(0);
    private final AtomicLong detectionSession = new AtomicLong(0);

    private final AtomicReference<Call> activeHttpCall = new AtomicReference<>(null);
    private final AtomicReference<Socket> activeTcpSocket = new AtomicReference<>(null);
    private final AtomicBoolean requestSent = new AtomicBoolean(false);

    private CameraSurfaceView previewView;
    private FaceOverlayView overlayView;
    private TextView statusView;
    private TextView countdownView;
    private TextView testBadge;
    private LinearLayout resultPanel;
    private TextView resultTitle;
    private TextView resultMessage;

    private Camera camera;
    private int cameraId;
    private volatile int rotation;
    private volatile int previewWidth = CAMERA_WIDTH;
    private volatile int previewHeight = CAMERA_HEIGHT;
    private volatile boolean surfaceReady;
    private volatile boolean engineReady;
    private volatile boolean detectionStarted;
    private volatile boolean finished;
    private volatile boolean submitting;
    private volatile boolean paused;
    private volatile long firstFrameAt;
    private int countdownStep = 5;

    private byte[] templateFeature;
    private byte[] maskFeature;
    private volatile byte[] activeKeyCode;

    private String baseUrl;
    private String token;
    private String employId;
    private int recordTcpPort;
    private int serverSpecifiedMap;
    private String gps;
    private String lat;
    private String lng;
    private boolean testMode;
    private String customTime;
    private String preflightError;

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (finished || paused) return;
            if (countdownStep > 0) {
                countdownView.setText(String.valueOf(countdownStep));
                countdownStep--;
                main.postDelayed(this, 500L);
            } else {
                countdownView.setText("请正对摄像头");
                detectionStarted = true;
                setStatus("正在检测人脸...");
                main.postDelayed(timeoutRunnable, 30000L);
            }
        }
    };

    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (!finished && !submitting && !paused) {
                fail("检测超时，请重试", false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readIntent();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersive();
        setContentView(buildLayout());

        if (preflightError != null) {
            fail(preflightError, false);
            return;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            boolean phoneGranted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!cameraGranted || !phoneGranted) {
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.READ_PHONE_STATE
                }, REQUEST_CAMERA);
                return;
            }
        }
        startInitialization();
    }

    private void readIntent() {
        Intent intent = getIntent();
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL);
        token = intent.getStringExtra(EXTRA_TOKEN);
        employId = intent.getStringExtra(EXTRA_EMPLOY_ID);
        recordTcpPort = intent.getIntExtra(EXTRA_TCP_PORT, -1);
        serverSpecifiedMap = intent.getIntExtra(EXTRA_MAP_GAODE, 0);
        gps = intent.getStringExtra(EXTRA_GPS);
        lat = intent.getStringExtra(EXTRA_LAT);
        lng = intent.getStringExtra(EXTRA_LNG);
        testMode = intent.getBooleanExtra(EXTRA_TEST_MODE, false);
        customTime = intent.getStringExtra(EXTRA_CUSTOM_TIME);

        // Remove sensitive tokens from intent to limit in-memory exposure
        if (intent != null) {
            intent.removeExtra(EXTRA_TOKEN);
            intent.removeExtra(EXTRA_EMPLOY_ID);
        }

        try {
            baseUrl = Protocol.normalizeBaseUrl(baseUrl);
            Protocol.requireToken(token);
            Protocol.formatCoordinate(lat, "lat");
            Protocol.formatCoordinate(lng, "lng");
            if (gps == null || gps.trim().isEmpty()) {
                throw new Protocol.ProtocolException("缺少地点名称", "gps");
            }
            if (testMode) Protocol.validateCustomTime(customTime);
        } catch (Protocol.ProtocolException e) {
            preflightError = e.getMessage();
        }
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new CameraSurfaceView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlayView = new FaceOverlayView(this);
        root.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        countdownView = new TextView(this);
        countdownView.setTextColor(Color.WHITE);
        countdownView.setTextSize(64f);
        countdownView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams countdownParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        countdownParams.gravity = Gravity.CENTER;
        root.addView(countdownView, countdownParams);

        testBadge = new TextView(this);
        testBadge.setText("测试模式：手动地点/时间");
        testBadge.setTextColor(Color.WHITE);
        testBadge.setTextSize(12f);
        testBadge.setPadding(16, 8, 16, 8);
        testBadge.setBackgroundColor(0xCCB71C1C);
        testBadge.setVisibility(testMode ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        badgeParams.gravity = Gravity.TOP | Gravity.START;
        badgeParams.setMargins(24, 24, 0, 0);
        root.addView(testBadge, badgeParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(15f);
        statusView.setPadding(24, 16, 24, 24);
        statusView.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.BOTTOM;
        root.addView(statusView, statusParams);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelPunch();
            }
        });
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cancelParams.gravity = Gravity.TOP | Gravity.END;
        cancelParams.setMargins(0, 24, 24, 0);
        root.addView(cancel, cancelParams);

        resultPanel = new LinearLayout(this);
        resultPanel.setOrientation(LinearLayout.VERTICAL);
        resultPanel.setBackgroundColor(0xFFFAFAFA);
        resultPanel.setPadding(42, 36, 42, 36);
        resultPanel.setVisibility(View.GONE);

        resultTitle = new TextView(this);
        resultTitle.setTextSize(26f);
        resultTitle.setTextColor(0xFF212121);
        resultPanel.addView(resultTitle);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = 20;
        resultMessage = new TextView(this);
        resultMessage.setTextSize(16f);
        resultMessage.setTextColor(0xFF424242);
        scroll.addView(resultMessage);
        resultPanel.addView(scroll, scrollParams);

        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backParams.topMargin = 24;
        resultPanel.addView(back, backParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.setMargins(32, 32, 32, 32);
        root.addView(resultPanel, panelParams);

        previewView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = true;
                startCameraIfReady();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                stopCamera();
            }
        });
        previewView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        return root;
    }

    private void startInitialization() {
        setStatus("正在初始化人脸引擎...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    activeKeyCode = KeyCodeManager.load(PunchActivity.this);
                    if (activeKeyCode == null) {
                        throw new FaceEngine.FaceException("KEYCODE_MISSING",
                                "未找到 KeyCode，请先在主界面导入或获取授权 KeyCode");
                    }
                    engine.init(activeKeyCode, getApplicationContext());
                    if (finished) {
                        engine.release();
                        return;
                    }

                    String standard = TemplateStore.loadStandard(PunchActivity.this);
                    if (standard == null) {
                        throw new FaceEngine.FaceException("TEMPLATE_MISSING",
                                "本地没有人脸模板，请先登录并下载模板");
                    }
                    templateFeature = engine.featureFromBase64(standard);
                    if (templateFeature == null) {
                        throw new FaceEngine.FaceException("TEMPLATE_READ_FAILED", "读取本地模板失败");
                    }
                    String mask = TemplateStore.loadMask(PunchActivity.this);
                    if (mask != null) {
                        try {
                            maskFeature = engine.featureFromBase64(mask);
                        } catch (FaceEngine.FaceException ignored) {
                            maskFeature = null;
                        }
                    }
                    engineReady = true;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (finished || paused) return;
                            setStatus("人脸引擎已就绪");
                            startCameraIfReady();
                        }
                    });
                } catch (Exception e) {
                    if (activeKeyCode != null) {
                        Arrays.fill(activeKeyCode, (byte) 0);
                        activeKeyCode = null;
                    }
                    final String message = e.getMessage() == null ? "初始化失败" : e.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            fail(message, false);
                        }
                    });
                }
            }
        });
    }

    private void startCameraIfReady() {
        if (finished || submitting || paused || !engineReady || !surfaceReady || camera != null) return;
        if (openCamera()) {
            startCountdown();
        }
    }

    private boolean openCamera() {
        try {
            cameraId = chooseCameraId();
            camera = Camera.open(cameraId);
            camera.setPreviewDisplay(previewView.getHolder());
            Camera.Parameters parameters = camera.getParameters();
            parameters.setPictureFormat(ImageFormat.JPEG);
            parameters.setPreviewFormat(ImageFormat.NV21);
            Camera.Size previewSize = chooseSize(parameters.getSupportedPreviewSizes(), CAMERA_WIDTH, CAMERA_HEIGHT);
            if (previewSize != null) {
                parameters.setPreviewSize(previewSize.width, previewSize.height);
                previewWidth = previewSize.width;
                previewHeight = previewSize.height;
            }
            Camera.Size pictureSize = chooseSize(parameters.getSupportedPictureSizes(), CAMERA_WIDTH, CAMERA_HEIGHT);
            if (pictureSize != null) {
                parameters.setPictureSize(pictureSize.width, pictureSize.height);
            }
            parameters.setJpegQuality(97);
            List<String> antibanding = parameters.getSupportedAntibanding();
            if (antibanding != null && antibanding.contains("50hz")) parameters.setAntibanding("50hz");
            List<String> whiteBalance = parameters.getSupportedWhiteBalance();
            if (whiteBalance != null && whiteBalance.contains("auto")) parameters.setWhiteBalance("auto");
            camera.setParameters(parameters);
            Camera.Size actualPreview = parameters.getPreviewSize();
            if (actualPreview != null) {
                previewWidth = actualPreview.width;
                previewHeight = actualPreview.height;
            }

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            rotation = info.orientation;
            camera.setDisplayOrientation(displayOrientation(cameraId, info));
            camera.setPreviewCallback(this);
            camera.startPreview();
            return true;
        } catch (Exception e) {
            stopCamera();
            fail("打开相机失败: " + safeMessage(e), false);
            return false;
        }
    }

    private int chooseCameraId() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return count == 1 ? 0 : 1;
    }

    private static int displayOrientation(int cameraId, Camera.CameraInfo info) {
        int rotation = 0;
        // The activity is portrait; the APK always requests 90 for its preview.
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int result = (info.orientation + rotation) % 360;
            return (360 - result) % 360;
        }
        return (info.orientation - rotation + 360) % 360;
    }

    private static Camera.Size chooseSize(List<Camera.Size> sizes, int width, int height) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        long bestDelta = Long.MAX_VALUE;
        for (Camera.Size size : sizes) {
            long delta = Math.abs((long) size.width - width) + Math.abs((long) size.height - height);
            if (delta < bestDelta) {
                best = size;
                bestDelta = delta;
            }
        }
        return best;
    }

    private void startCountdown() {
        main.removeCallbacks(countdownRunnable);
        main.removeCallbacks(timeoutRunnable);
        // Start a brand-new detection session epoch
        detectionSession.incrementAndGet();
        countdownStep = 5;
        detectionStarted = false;
        submitting = false;
        firstFrameAt = 0L;
        locateFailures.set(0);
        countdownView.setVisibility(View.VISIBLE);
        countdownView.setText(String.valueOf(countdownStep));
        main.postDelayed(countdownRunnable, 500L);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        final long sessionId = detectionSession.get();
        if (data == null || !detectionStarted || finished || submitting || paused) return;
        long now = SystemClock.elapsedRealtime();
        if (firstFrameAt == 0L) {
            firstFrameAt = now;
            return;
        }
        if (now - firstFrameAt < 1000L) return;
        int expectedBytes = previewWidth * previewHeight * 3 / 2;
        if (data.length < expectedBytes) return;
        if (!processing.compareAndSet(false, true)) return;
        final byte[] frame = new byte[expectedBytes];
        System.arraycopy(data, 0, frame, 0, expectedBytes);
        final int frameWidth = previewWidth;
        final int frameHeight = previewHeight;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    processFrame(sessionId, frame, frameWidth, frameHeight);
                } finally {
                    processing.set(false);
                }
            }
        });
    }

    private void processFrame(final long sessionId, byte[] frame, int frameWidth, int frameHeight) {
        if (sessionId != detectionSession.get() || finished || submitting || paused) return;
        try {
            byte[] nv21;
            int width;
            int height;
            if (rotation == 0) {
                nv21 = frame;
                width = frameWidth;
                height = frameHeight;
            } else {
                nv21 = FaceEngine.rotateNv21CounterClockwise(frame, frameWidth, frameHeight);
                width = frameHeight;
                height = frameWidth;
            }

            FaceEngine.DetectResult detect = engine.detect(nv21, width, height);
            final int[] facePos = detect.facePos;
            final int sourceWidth = width;
            final int sourceHeight = height;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (sessionId != detectionSession.get() || finished || paused) return;
                    if (detect.found) overlayView.setFace(facePos, sourceWidth, sourceHeight);
                    else overlayView.clearFace();
                }
            });

            if (!detect.found) {
                handleLocateFailure(sessionId, false);
                return;
            }
            int faceLeft = detect.facePos[2];
            int faceRight = detect.facePos[3];
            if (Math.abs(faceLeft - faceRight) > width / 2) {
                handleLocateFailure(sessionId, true);
                return;
            }

            locateFailures.set(0);
            byte[] currentFeature = engine.extractFeature(nv21, width, height, detect.facePos);
            try {
                byte[] template = templateFeature;
                if (template == null) {
                    throw new FaceEngine.FaceException("TEMPLATE_MISSING", "本地模板已被清理");
                }
                float score = engine.compare(template, currentFeature);
                float maskScore = 0f;
                byte[] mask = maskFeature;
                if (mask != null && mask.length > 0) {
                    maskScore = engine.compare(mask, currentFeature);
                }
                float maxScore = Math.max(score, maskScore);
                if (maxScore < FaceEngine.MATCH_THRESHOLD) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (sessionId != detectionSession.get() || finished || paused) return;
                            fail("本次识别失败", false);
                        }
                    });
                    return;
                }
                boolean masked = false;
                try {
                    masked = engine.hasMask(nv21, width, height, detect.facePos);
                } catch (FaceEngine.FaceException ignored) {
                    // The APK logs mask-check failure and continues.
                }

                // F1: Strictly encode JPEG only for the specific frame that passed native matching
                String framePhoto = FaceEngine.jpegBase64FromNv21(nv21, width, height);
                final RecognizedFrame recognized = new RecognizedFrame(sessionId, framePhoto, masked);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onRecognized(recognized);
                    }
                });
            } finally {
                Arrays.fill(currentFeature, (byte) 0);
            }
        } catch (Exception e) {
            final String message = e.getMessage() == null ? "人脸检测失败" : e.getMessage();
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (sessionId != detectionSession.get() || finished || paused) return;
                    fail(message, false);
                }
            });
        }
    }

    private void handleLocateFailure(final long sessionId, final boolean tooWide) {
        int failures = locateFailures.incrementAndGet();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (sessionId != detectionSession.get() || finished || submitting || paused) return;
                if (tooWide) {
                    setStatus("人脸占比过大，请后退");
                } else {
                    setStatus("未检测到人脸，请正对摄像头");
                }
            }
        });
        if (failures >= 20) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (sessionId != detectionSession.get() || finished || paused) return;
                    fail("人脸定位失败", false);
                }
            });
        }
    }

    private void onRecognized(final RecognizedFrame recognized) {
        if (recognized == null || recognized.sessionId != detectionSession.get()
                || finished || submitting || paused) {
            return;
        }
        submitting = true;
        detectionStarted = false;
        main.removeCallbacks(timeoutRunnable);
        countdownView.setVisibility(View.GONE);
        stopCamera();
        setStatus("识别成功，正在提交...");
        submitAsync(recognized);
    }

    private void submitAsync(final RecognizedFrame frame) {
        if (frame == null || frame.photoBase64 == null || frame.photoBase64.isEmpty()) {
            fail("未生成打卡照片", false);
            return;
        }
        requestSent.set(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (frame.sessionId != detectionSession.get() || finished || paused) {
                    return;
                }
                try {
                    Protocol.Times times = Protocol.resolveTimes(
                            testMode ? "custom" : "apk", customTime, new Date());
                    if (recordTcpPort > 0) {
                        String host = new URI(baseUrl).getHost();
                        if (host != null && host.startsWith("[") && host.endsWith("]")) {
                            host = host.substring(1, host.length() - 1);
                        }
                        Sm2Config config = Sm2ConfigLoader.load(PunchActivity.this);
                        String tcpTime = testMode ? times.addCardTime : Protocol.formatLocalDateTime(new Date());
                        PostMobileRecord record = new PostMobileRecord(
                                frame.photoBase64, employId, tcpTime, gps, lat, lng,
                                serverSpecifiedMap, 0, Build.VERSION.SDK_INT);
                        String response = new TcpPunchClient().post(
                                host, recordTcpPort, record, config, activeTcpSocket, requestSent);
                        if (frame.sessionId != detectionSession.get() || finished || paused) return;

                        String punchTime = TcpPunchClient.extractPunchTime(response);
                        final String displayTime = (punchTime == null || punchTime.isEmpty())
                                ? "服务端已受理" : punchTime;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (frame.sessionId != detectionSession.get() || finished) return;
                                showResult("打卡成功",
                                        "通道：TCP 加密\n"
                                                + "服务端时间：" + displayTime + "\n"
                                                + "打卡地点：" + gps + "\n"
                                                + "口罩：" + (frame.hasMask ? "是" : "否"),
                                        true, false);
                            }
                        });
                    } else {
                        AddCardResult result = new HttpApi().addCard(
                                baseUrl, token, times.addCardTime, gps, lat, lng, frame.photoBase64,
                                serverSpecifiedMap, 0, Build.VERSION.SDK_INT, activeHttpCall);
                        if (frame.sessionId != detectionSession.get() || finished || paused) return;

                        final String punchTime = result.punchTime == null || result.punchTime.isEmpty()
                                ? "服务端已受理" : result.punchTime;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (frame.sessionId != detectionSession.get() || finished) return;
                                showResult("打卡成功",
                                        "通道：HTTP\n"
                                                + "服务端时间：" + punchTime + "\n"
                                                + "打卡地点：" + gps + "\n"
                                                + "口罩：" + (frame.hasMask ? "是" : "否"),
                                        true, false);
                            }
                        });
                    }
                } catch (Exception e) {
                    if (frame.sessionId != detectionSession.get() || finished) return;
                    final boolean sent = requestSent.get();
                    final String rawMessage = e.getMessage() == null ? "上传考勤信息失败" : e.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (frame.sessionId != detectionSession.get() || finished) return;
                            if (sent) {
                                showResult("考勤异常",
                                        "打卡请求已发出，但等待服务器确认时连接中断或超时: " + rawMessage
                                                + "\n服务端可能已经记录本次打卡，请在管理端核对，切勿盲目重复提交。\n地点：" + gps,
                                        false, true);
                            } else {
                                fail("上传考勤信息失败: " + rawMessage, false);
                            }
                        }
                    });
                } finally {
                    submitting = false;
                }
            }
        });
    }

    private void cancelPunch() {
        if (finished) {
            finish();
            return;
        }
        detectionSession.incrementAndGet();
        abortActiveNetwork();
        boolean sent = requestSent.get();
        if (sent) {
            showResult("已取消", "操作已取消。打卡数据此前已向服务端发送，服务端是否已记录结果未知，请在管理端核对。", false, true);
        } else {
            finish();
        }
    }

    private void abortActiveNetwork() {
        Call call = activeHttpCall.getAndSet(null);
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {
            }
        }
        Socket socket = activeTcpSocket.getAndSet(null);
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void fail(String message, boolean maybeCommitted) {
        if (finished) return;
        detectionSession.incrementAndGet();
        detectionStarted = false;
        submitting = false;
        main.removeCallbacks(countdownRunnable);
        main.removeCallbacks(timeoutRunnable);
        stopCamera();
        overlayView.clearFace();
        abortActiveNetwork();
        showResult("考勤失败", message, false, maybeCommitted);
    }

    private void showResult(String title, String message, boolean success, boolean maybeCommitted) {
        finished = true;
        submitting = false;
        detectionStarted = false;
        main.removeCallbacks(countdownRunnable);
        main.removeCallbacks(timeoutRunnable);
        stopCamera();
        countdownView.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);
        resultTitle.setText(title);
        resultTitle.setTextColor(success ? 0xFF1B5E20 : 0xFFB71C1C);
        String suffix = testMode ? "\n测试模式：地点和时间来自手动输入" : "";
        resultMessage.setText((message == null ? "" : message) + suffix);

        if (success) {
            setStatus("提交完成");
        } else if (maybeCommitted) {
            setStatus("结果未知（已发出请求）");
        } else {
            setStatus("未提交");
        }
    }

    private void stopCamera() {
        Camera current = camera;
        camera = null;
        if (current == null) return;
        try {
            current.setPreviewCallback(null);
            current.stopPreview();
            current.release();
        } catch (Exception ignored) {
        }
    }

    private void setStatus(String text) {
        statusView.setText(text == null ? "" : text);
    }

    private void setImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted) {
            startInitialization();
        } else {
            fail("未授予相机权限", false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        detectionStarted = false;
        firstFrameAt = 0L;
        // Invalidate current detection session
        detectionSession.incrementAndGet();
        main.removeCallbacks(countdownRunnable);
        main.removeCallbacks(timeoutRunnable);
        stopCamera();
        if (!finished) setStatus("已暂停，返回后重新检测");
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        if (!finished && engineReady && surfaceReady) {
            startCameraIfReady();
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
        detectionSession.incrementAndGet();
        main.removeCallbacksAndMessages(null);
        stopCamera();
        abortActiveNetwork();
        worker.shutdownNow();
        engine.release();

        // F7: Secure zeroing and memory cleanup
        if (activeKeyCode != null) {
            Arrays.fill(activeKeyCode, (byte) 0);
            activeKeyCode = null;
        }
        if (templateFeature != null) {
            Arrays.fill(templateFeature, (byte) 0);
            templateFeature = null;
        }
        if (maskFeature != null) {
            Arrays.fill(maskFeature, (byte) 0);
            maskFeature = null;
        }
        token = null;
        employId = null;

        super.onDestroy();
    }
}
