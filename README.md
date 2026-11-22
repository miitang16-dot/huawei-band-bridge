# Huawei Band Bridge · 手环桥

**让 AI 直接读你的华为手环 —— 不用华为运动健康、不用华为账号、数据不上任何云。**
*Let your AI read your Huawei band directly: no Huawei Health app, no Huawei ID, no cloud.*（[English](README.en.md)）

> 🧪 **0.1 测试版。** 协议核心在真机（HUAWEI Band 10）上跑通；App 和 MCP 服务刚写好，正在真机测试。

## 下载 / 版本

| 版本 | 文件 | 说明 |
|---|---|---|
| `0.1.0`（2026-11-22） | [`dist/huawei-band-bridge-0.1-debug.apk`](dist/huawei-band-bridge-0.1-debug.apk) | 第一个测试包，debug 签名 |

- 直接点上面那个链接下载（GitHub 页面上是 `dist/` 里的文件，用「Download raw file」也能下）。
- 安装步骤、已验证 / 未验证清单、校验值（MD5 / SHA-256）、构建方式都在
  [`RELEASE-0.1.md`](RELEASE-0.1.md)。路线图和已知限制见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。
- debug 签名，不能覆盖安装到别的签名的包上；装了旧包先卸载。
- 从源码自己编（JDK 21 + Android SDK 36）：

```powershell
.\gradlew.bat :app:assembleDebug
# 产物 app\build\outputs\apk\debug\app-debug.apk
```

## 它是什么

一个安卓 App，用蓝牙直接连华为手环（走华为自己的私有协议，跟官方 App 一样完成 HiChain 认证），
把数据存在手机本机，并在手机上开一个 **MCP 服务**，让支持 MCP 的 AI（Claude、Cursor、各种 AI App…）
可以查你此刻和过去的健康数据，还能反过来给手环送天气、让手环控制手机音乐。

## 能拿到什么（Band 10 真机验证过）

| 数据 | 粒度 | 说明 |
|---|---|---|
| 心率 / 血氧 / 步数 / 静息心率 | 每分钟一格 | 手机每分钟同步一次（手环不支持实时推心率，这已是上限） |
| 科学睡眠 TruSleep | 每分钟阶段 | 深睡 / 浅睡 / 快速眼动 / 清醒 + 睡眠分，跟手环屏幕逐分钟对得上 |
| 压力 | 手环自测 | 先在 App 里静坐校准 1 分钟，之后手环自己测，分数 1–99 |
| 情绪 | 手环自测 | 愉悦度 / 激活度 / 三档状态（手环自带的情绪页仍需华为 App，见下） |
| 天气 → 手环 | 每小时 | Open-Meteo，只发粗略位置 |
| 手环音乐页 → 手机播放器 | 实时 | 播放 / 暂停 / 上一首 / 下一首 / 音量 |

## 跟其它「华为健康 MCP」有什么不同

GitHub 上已有的华为健康 MCP 基本都是 **从华为云取数据**（官方 Health Kit，或逆向华为运动健康的云接口），
需要华为账号、手环要连着华为运动健康，数据先经过华为云，多为每日汇总。

这里走的是另一条路：**手机直接和手环说话**。

| | 本项目 | 云端方案 |
|---|---|---|
| 需要华为运动健康 App | ❌（必须先和它解绑） | ✅ |
| 需要华为账号 | ❌ | ✅ |
| 数据经过云 | ❌ 只在你手机上 | ✅ 华为云 |
| 心率粒度 | 每分钟 | 多为每日汇总 |
| 能给手环写东西（天气 / 音乐） | ✅ | ❌ |
| 历史数据（解绑前的） | ❌ | ✅ |
| 手环自带的情绪页 | ❌ 显示「App 版本过低」 | ✅ |
| iPhone | ❌ 只支持安卓 | ✅ |

两条路各有取舍，按自己的需要选。

## 怎么用

1. 手环先和「华为运动健康」**解绑**（见下面的注意事项）。
2. 装上手环桥 App，点「连接」，允许「附近的设备」「通知」权限。第一次连接时手环上可能要点确认。
3. （可选）点「开始校准」静坐 1 分钟 —— 之后手环会自己测压力。
4. 「MCP 服务」那张卡里有地址（`http://手机IP:8848/mcp`）和口令，点「复制配置」，粘到你的 AI 客户端：

```json
{
  "mcpServers": {
    "huawei-band": {
      "type": "http",
      "url": "http://192.168.1.23:8848/mcp",
      "headers": { "Authorization": "Bearer <口令>" }
    }
  }
}
```

