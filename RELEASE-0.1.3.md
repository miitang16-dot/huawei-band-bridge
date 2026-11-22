# 手环桥 0.1.3 · 测试包

0.1.2 之后的打磨版：**修掉日志里的密钥泄露**，心跳直播的数字口径写准确，文档重新整理。
功能和 0.1.2 一样，仍然是**测试版**。

| | |
|---|---|
| 版本 | `0.1.3`（versionCode 4） |
| 包名 | `io.github.bandbridge` |
| 文件 | [`dist/huawei-band-bridge-0.1.3-debug.apk`](dist/huawei-band-bridge-0.1.3-debug.apk) |
| 大小 | 283299 字节 |
| MD5 | `F768A3DF31D20D087C6DE089360C33F9` |
| SHA-256 | `F837F5BAC2008E6FBD0E09BD6F11D3C7C0FB4B817234C94ADAC0819AA2A87F8B` |
| 签名 | Android **debug** 签名（不是发布签名） |
| 系统要求 | Android 8.0+（minSdk 26），`targetSdk 35`，`compileSdk 36` |

## 这版改了什么

### 1. 安全：密钥不再进 logcat（建议 0.1.0 ～ 0.1.2 的用户升级）

旧版的认证流程会把派生密钥、会话密钥、authToken 等用十六进制打进 logcat。
0.1.3 起这些地方**只打印长度**；`HuaweiPacket` / 各认证报文的 `toString` 也不再输出包体。
协议行为没有任何改变。详情见 [`SECURITY.md`](SECURITY.md)。

### 2. 心跳直播的口径

- 卡片上把「已收 N 拍」改成「**有效** N 拍」，并多一行「样本合计 = 有效 + 丢弃 · 收到 M 个包（每包约 K 个样本）」。
  这样能看出手环每包装几拍，也就是 lag 判断「是否成对推送」时缺的那块数据。
- lag 的说明改成：≈0 ms **与逐拍实时推送相符**，但单凭 lag **不能排除** ≤1 个包内的缓冲 / 成对推送。
- MCP `band_hr_live` 多返回 `samples`（有效 + 丢弃）和 `packets`，原有字段不变。

### 3. 文档

- README 改成一页入口：定位 / 兼容性矩阵 / 证据分级（`[VERIFIED]` `[IMPLEMENTED]` `[INFERRED]` `[UNSUPPORTED]`）/ 安装 / MCP / 架构。
- 逆向过程长文移到 [`docs/reverse-engineering.md`](docs/reverse-engineering.md)。
- `NOTICE.md` 写明上游 Gadgetbridge 的版本（`7257742`，2026-11-18）、零第三方库、AGPL 的实际含义。
- 新增 [`SECURITY.md`](SECURITY.md)：怎么私下报漏洞、MCP 服务的安全边界。

## 已验证 / 未验证

**已验证**

- 干净构建（`clean` + `assembleDebug`）通过；`aapt` 读到 `versionCode='4' versionName='0.1.3'`。
- 离线自测 `selftest.sh` → **PASS 251/251**。
- `git grep "createSecretKey ->"` 为空：没有旧的打印密钥的写法。

**未验证**

- 这一版没有在真机上跑过。改动只涉及日志、文字和两个 MCP 字段，协议路径没动，但还是要真机看一眼心跳卡的新行。
- 0.1.2 的健康页仍然没在真机上看过（见 [`RELEASE-0.1.2.md`](RELEASE-0.1.2.md)）。

## 怎么编出来的

```powershell
$env:JAVA_HOME='D:\dev\jdk-21.0.13.0.2+1'    # JDK 21
$env:ANDROID_HOME='D:\dev\android-sdk'
.\gradlew.bat clean :app:assembleDebug --no-daemon
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

Gradle 8.14.3（wrapper 自带）、Android Gradle Plugin 8.13.0。

## 安装、数据、许可

和 0.1.2 一样，见 [`RELEASE-0.1.2.md`](RELEASE-0.1.2.md) 的「安装」「数据与网络」「许可与来源」三节。
debug 签名：装过别的签名的版本要先卸载。
