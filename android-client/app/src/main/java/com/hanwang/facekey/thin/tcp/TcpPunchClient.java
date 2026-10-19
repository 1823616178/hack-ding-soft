package com.hanwang.facekey.thin.tcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanwang.facekey.thin.crypto.FrameCodec;
import com.hanwang.facekey.thin.crypto.Sm2;
import com.hanwang.facekey.thin.crypto.Sm2Config;
import com.hanwang.facekey.thin.crypto.Sm4;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-socket implementation of the APK's MINA TCP channel:
 * SM2 AKE in clear text, then SM4/OFB/PKCS7 encrypted PostMobileRecord frames.
 */
public final class TcpPunchClient {
    private final int connectTimeoutMs;
    private final int akeTimeoutMs;
    private final int responseTimeoutMs;

    public TcpPunchClient() {
        this(10000, 3000, 10000);
    }

    public TcpPunchClient(int connectTimeoutMs, int akeTimeoutMs, int responseTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.akeTimeoutMs = akeTimeoutMs;
        this.responseTimeoutMs = responseTimeoutMs;
    }

    public String post(String host, int port, PostMobileRecord record, Sm2Config config) throws Exception {
        return post(host, port, record, config, null, null);
    }

    public String post(
            String host,
            int port,
            PostMobileRecord record,
            Sm2Config config,
            AtomicReference<Socket> socketRef,
            AtomicBoolean requestSentRef) throws Exception {
        if (config == null) throw new IllegalArgumentException("缺少 SM2 配置");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("TCP 端口无效");

        Sm2.Initiation initiation = Sm2.createInitiation(
                config.ownerId, config.privateKey, config.publicKey, null);
        Socket socket = new Socket();
        if (socketRef != null) socketRef.set(socket);
        Sm2.Session session = null;
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(FrameCodec.encode(initiation.akeJson.getBytes(StandardCharsets.UTF_8)));
            out.flush();

            byte[] akeFrame = readFrame(socket, in, akeTimeoutMs);
            String akeResponse = new String(akeFrame, StandardCharsets.UTF_8);
            session = Sm2.complete(akeResponse, initiation.state);

            byte[] plaintext = record.toMessageJson().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = Sm4.ofbEncrypt(plaintext, session.key, session.iv);
            out.write(FrameCodec.encode(encrypted));
            out.flush();
            if (requestSentRef != null) requestSentRef.set(true);

            byte[] responseFrame = readFrame(socket, in, responseTimeoutMs);
            byte[] responsePlain = Sm4.ofbDecrypt(responseFrame, session.key, session.iv);
            JsonObject response = JsonParser.parseString(
                    new String(responsePlain, StandardCharsets.UTF_8)).getAsJsonObject();
            String returnValue = response.has("RETURN") ? response.get("RETURN").getAsString() : "";
            if (!"PostMobileRecord".equalsIgnoreCase(returnValue)) {
                throw new TcpException("TCP_RESPONSE_INVALID", "TCP 响应 RETURN 不是 PostMobileRecord");
            }
            JsonObject param = response.has("PARAM") ? response.getAsJsonObject("PARAM") : null;
            String result = param != null && param.has("result") ? param.get("result").getAsString() : "";
            if (!"success".equalsIgnoreCase(result)) {
                String reason = param != null && param.has("reason") ? param.get("reason").getAsString() : null;
                throw new TcpException("TCP_REJECTED",
                        reason == null || reason.isEmpty() ? "TCP 上传记录失败" : reason);
            }
            return response.toString();
        } catch (SocketException e) {
            if (socket.isClosed()) {
                throw new TcpException("TCP_CANCELED", "TCP 连接已主动关闭或取消");
            }
            throw e;
        } finally {
            if (session != null) session.clear();
            if (socketRef != null) socketRef.compareAndSet(socket, null);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String extractPunchTime(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isEmpty()) return "";
        try {
            JsonObject response = JsonParser.parseString(jsonResponse).getAsJsonObject();
            JsonObject param = response.has("PARAM") ? response.getAsJsonObject("PARAM") : null;
            if (param != null && param.has("punchTime") && !param.get("punchTime").isJsonNull()) {
                return param.get("punchTime").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static byte[] readFrame(Socket socket, InputStream in, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            byte[] header = readExactly(socket, in, 4, deadline);
            int size = ((header[0] & 0xff) << 24)
                    | ((header[1] & 0xff) << 16)
                    | ((header[2] & 0xff) << 8)
                    | (header[3] & 0xff);
            if (size == 0) continue;
            if (size > FrameCodec.MAX_FRAME_BYTES) {
                throw new TcpException("TCP_FRAME_TOO_LARGE", "TCP 帧超过大小限制");
            }
            return readExactly(socket, in, size, deadline);
        }
    }

    private static byte[] readExactly(Socket socket, InputStream in, int length, long deadline) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) throw new SocketTimeoutException("等待 TCP 帧超时");
            socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
            int read = in.read(data, offset, length - offset);
            if (read < 0) throw new EOFException("TCP 连接已关闭");
            offset += read;
        }
        return data;
    }

    public static final class TcpException extends IOException {
        public final String code;

        public TcpException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
