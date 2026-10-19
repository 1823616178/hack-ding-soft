package com.hanvon.faceRec;

import android.content.Context;

/**
 * Exact JNI bridge expected by libHWFaceRecogLibSDK.so.
 *
 * <p>The native library registers these methods for the class name
 * {@code com.hanvon.faceRec.FaceCoreHelper}; do not rename or move this class.
 * This class is extracted from the source APK's interface and contains no
 * vendor code. Use only with a valid Hanvon SDK license.</p>
 */
public final class FaceCoreHelper {
    static {
        System.loadLibrary("HWFaceRecogLibSDK");
    }

    private FaceCoreHelper() {
    }

    public static native int HwCompareFeature(long[] handle, byte[] feature1, byte[] feature2, float[] score);

    public static native int HwDetectMultiFaceAndEyeEx(
            long[] handle, byte[] gray, int width, int height, int[] facePos, int[] faceCount);

    public static native int HwGetFaceFeatureEx(
            long[] handle, byte[] gray, int width, int height, int[] facePos, byte[] feature);

    public static native int HwGetFeatureSize(long[] handle, int[] size);

    public static native int HwGetKeyCode(byte[] keyCode, int[] size, Context context);

    public static native int HwInitFace(long[] handle, byte[] keyCode, Context context);

    public static native int HwJudgeMask(
            long[] handle, byte[] rgb, int width, int height, int[] facePos, int[] result, float[] score);

    public static native int HwNV21TORGB(int width, int height, byte[] nv21, byte[] rgb);

    public static native int HwReleaseFace(long[] handle);

    public static native int HwSetPortrait(long[] handle, int portrait);
}
