package com.hanwang.facekey.thin.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.YuvImage;

import com.hanwang.facekey.thin.protocol.Base64Codec;
import com.hanvon.faceRec.FaceCoreHelper;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Thin wrapper around the Hanvon native face SDK. It mirrors the source APK's
 * FaceDetectActivity: NV21 location, feature extraction, template comparison and
 * mask judgement.
 */
public final class FaceEngine {
    public static final int HW_OK = 0;
    public static final int HW_SDK_CLOUDKEYCODE_ERROR = -31;
    public static final int FACE_POS_LENGTH = 228;
    public static final int CAMERA_WIDTH = 640;
    public static final int CAMERA_HEIGHT = 480;
    public static final double MATCH_THRESHOLD = 0.65d;
    public static final double MASK_THRESHOLD = 0.999d;

    private final long[] handle = new long[4];
    private boolean initialized;

    public synchronized void init(byte[] keyCode, Context context) throws FaceException {
        if (initialized) return;
        if (keyCode == null || keyCode.length == 0) {
            throw new FaceException("KEYCODE_MISSING", "未找到 KeyCode，请导入或从授权服务获取");
        }
        int result;
        try {
            result = FaceCoreHelper.HwInitFace(handle, keyCode, context);
        } catch (Throwable t) {
            throw new FaceException("NATIVE_LOAD_FAILED", "加载汉王人脸 SDK 失败: " + t.getClass().getSimpleName(), t);
        }
        if (result == HW_SDK_CLOUDKEYCODE_ERROR) {
            throw new FaceException("KEYCODE_REJECTED", "KeyCode 无效或授权已过期（-31）");
        }
        if (result != HW_OK) {
            throw new FaceException("INIT_FAILED", "人脸 SDK 初始化失败（" + result + "）");
        }
        initialized = true;
    }

    public synchronized DetectResult detect(byte[] nv21, int width, int height) throws FaceException {
        requireInitialized();
        int[] faceCount = new int[]{1};
        int[] facePos = new int[FACE_POS_LENGTH];
        int result = FaceCoreHelper.HwDetectMultiFaceAndEyeEx(
                handle, nv21, width, height, facePos, faceCount);
        if (result != HW_OK) {
            return new DetectResult(false, result, faceCount[0], facePos);
        }
        return new DetectResult(true, 0, faceCount[0], facePos);
    }

    public synchronized byte[] extractFeature(byte[] nv21, int width, int height, int[] facePos)
            throws FaceException {
        requireInitialized();
        int[] size = new int[1];
        int sizeResult = FaceCoreHelper.HwGetFeatureSize(handle, size);
        if (sizeResult != HW_OK || size[0] <= 0) {
            throw new FaceException("FEATURE_SIZE_FAILED", "读取人脸特征长度失败（" + sizeResult + "）");
        }
        byte[] feature = new byte[size[0]];
        int result = FaceCoreHelper.HwGetFaceFeatureEx(
                handle, nv21, width, height, facePos, feature);
        if (result != HW_OK) {
            throw new FaceException("FEATURE_EXTRACT_FAILED", "提取当前帧人脸模板失败（" + result + "）");
        }
        return feature;
    }

    public synchronized float compare(byte[] feature1, byte[] feature2) throws FaceException {
        requireInitialized();
        float[] score = new float[1];
        int result = FaceCoreHelper.HwCompareFeature(handle, feature1, feature2, score);
        if (result != HW_OK) {
            throw new FaceException("COMPARE_FAILED", "人脸模板比对失败（" + result + "）");
        }
        return score[0];
    }

    public synchronized boolean hasMask(byte[] nv21, int width, int height, int[] facePos)
            throws FaceException {
        requireInitialized();
        byte[] rgb = new byte[width * height * 3];
        int rgbResult = FaceCoreHelper.HwNV21TORGB(width, height, nv21, rgb);
        int[] result = new int[1];
        float[] score = new float[2];
        int judgeResult = FaceCoreHelper.HwJudgeMask(
                handle, rgb, width, height, facePos, result, score);
        if (rgbResult != HW_OK || judgeResult != HW_OK) {
            throw new FaceException("MASK_CHECK_FAILED", "口罩检测核心调用失败");
        }
        return result[0] == 1 && score[1] > MASK_THRESHOLD;
    }

