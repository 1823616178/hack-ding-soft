# eFaceGo Android 薄客户端

仅用于自己管理或明确授权的测试环境与账号。它保留源 APK 的 native 人脸前置流程，
并复用已经在 `web-client` 中验证过的 HTTP/TCP 协议层；不重写考勤服务端，也不绕过
服务端的人脸、围栏或时间裁决。

源 APK：`../base.apk`
包名：`com.hanwang.facekey`，版本 `1.0.15.79.20240816`（versionCode 41）

本客户端包名：`com.hanwang.facekey.thin`

---

## 1. 已实现范围

- 登录：`POST /manage/m/login.do`，字段顺序 `userName`、`pwd`
- 定位校验：`POST /manage/m/checkLocation.do`，字段顺序 `gps`、`lat`、`lng`、`token`、`time`
- HTTP 打卡：`POST /manage/m/addCard.do`，完整 13 个字段，顺序与 APK 一致
- 模板下载：`GET /manage/mobiSetting/getEmployeeFeaturePicToMobi.do?token=...&picType=1`
- TCP 打卡：MINA 四字节大端帧 + SM2 AKE + SM3 KDF + SM4/OFB/PKCS7
- Android 定位、天地图逆地理编码、失败时降级到 Android Geocoder
- 相机 NV21 640x480 预览、旋转、人脸定位、特征提取、模板比对
- 口罩检测：`pnRst[0] == 1 && pfScore[1] > 0.999`
- 本地比对阈值：`maxScore >= 0.65`
- 识别成功后 NV21 → JPEG quality 80 → Android `Base64.DEFAULT`
- 测试模式：手动地点/经纬度/时间，UI 明确标记
- 后台释放相机、检测超时、取消、错误展示、内存敏感数据清理

## 2. 未实现/明确边界

- 不复刻完整 APK UI、地图成功页、推送、VoIP、签到记录等业务页面。
- 不实现旧式非 SM2 AKE 回退；服务端返回非 SM2 响应时明确报错。
- 不关闭 HTTPS 证书校验。
- 不自动获取 KeyCode：`HwInitFace` 返回 -31 时只显示错误；获取 KeyCode 必须由用户
  点击“从汉王授权服务获取 KeyCode”并确认，或从厂商导入。
- 没有真实设备/真实考勤服务器时无法完成端到端验收（见 `ACCEPTANCE.md`）。
- 相机选择、旋转和镜像在不同设备上可能存在差异；已按源 APK 的 `CameraInfo.orientation`
  与 `FuncUtil.rotateYUV240SP_AntiClockwise` 复现。

## 3. 构建

### 3.1 前置条件

- JDK 17（AGP 8.0.2 要求）
- Android SDK：compileSdk 34、build-tools 34.0.0
- 源 APK 位于 `../base.apk`
- 可选：`../web-client/sm2.config.local.json`（TCP 通道需要）

`local.properties` 已指向本机 SDK；如路径不同请修改：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

### 3.2 本地 SM2 配置

Gradle 的 `copySm2Config` 会把：

```text
../web-client/sm2.config.local.json
```

复制到：

```text
app/src/main/assets/sm2.config.local.json
```

该文件包含 APK 的 SM2 私钥，已被 `.gitignore` 忽略。不要提交、截图或发到日志。
构建出的 debug APK 也会包含该私钥；不要把 APK 公开发布，只在本地授权测试环境使用。

### 3.3 native 库

Gradle 的 `extractNativeLibs` 会从 `../base.apk` 提取：

```text
lib/arm64-v8a/libHWFaceRecogLibSDK.so
lib/arm64-v8a/libc++_shared.so
```

到 `app/src/main/jniLibs/arm64-v8a/`，该目录同样被忽略。也可以手动运行：

```bash
bash tools/extract-native-libs.sh
```

来源与完整性记录见 `NATIVE_LIBS.md`。

### 3.4 构建命令

当前机器的全局 `GRADLE_USER_HOME` 可能指向一个带有旧代理配置的 Gradle 目录。
建议为本次构建使用项目内独立目录。工程提供了 Windows 批处理和 Bash 包装脚本：

#### Windows CMD / PowerShell：
```cmd
cd android-client
build.bat            :: 直接打包 debug APK（默认）
build.bat apk        :: 打包 debug APK
build.bat test       :: 运行全量单元测试
build.bat all        :: 运行单元测试并打包 debug APK
build.bat clean      :: 清理构建缓存
```

#### Linux / macOS / Git Bash：
```bash
cd android-client
bash build.sh all       # 运行测试并构建 debug APK
bash build.sh test      # 只运行测试
bash build.sh apk       # 只构建 APK
```

产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果网络无法访问 `dl.google.com`，`settings.gradle` 和 `build.gradle` 已优先使用
阿里云 Maven 镜像；仍失败时请检查本机 Gradle 代理配置。

## 4. 安装与使用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 填写服务器地址，只填 `http(s)://主机[:端口]`，不要附加路径。
2. 填写用户名、密码，勾选授权确认。
3. 点击“登录并下载模板”。登录成功后会自动下载员工标准模板和可选的口罩模板。
4. 默认模式使用 Android GPS/网络定位和天地图逆地理编码。
5. 测试模式下手填地点、经纬度、时间；UI 会显示“测试模式”值。
6. 点击“定位校验”。服务端通过后才允许开始检测。
7. 点击“开始检测”，在相机页正对镜头。倒计时结束后开始检测。
8. 识别成功且模板分数 ≥ 0.65 后，按登录下发的 `recordTcpPort` 自动选择 HTTP 或 TCP。
9. 页面展示服务端真实成功时间或错误原因。

密码和 token 不写入磁盘；token 只保存在内存会话中。

## 5. KeyCode

源 APK 在 `HwInitFace` 返回 -31 时会连接汉王授权服务获取设备 KeyCode。本客户端：

- 优先读取 `filesDir/hwKeyCode.dat`
- 支持“导入 KeyCode”从文件选择器导入厂商签发的文件
- 支持用户确认后调用与 APK 相同的授权服务流程

授权服务地址与 APK 一致：`182.92.162.37:8888`。KeyCode 是设备/授权绑定的，
不保证能跨包名、跨签名或跨设备使用。请确认你拥有汉王 SDK 的有效授权。

应用会在获取 KeyCode 和进入检测页时请求 `READ_PHONE_STATE`，因为源 APK 的
native 库使用 IMEI/设备标识生成申请和校验。拒绝该权限时仍可导入已签发的 KeyCode
文件，但 native 初始化可能失败并显示 KeyCode 错误。

如果从原 APK 所在设备导出 KeyCode：

```bash
adb pull /sdcard/com.hanwang.facekey/hwKeyCode.dat
```
