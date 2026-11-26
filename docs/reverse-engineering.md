# 华为手环（Band 10）协议逆向 · 过程与结果

> 设备：HUAWEI Band 10（型号 `Nora-B19FP`），手机：HarmonyOS 7。
> 目标：**不经过华为运动健康 / 华为账号 / 华为云**，用自己的 App 直接跟手环说话，数据留本机。
> 代码：本仓库 `bandcore/`（AGPL-3.0，大量移植自 Gadgetbridge，见 [`NOTICE.md`](../NOTICE.md)）。
>
> 证据分级（全文使用）：`[VERIFIED]` 真机验证过 · `[IMPLEMENTED]` 已实现但未充分验证 ·
> `[INFERRED]` 从源码 / 协议推断 · `[UNSUPPORTED]` 已确认不支持。

## 0. 一句话结论

华为手环的主通道是 **`0xFE86` 上的私有二进制协议**（magic `0x5A` + 长度 + serviceId + commandId + TLV，
可选 AES 加密与分片），认证走 **HiChain**，跟官方 App 用的是同一套。协议通了之后能拿到：
分钟级心率 / 血氧 / 步数 / 静息心率、TruSleep 科学睡眠分期、压力、情绪字典、
以及**逐拍心跳（RRI，服务 `0x19`）**；还能反向把天气、音乐控制写进手环。

## 1. 探测方法（没有 adb 的情况下怎么摸）

前提限制：**这台 HarmonyOS 7 手机没有 adb**，只有 HDC（要从 OpenHarmony SDK 里抠，约 3 GB，不划算）。
所以整条路的探测手段是：自己写探针 App + 把 Gadgetbridge 源码当「协议字典」+ 在 App 里留探针读结果。

1. **探针 App** `[VERIFIED]`：一个 16.6 KB 的 debug 包，扫到 35 个 BLE 设备；第二版加了 GATT 枚举，
   把每个服务的 UUID / 特征 / 属性打出来。结论：**兼容层里第三方 App 能正常操作蓝牙**，HarmonyOS 没拦 BLE。
   （另外验证过：**原生 `HttpURLConnection` 会被系统静默拦掉** —— 这条结论直接废掉了「用 Gadgetbridge
   中转」那条路，见 §6 第 6 条。）
2. **GATT 枚举结果** `[VERIFIED]`（Band 10）：

   | 服务 | 写 | 通知 | 说明 |
   |---|---|---|---|
   | `0xFE86` | `0xFE01` | `0xFE02` | **华为主通道**：所有私有协议都走这里 |
   | `0xE2E3` | `0xE2E4` | `0xE2E5` | 次通道 |
   | `0xE2CB` | `0xE914` | `0x1A19` | 辅助 |
   | `0x1987` | `0x2911` | `0x2910` | 辅助 |
   | `0x1887` | `0x2A11` | `0x2A10` | 辅助 |
   | `cc353442-be58-4ea2-876e-11d8d6976366` | ✓ | ✓ | 自定义 128 位 |
   | `0x1800` / `0x1801` / `0x180A` / `0x1812` / `0x3802` | — | — | 标准 GAP / GATT / 设备信息 / HID |

   **没有 `0x180D`，也没有 `0x2A37`** —— 标准心率服务不存在（关键，见 §4）。
   连上不需要系统配对确认：`onServicesDiscovered status=0` 直接过。
3. **Gadgetbridge 源码当字典** `[VERIFIED]`：华为实现规模约 300 KB Java，而且是嵌在它的 BLE 框架里的，
   抽不出来 ⇒ 只能读懂再重写。主要文件：`HuaweiSupportProvider`（~159 KB，总入口）、
   `HuaweiPacket`（~53 KB，帧）、`HiChainPakeUtils`（~36 KB，认证）、`HuaweiCrypto`（~13.5 KB，密钥派生）。
