# 汉王 eFaceGo 打卡 App 逆向分析记录

> 交接文档：本文汇总了用 rea-agents（jadx 引擎）对 `base.apk` 的全部已验证推论、证据、未竟事项与命令手册。
> 撰写时间：2026-10-16。分析工具链已就绪（见 §6），可直接续做。
> 立场：防御性安全分析 / 自有 App 缺陷定位（用户即为该 App 的维护分析方）。

## 1. 目标

| 项 | 值 |
|---|---|
| 文件 | `D:\code\hackApk\base.apk` |
| SHA-256 | `43a06ae8a19aac0859d558fbfb2a688a533cf0498230ac1fc335624d5cd1171e` |
| 包名 | `com.hanwang.facekey`（汉王 "eFaceGo" 人脸考勤） |
| 版本 | 1.0.15.79.20240816 (code 41)，minSdk 19 / targetSdk 28 |
| 规模 | 4978 类 / 919 资源；classes.dex×3（classes2.dex 为主） |
| 厂商线索 | `hwzy99.com`（汉王智悦）、`wistek.hanwang.com.cn`、升级包 `download.hw99.com/hanwang/hwzy/eFaceGo.apk` |

## 2. 校验架构总览（结论先行）

真正生效的打卡校验 = **登录 token + native 人脸活体（汉王/华大 SDK + KeyCode 服务端授权）+ 定位上报（服务端围栏裁决）+ 照片留证上传**；客户端侧防作弊（root 改钟、探测作弊 App、设备指纹）**在本版本均为未接线的死代码**。整体信任重心在"服务端裁决 + native 人脸"，客户端防护弱于表象。

## 3. 已验证证据明细

### 3.1 服务端接口（dex 明文字符串 + Const 类 96 字段）

基址 `http://ifaceattend.hwzy99.com/`（`.do` 后端），另有运行期可配 IP/端口（`SettingsActivity`/`IpPortEditFragment`）。
关键端点：`login.do`、`changePassword.do`、`addCard.do`/`queryCard.do`、`getEmployeeFeaturePicToMobi.do`（下发员工人脸模板）、`getFeature.do`、`getQianDaoList.do`（签到）、`getDayAttendList.do`、`getWorkedHours.do`、`queryApproveCheck.do`、`applyAflProcess.do`/`applyAppointment.do`/`applyOverProcess.do`（请假/预约/加班审批流）等（注：`auth/query.do` 经 §5 核实为未引用的死常量）；`Const` 还有 `CHECK_LOCATION_URL`、`EMPLOYEE_FEATURE_URL`、`SIGNATURE_URL`、`RE_LOGIN`、`MANDATORY_UPDATE` 等字段名。
外部时间源：`http://www.bjtime.cn`（北京授时，**但 Java 层未发现调用点，疑遗留**）。定位服务 SDK：高德 AMap + 天地图 geocoder。

### 3.2 上传双通道（均已接线，经内部类 Runnable 触发）

- **HTTP/OkHttp**：`AMapFaceDetectActivity.uploadData()` → POST 到 `HWFaceCommonUtil.getAddCardNew()`，表单字段 `token, source=1, time=“”（空！时间由服务端盖章）, gps/lat/lng（AMapUtil）, photo=mPhoto（人脸 jpg）, appVer, usedMap, systemName...`
- **私有 TCP（Apache MINA）+ SM2 国密**：`postRecogResult()` → `PostMobileRecordBean{capturejpg, id, time=本地 new Date(), gps/lat/lng, recogSource}`，`isEncrypted=true`，目的 `WEB_MATCH_PATH/PORT` 取自登录下发配置（`HWFACE_LOGIN_IP`/`HWFACE_TCP_LOG*`）。`TCPClientHandler` 字段含 `SM2ClientInfo`/`EncryptionReadyEvent`/`IsPassiveEncryption`。`messageReceived` trace=0 引用属正常（MINA IoHandler 接口回调）。

### 3.3 人脸校验（核心，确证活跃）

