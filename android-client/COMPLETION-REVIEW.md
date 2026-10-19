# Android 薄客户端完成情况检查

检查日期：2026-10-19

检查依据：`../android-task.md`、`../result.md`、当前 Android 源码与本次本地验证结果。

## 结论

**尚未达到任务书的全部完成标准。** 协议基础、本地 HTTP/TCP 测试和 debug 构建已完成；native 人脸链路已有实现，但尚无真机运行证据，并存在照片与识别结果关联、取消提交和会话失效方面的代码缺陷。当前适合继续本地和授权真机联调，不能标记为端到端验收通过。

本次未修改应用源码，未访问真实考勤服务器或汉王授权服务，未清除现有账号配置、模板或密钥。

## 本次重新验证

- JDK 17、Gradle 8.0.2 下执行 `:app:testDebugUnitTest :app:assembleDebug`，返回 `BUILD SUCCESSFUL`。
- 28 项 JVM 测试通过，0 失败、0 错误、0 跳过，包含本地 APK SM2 配置的 AKE 测试。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，33,868,124 字节。
- APK SHA-256：`5d41fe2b103bf21bad0870d3ce0dbf6296aec8604fdf97d96cfbecf0ef188e8f`，与原验收记录一致。
- 打包包含 arm64-v8a 的两项 native 库及本地 SM2 配置；没有输出配置内容。
- 两项提取库的 SHA-256 均与 `NATIVE_LIBS.md` 一致。
- `adb devices -l` 无连接设备，本次未做安装、相机、定位或 native 执行验证。
- 额外本地探针确认：HTTP 定位和 HTTP 打卡会把非法日历时间发送到 loopback mock server；TCP 记录构造也接受该时间。
- 同一探针确认：缺 token、缺照片、越界坐标均被拒绝，且没有新增网络请求。

本次构建使用的 PowerShell 命令：