4. **没有 logcat 怎么办**：App 里留一套 `Probe` 探针（`hwauth` / `hwinit` / `hwsync` / `hwlive` …），
   只走 App 日志页 + 回调，**不发网络**；关键实验的数据从日志页 / 探针读。
   （密钥类字段一律不打印，见 [`SECURITY.md`](../SECURITY.md)。）

## 2. 协议层拆解

### 2.1 帧 `[VERIFIED]`

```
magic 0x5A | length | serviceId | commandId | TLV payload ... | CRC16
```

- 载荷是 **TLV**（tag / type / length / value），支持嵌套与数组。
- 加密：`AES-ECB` / `AES-CBC` / `AES-GCM`，搭配 `HKDF` 派生、`HMAC` 校验（按协商结果选）。
- 分片：按第一个包 `LinkParams` 给的 `sliceSize` 切；写用 `WRITE_TYPE_NO_RESPONSE`，片间约 22 ms，
  失败回队头重试。
- 收包：累积 → 多包消费；CRC16 校验。

### 2.2 认证（HiChain）

顺序（Band 10 走 HiChain3）`[VERIFIED]`：

1. **LinkParams**（手环主动发的第一个包）：protocolVersion / sliceSize / mtu / nonce / authVersion /
   deviceSupportType / authAlgo / bondState / encryptMethod。
2. **SecurityNegotiation**：上报 authMode、androidId 等，手环回 `authType` 决定后续分支。
3. 按 `authType` 分派：
   - **HiChain3**（Band 10：`authType=0x5`；重连 `op=2`）
   - HiChainLite（`createSecretKey → PIN → Auth → BondParams → Bond`）`[IMPLEMENTED]`
   - Normal（密钥从 secretKey 派生）`[IMPLEMENTED]`
4. **首次绑定**：BIND + PIN（手环屏上显示数字，手机上输入）；Band 10 实测这次是**免 PIN** 直接绑定 `[VERIFIED]`。
5. 边界：手环「忘了我们」（`authType=0x5` 但本地记着已配对）⇒ 自动改走首次绑定 `[VERIFIED]`。

身份持久化（`AuthState`）：`authkey`、**伪造的 `device_mac`**、`android_id`、以及「已配对」标记 ——
这是能重连免 PIN 的关键 `[VERIFIED]`。

### 2.3 设备初始化（连上之后必须做的）`[VERIFIED]`

设备信息、对时、电量、**支持的服务与命令表**、同意协议、上报已连接、语言 / 日期 / 账号、
打开手环的自动测量（自动心率 / 血氧 / 科学睡眠 / 抬腕亮屏）。

> 命令表是**连上后探测**的，不同型号 / 固件不一样 —— 这也是判断「某个命令有没有」的唯一可靠办法。
> 其它型号 / 固件版本**未验证** `[INFERRED]`。

### 2.4 请求框架 `[IMPLEMENTED]`

`RequestRunner`：同步「发一个请求、等它的响应」；也支持「一次请求之后手环连推多包」的**流**
（文件下载、逐拍心率都是这种）。同一时间只允许一个 owner（同步 / 压力校准 / 心跳直播互斥）。

## 3. 各服务逆向结果

| 服务 | 干什么 | 我们的实现 | 证据 |
|---|---|---|---|
| `0xFE86` 主通道 | 所有私有命令的载体 | 写 `0xFE01` / 通知 `0xFE02` | `[VERIFIED]` |
| `0x07` | **分钟数据**：心率 / 血氧 / 步数 / 静息心率 | `StepSync`（先问页数，再逐页拉） | `[VERIFIED]` |
| `0x2C` | **文件下载**（新同步） | `FileDownloader` + 序列数据解析 → TruSleep | `[VERIFIED]` |
| `0x19` | **RRI / 压力**：逐拍心跳间期 + 压力校准 | `StressSync`（原始用途）+ `HrLiveSession`（心率，见 §4） | `[VERIFIED]` |
| `0x34 / 0x01` | **P2P 字典同步**（情绪等） | `DictSync`（手环来通知 or 每 10 分钟拉） | `[VERIFIED]`（情绪） |
| 天气相关 | 写天气进手环 | `WeatherSync`：单位 / 支持 / 日月 / 当前 / 24 小时 + 7 天预报 | `[IMPLEMENTED]` |
| 音乐相关 | 手环音乐页 ↔ 手机播放器 | `MusicControl`：回「在放什么」，收按键 | `[IMPLEMENTED]` |

