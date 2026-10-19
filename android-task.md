# Android 薄客户端开发任务书

日期：2026-10-19

## 1. 目标

开发一个只用于自有或明确授权测试环境的 Android 薄客户端。它在保留 APK 原 native 人脸前置流程的前提下，对接原 APK 已使用的服务端接口，实现与 `base.apk` 一致的核心打卡链路。

目标不是复刻整套 APK，也不是重写服务端业务。

## 2. 背景与当前状态

Web 薄客户端已完成并验证 HTTP 与 TCP 两条协议路径：

- `login.do`
- `checkLocation.do`
- HTTP `addCard.do`
- MINA TCP + SM2/SM4 `PostMobileRecord`

当前缺口：Web 端无法执行 Android native 人脸定位、模板比对、KeyCode 和相机帧处理。因此 Android 端的核心任务是补齐这条 native 前置流程。

参考文件：

- 目标 APK：`D:\code\hackApk\base.apk`
- 阶段结果：`D:\code\hackApk\result.md`
- 已实现协议客户端：`D:\code\hackApk\web-client`
- 本任务书：`D:\code\hackApk\android-task.md`

## 3. 范围

### 必须实现

1. 服务器与账号配置
   - 支持 `http://host[:port]`
   - 保存到 Android `SharedPreferences`
   - 不把地址写死在代码中

2. 登录
   - `POST /manage/m/login.do`
   - 表单字段顺序：`userName`、`pwd`
   - 解析 `token`、`employId`、`mapGaode`、`recordTcpPort`
   - 登录成功后保存会话到内存或本地安全配置

3. 定位校验
   - `POST /manage/m/checkLocation.do`
   - 表单顺序：`gps`、`lat`、`lng`、`token`、`time`
   - `Cache-Control: no-cache`
   - 默认使用 Android 定位与逆地理编码
   - 测试模式下允许手动输入地点、经纬度和自定义时间，并在 UI 中明确标记为测试值

4. HTTP 打卡
   - `POST /manage/m/addCard.do`
   - 表单顺序与 APK 一致：
     `token, source, time, gps, lat, lng, photo, appVer, appVerCode, serverSpecifiedMap, usedMap, systemName, currSysVer`
   - 默认 `time=""`
   - `photo` 为 native 流程产生的 JPEG 的 Android `Base64.DEFAULT` 编码

5. TCP 打卡
   - 登录下发有效 `recordTcpPort` 时使用
   - 明文 SM2 AKE，然后 SM4/OFB/PKCS7 加密 `PostMobileRecord`
   - 帧格式为四字节大端长度 + 密文
   - 曲线为 `sm2p256v1`
   - AKE 点坐标使用大写十六进制

6. native 人脸流程
   - 下载员工人脸模板
   - 初始化汉王 native 人脸 SDK
   - 获取 KeyCode
   - 相机 NV21 预览帧处理
   - 人脸定位
   - 提取当前帧特征
   - 与本地员工模板比对
   - 执行源 APK 的口罩检测判断
   - 成功后将当前 NV21 帧压缩为 JPEG quality 80
   - 再进入 HTTP 或 TCP 提交

7. 最小 UI
   - 服务器地址
   - 用户名、密码
   - 默认/自定义时间模式
   - 定位状态
   - 相机预览与检测状态
   - 登录、定位校验、开始检测、取消
   - 结果与真实服务端错误展示

### 不实现

- 不重写服务端登录、围栏、时间盖章、审批或人脸业务
- 不复刻完整 APK UI
- 不做 root、改系统时间、作弊 App 探测等 APK 中未接线代码
- 不在生产功能中提供“任意照片直接上传”
- 不关闭 HTTPS 证书校验
- 不绕过服务端人脸、围栏或时间裁决

## 4. 技术要求

### 工程形态

- 目录：`D:\code\hackApk\android-client`
- Android Gradle 工程
- 优先 Java，便于按 APK 的反编译逻辑逐项对齐
- minSdk 19，targetSdk 可按现有构建环境调整，行为字段仍使用源 APK 的 `appVer=1.0.15.79.20240816`、`appVerCode=41`

### 网络

- HTTP 优先使用 OkHttp 3.11.0，以减少请求头差异
- 不允许浏览器/WebView 直接提交业务请求
- token 放在表单字段，不新增 `Authorization` 头
- 不添加 APK 未使用的指纹字段

### TCP 加密

- 可直接使用 plain `Socket`，无需引入 MINA；只要求线上字节协议一致
- SM3、SM2 曲线运算、SM4/OFB/PKCS7 可用 BouncyCastle 实现
- 与 Web 端 `lib/sm2.js`、`lib/crypto-sm.js` 的行为逐测试向量对齐
- 密钥材料不得提交到仓库或输出到日志

### native SDK

需要从源 APK 或厂商 SDK 包中确认：

- `libHWFaceRecogLibSDK.so`
- 相关 JNI 类：`FaceCoreHelper`、`HWFaceClient`、`CheckFeatureUtils`
- 模板读取与预处理：`getFeature/readFeatureFromBase64/RGBToGray`
- 口罩判断：`HwNV21TORGB`、`HwJudgeMask`
- 本地比对阈值：`maxScore >= 0.65`
- 口罩阈值：`pnRst[0] == 1 && pfScore[1] > 0.999`

