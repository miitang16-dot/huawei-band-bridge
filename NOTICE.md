# NOTICE · 第三方来源与许可

Huawei Band Bridge is licensed under the **GNU Affero General Public License v3.0** — see [`LICENSE`](LICENSE).

## 1. Gadgetbridge（上游来源）

`bandcore/` 里华为手环协议的实现大量移植自
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)（AGPL-3.0）。

- **上游版本**：Git clone `HEAD = 7257742b96a30fff0db9e79230435ac2aeb39f57`
  （`7257742`，2026-11-18，「Baseus Bowie MC1: Add headphone settings」）。
  移植工作基于这个提交的源码；上游之后的变化没有跟进。
- **移植了哪些**：
  - 帧编解码、TLV、加解密、CRC：`proto/`（`HuaweiPacket`、`HuaweiTLV`、`HuaweiCrypto`、`CryptoUtils`、
    `CheckSums`、`HuaweiUtil`、`HuaweiConstants`、`HuaweiDeviceType`）
  - HiChain 认证的报文与状态机：`auth/`（`AuthFlow`、`AuthState`、`HiChainPakeUtils`）与
    `proto/packets/DeviceConfig.java`
  - 各服务报文定义：`proto/packets/`（`DeviceConfig`、`FitnessData`、`FileDownloadService2C`、
    `FileUpload`、`DataSync`、`P2P`、`HrRriTest`、`Stress`、`Weather`、`MusicControl`、`App`、
    `Notifications`、`HuaweiDataSyncCommon`）
  - TruSleep / 序列数据解析：`HuaweiSequenceDataFileParser`、`HuaweiTrueSleepSequenceDataParser`、
    `HuaweiSequenceDataParserBase`
  - 压力校准数学与 RRI 文件解析：`proto/stress/*`、`HuaweiStressParser`
- **改了什么**：包名改为 `io.github.bandbridge.*`；把 slf4j 日志换成 `Probe`（logcat + App 回调）；
  去掉依赖 Gadgetbridge 数据库 / 设备模型 / Android UI 的部分；少量文件按本仓库结构合并。
- **保留了什么**：移植文件里的原始版权头（`Copyright (C) ...` / AGPL 头）一律保留，不删除、不改写。
- **没有移植**：PAKE / STS 的调度（`HiChainPakeUtils` 只有算法实现，`AuthFlow` 没有接上）、
  OTA / 表盘 / 通知发送 / 联系人 / 闹钟等报文。

## 2. 依赖清单：零第三方库

本项目**没有引入任何第三方运行时依赖**：

- `bandcore/` 只用 Android SDK（`android.*`）与 JDK 标准库（`javax.crypto`、`org.json` 是 Android 平台自带的）。
- `app/` 只用 Android SDK 的系统 View / Canvas，没有 AndroidX、没有图片加载库、没有网络库、
  没有 MCP SDK（`McpServer` 是手写的 HTTP + JSON-RPC）。
- 构建只依赖 Android Gradle Plugin 与 Gradle wrapper（开发工具，不进入产物）。
- 离线自测只用 JDK 的 `javac` / `java`，不拉任何测试库（见 [`docs/TESTING.md`](docs/TESTING.md)）。

因此**不存在其它第三方库的许可证冲突**；唯一的上游代码来源是第 1 节的 Gadgetbridge。

## 3. AGPL-3.0 意味着什么（实际含义）

- 你可以自由使用、修改、分发本项目，但**分发（或让别人通过网络使用）本项目时，必须提供完整源码**，
  并且修改后的版本同样以 AGPL-3.0 授权。
- 如果有人把这个 App / 协议库放进自己的产品里分发，或把它作为网络服务提供给用户，
  那么他们也需要把对应的源码（包括修改）以 AGPL-3.0 开放。
- 从 Gadgetbridge 移植来的文件保留原版权头；本仓库的其余代码由本仓库的贡献者以 AGPL-3.0 授权。
- 这些是常见理解，不构成法律意见；如有商业使用计划，请咨询律师。

## 4. 与华为无关

"Huawei"、"HUAWEI Band"、"TruSleep" 是华为技术有限公司的商标。
本项目与华为无关、非官方、未获其背书；它通过逆向出来的私有协议与手环通信，
华为更新固件后协议可能失效。这些数据不是医疗诊断。