```powershell
cd D:\code\hackApk\android-client
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME = "$PWD\.gradle-home"
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

当前默认 PATH 的 Java 为 8，因此需要选择 JDK 17。额外探针及其输出位于被忽略的 `test-output/`；它不属于原有 28 项测试。

## 按阶段核对

| 阶段 | 状态 | 依据与缺口 |
| --- | --- | --- |
| A 工程与协议基础 | 基本完成 | 工程、SharedPreferences、权限、arm64 ABI、OkHttp 3.11.0、登录与会话模型存在；登录 mock 测试通过。 |
| B 定位与 HTTP | 部分完成 | Android 定位、天地图/Geocoder 降级、HTTP 表单和照片换行编码已实现；mock 测试通过。真实 GPS、权限拒绝、弱网未验证；协议层非法时间校验缺失。 |
| C TCP 加密 | 本地验收通过 | SM2/SM3/SM4、AKE、帧及本地加密往返测试通过，实际本地 SM2 配置测试未跳过。真实服务器未联调。 |
| D native 人脸 | 代码已接入，未验收 | JNI、KeyCode 导入/获取、模板、NV21、灰度、定位、特征、比对、口罩与 JPEG 80 已有代码。尚无 SDK 初始化成功、本人识别通过或错误人脸被拒绝的设备证据；另见 F1。 |
| E 生命周期与稳定性 | 部分完成 | 有暂停相机、倒计时、超时与销毁逻辑，但取消请求、跨检测会话结果失效、清除会话期间的异步任务处理仍有缺陷，见 F2/F3。 |

## 需要修复的发现

以下除 F4 的本地探针结果外，均为静态代码发现；尚未在设备上复现相机线程交错。

### F1 / 高：上传照片没有绑定到通过比对的那一帧

位置：`ui/PunchActivity.java:534`、`:563`、`:623`。

`processFrame()` 在特征提取、比对完成前就覆盖共享字段 `photoBase64`，成功后只把 `hasMask` 传给主线程的 `onRecognized()`；`submitAsync()` 再读取共享照片字段。工作线程返回时会立即释放 `processing` 标记，主线程处理成功消息前，排队的相机回调可能启动下一帧处理并改写照片。

因此代码存在“识别通过的是 A 帧，提交读取的是 B 帧”的竞态窗口，无法保证上传照片就是完成 native 比对的照片，不满足任务书完成标准第 6 项的可靠性要求。

建议：识别通过后再为该帧生成 JPEG，把照片、检测会话编号与检测结果作为不可变对象一起交付提交层；只允许该对象触发一次提交。

### F2 / 高：取消和进入后台不能可靠阻止后续提交

位置：`ui/PunchActivity.java:239`、`:628`、`:747`、`:768`；`tcp/TcpPunchClient.java:39`；`net/HttpApi.java` 的 `executeJson()`。

取消按钮仅调用 `finish()`；`onPause()` 只停相机，`onDestroy()` 调用 `shutdownNow()`。提交任务开始后没有再次检查 `finished/paused` 或检测会话编号，HTTP `Call` 与 TCP `Socket` 也没有暴露给取消逻辑。线程中断不能代替关闭这些在途网络操作。例如在 TCP AKE 等待期间取消，任务仍可能继续发送记录。

此外，后台中的帧处理没有会话编号；若它在恢复前台后才返回，旧帧的成功回调可能在新倒计时完成前被接受。恢复前台只重开相机，未使旧检测结果失效。

建议：为每次检测建立会话编号；暂停/取消时立即使其失效，在发送前核对，并取消 HTTP Call、关闭 TCP Socket。对于已经发出的请求，显示“结果未知”等真实状态，避免声称服务端一定未写入。

### F3 / 高：清除会话后，在途登录仍能恢复会话与模板

位置：`ui/MainActivity.java:199`、`:353`、`:356`、`:363`、`:578`、`:612`。

“清除会话与模板”和“保存配置”未加入 `busyViews`，在登录/模板下载期间仍可操作。清除逻辑只处理当前字段与磁盘文件，没有取消或使在途任务失效；登录成功后工作线程会再次写入 `session/sessionBaseUrl`，模板下载完成后也会再次保存模板。切换服务器期间同样存在旧任务写回的窗口。

建议：用会话代次隔离登录、下载、定位与清除操作；所有异步结果写回前核对代次及服务器/员工身份，并取消失效请求。

### F4 / 中：非法时间没有在协议层联网前拒绝（已复现）

位置：`protocol/Protocol.java:216`、`:228`；`tcp/PostMobileRecord.java` 构造函数。

`validateCustomTime()` 本身可拒绝非法日历时间，但 `checkLocationFields()`、`addCardFields()` 没有调用它；TCP 只检查时间非空。UI 正常自定义路径有校验，但协议层不满足任务书第 6 节的输入拒绝要求。

本地使用非法日期及超范围时分秒验证，结果为：

```text
invalid_check_time_reached_loopback=true
invalid_add_card_time_reached_loopback=true
invalid_tcp_time_accepted=true
missing_token_bad_coordinate_missing_photo_rejected=true
rejected_inputs_created_no_requests=true
```

建议：在协议入口验证时间，保留 HTTP `addCard.time=""` 的 APK 默认行为；补充断言 mock server 请求数不变的负向测试。

### F5 / 中：mock server 日志未脱敏用户名

位置：`tools/mock-server.mjs:92`、登录分支的 `console.log('[http] login', redactForm(form))`。

`redactForm()` 只过滤 `photo/pwd/token`，`userName` 会原样进入控制台日志。真机使用账号登录该 mock 时违反“日志不含账号”的要求。

建议：账号字段也脱敏，最好仅输出接口、字段名称及校验结果。

### F6 / 中：TCP 成功页丢弃服务端时间，失败页会笼统显示“未提交”

位置：`ui/PunchActivity.java:644`、`:648`、`:703`。

TCP 客户端返回响应 JSON，但页面丢弃返回值，只展示“TCP 加密上传成功”；即使响应带 `punchTime` 也不会展示。本地 TCP mock 就会返回该字段。HTTP 成功页有服务端时间，两通道行为不一致。

`showResult()` 对所有失败都显示“未提交”，网络超时也如此；服务器可能已处理请求，这一文字无法准确反映结果。成功页也没有提供地点/照片记录核对信息，完成标准第 5 项仍需补充实现和真实记录验证。

建议：保留并解析实际响应中存在的服务端结果字段，展示提交地点，并区分本地拒绝、服务端拒绝、请求已发出但结果未知。

### F7 / 中：特征与敏感会话的内存清理不完整

位置：`ui/PunchActivity.java:774`、`:776`、`:779`；`ui/MainActivity.java:704`。

当前只对当前帧特征和部分 KeyCode 数组执行清零；标准/口罩特征在销毁时仅置空引用，token 还存在于 Activity 的 Intent extras 中。主界面销毁也没有清除密码输入框或移除已排队的 Handler 回调。不能据此宣称所有模板、会话和照片数据都已清理；Java String 也无法保证原地擦除。

建议：对自有 byte[] 特征执行清零，移除敏感 Intent extras，清理输入和回调，并使回调在页面销毁后失效；文档应准确描述 String/网络缓冲区的清理边界。

## native 对齐的证据边界

- 通过普通文件工具对照了已有 `../decompiled/` 中的 `FaceCoreHelper`、`CheckFeatureUtils`、`FaceDetectActivity` 和 `HWFaceClient` 材料。
- JNI 方法声明、平均 RGB 灰度预处理、比对阈值、口罩阈值和 JPEG 质量在所检查源码中可对应；这不证明 native 已在设备上成功加载或识别效果等同。
- 源 APK 的口罩检测调用失败后继续流程；当前客户端也有相同处理，因此未将这一点作为新增缺陷。
- 当前 REA MCP 未接入；只读 doctor 诊断为 Codex 注册配置漂移。本次未改动工具注册或安装分析引擎，也没有生成新的 REA 二进制证据。
- `NATIVE_LIBS.md` 有来源和完整性记录，但未发现能够证明厂商 SDK 授权有效、KeyCode 可用于当前包名/签名的验收记录。

## 任务书最终验收标准

| 标准 | 本次判断 |
| --- | --- |
| 1. 本地 mock HTTP 链路通过 | 通过现有测试；另有 F4 负向校验缺口。 |
| 2. 本地 mock TCP 加密链路通过 | 通过。 |
| 3. APK SM2 配置本地 AKE 通过 | 通过，相关测试未跳过。 |
| 4. 授权账号和真实设备端到端打卡 | 未执行。 |
| 5. 服务端时间、地点、照片与 UI 一致 | 未验收，TCP 结果展示亦有缺口。 |
| 6. 未通过 native 流程不会提交 | 未完成设备验收，F1/F2 需要先修复。 |
| 7. 自定义时间/地点仅显式测试模式 | UI 代码具备模式限制和标记，尚待设备验证。 |
| 8. 日志、仓库、报告不含敏感材料 | mock 日志存在 F5；忽略规则存在，当前目录不是 Git 仓库，无法核验提交历史。此报告不含真实账号或密钥内容。 |

## 后续验收顺序

1. 修复 F1–F3，补充识别结果与照片绑定、暂停/取消、清除会话期间异步完成的回归验证。
2. 修复 F4–F7，补齐协议负向测试及真实结果展示。
3. 在有 SDK 授权和有效 KeyCode 的 arm64-v8a 真机上，先用本地 mock 验证初始化、模板、相机旋转、本人通过和错误人脸拒绝。
4. 执行权限拒绝、无 GPS、弱网、超时、取消、后台恢复、模板损坏等设备测试。
5. native 流程验证通过后，再连接授权测试服务器完成 HTTP/TCP 端到端验收，并核对服务端记录。

原 `ACCEPTANCE.md` 保留为历史记录；其 28/28 测试与构建结论已由本次复测确认，真机待验收项仍未完成。