- native SDK：`lib/arm64-v8a/libHWFaceRecogLibSDK.so`（37MB）+ `libmediastreamer/libmswebrtc`（linphone 实时视频，另有 `voip.IncomingCallActivity`/`VoIPService` 远程视频核验能力）。
- Java 封装 `checkWork.faceRec.HWFaceClient`：`InitFaceClient(ServerIP, Port, ctx, handler, isNewLicense)` 打开裸 `Socket` 连人脸服务器，`FaceCoreHelper.HwGetKeyCode()` 取 **KeyCode 授权码**，失败返回 -1；错误码 `HW_SDK_CLOUDKEYCODE_ERROR`/`HW_SDK_USERINFO_NOTEXIST`。**`InitFaceClient` 有 8 个调用点**（两个打卡 Activity 的检测/注册匿名 Runnable + MainActivity）→ 引擎活跃。
- 判定流：`judge()` 读 `getIntent().getIntExtra("code")` 分发成功/拒绝 Fragment（真正的比对阈值在 native）。`CheckFeatureUtils.getFeature/readFeatureFromBase64/RGBToGray` 做模板预处理；`checkMask()` 口罩检测；`beginDetectTime` 会话计时。
- 模板来源：`getEmployeeFeaturePicToMobi.do` 下载 → 本地 `SaveDataHelper/innerDataFile`。

### 3.4 定位校验（确证活跃，`HomeFragment.checkLocation` 两重载已反编译）

- ①`checkLocation()`：强制 `isProviderEnabled("gps")`（否则弹设置页）→ `Criteria(accuracy=1,power=1)` 取 bestProvider → 依次 `gps→network→passive` 注册 `requestLocationUpdates`。**未见任何 Mock 检测**（无 `isFromMockProvider`/`test_` provider 过滤）。
- ②`checkLocation(gps,lat,lng)`：POST `CHECK_LOCATION_URL`，字段 `gps/lat/lng/token/time=本地 new Date()`，`FORCE_NETWORK`，服务端应答交 handler 裁决（1=结果,2=失败）。
- `GPSProviderStatus` 仅状态常量；`AMapFaceDetectActivity` = 高德地图 + 人脸合体页（`FLAG_USED_GAODE`）。

### 3.5 客户端防作弊代码 = 本版本死代码（关键反转）

`main.utils.AppUtil` 中下列方法**全部 0 个 Java 调用点**（对照实验：`HttpUtil.getClient` = 19 调用点；trace 输出带 `direct_invokes_exhaustive:true`，0 可信）：

- `syncServerTime(activity,serverTime)`：`AlarmManager.setTimeZone("Asia/Shanghai")` + 调 `changeSystemTime(yyyyMMdd.HHmmss)`
- `changeSystemTime(t)`：`Runtime.exec(SUPER_USER)` → `su -c "/system/bin/date -s <t>"` + `clock -w`（root 强改本机时钟；普通机静默失败）
- `rootCommand(cmd)`：任意 shell（日志 tag `xinhua_eric`）
- `checkAppInstalled(pkg)`（getPackageInfo）、`isAppRunning(pkg)`（getRunningTasks(100)）、`isServiceRunning`（探测虚拟定位/多开类作弊 App 的能力；**未发现硬编码黑名单包名**，目标需调用方传入）
- `getWifiMac()`（识别 `02:00:00:00:00:00` 掩码）、`getIpAddressString()`、`getProperty()`（设备/网络指纹）
- `HWFaceClient.GetFacePos` 也是 0 引用（Java 层不用，native 直连 FaceCoreHelper）

`xposed/frida/emulator/goldfish/MIUI/serial` 等串存在于 classes2.dex，但未找到业务层主动调用点，**大概率来自 Bugly/JPush/linphone 等 SDK**，置信度：中。`h5Function.JsInterface` 仅暴露 `login/close`，H5 够不到上述工具。

→ 推断：这些是早期/考勤机（kiosk root 设备）部署遗留，当前手机版未启用。

### 3.6 已知弱点（防御清单）

1. **TLS trust-all（确证）**：`checkWork.net.MySSLSocketFactory.<init>(KeyStore)` 安装空实现的 `X509TrustManager`（`checkServerTrusted` 空）→ HTTPS 可中间人。
2. **无 Mock 定位防护（确证）**：见 §3.4，虚拟定位 API 注入可绕围栏。
3. **时间防线名存实亡（确证）**：强改钟逻辑未接线；`checkLocation` 的 time 用本地钟；`uploadData` 留空由服务端盖章（这条反而是好的）。
4. 人脸活体是唯一硬门槛，但存在 §4 的生命周期缺陷。