关键细节：

- **分钟数据是「记录粒度」不是「同步粒度」** `[VERIFIED]`：`FitnessData` 的偏移单位就是 60 秒，
  请求里没有间隔参数。拉得再勤，拿到的也永远是「最近那一分钟的那一格」。
- **TruSleep** `[VERIFIED]`：文件下载（服务 `0x2C`）→ 序列数据解析 → 每分钟阶段
  （深睡 / 浅睡 / 快速眼动 / 清醒）+ 睡眠分 + 睡时心率血氧呼吸；阶段码 `1 浅 / 2 REM / 3 深 / 4 清醒`。
- **压力** `[VERIFIED]`：60 秒 RRI 校准 → HRV 特征 → 分数 → 打开自动压力 → 定期下载压力文件。
- **情绪** `[VERIFIED]`（数据）/ `[UNSUPPORTED]`（手环自带情绪页）：拉回来的是原始值
  （愉悦度 / 激活度 / 状态码）；手环自带的情绪页要华为运动健康 + 华为云算法（`/healthAlgorithm/v1`），
  模拟不了 —— 传功能清单、换 PhoneInfo 版本等实验都试过，页面仍显示「App 版本过低」。

## 4. 关键发现：`0x19` 的逐拍心率（RRI）

### 4.1 先被排除的几条路

| 路 | 结论 | 证据 |
|---|---|---|
| 华为 Health Kit | ❌ | 需要开发者实名资质，当时申请不下来 |
| 标准 BLE 心率 `0x180D` / `0x2A37` | ❌ `[UNSUPPORTED]` | GATT 枚举实测不存在 |
| 命令 `0x07/0x1C`（"实时心率开关"） | ❌ `[UNSUPPORTED]` | Gadgetbridge 里无实现；手环命令表里也没有；`0x1C` 实际是**自动测量模式**开关，不推数据 |
| 运动模式（`0x17`）里的实时心率 | ❌ `[INFERRED]` | 代码侧没有运动中实时心率；只有事后逐样本，且 App 侧没有「开始运动」的命令 |
| `0xFE02` 主动推送里的自发心率 | ❌ `[VERIFIED]`（清点过） | Gadgetbridge 21 个 handler + 本机实际包清单逐个清点：没有 |
| 心率广播 | ⚠️ 兜底 | 只能手环上手动开；开了是**另一个 BLE 身份**、才会出现 `0x180D` |

### 4.2 真正那条：`0x19/0x01` → `0x19/0x05` `[VERIFIED]`

```
开流：  0x19 / 0x01  { 01:03 }        → 回执 7f04 000186a0（成功）
推送：  0x19 / 0x05  每个样本：rri(ms) + sqi（信号质量）
关流：  0x19 / 0x01  { 01:04 }
心率：  HR = 60000 / rri
```

**它一直被当成「压力校准」用**：Gadgetbridge 的 `HuaweiStressCalibration` 开 60 秒、算完压力分就关，
从头到尾没往「这是心跳」上想。我们自己的 `StressSync` 也是这么用的 —— 第一次翻到它时，
它是躺在压力代码里的。任何「开了流又收到一串数」的命令，都值得问一句「这串数到底是什么」。

### 4.3 真机数据（手环桥 0.1.1 / 0.1.2，2026-11-23 凌晨）

**先说口径**：手环推上来的每个样本 = `rri` + `sqi`。其中 `sqi=0`（手环自己标为不可信）或
`rri` 超出 `250–3000 ms`（漏拍 / 断拍）的样本会被**丢掉、不参与心率计算**。
所以「样本总数 = 有效拍 + 被丢弃样本」，**丢的是无效样本，不是漏记的心跳**。

