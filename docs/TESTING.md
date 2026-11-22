# 测试 · Testing

> 这份文档说明：**哪些代码不需要手环就能验证、怎么跑、测不到什么**。
> 状态：`selftest` 在本机（Windows / PowerShell 5.1 + JDK 21）跑过，**168 项全过**；
> GitHub Actions 上的实跑见文末「CI」一节，**未在 GitHub 上实跑验证**。

## 为什么是「零依赖自测」而不是 JUnit

bandcore 的协议层（TLV、帧、CRC、分片、时间换算）是纯 Java 逻辑，本来适合单元测试。
但本仓库的离线构建缓存里没有 JUnit 的 artifact，而这次补工程化的前提是**不联网、不改构建链**：

- 不往 `bandcore/build.gradle` 里加 `testImplementation 'junit:junit'` —— 那会要求所有构建环境（包括贡献者的离线环境）能拉到依赖；
- 不引入 Robolectric —— 它要模拟整个 Android 框架，是另一个量级的依赖。

所以测试是**普通 Java `main()` + 断言**：只用 JDK 21 的 `javac`/`java`，零第三方库、零网络。
`bandcore/src/test/java` 下放自测类，`tools/selftest/` 放跑测试的工具（编译用的 Android/JSON 替身、入口类）。
`app`/`bandcore` 的 Gradle 构建**完全不受影响**（`assembleDebug` 不编译 `src/test`），测试由脚本单独跑。

## 怎么跑

需要 JDK 17+（项目用 JDK 21）。不需要 Android SDK、不需要 Gradle、不需要手环。

**Windows（PowerShell 5.1 或 pwsh）**

```powershell
$env:JAVA_HOME='D:\dev\jdk-21.0.13.0.2+1'   # 换成你的 JDK；已在 PATH 里也可以不设
.\selftest.ps1
```

**Linux / macOS / Git Bash**

```bash
export JAVA_HOME=/path/to/jdk-21
./selftest.sh
```

输出是一行一个检查项，最后一行是汇总；**退出码 0 = 全过**，非 0 = 有失败或编译失败：

```
== TLV round-trip (all put() overloads)
  ok   parsed equals original
  ...
------------------------------------------------------------
PASS  168/168 checks
------------------------------------------------------------
```

脚本做的事（`selftest.ps1` / `selftest.sh` 内容一致）：

1. 编译 `tools/selftest/stubs/` 下的最小替身（`android.util.Log`、`android.text.TextUtils`、`org.json.*`）；
2. 编译 bandcore 里**不依赖 Android 设备/API** 的源码：整个 `proto/` 包、`Probe`、`transport/PacketAssembler`；
3. 编译 `bandcore/src/test/java` 下的自测类和 `tools/selftest/SelfTestRunner.java`；
4. 跑 `SelfTestRunner`。

产物落在 `build/selftest/`（已被 `.gitignore` 忽略）。

## 覆盖了什么

| 被测对象 | 检查内容 |
|---|---|
| `HuaweiTLV` + `VarInt`（包级私有） | 所有 `put()` 重载的序列化→解析往返（byte/bool/short/int/long/double/String/嵌套 TLV）、线上格式（tag + VarInt 长度 + value）、空 TLV、空 buffer、`parse(null)`、尾部多余 `0x00` 填充、长度前缀大于剩余字节、截断元素、`put(tag, null)` 是 no-op、`remove()` 只移除最后一个同 tag 元素、`getBytes/getByte/getShort/getInteger/getAsInteger/getBoolean` 的缺省值语义、`getObjects` / `getAllContainerObjects`（高位置 1 的 tag 视为容器）、VarInt 各长度档位（0/127/128/16383/16384/…）与编解码往返 |
| `CheckSums`（CRC16） | 公开校验值：`"123456789"` 在 init `0xFFFF` 下 = `0x29B1`（CRC-16/CCITT-FALSE）、init `0x0000` 下 = `0x31C3`（CRC-16/XMODEM）；与**独立写的逐位实现**（多项式 `0x1021`）在 500 组确定性伪随机数据上交叉验证；增量性（分块算 = 一次算，解析分片时依赖这一点）；结果始终 16 位 |
| `HuaweiPacket`（帧层） | 手搓帧（不是只拿 `serialize()` 的产物反喂自己）：magic `0x5A` + 长度 + 分片标记 + serviceId/commandId + TLV + CRC 的解析；magic 错、CRC 错、载荷翻一位、长度为负被拒；半包累积（1 字节 → 2 字节 → 剩余）；`getLeft()` 报告后续包长度；raw payload 服务（`0x0A/0x05` 等）不建 TLV；按 service/command 分派到 `DeviceConfig.BatteryLevel.Response` 等子类（未知服务落到普通 `HuaweiPacket`）；`serialize()` 的未分片帧与解析往返；**分片**：按 `sliceSize` 切帧、每帧 ≤ sliceSize、CRC 有效、slice 标记 1/2/3、flag 递增、逐帧喂回重组后 TLV 一致 |
| `PacketAssembler` | 整帧一次喂、逐字节喂、一次喂多帧、分片逐条喂 / 一次全喂 / 5 字节一段喂；没人认领的包走 `onAsync`；断线 `reset()` 丢掉半包、不把上一条链路的尾巴接过来；null / 空 feed 被忽略 |
| `HuaweiUtil` | `timeToByte`（`HH:mm` → `{时, 分}`，含非法输入返回 null）；`getTimeAndZoneId` 的 6+N 字节布局：秒级时间戳、时区小时字节、分钟字节、时区 ID（UTC / Asia/Shanghai / Asia/Kathmandu +5:45） |