## 4. 生命周期 Bug：打卡页切后台再回前台"继续打卡"（已定位成因，确证）

继承链：`FaceDetectActivity/AMapFaceDetectActivity → MapBaseActivity → BaseActivity`

证据与机制核实：
- **Activity 生命周期缺失**：两个打卡页仅重写 `onCreate`/`onDestroy`；`BaseActivity.onStop()` 为空转，切后台不会触发 `onDestroy()`，Handler 消息队列与 `HWFaceClient` 在后台持续存活。
- **相机硬件生命周期与业务状态脱钩（关键事实订正）**：
  - 切后台时，Android 销毁 Surface，触发 `SurfaceHolder.Callback.surfaceDestroyed` -> 显式调用 `closeCamera()`（执行 `mCamera.setPreviewCallback(null); mCamera.stopPreview(); mCamera.release(); mCamera = null;`）。因此**相机硬件在后台已释放，并非在后台偷跑喂帧**。
  - 但**业务会话状态完全未重置**：`mStart` 仍然为 true，`feature` 人脸模板、`beginDetectTime`、`tcpClientThread` 依然保留在内存中，且无任何超时废弃计时器（无 postDelayed 作废）。
  - 回前台时，SurfaceView 重新附着触发 `surfaceCreated` -> 显式调用 `openCamera(surfaceHolder)`，重新打开相机硬件并注册 `previewCallback`。首帧视频送达时因 `mStart` 依然为 true，直接沿用上次挂起的会话上下文完成检测与打卡。
- 附带风险：一次活体会话可跨后台无限期挂起后完成打卡，无重新确认。

修复建议（维护者视角）：
1. 打卡页重写 `onStop()`：`closeCamera()`、停 `tcpClientThread`、`mStart=false`、`handler.removeCallbacksAndMessages(null)`、必要时 `ReleaseFaceClient()`。
2. `onResume()` 重新走 `judge()` 全量初始化，强制回前台后**重新活体**。
3. 加会话超时（postDelayed N 秒 → `showFailDialog()`/重置）。
4. `onStop` 作废在途 `postRecogResult/uploadData`，提交前要求前台新会话的成功码。

未竟（置信度补完）：**已彻底核实**。反编译 `initCameraView`、`openCamera` 与 `closeCamera` 确证：退后台时 `SurfaceView` 销毁触发 `surfaceDestroyed` -> 显式执行 `closeCamera()`（含 `setPreviewCallback(null); stopPreview(); release(); mCamera = null;`），**相机硬件在后台已释放，绝无后台偷跑喂帧**。但业务现场（Activity 实例、`mStart` 状态、特征模板 `feature`、`tcpClientThread` 通信线程等）未受任何清理，回前台 `surfaceCreated` 触发 `openCamera` 重新分配相机并注册 `previewCallback` 后，直接沿用挂起状态继续检测。

## 5. 问题求证结论与最新验证明细

- [x] **后台帧循环与相机生命周期**（确证）：切后台时 `SurfaceHolder.Callback.surfaceDestroyed` 会调用 `closeCamera()` 关闭预览并置空 `PreviewCallback`，后台**不会**喂帧。漏洞实质为**业务会话未与组件生命周期绑定**（无会话作废与超时）。
- [x] **`auth/query.do` 接口定位**（确证为死代码）：`http://ifaceattend.hwzy99.com/auth/query.do?` 仅在 `HWFaceCommonUtil.REQUESTNAMESPACEURL` 静态字段中声明，在 classes.dex/classes2.dex/classes3.dex 的所有字节码指令中**均无任何引用读取**。当前版本真实登录仅走 `Const.LOGIN_URL`（即 `login.do`，由 `LoginFragment` 通过 OkHttp 表单发起）。
- [x] **`TextUtil.getUrl()` 拼接与配置安全**（确证）：`getUrl(path)` 从 SharedPreferences 取可配 IP/Port 拼成 `http://{ip}:{port}{path}`；其多参重载 `getUrl(url, map)` 遍历 Map 直接以 `?key=value&` 拼装 Query String，**未做任何 URL 编码转义**。
- [x] **`checkMask` 口罩与 `getAddCardNew` 方法体**（确证）：
  - `checkMask`: NV21 转 RGB 后调 `FaceCoreHelper.HwNV21TORGB` 与 `HwJudgeMask`，活体口罩判定逻辑为硬编码阈值 `pnRst[0] == 1 && pfScore[1] > 0.999`（置信度大于 99.9% 判定佩戴）。
  - `getAddCardNew`: 从 SharedPreferences 读取 `ATTEND_URL`，拼接返回 `ATTEND_URL + "m/addCard.do"`。
