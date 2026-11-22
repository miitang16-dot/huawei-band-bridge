# 手环桥 0.1.0 · 测试包

第一个公开的、能装的版本。**测试版**：协议核心在 HUAWEI Band 10 真机上跑通过，
App 和 MCP 服务是重新整理出来的，还在逐项真机复测。

| | |
|---|---|
| 版本 | `0.1.0`（versionCode 1） |
| 包名 | `io.github.bandbridge` |
| 文件 | [`dist/huawei-band-bridge-0.1-debug.apk`](dist/huawei-band-bridge-0.1-debug.apk) |
| 大小 | 236105 字节 |
| MD5 | `CF784568A0E8E6E18C9954EB4CC5ED0F` |
| SHA-256 | `00C64F4ABD45F51F2EDC0E3F83434E36F4EB1A422DBDEF2A447671A62F8ECB1C` |
| 构建时间 | 2026-11-22 23:26 |
| 签名 | Android **debug** 签名（不是发布签名） |
| 系统要求 | Android 8.0+（minSdk 26），`targetSdk 35`，`compileSdk 36` |

## 安装

1. 手环先跟「华为运动健康」**解绑**（华为 App 里删除设备，或手环恢复出厂）。
   两边不能同时连。
2. 把 APK 传到手机上装（允许「安装未知来源应用」）。
   - 这是 debug 签名：如果之前装过别的签名的版本，要先卸载再装。
3. 打开 App →「连接」，允许「附近的设备」「通知」权限。第一次连接手环上可能要点一下确认。
4. （可选）点「开始校准」静坐 1 分钟，之后手环会自己测压力。
5. MCP 那张卡里有地址和口令，复制配置粘到 AI 客户端。详见 [README](README.md)。

## 怎么编出来的

```powershell
$env:JAVA_HOME='D:\dev\jdk-21.0.13.0.2+1'    # JDK 21
$env:ANDROID_HOME='D:\dev\android-sdk'
.\gradlew.bat clean :app:assembleDebug --no-daemon
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

- Gradle 8.14.3（wrapper 自带）、Android Gradle Plugin 8.13.0。
- 干净重建（`clean` 后重编）出的 APK 与上一轮**字节完全一致**（同一个 MD5），
  说明仓库里的源码就是出这个包的那份源码。

## 已验证 / 未验证

### 已验证

- **编译**：干净重建通过，产物可复现（两轮 MD5 相同）。
- **协议核心（`bandcore/`）** 在 HUAWEI Band 10 真机上跑通过：HiChain 认证（免 PIN）、
  设备初始化、每分钟心率 / 血氧 / 步数 / 静息心率、TruSleep 科学睡眠、
  压力校准与自动压力、情绪（P2P 字典同步）、天气下发、手环音乐页控制。
  这部分代码原来在一个自用 App 里，现已整理成独立库 `bandcore/`；上述结论来自那份真机工作。
- **数据只在本机**：没有账号、没有云端，健康数据不上传（见下）。

### 未验证（0.1 版老实说）

- **这个 APK 本身**还没有在真机上跑完一整轮：首次配对（要 PIN 的那条分支）、
  掉线重连、后台保活、MCP 客户端接入后的 6 个工具逐条调用。
- **MCP 服务**没有被各种客户端（Claude Desktop / Cursor / 自写脚本）都试过；
  协议按 Streamable HTTP（POST /mcp，JSON-RPC）实现，只支持 JSON 响应，不提供 SSE 流。
- **手环自带的情绪页**仍然显示「手机运动健康 App 版本过低」——需要华为运动健康 + 华为云算法，
  本项目做不到（App 里的情绪数据是拉回来的原始值，仍是有效的）。
- **其他型号手环**没测：目前只在 Band 10（型号 `Nora-B19FP`）上验证。
- **实时心率**：Band 10 没有实时心率通道（详见 [docs/ROADMAP.md](docs/ROADMAP.md)），
  能做到的是每分钟同步一次，不是「此刻正在跳多少」。
- **发布签名 / 上架商店**：没做。

## 数据与网络

- 健康数据（心率、血氧、步数、静息心率、睡眠、压力、情绪）**只存在手机本地**
  （`SharedPreferences` + App 私有目录的 JSON），不上传任何服务器。
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
- 走的是逆向协议，不保证与任何固件版本兼容；这些数据不是医疗诊断。