| 指标 | 60 秒档（0.1.1） | 180 秒档（0.1.1） | 180 秒档（0.1.8） |
|---|---|---|---|
| 用时 | 60.0 s | 180.0 s | 180.0 s |
| 有效拍 | 87 | 279 | 264 |
| 丢弃样本 | 109 | 337 | 352（全部 `sqi=0`） |
| **样本总数**（有效 + 丢弃） | **196** | **616** | **616** |
| 收到包数（`packets`） | 没记 | 没记 | **44** |
| 每包样本数 | — | — | **约 14**（约 4 秒一包） |
| 心率 | 100 bpm（整段均 95） | 95 bpm（整段均 96） | 76 bpm（整段均 95） |
| 首包延迟 | 5194 ms | 5440 ms | 约 5.2 s |
| lag 估计（结束时） | 0 ms | 0 ms | **5900 ms** |

关于 `lag`（措辞要准确）：

- `lag` = 包到达时刻 − 按 RRI 累加出来的最后一拍时刻。
- 0.1.1 的两次实测结束时都是 **≈0 ms**，但当时没记包数。
- 0.1.8 的第三次记了：**44 个包、每包约 14 个样本、约 4 秒一包**，结束时 lag **5900 ms**。
  ⇒ 手环是**成批推送**的，延迟是**秒级** `[VERIFIED]`。这条流能给「一拍一个值」，但是「几秒内到」，
  不是「每一拍当场就到」。前两次为什么是 0 没查清（推测是结束那一刻刚好有一包到达）`[INFERRED]`，不能拿来证明逐拍实时。
- 首包约 5 秒到 `[VERIFIED]`（三次都是 5.2–5.4 秒）。
- 能连开 3 分钟（Gadgetbridge 自己只敢 60 秒）`[VERIFIED]`。
- 丢弃样本约占 55%：`sqi=0` 与「间期离谱」两类分开计数（`droppedSqi0` / `droppedRange`）。
  快照里还有 `packets`；App 卡片把「有效 / 丢弃 / 样本合计 / 包数」都显示出来。

### 4.4 数据处理

- 脏拍过滤：`sqi=0`、`rri < 250 ms`、`rri > 3000 ms` 丢掉（分两类计数）。
- 三个口径：当前（10 秒滑动窗）/ 整段平均 / 只看最后一拍。
- 纯逻辑抽在 `proto/hr/RriBeats`（离线自测覆盖：间期→心率、时间累加、窗口收缩、脏拍过滤、丢弃分类）。
- 逐拍回放在 `proto/hr/BeatReplay` 里：一包 RRI → 每一拍的相对时刻（第 i 拍用第 i 个间期）、
  每拍心率、以及 `capNewest`（只留最近几拍，回放上限 4 秒，避免成批推送下越排越后）。
  App 的 `PulseView` 只负责按这张表闪；卡片文案写明**「不是『此刻这一拍』」**。

## 5. 逆向出来的边界（坑都在这儿）

1. **手环同一时间只跟一个 App 说话** `[VERIFIED]`。要跟自己的 App 连，必须先在「华为运动健康」里解绑
   （或手环恢复出厂）。
2. 解绑后手环屏幕会停在**出厂引导页** `[VERIFIED]`；App 要补齐初始化步骤让它退出。
   引导页待太久手环会「忘掉」绑定，App 会自动重新绑定（免 PIN）`[VERIFIED]`。
