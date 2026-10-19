# 验收记录

日期：2026-10-19
工程：`android-client`
APK：`app/build/outputs/apk/debug/app-debug.apk`
SHA-256：`5d41fe2b103bf21bad0870d3ce0dbf6296aec8604fdf97d96cfbecf0ef188e8f`

## 1. 本地已完成

### 构建

```text
BUILD SUCCESSFUL
package: com.hanwang.facekey.thin
versionCode 1 / versionName 1.0.0
minSdk 19 / targetSdk 28
ABI: arm64-v8a
```

产物包含：

```text
lib/arm64-v8a/libHWFaceRecogLibSDK.so
lib/arm64-v8a/libc++_shared.so
assets/sm2.config.local.json
```

`FaceCoreHelper` 的 dex 类名和 native 方法已确认：

```text
Lcom/hanvon/faceRec/FaceCoreHelper;
HwCompareFeature
HwDetectMultiFaceAndEyeEx
HwGetFaceFeatureEx
HwGetFeatureSize
HwGetKeyCode
HwInitFace
HwJudgeMask
HwNV21TORGB
HwReleaseFace
HwSetPortrait
```

### JVM 测试：28/28 通过

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `ProtocolTest` | 9 | 表单顺序、坐标、时间、Base64、URL |
| `SmCryptoTest` | 6 | SM3 向量、SM4 标准向量、OFB/PKCS7、KDF、帧 |
| `Sm2Test` | 4 | 协商对称性、AKE、错误响应、APK SM2 配置 |
| `HttpApiTest` | 4 | 登录、定位、addCard、模板下载、gzip、头 |
| `WorkflowTest` | 2 | HTTP 三段链路、TCP 登录选择 |
| `TcpPunchClientTest` | 3 | TCP AKE/加密记录、服务端拒绝、APK SM2 配置 AKE |

关键断言：

- 登录/定位/addCard 的字段顺序与 APK 一致
- `checkLocation.do` 带 `Cache-Control: no-cache`
- `User-Agent: okhttp/3.11.0`
- `Connection: Keep-Alive`
- `Accept-Encoding: gzip`
- 照片 76 字符换行、LF、结尾 LF
- SM4 标准向量 `681edf34d206965e86b3e94f536e4246`
- SM2 共享密钥与 `web-client/lib/sm2.js` 的 Node 结果一致：
  `A7E3C4DBB720804F42BDE36D866D559ED5ABA7EB252148ACE26987E0A53AA6CF`
- APK 的 `sm2.config.local.json` 完成本地 AKE，未输出私钥
- TCP 分包、合并包、零长度帧
- 错误照片/坐标/时间在联网前拒绝

### 本地 mock server

`tools/mock-server.mjs` 已通过 HTTP 手工探测：

- `/manage/m/login.do`
- `/manage/m/checkLocation.do`
- `/manage/mobiSetting/getEmployeeFeaturePicToMobi.do`

TCP mock 使用 `web-client/lib` 的同一套 SM2/SM4 实现，供真机联调。

## 2. 未完成（需要真实环境）

本机没有连接 Android 设备；arm64 系统镜像无法在 x86_64 主机上由当前 QEMU2
模拟器启动（`PANIC: Avd's CPU Architecture 'arm64' is not supported ...`）。
因此以下项目未执行：

- [ ] `arm64-v8a` 真机安装
- [ ] 相机权限允许/拒绝
- [ ] 真实 GPS、弱网、无 GPS
- [ ] native SDK `HwInitFace` 成功
- [ ] KeyCode 导入或授权服务获取
- [ ] 真实员工模板下载
- [ ] 活体/口罩/人脸比对
- [ ] 真实授权服务器 HTTP 打卡
- [ ] 真实授权服务器 TCP 打卡
- [ ] 检测中断、回前台、后台释放相机
- [ ] 非授权人脸被拒绝
- [ ] 服务端时间/地点/照片记录与 UI 一致

## 3. 真机验收步骤

1. 安装：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. 配置并登录授权测试服务器。
3. 确认模板状态为“模板已下载”。
4. 导入授权 KeyCode，确认状态为“KeyCode 已加载”。
5. 完成一次真实 GPS 定位校验。
6. 进入检测页，用授权员工本人完成一次识别。
7. 检查服务端记录的 `time`、`gps`、`lat/lng`、`photo` 是否与 UI 一致。
8. 用非授权人脸重复，确认不会进入提交。
9. 在检测中按 Home 键回前台，确认相机重新初始化且没有后台偷跑。
10. 若登录下发 `recordTcpPort > 0`，确认只走 TCP，不出现 HTTP `addCard.do`。

## 4. 风险

- native SDK 的 KeyCode 可能绑定原包名/签名/设备；本客户端包名为
  `com.hanwang.facekey.thin`，不保证原 APK 的 KeyCode 可直接使用。
- 相机旋转/镜像在不同厂商设备上可能不同。
- 真实服务器可能对测试模式的自定义时间/地点做额外审计或拒绝。
- 未实现旧式非 SM2 AKE 回退。
- 未实现 APK 的成功页静态地图，仅保留打卡主链路。
