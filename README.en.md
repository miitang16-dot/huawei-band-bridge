# Huawei Band Bridge

**Let your AI read your Huawei band directly — no Huawei Health app, no Huawei ID, no cloud.**
*让 AI 直接读你的华为手环 —— 不用华为运动健康、不用华为账号、数据不上任何云。*（[中文](README.md)）

> 🧪 **0.1 beta.** The protocol core works on a real HUAWEI Band 10; the app and the MCP server are
> freshly written and still being tested on the device.

## Download / versions

| Version | File | Notes |
|---|---|---|
| `0.1.0` (2026-11-22) | [`dist/huawei-band-bridge-0.1-debug.apk`](dist/huawei-band-bridge-0.1-debug.apk) | first test build, debug signature |

- Install steps, the verified / not-yet-verified list, checksums (MD5 / SHA-256) and how it was built
  are in [`RELEASE-0.1.md`](RELEASE-0.1.md). Roadmap and known limits: [`docs/ROADMAP.md`](docs/ROADMAP.md).
- Debug signature: you cannot install it over a build with a different signature — uninstall first.
- Build it yourself (JDK 21 + Android SDK 36):

```powershell
.\gradlew.bat :app:assembleDebug
# output: app\build\outputs\apk\debug\app-debug.apk
```

## What it is

An Android app that talks to a Huawei band over Bluetooth using Huawei's own private protocol
(including the HiChain authentication, same as the official app). It keeps the data on the phone
and runs an **MCP server** on the phone, so MCP-capable AI clients (Claude, Cursor, any AI app…)
can read your current and past health data — and it can also push weather to the band and let the
band control the phone's music player.

## What you get (verified on a real Band 10)

| Data | Granularity | Notes |
|---|---|---|
| Heart rate / SpO2 / steps / resting HR | one point per minute | the app syncs once a minute (the band has no realtime HR push; this is the ceiling) |
| TruSleep | per-minute stage | deep / light / REM / awake + sleep score; matches the band's screen minute by minute |
| Stress | measured by the band | calibrate once in the app (sit still for 1 minute), then the band measures on its own, score 1–99 |
| Emotion | measured by the band | valence / arousal / 3 states (the band's own emotion page still needs Huawei Health, see below) |
| Weather → band | hourly | Open-Meteo, coarse location only |
| Band music page → phone player | realtime | play / pause / previous / next / volume |

## How it differs from other "Huawei health MCP" projects

Almost all Huawei health MCP servers on GitHub pull data **from Huawei's cloud** (official Health Kit,
or reverse-engineered Huawei Health cloud APIs). They need a Huawei ID, the band must stay connected
to Huawei Health, data goes through Huawei's servers, and it is mostly daily aggregates.

This project takes the other road: **the phone talks to the band directly.**

| | This project | Cloud-based |
|---|---|---|
| Huawei Health app required | ❌ (the band must be unpaired from it) | ✅ |
| Huawei ID required | ❌ | ✅ |
| Data goes through a cloud | ❌ phone only | ✅ Huawei cloud |
| Heart-rate granularity | 1 minute | mostly daily aggregates |
| Can write to the band (weather / music) | ✅ | ❌ |
| History from before the switch | ❌ | ✅ |
| The band's own emotion page | ❌ shows "app version too old" | ✅ |
| iPhone | ❌ Android only | ✅ |

Both approaches have trade-offs; pick whichever you need.

## Usage

1. **Unpair** the band from Huawei Health first (see the caveats below).
2. Install the APK, tap "连接" (Connect), grant "Nearby devices" and "Notifications". On the first
   connection the band may ask you to confirm.
3. (Optional) Tap "开始校准" (Calibrate) and sit still for one minute — afterwards the band measures
   stress on its own.
4. The "MCP 服务" card shows the address (`http://<phone-ip>:8848/mcp`) and the token; tap "复制配置"
   (Copy config) and paste it into your AI client:

