package com.hanwang.facekey.thin.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SM2 (sm2p256v1) arithmetic and the exact key-agreement formula used by the
 * APK's FaceReaderProtocolCodecFactory / SM2UserInformation flow.
 *
 * <p>This is a direct Java port of the validated web-client implementation.</p>
 */
public final class Sm2 {
    public static final BigInteger P = hex("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF");
    public static final BigInteger A = hex("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC");
    public static final BigInteger B = hex("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93");
    public static final BigInteger N = hex("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123");
    public static final BigInteger GX = hex("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7");
    public static final BigInteger GY = hex("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0");
    public static final Point G = new Point(GX, GY);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Sm2() {
    }

    public static final class Point {
        public final BigInteger x;
        public final BigInteger y;

        public Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Point)) return false;
            Point point = (Point) other;
            return x.equals(point.x) && y.equals(point.y);
        }

        @Override
        public int hashCode() {
            return 31 * x.hashCode() + y.hashCode();
        }
    }

    public static final class Initiation {
        public final String akeJson;
        public final State state;

        Initiation(String akeJson, State state) {
            this.akeJson = akeJson;
            this.state = state;
        }
    }

    public static final class State {
        public final String ownerId;
        public final BigInteger privateKey;
        public final Point publicKey;
        public final BigInteger r;
        public final Point R;
        public final byte[] Z;

        State(String ownerId, BigInteger privateKey, Point publicKey, BigInteger r, Point R, byte[] z) {
            this.ownerId = ownerId;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.r = r;
            this.R = R;
            this.Z = z;
        }
    }

    public static final class Session {
        public final byte[] sharedKey;
        public final byte[] key;
        public final byte[] iv;
        public final Point partnerPublicKey;
        public final Point partnerR;
        public final byte[] partnerZ;

        Session(byte[] sharedKey, byte[] key, byte[] iv, Point partnerPublicKey, Point partnerR, byte[] partnerZ) {
            this.sharedKey = sharedKey;
            this.key = key;
            this.iv = iv;
            this.partnerPublicKey = partnerPublicKey;
            this.partnerR = partnerR;
            this.partnerZ = partnerZ;
        }

        public void clear() {
            Arrays.fill(sharedKey, (byte) 0);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public static Initiation createInitiation(
            String ownerId,
            BigInteger privateKey,
            Point publicKey,
            BigInteger ephemeralScalar) {
        if (privateKey == null || privateKey.signum() <= 0 || privateKey.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Invalid SM2 private key");
        }
        if (!isOnCurve(publicKey)) throw new IllegalArgumentException("Invalid SM2 public key");
        BigInteger r = ephemeralScalar == null ? randomScalar() : ephemeralScalar;
        if (r.signum() <= 0 || r.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Invalid SM2 ephemeral scalar");
        }
        Point R = multiply(G, r);
        byte[] Z = computeZ(ownerId, publicKey);
        String pub = pointToHex(publicKey);
        String ephemeral = pointToHex(R);
        String json = "{\"COMMAND\":\"AKE\",\"PARAM\":{\"sn\":"
                + quote(ownerId)
                + ",\"key\":[\""
                + pub
                + "\",\""
                + ephemeral
                + "\"]}}";
        return new Initiation(json, new State(ownerId, privateKey, publicKey, r, R, Z));
    }

    public static Session complete(String akeResponseJson, State state) {
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(akeResponseJson).getAsJsonObject();
        String returnValue = root.has("RETURN") ? root.get("RETURN").getAsString() : "";
        if (!"AKE".equals(returnValue)) throw new IllegalArgumentException("AKE response RETURN is not AKE");
        com.google.gson.JsonObject param = root.has("PARAM") ? root.getAsJsonObject("PARAM") : null;
        if (param == null || !param.has("result") || !"success".equals(param.get("result").getAsString())) {
            throw new IllegalArgumentException("AKE response was not successful");
        }
        if (!param.has("key") || !param.get("key").isJsonArray() || param.getAsJsonArray("key").size() < 2) {
            throw new IllegalArgumentException("SM2 AKE response does not contain both EC points");
        }
        String partnerPublicHex = param.getAsJsonArray("key").get(0).getAsString();
        String partnerRHex = param.getAsJsonArray("key").get(1).getAsString();
        if (partnerPublicHex.isEmpty() || partnerRHex.isEmpty()) {
            throw new IllegalArgumentException("SM2 AKE response does not contain both EC points");
        }
        Point partnerPublicKey = pointFromHex(partnerPublicHex);
        Point partnerR = pointFromHex(partnerRHex);
        String partnerOwnerId = param.has("sn") ? param.get("sn").getAsString() : "";
        byte[] partnerZ = computeZ(partnerOwnerId, partnerPublicKey);
        byte[] sharedKey = keyAgreement(
                state.privateKey,
                state.r,
                state.R,
                state.Z,
                partnerPublicKey,
                partnerR,
                partnerZ,
                true);
        byte[] derived = Sm4.deriveKeyAndIv(sharedKey);
        return new Session(
                sharedKey,
                Sm4.copyRange(derived, 0, 16),
                Sm4.copyRange(derived, 16, 16),
                partnerPublicKey,
                partnerR,
                partnerZ);
    }

    public static byte[] keyAgreement(
            BigInteger privateKey,
            BigInteger r,
            Point R,
            byte[] Z,
            Point partnerPublicKey,
            Point partnerR,
            byte[] partnerZ,
            boolean initiator) {
        for (Point point : new Point[]{R, partnerPublicKey, partnerR}) {
            if (!isOnCurve(point)) throw new IllegalArgumentException("Invalid EC point");
        }
        if (Z == null || Z.length != 32 || partnerZ == null || partnerZ.length != 32) {
            throw new IllegalArgumentException("SM2 Z values must be 32 bytes");
        }

        int half = N.bitLength() >> 1;
        BigInteger w1 = BigInteger.ONE.shiftLeft(half - 1);
        BigInteger w2 = w1.subtract(BigInteger.ONE);
        BigInteger x = w1.add(R.x.and(w2));
        BigInteger t = privateKey.add(r.multiply(x)).mod(N);
        BigInteger partnerX = w1.add(partnerR.x.and(w2));
        Point partnerPoint = add(partnerPublicKey, multiply(partnerR, partnerX));
        Point V = multiply(partnerPoint, t);
        if (V == null) throw new IllegalArgumentException("SM2 key agreement produced point at infinity");

        byte[] zBytes = Sm4.concat(
                toFixed(V.x),
                toFixed(V.y),
                initiator ? Z : partnerZ,
                initiator ? partnerZ : Z);
        return Sm3.kdf(zBytes, 32);
    }

    public static byte[] computeZ(String ownerId, Point publicKey) {
        byte[] id = ownerId.getBytes(StandardCharsets.UTF_8);
        int bitLength = id.length * 8;
        if (bitLength > 0xffff) throw new IllegalArgumentException("SM2 owner ID is too long");
        byte[] prefix = new byte[]{(byte) (bitLength >>> 8), (byte) bitLength};
        return Sm3.digest(Sm4.concat(
                prefix,
                id,
                toFixed(A),
                toFixed(B),
                toFixed(GX),
                toFixed(GY),
                toFixed(publicKey.x),
                toFixed(publicKey.y)));
    }

    public static Point add(Point left, Point right) {
        if (left == null) return right;
        if (right == null) return left;
        BigInteger lambda;
        if (left.x.equals(right.x)) {
            if (left.y.add(right.y).mod(P).signum() == 0) return null;
            BigInteger numerator = left.x.multiply(left.x).multiply(BigInteger.valueOf(3)).add(A).mod(P);
            BigInteger denominator = left.y.shiftLeft(1).mod(P).modInverse(P);
            lambda = numerator.multiply(denominator).mod(P);
        } else {
            BigInteger numerator = right.y.subtract(left.y).mod(P);
            BigInteger denominator = right.x.subtract(left.x).mod(P).modInverse(P);
            lambda = numerator.multiply(denominator).mod(P);
        }
        BigInteger x = lambda.multiply(lambda).subtract(left.x).subtract(right.x).mod(P);
        BigInteger y = lambda.multiply(left.x.subtract(x)).subtract(left.y).mod(P);
        return new Point(x, y);
    }

    public static Point multiply(Point point, BigInteger scalar) {
        if (scalar.signum() < 0) throw new IllegalArgumentException("Scalar must be nonnegative");
        Point result = null;
        Point addend = point;
        BigInteger value = scalar;
        while (value.signum() > 0) {
            if (value.testBit(0)) result = add(result, addend);
            addend = add(addend, addend);
            value = value.shiftRight(1);
        }
        return result;
    }

    public static boolean isOnCurve(Point point) {
        if (point == null) return true;
        if (point.x.signum() < 0 || point.x.compareTo(P) >= 0
                || point.y.signum() < 0 || point.y.compareTo(P) >= 0) {
            return false;
        }
        BigInteger left = point.y.multiply(point.y).mod(P);
        BigInteger right = point.x.pow(3).add(A.multiply(point.x)).add(B).mod(P);
        return left.equals(right);
    }

    public static Point pointFromHex(String text) {
        if (text == null || !text.matches("(?i)^[0-9a-f]{128}$")) {
            throw new IllegalArgumentException("Expected a 128-character EC point");
        }
        Point point = new Point(
                new BigInteger(text.substring(0, 64), 16),
                new BigInteger(text.substring(64), 16));
        if (!isOnCurve(point)) throw new IllegalArgumentException("EC point is not on the selected curve");
        return point;
    }

    public static String pointToHex(Point point) {
        if (point == null) throw new IllegalArgumentException("Cannot encode point at infinity");
        return hex(toFixed(point.x)) + hex(toFixed(point.y));
    }

    public static BigInteger randomScalar() {
        for (; ; ) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            BigInteger value = new BigInteger(1, bytes);
            if (value.signum() > 0 && value.compareTo(N) < 0) return value;
        }
    }

    public static BigInteger fromPrivateKeyHex(String text) {
        if (text == null || !text.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("SM2 private key must be 64 hex characters");
        }
        BigInteger value = new BigInteger(text, 16);
        if (value.signum() <= 0 || value.compareTo(N) >= 0) {
            throw new IllegalArgumentException("SM2 private key is out of range");
        }
        return value;
    }

    public static Point publicKeyFromPrivate(BigInteger privateKey) {
        return multiply(G, privateKey);
    }

    public static byte[] toFixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) start++;
        int length = raw.length - start;
        if (length > 32) throw new IllegalArgumentException("Integer does not fit 32 bytes");
        byte[] out = new byte[32];
        System.arraycopy(raw, start, out, 32 - length, length);
        return out;
    }

    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format("%02X", b & 0xff));
        return out.toString();
    }

    private static BigInteger hex(String value) {
        return new BigInteger(value, 16);
    }
}
