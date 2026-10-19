# JNI classes and native methods are referenced by name from libHWFaceRecogLibSDK.so.
-keep class com.hanvon.faceRec.FaceCoreHelper { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
