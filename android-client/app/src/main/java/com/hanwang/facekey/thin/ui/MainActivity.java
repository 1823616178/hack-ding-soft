package com.hanwang.facekey.thin.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hanwang.facekey.thin.config.AppConfig;
import com.hanwang.facekey.thin.crypto.Sm2ConfigLoader;
import com.hanwang.facekey.thin.face.KeyCodeManager;
import com.hanwang.facekey.thin.face.TemplateStore;
import com.hanwang.facekey.thin.location.LocationHelper;
import com.hanwang.facekey.thin.net.CheckLocationResult;
import com.hanwang.facekey.thin.net.FeatureResult;
import com.hanwang.facekey.thin.net.HttpApi;
import com.hanwang.facekey.thin.net.LoginResult;
import com.hanwang.facekey.thin.net.Session;
import com.hanwang.facekey.thin.protocol.Protocol;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

/**
 * Modernized Thin Client Activity.
 * Aesthetic Material Cards, Interactive Map Selection, Multi-server Support, and Hardened Lifecycle.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 31;
    private static final int REQUEST_KEYCODE = 32;
    private static final int REQUEST_PHONE_STATE = 33;
    private static final int REQUEST_MAP_PICKER = 34;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final HttpApi api = new HttpApi();
    private final List<View> busyViews = new ArrayList<>();
    private final AtomicLong sessionEpoch = new AtomicLong(0);
    private final AtomicReference<Call> activeHttpCall = new AtomicReference<>(null);

    private AppConfig config;
    private Session session;
    private LocationHelper.Fix lastFix;
    private String sessionBaseUrl;
    private String lastCheckTime;
    private boolean lastFixManual;
    private boolean pendingLocation;

    private EditText serverInput;
    private EditText userInput;
    private EditText pwdInput;
    private EditText gpsInput;
    private EditText latInput;
    private EditText lngInput;
    private EditText timeInput;
    private EditText tiandituInput;
    private CheckBox testModeCheck;
    private CheckBox consentCheck;
    private TextView sessionStatus;
    private TextView templateStatus;
    private TextView keycodeStatus;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = AppConfig.load(this);
        setContentView(buildLayout());
        fillConfig();
        updateSessionStatus();
        updateTemplateStatus();
        updateKeyCodeStatus();
        setStatus("系统准备就绪。默认服务器: " + config.serverUrl);
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF1F5F9); // Light Slate

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Header Hero Card
        buildHeader(root);

        // 2. Server & Account Card
        buildAccountCard(root);

        // 3. Location & Map Picker Card
        buildLocationCard(root);

        // 4. Face Recognition Card
        buildPunchCard(root);

        // 5. Console & Status Log Card
        buildConsoleCard(root);

        setManualFieldsEnabled(false);
        return scroll;
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(8), dp(4), dp(8), dp(12));

        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(this);
        tag.setText(" eFaceGo Thin 3.0 ");
        tag.setTextSize(11f);
        tag.setTextColor(0xFF1E40AF);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(0xFFDBEAFE);
        tagBg.setCornerRadius(dp(12));
        tag.setBackground(tagBg);
        tag.setPadding(dp(8), dp(2), dp(8), dp(2));
        badgeRow.addView(tag);

        header.addView(badgeRow);

        TextView title = new TextView(this);
        title.setText("智能考勤终端");
        title.setTextSize(22f);
        title.setTextColor(0xFF0F172A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), 0, 0);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("授权专用 · 交互式地图选点 · 国密SM2/4安全打卡");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(0xFF64748B);
        header.addView(subtitle);

        // Security Banner
        LinearLayout warnBanner = new LinearLayout(this);
        warnBanner.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(0xFFFEF2F2);
        warnBg.setCornerRadius(dp(8));
        warnBg.setStroke(dp(1), 0xFFFECACA);
        warnBanner.setBackground(warnBg);
        warnBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams warnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warnLp.setMargins(0, dp(10), 0, 0);
        warnBanner.setLayoutParams(warnLp);

        TextView warnText = new TextView(this);
        warnText.setText("提示：本客户端严格遵循协议规范，所有定位与打卡均由服务端判定。");
        warnText.setTextSize(12f);
        warnText.setTextColor(0xFF991B1B);
        warnBanner.addView(warnText);

        header.addView(warnBanner);
        root.addView(header);
    }

    private void buildAccountCard(LinearLayout root) {
        LinearLayout card = createCard(root, "🖥️ 服务器与认证", "配置考勤目标服务器与员工账号");

        serverInput = addStyledInput(card, "服务器地址 (URL)", "http://223.223.177.153:7300", InputType.TYPE_TEXT_VARIATION_URI);
        userInput = addStyledInput(card, "员工工号 / 用户名", "例如: 1001", InputType.TYPE_CLASS_TEXT);
        pwdInput = addStyledInput(card, "登录密码", "仅用于单次认证，不持久化保存", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        consentCheck = new CheckBox(this);
        consentCheck.setText("我确认在明确授权的考勤与测试环境中使用");
        consentCheck.setTextSize(12f);
        consentCheck.setTextColor(0xFF475569);
        consentCheck.setPadding(dp(4), dp(6), 0, dp(6));
        card.addView(consentCheck);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(8), 0, dp(8));

        Button saveBtn = createButton(btnRow, "💾 保存配置", 0xFFF1F5F9, 0xFF0F172A, v -> saveConfig());
        saveBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1f));
        busyViews.add(saveBtn);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        btnRow.addView(gap);

        Button loginBtn = createButton(btnRow, "🚀 登录并同步模板", 0xFF2563EB, 0xFFFFFFFF, v -> loginAndDownload());
        loginBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1.3f));
        busyViews.add(loginBtn);

        card.addView(btnRow);

        // Status lines container
        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xFFF8FAFC);
        boxBg.setCornerRadius(dp(8));
        boxBg.setStroke(dp(1), 0xFFE2E8F0);
        statusBox.setBackground(boxBg);
        statusBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(0, dp(4), 0, dp(8));
        statusBox.setLayoutParams(boxLp);

        sessionStatus = addCardStatusLine(statusBox, "会话: 未登录");
        templateStatus = addCardStatusLine(statusBox, "人脸模板: 未下载");
        keycodeStatus = addCardStatusLine(statusBox, "KeyCode: 检查中");

        card.addView(statusBox);

        // Clear button
        Button clearBtn = createButton(card, "🧹 清除本地会话与模板", 0xFFFFF1F2, 0xFFE11D48, v -> confirmClear());
        clearBtn.setTextSize(12f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        clearBtn.setLayoutParams(clp);
        busyViews.add(clearBtn);
    }

    private void buildLocationCard(LinearLayout root) {
        LinearLayout card = createCard(root, "📍 定位与地图选点", "支持实时 GPS 定位或直接在地图上选点");

        LinearLayout mapActionRow = new LinearLayout(this);
        mapActionRow.setOrientation(LinearLayout.HORIZONTAL);
        mapActionRow.setPadding(0, 0, 0, dp(8));

        Button mapPickBtn = createButton(mapActionRow, "🗺️ 地图可视化选点", 0xFF0D9488, 0xFFFFFFFF, v -> openMapPicker());
        mapPickBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1.2f));
        busyViews.add(mapPickBtn);

        View mapGap = new View(this);
        mapGap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        mapActionRow.addView(mapGap);

        Button gpsBtn = createButton(mapActionRow, "🛰️ 手机GPS定位", 0xFF4F46E5, 0xFFFFFFFF, v -> ensureLocationAndCheck());
        gpsBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        busyViews.add(gpsBtn);

        card.addView(mapActionRow);

        gpsInput = addStyledInput(card, "打卡地点名称", "点击上方【地图选点】或【GPS定位】自动获取", InputType.TYPE_CLASS_TEXT);

        LinearLayout coordRow = new LinearLayout(this);
        coordRow.setOrientation(LinearLayout.HORIZONTAL);
        latInput = addStyledInput(coordRow, "纬度 (Lat)", "39.9042", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        latInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View coordGap = new View(this);
        coordGap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        coordRow.addView(coordGap);

        lngInput = addStyledInput(coordRow, "经度 (Lng)", "116.4074", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        lngInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(coordRow);

        timeInput = addStyledInput(card, "自定义打卡时间 (可选)", "yyyy-MM-dd HH:mm:ss (测试模式使用)", InputType.TYPE_CLASS_TEXT);

        tiandituInput = addStyledInput(card, "天地图 Web Key", "用于逆地理反查中文地址", InputType.TYPE_CLASS_TEXT);

        testModeCheck = new CheckBox(this);
        testModeCheck.setText("测试微调模式：允许手动修改地点坐标与时间");
        testModeCheck.setTextSize(12f);
        testModeCheck.setTextColor(0xFF475569);
        testModeCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setManualFieldsEnabled(isChecked);
            if (isChecked && timeInput.getText().toString().trim().isEmpty()) {
                timeInput.setText(Protocol.formatLocalDateTime(new Date()));
            }
        });
        card.addView(testModeCheck);

        // Pre-check location button
        Button checkLocBtn = createButton(card, "🔍 预检当前定位有效性 (Check Location)", 0xFF0284C7, 0xFFFFFFFF, v -> checkLocation());
        LinearLayout.LayoutParams chkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        chkLp.setMargins(0, dp(8), 0, dp(6));
        checkLocBtn.setLayoutParams(chkLp);
        busyViews.add(checkLocBtn);
    }

    private void buildPunchCard(LinearLayout root) {
        LinearLayout card = createCard(root, "👤 人脸识别与打卡", "核对人脸特征并提交服务端");

        LinearLayout keyActionRow = new LinearLayout(this);
        keyActionRow.setOrientation(LinearLayout.HORIZONTAL);
        keyActionRow.setPadding(0, 0, 0, dp(8));

        Button importKey = createButton(keyActionRow, "📄 导入 KeyCode", 0xFFF1F5F9, 0xFF0F172A, v -> importKeyCode());
        importKey.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
        busyViews.add(importKey);

        View kGap = new View(this);
        kGap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        keyActionRow.addView(kGap);

        Button vendorKey = createButton(keyActionRow, "🌐 在线申请 KeyCode", 0xFFF1F5F9, 0xFF0F172A, v -> confirmVendorKeyCode());
        vendorKey.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
        busyViews.add(vendorKey);

        card.addView(keyActionRow);

        Button punchBtn = createButton(card, "📷 开始人脸识别并打卡", 0xFF059669, 0xFFFFFFFF, v -> startPunch());
        punchBtn.setTextSize(16f);
        LinearLayout.LayoutParams punchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        punchLp.setMargins(0, dp(4), 0, dp(4));
        punchBtn.setLayoutParams(punchLp);
        busyViews.add(punchBtn);

        TextView punchTip = new TextView(this);
        punchTip.setText("• 支持活体比对与国密加密通道传输\n• 打卡前请确保已完成【登录并同步模板】与【定位获取】");
        punchTip.setTextSize(12f);
        punchTip.setTextColor(0xFF64748B);
        punchTip.setPadding(dp(4), dp(4), dp(4), 0);
        card.addView(punchTip);
    }

    private void buildConsoleCard(LinearLayout root) {
        LinearLayout card = createCard(root, "📋 运行日志与控制台", "查看实时交互记录与系统响应");

        statusView = new TextView(this);
        statusView.setText("准备就绪");
        statusView.setTextSize(12f);
        statusView.setTextColor(0xFF38BDF8); // Tech cyan
        statusView.setTypeface(Typeface.MONOSPACE);

        GradientDrawable consoleBg = new GradientDrawable();
        consoleBg.setColor(0xFF0F172A); // Dark slate
        consoleBg.setCornerRadius(dp(8));
        statusView.setBackground(consoleBg);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout.LayoutParams consLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        consLp.setMargins(0, dp(4), 0, 0);
        card.addView(statusView, consLp);
    }

    private LinearLayout createCard(LinearLayout parent, String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);

        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(2));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(8));
        card.setLayoutParams(lp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16f);
        tvTitle.setTextColor(0xFF0F172A);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(this);
            tvSub.setText(subtitle);
            tvSub.setTextSize(12f);
            tvSub.setTextColor(0xFF64748B);
            tvSub.setPadding(0, dp(2), 0, dp(10));
            card.addView(tvSub);
        }

        parent.addView(card);
        return card;
    }

    private EditText addStyledInput(ViewGroup parent, String labelText, String hint, int inputType) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(4), 0, dp(6));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(12f);
        label.setTextColor(0xFF334155);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, 0, 0, dp(4));
        container.addView(label);

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(0xFF94A3B8);
        input.setTextColor(0xFF0F172A);
        input.setTextSize(14f);
        input.setInputType(inputType);

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFFF8FAFC);
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), 0xFFCBD5E1);
        input.setBackground(inputBg);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));

        container.addView(input);
        parent.addView(container);
        return input;
    }

    private Button createButton(ViewGroup parent, String text, int bgColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(13f);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(bgColor);
        btnBg.setCornerRadius(dp(8));
        if (bgColor == 0xFFF1F5F9 || bgColor == 0xFFFFF1F2) {
            btnBg.setStroke(dp(1), 0xFFCBD5E1);
        }
        btn.setBackground(btnBg);
        btn.setOnClickListener(listener);
        parent.addView(btn);
        return btn;
    }

    private TextView addCardStatusLine(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF475569);
        tv.setPadding(0, dp(2), 0, dp(2));
        parent.addView(tv);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void fillConfig() {
        serverInput.setText(config.serverUrl);
        userInput.setText(config.userName);
        tiandituInput.setText(config.tiandituKey);
        testModeCheck.setChecked(config.testMode);
        setManualFieldsEnabled(config.testMode);
    }

    private void setManualFieldsEnabled(boolean enabled) {
        gpsInput.setEnabled(enabled);
        latInput.setEnabled(enabled);
        lngInput.setEnabled(enabled);
        timeInput.setEnabled(enabled);
        gpsInput.setAlpha(enabled ? 1f : 0.45f);
        latInput.setAlpha(enabled ? 1f : 0.45f);
        lngInput.setAlpha(enabled ? 1f : 0.45f);
        timeInput.setAlpha(enabled ? 1f : 0.45f);
    }

    private void abortActiveNetwork() {
        Call call = activeHttpCall.getAndSet(null);
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private void saveConfig() {
        try {
            String normalized = Protocol.normalizeBaseUrl(serverInput.getText().toString());
            boolean serverChanged = sessionBaseUrl != null && !sessionBaseUrl.equals(normalized);
            config.serverUrl = normalized;
            config.userName = userInput.getText().toString().trim();
            config.testMode = testModeCheck.isChecked();
            config.tiandituKey = tiandituInput.getText().toString().trim();
            config.save(this);
            serverInput.setText(config.serverUrl);
            if (serverChanged) {
                sessionEpoch.incrementAndGet();
                abortActiveNetwork();
                if (session != null) session.clear();
                session = null;
                sessionBaseUrl = null;
                lastFix = null;
                lastFixManual = false;
                lastCheckTime = null;
                TemplateStore.clear(this);
                updateSessionStatus();
                updateTemplateStatus();
                setStatus("服务器已变更，请重新登录并下载模板。");
            } else {
                setStatus("配置已保存。密码和 token 不会写入磁盘。");
            }
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            setStatus("保存失败：" + e.getMessage());
        }
    }

    private void saveConfigQuietly() {
        try {
            config.serverUrl = Protocol.normalizeBaseUrl(serverInput.getText().toString());
            config.userName = userInput.getText().toString().trim();
            config.testMode = testModeCheck.isChecked();
            config.tiandituKey = tiandituInput.getText().toString().trim();
            config.save(this);
        } catch (Exception ignored) {
        }
    }

    private void loginAndDownload() {
        if (!consentCheck.isChecked()) {
            setStatus("请先勾选授权测试确认。");
            Toast.makeText(this, "请勾选授权确认", Toast.LENGTH_SHORT).show();
            return;
        }
        final String baseUrl;
        final String userName;
        final String pwd;
        try {
            baseUrl = Protocol.normalizeBaseUrl(serverInput.getText().toString());
            userName = userInput.getText().toString().trim();
            pwd = pwdInput.getText().toString();
            Protocol.loginFields(userName, pwd);
        } catch (Exception e) {
            setStatus("输入无效：" + e.getMessage());
            return;
        }
        saveConfigQuietly();

        final long currentEpoch = sessionEpoch.incrementAndGet();
        abortActiveNetwork();

        if (session != null) session.clear();
        session = null;
        sessionBaseUrl = null;
        lastFix = null;
        lastFixManual = false;
        lastCheckTime = null;
        TemplateStore.clear(this);
        updateSessionStatus();
        updateTemplateStatus();
        setBusy(true, "登录中...");

        worker.execute(() -> {
            try {
                LoginResult login = api.login(baseUrl, userName, pwd, activeHttpCall);
                if (currentEpoch != sessionEpoch.get()) return;

                session = new Session(login.token, login.employId, login.mapGaode, login.recordTcpPort);
                sessionBaseUrl = baseUrl;
                main.post(() -> {
                    if (currentEpoch != sessionEpoch.get()) return;
                    updateSessionStatus();
                    if (pwdInput != null) pwdInput.setText("");
                    setStatus("登录成功，正在下载员工人脸模板...");
                });

                FeatureResult feature = api.downloadFeature(baseUrl, login.token, activeHttpCall);
                if (currentEpoch != sessionEpoch.get()) return;

                TemplateStore.save(MainActivity.this, feature.picStr, feature.picMaskStr);
                main.post(() -> {
                    if (currentEpoch != sessionEpoch.get()) return;
                    updateTemplateStatus();
                    setBusy(false, "登录成功，模板已下载。通道=" + login.transport.toUpperCase());
                    Toast.makeText(MainActivity.this, "登录与模板同步成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (currentEpoch != sessionEpoch.get()) return;
                main.post(() -> {
                    if (currentEpoch != sessionEpoch.get()) return;
                    setBusy(false, "登录或下载模板失败：" + message(e));
                });
            }
        });
    }

    private void openMapPicker() {
        Intent intent = new Intent(this, MapPickerActivity.class);
        String currentLat = latInput != null ? latInput.getText().toString().trim() : "";
        String currentLng = lngInput != null ? lngInput.getText().toString().trim() : "";
        String currentAddress = gpsInput != null ? gpsInput.getText().toString().trim() : "";
        String tiandituKey = tiandituInput != null ? tiandituInput.getText().toString().trim() : "";
        if (!currentLat.isEmpty()) intent.putExtra(MapPickerActivity.EXTRA_INITIAL_LAT, currentLat);
        if (!currentLng.isEmpty()) intent.putExtra(MapPickerActivity.EXTRA_INITIAL_LNG, currentLng);
        if (!currentAddress.isEmpty()) intent.putExtra(MapPickerActivity.EXTRA_INITIAL_ADDRESS, currentAddress);
        if (!tiandituKey.isEmpty()) intent.putExtra(MapPickerActivity.EXTRA_TIANDITU_KEY, tiandituKey);
        startActivityForResult(intent, REQUEST_MAP_PICKER);
    }

    private void ensureLocationAndCheck() {
        if (session == null || session.token == null) {
            setStatus("请先登录。");
            return;
        }
        if (!testModeCheck.isChecked()
                && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingLocation = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_LOCATION);
            return;
        }
        checkLocation();
    }

    private void checkLocation() {
        if (session == null || session.token == null) {
            setStatus("请先登录。");
            return;
        }
        lastFix = null;
        lastCheckTime = null;
        final boolean manual = testModeCheck.isChecked();
        final String baseUrl;
        try {
            baseUrl = sessionBaseUrl != null
                    ? sessionBaseUrl
                    : Protocol.normalizeBaseUrl(serverInput.getText().toString());
        } catch (Exception e) {
            setStatus("服务器地址无效：" + e.getMessage());
            return;
        }

        final long currentEpoch = sessionEpoch.get();
        setBusy(true, manual ? "使用测试地点进行定位校验..." : "正在获取 Android 定位...");
        worker.execute(() -> {
            try {
                LocationHelper.Fix fix;
                if (manual) {
                    fix = LocationHelper.manual(
                            gpsInput.getText().toString(), latInput.getText().toString(), lngInput.getText().toString());
                    Protocol.validateCustomTime(timeInput.getText().toString());
                } else {
                    fix = LocationHelper.requestFix(MainActivity.this, 15000L,
                            tiandituInput.getText().toString().trim());
                }
                Protocol.Times times = Protocol.resolveTimes(
                        manual ? "custom" : "apk",
                        timeInput.getText().toString(),
                        new Date());
                CheckLocationResult result = api.checkLocation(
                        baseUrl, session.token, fix.address, fix.lat, fix.lng, times.checkLocationTime, activeHttpCall);

                if (currentEpoch != sessionEpoch.get()) return;

                lastFix = fix;
                lastFixManual = manual;
                lastCheckTime = times.checkLocationTime;
                if (result.mapGaodePresent) {
                    session.mapGaode = result.mapGaode;
                }
                main.post(() -> {
                    if (currentEpoch != sessionEpoch.get()) return;
                    gpsInput.setText(fix.address);
                    latInput.setText(fix.lat);
                    lngInput.setText(fix.lng);
                    setBusy(false, "定位校验通过。"
                            + (manual ? "\n当前为测试地点/时间，UI 已标记。" : "")
                            + "\n地点：" + fix.address
                            + "\nlat=" + fix.lat + " lng=" + fix.lng
                            + "\nmapGaode=" + result.mapGaode);
                });
            } catch (Exception e) {
                if (currentEpoch != sessionEpoch.get()) return;
                main.post(() -> {
                    if (currentEpoch != sessionEpoch.get()) return;
                    setBusy(false, "定位校验失败：" + message(e));
                });
            }
        });
    }

    private void startPunch() {
        if (!consentCheck.isChecked()) {
            setStatus("请先勾选授权测试确认。");
            return;
        }
        if (session == null || session.token == null) {
            setStatus("请先登录。");
            return;
        }
        if (sessionBaseUrl == null) {
            setStatus("会话缺少服务器地址，请重新登录。");
            return;
        }
        if (lastFix == null) {
            setStatus("请先完成定位校验。");
            return;
        }
        if (!TemplateStore.hasStandard(this)) {
            setStatus("本地没有模板，请重新登录下载模板。");
            return;
        }
        try {
            if (KeyCodeManager.load(this) == null) {
                setStatus("未找到 KeyCode。请导入厂商 KeyCode 或从授权服务获取。");
                return;
            }
        } catch (Exception e) {
            setStatus("读取 KeyCode 失败：" + e.getMessage());
            return;
        }
        if (lastFixManual != testModeCheck.isChecked()) {
            setStatus("定位模式已切换，请重新进行定位校验。");
            return;
        }
        if (session.recordTcpPort > 0 && !Sm2ConfigLoader.available(this)) {
            setStatus("登录要求 TCP 通道，但本机缺少 sm2.config.local.json。");
            return;
        }
        String customTime = timeInput.getText().toString();
        Protocol.Times punchTimes;
        try {
            punchTimes = Protocol.resolveTimes(
                    testModeCheck.isChecked() ? "custom" : "apk", customTime, new Date());
        } catch (Exception e) {
            setStatus("时间无效：" + e.getMessage());
            return;
        }
        if (testModeCheck.isChecked()) {
            try {
                String currentGps = gpsInput.getText().toString();
                String currentLat = Protocol.formatCoordinate(latInput.getText().toString(), "lat");
                String currentLng = Protocol.formatCoordinate(lngInput.getText().toString(), "lng");
                if (!lastFix.address.equals(currentGps)
                        || !lastFix.lat.equals(currentLat)
                        || !lastFix.lng.equals(currentLng)
                        || lastCheckTime == null
                        || !lastCheckTime.equals(punchTimes.checkLocationTime)) {
                    setStatus("测试地点或时间已修改，请重新进行定位校验。");
                    return;
                }
            } catch (Exception e) {
                setStatus("测试地点无效：" + e.getMessage());
                return;
            }
        }

        Intent intent = new Intent(this, PunchActivity.class);
        intent.putExtra(PunchActivity.EXTRA_BASE_URL, sessionBaseUrl);
        intent.putExtra(PunchActivity.EXTRA_TOKEN, session.token);
        intent.putExtra(PunchActivity.EXTRA_EMPLOY_ID, session.employId);
        intent.putExtra(PunchActivity.EXTRA_TCP_PORT, session.recordTcpPort);
        intent.putExtra(PunchActivity.EXTRA_MAP_GAODE, session.mapGaode);
        intent.putExtra(PunchActivity.EXTRA_GPS, lastFix.address);
        intent.putExtra(PunchActivity.EXTRA_LAT, lastFix.lat);
        intent.putExtra(PunchActivity.EXTRA_LNG, lastFix.lng);
        intent.putExtra(PunchActivity.EXTRA_TEST_MODE, testModeCheck.isChecked());
        intent.putExtra(PunchActivity.EXTRA_CUSTOM_TIME, customTime);
        startActivity(intent);
        setStatus("已进入检测页面。测试模式：" + (testModeCheck.isChecked() ? "是" : "否"));
    }

    private void importKeyCode() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_KEYCODE);
    }

    private void confirmVendorKeyCode() {
        new AlertDialog.Builder(this)
                .setTitle("连接汉王授权服务")
                .setMessage("此操作会使用设备信息向汉王授权服务（182.92.162.37:8888）申请 KeyCode，"
                        + "与源 APK 的授权流程一致。请确认该设备与 SDK 授权允许这样做。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ensurePhoneStateAndFetch();
                    }
                })
                .show();
    }

    private void ensurePhoneStateAndFetch() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_PHONE_STATE);
            return;
        }
        fetchVendorKeyCode();
    }

    private void fetchVendorKeyCode() {
        setBusy(true, "正在向授权服务申请 KeyCode...");
        worker.execute(() -> {
            try {
                byte[] keyCode = KeyCodeManager.fetchFromVendor(MainActivity.this);
                java.util.Arrays.fill(keyCode, (byte) 0);
                main.post(() -> {
                    updateKeyCodeStatus();
                    setBusy(false, "KeyCode 已获取并保存。");
                });
            } catch (Exception e) {
                main.post(() -> setBusy(false, "获取 KeyCode 失败：" + message(e)));
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除本地数据")
                .setMessage("将清除当前会话、模板和照片缓存。KeyCode 会保留，除非你手动删除应用数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        sessionEpoch.incrementAndGet();
                        abortActiveNetwork();
                        if (session != null) session.clear();
                        session = null;
                        sessionBaseUrl = null;
                        lastFix = null;
                        lastFixManual = false;
                        lastCheckTime = null;
                        if (pwdInput != null) pwdInput.setText("");
                        TemplateStore.clear(MainActivity.this);
                        updateSessionStatus();
                        updateTemplateStatus();
                        setStatus("已清除会话与模板。");
                    }
                })
                .show();
    }

    private void setBusy(boolean value, String message) {
        for (View view : busyViews) view.setEnabled(!value);
        if (message != null) setStatus(message);
    }

    private void setStatus(String message) {
        statusView.setText(message == null ? "" : message);
    }

    private void updateSessionStatus() {
        if (session == null) {
            sessionStatus.setText("会话：未登录");
        } else {
            sessionStatus.setText("会话：employId=" + session.employId
                    + "，通道=" + session.transport.toUpperCase()
                    + "，token=<redacted>");
        }
    }

    private void updateTemplateStatus() {
        templateStatus.setText("人脸模板：" + TemplateStore.status(this));
    }

    private void updateKeyCodeStatus() {
        keycodeStatus.setText(KeyCodeManager.status(this));
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }



    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_KEYCODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            setBusy(true, "正在导入 KeyCode...");
            worker.execute(() -> {
                try {
                    byte[] keyCode = KeyCodeManager.importFromUri(MainActivity.this, uri);
                    java.util.Arrays.fill(keyCode, (byte) 0);
                    main.post(() -> {
                        updateKeyCodeStatus();
                        setBusy(false, "KeyCode 已导入。");
                    });
                } catch (Exception e) {
                    main.post(() -> setBusy(false, "导入 KeyCode 失败：" + message(e)));
                }
            });
            return;
        }

        if (requestCode == REQUEST_MAP_PICKER && resultCode == RESULT_OK && data != null) {
            String pickedLat = data.getStringExtra(MapPickerActivity.EXTRA_SELECTED_LAT);
            String pickedLng = data.getStringExtra(MapPickerActivity.EXTRA_SELECTED_LNG);
            String pickedAddr = data.getStringExtra(MapPickerActivity.EXTRA_SELECTED_ADDRESS);
            if (pickedLat != null && pickedLng != null) {
                testModeCheck.setChecked(true);
                latInput.setText(pickedLat);
                lngInput.setText(pickedLng);
                gpsInput.setText(pickedAddr != null ? pickedAddr : "");
                setStatus("地图选点完成: " + (pickedAddr != null ? pickedAddr : "") + " (" + pickedLat + ", " + pickedLng + ")");
                Toast.makeText(this, "已应用地图选点位置", Toast.LENGTH_SHORT).show();
                if (session != null && session.token != null) {
                    checkLocation();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PHONE_STATE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchVendorKeyCode();
            } else {
                setStatus("未授予设备信息权限，无法调用汉王授权服务；可改为导入 KeyCode 文件。");
            }
            return;
        }
        if (requestCode != REQUEST_LOCATION) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) granted = true;
        }
        if (granted) {
            if (pendingLocation) {
                pendingLocation = false;
                checkLocation();
            }
        } else {
            pendingLocation = false;
            setStatus("未授予定位权限。可点击【地图可视化选点】直接在地图上选点。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTemplateStatus();
        updateKeyCodeStatus();
    }

    @Override
    protected void onDestroy() {
        sessionEpoch.incrementAndGet();
        abortActiveNetwork();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (session != null) session.clear();
        session = null;
        sessionBaseUrl = null;
        lastFixManual = false;
        lastCheckTime = null;
        if (pwdInput != null) pwdInput.setText("");
        super.onDestroy();
    }
}
