package com.hanwang.facekey.thin.crypto;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Locally supplied APK SM2 identity. The JSON asset is copied from
 * web-client/sm2.config.local.json by Gradle and is intentionally ignored by git.
 */
public final class Sm2Config {
    public final String ownerId;
    public final BigInteger privateKey;
    public final Sm2.Point publicKey;
    public final boolean uppercase;

    private Sm2Config(String ownerId, BigInteger privateKey, Sm2.Point publicKey, boolean uppercase) {
        this.ownerId = ownerId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.uppercase = uppercase;
    }

    public static Sm2Config parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String ownerId = root.has("ownerId") && !root.get("ownerId").isJsonNull()
                ? root.get("ownerId").getAsString()
                : "汉王人脸仪客户端";
        String curve = root.has("curve") ? root.get("curve").getAsString() : "sm2p256v1";
        if (!"sm2p256v1".equals(curve)) {
            throw new IllegalArgumentException("Only sm2p256v1 is supported");
        }
        BigInteger privateKey = Sm2.fromPrivateKeyHex(root.get("privateKey").getAsString());
        String x = root.get("publicKeyX").getAsString();
        String y = root.get("publicKeyY").getAsString();
        Sm2.Point publicKey = Sm2.pointFromHex(x + y);
        Sm2.Point derived = Sm2.publicKeyFromPrivate(privateKey);
        if (!derived.equals(publicKey)) {
            throw new IllegalArgumentException("SM2 private key and public key do not match");
        }
        boolean uppercase = !root.has("uppercase") || root.get("uppercase").getAsBoolean();
        return new Sm2Config(ownerId, privateKey, publicKey, uppercase);
    }

    public static Sm2Config load(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "Sm2Config{ownerId='" + ownerId + "', publicKey=" + Sm2.pointToHex(publicKey)
                + ", privateKey=<redacted>}";
    }
}
