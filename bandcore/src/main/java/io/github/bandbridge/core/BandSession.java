package io.github.bandbridge.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import io.github.bandbridge.core.auth.AuthFlow;
import io.github.bandbridge.core.data.DeviceInit;
import io.github.bandbridge.core.data.DictStore;
import io.github.bandbridge.core.data.DictSync;
import io.github.bandbridge.core.data.FeatureUpload;
import io.github.bandbridge.core.data.HrLiveSession;
import io.github.bandbridge.core.data.SampleStore;
import io.github.bandbridge.core.data.StepSync;
import io.github.bandbridge.core.data.StressSync;
import io.github.bandbridge.core.data.TruSleepStore;
import io.github.bandbridge.core.data.TruSleepSync;
import io.github.bandbridge.core.data.WeatherSync;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.HuaweiStressParser;
import io.github.bandbridge.core.proto.packets.DataSync;
import io.github.bandbridge.core.proto.packets.DeviceConfig;
import io.github.bandbridge.core.proto.packets.HuaweiDataSyncCommon;
import io.github.bandbridge.core.proto.packets.MusicControl;
import io.github.bandbridge.core.transport.HuaweiLink;
import io.github.bandbridge.core.transport.PacketAssembler;
import io.github.bandbridge.core.transport.RequestRunner;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One connection to one Huawei band: scan → BLE link → HiChain auth → device init → sync loop,
 * plus answering what the band asks the phone for (phone info, weather, music, feature list).
 *
 * <p>Data is kept on the phone in small JSON stores (see {@link #samples()}, {@link #truSleep()},
 * {@link #stress()}, {@link #emotion()}). Nothing here touches the network; weather comes from
 * the app through {@link Hooks#weather()}.</p>
 *
 * <p>Threading: BLE callbacks only feed bytes in; a single worker thread runs auth, init and the
 * sync loop (one minute per round). Public methods are safe to call from any thread.</p>
 *
 * <p>Verified on a HUAWEI Band 10. The band must NOT be paired with the Huawei Health app at the
 * same time (unpair / factory-reset it first).</p>
 */
public final class BandSession {

    /** Things that differ per app. Every method may return null / do nothing. */
    public interface Hooks {
        /** Weather to push to the band (format: see {@link WeatherSync}), or null if you have none yet. */
        default JSONObject weather() { return null; }
        /** Called when the band wants weather and there is none (or it is older than 50 min). */
        default void weatherNeeded() { }
        /** Now playing on the phone: {title, artist, playing}. Null = nothing playing. */
        default JSONObject nowPlaying() { return null; }
        /** Band music page button: 1 play, 2 pause, 3 previous, 4 next, 5 volume up, 6 volume down. */
        default void musicButton(int button) { }
        /** User profile for calories / stress; null = not filled in. */
        default DeviceInit.Profile profile() { return null; }
        /** State changed ("scanning", "auth", "ready", "lost", "authfail", "off"). */
        default void onState(String state) { }
        /** A sync round finished and stored new data. */
        default void onData(int newSamples) { }
        /** Stress calibration progress: state "measuring" (left, beats) / "done" (score) / "fail". */
        default void onCalibration(String state, int a, int b) { }

        /**
         * 心跳直播（0x19 逐拍流）：测量期间每个包刷新一次，结束后 running=false
         * （endReason 说明是到点 / 被停 / 没数据 / 出错）。
         */
        default void onHrLive(HrLiveSession.Snapshot snapshot) { }
    }

    public static final int PHASE_IDLE = 0, PHASE_SCAN = 1, PHASE_AUTH = 2, PHASE_READY = 3;

    private static final String PAIR_SP = "bandbridge_pair";
    private static final int[] DICT_CLASSES = { DictSync.EMOTION };   // HRV 500044 / skin temp 400012: not on Band 10

    private final Context app;
    private final Hooks hooks;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<Boolean> kick = new LinkedBlockingQueue<>();

    private volatile boolean want = false;
    private volatile int phase = PHASE_IDLE;
    private volatile int gen = 0;
    private volatile String lastErr = "", lastStage = "";
    private volatile HuaweiLink link;
    private volatile AuthFlow auth;
    private volatile PacketAssembler assembler;
    private volatile RequestRunner runner;
    private volatile DeviceInit deviceInit;
    private volatile Thread worker;

    private final SampleStore samples;
    private final TruSleepStore truSleep;
    private final DictStore stress, emotion;

    /* per-connection flags */
    private volatile boolean profileDirty, autoStressSent, featWanted, featDone, wxWanted, wxAckOnly, dictKick, wantCalib, calibRunning;
    private volatile int featTries, round;
    private volatile long lastTruAt, lastStressAt, lastDictAt, wxSentAt, lastDataAt;
    private volatile String lastMusicSig = "";

    /* 心跳直播（0x19 逐拍流）：请求 → 同步循环里跑一轮 → 结果留在 hrLiveSnap（结束后也留着） */
    private volatile int wantHrLiveSec;
    private volatile HrLiveSession hrLiveSess;
    private volatile HrLiveSession.Callback hrLiveCallback;
    private volatile HrLiveSession.Snapshot hrLiveSnap;

    public BandSession(Context context, Hooks hooks) {
        this.app = context.getApplicationContext();
        this.hooks = hooks == null ? new Hooks() { } : hooks;
        this.samples = new SampleStore(app);
        this.truSleep = new TruSleepStore(app);
        this.stress = new DictStore(app, "stress");
        this.emotion = new DictStore(app, DictSync.EMOTION);
    }

    /* ── public API ─────────────────────────────────────────────────────────── */

    public SampleStore samples() { return samples; }
    public TruSleepStore truSleep() { return truSleep; }
    public DictStore stress() { return stress; }
    public DictStore emotion() { return emotion; }
    public int phase() { return phase; }
    public boolean isReady() { return phase == PHASE_READY; }
    public String lastError() { return lastErr; }
    public String stage() { return lastStage; }
    public long lastDataAt() { return lastDataAt; }
    public int battery() { DeviceInit d = deviceInit; return d == null ? -1 : d.battery; }
    public String model() { DeviceInit d = deviceInit; return d == null ? "" : d.model; }
    public HuaweiStressParser.StressData calibration() { return StressSync.loadCalib(app); }
    public boolean calibrating() { return calibRunning; }

    /** Start (or retry). Needs BLUETOOTH_SCAN / BLUETOOTH_CONNECT already granted. */
    public void start() {
        want = true;
        lastErr = "";
        h.post(() -> {
            if (phase == PHASE_READY && link != null) return;
            Thread w = worker;
            if (w != null && w.isAlive()) return;
            if (link != null && (link.hasGivenUp() || phase == PHASE_AUTH)) teardown();
            boot();
        });
    }

    public void stop() {
        want = false;
        h.post(() -> { h.removeCallbacks(giveupRetry); teardown(); state("off"); });
    }

    /** Pull now (otherwise it pulls once a minute). */
    public void syncNow() {
        // TruSleep is a file download (a few seconds); tapping sync again within 2 min reuses the last one
        if (System.currentTimeMillis() - lastTruAt > 120_000L) lastTruAt = 0;
        kick.offer(Boolean.TRUE);
    }

    /** New weather is available from {@link Hooks#weather()} → push it now. */
    public void weatherChanged() { wxSentAt = 0; kick.offer(Boolean.TRUE); }

    /** Profile changed → resend on the next round. */
    public void profileChanged() { profileDirty = true; kick.offer(Boolean.TRUE); }

    /** Phone playback changed → tell the band (deduplicated). */
    public void nowPlayingChanged() { sendMusicInfo(false); }

    /** Start a 60-second stress calibration (sit still). Progress via {@link Hooks#onCalibration}. */
    public boolean calibrateStress() {
        if (phase != PHASE_READY || calibRunning) return false;
        wantCalib = true;
        kick.offer(Boolean.TRUE);
        return true;
    }

    /* ── 心跳直播：逐拍 RRI 流（service 0x19） ─────────────────────────────── */

    /**
     * 开始一次「心跳直播」：开手环的逐拍测量，把心跳一拍一拍换算成心率。
     * <b>立刻返回</b>；进度和结果通过 {@link Hooks#onHrLive(HrLiveSession.Snapshot)}。
     *
     * <p>跟压力校准一样，测量期间占用同步循环（那几十秒不拉分钟数据），结束后恢复。
     * 一次是有限窗口：默认 60 秒，最长 3 分钟（<b>能不能开到 3 分钟未验证</b>）。
     * 手环 0x19 的推送节奏也没真机量过，所以 {@code snapshot().lagMs} 就是判断
     * 「这是此刻还是几秒前」的依据。</p>
     *
     * @param seconds 5..180，越界会被夹住
     * @return false = 还没连上手环 / 已经在测
     */
    public boolean hrLiveStart(int seconds) { return hrLiveStart(seconds, null); }

    /**
     * 同上，但把进度回调给你（{@link HrLiveSession.Callback} 给的是
     * bpm / bpmAvg / beats / lagMs / lastBeatMsAgo 五个数），方便别的壳薄薄一层转出去。
     */
    public boolean hrLiveStart(int seconds, HrLiveSession.Callback callback) {
        if (phase != PHASE_READY) return false;
        if (hrLiveSess != null || wantHrLiveSec > 0) return false;
        int secs = Math.max(HrLiveSession.MIN_SECONDS, Math.min(HrLiveSession.MAX_SECONDS, seconds));
        hrLiveCallback = callback;
        hrLiveSnap = HrLiveSession.Snapshot.starting(System.currentTimeMillis(), secs);
        wantHrLiveSec = secs;
        kick.offer(Boolean.TRUE);
        return true;
    }

    /** 中途停掉心跳直播（不等它退；下一个轮询点就会收尾）。 */
    public void hrLiveStop() {
        HrLiveSession s = hrLiveSess;
        if (s != null) { s.stop(); return; }
        wantHrLiveSec = 0;                                      // 还没轮到它开：撤掉这次请求
        if (hrLiveSnap != null && hrLiveSnap.running) hrLiveSnap = null;   // 别留一个假的「正在测」
    }

    /** 在测（或已请求、还没开始）吗。 */
    public boolean hrLiveRunning() { return hrLiveSess != null || wantHrLiveSec > 0; }

    /** 当前 / 最近一次心跳直播的快照；从来没测过 = null（结束后也保留最后一次）。 */
    public HrLiveSession.Snapshot hrLiveSnapshot() { return hrLiveSnap; }

    /** Forget the paired identity (next start pairs as a new device). */
    public void forget() {
        try { app.getSharedPreferences(PAIR_SP, Context.MODE_PRIVATE).edit().clear().apply(); } catch (Throwable ignored) { }
        try { app.getSharedPreferences(io.github.bandbridge.core.auth.AuthState.PREF_FILE, Context.MODE_PRIVATE).edit().clear().apply(); } catch (Throwable ignored) { }
        io.github.bandbridge.core.transport.HuaweiLink.forgetAddr(app);   // also forget the saved band address (autoConnect target)
    }

    /* ── lifecycle ──────────────────────────────────────────────────────────── */

    private void state(String s) { try { hooks.onState(s); } catch (Throwable ignored) { } }

    private void boot() {
        if (!want || link != null) return;
        phase = PHASE_SCAN;
        lastStage = "scan";
        link = new HuaweiLink(app, new HuaweiLink.Listener() {
            @Override public void onReady() { lastStage = "link.ready"; phase = PHASE_AUTH; state("auth"); startAuth(); }
            @Override public void onPacket(byte[] data) { PacketAssembler as = assembler; if (as != null) as.feed(data); }
            @Override public void onLost(String why) {
                lastStage = "link.lost:" + why;
                lastErr = why;
                gen++;
                Thread w = worker;
                if (w != null) { try { w.interrupt(); } catch (Throwable ignored) { } }
                assembler = null; runner = null; auth = null;
                if ("giveup".equals(why)) {
                    phase = PHASE_IDLE;
                    h.removeCallbacks(giveupRetry);
                    h.postDelayed(giveupRetry, 5 * 60 * 1000L);   // band out of range: try again every 5 min
                } else if (phase != PHASE_IDLE) phase = PHASE_SCAN;
                state("lost");
            }
        });
        link.start();
        state("scanning");
    }

    private final Runnable giveupRetry = () -> {
        if (!want) return;
        HuaweiLink l = link;
        if (l != null && !l.hasGivenUp()) return;
        teardown();
        want = true;
        boot();
    };

    private void teardown() {
        gen++;
        phase = PHASE_IDLE;
        auth = null; assembler = null; runner = null;
        if (link != null) { link.stop(); link = null; }
        Thread w = worker;
        worker = null;
        if (w != null) { try { w.interrupt(); } catch (Throwable ignored) { } }
    }

    private boolean isPaired() { try { return app.getSharedPreferences(PAIR_SP, Context.MODE_PRIVATE).getBoolean("paired", false); } catch (Throwable t) { return false; } }
    private void markPaired() { try { app.getSharedPreferences(PAIR_SP, Context.MODE_PRIVATE).edit().putBoolean("paired", true).apply(); } catch (Throwable ignored) { } }

    private void startAuth() {
        if (!want || link == null) return;
        final String addr = link.getAddr();
        final int myGen = ++gen;
        Thread old = worker;
        if (old != null && old.isAlive()) { try { old.interrupt(); } catch (Throwable ignored) { } }
        worker = new Thread(() -> {
            try {
                AuthFlow a = new AuthFlow(packet -> { HuaweiLink l = link; if (l != null) l.send(packet); }, app);
                a.setDeviceAddress(addr);
                a.setFirstConnection(!isPaired());
                assembler = new PacketAssembler(a.getParamsProvider(), new PacketAssembler.Sink() {
                    @Override public boolean onPacket(HuaweiPacket p) {
                        AuthFlow cur = auth;
                        if (cur != null && cur.isRunning()) { cur.onPacket(p); return true; }
                        RequestRunner r = runner;
                        return r != null && r.offer(p);
                    }
                    @Override public void onAsync(HuaweiPacket p) { onBandAsks(p, a); }
                });
                auth = a;
                boolean ok = a.run();
                if (myGen != gen) return;
                if (!ok) { lastErr = a.getLastError(); phase = PHASE_AUTH; state("authfail"); return; }
                markPaired();
                phase = PHASE_READY;
                Probe.post("hwauth", "ok | " + a.getAuthPath());

                RequestRunner rr = new RequestRunner(a.getParamsProvider(), slice -> { HuaweiLink l = link; if (l != null) l.send(slice); });
                runner = rr;
                DeviceInit init = new DeviceInit(rr);
                init.run();
                if (myGen != gen) return;
                deviceInit = init;
                state("ready");
                syncLoop(rr, myGen);
            } catch (Throwable t) {
                if (myGen != gen) return;
                lastErr = t.getClass().getSimpleName() + ":" + t.getMessage();
                phase = PHASE_AUTH;
                state("authfail");
            }
        }, "bandbridge-worker");
        worker.start();
    }

    /* ── what the band asks the phone (BLE callback thread: answer quickly, never block) ── */

    private void onBandAsks(HuaweiPacket p, AuthFlow a) {
        try {
            int svc = p.serviceId & 0xFF, cmd = p.commandId & 0xFF;
            if (svc == 0x01 && cmd == (DeviceConfig.PhoneInfo.id & 0xFF) && p instanceof DeviceConfig.PhoneInfo.Response) {
                DeviceConfig.PhoneInfo.Response r = (DeviceConfig.PhoneInfo.Response) p;
                r.parseTlv();
                HuaweiLink l = link;
                if (!r.isAck && l != null && r.info != null)
                    for (byte[] s : new DeviceConfig.PhoneInfo.Request(a.getParamsProvider(), r.info).serialize()) l.send(s);
            } else if (svc == 0x37 && cmd == 0x04) {                       // "new emotion data available"
                dictKick = true; kick.offer(Boolean.TRUE);
            } else if (p instanceof DataSync.ConfigCommand.Response) {     // featureManager asks for the feature list
                DataSync.ConfigCommand.Response cr = (DataSync.ConfigCommand.Response) p;
                cr.parseTlv();
                if ("featureManager".equals(cr.srcPackage) && !featDone) { featWanted = true; kick.offer(Boolean.TRUE); }
            } else if (svc == 0x0f && cmd == 0x04) {                       // weather
                boolean wants = p.getTlv() != null && p.getTlv().getAsInteger(0x7f, -1) == 0x000186AA;
                if (wants) { if (System.currentTimeMillis() - wxSentAt > 20000L) { wxWanted = true; kick.offer(Boolean.TRUE); } }
                else { wxAckOnly = true; kick.offer(Boolean.TRUE); }
            } else if (svc == 0x25) {                                      // music page
                onMusicPacket(p);
            }
        } catch (Throwable t) { Probe.post("hwasync", "fail | " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
    }

    private void sendPkt(HuaweiPacket pk) { try { RequestRunner r = runner; if (r != null && phase == PHASE_READY) r.sendOnly(pk); } catch (Throwable ignored) { } }

    private void sendMusicInfo(boolean force) {
        try {
            RequestRunner r = runner;
            if (r == null || phase != PHASE_READY) return;
            JSONObject np = hooks.nowPlaying();
            String title = np == null ? "" : np.optString("title", "");
            String artist = np == null ? "" : np.optString("artist", "");
            boolean playing = np != null && np.optBoolean("playing", false);
            String sig = title + "|" + artist + "|" + playing + "|" + (np != null);
            if (!force && sig.equals(lastMusicSig)) return;
            lastMusicSig = sig;
            android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            byte maxV = (byte) am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            byte curV = (byte) am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            byte st = (byte) (np == null ? 0 : playing ? 3 : 2);
            r.sendOnly(new MusicControl.MusicInfo.Request(r.params(), artist, title, st, maxV, curV));
        } catch (Throwable t) { Probe.post("hwmusic", "info fail | " + t.getMessage()); }
    }

    private void onMusicPacket(HuaweiPacket p) throws Exception {
        RequestRunner r = runner;
        if (r == null) return;
        if (p instanceof MusicControl.MusicStatusResponse) {
            sendPkt(new MusicControl.MusicStatusRequest(r.params(), MusicControl.MusicStatusResponse.id, MusicControl.successValue));
            sendMusicInfo(true);
        } else if (p instanceof MusicControl.Control.Response) {
            MusicControl.Control.Response c = (MusicControl.Control.Response) p;
            c.parseTlv();
            if (!c.buttonPresent && !c.volumePresent) return;      // 100 = left the music page
            int raw = c.rawButton & 0xff;
            if (raw >= 1 && raw <= 6) hooks.musicButton(raw);
            if (c.volumePresent) {
                android.media.AudioManager am = (android.media.AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (c.volume >= 0 && c.volume <= am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, c.volume, 0);
            }
            sendPkt(new MusicControl.MusicStatusRequest(r.params(), MusicControl.Control.id, MusicControl.successValue));
            h.postDelayed(() -> sendMusicInfo(true), 400);
        }
    }

    private static boolean xbit(byte[] caps, int which) {
        if (caps == null || which >= caps.length * 8) return false;
        int bit = 1 << (which % 8);
        return (caps[which / 8] & bit) == bit;
    }

    /* ── sync loop (worker thread) ──────────────────────────────────────────── */

    private void syncLoop(RequestRunner rr, int myGen) throws InterruptedException {
        kick.clear();
        profileDirty = true; autoStressSent = false; featDone = false; featTries = 0; wxSentAt = 0; lastMusicSig = "";
        final Set<Integer> dictSkip = Collections.synchronizedSet(new HashSet<>());
        while (myGen == gen && phase == PHASE_READY) {
            /* ⓪ 心跳直播（0x19 逐拍流）：她 / MCP 要了一次就这里跑。
               测量期间占用 worker 和 runner（跟压力校准一样，那一轮不拉分钟数据），跑完恢复。 */
            if (wantHrLiveSec > 0 && hrLiveSess == null) {
                final int secs = wantHrLiveSec;
                wantHrLiveSec = 0;
                runHrLive(rr, secs);
                if (myGen != gen) return;
            }

            long now = System.currentTimeMillis() / 1000L;

            if (profileDirty) {
                DeviceInit.Profile pf = null;
                try { pf = hooks.profile(); } catch (Throwable ignored) { }
                if (pf != null) new DeviceInit(rr).sendProfile(pf);
                profileDirty = false;
            }

            /* ① heart rate / SpO2 / steps / resting HR — one record per minute on the band */
            long last = samples.lastTs();
            long from = last > 0 ? Math.max(last - 600, now - 10 * 86400L) : now - 3 * 86400L;
            List<StepSync.Sample> got = new StepSync(rr).quiet((round % 15) != 0).run((int) from, (int) now);
            if (myGen != gen) return;
            int added = samples.merge(got);
            if (added > 0) lastDataAt = System.currentTimeMillis();

            /* ② TruSleep (file download over 0x2C), every 30 min */
            DeviceInit di = deviceInit;
            if (di != null && System.currentTimeMillis() - lastTruAt > 30 * 60000L) {
                long lw = truSleep.lastWakeSec();
                long tFrom = lw > 0 ? Math.max(lw - 12 * 3600, now - 10 * 86400L) : now - 3 * 86400L;
                List<JSONObject> nights = new TruSleepSync(rr).run((int) tFrom, (int) now, di.expand);
                if (myGen != gen) return;
                truSleep.merge(nights);
                lastTruAt = System.currentTimeMillis();
            }

            /* ③ feature list (the band keeps asking featureManager for it) */
            if (featWanted && !featDone && featTries < 2) {
                featWanted = false; featTries++;
                featDone = new FeatureUpload(rr).run("CN");
                if (myGen != gen) return;
            }

            /* ④ weather */
            if (wxAckOnly) { wxAckOnly = false; new WeatherSync(rr, false, false).ackDevice(); }
            if (wxWanted || System.currentTimeMillis() - wxSentAt > 60 * 60000L) {
                wxWanted = false;
                JSONObject wx = null;
                try { wx = hooks.weather(); } catch (Throwable ignored) { }
                if (wx != null) {
                    WeatherSync ws = new WeatherSync(rr, di != null && xbit(di.expand, 0x2f), di != null && xbit(di.expand, 0xc0));
                    if (ws.send(wx)) wxSentAt = System.currentTimeMillis();
                } else {
                    wxSentAt = System.currentTimeMillis() - 55 * 60000L;   // look again in 5 min
                    try { hooks.weatherNeeded(); } catch (Throwable ignored) { }
                }
                if (myGen != gen) return;
            }

            /* ⑤ stress: calibration on request, auto-stress on every connect, download every 30 min */
            StressSync ssy = new StressSync(rr, app);
            if (wantCalib) {
                wantCalib = false;
                calibRunning = true;
                try {
                    hooks.onCalibration("measuring", 60, 0);
                    HuaweiStressParser.StressData cd = ssy.calibrate((left, beats) -> hooks.onCalibration("measuring", left, beats));
                    if (cd != null) { autoStressSent = true; hooks.onCalibration("done", cd.score & 0xff, cd.level & 0xff); }
                    else hooks.onCalibration("fail", 0, 0);
                } finally { calibRunning = false; }
                if (myGen != gen) return;
            }
            if (!autoStressSent) { ssy.enableAuto(null); autoStressSent = true; }
            if (StressSync.loadCalib(app) != null && System.currentTimeMillis() - lastStressAt > 30 * 60000L) {
                long lm = stress.lastMs() / 1000L;
                long sFrom = lm > 0 ? Math.max(lm - 3600, now - 10 * 86400L) : now - 3 * 86400L;
                List<JSONObject> sr = ssy.download((int) sFrom, (int) now);
                if (myGen != gen) return;
                stress.merge(sr);
                lastStressAt = System.currentTimeMillis();
            }

            /* ⑥ emotion (P2P data dictionary): when the band says there is new data, or every 10 min */
            if (dictKick || System.currentTimeMillis() - lastDictAt > 10 * 60000L) {
                dictKick = false;
                DictSync ds = new DictSync(rr);
                for (int cls : DICT_CLASSES) {
                    if (dictSkip.contains(cls)) continue;
                    long lm = emotion.lastMs();
                    long dFrom = lm > 0 ? Math.max(lm + 1000, System.currentTimeMillis() - 10 * 86400000L) : System.currentTimeMillis() - 7 * 86400000L;
                    List<JSONObject> recs = ds.fetch(cls, dFrom);
                    if (myGen != gen) return;
                    if (ds.lastFailed) dictSkip.add(cls);
                    emotion.merge(recs);
                }
                lastDictAt = System.currentTimeMillis();
            }

            try { hooks.onData(added); } catch (Throwable ignored) { }
            round++;
            kick.poll(1, TimeUnit.MINUTES);                         // 手环约每分钟记一格（心跳直播是另开的 0x19 窗口，不算在内）
        }
    }

    /* ── 心跳直播（worker 线程内；把 0x19 逐拍流当心率用） ─────────────────── */

    /**
     * 跑一次心跳直播。这里的顺序很重要：
     * 先 {@code start()}（HrLiveSession 自己起线程），再 {@code awaitFinished()} —— 
     * 测量期间这个 worker 只等着，不碰 {@link RequestRunner}，所以 runner 的所有权是清楚的。
     */
    private void runHrLive(RequestRunner rr, int seconds) {
        final HrLiveSession.Callback user = hrLiveCallback;
        hrLiveCallback = null;
        final HrLiveSession sess = new HrLiveSession(rr);
        hrLiveSess = sess;
        HrLiveSession.Callback relay = new HrLiveSession.Callback() {
            @Override public void onUpdate(int bpm, int bpmAvg, int beats, long lagMs, long lastBeatMsAgo) {
                HrLiveSession.Snapshot snap = sess.snapshot();
                if (snap != null) emitHrLive(snap);
                if (user != null) {
                    try { user.onUpdate(bpm, bpmAvg, beats, lagMs, lastBeatMsAgo); } catch (Throwable ignored) { }
                }
            }

            @Override public void onEnd(HrLiveSession.Snapshot snap) {
                emitHrLive(snap);
                if (user != null) { try { user.onEnd(snap); } catch (Throwable ignored) { } }
            }
        };
        try {
            sess.start(seconds, relay);
            long wait = seconds * 1000L + HrLiveSession.HOST_WAIT_MARGIN_MS;
            if (!sess.awaitFinished(wait)) {
                Probe.post("hwlive", "stuck | " + seconds + "s 还没结束，主动停");
                sess.stop();
                sess.awaitFinished(5000L);
            }
        } catch (Throwable t) {
            Probe.post("hwlive", "fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        } finally {
            hrLiveSess = null;
        }
    }

    private void emitHrLive(HrLiveSession.Snapshot snap) {
        hrLiveSnap = snap;
        try { hooks.onHrLive(snap); } catch (Throwable ignored) { }
    }
}