AI 和手机要在同一个局域网（或者用 Tailscale 之类的组网）。不能设置请求头的客户端可以用 `…/mcp?token=<口令>`。

5. 想把数据留一份自己的？「导出数据」那张卡点一下：会往手机的 `Download/HuaweiBandBridge/`
   写一个原样打包的 JSON + 每个存档一个 CSV（按日期摊平，Excel 能直接开）。只写本机，不上传。

### MCP 工具

| 工具 | 干什么 |
|---|---|
| `band_status` | 连没连上、型号、电量、最新数据到几点、压力校准了没 |
| `band_now` | 最新的心率 / 血氧 / 静息心率 / 今天步数 / 压力 / 情绪 / 昨晚睡眠（每项带记录时间） |
| `band_day` | 某一天或某个时间段的心率 / 血氧 / 步数统计 + 分段序列（长时段自动放粗，写明粒度） |
| `band_sleep` | 某天早上醒来那一觉：入睡 / 醒来、睡眠分、深浅 REM 清醒分钟、睡时心率血氧、阶段时间线 |
| `band_stress` | 某天手环测的压力（1–99 + 档位） |
| `band_emotion` | 某天手环测的情绪（愉悦度 / 激活度 / 三档状态） |

没有记录就返回 null / 空，**不会补数**。

## 架构

两个 Gradle 模块：`bandcore/` 是可以单独用的华为手环协议库，`app/` 是那个 App。

```
app/                        一屏 UI + 前台服务 + 手机上的 MCP 服务
  BandService                 常驻前台服务：持有 BandSession 和 MCP 服务，屏幕关掉也连着
  MainActivity                纯原生 View 的一屏：连接 / 同步 / 校准 / MCP 配置 / 个人资料 / 日志
  McpServer                   手机上的 MCP（Streamable HTTP，POST /mcp，Bearer 口令）
  BandTools                   6 个工具的实现：读本机存档，回答带记录时间和粒度
  WeatherFetch                天气（Open-Meteo，只发粗位置）
  DataExport                  数据导出：本机存档 → Download/HuaweiBandBridge（JSON + CSV）
  Media · MediaListener       读当前播放（通知使用权），把手环音乐页接上手机播放器

bandcore/                   华为手环协议核心（无 UI、无网络、无华为依赖）
  transport/HuaweiLink        扫描 → 连接 → 发现服务 → 订阅 FE02 → 写队列（片间 22ms）→ 重连
  transport/PacketAssembler   攒分片并分发：认证期的包给 AuthFlow，其余当异步包
  transport/RequestRunner     发一个请求、阻塞等它的响应；支持「流」（0x2C 文件下载）
  proto/                      帧编解码（magic 0x5A / TLV / AES-ECB·CBC·GCM + HKDF + HMAC）、
                              CRC16、各 service 的报文类
  auth/AuthFlow               认证状态机：LinkParams → SecurityNegotiation →
                              HiChain（PIN+BIND / 重连 op=2）· HiChainLite · Normal
  auth/AuthState              三个必须持久化的身份（authkey / 伪造的 device_mac / android_id）
  auth/HiChainPakeUtils       PAKE（Honor Watch 5 那条路）的实现，目前 AuthFlow 没接上
  data/DeviceInit             认证后初始化：设备信息、对时、电量、命令表、同意协议、
                              上报已连接、语言 / 日期 / 账号、打开手环的自动测量开关
  data/StepSync …             各同步器：分钟数据、TruSleep（0x2C 文件下载 + 序列数据解析）、
                              压力（60 秒 RRI 校准 + 文件）、情绪（P2P 字典同步）、天气、音乐
  data/*Store                 本机存档（App 私有目录里的 JSON）
```

**线程模型**：BLE 回调线程只把字节喂给 `PacketAssembler`，绝不做重活；认证、初始化、
同步全在一个 worker 线程上（`AuthFlow.run()` 阻塞等响应，不能和喂包同线程）；
UI 只读状态。写特征值一律走 `HuaweiLink` 内部的队列，一次一片，不要自己并发写。

**同步节奏**（连上后 worker 每轮跑一次）：分钟数据每 1 分钟；科学睡眠每 30 分钟；
压力文件每 30 分钟；情绪每 10 分钟或手环来通知时立刻；天气每小时或手环来要时；
手环来问手机信息 / 天气 / 音乐，就在同一个循环里回。

