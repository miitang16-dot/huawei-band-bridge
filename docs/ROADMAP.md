# 路线图 · Roadmap

> 状态：**0.1.1 测试版**（2026-11-23）。下面「已完成」指代码已经写完并编译通过，
> 其中协议核心在 Band 10 真机上跑通过；「未验证」的项会单独标出来。

## 为什么自己做（已经排除的路）

这些是前期试过或评估过的方案，写在这里省得别人再走一遍：

| 方案 | 结论 |
|---|---|
| 华为 Health Kit | 需要开发者实名资质，当时申请不下来 |
| 手环「心率广播」0x180D | 能用，但只给心率，没有血氧 / 压力 / 睡眠；而且设计上不想要这条路线 |
| 现成的 Gadgetbridge | 能读数据（约 1 分钟一格的历史同步），但：① 界面太杂；② 要再装一个陌生 App；③ 更致命的是**测试机（鸿蒙 7）把 Gadgetbridge 的原生网络请求静默拦掉了**（WebView 的 fetch 能通，`HttpURLConnection` 不行），所以拿它当中转这条路走不通 |
| 华为运动健康云端接口 | 要华为账号、数据经过华为云、多为每日汇总 |
| 每天手动整理数据 | 起点就是这样，正是想消灭的 |

最后选了：**自己实现华为私有协议**，App 直连手环，数据留本机，再开一个 MCP 服务给 AI 查。
Gadgetbridge 的源码是这条路的起点（AGPL-3.0，见 `NOTICE.md`），协议层大量移植自它。

## 已完成

### 协议层 `bandcore/`

- [x] BLE 传输：扫描（按名字 / FE86 广播认设备）→ 连接 → 发现服务 → 订阅 `FE02` →
      写队列（`WRITE_TYPE_NO_RESPONSE`，片间 22ms，失败回队头重试）→ 掉线重连 / 放弃后 5 分钟再试
- [x] 帧编解码：magic `0x5A` + 长度 + serviceId + commandId + TLV；AES-ECB / CBC / GCM，
      HKDF、HMAC；按 LinkParams 给的 sliceSize 分片；收包累积与多包消费
- [x] CRC16、时区换算、字符串工具
- [x] 认证状态机（`AuthFlow`）：
      - LinkParams（第一个包，拿 protocolVersion / sliceSize / mtu / nonce / authVersion /
        deviceSupportType / authAlgo / bondState / encryptMethod）
      - SecurityNegotiation（authMode / androidId 等 → authType 分派）
      - **HiChain**（Band 10 走这条：authType=0x5 → HiChain3，重连 op=2；首次 BIND + PIN 也实现了）
      - **HiChainLite**（createSecretKey → PIN → Auth → BondParams → Bond）
      - **Normal**（密钥从 secretKey 派生）
      - 手环「忘了我们」时（authType=0x5 但本地记着已配对）自动改走首次绑定
- [x] 身份持久化（`AuthState`）：`authkey` / 伪造的 `device_mac` / `android_id`，
      以及「已配对」标记；App 里有「忘记配对」
- [x] 设备初始化（`DeviceInit`）：设备信息、对时、电量、支持的服务与命令表、同意协议、
      上报已连接、语言 / 日期 / 账号、打开手环的自动测量（自动心率 / 血氧 / 科学睡眠 / 抬腕亮屏）
