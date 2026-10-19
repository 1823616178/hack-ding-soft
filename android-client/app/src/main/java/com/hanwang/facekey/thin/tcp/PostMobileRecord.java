package com.hanwang.facekey.thin.tcp;

import com.hanwang.facekey.thin.config.ApkIdentity;
import com.hanwang.facekey.thin.crypto.Sm2;
import com.hanwang.facekey.thin.protocol.Protocol;

/** PostMobileRecord PARAM, preserving the APK field order. */
public final class PostMobileRecord {
    public final String capturejpg;
    public final String id;
    public final String time;
    public final String gps;
    public final String lat;
    public final String lng;
    public final String recogSource;
    public final String appVer;
    public final String appVerCode;
    public final String serverSpecifiedMap;
    public final String usedMap;
    public final String systemName;
    public final String currSysVer;

    public PostMobileRecord(
            String capturejpg,
            String id,
            String time,
            String gps,
            Object lat,
            Object lng,
            int serverSpecifiedMap,
            int usedMap,
            int currSysVer) {
        if (id == null || id.isEmpty()) throw new Protocol.ProtocolException("TCP 打卡缺少 employId", "employId");
        this.time = Protocol.validateCalendarTime(time, "time", false);
        if (currSysVer < 0) {
            throw new Protocol.ProtocolException("currSysVer 必须是 Android SDK 整数版本", "currSysVer");
        }
        if (usedMap != 0 && usedMap != 1) {
            throw new Protocol.ProtocolException("usedMap 只能是 0 或 1", "usedMap");
        }
        this.capturejpg = Protocol.encodePhotoForApk(capturejpg);
        this.id = id;
        this.gps = gps == null ? "" : gps;
        this.lat = Protocol.formatCoordinate(lat, "lat");
        this.lng = Protocol.formatCoordinate(lng, "lng");
        this.recogSource = ApkIdentity.SOURCE;
        this.appVer = ApkIdentity.APP_VER;
        this.appVerCode = ApkIdentity.APP_VER_CODE;
        this.serverSpecifiedMap = String.valueOf(serverSpecifiedMap);
        this.usedMap = String.valueOf(usedMap);
        this.systemName = ApkIdentity.SYSTEM_NAME;
        this.currSysVer = String.valueOf(currSysVer);
    }

    public String toJson() {
        return "{"
                + "\"capturejpg\":" + Sm2.quote(capturejpg)
                + ",\"id\":" + Sm2.quote(id)
                + ",\"time\":" + Sm2.quote(time)
                + ",\"gps\":" + Sm2.quote(gps)
                + ",\"lat\":" + Sm2.quote(lat)
                + ",\"lng\":" + Sm2.quote(lng)
                + ",\"recogSource\":" + Sm2.quote(recogSource)
                + ",\"appVer\":" + Sm2.quote(appVer)
                + ",\"appVerCode\":" + Sm2.quote(appVerCode)
                + ",\"serverSpecifiedMap\":" + Sm2.quote(serverSpecifiedMap)
                + ",\"usedMap\":" + Sm2.quote(usedMap)
                + ",\"systemName\":" + Sm2.quote(systemName)
                + ",\"currSysVer\":" + Sm2.quote(currSysVer)
                + "}";
    }

    public String toMessageJson() {
        return "{\"COMMAND\":\"PostMobileRecord\",\"PARAM\":" + toJson() + "}";
    }

    @Override
    public String toString() {
        return "PostMobileRecord{id='" + id + "', time='" + time + "', capturejpg=<"
                + capturejpg.length() + " chars>}";
    }
}
