package com.hanwang.facekey.thin.crypto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertFalse;

public class SmCryptoTest {
    private static byte[] bytes(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    @Test
    public void sm3MatchesAbcVector() {
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
                hex(Sm3.digest("abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void sm4MatchesStandardVector() {
        byte[] key = bytes("0123456789abcdeffedcba9876543210");
        byte[] plain = bytes("0123456789abcdeffedcba9876543210");
        assertEquals("681edf34d206965e86b3e94f536e4246", hex(Sm4.encryptBlock(plain, key)));
    }

    @Test
    public void sm4OfbPkcs7RoundTripsAndRejectsBadPadding() {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        java.util.Arrays.fill(key, (byte) 0x11);
        java.util.Arrays.fill(iv, (byte) 0x22);
        for (byte[] value : new byte[][]{
                "hello".getBytes(StandardCharsets.UTF_8),
                new byte[16],
                new byte[32]
        }) {
            byte[] encrypted = Sm4.ofbEncrypt(value, key, iv);
            assertEquals(0, encrypted.length % 16);
            assertArrayEquals(value, Sm4.ofbDecrypt(encrypted, key, iv));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Sm4.pkcs7Unpad(new byte[16]));
    }

    @Test
    public void kdfIsCounterBasedAndDeterministic() {
        byte[] first = Sm3.kdf("shared secret".getBytes(StandardCharsets.UTF_8), 48);
        byte[] second = Sm3.kdf("shared secret".getBytes(StandardCharsets.UTF_8), 48);
        assertEquals(48, first.length);
        assertArrayEquals(first, second);
        assertFalse(java.util.Arrays.equals(
                java.util.Arrays.copyOfRange(first, 0, 16),
                java.util.Arrays.copyOfRange(first, 32, 48)));
    }

    @Test
    public void frameCodecHandlesFragmentationCoalescingAndZeroFrames() {
        byte[] first = FrameCodec.encode("first".getBytes(StandardCharsets.UTF_8));
        byte[] second = FrameCodec.encode("second".getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[first.length + 4 + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length + 4, second.length);

        FrameCodec.Decoder decoder = new FrameCodec.Decoder();
        java.util.ArrayList<byte[]> frames = new java.util.ArrayList<>();
        for (byte value : combined) {
            frames.addAll(decoder.push(new byte[]{value}));
        }
        assertEquals(2, frames.size());
        assertEquals("first", new String(frames.get(0), StandardCharsets.UTF_8));
        assertEquals("second", new String(frames.get(1), StandardCharsets.UTF_8));
        assertEquals(0, decoder.pending().length);
    }

    @Test
    public void frameDecoderRejectsOversizedFrame() {
        FrameCodec.Decoder decoder = new FrameCodec.Decoder();
        byte[] header = new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertThrows(IllegalArgumentException.class, () -> decoder.push(header));
    }
}
