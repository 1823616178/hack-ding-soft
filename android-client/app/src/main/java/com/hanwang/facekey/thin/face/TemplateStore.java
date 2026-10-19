package com.hanwang.facekey.thin.face;

import android.content.Context;

import com.hanwang.facekey.thin.protocol.Base64Codec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Employee face templates stored only in app-private storage.
 * The files are base64 JPEGs, exactly as returned by getEmployeeFeaturePicToMobi.do.
 */
public final class TemplateStore {
    private static final String DIR = "face";
    private static final String STANDARD = "standard.b64";
    private static final String MASK = "mask.b64";
    private static final int MAX_TEMPLATE_CHARS = 16 * 1024 * 1024;

    private TemplateStore() {
    }

    public static synchronized void save(Context context, String standard, String mask)
            throws IOException {
        if (standard == null || standard.trim().isEmpty()) {
            throw new IOException("标准人脸模板为空");
        }
        validateTemplate(standard, "标准人脸模板");
        if (mask != null && !mask.trim().isEmpty()) validateTemplate(mask, "口罩模板");
        File dir = dir(context);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建模板目录");
        writeAtomic(new File(dir, STANDARD), standard.getBytes("UTF-8"));
        if (mask != null && !mask.trim().isEmpty()) {
            writeAtomic(new File(dir, MASK), mask.getBytes("UTF-8"));
        } else {
            File maskFile = new File(dir, MASK);
            if (maskFile.exists() && !maskFile.delete()) {
                throw new IOException("无法清除旧口罩模板");
            }
        }
    }

    public static synchronized String loadStandard(Context context) throws IOException {
        return read(new File(dir(context), STANDARD));
    }

    public static synchronized String loadMask(Context context) throws IOException {
        return read(new File(dir(context), MASK));
    }

    public static synchronized boolean hasStandard(Context context) {
        File file = new File(dir(context), STANDARD);
        return file.isFile() && file.length() > 0;
    }

    public static synchronized String status(Context context) {
        return hasStandard(context) ? "模板已下载" : "模板未下载";
    }

    public static synchronized void clear(Context context) {
        File dir = dir(context);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void validateTemplate(String base64, String label) throws IOException {
        if (base64.length() > MAX_TEMPLATE_CHARS) throw new IOException(label + "过大");
        try {
            byte[] bytes = Base64Codec.decode(base64);
            if (bytes.length == 0) throw new IOException(label + "为空");
        } catch (IllegalArgumentException e) {
            throw new IOException(label + "不是有效的 Base64 数据");
        }
    }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    private static String read(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0) return null;
        if (file.length() > MAX_TEMPLATE_CHARS) throw new IOException("模板文件过大");
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("无法替换旧模板");
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new IOException("无法保存模板");
        }
    }
}