**数据流**：手环 →（BLE 私有协议）→ `bandcore` → 本机 JSON 存档 →（MCP，本机 8848 口）→ AI 客户端。
中间没有服务器。想直接拿库用的看下一节。

## 隐私

- 健康数据（心率、血氧、步数、静息心率、睡眠、压力、情绪）**只存在手机本地**，不上传、不联网。
- **唯一的联网**是天气：向 Open-Meteo 取当前 / 预报天气发给手环，只发经纬度四舍五入到 0.1°
  （约 10 km）的粗略位置，不带身份信息，不需要任何 key。
- 不需要华为账号，不用华为运动健康（反而要跟它解绑）。没有广告、没有统计、没有第三方 SDK。
- MCP 服务在手机本机监听 8848 端口，随机 Bearer 口令鉴权；**不要映射到公网**，
  建议只在同一局域网（或 Tailscale 之类的组网）里用。
- 卸载 App 就清干净：身份、口令、数据全在 App 私有目录和 SharedPreferences 里。
- 「导出数据」写出的文件也只落在手机本地存储（Download 目录或 App 外部目录），App 不会上传它们。

## 测试 / 开发

协议层（TLV、帧、CRC16、分片、时间换算）有**离线自测**，不需要手环、不需要 Android SDK、不联网：

```powershell
.\selftest.ps1      # Windows
./selftest.sh       # Linux / macOS / Git Bash
```

只用 JDK 17+（项目用 JDK 21）跑 `javac` + `java`，当前 **168 项检查**（TLV 编解码与边界、CRC16 对照独立实现、
帧头/长度/半包/分片重组、`PacketAssembler` 喂包、`HuaweiUtil` 时间换算）。测什么、测不到什么（蓝牙链路、
认证、加密 KAT 都还没覆盖）写在 **[docs/TESTING.md](docs/TESTING.md)**，请先读那篇再改协议代码。

`.github/workflows/build.yml` 在 push / PR 时跑自测 + `:app:assembleDebug` 并把 APK 传成 artifact；
**这个 workflow 还没在 GitHub 上真实跑过**（本机无法验证 Actions），第一次运行可能要小修。

## 当库用（给自己写 App 的人）

`bandcore/` 是一个独立的 Android 库。核心是 `BandSession`：

```java
BandSession s = new BandSession(context, new BandSession.Hooks() {
    @Override public void onData(int newSamples) { /* 新数据进来了 */ }
    @Override public JSONObject weather() { return myWeather; }          // 可选：给手环的天气
    @Override public void musicButton(int b) { /* 手环音乐页按键 */ }      // 可选
});
s.start();                       // 扫描 → 认证 → 初始化 → 每分钟同步
s.samples().query(fromSec, toSec);   // 心率 / 血氧 / 步数
s.truSleep().query(fromMs, toMs);    // 科学睡眠
s.stress().query(fromMs, toMs);      // 压力
s.emotion().query(fromMs, toMs);     // 情绪
```

## ⚠️ 用之前要知道

- **手环必须先和华为运动健康解绑**（华为 App 里删除设备 / 手环恢复出厂），两边不能同时连。
- 只支持 **安卓**（8.0+）。App 用常驻通知保持后台连接；点通知里的「断开」或 App 里的「断开」才会停。
- 同一时间只能有**一个** App 连手环：用手环桥时，别的直连手环的 App 要先断开。
- 协议是逆向来的，华为更新手环固件后可能失效。
- 这些数据**不是医疗诊断**。

## 已知的坑（省得你再踩）

- Band 10 走「新同步」：文件下载用服务 `0x2C`，不是 `0x0A`。
- 科学睡眠阶段码：`1` 浅睡 / `2` 快速眼动 / `3` 深睡 / `4` 清醒。
- 实时心率 `0x07/0x1c` 手环不支持。
- 手环只在刚连上时问一次手机信息（`0x01/0x10`），之后不再问。
- 天气：手环发 `0x0f/0x04` 带 `7f=186AA` 时只回天气、别回「收到」，否则会每 3 秒要一次。
- HRV 字典 `500044`、皮肤温度 `400012` 在 Band 10 上拿不到。
- 还没在鸿蒙上验过的事：MCP 服务的本机监听、天气请求（此前别的 App 的原生网络在这台手机上被系统静默拦过）。

## 致谢 / 许可

协议实现大量移植自 [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)（AGPL-3.0），
因此本项目同样以 **AGPL-3.0** 发布，详见 `LICENSE` 和 `NOTICE.md`。
与华为无关，非官方。
