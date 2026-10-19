package com.hanwang.facekey.thin.net;

/** In-memory session; never written to disk or logs. */
public final class Session {
    public String token;
    public final String employId;
    public volatile int mapGaode;
    public final int recordTcpPort;
    public final String transport;

    public Session(String token, String employId, int mapGaode, int recordTcpPort) {
        this.token = token;
        this.employId = employId;
        this.mapGaode = mapGaode;
        this.recordTcpPort = recordTcpPort;
        this.transport = recordTcpPort > 0 ? "tcp" : "http";
    }

    public void clear() {
        token = null;
    }

    @Override
    public String toString() {
        return "Session{employId='" + employId + "', mapGaode=" + mapGaode
                + ", recordTcpPort=" + recordTcpPort + ", transport='" + transport
                + "', token=<redacted>}";
    }
}
