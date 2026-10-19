# eFaceGo Web 薄客户端

仅调用 `base.apk` 已使用的服务端接口，不实现另一套考勤服务端，也不复刻完整 APK。

## 运行

需要 Node.js 22 或更高版本，无第三方 npm 依赖，无需 `npm install`。

```powershell
cd D:\code\hackApk\web-client
npm start
```

打开 `http://127.0.0.1:8787`。服务只允许绑定回环地址，避免把账号、token 和照片暴露给局域网。

测试：

```powershell
npm test
```

测试只启动本机临时 HTTP 服务，不访问真实考勤服务器。若本机 Chrome 位于 `C:\Program Files\Google\Chrome\Application\chrome.exe`，还可运行真实页面冒烟测试：

```powershell
npm run test:browser
```

它会检查页面渲染、时间模式切换、JPEG 选择和手机宽度布局，截图写入 `test-output/`，同样不会调用考勤接口。

## 已实现的 APK 调用链

1. `POST /manage/m/login.do`：`userName`、`pwd`。
2. `POST /manage/m/checkLocation.do`：`gps`、`lat`、`lng`、`token`、`time`，并带 `Cache-Control: no-cache`。
3. 登录下发 `recordTcpPort <= 0` 或字段缺失时：`POST /manage/m/addCard.do`。

`addCard.do` 的表单顺序和字段与 APK 一致：`token`、`source`、`time`、`gps`、`lat`、`lng`、`photo`、`appVer`、`appVerCode`、`serverSpecifiedMap`、`usedMap`、`systemName`、`currSysVer`。

## 时间和地点

- **跟随原 APK**：`checkLocation.time` 是本机当前时间，格式 `yyyy-MM-dd HH:mm:ss`；`addCard.time` 固定为空，由服务端返回打卡时间。
- **自定义时间**：用户输入的值同时写入两个接口的 `time`。服务端是否接受非空 `addCard.time` 只能由真实响应判断，客户端不会伪造成功。
- 地点名称写入 `gps`；经纬度写入 `lat/lng`，统一格式化为六位小数。

## 请求头与照片

- `User-Agent: okhttp/3.11.0`
- `Content-Type: application/x-www-form-urlencoded`
- `Accept-Encoding: gzip`
- `Connection: Keep-Alive`
- 显式 `Content-Length` 和 `Host`
- 仅 `checkLocation.do` 额外使用 `Cache-Control: no-cache`

不会把浏览器 `Origin`、`Sec-Fetch-*`、浏览器 UA 转发给上游，也不会附加 IMEI、MAC、设备 IP 等 APK 未使用的指纹字段。`appVer=1.0.15.79.20240816`、`appVerCode=41`、`systemName=Android` 与 APK 一致；`currSysVer` 需用户明确填写，因为原值来自 Android 设备的 `Build.VERSION.SDK_INT`。

照片须为 JPEG。原始字节按 `android.util.Base64.encodeToString(bytes, Base64.DEFAULT)` 的形式编码：标准 Base64、76 字符换行、LF 分隔以及结尾 LF，然后进行表单编码。

## TCP 加密通道

登录响应含有效 `recordTcpPort` 时，客户端自动改用 TCP，不会误发 HTTP `addCard.do`：

1. 明文发送 `{"COMMAND":"AKE","PARAM":{"sn":OwnerId,"key":[静态公钥XY,临时R点XY]}}`；点为 sm2p256v1 大写十六进制。
2. 按 APK 的 SM2 密钥协商公式得到 32 字节共享密钥，前 16 字节作 SM4 key，后 16 字节作 IV。
3. 用 SM4/OFB/PKCS7 加密 `{"COMMAND":"PostMobileRecord","PARAM":...}`，外层为四字节大端长度。
4. 解密响应并要求 `RETURN=PostMobileRecord`、`PARAM.result=success`。

APK 的静态 SM2 配置已提取到 `sm2.config.local.json`（该文件不会提交，也不要分享）。字段模板见 `sm2.config.example.json`。如果服务器返回未使用 SM2 的旧式 AKE 回退（`key[1]` 为空），当前客户端会明确报错，不会猜测密钥。

## 明确边界

- 本客户端不执行 APK 的 native 人脸模板比对、KeyCode 或活体检测，只上传用户选择的 JPEG 并展示真实服务端响应。
- 不关闭 HTTPS 证书校验，不持久化账号、token 或照片，也不把内容发送到其他第三方。
- 仅用于自己管理或明确授权的测试环境与账号。
- 尚未使用真实账号联调；本地模拟服务端覆盖了 HTTP/TCP 两条路径、分包/组包和加密往返。
