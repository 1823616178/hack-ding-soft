package com.hanwang.facekey.thin.net;

public final class LoginResult {
    public final String token;
    public final String employId;
    public final int mapGaode;
    public final int recordTcpPort;
    public final String transport;

    public LoginResult(String token, String employId, int mapGaode, int recordTcpPort) {
        this.token = token;
        this.employId = employId;
        this.mapGaode = mapGaode;
        this.recordTcpPort = recordTcpPort;
        this.transport = recordTcpPort > 0 ? "tcp" : "http";
    }

    @Override
    public String toString() {
        return "LoginResult{employId='" + employId + "', mapGaode=" + mapGaode
                + ", recordTcpPort=" + recordTcpPort + ", token=<redacted>}";
    }
}