优先使用厂商正式 SDK；若从 APK 提取库和必要资源，必须确认用户拥有使用授权，并保留来源与完整性记录。

## 5. 分阶段任务

### 阶段 A：工程与协议基础

1. 创建 `android-client` 工程。
2. 配置签名、ABI、权限和本地密钥忽略规则。
3. 实现统一配置层：服务器、账号、会话、测试模式。
4. 实现 OkHttp 请求构造。
5. 实现登录和登录响应保存。

验收：本地模拟服务器确认登录 URL、字段顺序和请求头。

### 阶段 B：定位与 HTTP 提交通道

1. 实现 Android 定位获取与降级策略。
2. 实现天地图逆地理编码（若测试环境不可用，允许手动输入）。
3. 实现 `checkLocation.do`。
4. 实现 `addCard.do` 请求构造，但暂不提交真实打卡。
5. 增加本地 mock server 测试。

验收：mock server 捕获的字段、顺序、头和照片编码与 Web 端测试一致。

### 阶段 C：TCP 加密通道

1. 实现 SM3 与 KDF。
2. 实现 sm2p256v1 点运算与密钥协商。
3. 实现 AKE 请求/响应。
4. 实现 SM4/OFB/PKCS7。
5. 实现四字节大端帧和分包读取。
6. 实现 `PostMobileRecordBean`。
7. 完成本地加密 mock server 联调。

验收：使用 APK 静态 SM2 配置完成本地 AKE 和加密往返，不输出密钥。

### 阶段 D：native 人脸 SDK 接入

1. 确认 SDK 来源、ABI 和加载方式。
2. 建立 Java/JNI 封装。
3. 下载员工模板并保存到私有目录。
4. 初始化 `HWFaceClient` 并处理 KeyCode 错误。
5. 相机预览输出 NV21。
6. 按源 APK 的旋转逻辑处理帧。
7. 人脸定位和特征提取。
8. 模板比对与口罩检测。
9. JPEG quality 80 生成。
10. 成功后按登录配置选择 HTTP 或 TCP。

验收：授权测试员工在真实 Android 设备上完成一次端到端打卡；错误人脸不能进入提交。

### 阶段 E：生命周期与稳定性

1. 检测页面进入后台时停止预览和释放相机。
2. 回前台时重新初始化检测会话。
3. 增加检测超时和取消。
4. 断网、超时、非 JSON、服务器拒绝时展示真实原因。
5. 清理内存中的 token、模板和照片。

验收：无后台偷跑相机；无过期会话继续提交；日志不含账号、token、密钥或照片。

## 6. 测试要求

### 本地单元/集成测试

必须覆盖：

- 登录表单与请求头
- `checkLocation` 表单与 `Cache-Control`
- `addCard` 完整字段顺序
- Android `Base64.DEFAULT`
- SM2 AKE 双方共享密钥一致
- SM3 KDF 测试向量
- SM4/OFB/PKCS7 加解密往返
- TCP 分包、合并包、零长度帧
- 缺 token、缺照片、非法坐标、非法时间在联网前拒绝

### 设备测试

- 至少一台 arm64-v8a 真机
- 权限拒绝场景
- 无 GPS/弱网场景
- 检测中断与回前台
- native SDK 初始化失败与 KeyCode 失败
- 模板不存在/模板损坏

## 7. 完成验收标准

1. 本地 mock HTTP 链路通过。
2. 本地 mock TCP 加密链路通过。
3. 使用 APK SM2 配置的本地 AKE 通过，且不输出密钥。
4. 使用授权测试账号和真实设备完成一次端到端打卡。
5. 服务端返回的时间、地点和照片记录与 UI 展示一致。
6. 未通过 native 人脸流程时不会提交。
7. 自定义时间和地点只出现在显式测试模式。
8. 日志、仓库和最终报告中不包含账号、token、私钥、模板或照片。

## 8. 主要风险

- native SDK 授权、ABI 兼容和初始化失败
- KeyCode 服务不可用或授权过期
- 相机旋转/镜像在不同设备上的差异
- 模板隐私和本地存储安全
- 自定义时间/地点被误用于非测试环境
- 服务端可能记录与正式 APK 不同的审计行为
- ProGuard/R8 可能破坏 JNI 类名或 native 方法映射

## 9. 交付物

- `android-client/` Android 工程
- Android 端 README 与运行说明
- 本地 mock server 或测试脚本
- 单元/集成测试
- debug APK 构建说明
- 端到端验收记录
- 不包含密钥、模板、照片或真实账号的最终报告

## 10. 现有可复用实现

Web 端以下文件可作为行为对照，不建议直接跨语言复制而不测试：

- `web-client/lib/protocol.js`：HTTP 表单、时间、照片编码
- `web-client/lib/workflow.js`：login/checkLocation/HTTP/TCP 自动选择
- `web-client/lib/sm2.js`：SM2 曲线与密钥协商
- `web-client/lib/crypto-sm.js`：SM3、SM4、PKCS7、帧
- `web-client/lib/tcp-client.js`：TCP AKE 与加密提交
- `web-client/test/`：本地 mock server 和测试断言

先让 Android 协议层通过这些相同测试，再接入 native 人脸流程。不要在 native 流程未验证前先对真实服务器提交。