```json
{
  "mcpServers": {
    "huawei-band": {
      "type": "http",
      "url": "http://192.168.1.23:8848/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

The AI and the phone must be on the same LAN (or joined through something like Tailscale). Clients
that cannot set headers can use `…/mcp?token=<token>` instead.

5. Want your own copy of the data? Tap "导出数据" (Export data): it writes one JSON with all local
   stores packed as-is plus one CSV per store (flattened by date + fields, opens straight in Excel)
   to `Download/HuaweiBandBridge/` on the phone. Local only — nothing is uploaded.

### MCP tools

| Tool | What it does |
|---|---|
| `band_status` | Connected or not, model, battery, time of the newest data, whether stress is calibrated |
| `band_now` | Latest heart rate / SpO2 / resting HR / today's steps / stress / emotion / last night's sleep, each with the time it was recorded |
| `band_day` | Heart rate / SpO2 / steps for a day or a time window, with stats and a per-interval series (long windows are coarsened; the answer says the granularity) |
| `band_sleep` | The night that ended on a given morning: fall asleep / wake time, sleep score, deep / light / REM / awake minutes, sleep HR and SpO2, stage timeline |
| `band_stress` | Stress scores the band measured on a day (1–99 + level) |
| `band_emotion` | Emotion records the band measured on a day (valence / arousal / 3 states) |

No record means null / empty — **values are never invented**.

## Architecture

Two Gradle modules: `bandcore/` is a reusable Huawei-band protocol library, `app/` is the app.

```
app/                        one-screen UI + foreground service + the MCP server on the phone
  BandService                 foreground service holding BandSession and the MCP server
  MainActivity                plain-View screen: connect / sync / calibration / MCP config / profile / log
  McpServer                   on-phone MCP (Streamable HTTP, POST /mcp, Bearer token)
  BandTools                   the 6 tools: read local stores, answers carry record time and granularity
  WeatherFetch                weather from Open-Meteo (coarse location only)
  DataExport                  export local stores → Download/HuaweiBandBridge (JSON + CSV)
  Media · MediaListener       read the current playback (notification access) for the band music page

