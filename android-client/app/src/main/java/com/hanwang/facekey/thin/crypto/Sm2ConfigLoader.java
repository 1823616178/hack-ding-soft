package com.hanwang.facekey.thin.crypto;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

/** Loads the local SM2 identity copied into assets by Gradle. */
public final class Sm2ConfigLoader {
    public static final String ASSET = "sm2.config.local.json";

    private Sm2ConfigLoader() {
    }

    public static Sm2Config load(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        try (InputStream in = assets.open(ASSET)) {
            return Sm2Config.load(in);
        } catch (RuntimeException e) {
            throw new IOException("SM2 配置无效", e);
        }
    }

    public static boolean available(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }
}
