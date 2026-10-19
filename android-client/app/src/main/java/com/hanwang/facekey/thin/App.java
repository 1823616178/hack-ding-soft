package com.hanwang.facekey.thin;

import android.app.Application;
import com.amap.api.services.core.ServiceSettings;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Exception ignored) {
        }
    }
}