- [x] 请求框架（`RequestRunner`）：同步「发一个请求、等它的响应」，支持一次请求后手环连推多包的「流」
- [x] 数据同步：
      - 分钟数据（`StepSync`）：心率 / 血氧 / 步数 / 静息心率，先问页数再逐页拉
      - 科学睡眠（`TruSleepSync` + `FileDownloader`）：走「新同步」的服务 `0x2C` 文件下载 +
        序列数据解析 → 深睡 / 浅睡 / 快速眼动 / 清醒（每分钟阶段）+ 睡眠分 + 睡时心率血氧呼吸
      - 压力（`StressSync`）：60 秒 RRI 校准 → HRV 特征 → 分数 → 打开自动压力 → 定期下载压力文件
      - **心跳直播（`HrLiveSession` + `proto/hr/RriBeats`）**：开 `0x19/0x01 {01:03}` → 收 `0x19/0x05`
        逐拍 RRI → `HR = 60000 / rri`（当前 / 整段平均 / 脏拍过滤 / 窗口收缩）。
        自包含：不依赖 Android，别的壳（Capacitor 插件等）可以直接收。
        真机上**开流成功过**（成功码 `7f04 0001 86a0`，60 秒收到 85 拍）；**推送延迟还没量**、
        **能不能开超过 60 秒未验证**
      - 情绪（`DictSync`）：P2P 字典同步（`0x34/0x01`），手环来通知或每 10 分钟拉
      - 天气（`WeatherSync`）：按 GB 的顺序发单位 / 支持 / 日月 / 当前 / 24 小时 + 7 天预报
      - 音乐（`MusicControl`）：回手环「在放什么」，收手环按键 → 手机播放器 / 音量
- [x] 本机存档：`hw_samples.json`（10 天）、`hw_trusleep.json`（60 晚）、
      `hw_stress.json` / `hw_dict_<类别>.json`（30 天）
- [x] `Probe`：协议诊断日志（`hwauth` / `hwinit` / `hwsync` …），只走 logcat 和 App 回调，**不发网络**

### App `app/`

- [x] 常驻前台服务（`BandService`）：屏幕关掉也保持连接 + MCP 在跑
- [x] 一屏 UI（`MainActivity`，纯系统 View，没有第三方库）：连接 / 心跳直播 / 压力校准 /
      MCP 地址口令（复制配置）/ 个人资料 / 日志查看
- [x] 「心跳直播」卡片（0.1.1）：BPM、整段平均、已收拍数、**最近一拍距今 ms**、**lag 估计**、
      首包延迟、起止时间；1 分钟档 + 3 分钟档（后标「未验证」）；测完保留最后一次结果
- [x] 手机上的 MCP 服务（`McpServer`）：Streamable HTTP，`POST /mcp`，JSON-RPC 2.0
      （initialize / ping / tools/list / tools/call），Bearer 口令或 `?token=`，无第三方库
- [x] 7 个 MCP 工具：`band_status` / `band_now` / `band_day` / `band_sleep` / `band_stress` / `band_emotion` / `band_hr_live`
      —— 缺数据就返回空，不补数、不编数，答案带「记录时间 + 粒度」；
      `band_hr_live` 的返回字段是固定契约（`ok / running / bpm / bpm_avg / beats / window_ms / lag_ms /
      last_beat_ms_ago / started_at / device / unverified`），没在播就 `hr_live_not_running`
- [x] 天气（`WeatherFetch`）：Open-Meteo，粗位置（0.1°）
- [x] 音乐（`Media` / `MediaListener`）：通知使用权 → 当前播放 → 手环音乐页
- [x] 个人资料（身高 / 体重 / 生日 / 性别）发给手环，只存本机

### 工程 / 文档

- [x] 两个 Gradle 模块（`bandcore` 库 + `app`），AGP 8.13.0 / Gradle 8.14.3 / JDK 21，编译通过，
      干净重建产物可复现（见 `RELEASE-0.1.md`）
- [x] **纯协议层离线自测**：`selftest.ps1` / `selftest.sh`，零依赖（只用 JDK 的 `javac` + `java`，
      不需要手环 / Android SDK / 网络），235 项检查覆盖 TLV 编解码与边界、CRC16（对照独立实现）、
      帧头 / 长度 / 半包 / 分片重组、`PacketAssembler` 喂包、时间换算、`RriBeats` 的间期→心率与窗口。
      见 `docs/TESTING.md`（也写清了蓝牙链路、认证、加密 KAT 这些都还测不到）
- [x] GitHub Actions：`.github/workflows/build.yml`，push / PR 时跑自测 + `:app:assembleDebug`，
      APK 作为 artifact 上传 —— **未在 GitHub 上实跑验证**（本机无法验证 Actions，第一次跑可能还要小修）