    /** Mirrors CheckFeatureUtils.readFeatureFromBase64; returns null when no face is found. */
    public synchronized byte[] featureFromBase64(String base64) throws FaceException {
        if (base64 == null || base64.trim().isEmpty()) return null;
        byte[] jpeg;
        try {
            jpeg = Base64Codec.decode(base64);
        } catch (IllegalArgumentException e) {
            throw new FaceException("TEMPLATE_BASE64_INVALID", "人脸模板不是有效的 Base64 数据");
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) {
            throw new FaceException("TEMPLATE_IMAGE_INVALID", "人脸模板不是有效的 JPEG/PNG 图片");
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] gray = rgbToGrayBytes(pixels, width, height);
            DetectResult detect = detect(gray, width, height);
            if (!detect.found) return null;
            return extractFeature(gray, width, height, detect.facePos);
        } finally {
            bitmap.recycle();
        }
    }

    /** Mirrors FaceDetectActivity: YuvImage(NV21, quality 80) then Android Base64.DEFAULT. */
    public static String jpegBase64FromNv21(byte[] nv21, int width, int height) {
        YuvImage image = new YuvImage(nv21, 17, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!image.compressToJpeg(new Rect(0, 0, width, height), 80, out)) {
            throw new IllegalStateException("NV21 to JPEG compression failed");
        }
        return Base64Codec.encodeAndroid(out.toByteArray());
    }

    /** Exact port of FuncUtil.rotateYUV240SP_AntiClockwise. */
    public static byte[] rotateNv21CounterClockwise(byte[] source, int width, int height) {
        byte[] target = new byte[source.length];
        int wh = width * height;
        int k = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                target[k++] = source[(((width * j) + width) - i) - 1];
            }
        }
        for (int i = 0; i < width; i += 2) {
            for (int j = 0; j < height / 2; j++) {
                target[k + 1] = source[((((width * j) + wh) + width) - i) - 1];
                target[k] = source[((((width * j) + wh) + width) - (i + 1)) - 1];
                k += 2;
            }
        }
        return target;
    }

    /** Exact port of CheckFeatureUtils.rgbToGrayBytes (average of R, G, B). */
    public static byte[] rgbToGrayBytes(int[] pixels, int width, int height) {
        byte[] gray = new byte[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = width * y + x;
                int color = pixels[index];
                int red = (color & 0xff0000) >> 16;
                int green = (color & 0xff00) >> 8;
                int blue = color & 0xff;
                int value = (red + green + blue) / 3;
                gray[index] = (byte) (((value << 16) | 0xff000000 | (value << 8) | value) & 0xff);
            }
        }
        return gray;
    }

    public synchronized void release() {
        if (!initialized) return;
        try {
            FaceCoreHelper.HwReleaseFace(handle);
        } catch (Throwable ignored) {
        }
        Arrays.fill(handle, 0L);
        initialized = false;
    }

    private void requireInitialized() throws FaceException {
        if (!initialized) throw new FaceException("NOT_INITIALIZED", "人脸 SDK 尚未初始化");
    }

    public static final class DetectResult {
        public final boolean found;
        public final int resultCode;
        public final int faceCount;
        public final int[] facePos;

        DetectResult(boolean found, int resultCode, int faceCount, int[] facePos) {
            this.found = found;
            this.resultCode = resultCode;
            this.faceCount = faceCount;
            this.facePos = facePos;
        }
    }

    public static final class FaceException extends Exception {
        public final String code;

        public FaceException(String code, String message) {
            super(message);
            this.code = code;
        }

        public FaceException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }
}
