package com.hanwang.facekey.thin.net;

public final class CheckLocationResult {
    public final boolean success;
    public final int code;
    public final int mapGaode;
    public final boolean mapGaodePresent;

    public CheckLocationResult(boolean success, int code, int mapGaode, boolean mapGaodePresent) {
        this.success = success;
        this.code = code;
        this.mapGaode = mapGaode;
        this.mapGaodePresent = mapGaodePresent;
    }

    public boolean isAccepted() {
        return success && code == 0;
    }
}
