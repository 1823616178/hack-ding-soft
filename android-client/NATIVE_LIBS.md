# Native 库来源与完整性记录

本工程不包含厂商 native 库源码，只从用户提供的源 APK 中提取已存在的 arm64-v8a
运行库。使用前请确认你拥有汉王 SDK 的有效授权，并遵守其许可条款。

## 源 APK

```text
文件：../base.apk
SHA-256：43a06ae8a19aac0859d558fbfb2a688a533cf0498230ac1fc335624d5cd1171e
包名：com.hanwang.facekey
版本：1.0.15.79.20240816（versionCode 41）
minSdk 19 / targetSdk 28
```

## 提取的库

```text
lib/arm64-v8a/libHWFaceRecogLibSDK.so
SHA-256：9717d3c93d0d40388deadb6ec52c9cbfda5a327bd02ab826920d7e71f911d014

lib/arm64-v8a/libc++_shared.so
SHA-256：ecb1cc1de2c0121efe11f10c5b84bbbf224fbdc047ae675affeaa8e3bf9b8e47
```

`libHWFaceRecogLibSDK.so` 的 ELF `NEEDED`：

```text
libc++_shared.so
liblog.so
libc.so
libm.so
libstdc++.so
libdl.so
```

其中 `libc++_shared.so` 随 APK 一起打包；其余为 Android 系统库。

## 提取方式

Gradle 任务 `extractNativeLibs` 在 `preBuild` 前执行，等价于：

```bash
bash tools/extract-native-libs.sh
```

输出目录 `app/src/main/jniLibs/arm64-v8a/` 已被 `.gitignore` 排除，不进入仓库。

## JNI 契约

native 库通过 `JNI_OnLoad` 动态注册，字符串中包含：

```text
com/hanvon/faceRec/FaceCoreHelper
HwGetKeyCode
HwInitFace
HwDetectMultiFaceAndEyeEx
HwGetFaceFeatureEx
HwGetFeatureSize
HwCompareFeature
HwJudgeMask
HwNV21TORGB
HwReleaseFace
HwSetPortrait
```

因此 `FaceCoreHelper` 的包名、类名和方法签名必须保持不变；`proguard-rules.pro`
已加入 keep 规则。