bandcore/                   the Huawei-band protocol core (no UI, no network, no Huawei dependency)
  transport/HuaweiLink        scan → connect → discover → subscribe FE02 → write queue (22 ms slices) → reconnect
  transport/PacketAssembler   reassemble slices and dispatch (auth packets to AuthFlow, the rest as async)
  transport/RequestRunner     send one request and block for its response; supports streams (0x2C file download)
  proto/                      framing (magic 0x5A / TLV / AES-ECB·CBC·GCM + HKDF + HMAC), CRC16, per-service packets
  auth/AuthFlow               auth state machine: LinkParams → SecurityNegotiation →
                              HiChain (PIN+BIND / reconnect op=2) · HiChainLite · Normal
  auth/AuthState              the three persisted identities (authkey / spoofed device_mac / android_id)
  auth/HiChainPakeUtils       PAKE (Honor Watch 5 path) implementation, not wired into AuthFlow yet
  data/DeviceInit             post-auth init: device info, time sync, battery, command table, agreement,
                              report connected, language / date / account, enable the band's auto measurements
  data/StepSync …             syncers: minute data, TruSleep (0x2C file download + sequence parser),
                              stress (60 s RRI calibration + files), emotion (P2P dictionary sync), weather, music
  data/*Store                 local stores (JSON files in the app's private dir)
```

**Threading**: the BLE callback thread only feeds bytes into `PacketAssembler`; auth, init and the
sync loop run on one worker thread (`AuthFlow.run()` blocks on responses, so it must not share the
thread that feeds packets); the UI only reads state. Characteristic writes always go through
`HuaweiLink`'s internal queue, one slice at a time.

**Sync cadence** (once per worker loop after connecting): minute data every 1 minute; TruSleep every
30 minutes; stress files every 30 minutes; emotion every 10 minutes or immediately when the band
says it has new data; weather hourly or when the band asks. Phone info / weather / music questions
from the band are answered in that same loop.

**Data flow**: band → (private BLE protocol) → `bandcore` → local JSON stores → (MCP, port 8848 on
the phone) → AI client. There is no server in between.

## Privacy

- Health data (heart rate, SpO2, steps, resting HR, sleep, stress, emotion) **never leaves the phone**;
  nothing is uploaded.
- The only network use is weather: Open-Meteo is asked for current/forecast weather for the band.
  Only the location rounded to 0.1° (≈ 10 km) is sent — no identity, no API key.
- No Huawei ID, no Huawei Health (the band must rather be unpaired from it). No ads, no analytics,
  no third-party SDKs.
- The MCP server listens on port 8848 on the phone with a random Bearer token. **Do not expose it to
  the internet**; use it on your LAN (or through Tailscale-like networking).
- Uninstalling the app wipes everything: identities, token and data all live in the app's private
  storage and SharedPreferences.
- Files written by "Export data" also stay on the phone (Download directory or the app's external
  directory); the app never uploads them.

## Tests / development

The protocol layer (TLV, framing, CRC16, slicing, time conversion) has an **offline self-test** that
needs no band, no Android SDK and no network:

```bash
./selftest.sh      # Linux / macOS / Git Bash
```

```powershell
.\selftest.ps1     # Windows
```

It only uses JDK 17+ (the project uses JDK 21) with `javac` + `java` and currently runs **168 checks**
(TLV encode/decode + edge cases, CRC16 against an independent implementation, frame header/length/
partial packets/slice reassembly, `PacketAssembler` feeding, `HuaweiUtil` time conversion). What is
covered and what is *not* (BLE link, auth, crypto KATs) is in **[docs/TESTING.md](docs/TESTING.md)** —
read it before touching protocol code.

`.github/workflows/build.yml` runs the self-test and `:app:assembleDebug` on push / PR and uploads the
APK as an artifact; **this workflow has not been run on GitHub yet** (Actions cannot be verified from
this machine), so the first real run may need small fixes.

## Use it as a library

`bandcore/` is a standalone Android library. The entry point is `BandSession`:

```java
BandSession s = new BandSession(context, new BandSession.Hooks() {
    @Override public void onData(int newSamples) { /* new data arrived */ }
    @Override public JSONObject weather() { return myWeather; }          // optional: weather for the band
    @Override public void musicButton(int b) { /* band music page button */ }  // optional
});
s.start();                       // scan → auth → init → sync once a minute
s.samples().query(fromSec, toSec);   // heart rate / SpO2 / steps
s.truSleep().query(fromMs, toMs);    // TruSleep
s.stress().query(fromMs, toMs);      // stress
s.emotion().query(fromMs, toMs);     // emotion
```

## ⚠️ Before you start

- **The band must be unpaired from Huawei Health first** (remove the device in the Huawei app, or
  factory-reset the band). Both apps cannot be connected at the same time.
- **Android only** (8.0+). The app keeps the connection alive with a foreground notification; only
  the "disconnect" action in the app or the notification stops it.
- Only **one** app can talk to the band at a time: disconnect other apps that connect to it directly.
- The protocol is reverse-engineered; a Huawei firmware update may break it.
- These numbers are **not a medical diagnosis**.

## Known pitfalls (save yourself the trouble)

- Band 10 uses the "new sync": file downloads go through service `0x2C`, not `0x0A`.
- TruSleep stage codes: `1` light / `2` REM / `3` deep / `4` awake.
- There is no realtime heart rate `0x07/0x1c` — Band 10 does not support it.
- The band asks for phone info (`0x01/0x10`) only once, right after connecting, and never again.
- Weather: when the band sends `0x0f/0x04` with `7f=186AA`, answer with weather only — do not also
  answer "received", or it will ask again every 3 seconds.
- HRV dictionary `500044` and skin temperature `400012` are not available on Band 10.
- Not yet checked on HarmonyOS: the MCP server's local listener and the weather request (another app's
  native network calls were silently blocked on this phone before).

## Credits / license

The protocol implementation is largely ported from
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0), so this project is
released under **AGPL-3.0** as well — see `LICENSE` and `NOTICE.md`.
Not affiliated with Huawei; unofficial.
