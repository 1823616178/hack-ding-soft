# eFaceGo APK 接口复现阶段性结果

日期：2026-10-19

## 1. 目标 APK

- 文件：`D:\code\hackApk\base.apk`
- SHA-256：`43a06ae8a19aac0859d558fbfb2a688a533cf0498230ac1fc335624d5cd1171e`
- 包名：`com.hanwang.facekey`
- 版本：`1.0.15.79.20240816`（versionCode `41`）
- minSdk 19 / targetSdk 28

## 2. 已完成的 Web 薄客户端

位置：`D:\code\hackApk\web-client`

定位：**授权测试环境中的服务端接口薄客户端**。它不是完整 APK 复刻，不实现服务端业务，也不执行 APK 的 native 人脸前置流程。

### HTTP 通道

已实现：

1. `POST /manage/m/login.do`
2. `POST /manage/m/checkLocation.do`
3. 登录未下发有效 TCP 端口时：`POST /manage/m/addCard.do`

与 APK 对齐的内容：

- 表单字段和字段顺序
- `time/gps/lat/lng/photo/appVer/appVerCode/serverSpecifiedMap/usedMap/systemName/currSysVer`
- 经纬度六位小数
- JPEG 的 Android `Base64.DEFAULT` 编码（76 字符换行、LF、结尾 LF）
- `User-Agent: okhttp/3.11.0`
- `Content-Type: application/x-www-form-urlencoded`
- `Accept-Encoding: gzip`
- `Connection: Keep-Alive`
- `checkLocation.do` 的 `Cache-Control: no-cache`
- 不转发浏览器 `Origin`、`Sec-Fetch-*` 或浏览器 UA
- 不附加 IMEI、MAC、设备 IP 等 APK 未使用指纹字段

自定义参数：

- 时间模式：
  - 默认：`checkLocation.time` 使用当前时间，`addCard.time` 为空，由服务端决定结果
  - 自定义：同一值同时写入两个接口；是否接受由服务端决定
- 地点：`gps/lat/lng` 均可由用户输入

### TCP 加密通道

登录响应含有效 `recordTcpPort` 时，客户端自动切换为 TCP，不会误走 HTTP `addCard.do`。

已按 APK 复现：

1. 明文 AKE：
   - `COMMAND=AKE`
   - `PARAM.sn=<OwnerId>`
   - `PARAM.key=[静态公钥XY, 临时R点XY]`
   - 坐标为大写十六进制
2. SM2 曲线：标准 `sm2p256v1`
3. SM2 密钥协商，SM3 KDF 输出 32 字节共享密钥
4. 前 16 字节为 SM4 key，后 16 字节为 IV
5. `PostMobileRecord` 使用 SM4/OFB/PKCS7 加密
6. 外层帧为四字节大端长度
7. 支持分包、合并包和加密响应解码
8. 响应要求 `RETURN=PostMobileRecord`、`PARAM.result=success`

APK 的 SM2 客户端配置已提取到：

```text
D:\code\hackApk\web-client\sm2.config.local.json
```

该文件包含密钥，已加入 `.gitignore`，不要提交或分享。私钥与公钥坐标已通过曲线运算验证匹配。

### 验证结果

当前 `npm test`：19 项全部通过，包括：

- HTTP 完整链路
- TCP 完整 AKE 与加密提交
- APK 实际 SM2 密钥对的本地 AKE
- SM3/SM4/PKCS7
- TCP 分包/组包
- 自定义时间和地点
- HTTP 请求头与字段顺序
- 本地输入拒绝在任何上游请求前完成

页面测试也已通过：桌面和手机宽度均正常，控制台 0 错误。

所有测试均使用本机模拟服务端，未向真实考勤服务器发送请求。

## 3. 当前明确边界

1. **native 人脸流程未复现。**
   源 APK 的 `photo` 是 native 人脸定位、模板提取、模板比对和相关检测通过之后生成的。Web 端允许直接选择 JPEG，因此只能算接口协议测试客户端，不能算与 APK 完全等效的人脸打卡客户端。

2. **真实服务器未联调。**
   自定义时间和地点是否被接受，只能由授权测试服务器的真实响应判断。

3. **旧式 AKE 回退未实现。**
   如果服务端返回 `key[1]` 为空的非 SM2 回退响应，当前客户端会明确报错，不会猜测或使用未授权密钥。

4. **HTTPS 未关闭证书校验。**
   不建议为了复现而关闭 TLS 校验。

## 4. 下一阶段结论

若目标是“业务逻辑也要与 APK 完全一致，避免接口测试之外的偏差”，下一阶段应开发 **Android 薄客户端**：

- 复用 Android 相机与 native 人脸 SDK
- 完成模板下载、特征提取、模板比对、口罩/活体相关流程
- 通过后再调用已复现的 HTTP/TCP 协议层

详细开发任务见：

```text
D:\code\hackApk\android-task.md
```
