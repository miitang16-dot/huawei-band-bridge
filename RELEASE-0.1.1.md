# 手环桥 0.1.1 · 测试包

0.1.0 之后的第一版：加了 **「心跳直播」**——按一下测一段时间，能看到手环**一拍一拍**的心率
（走 `0x19` 的 RRI 流），不再只有「每分钟一格」。仍然是**测试版**。

| | |
|---|---|
| 版本 | `0.1.1`（versionCode 2） |
| 包名 | `io.github.bandbridge` |
| 文件 | [`dist/huawei-band-bridge-0.1.1-debug.apk`](dist/huawei-band-bridge-0.1.1-debug.apk) |
| 大小 | 254487 字节 |
| MD5 | `174BAE0C286701D6BB32ED215DD47E8A` |
| SHA-256 | `5596935E415F824B9D4790341CB83C6147221A2C864D747D01820C5E3FEA0718` |
| 构建时间 | 2026-11-23 00:12 |
| 签名 | Android **debug** 签名（不是发布签名） |
| 系统要求 | Android 8.0+（minSdk 26），`targetSdk 35`，`compileSdk 36` |

## 这版改了什么

1. **bandcore：心跳直播**（`data/HrLiveSession.java` + `proto/hr/RriBeats.java`）
   - 开手环的逐拍测量（`0x19/0x01 {01:03}`）→ 收 `0x19/0x05`（每拍 `rri` + `sqi`）→
     `HR = 60000 / rri`；维护「当前（10 秒窗口）/ 整段平均 / 只看最后一拍」三个口径；
     `sqi=0`、<250 ms、>3000 ms 的脏拍过滤掉；
   - 时长可配：默认 60 秒，另给 3 分钟档（**能不能开这么久未验证**）；失败/没数据会优雅收尾
     （关流、发 `{01:04}`、把原因写进探针），不会把连接弄坏；
   - **自包含**：不依赖 `app` 模块、不依赖 Android（没有 `Context`/`BluetoothGatt`），
     只吃一个已经认证好的 `RequestRunner`；`start(seconds, callback)` / `stop()` / `snapshot()`
     三个方法就能被别的壳（Capacitor 插件等）直接收。
2. **App：「心跳直播」卡片**：BPM、整段平均、已收拍数、**最近一拍距今多少毫秒**、
   **lag 估计**、首包延迟、起止时间；1 分钟 / 3 分钟两档 + 停止；测完保留最后一次结果。
   她没法用 logcat，所以 `hwlive` 探针（开流成功码 / 首包延迟 / 拍数 / lag）也会出现在 App 日志页。
3. **MCP：新工具 `band_hr_live`**：`seconds` 传了就开一次（5–180），不传只读当前/最近一次。
   返回字段固定：`ok / running / bpm / bpm_avg / beats / window_ms / lag_ms / last_beat_ms_ago /
   started_at / device / unverified`；没在播就 `{ok:false, running:false, error:"hr_live_not_running"}`。
4. **测试**：离线自测从 168 项加到 **235 项**（新增 `RriBeats`：间期→心率、时间累加、窗口收缩、脏拍过滤）。
5. **文档**：README 中英 + `docs/ROADMAP.md` 改成准确表述（「每分钟一格」是**记录**粒度；
   `0x19` 是逐拍流但**延迟未测**）；新增 `docs/lab/hrlive-0x19.md` 协议笔记。

## 安装

1. 手环先跟「华为运动健康」**解绑**（华为 App 里删除设备，或手环恢复出厂）。
   两边不能同时连。
2. 把 APK 传到手机上装（允许「安装未知来源应用」）。
   - 这是 debug 签名：如果之前装过别的签名的版本，要先卸载再装。
3. 打开 App →「连接」，允许「附近的设备」「通知」权限。第一次连接手环上可能要点一下确认。
4. （可选）点「开始校准」静坐 1 分钟，之后手环会自己测压力。
5. 想看心跳：**「心跳直播 → 开始（1 分钟）」**，戴紧、安静坐着，看卡片上的 bpm / 最近一拍 /
   lag；日志页里会同时出现 `hwlive` 那几行。
6. MCP 那张卡里有地址和口令，复制配置粘到 AI 客户端。详见 [README](README.md)。

## 怎么编出来的

