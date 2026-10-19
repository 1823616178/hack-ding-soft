package com.hanwang.facekey.thin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.services.core.AMapException;
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.core.PoiItem;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeAddress;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;
import com.amap.api.services.poisearch.PoiResult;
import com.amap.api.services.poisearch.PoiSearch;
import com.hanwang.facekey.thin.location.LocationHelper;
import com.hanwang.facekey.thin.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modern visual map selection activity integrated with GaoDe (AMap) SDK.
 * Supports interactive map selection, GaoDe POI search, auto-complete places,
 * and high-accuracy reverse geocoding.
 */
public final class MapPickerActivity extends Activity {
    public static final String EXTRA_INITIAL_LAT = "initialLat";
    public static final String EXTRA_INITIAL_LNG = "initialLng";
    public static final String EXTRA_INITIAL_ADDRESS = "initialAddress";
    public static final String EXTRA_TIANDITU_KEY = "tiandituKey";

    public static final String EXTRA_SELECTED_LAT = "selectedLat";
    public static final String EXTRA_SELECTED_LNG = "selectedLng";
    public static final String EXTRA_SELECTED_ADDRESS = "selectedAddress";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WebView webView;
    private TextView coordBadge;
    private EditText addressInput;
    private TextView geocodeStatus;
    private Button confirmButton;

    // Search bar widgets
    private EditText searchInput;
    private View clearSearchBtn;
    private ProgressBar searchLoading;
    private ScrollView searchResultsScroll;
    private LinearLayout searchResultsList;

    private GeocodeSearch geocodeSearch;

