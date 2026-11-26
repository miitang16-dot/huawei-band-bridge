# 手环桥 0.1.14 · 测试包（Band 11 诊断 + 心跳回放）

这一版是给 **Band 11 那位用户**做的诊断版：把"连上之后到底发生了什么"写进日志，让下一次反馈能直接定案；顺带把 P1 的**心跳逐拍回放**做出来了。仍然是测试版，debug 签名。

| | |
|---|---|
| 版本 | `0.1.14`（versionCode 15） |
| 包名 | `io.github.bandbridge` |
| 文件 | [`dist/huawei-band-bridge-0.1.14-debug.apk`](dist/huawei-band-bridge-0.1.14-debug.apk)（下面附件里也有） |
| 大小 | 315129 字节 |
| MD5 | `F683810E70B0772883B3AE3B2C76A78A` |
| SHA-256 | `8F174A1AB344CC827EF5DE249B68D2815EB7E257AFFC9FAEB96B10F106F42F0D` |
| 签名 | Android **debug** 签名（证书与 0.1.0~0.1.13 相同，可以覆盖安装） |
| 系统要求 | Android 8.0+（minSdk 26），`targetSdk 35`，`compileSdk 36` |

## ① Band 11 诊断（主因）

用户实测日志（0.1.13）里，故障是**连接之后的 GATT 服务发现**，不是"扫描没找到"：

```
connected status=0 → discgo ret=1 → disc status=0 n=0 → 找不到 FE86 → fail nosvc
```

而且 `cand` 那行只有 rssi、没有设备名/MAC ⇒ 从日志上**证明不了连的是哪台**。这一版把证据补齐（只加日志 + 两处低风险修正，不改协议）：

- `cand` / `pick` / `connected` 三行都带上 **设备名 + 地址**；
- 扫描路径连接改为显式 **`TRANSPORT_LE`**（原来不指定 transport，而自动直连那条路径用的是 LE；双模手环 + 系统里有旧配对时 AUTO 可能走 BR/EDR ⇒ 服务表为空）；
- 首次 `nosvc` **先重试一次**服务发现（连上仅 320ms 就去发现，表可能还没就绪），仍然没有才如实失败；
- 「复制诊断信息」的**白名单加了 `hwlink` / `hwscan`** —— 以后用户点一下就能把连接日志带回来，不用只截日志面板。

⚠️ **Band 11 本体没有验证过**：我们手上只有 Band 10。这一版是"让下一份日志能定案"，不是"已经修好"。

## ② 心跳逐拍回放（P1）

- 新增 `BeatReplay`（纯逻辑，零 Android 依赖）：把一包 RRI 换成"每拍相对时刻 + 每拍心率"，只保留最近 4 秒（避免成批推送时越排越后）。
- `HrLiveSession.Snapshot` 多一个 `lastPacketRris`（additive，不动原有字段）。
- 新的 `PulseView`：按时间表画外扩环 + 心跳点，最后一拍结束后自动停。
- 心跳卡片里加一行说明：「按这包 N 个间隔回放 · 这批约 X 秒前收到（不是『此刻这一拍』）」——**不假装是实时**。

⚠️ **动画没上过真机**（观感、流畅度、每包约 4 秒的衔接、配合真实 RRI 的顺序），需要戴着手环看一次；已写进 ROADMAP P0。

## 证据等级

- `[VERIFIED]` 自测 **298/298** 全绿（新增 32 项是 `BeatReplay` 的）；`:app:assembleDebug` 构建通过。
- `[VERIFIED]` Band 11 的故障点在服务发现（来自用户日志），不是权限/蓝牙开关/扫不到广播。
- `[UNSUPPORTED]` Band 11 是否必须系统配对后才暴露服务：**无证据**，待下一份日志。
- `[INFERRED]` `TRANSPORT_LE` 与"首次 nosvc 重试"可能改善；两者都属低风险修正。

## Band 11 用户请这样帮我们

1. 装 0.1.14，连上手环（如果能连上的话）；
2. 连不上也没关系——**打开 App 首页的「日志」，复制最后 30 行**发回来；或者点「复制诊断信息」（这一版已经包含 `hwlink` 了）；
3. 如果方便，装一个 **nRF Connect**，扫到手环后截三张：名字+MAC+RSSI、Advertisement Data（Service UUIDs / Manufacturer data）、CONNECT 之后的 Services 列表——有这三张，我们能直接定案。