- [x] **数据导出**：App 里「导出数据」→ `Download/HuaweiBandBridge/` 下一个原样打包的 JSON +
      每个存档一个 CSV（按日期摊平；Android 10+ 用 MediaStore，8/9 写 App 外部目录，都不需要存储权限）
      —— 代码编译通过，**还没上真机验证写盘 / CSV 打开**（未验证）
- [x] `LICENSE`（AGPL-3.0）、`NOTICE.md`（Gadgetbridge 来源与改动）、`README.md`、
      `RELEASE-0.1.md`、本文件

## 待做

### P0 · 真机跑完 0.1（最优先，没有真机就没有结论）

- [ ] **心跳直播真机复测（0.1.1 最想知道的）**：戴紧 → 点「开始（1 分钟）」→ 静坐 30 秒 →
      看日志里的 `hwlive`：
      - `open-ack | 0x186a0 (success)` 有没有出现；
      - `first-pkt | after=…ms` 首包多久来；
      - `pkt | … lag=…ms` 连续出现时 lag 是多少（≤1 秒 = 真的近实时；≈60 秒 = 只能「测一分钟再回放」）；
      - 再点「试 3 分钟」，看 `end | reason=` 是 `timeup` 还是 `no-data`（后者 = 手环不肯开那么久）。
- [ ] 在 Band 10 + 鸿蒙手机上把 `dist/` 那个包完整走一遍：首次配对（PIN 分支）→ 分钟同步 →
      科学睡眠 → 压力校准 → 心跳直播 → 情绪 → 天气 → 音乐 → 掉线重连 → 杀进程后重开
- [ ] 用真实 AI 客户端（Claude Desktop / Cursor / 自写脚本）接上 MCP，7 个工具逐条调一遍
- [ ] 把真机 `Probe` 日志里失败的分支记下来，按原因修（参考 `README` 的「已知的坑」）
- [ ] 截图（App 一屏 + MCP 配置）放进 README

### P1 · 功能

- [x] **更细的心率（0.1.1 做了逐拍）**：`0x19`（HrRriTest）是手环的逐拍 RRI 流，开一次测量能收到
      一拍一拍的间隔 → `HR = 60000 / rri`。已实现成 `HrLiveSession`（App 卡片 + MCP `band_hr_live`）。
      **还差真机量三件事**：推送延迟（`lag`）、能不能开超过 60 秒、测量时手环屏幕/耗电表现。
      另外两条路仍然不推荐：① 运动模式 `0x17` 运动中不推实时心率（只有事后下载的逐样本数据）；
      ② 提高 `0x07` 拉取频率没用 —— 手环就是每分钟记一格。
- [ ] 心跳直播的后续改进（等真机数据再定）：如果 `lag` 大，就做成「按住测一分钟 → 回放这一分钟」；
      如果 `lag` 小，就做一个真的「心跳在跳」的实时小窗。
- [ ] 手机 → 手环通知（`Notifications` 报文类已经搬进来了，但没用起来）
- [ ] 闹钟 / 找手机 / 久坐提醒
- [ ] 表盘：不做（风险高，要传文件 + 改固件资源）
- [ ] OTA 固件升级：不做（弄坏手环的风险不值得）
- [ ] 更多型号（Band 9 / 11、Watch 系列）：命令表是连上后探测的，理论上能适配，
      差异要逐型号摸。`HiChainPakeUtils`（PAKE / STS，Honor Watch 5 用）已经搬进来，
      但 `AuthFlow` 还没接上调度
- [ ] MCP：SSE 流（现在只回 JSON）、HTTPS、更多工具（周趋势 / 同步触发 / 数据导出工具）
- 数据导出：已做（App 里的「导出数据」，见上面「已完成」）；MCP 侧暂时没有 export 工具

### P2 · 工程

- [x] 纯协议层单元测试（TLV、帧编解码、CRC、时间换算）—— 零依赖 self-test，不需要硬件，已进 CI
- [x] CI：GitHub Actions 编 APK 当 artifact + 跑自测（workflow 未在 GitHub 上实跑验证）
- [ ] 发布签名 + 版本号流程（现在只有 debug 签名）
- [ ] UI 中英文字符串抽到资源、加图标、支持横屏 / 深色主题检查
- [ ] 各厂商电池优化白名单的引导文案（国产 ROM 后台杀进程）
- [ ] `AuthFlow` 接 PAKE / STS（等有 Honor 设备的贡献者）

