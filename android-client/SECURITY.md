# 安全与隐私说明

## 数据分类

| 数据 | 存储位置 | 生命周期 | 是否写日志 | 内存清理方式 |
|---|---|---|---|---|
| 服务器地址、用户名 | SharedPreferences | 持久 | 否 | N/A |
| 密码 | 仅 EditText/内存 | 登录完成即清空 | 否 | 登录完成后即置空输入框；页面销毁时再次置空 |
| token | 内存 Session | 进程/会话 | 否 | 会话清除/退出时置空；Intent 读取后立即移除 extra |
| KeyCode | `filesDir/hwKeyCode.dat` | 持久 | 否 | 加载/使用后调用 `Arrays.fill(bytes, (byte)0)` 显式清零 |
| 标准/口罩模板 | `filesDir/face/*.b64` | 持久 | 否 | 提取为 `byte[]` 特征后，销毁/比对完成显式 `Arrays.fill` 清零 |
| 打卡照片 | 内存 Base64 字符串 | 当前请求 | 否 | 仅为通过比对的特定帧生成，单次提交完成后置空引用 |
| SM2 私钥 | `assets/sm2.config.local.json` | 随 APK | 否 | AKE 完成生成会话密钥后清零中间敏感标量 |

> [!NOTE]
> **JVM 内存清理边界**：对于 Java `java.lang.String` 以及底层网络传输缓冲区，JVM 运行时并不支持可靠的原地物理内存擦除。本工程通过以下措施最大化收敛暴露面：
> 1. 所有自有敏感 `byte[]`（如 KeyCode 数组、人脸特征数组、加解密密钥字节）在使用完毕或页面销毁时一律显式调用 `Arrays.fill(..., (byte) 0)` 清零。
> 2. `PunchActivity` 在 `readIntent()` 之后立即调用 `removeExtra(EXTRA_TOKEN)` 和 `removeExtra(EXTRA_EMPLOY_ID)`，避免敏感 token 滞留在 Intent extras 中。
> 3. `MainActivity` 在登录成功、清除会话和页面销毁时立即清空密码输入框。
> 4. 对在途网络任务采用会话代次（Epoch）和强取消，防止旧会话恢复。

## 网络

- 只向用户填写的服务器地址发送业务请求。
- 不添加 IMEI、MAC、设备 IP、Android ID 等 APK 未使用的指纹字段。
- 不转发浏览器 `Origin`、`Sec-Fetch-*` 或浏览器 UA。
- 不关闭 HTTPS 主机名/证书校验。
- 协议层严格校验日历时间有效性，非法格式或不存在的日期（如 2 月 30 日）在发出网络请求前直接本地拦截拒绝。
- TCP 使用明文 Socket，仅承载 APK 原有的 SM2/SM4 加密协议；不要在不可信网络使用。
- 请求发送前后精确维护在途状态（`requestSent`）：已发出打卡数据但在等待确认时发生中断或取消，如实标识为“结果未知”，避免误导用户。
- KeyCode 授权服务是唯一的内置第三方地址：`182.92.162.37:8888`，且必须由用户确认后调用。
- `READ_PHONE_STATE` 在获取 KeyCode 和进入检测页时请求；该权限与源 APK 的 native 设备标识流程对应，不用于业务请求字段，也不写入日志。拒绝后仍可尝试导入 KeyCode。

## 本地与生命周期

- 模板和 KeyCode 存放在应用私有目录，不写外部存储。
- 退出检测页或切后台时停止预览、释放 Camera、使当前检测会话代次失效。
- 提交结束或页面销毁时清除特征数组和照片引用，并取消所有排队的 Handler 回调。
- 清除会话时递增会话代次并终止在途网络请求，彻底防止旧会话与模板异步写回。
- 不把 secret 放入 `BuildConfig`、日志或异常消息。
- `mock-server` 与客户端日志均对 `userName`、`pwd`、`token`、`photo`、`id` 严格脱敏。
- `Sm2Config.toString()`、`Session.toString()`、`LoginResult.toString()`、`PostMobileRecord.toString()` 均对敏感字段做脱敏。

## 构建产物

`.gitignore` 排除：

```text
app/src/main/assets/sm2.config.local.json
app/src/main/jniLibs/
*.jks *.keystore *.p12
.gradle-home/
```

发布前请确认 APK、测试报告、截图和日志中不包含账号、token、私钥、模板或照片。
