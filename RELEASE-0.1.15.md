# 手环桥 0.1.15 · 测试包（睡眠期间心率 / 血氧）

AI 问「昨晚睡着时心率最低是多少」时，手环的科学睡眠摘要有的晚上不给这一项，AI 只能说「没法核对」。这一版用**入睡 → 醒来这段的每分钟数据**自己算出来。仍然是测试版，debug 签名。

| | |
|---|---|
| 版本 | `0.1.15`（versionCode 16） |
| 包名 | `io.github.bandbridge` |
| 文件 | [`dist/huawei-band-bridge-0.1.15-debug.apk`](dist/huawei-band-bridge-0.1.15-debug.apk)（下面附件里也有） |
| 大小 | 316665 字节 |
| MD5 | `085CF1280981CD90F6FB322EF4AF7E51` |
| SHA-256 | `2FD62A648AB3B99F51EB7C1EAAC5FAF5995BA88F9412F1FF83C7F74ECB89701F` |
| 签名 | Android **debug** 签名（证书与 0.1.0~0.1.14 相同，可以覆盖安装） |
| 系统要求 | Android 8.0+（minSdk 26），`targetSdk 35`，`compileSdk 36` |

## 改了什么

MCP 工具 `band_sleep` 多返回三项：

- `sleepHrMinute`：睡着这段的心率 `{min, minAt, avg, max, maxAt, n}`，也就是最低值和几点、平均、最高值和几点、有几分钟有数；
- `sleepSpoMinute`：睡着这段的血氧 `{min, minAt, avg, n}`；
- `hrCurve`：整晚心率曲线，每 15 分钟一格的平均值。

原来睡眠摘要里的 `sleepHr` / `sleepSpo` 照旧保留，两份都给，工具说明里写清了来源：

- 手环屏上的「最低心率」可能是瞬时值，比每分钟的低 1~3 下，属于正常；
- 每分钟数据只留 10 天，更早的晚上这三项是 `null`。

另外，关于页的版本号对上了（之前停在 0.1.13）。

## 证据等级

- 算法用假数据测过（指定一分钟心率 42 ⇒ 最低 42 和对应时刻、平均、曲线格数都对），同一套逻辑也上了 Nexus 3.431。
- **手环桥这一版还没在真机上看过**，`selftest` 298/298 只覆盖协议层，测不到 app 层。
