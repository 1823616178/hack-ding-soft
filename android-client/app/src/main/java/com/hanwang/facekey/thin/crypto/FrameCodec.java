package com.hanwang.facekey.thin.crypto;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Four-byte big-endian MINA frame codec, matching the APK transport. */
public final class FrameCodec {
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private FrameCodec() {
    }

    public static byte[] encode(byte[] payload) {
        byte[] out = new byte[payload.length + 4];
        out[0] = (byte) (payload.length >>> 24);
        out[1] = (byte) (payload.length >>> 16);
        out[2] = (byte) (payload.length >>> 8);
        out[3] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    public static final class Decoder {
        private byte[] buffer = new byte[0];

        public synchronized java.util.List<byte[]> push(byte[] chunk) {
            byte[] next = new byte[buffer.length + chunk.length];
            System.arraycopy(buffer, 0, next, 0, buffer.length);
            System.arraycopy(chunk, 0, next, buffer.length, chunk.length);
            buffer = next;

            java.util.List<byte[]> frames = new java.util.ArrayList<>();
            int offset = 0;
            while (buffer.length - offset >= 4) {
                int size = ((buffer[offset] & 0xff) << 24)
                        | ((buffer[offset + 1] & 0xff) << 16)
                        | ((buffer[offset + 2] & 0xff) << 8)
                        | (buffer[offset + 3] & 0xff);
                if (size < 0 || size > MAX_FRAME_BYTES) {
                    throw new IllegalArgumentException("Frame size is invalid: " + size);
                }
                if (size == 0) {
                    offset += 4;
                    continue;
                }
                if (buffer.length - offset < size + 4) break;
                byte[] frame = new byte[size];
                System.arraycopy(buffer, offset + 4, frame, 0, size);
                frames.add(frame);
                offset += size + 4;
            }
            if (offset > 0) {
                buffer = Arrays.copyOfRange(buffer, offset, buffer.length);
            }
            return frames;
        }

        public synchronized byte[] pending() {
            return buffer.clone();
        }

        public synchronized void clear() {
            Arrays.fill(buffer, (byte) 0);
            buffer = new byte[0];
        }
    }

    public static byte[] concat(java.util.List<byte[]> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : frames) out.write(frame, 0, frame.length);
        return out.toByteArray();
    }
}