- [x] **SM2 密钥来源**（确证为客户端硬编码静态密钥）：在 `TCPClientHandler.<init>` 中，直接使用明文字符串硬编码构造 `SM2UserInformation`：`OwnerId` 固定为 `"汉王人脸仪客户端"`，私钥与公钥对全量明文硬编码在字节码常量池中（全客户端设备共享同一套静态 SM2 密钥，非动态协商/非每机独占）。
- [x] **`UpdateApkUtil` / OTA 升级完整性**（确证无完整性校验）：应用由 `DownloadService` 执行 APK 下载，下载完成后通过 `FileProvider` 封装 Intent 直接调用系统 `application/vnd.android.package-archive` 界面安装，客户端**未对下载的 APK 做 SHA-256 哈希校验或开发者证书公钥签名比对**，配合 Trust-all TLS 易受中间人替换攻击。
- [ ] **Native 引擎深度挖掘**（可选后续，优先级低）：`libHWFaceRecogLibSDK.so`（arm64-v8a 37MB）中的 KeyCode 离线/在线校验算法及特征模型参数。该层受 native 二进制保护，逆向耗时高且目前 Java 层的安全边界与缺陷已足够支撑防御重构。

## 6. 工具链状态（续做必读）

- **rea-agents 6.5.0**（npm），MCP 已注册 user scope：`C:\Users\lx182\.qoder-cn\settings.json` → `mcpServers.rea`（`cmd.exe /d /c npx -y rea-agents@6.5.0 mcp`，env: `GHIDRA_INSTALL_DIR=D:\code\env\ghidra_12.1.4_PUBLIC`、`JAVA_HOME=D:\code\env\jdk-21.0.12.1+1`）。`qoderclicn mcp get rea` = Connected。
- 引擎状态：jadx 0.7.1（`D:\code\env\jadx-headless-mcp-0.7.1-all.jar`，可用户 JAVA_HOME=17 或 env 内 21）、Ghidra 12.1.4（需 JDK21+，`launch.properties` 已设 `JAVA_HOME_OVERRIDE=D:\code\env\jdk-21.0.12.1+1`）、IDA 未装（商业）。
- **坑 1**：本会话的 AI 工具面板不含 `mcp__rea__*`（MCP 工具仅会话启动时注入）。**续做请优先用 CLI**：`npx -y rea-agents@6.5.0 <cmd>`（功能等价）。彻底解决需重启 CLI 会话。
- **坑 2**：npx 缓存曾损坏（trace-mapping/sourcemap-codec 缺文件），已删缓存重装修复。若再现同类 ERR_MODULE_NOT_FOUND：`rm -rf %LOCALAPPDATA%\npm-cache\_npx\<hash>` 重拉。
- **坑 3**：Git Bash 里 `/tmp` 路径 node 不认；临时 JSON 一律写到 `C:\Users\lx182\` 或直接用 Read/node 以 Windows 绝对路径处理。bash heredoc 里的 JS 慎用嵌套引号。

### rea 命令速查（本次实际用法）

```bash
P="D:\code\hackApk\base.apk"; R="npx -y rea-agents@6.5.0"
$R doctor | $R setup                                   # 引擎体检
$R inspect-android-package "$P" --format json          # 包名/权限/组件/清单(大,存文件)
$R search-android-classes "$P" "com.hanwang"           # 类名枚举
$R inspect-android-class "$P" <fqcn> --format json     # 成员清单(.normalized_result.fields/methods)
$R inspect-android-method "$P" <fqcn> <name> [--overload-index N]   # 方法体(.normalized_result.source.text)
$R trace-android-references "$P" <fqcn> --method-name <name>        # 调用点(.normalized_result.references, direct_invokes_exhaustive)
$R decode-android-resources "$P"                       # apktool 资源解码(未用)
# 注意：rea analyze/decompile 仅原生二进制，APK 用上面 inspect/search/trace 系列
```

（完）
