package com.hanwang.facekey.thin.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Standard Base64 codec with the exact wrapping used by
 * {@code android.util.Base64.encodeToString(bytes, Base64.DEFAULT)}:
 * 76-character lines, LF separators and a trailing LF.
 *
 * <p>Implemented in pure Java so the protocol layer can be unit-tested on the JVM
 * and so it does not depend on API 26's {@code java.util.Base64}.</p>
 */
public final class Base64Codec {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] DECODE = new int[128];

    static {
        for (int i = 0; i < DECODE.length; i++) DECODE[i] = -1;
        for (int i = 0; i < ALPHABET.length; i++) DECODE[ALPHABET[i]] = i;
    }

    private Base64Codec() {
    }

    public static String encodeAndroid(byte[] data) {
        String raw = encode(data);
        if (raw.isEmpty()) return "";
        StringBuilder wrapped = new StringBuilder(raw.length() + (raw.length() / 76) + 2);
        for (int offset = 0; offset < raw.length(); offset += 76) {
            int end = Math.min(offset + 76, raw.length());
            wrapped.append(raw, offset, end).append('\n');
        }
        return wrapped.toString();
    }

    public static String encode(byte[] data) {
        if (data == null) throw new IllegalArgumentException("data == null");
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            out.append(ALPHABET[b0 >>> 2]);
            out.append(ALPHABET[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            out.append(i + 1 < data.length
                    ? ALPHABET[((b1 & 0x0f) << 2) | (b2 >>> 6)]
                    : '=');
            out.append(i + 2 < data.length ? ALPHABET[b2 & 0x3f] : '=');
        }
        return out.toString();
    }

    public static byte[] decode(String text) {
        if (text == null) throw new IllegalArgumentException("Base64 text is null");
        String compact = text.replaceAll("\\s+", "");
        if (compact.isEmpty()) return new byte[0];
        if (compact.length() % 4 != 0) {
            throw new IllegalArgumentException("Base64 length is not a multiple of 4");
        }
        int padding = 0;
        if (compact.endsWith("==")) padding = 2;
        else if (compact.endsWith("=")) padding = 1;
        if (compact.indexOf('=') >= 0 && compact.indexOf('=') < compact.length() - padding) {
            throw new IllegalArgumentException("Base64 padding is not at the end");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream((compact.length() / 4) * 3);
        for (int i = 0; i < compact.length(); i += 4) {
            int c0 = decodeChar(compact.charAt(i));
            int c1 = decodeChar(compact.charAt(i + 1));
            int c2 = compact.charAt(i + 2) == '=' ? 0 : decodeChar(compact.charAt(i + 2));
            int c3 = compact.charAt(i + 3) == '=' ? 0 : decodeChar(compact.charAt(i + 3));
            out.write((c0 << 2) | (c1 >>> 4));
            if (compact.charAt(i + 2) != '=') out.write(((c1 & 0x0f) << 4) | (c2 >>> 2));
            if (compact.charAt(i + 3) != '=') out.write(((c2 & 0x03) << 6) | c3);
        }
        return out.toByteArray();
    }

    private static int decodeChar(char ch) {
        int value = ch < DECODE.length ? DECODE[ch] : -1;
        if (value < 0) throw new IllegalArgumentException("Invalid Base64 character");
        return value;
    }
}
