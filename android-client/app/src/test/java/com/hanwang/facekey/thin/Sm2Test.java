package com.hanwang.facekey.thin.crypto;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class Sm2Test {
    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format("%02X", b & 0xff));
        return out.toString();
    }

    private static Sm2.State party(String ownerId, BigInteger privateKey, BigInteger ephemeral) {
        Sm2.Point publicKey = Sm2.publicKeyFromPrivate(privateKey);
        Sm2.Point R = Sm2.publicKeyFromPrivate(ephemeral);
        return new Sm2.State(ownerId, privateKey, publicKey, ephemeral, R,
                Sm2.computeZ(ownerId, publicKey));
    }

    @Test
    public void keyAgreementIsSymmetricAndAkeCompletes() {
        Sm2.State client = party("汉王人脸仪客户端", BigInteger.valueOf(2), BigInteger.valueOf(3));
        Sm2.State server = party("test-server", BigInteger.valueOf(5), BigInteger.valueOf(7));

        byte[] clientKey = Sm2.keyAgreement(client.privateKey, client.r, client.R, client.Z,
                server.publicKey, server.R, server.Z, true);
        byte[] serverKey = Sm2.keyAgreement(server.privateKey, server.r, server.R, server.Z,
                client.publicKey, client.R, client.Z, false);
        assertArrayEquals(clientKey, serverKey);
        assertEquals(32, clientKey.length);
        // Cross-checked against web-client/lib/sm2.js with the same deterministic scalars.
        assertEquals("A7E3C4DBB720804F42BDE36D866D559ED5ABA7EB252148ACE26987E0A53AA6CF",
                hex(clientKey));

        Sm2.Initiation initiation = Sm2.createInitiation(
                client.ownerId, client.privateKey, client.publicKey, client.r);
        JsonObject ake = JsonParser.parseString(initiation.akeJson).getAsJsonObject();
        assertEquals("AKE", ake.get("COMMAND").getAsString());
        assertEquals(client.ownerId, ake.getAsJsonObject("PARAM").get("sn").getAsString());
        String sentPublic = ake.getAsJsonObject("PARAM").getAsJsonArray("key").get(0).getAsString();
        String sentR = ake.getAsJsonObject("PARAM").getAsJsonArray("key").get(1).getAsString();
        assertEquals(Sm2.pointToHex(client.publicKey), sentPublic);
        assertEquals(Sm2.pointToHex(client.R), sentR);
        assertTrue(sentPublic.matches("[0-9A-F]{128}"));
        assertTrue(sentR.matches("[0-9A-F]{128}"));

        String response = "{\"RETURN\":\"AKE\",\"PARAM\":{\"result\":\"success\",\"sn\":\"test-server\","
                + "\"key\":[\"" + Sm2.pointToHex(server.publicKey) + "\",\""
                + Sm2.pointToHex(server.R) + "\"]}}";
        Sm2.Session session = Sm2.complete(response, initiation.state);
        assertArrayEquals(clientKey, session.sharedKey);
        assertEquals(16, session.key.length);
        assertEquals(16, session.iv.length);
        session.clear();
        assertArrayEquals(new byte[32], session.sharedKey);
    }

    @Test
    public void malformedAkeResponsesAreRejected() {
        Sm2.State client = party("client", BigInteger.valueOf(2), BigInteger.valueOf(3));
        Sm2.Initiation initiation = Sm2.createInitiation(
                client.ownerId, client.privateKey, client.publicKey, client.r);
        assertThrows(IllegalArgumentException.class,
                () -> Sm2.complete("{\"RETURN\":\"AKE\",\"PARAM\":{\"result\":\"failed\"}}", initiation.state));
        assertThrows(IllegalArgumentException.class,
                () -> Sm2.complete("{\"RETURN\":\"AKE\",\"PARAM\":{\"result\":\"success\","
                        + "\"sn\":\"server\",\"key\":[\"\",\"\"]}}", initiation.state));
        assertThrows(IllegalArgumentException.class,
                () -> Sm2.pointFromHex("00"));
    }

    @Test
    public void configParsesAndValidatesKeyPairWithoutExposingPrivateKey() {
        BigInteger privateKey = BigInteger.valueOf(123456789L);
        Sm2.Point publicKey = Sm2.publicKeyFromPrivate(privateKey);
        String publicHex = Sm2.pointToHex(publicKey);
        String json = "{\"ownerId\":\"test\",\"curve\":\"sm2p256v1\",\"privateKey\":\""
                + String.format("%064x", privateKey) + "\",\"publicKeyX\":\"" + publicHex.substring(0, 64)
                + "\",\"publicKeyY\":\"" + publicHex.substring(64) + "\",\"uppercase\":true}";
        Sm2Config config = Sm2Config.parse(json);
        assertEquals("test", config.ownerId);
        assertEquals(publicKey, config.publicKey);
        assertTrue(!config.toString().contains(privateKey.toString(16)));

        String bad = json.replace(publicHex.substring(64), "00".repeat(32));
        assertThrows(IllegalArgumentException.class, () -> Sm2Config.parse(bad));
    }

    @Test
    public void apkConfigLoadsWhenAvailable() throws Exception {
        File file = new File("../../web-client/sm2.config.local.json");
        Assume.assumeTrue(file.isFile());
        Sm2Config config = Sm2Config.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertNotNull(config.publicKey);
        assertEquals("汉王人脸仪客户端", config.ownerId);
        assertEquals(config.publicKey, Sm2.publicKeyFromPrivate(config.privateKey));
        assertTrue(config.toString().contains("<redacted>"));
    }
}
