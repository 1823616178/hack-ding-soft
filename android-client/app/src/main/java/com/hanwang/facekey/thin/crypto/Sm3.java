package com.hanwang.facekey.thin.crypto;

import java.io.ByteArrayOutputStream;

/** SM3 hash (GB/T 32905-2016). */
public final class Sm3 {
    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    private Sm3() {
    }

    public static byte[] digest(byte[] data) {
        if (data == null) throw new IllegalArgumentException("data == null");
        long bitLength = ((long) data.length) << 3;
        int padding = data.length % 64 < 56
                ? 56 - data.length % 64
                : 120 - data.length % 64;
        byte[] padded = new byte[data.length + padding + 8];
        System.arraycopy(data, 0, padded, 0, data.length);
        padded[data.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) (bitLength >>> (8 * i));
        }

        int[] v = IV.clone();
        int[] w = new int[68];
        int[] wp = new int[64];
        for (int offset = 0; offset < padded.length; offset += 64) {
            for (int i = 0; i < 16; i++) {
                int p = offset + i * 4;
                w[i] = ((padded[p] & 0xff) << 24)
                        | ((padded[p + 1] & 0xff) << 16)
                        | ((padded[p + 2] & 0xff) << 8)
                        | (padded[p + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                w[i] = p1(w[i - 16] ^ w[i - 9] ^ rol(w[i - 3], 15))
                        ^ rol(w[i - 13], 7) ^ w[i - 6];
            }
            for (int i = 0; i < 64; i++) wp[i] = w[i] ^ w[i + 4];

            int a = v[0], b = v[1], c = v[2], d = v[3];
            int e = v[4], f = v[5], g = v[6], h = v[7];

            for (int i = 0; i < 64; i++) {
                int t = i < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = rol(rol(a, 12) + e + rol(t, i & 31), 7);
                int ss2 = ss1 ^ rol(a, 12);
                int tt1 = ff(a, b, c, i) + d + ss2 + wp[i];
                int tt2 = gg(e, f, g, i) + h + ss1 + w[i];
                d = c;
                c = rol(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = rol(f, 19);
                f = e;
                e = p0(tt2);
            }

            v[0] ^= a;
            v[1] ^= b;
            v[2] ^= c;
            v[3] ^= d;
            v[4] ^= e;
            v[5] ^= f;
            v[6] ^= g;
            v[7] ^= h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (v[i] >>> 24);
            out[i * 4 + 1] = (byte) (v[i] >>> 16);
            out[i * 4 + 2] = (byte) (v[i] >>> 8);
            out[i * 4 + 3] = (byte) v[i];
        }
        return out;
    }

    /** SM3 based counter KDF used by the APK AKE. */
    public static byte[] kdf(byte[] input, int outputLength) {
        if (outputLength < 0) throw new IllegalArgumentException("outputLength < 0");
        ByteArrayOutputStream out = new ByteArrayOutputStream(outputLength);
        int counter = 1;
        while (out.size() < outputLength) {
            byte[] block = new byte[input.length + 4];
            System.arraycopy(input, 0, block, 0, input.length);
            block[input.length] = (byte) (counter >>> 24);
            block[input.length + 1] = (byte) (counter >>> 16);
            block[input.length + 2] = (byte) (counter >>> 8);
            block[input.length + 3] = (byte) counter;
            byte[] digest = digest(block);
            int take = Math.min(digest.length, outputLength - out.size());
            out.write(digest, 0, take);
            counter++;
        }
        return out.toByteArray();
    }

    private static int ff(int x, int y, int z, int round) {
        return round < 16 ? x ^ y ^ z : (x & y) | (x & z) | (y & z);
    }

    private static int gg(int x, int y, int z, int round) {
        return round < 16 ? x ^ y ^ z : (x & y) | ((~x) & z);
    }

    private static int p0(int x) {
        return x ^ rol(x, 9) ^ rol(x, 17);
    }

    private static int p1(int x) {
        return x ^ rol(x, 15) ^ rol(x, 23);
    }

    private static int rol(int value, int bits) {
        int shift = bits & 31;
        return shift == 0 ? value : (value << shift) | (value >>> (32 - shift));
    }
}
