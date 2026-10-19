package com.hanwang.facekey.thin.crypto;

import java.io.ByteArrayOutputStream;

/** SM4 block cipher (GB/T 32907-2016) with the APK's OFB + PKCS7 construction. */
public final class Sm4 {
    private static final int[] FK = {
            0xa3b1bac6, 0x56aa3350, 0x677d9197, 0xb27022dc
    };
    private static final int[] CK = new int[32];
    private static final int[] SBOX = {
            0xd6, 0x90, 0xe9, 0xfe, 0xcc, 0xe1, 0x3d, 0xb7, 0x16, 0xb6, 0x14, 0xc2, 0x28, 0xfb, 0x2c, 0x05,
            0x2b, 0x67, 0x9a, 0x76, 0x2a, 0xbe, 0x04, 0xc3, 0xaa, 0x44, 0x13, 0x26, 0x49, 0x86, 0x06, 0x99,
            0x9c, 0x42, 0x50, 0xf4, 0x91, 0xef, 0x98, 0x7a, 0x33, 0x54, 0x0b, 0x43, 0xed, 0xcf, 0xac, 0x62,
            0xe4, 0xb3, 0x1c, 0xa9, 0xc9, 0x08, 0xe8, 0x95, 0x80, 0xdf, 0x94, 0xfa, 0x75, 0x8f, 0x3f, 0xa6,
            0x47, 0x07, 0xa7, 0xfc, 0xf3, 0x73, 0x17, 0xba, 0x83, 0x59, 0x3c, 0x19, 0xe6, 0x85, 0x4f, 0xa8,
            0x68, 0x6b, 0x81, 0xb2, 0x71, 0x64, 0xda, 0x8b, 0xf8, 0xeb, 0x0f, 0x4b, 0x70, 0x56, 0x9d, 0x35,
            0x1e, 0x24, 0x0e, 0x5e, 0x63, 0x58, 0xd1, 0xa2, 0x25, 0x22, 0x7c, 0x3b, 0x01, 0x21, 0x78, 0x87,
            0xd4, 0x00, 0x46, 0x57, 0x9f, 0xd3, 0x27, 0x52, 0x4c, 0x36, 0x02, 0xe7, 0xa0, 0xc4, 0xc8, 0x9e,
            0xea, 0xbf, 0x8a, 0xd2, 0x40, 0xc7, 0x38, 0xb5, 0xa3, 0xf7, 0xf2, 0xce, 0xf9, 0x61, 0x15, 0xa1,
            0xe0, 0xae, 0x5d, 0xa4, 0x9b, 0x34, 0x1a, 0x55, 0xad, 0x93, 0x32, 0x30, 0xf5, 0x8c, 0xb1, 0xe3,
            0x1d, 0xf6, 0xe2, 0x2e, 0x82, 0x66, 0xca, 0x60, 0xc0, 0x29, 0x23, 0xab, 0x0d, 0x53, 0x4e, 0x6f,
            0xd5, 0xdb, 0x37, 0x45, 0xde, 0xfd, 0x8e, 0x2f, 0x03, 0xff, 0x6a, 0x72, 0x6d, 0x6c, 0x5b, 0x51,
            0x8d, 0x1b, 0xaf, 0x92, 0xbb, 0xdd, 0xbc, 0x7f, 0x11, 0xd9, 0x5c, 0x41, 0x1f, 0x10, 0x5a, 0xd8,
            0x0a, 0xc1, 0x31, 0x88, 0xa5, 0xcd, 0x7b, 0xbd, 0x2d, 0x74, 0xd0, 0x12, 0xb8, 0xe5, 0xb4, 0xb0,
            0x89, 0x69, 0x97, 0x4a, 0x0c, 0x96, 0x77, 0x7e, 0x65, 0xb9, 0xf1, 0x09, 0xc5, 0x6e, 0xc6, 0x84,
            0x18, 0xf0, 0x7d, 0xec, 0x3a, 0xdc, 0x4d, 0x20, 0x79, 0xee, 0x5f, 0x3e, 0xd7, 0xcb, 0x39, 0x48
    };

    static {
        for (int i = 0; i < 32; i++) {
            int base = i * 4;
            CK[i] = (((base * 7) & 0xff) << 24)
                    | ((((base + 1) * 7) & 0xff) << 16)
                    | ((((base + 2) * 7) & 0xff) << 8)
                    | (((base + 3) * 7) & 0xff);
        }
    }

    private Sm4() {
    }

    public static byte[] ofbEncrypt(byte[] plaintext, byte[] key, byte[] iv) {
        return ofb(plaintext, key, iv, true);
    }

