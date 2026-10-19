package com.hanwang.facekey.thin.net;

public final class FeatureResult {
    public final String picStr;
    public final String picMaskStr;

    public FeatureResult(String picStr, String picMaskStr) {
        this.picStr = picStr;
        this.picMaskStr = picMaskStr;
    }

    @Override
    public String toString() {
        return "FeatureResult{picStr=" + (picStr == null ? "null" : "<" + picStr.length() + " chars>")
                + ", picMaskStr=" + (picMaskStr == null ? "null" : "<" + picMaskStr.length() + " chars>") + "}";
    }
}