## 已知限制（诚实清单）

1. **心率有两种粒度，别混。**
   - **记录粒度是 1 分钟**：手环按分钟存心率 / 血氧 / 步数，App 每分钟同步一次。
     拉得更勤也只是「最近一分钟那一格」，**改不了这个**（包格式里就是「第几分钟」的偏移，没有秒这一级）。
   - **逐拍是另一条路（0.1.1 起）**：`0x19`（HrRriTest）开一次测量，手环推逐拍 RRI，
     `HR = 60000 / rri`。这是真的比分钟细。**但**：
     - **推送延迟还没在真机上量过**（`lag_ms` 就是量它的），所以「此刻正在跳多少」还不能算已验证；
     - 一次测量是有限窗口（目前给 60 秒 / 3 分钟档），**能不能一直开着未验证**；
     - 测量期间不拉分钟数据（跟压力校准一样）。
   - `0x07/0x1c`（Gadgetbridge 叫 realtime heart rate）**不在 Band 10 的命令表里**，而且它本身只是
     「自动测心率模式」的开关，不推数据 —— 这条别再试了。
2. **手环自带的情绪页打不开**：显示「手机运动健康 App 版本过低」。需要华为运动健康 +
   华为云算法（`/healthAlgorithm/v1`），本项目模拟不了。App / MCP 里的情绪数据是直接拉回来的
   原始值（愉悦度 / 激活度 / 状态码），状态码 → 文字是按数据推的，不是华为的定义。
3. **只支持 Android**（8.0+）。没有 iOS 版，也不打算做（iOS 的 BLE 后台限制 + 同类问题）。
4. **鸿蒙兼容层的网络限制**：鸿蒙 7 上原生 `HttpURLConnection` 会被静默拦掉（WebView 的能通），
   所以把数据从别的 App（比如 Gadgetbridge）中转出去这条路走不通 —— 这也是本项目存在的直接原因。
   本项目自己的网络请求（只有天气）在鸿蒙上是否能通，**还没在真机上确认**；
   如果天气发不出去，App 其它功能不受影响。
5. **必须跟华为运动健康解绑**：手环同一时间只跟一个 App 说话。解绑后手环屏幕会停在出厂引导页
   （App 会补齐初始化步骤让它退出）；手环在引导页待太久会「忘掉」绑定，App 会自动重新绑定。
6. **没有 adb / logcat**（鸿蒙 7 手机只有 HDC，要装 ~3GB SDK）。诊断只能靠 App 里的日志和
   协议探针（`Probe`）。这限制了排错方式。
7. **压力要一次校准**：手环的自动压力需要先有一次压力测试结果（App 里静坐 1 分钟）。
   没校准就没有自动压力数据。
8. **HRV（500044）/ 皮肤温度（400012）** 在 Band 10 上拿不到（字典类请求第一页就超时）。
9. **数据有保留期**：分钟数据 10 天、科学睡眠 60 晚、压力 / 情绪 30 天。更早的只能从手环重新拉。
10. **协议是逆向来的**：华为更新固件可能失效；`HuaweiPacket.parse` 只保留了 5 个 service 的响应映射，
    其余 20 多个（Workout / OTA / Watchface / 文件传输 / 联系人…）走「只解密不解析」的默认分支。
11. **debug 签名**：0.1 用的是 debug key，不是发布签名；不同签名之间不能覆盖安装。
12. **这不是医疗设备**，数据不能用于诊断。

## 不做 / 范围外

- 云端方案（华为账号、Health Kit、任何把健康数据传到服务器的设计）
- 绕过手环的安全机制、提取 / 分发密钥 —— 这里做的是「用官方 App 同样的身份跟手环说话」，
  认证用的密钥是在配对时由手环和我们协商出来的，每台设备一份
- 表盘、OTA（风险高，收益低）
- iOS