    private double currentLat = 39.9042;
    private double currentLng = 116.4074;
    private String tiandituKey = "";
    private long lastGeocodeTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupWindow();
        initAmapPrivacy();
        readIntent();
        initGeocodeSearch();
        setContentView(buildLayout());
        initWebView();
    }

    private void setupWindow() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFFFFFFFF);
            getWindow().setNavigationBarColor(0xFFFFFFFF);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void initAmapPrivacy() {
        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Exception ignored) {
        }
    }

    private void initGeocodeSearch() {
        try {
            geocodeSearch = new GeocodeSearch(this);
            geocodeSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
                @Override
                public void onRegeocodeSearched(RegeocodeResult result, int rCode) {
                    if (rCode == 1000 && result != null && result.getRegeocodeAddress() != null) {
                        RegeocodeAddress addr = result.getRegeocodeAddress();
                        String formatted = addr.getFormatAddress();
                        if (formatted != null && !formatted.trim().isEmpty()) {
                            main.post(() -> {
                                addressInput.setText(formatted);
                                geocodeStatus.setText("✓ 高德解析成功: " + (addr.getBuilding() != null && !addr.getBuilding().isEmpty() ? addr.getBuilding() : addr.getDistrict()));
                                geocodeStatus.setTextColor(0xFF059669);
                            });
                            return;
                        }
                    }
                    fallbackGeocode();
                }

                @Override
                public void onGeocodeSearched(GeocodeResult result, int rCode) {
                }
            });
        } catch (Exception e) {
            geocodeSearch = null;
        }
    }

    private void readIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            String latStr = intent.getStringExtra(EXTRA_INITIAL_LAT);
            String lngStr = intent.getStringExtra(EXTRA_INITIAL_LNG);
            try {
                if (latStr != null && !latStr.trim().isEmpty()) currentLat = Double.parseDouble(latStr);
                if (lngStr != null && !lngStr.trim().isEmpty()) currentLng = Double.parseDouble(lngStr);
            } catch (Exception ignored) {
            }
            tiandituKey = intent.getStringExtra(EXTRA_TIANDITU_KEY);
        }
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF8FAFC);

        // 1. Map container
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Center Pin overlay (indicates exact center point)
        TextView centerPin = new TextView(this);
        centerPin.setText("📍");
        centerPin.setTextSize(36f);
        centerPin.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams pinParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinParams.gravity = Gravity.CENTER;
        pinParams.setMargins(0, 0, 0, dp(36)); // Align pin point to center
        root.addView(centerPin, pinParams);

        // 3. Top Floating Search Bar
        LinearLayout topSearchContainer = buildTopSearchBar();
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;
        topParams.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(topSearchContainer, topParams);

        // 4. Floating Search Results Dropdown List
        searchResultsScroll = new ScrollView(this);
        searchResultsScroll.setVisibility(View.GONE);
        GradientDrawable dropBg = createCardDrawable(0xFFFFFFFF, dp(12), 0xFFCBD5E1);
        searchResultsScroll.setBackground(dropBg);
        if (Build.VERSION.SDK_INT >= 21) searchResultsScroll.setElevation(dp(12));

        searchResultsList = new LinearLayout(this);
        searchResultsList.setOrientation(LinearLayout.VERTICAL);
        searchResultsScroll.addView(searchResultsList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams dropParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        dropParams.gravity = Gravity.TOP;
        dropParams.setMargins(dp(12), dp(72), dp(12), 0);
        root.addView(searchResultsScroll, dropParams);

        // 5. Bottom Selection Card
        LinearLayout bottomCard = buildBottomCard();
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.setMargins(dp(16), 0, dp(16), dp(16));
        root.addView(bottomCard, bottomParams);

        return root;
    }

    private LinearLayout buildTopSearchBar() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setBackground(createCardDrawable(0xFFFFFFFF, dp(14), 0xFFE2E8F0));
        container.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (Build.VERSION.SDK_INT >= 21) container.setElevation(dp(6));

        // Back button
        Button backBtn = createFlatButton("←", 0xFF334155, 0x00000000);
        backBtn.setTextSize(18f);
        backBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(38)));
        backBtn.setOnClickListener(v -> finish());
        container.addView(backBtn);

        // Search Input
        searchInput = new EditText(this);
        searchInput.setHint("输入地点或大厦，如：科技园、软件园");
        searchInput.setHintTextColor(0xFF94A3B8);
        searchInput.setTextColor(0xFF0F172A);
        searchInput.setTextSize(14f);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch(searchInput.getText().toString().trim());
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        inputLp.setMargins(dp(6), 0, dp(6), 0);
        container.addView(searchInput, inputLp);

        // Clear Search Button
        TextView clearBtn = new TextView(this);
        clearBtn.setText(" ✕ ");
        clearBtn.setTextColor(0xFF94A3B8);
        clearBtn.setTextSize(14f);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setVisibility(View.GONE);
        clearBtn.setOnClickListener(v -> {
            searchInput.setText("");
            searchResultsScroll.setVisibility(View.GONE);
            hideKeyboard();
        });
        clearSearchBtn = clearBtn;
        container.addView(clearBtn);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Search Loading spinner
        searchLoading = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        searchLoading.setVisibility(View.GONE);
        container.addView(searchLoading);

        // Search Action Button
        Button searchBtn = createPillButton("🔍 搜索", Color.WHITE, 0xFF2563EB);
        searchBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        searchBtn.setOnClickListener(v -> performSearch(searchInput.getText().toString().trim()));
        container.addView(searchBtn);

        return container;
    }

    private LinearLayout buildBottomCard() {
        LinearLayout bottomCard = new LinearLayout(this);
        bottomCard.setOrientation(LinearLayout.VERTICAL);
        bottomCard.setBackground(createCardDrawable(0xFFFFFFFF, dp(16), 0xFFE2E8F0));
        bottomCard.setPadding(dp(18), dp(16), dp(18), dp(18));
        if (Build.VERSION.SDK_INT >= 21) bottomCard.setElevation(dp(8));

        // Coordinate pill & GPS relocate row
        LinearLayout coordRow = new LinearLayout(this);
        coordRow.setOrientation(LinearLayout.HORIZONTAL);
        coordRow.setGravity(Gravity.CENTER_VERTICAL);

        coordBadge = new TextView(this);
        coordBadge.setText("📍 经纬度: 正在读取...");
        coordBadge.setTextSize(12f);
        coordBadge.setTextColor(0xFF1E40AF);
        coordBadge.setTypeface(Typeface.DEFAULT_BOLD);
        coordBadge.setBackground(createPillDrawable(0xFFDBEAFE));
        coordBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        coordRow.addView(coordBadge);

        View gap = new View(this);
        coordRow.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        Button gpsBtn = createPillButton("🛰️ 我的位置", 0xFF0284C7, 0xFFF0F9FF);
        gpsBtn.setOnClickListener(v -> locateCurrentPosition());
        coordRow.addView(gpsBtn);

        bottomCard.addView(coordRow);

        // Status
        geocodeStatus = new TextView(this);
        geocodeStatus.setText("正在解析地址...");
        geocodeStatus.setTextSize(11f);
        geocodeStatus.setTextColor(0xFF64748B);
        geocodeStatus.setPadding(dp(4), dp(6), dp(4), dp(2));
        bottomCard.addView(geocodeStatus);

        // Address editable field
        addressInput = new EditText(this);
        addressInput.setHint("选定打卡地点名称");
        addressInput.setHintTextColor(0xFF94A3B8);
        addressInput.setTextColor(0xFF0F172A);
        addressInput.setTextSize(14f);
        addressInput.setTypeface(Typeface.DEFAULT_BOLD);
        addressInput.setBackground(createCardDrawable(0xFFF8FAFC, dp(8), 0xFFCBD5E1));
        addressInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        bottomCard.addView(addressInput);

        // Confirm button
        confirmButton = createPrimaryButton("✓ 确定使用此打卡地点");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        btnParams.topMargin = dp(12);
        confirmButton.setLayoutParams(btnParams);
        confirmButton.setOnClickListener(v -> confirmSelection());
        bottomCard.addView(confirmButton);

        return bottomCard;
    }

    private void performSearch(String keyword) {
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入要搜索的地点关键词", Toast.LENGTH_SHORT).show();
            return;
        }

        hideKeyboard();
        searchLoading.setVisibility(View.VISIBLE);
        searchResultsList.removeAllViews();
        searchResultsScroll.setVisibility(View.VISIBLE);

        TextView searchingTip = new TextView(this);
        searchingTip.setText("正在通过高德地图搜索: " + keyword + " ...");
        searchingTip.setTextColor(0xFF64748B);
        searchingTip.setTextSize(13f);
        searchingTip.setPadding(dp(16), dp(16), dp(16), dp(16));
        searchResultsList.addView(searchingTip);

        try {
            PoiSearch.Query query = new PoiSearch.Query(keyword, "", "");
            query.setPageSize(15);
            query.setPageNum(0);

            PoiSearch poiSearch = new PoiSearch(this, query);
            poiSearch.setOnPoiSearchListener(new PoiSearch.OnPoiSearchListener() {
                @Override
                public void onPoiSearched(PoiResult result, int rCode) {
                    main.post(() -> {
                        searchLoading.setVisibility(View.GONE);
                        if (rCode == 1000 && result != null && result.getPois() != null && !result.getPois().isEmpty()) {
                            populateSearchResults(result.getPois());
                        } else {
                            searchResultsList.removeAllViews();
                            TextView emptyView = new TextView(MapPickerActivity.this);
                            emptyView.setText("未检索到相关地点，请尝试其他关键词");
                            emptyView.setTextColor(0xFFDC2626);
                            emptyView.setTextSize(13f);
                            emptyView.setPadding(dp(16), dp(16), dp(16), dp(16));
                            searchResultsList.addView(emptyView);
                        }
                    });
                }

                @Override
                public void onPoiItemSearched(PoiItem item, int rCode) {}
            });
            poiSearch.searchPOIAsyn();
        } catch (AMapException e) {
            searchLoading.setVisibility(View.GONE);
            Toast.makeText(this, "高德搜索异常: " + e.getErrorMessage(), Toast.LENGTH_SHORT).show();
            searchResultsScroll.setVisibility(View.GONE);
        }
    }

    private void populateSearchResults(List<PoiItem> pois) {
        searchResultsList.removeAllViews();

        for (final PoiItem poi : pois) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(14), dp(10), dp(14), dp(10));

            TextView title = new TextView(this);
            title.setText(poi.getTitle());
            title.setTextSize(14f);
            title.setTextColor(0xFF0F172A);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            item.addView(title);

            String snippet = poi.getSnippet();
            if (snippet == null || snippet.isEmpty()) {
                snippet = poi.getCityName() + poi.getAdName();
            }
            TextView sub = new TextView(this);
            sub.setText(snippet);
            sub.setTextSize(12f);
            sub.setTextColor(0xFF64748B);
            sub.setPadding(0, dp(2), 0, 0);
            item.addView(sub);

            View divider = new View(this);
            divider.setBackgroundColor(0xFFF1F5F9);
            item.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            item.setOnClickListener(v -> {
                searchResultsScroll.setVisibility(View.GONE);
                hideKeyboard();
                LatLonPoint point = poi.getLatLonPoint();
                if (point != null) {
                    currentLat = point.getLatitude();
                    currentLng = point.getLongitude();
                    String fullAddr = poi.getTitle();
                    if (!poi.getSnippet().isEmpty()) {
                        fullAddr = poi.getCityName() + poi.getAdName() + " " + poi.getTitle();
                    }
                    addressInput.setText(fullAddr);
                    coordBadge.setText(String.format(Locale.US, "📍 %.6f, %.6f", currentLng, currentLat));
                    geocodeStatus.setText("✓ 高德地点: " + poi.getTitle());
                    geocodeStatus.setTextColor(0xFF059669);

                    // Move map smoothly
                    webView.evaluateJavascript(
                            String.format(Locale.US, "flyTo(%.6f, %.6f);", currentLat, currentLng), null);
                }
            });

            searchResultsList.addView(item);
        }
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void initWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onMapMoved(final double lat, final double lng) {
                main.post(() -> onLocationChanged(lat, lng));
            }
        }, "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                onLocationChanged(currentLat, currentLng);
            }
        });

        String html = generateMapHtml(currentLat, currentLng);
        webView.loadDataWithBaseURL("https://web.map", html, "text/html", "UTF-8", null);
    }

    private void onLocationChanged(double lat, double lng) {
        currentLat = lat;
        currentLng = lng;
        coordBadge.setText(String.format(Locale.US, "📍 %.6f, %.6f", currentLng, currentLat));
        geocodeStatus.setText("正在解析高德详细地址...");
        geocodeStatus.setTextColor(0xFF64748B);

        final long now = System.currentTimeMillis();
        lastGeocodeTime = now;

        // 1. Try GaoDe official SDK GeocodeSearch
        if (geocodeSearch != null) {
            RegeocodeQuery query = new RegeocodeQuery(new LatLonPoint(lat, lng), 200, GeocodeSearch.AMAP);
            geocodeSearch.getFromLocationAsyn(query);
        } else {
            fallbackGeocode();
        }
    }

    private void fallbackGeocode() {
        final long now = lastGeocodeTime;
        worker.execute(() -> {
            try {
                String address = LocationHelper.reverseGeocode(
                        getApplicationContext(), currentLng, currentLat, tiandituKey);
                if (now == lastGeocodeTime) {
                    main.post(() -> {
                        if (address != null && !address.trim().isEmpty() && !address.contains("无法获取")) {
                            addressInput.setText(address);
                            geocodeStatus.setText("✓ 逆地理反查成功");
                            geocodeStatus.setTextColor(0xFF059669);
                        } else {
                            geocodeStatus.setText("未能获取街道，可在上方输入框手动补充");
                            geocodeStatus.setTextColor(0xFF94A3B8);
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void locateCurrentPosition() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        Location last = null;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (last == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException ignored) {
        }
        if (last != null) {
            double lat = last.getLatitude();
            double lng = last.getLongitude();
            webView.evaluateJavascript(String.format(Locale.US, "flyTo(%.6f, %.6f);", lat, lng), null);
            Toast.makeText(this, "已定位到手机当前 GPS 位置", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "暂未获取到手机 GPS 位置，请开启定位权限并移至开阔处", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmSelection() {
        String finalAddr = addressInput.getText().toString().trim();
        if (finalAddr.isEmpty()) {
            finalAddr = String.format(Locale.US, "考勤定位点(%.6f, %.6f)", currentLng, currentLat);
        }

        Intent data = new Intent();
        data.putExtra(EXTRA_SELECTED_LAT, String.format(Locale.US, "%.6f", currentLat));
        data.putExtra(EXTRA_SELECTED_LNG, String.format(Locale.US, "%.6f", currentLng));
        data.putExtra(EXTRA_SELECTED_ADDRESS, finalAddr);
        setResult(RESULT_OK, data);
        finish();
    }

    private String generateMapHtml(double lat, double lng) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<style>body,html,#map{width:100%;height:100%;margin:0;padding:0;background:#F8FAFC;overflow:hidden;}</style>"
                + "</head><body><div id='map'></div><script>"
                + "var map = L.map('map', { zoomControl: false }).setView([" + lat + ", " + lng + "], 16);"
                + "L.tileLayer('https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {"
                + "  maxZoom: 18, minZoom: 3"
                + "}).addTo(map);"
                + "map.on('moveend', function() {"
                + "  var c = map.getCenter();"
                + "  if (window.AndroidBridge) {"
                + "    window.AndroidBridge.onMapMoved(c.lat, c.lng);"
                + "  }"
                + "});"
                + "map.on('click', function(e) {"
                + "  map.panTo(e.latlng);"
                + "});"
                + "function flyTo(lat, lng) { map.flyTo([lat, lng], 17); }"
                + "</script></body></html>";
    }

    private static GradientDrawable createCardDrawable(int bgColor, int radius, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(radius);
        if (strokeColor != 0) gd.setStroke(1, strokeColor);
        return gd;
    }

    private static GradientDrawable createPillDrawable(int bgColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(999f);
        return gd;
    }

    private Button createPrimaryButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(15f);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        gd.setColors(new int[]{0xFF059669, 0xFF10B981}); // Emerald
        gd.setCornerRadius(dp(10));
        btn.setBackground(gd);
        return btn;
    }

    private Button createPillButton(String text, int textColor, int bgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12f);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setBackground(createPillDrawable(bgColor));
        return btn;
    }

    private Button createFlatButton(String text, int textColor, int bgColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14f);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        btn.setBackgroundColor(bgColor);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
