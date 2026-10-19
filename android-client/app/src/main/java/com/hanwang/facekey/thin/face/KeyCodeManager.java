package com.hanwang.facekey.thin.face;

import android.content.Context;
import android.net.Uri;

import com.hanvon.faceRec.FaceCoreHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * KeyCode lifecycle. The source APK fetches a device-bound key from Hanvon's
 * licensing service and stores it as hwKeyCode.dat. This client supports both
 * importing a vendor-issued file and the explicit, user-confirmed vendor fetch.
 */
public final class KeyCodeManager {
    public static final String FILE_NAME = "hwKeyCode.dat";
    private static final int MAX_KEYCODE_BYTES = 64 * 1024;
    private static final String VENDOR_HOST = "182.92.162.37";
    private static final int VENDOR_PORT = 8888;

    private KeyCodeManager() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static byte[] load(Context context) throws KeyCodeException {
        File file = file(context);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_KEYCODE_BYTES) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            return readAll(in, MAX_KEYCODE_BYTES);
        } catch (IOException e) {
            throw new KeyCodeException("KEYCODE_READ_FAILED", "读取 KeyCode 失败", e);
        }
    }

    public static void save(Context context, byte[] keyCode) throws KeyCodeException {
        if (keyCode == null || keyCode.length == 0 || keyCode.length > MAX_KEYCODE_BYTES) {
            throw new KeyCodeException("KEYCODE_INVALID", "KeyCode 内容为空或过大");
        }
        File target = file(context);
        File temp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(keyCode);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new KeyCodeException("KEYCODE_WRITE_FAILED", "保存 KeyCode 失败", e);
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new KeyCodeException("KEYCODE_WRITE_FAILED", "保存 KeyCode 失败");
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new KeyCodeException("KEYCODE_WRITE_FAILED", "保存 KeyCode 失败");
        }
    }

    public static byte[] importFromUri(Context context, Uri uri) throws KeyCodeException {
        if (uri == null) throw new KeyCodeException("KEYCODE_INVALID", "未选择 KeyCode 文件");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new KeyCodeException("KEYCODE_INVALID", "无法读取 KeyCode 文件");
            byte[] value = readAll(in, MAX_KEYCODE_BYTES);
            save(context, value);
            return value;
        } catch (IOException e) {
            throw new KeyCodeException("KEYCODE_READ_FAILED", "读取 KeyCode 文件失败", e);
        }
    }

    /**
     * Replicates HWFaceClient.InitFaceClient: ask the native SDK for a request
     * key, exchange it with Hanvon's licensing endpoint, then persist the reply.
     * Only call after explicit user confirmation.
     */
    public static byte[] fetchFromVendor(Context context) throws KeyCodeException {
        byte[] request = new byte[256];
        int[] length = new int[]{request.length};
        int result;
        try {
            result = FaceCoreHelper.HwGetKeyCode(request, length, context);
        } catch (Throwable t) {
            throw new KeyCodeException("KEYCODE_NATIVE_FAILED", "获取设备 KeyCode 失败", t);
        }
        if (result != 0 || length[0] <= 0 || length[0] > request.length) {
            throw new KeyCodeException("KEYCODE_REQUEST_FAILED",
                    "获取设备 KeyCode 失败（" + result + "）");
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(VENDOR_HOST, VENDOR_PORT), 10000);
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            writeInt(out, length[0]);
            out.write(request, 0, length[0]);
            out.flush();

            for (int attempt = 0; attempt < 8; attempt++) {
                int responseLength = readInt(in);
                if (responseLength == 0) continue;
                if (responseLength < 0 || responseLength > MAX_KEYCODE_BYTES) {
                    throw new KeyCodeException("KEYCODE_SERVER_INVALID", "授权服务返回的 KeyCode 长度无效");
                }
                byte[] keyCode = readExactly(in, responseLength);
                save(context, keyCode);
                return keyCode;
            }
            throw new KeyCodeException("KEYCODE_SERVER_INVALID", "授权服务未返回有效 KeyCode");
        } catch (IOException e) {
            throw new KeyCodeException("KEYCODE_SERVER_FAILED", "连接汉王授权服务失败", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String status(Context context) {
        File file = file(context);
        if (file.isFile() && file.length() > 0 && file.length() <= MAX_KEYCODE_BYTES) {
            return "KeyCode 已加载（" + file.length() + " 字节）";
        }
        return "KeyCode 未找到或文件无效，请导入或从授权服务获取";
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > max) {
                throw new IOException("KeyCode 文件超过大小限制");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] bytes = readExactly(in, 4);
        return ((bytes[0] & 0xff) << 24)
                | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) throw new IOException("连接已关闭");
            offset += read;
        }
        return data;
    }

    public static final class KeyCodeException extends Exception {
        public final String code;

        public KeyCodeException(String code, String message) {
            super(message);
            this.code = code;
        }

        public KeyCodeException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }
}