合计 **168 项检查**。

### 两处「钉住现有行为」的地方（不是 bug 修复）

测试不只是证明「现在能跑」，也把两处**看起来像 bug 的现有行为**固定下来，并写了注释，
这样以后有人想改，会先看到测试失败、再决定改不改：

1. `HuaweiTLV.getAsInteger(int)`（无缺省值那个重载）对 **3 字节**的值会 `BufferUnderflowException`
   —— 它只处理 1/2 字节，其余直接用 `ByteBuffer.getInt()`。带缺省值的 `getAsInteger(tag, def)` 没这个问题。
   真机路径上目前没人用 3 字节 + 无缺省重载的组合，所以**这次不改生产代码**。
2. `HuaweiUtil.getTimeAndZoneId` 对**西半球非整点时区**（例如 UTC-3:30）会把分钟写成负数字节
   （`-12600 / 60 % 60 = -30`）。手环那侧是否认这个编码未验证；改了会影响发出去的字节，
   所以同样只钉住、不改。

## 已知测不到什么（诚实清单）

- **蓝牙链路本身**（`transport/HuaweiLink`：扫描、连接、MTU、写队列、22ms 片间间隔、掉线重连）——要真机 + 蓝牙栈。
- **认证**（`auth/AuthFlow`、HiChain / HiChainLite / PAKE）——要真机、要一台已经绑定过的手环、还要 `Context`/`SharedPreferences`。
- **数据层**（`BandSession`、`data/**`：`SampleStore`、`TruSleepStore`、`StressSync`…）——依赖 `Context`、文件系统、真实数据包。
- **加密**（`HuaweiCrypto`：AES-ECB/CBC/GCM、HKDF、HMAC）——没有做已知答案向量（KAT）测试，因为手头没有可对照的
  设备抓包；自测里走的是「关掉加密」的帧路径，只验证**帧结构**，不验证**密文正确性**。这是本项目测试最大的空洞，
  真机日志（`Probe`）是现在的替代手段。
- **报文级语义**（各 `Response.parseTlv()` 的字段含义）——只验证了分派到哪个类，没有逐字段验证；
  字段含义是靠真机数据反推的（见 `docs/ROADMAP.md`）。
- **App / MCP / 天气 / 音乐**——UI、HTTP 服务、Open-Meteo、通知使用权都不在自测范围。
- 自测**不代表协议正确**：它是回归网（防止重构把已知行为改坏），不是正确性证明。

## 替身（stubs）说明

`tools/selftest/stubs/` 里是三个只用于**离线编译**的最小替身，**不会打进 APK**：

| 替身 | 为什么 | 行为 |
|---|---|---|
| `android.util.Log` | `proto/` 里所有 `TAG + Log.d` 的日志调用 | 全部 no-op |
| `android.text.TextUtils` | `packets/Notifications.java` 用了 `TextUtils.isEmpty` | 与系统实现一致（null / 空串为 true） |
| `org.json.*` | `packets/DeviceConfig.java` 的 HiChain JSON、`HuaweiStressParser` | 功能可用的小实现（对象 / 数组 / 解析 / 输出）；**只有编译期与自测用的意义** |

真机上永远用 Android framework 里的真实现；自测里不碰 HiChain / 压力 JSON 路径，所以替身的边界不影响测试结论。

## 加一个自测怎么加

1. 在 `bandcore/src/test/java/io/github/bandbridge/core/…` 下新建 `XxxSelfTest`，写 `public static void run()`，用 `io.github.bandbridge.core.testing.T` 里的断言（`T.eq` / `T.bytes` / `T.thrown` / `T.group`）；
2. 在 `tools/selftest/SelfTestRunner.java` 的 `main` 里加一行调用；
3. `.\selftest.ps1`（或 `./selftest.sh`）跑，确认全过、退出码 0。

新增被测类时，如果它在 `proto/` 之外，还要在 `selftest.ps1` / `selftest.sh` 的「pure sources」列表里加上它 ——
只加**不依赖 `Context` / `BluetoothGatt` / `SharedPreferences`** 的类，否则编译不过（这是有意的边界）。

## CI

`.github/workflows/build.yml` 在 push / PR 时跑两条：

- **test**：JDK 21，跑 `./selftest.sh`（不需要 Android SDK）；
- **build**：JDK 21 + Android SDK，跑 `./gradlew :app:assembleDebug`，APK 作为 artifact 上传。

**未在 GitHub 上实跑验证**：本机没有 Actions 的运行环境（也不能替仓库开权限去触发），
workflow 的 YAML 和步骤是按 GitHub 官方 action 的用法写的，第一次真实运行可能还要小修（比如
`platforms;android-36` 的安装步骤）。

## 手动构建（和 CI 里的 build 一致）

```powershell
$env:JAVA_HOME='D:\dev\jdk-21.0.13.0.2+1'
$env:ANDROID_HOME='D:\dev\android-sdk'
$env:GRADLE_USER_HOME='E:\dev\.gradle'
.\gradlew.bat :app:assembleDebug --no-daemon
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。