3. **没有 adb / logcat**（HarmonyOS 只有 HDC）`[VERIFIED]` ⇒ 只能靠 App 内探针和日志页排错。
4. 命令表按型号 / 固件不同 `[INFERRED]` ⇒ 任何「某个命令有没有」的结论都必须连上后探测，别照抄。
5. 压力要先**校准一次**（静坐 1 分钟），否则没有自动压力数据 `[VERIFIED]`。
6. HRV（`500044`）/ 皮肤温度（`400012`）在 Band 10 上拿不到 `[VERIFIED]`（字典类请求第一页就超时）。
7. 数据保留期：分钟数据 10 天、科学睡眠 60 晚、压力 / 情绪 30 天 `[IMPLEMENTED]`；更早的要手环里还有才能重拉。
8. 协议是逆向来的：**华为更新固件可能失效** `[INFERRED]`。`HuaweiPacket.parse` 目前只保留了 5 个 service
   的响应映射，其余 20 多个（Workout / OTA / 表盘 / 文件传输 / 联系人…）走「只解密不解析」的默认分支。
9. 其它型号没测 `[INFERRED]`：`HiChainPakeUtils`（PAKE / STS，Honor Watch 5 用）已经搬进来，
   但 `AuthFlow` 还没接上调度。

## 6. 可复用的方法（给下一个做逆向的人）

1. **先枚举 GATT，别先读别人的 App**。知道「哪个服务有写、哪个有通知」，比读十篇博客有用。
2. **认证走官方那条路是最稳的**：HiChain 看起来吓人，但 Gadgetbridge 已经把状态机写出来了，
   照着重写一遍比自创协议可靠得多。
3. **分清「记录粒度」和「同步粒度」**，不然会一直觉得自己拉得不够勤。
4. **注意「被当成别的功能用」的服务**：`0x19` 在压力校准里躺了很久。
5. **没有 logcat 就把探针做进 App**，让它把关键字段显示出来，别指望手环自己给你日志。
6. **别用「某个 App 能读」当可用的证据**：同一个 App 在 HarmonyOS 上原生网络被静默拦掉这件事踩过一次
   （那条中转路直接废掉，最后转成自己直连）。

## 7. 代码结构（这个仓库的两层）

```
bandcore/                   可复用的协议库（无 UI、无 app 依赖；只有 BLE 传输层需要 Context）
  transport/                HuaweiLink（扫描/连接/写队列/重连）、PacketAssembler、RequestRunner
  proto/                    帧、TLV、加密、CRC、时间换算、各 service 报文；hr/RriBeats 纯换算、hr/BeatReplay 回放排程
  auth/                     AuthFlow（状态机）、AuthState（本机身份）、HiChainPakeUtils（PAKE 移植）
  data/                     DeviceInit、各种 Sync、Store、HrLiveSession、Probe
app/                        参考实现 App（UI + 前台服务 + 手机上的 MCP 服务）
  MainActivity              操作屏（连接/心跳直播/校准/MCP 配置/资料/日志）
  HealthActivity + Charts   健康页（横幅/六块/折线/分期条/时间线/柱状，纯 Canvas）
  HealthData                健康页的数据快照（只读本机存档）
  McpServer + BandTools     手机上的 MCP（7 个工具，HTTP + Bearer 口令）
  BandService               前台服务（连接 + MCP 保活）
```

## 8. 证据坐标

- GATT / 协议实测记录：[`docs/lab/hrlive-0x19.md`](lab/hrlive-0x19.md)（`0x19` 的完整实验笔记）
- 探针实现：`bandcore/.../Probe.java`（`hwauth` / `hwinit` / `hwsync` / `hwlive`）
- 逐拍心率：`bandcore/data/HrLiveSession.java` + `bandcore/proto/hr/RriBeats.java`
  逐拍回放：`bandcore/proto/hr/BeatReplay.java` + `app/PulseView.java`
- 压力校准（`0x19` 的原始用法）：`bandcore/data/StressSync.java`
- 分钟数据 / TruSleep / 情绪 / 天气 / 音乐：`bandcore/data/*Sync.java`、`bandcore/proto/packets/`
- 认证：`bandcore/auth/{AuthFlow,HiChainPakeUtils,AuthState}.java`、`bandcore/proto/HuaweiCrypto.java`
- 帧：`bandcore/proto/{HuaweiPacket,HuaweiTLV,CheckSums,HuaweiUtil}.java`
- 离线自测：`bandcore/src/test/` + `selftest.sh`（298 项）
