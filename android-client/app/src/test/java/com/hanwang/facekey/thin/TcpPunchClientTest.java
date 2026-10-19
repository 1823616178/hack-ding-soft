package com.hanwang.facekey.thin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanwang.facekey.thin.crypto.Sm2;
import com.hanwang.facekey.thin.crypto.Sm2Config;
import com.hanwang.facekey.thin.crypto.Sm4;
import com.hanwang.facekey.thin.tcp.PostMobileRecord;
import com.hanwang.facekey.thin.tcp.TcpPunchClient;

import org.junit.Assume;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TcpPunchClientTest {
    private static final String JPEG_BASE64 = java.util.Base64.getEncoder()
            .encodeToString(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00});
    private static final String OWNER_ID = "汉王人脸仪客户端";

    @Test
    public void tcpAkeAndEncryptedRecordMatchTheApkProtocol() throws Exception {
        MockServer server = new MockServer(false);
        try {
            Sm2Config config = clientConfig();
            PostMobileRecord record = new PostMobileRecord(
                    JPEG_BASE64, "employee-tcp", "2024-08-16 09:08:07",
                    "北京 中关村", 39.9042, 116.4074, 0, 0, 28);
            String response = new TcpPunchClient(3000, 3000, 3000)
                    .post("127.0.0.1", server.port(), record, config);
            assertTrue(response.contains("PostMobileRecord"));
            assertNull(server.error.get());

            JsonObject ake = server.ake.get();
            assertNotNull(ake);
            assertEquals("AKE", ake.get("COMMAND").getAsString());
            assertEquals(OWNER_ID, ake.getAsJsonObject("PARAM").get("sn").getAsString());
            String pub = ake.getAsJsonObject("PARAM").getAsJsonArray("key").get(0).getAsString();
            String r = ake.getAsJsonObject("PARAM").getAsJsonArray("key").get(1).getAsString();
            assertTrue(pub.matches("[0-9A-F]{128}"));
            assertTrue(r.matches("[0-9A-F]{128}"));
            assertEquals(ClientKeys.PUBLIC_HEX, pub);

            JsonObject recordJson = server.record.get();
            assertNotNull(recordJson);
            assertEquals("PostMobileRecord", recordJson.get("COMMAND").getAsString());
            JsonObject param = recordJson.getAsJsonObject("PARAM");
            assertEquals("employee-tcp", param.get("id").getAsString());
            assertEquals("2024-08-16 09:08:07", param.get("time").getAsString());
            assertEquals("北京 中关村", param.get("gps").getAsString());
            assertEquals("39.904200", param.get("lat").getAsString());
            assertEquals("116.407400", param.get("lng").getAsString());
            assertEquals(JPEG_BASE64 + "\n", param.get("capturejpg").getAsString());
            assertEquals("1", param.get("recogSource").getAsString());
            assertEquals("1.0.15.79.20240816", param.get("appVer").getAsString());
            assertEquals("41", param.get("appVerCode").getAsString());
            assertEquals("Android", param.get("systemName").getAsString());
            assertEquals("28", param.get("currSysVer").getAsString());
        } finally {
            server.close();
        }
    }

    @Test
    public void apkSm2ConfigCompletesLocalAke() throws Exception {
        File file = new File("../../web-client/sm2.config.local.json");
        Assume.assumeTrue(file.isFile());
        Sm2Config config = Sm2Config.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        MockServer server = new MockServer(false);
        try {
            PostMobileRecord record = new PostMobileRecord(
                    JPEG_BASE64, "employee-apk-config", "2024-08-16 09:08:07",
                    "本地测试", 39.9042, 116.4074, 0, 0, 28);
            String response = new TcpPunchClient(3000, 3000, 3000)
                    .post("127.0.0.1", server.port(), record, config);
            assertTrue(response.contains("PostMobileRecord"));
            assertNull(server.error.get());
            assertNotNull(server.ake.get());
            assertEquals(config.ownerId, server.ake.get().getAsJsonObject("PARAM").get("sn").getAsString());
            assertEquals(Sm2.pointToHex(config.publicKey),
                    server.ake.get().getAsJsonObject("PARAM").getAsJsonArray("key").get(0).getAsString());
        } finally {
            server.close();
        }
    }

    @Test
    public void tcpServerRejectionIsReported() throws Exception {
        MockServer server = new MockServer(true);
        try {
            PostMobileRecord record = new PostMobileRecord(
                    JPEG_BASE64, "employee-tcp", "2024-08-16 09:08:07",
                    "北京", 39.9042, 116.4074, 0, 0, 28);
            TcpPunchClient.TcpException error = assertThrows(TcpPunchClient.TcpException.class,
                    () -> new TcpPunchClient(3000, 3000, 3000)
                            .post("127.0.0.1", server.port(), record, clientConfig()));
            assertTrue(error.getMessage().contains("服务端拒绝"));
            assertNull(server.error.get());
        } finally {
            server.close();
        }
    }

    @Test
    public void extractPunchTimeParsesServerTime() {
        String json = "{\"RETURN\":\"PostMobileRecord\",\"PARAM\":{\"result\":\"success\",\"punchTime\":\"2026-10-19 17:00:00\"}}";
        assertEquals("2026-10-19 17:00:00", TcpPunchClient.extractPunchTime(json));
        assertEquals("", TcpPunchClient.extractPunchTime("{}"));
        assertEquals("", TcpPunchClient.extractPunchTime(null));
    }

    private static Sm2Config clientConfig() {
        String json = "{\"ownerId\":\"" + OWNER_ID + "\",\"curve\":\"sm2p256v1\","
                + "\"privateKey\":\"" + String.format("%064x", ClientKeys.PRIVATE) + "\","
                + "\"publicKeyX\":\"" + ClientKeys.PUBLIC_HEX.substring(0, 64) + "\","
                + "\"publicKeyY\":\"" + ClientKeys.PUBLIC_HEX.substring(64) + "\","
                + "\"uppercase\":true}";
        return Sm2Config.parse(json);
    }

    private static final class ClientKeys {
        static final BigInteger PRIVATE = BigInteger.valueOf(2);
        static final BigInteger EPHEMERAL = BigInteger.valueOf(3);
        static final Sm2.Point PUBLIC = Sm2.publicKeyFromPrivate(PRIVATE);
        static final Sm2.Point R = Sm2.publicKeyFromPrivate(EPHEMERAL);
        static final String PUBLIC_HEX = Sm2.pointToHex(PUBLIC);
        static final String R_HEX = Sm2.pointToHex(R);
    }

    private static final class MockServer {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final boolean fail;
        final AtomicReference<JsonObject> ake = new AtomicReference<>();
        final AtomicReference<JsonObject> record = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        MockServer(boolean fail) throws IOException {
            this.fail = fail;
            this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.thread = new Thread(this::run, "mock-tcp");
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void close() throws Exception {
            serverSocket.close();
            thread.join(5000);
        }

        private void run() {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                // Zero-length frame must be skipped by the client.
                out.writeInt(0);
                out.flush();

                byte[] akeFrame = readFrame(in);
                JsonObject akeJson = JsonParser.parseString(new String(akeFrame, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                ake.set(akeJson);
                JsonObject akeParam = akeJson.getAsJsonObject("PARAM");
                Sm2.Point clientPublic = Sm2.pointFromHex(akeParam.getAsJsonArray("key").get(0).getAsString());
                Sm2.Point clientR = Sm2.pointFromHex(akeParam.getAsJsonArray("key").get(1).getAsString());
                byte[] clientZ = Sm2.computeZ(akeParam.get("sn").getAsString(), clientPublic);

                Sm2.Point serverPublic = Sm2.publicKeyFromPrivate(BigInteger.valueOf(5));
                Sm2.Point serverR = Sm2.publicKeyFromPrivate(BigInteger.valueOf(7));
                byte[] serverZ = Sm2.computeZ("tcp-test-server", serverPublic);
                byte[] shared = Sm2.keyAgreement(
                        BigInteger.valueOf(5), BigInteger.valueOf(7), serverR, serverZ,
                        clientPublic, clientR, clientZ, false);
                byte[] derived = Sm4.deriveKeyAndIv(shared);
                byte[] key = Sm4.copyRange(derived, 0, 16);
                byte[] iv = Sm4.copyRange(derived, 16, 16);

                String akeResponse = "{\"RETURN\":\"AKE\",\"PARAM\":{\"result\":\"success\","
                        + "\"sn\":\"tcp-test-server\",\"key\":[\"" + Sm2.pointToHex(serverPublic)
                        + "\",\"" + Sm2.pointToHex(serverR) + "\"]}}";
                byte[] akeReply = Frame(akeResponse.getBytes(StandardCharsets.UTF_8));
                out.write(akeReply, 0, 2);
                out.flush();
                out.write(akeReply, 2, akeReply.length - 2);
                out.flush();

                byte[] encryptedRecord = readFrame(in);
                byte[] plainRecord = Sm4.ofbDecrypt(encryptedRecord, key, iv);
                record.set(JsonParser.parseString(new String(plainRecord, StandardCharsets.UTF_8))
                        .getAsJsonObject());

                String result = fail
                        ? "{\"RETURN\":\"PostMobileRecord\",\"PARAM\":{\"result\":\"failed\",\"reason\":\"服务端拒绝\"}}"
                        : "{\"RETURN\":\"PostMobileRecord\",\"PARAM\":{\"result\":\"success\"}}";
                byte[] reply = Sm4.ofbEncrypt(result.getBytes(StandardCharsets.UTF_8), key, iv);
                out.writeInt(0);
                out.write(Frame(reply));
                out.flush();
            } catch (Throwable t) {
                if (!(t instanceof EOFException)) error.set(t);
            }
        }

        private static byte[] Frame(byte[] payload) {
            byte[] out = new byte[payload.length + 4];
            out[0] = (byte) (payload.length >>> 24);
            out[1] = (byte) (payload.length >>> 16);
            out[2] = (byte) (payload.length >>> 8);
            out[3] = (byte) payload.length;
            System.arraycopy(payload, 0, out, 4, payload.length);
            return out;
        }

        private static byte[] readFrame(DataInputStream in) throws IOException {
            int length = in.readInt();
            if (length <= 0 || length > 16 * 1024 * 1024) throw new IOException("bad frame");
            byte[] data = new byte[length];
            in.readFully(data);
            return data;
        }
    }
}
