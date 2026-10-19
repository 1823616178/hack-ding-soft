package com.hanwang.facekey.thin.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret UI configuration. The password and token are deliberately not persisted. */
public final class AppConfig {
    private static final String PREFS = "efacego_thin_config";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_USER = "user_name";
    private static final String KEY_TEST_MODE = "test_mode";
    private static final String KEY_TIANDITU = "tianditu_key";
    public static final String DEFAULT_SERVER = "http://223.223.177.153:7300";
    private static final String DEFAULT_TIANDITU_KEY = "a7349ecc9edda4014f0c186aebe6ac39";

    public String serverUrl = DEFAULT_SERVER;
    public String userName = "";
    public boolean testMode = false;
    public String tiandituKey = DEFAULT_TIANDITU_KEY;

    public static AppConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppConfig config = new AppConfig();
        String savedServer = prefs.getString(KEY_SERVER, "");
        config.serverUrl = (savedServer == null || savedServer.trim().isEmpty())
                ? DEFAULT_SERVER : savedServer.trim();
        config.userName = prefs.getString(KEY_USER, "");
        config.testMode = prefs.getBoolean(KEY_TEST_MODE, false);
        config.tiandituKey = prefs.getString(KEY_TIANDITU, DEFAULT_TIANDITU_KEY);
        return config;
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SERVER, serverUrl == null ? DEFAULT_SERVER : serverUrl)
                .putString(KEY_USER, userName == null ? "" : userName)
                .putBoolean(KEY_TEST_MODE, testMode)
                .putString(KEY_TIANDITU, tiandituKey == null ? DEFAULT_TIANDITU_KEY : tiandituKey)
                .apply();
    }
}