    public static byte[] ofbDecrypt(byte[] ciphertext, byte[] key, byte[] iv) {
        return ofb(ciphertext, key, iv, false);
    }

    private static byte[] ofb(byte[] input, byte[] key, byte[] iv, boolean encrypt) {
        if (key == null || key.length != 16 || iv == null || iv.length != 16) {
            throw new IllegalArgumentException("SM4 key and IV must both be 16 bytes");
        }
        byte[] data = encrypt ? pkcs7Pad(input) : input;
        if (!encrypt && data.length % 16 != 0) {
            throw new IllegalArgumentException("Padded SM4/OFB ciphertext must be block-aligned");
        }
        int[] roundKeys = expandKey(key);
        byte[] out = new byte[data.length];
        byte[] feedback = iv.clone();
        for (int offset = 0; offset < data.length; offset += 16) {
            byte[] stream = encryptBlock(feedback, roundKeys);
            feedback = stream;
            for (int i = 0; i < 16; i++) {
                out[offset + i] = (byte) (data[offset + i] ^ stream[i]);
            }
        }
        return encrypt ? out : pkcs7Unpad(out);
    }

    public static byte[] encryptBlock(byte[] block, byte[] key) {
        if (block == null || block.length != 16) throw new IllegalArgumentException("SM4 block must be 16 bytes");
        return encryptBlock(block, expandKey(key));
    }

    private static byte[] encryptBlock(byte[] block, int[] roundKeys) {
        int[] x = new int[36];
        x[0] = readInt(block, 0);
        x[1] = readInt(block, 4);
        x[2] = readInt(block, 8);
        x[3] = readInt(block, 12);
        for (int i = 0; i < 32; i++) {
            x[i + 4] = x[i] ^ t(x[i + 1] ^ x[i + 2] ^ x[i + 3] ^ roundKeys[i]);
        }
        byte[] out = new byte[16];
        writeInt(out, 0, x[35]);
        writeInt(out, 4, x[34]);
        writeInt(out, 8, x[33]);
        writeInt(out, 12, x[32]);
        return out;
    }

    private static int[] expandKey(byte[] key) {
        if (key == null || key.length != 16) throw new IllegalArgumentException("SM4 key must be 16 bytes");
        int[] k = new int[36];
        for (int i = 0; i < 4; i++) k[i] = readInt(key, i * 4) ^ FK[i];
        int[] rk = new int[32];
        for (int i = 0; i < 32; i++) {
            k[i + 4] = k[i] ^ tPrime(k[i + 1] ^ k[i + 2] ^ k[i + 3] ^ CK[i]);
            rk[i] = k[i + 4];
        }
        return rk;
    }

    private static int t(int value) {
        int b = sbox(value);
        return b ^ rol(b, 2) ^ rol(b, 10) ^ rol(b, 18) ^ rol(b, 24);
    }

    private static int tPrime(int value) {
        int b = sbox(value);
        return b ^ rol(b, 13) ^ rol(b, 23);
    }

    private static int sbox(int value) {
        return (SBOX[(value >>> 24) & 0xff] << 24)
                | (SBOX[(value >>> 16) & 0xff] << 16)
                | (SBOX[(value >>> 8) & 0xff] << 8)
                | SBOX[value & 0xff];
    }

    private static int rol(int value, int bits) {
        return (value << bits) | (value >>> (32 - bits));
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    public static byte[] pkcs7Pad(byte[] input) {
        int padding = 16 - (input.length % 16);
        byte[] out = new byte[input.length + padding];
        System.arraycopy(input, 0, out, 0, input.length);
        for (int i = input.length; i < out.length; i++) out[i] = (byte) padding;
        return out;
    }

    public static byte[] pkcs7Unpad(byte[] input) {
        if (input.length == 0 || input.length % 16 != 0) {
            throw new IllegalArgumentException("Invalid PKCS7 data length");
        }
        int padding = input[input.length - 1] & 0xff;
        if (padding < 1 || padding > 16) throw new IllegalArgumentException("Invalid PKCS7 padding");
        for (int i = input.length - padding; i < input.length; i++) {
            if ((input[i] & 0xff) != padding) throw new IllegalArgumentException("Invalid PKCS7 padding");
        }
        byte[] out = new byte[input.length - padding];
        System.arraycopy(input, 0, out, 0, out.length);
        return out;
    }

    public static byte[] deriveKeyAndIv(byte[] sharedSecret) {
        if (sharedSecret.length == 32) return sharedSecret.clone();
        return Sm3.kdf(sharedSecret, 32);
    }

    public static byte[] copyRange(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    public static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.write(array, 0, array.length);
        return out.toByteArray();
    }
}