```powershell
$env:JAVA_HOME='D:\dev\jdk-21.0.13.0.2+1'    # JDK 21
$env:ANDROID_HOME='D:\dev\android-sdk'
$env:GRADLE_USER_HOME='E:\dev\.gradle'
.\gradlew.bat clean :app:assembleDebug --no-daemon
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

- Gradle 8.14.3（wrapper 自带）、Android Gradle Plugin 8.13.0。
- **干净重建了两次**，出的 APK 字节完全一致（同一个 MD5 `174BAE0C…`），
  说明 `dist/` 里这个包就是这份源码编出来的。

## 已验证 / 未验证

### 已验证

- **编译**：`:app:assembleDebug` 通过（干净重建两次，产物可复现，MD5 相同）。
- **离线自测**：`selftest.ps1` **235/235 全过**（`RriBeats` 的换算和窗口逻辑在这轮里）。
- **协议事实**（来自真机，早于这版 App）：`0x19/0x01 {01:03}` 在 HUAWEI Band 10 上被接受
  （回成功码 `7f04 0001 86a0`），60 秒收到 **85 拍**逐拍 RRI。这是「心跳直播」能成立的前提。
- **App 的其余部分**（连接 / 分钟同步 / 睡眠 / 压力 / 情绪 / 天气 / 音乐）沿用 0.1.0，
  `bandcore` 那一侧在真机上跑通过（见 `RELEASE-0.1.md`）。

### 未验证（0.1.1 老实说）

- **心跳直播这个功能本身还没有在真机上跑过**：`lag` 到底是多少（几十毫秒？几秒？还是攒到 60 秒才推）、
  手环肯不肯开 3 分钟、测量时手环屏幕 / 耗电什么样，全都没量。**界面和工具里都标了「未验证」**。
- **这个 APK 本身**没有在真机上完整跑一轮（0.1.0 的那条清单仍然有效）。
- **MCP 的 `band_hr_live`** 没有用真实 AI 客户端调过；返回字段是按契约写的，代码层面没跑过端到端。
- **被别的壳嵌入**（Nexus 那种 Capacitor 插件直连 bandcore）没验证；`HrLiveSession` 是按「只依赖
  `RequestRunner`」写的，但真正的宿主还没接。
- **鸿蒙兼容层**上「同机 App 访问 `127.0.0.1:8848`」没验证（MCP 服务监听的是所有网卡，回环包含在内；
  理论上是通的，但这台手机上原生网络以前被系统静默拦过，不敢写「已验证」）。
- **其他型号手环**没测：目前只在 Band 10（`Nora-B19FP`）上验证过协议。

## 数据与网络

- 健康数据（心率、血氧、步数、静息心率、睡眠、压力、情绪、心跳直播的拍）**只存在手机本地**
  （`SharedPreferences` + App 私有目录的 JSON / 内存快照），不上传任何服务器。
- **唯一的联网**是天气：向 Open-Meteo（免费、无需注册）请求当前与预报天气，发给手环。
  只发经纬度**四舍五入到 0.1°**（约 10 km）的粗略位置，不带任何身份信息。
- MCP 服务在手机本机监听 8848 端口，用随机 Bearer 口令鉴权；**没有对外开放**，
  只建议在同一个局域网（或用 Tailscale 之类的组网）里用。明文 HTTP，别直接映射到公网。
- 不需要华为账号，不需要华为运动健康（反而要跟它解绑）。

## 许可与来源

- 本项目以 **AGPL-3.0** 发布，见 [`LICENSE`](LICENSE)。
- 协议实现大量移植自 [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)（AGPL-3.0），
  来源文件与原版权头保留，详见 [`NOTICE.md`](NOTICE.md)。
- 与华为无关，非官方。「Huawei」「HUAWEI Band」「TruSleep」是华为的商标。
- 华为更新手环固件后协议可能失效。

## 已知问题

- debug 签名，不能覆盖安装到别的签名的包上（先卸载）。
- 手环必须跟华为运动健康解绑，且同一时间只能有一个 App 连它。
- 手环停在出厂引导页太久会「忘掉」绑定，App 会自动重新绑定（免 PIN）。
- 数据存档有保留期：分钟数据 10 天、科学睡眠 60 晚、压力/情绪 30 天。更早的数据要手环里还有才能重新拉。
- 心跳直播期间，分钟同步会停那几十秒（跟压力校准一样），测完自动恢复。
- 走的是逆向协议，不保证与任何固件版本兼容；这些数据不是医疗诊断。
