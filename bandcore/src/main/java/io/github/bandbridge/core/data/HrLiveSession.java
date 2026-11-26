package io.github.bandbridge.core.data;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.hr.RriBeats;
import io.github.bandbridge.core.proto.packets.HrRriTest;
import io.github.bandbridge.core.transport.RequestRunner;

/**
 * 「心跳直播」：开手环的逐拍（RRI）测量，把一拍一拍换算成心率。
 *
 * <p><b>自包含、可移植</b>：这个类不碰 Android（没有 {@code Context}、没有 {@code BluetoothGatt}），
 * 只依赖 {@link RequestRunner}（传输层）和协议报文类。谁都可以把它收进自己的壳：
 * 一个已经建好 {@code RequestRunner} 的宿主（比如另一个 App、一个 Capacitor 插件）
 * 只要调 {@link #start(int, Callback)} / {@link #stop()} / {@link #snapshot()}。</p>
 *
 * <p>协议（见 {@code docs/lab/hrlive-0x19.md}）：</p>
 * <pre>
 *   发  0x19 / 0x01  {01: 03}        开启测量（加密包；手环回 7f=000186a0 表示成功）
 *   收  0x19 / 0x05                  手环主动推：0x82 容器 → 若干 0x83 子容器
 *                                     每个子容器：0x04 = rri(毫秒) + 0x05 = sqi(信号质量)
 *   发  0x19 / 0x01  {01: 04}        结束测量
 * </pre>
 *
 * <p>心率 = 60000 / rri，逐拍。Gadgetbridge（以及移植过来的 {@link StressSync}）只用它做
 * 60 秒压力校准，从不算心率 —— 这个类就是把同一股数据当成「实时心跳」用。</p>
 *
 * <p><b>几件要知道的事（设计取舍，不是猜的）：</b></p>
 * <ul>
 *   <li>一次测量是有限窗口。GB 只开到 60 秒；能开多久（3 分钟？常开？）<b>未验证</b>。
 *       {@link #runBlocking(int)} 到点就关，失败只是「这次没测成」，不会把连接搞坏。</li>
 *   <li>推包节奏未知。每包几拍、多久一包要真机看 {@link Snapshot#lagMs} 和
 *       {@link Snapshot#firstPacketAfterMs}；lag 小才是真「此刻」。</li>
 *   <li><b>同一个 {@link RequestRunner} 一次只能有一个消费者。</b>
 *       如果你用 {@link #start(int, Callback)}（自己起线程），调用前要保证没有别人正在用这个
 *       runner 做阻塞请求；用 {@link BandSession} 的话它已经替你排队了
 *       （测量期间同步循环会停下来等）。</li>
 * </ul>
 *
 * <p>探针前缀 {@code hwlive}（开流成功码 / 首包延迟 / 拍数 / lag），会出现在宿主装的
 * {@code Probe.Sink} 里（手环桥 App 会显示在日志页）。</p>
 */
public final class HrLiveSession {

    /** 0x19/0x01 的 type：3 = 开（GB {@code HuaweiStressCalibration} 用的值）。 */
    public static final byte TYPE_OPEN = 3;
    /** 0x19/0x01 的 type：4 = 关。 */
    public static final byte TYPE_CLOSE = 4;

    /** 手环对 0x19/0x01 的成功应答码（TLV {@code 0x7f}，真机上是 {@code 000186A0}）。 */
    public static final int SUCCESS_CODE = 0x000186A0;

    public static final int MIN_SECONDS = 5;
    public static final int DEFAULT_SECONDS = 60;
    public static final int MAX_SECONDS = 180;

    private static final long OPEN_ACK_TIMEOUT_MS = 3000L;
    private static final long FIRST_PACKET_TIMEOUT_MS = 7000L;
    private static final long POLL_SLICE_MS = 500L;
    /** 每 5 秒打一行 `pkt`：60 秒约 12 行，3 分钟约 36 行 —— App 的日志环形缓冲只有 80 行，别把它刷掉。 */
    private static final long PROBE_EVERY_MS = 5000L;
    /** 给宿主的超时余量：开应答 3 秒 + 首包 7 秒 + 收尾。 */
    public static final long HOST_WAIT_MARGIN_MS = 15_000L;

    /** 一次测量的快照（运行中随着包刷新；结束后是最终结果）。 */
    public static final class Snapshot {
        /** true = 还在测；false = 已经结束（{@link #endReason} 说明为什么）。 */
        public final boolean running;
        /** 这次打算测多少秒。 */
        public final int plannedSeconds;
        /** 开始时刻（epoch 毫秒）。 */
        public final long startedAtMs;
        /** 结束时刻（epoch 毫秒）；运行中 = 0。 */
        public final long endedAtMs;
        /** 「此刻大概多少」：最近 10 秒窗口的平均心率，0 = 还没收到拍。 */
        public final int bpm;
        /** 整段平均心率，0 = 还没收到拍。 */
        public final int bpmAvg;
        /** 只看最后一拍，0 = 还没收到拍。 */
        public final int instantBpm;
        /** 收下的总拍数。 */
        public final int beats;
        /** 被丢掉拍的数（sqi=0 / 间期离谱）。= {@link #droppedSqi0} + {@link #droppedRange}。 */
        public final int dropped;
        /** 丢掉里 sqi=0 的条数（手环自己说这一拍不可信）。 */
        public final int droppedSqi0;
        /** 丢掉里间期 <250 ms 或 >3000 ms 的条数（漏拍 / 断拍 / 手没贴住）。 */
        public final int droppedRange;
        /** 收到几个 0x19/0x05 包。 */
        public final int packets;
        /**
         * 最后一个包里**收下**的那些 RRI（毫秒，按到达顺序，已过滤 sqi=0 / 间期离谱）。
         * 给 UI 做「按包回放」用：手环是攒几秒成批发包的，所以这是「几秒前」的拍，不是此刻。
         * 没收到过 = 空数组（不会是 null）。
         */
        public final int[] lastPacketRris;
        /** 最后一拍的时刻（epoch 毫秒），0 = 还没收到拍。 */
        public final long lastBeatAtMs;
        /** 收到最后一包时的延迟 = 包到达 − 按 RRI 累加出来的最后一拍；-1 = 还没收到包。 */
        public final long lagMs;
        /** 开流到第一个包过了多久（毫秒）；-1 = 还没收到包。 */
        public final long firstPacketAfterMs;
        /** 「当前心率」那个窗口实际覆盖了多久（毫秒）。 */
        public final int windowSpanMs;
        /** 结束原因：{@code null}（还在跑）/ {@code timeup} / {@code stopped} / {@code no-data} /
         *  {@code interrupted} / {@code error}。 */
        public final String endReason;
        /** 抛异常时的 {@code 类名:消息}；否则 null。 */
        public final String error;

        Snapshot(boolean running, int plannedSeconds, long startedAtMs, long endedAtMs,
                 int bpm, int bpmAvg, int instantBpm, int beats, int dropped, int droppedSqi0, int droppedRange,
                 int packets, int[] lastPacketRris,
                 long lastBeatAtMs, long lagMs, long firstPacketAfterMs, int windowSpanMs,
                 String endReason, String error) {
            this.running = running;
            this.plannedSeconds = plannedSeconds;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
            this.bpm = bpm;
            this.bpmAvg = bpmAvg;
            this.instantBpm = instantBpm;
            this.beats = beats;
            this.dropped = dropped;
            this.droppedSqi0 = droppedSqi0;
            this.droppedRange = droppedRange;
            this.packets = packets;
            this.lastPacketRris = lastPacketRris == null ? new int[0] : lastPacketRris;
            this.lastBeatAtMs = lastBeatAtMs;
            this.lagMs = lagMs;
            this.firstPacketAfterMs = firstPacketAfterMs;
            this.windowSpanMs = windowSpanMs;
            this.endReason = endReason;
            this.error = error;
        }

        /** 请求刚发出、测量还没真正开始时的占位快照（别让宿主以为「没在测」）。 */
        public static Snapshot starting(long atMs, int seconds) {
            return new Snapshot(true, seconds, atMs, 0L, 0, 0, 0, 0, 0, 0, 0, 0, new int[0], 0L, -1L, -1L, 0, null, null);
        }

        /** 这次测了多久（毫秒）：运行中 = 到现在，结束后 = 实际时长。 */
        public long elapsedMs(long nowMs) {
            long end = running ? nowMs : (endedAtMs > 0 ? endedAtMs : nowMs);
            return Math.max(0L, end - startedAtMs);
        }

        /** 最后一拍离 {@code nowMs} 多久；还没拍 = -1。 */
        public long lastBeatAgoMs(long nowMs) {
            return lastBeatAtMs <= 0 ? -1L : Math.max(0L, nowMs - lastBeatAtMs);
        }

        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "%s %ds bpm=%d avg=%d beats=%d packets=%d lag=%dms span=%dms%s",
                    running ? "running" : "done", plannedSeconds, bpm, bpmAvg, beats, packets,
                    lagMs, windowSpanMs,
                    endReason == null ? "" : " reason=" + endReason);
        }
    }

    /**
     * 进度回调。**只有这四个数是最常用的**，宿主直接转给 UI 就行；
     * 要更细的信息用 {@link #snapshot()} 或 {@link #onEnd(Snapshot)}。
     */
    public interface Callback {
        /**
         * 每收到一个包刷一次（在测量的工作线程上调，别在里面做重活）。
         *
         * @param bpm            当前心率（最近 10 秒平滑，0 = 还没收到拍）
         * @param bpmAvg         整段平均心率（0 = 还没收到拍）
         * @param beats          收下的拍数
         * @param lagMs          收最后一包时的推送延迟（包到达 − 最后一拍，-1 = 还没收到包）
         * @param lastBeatMsAgo  最后一拍离现在多久（-1 = 还没收到拍）
         */
        void onUpdate(int bpm, int bpmAvg, int beats, long lagMs, long lastBeatMsAgo);

        /** 结束（成功 / 到点 / 被停 / 没数据 / 出错都会来一次）。默认不管。 */
        default void onEnd(Snapshot snapshot) { }
    }

    private final RequestRunner rr;

    private volatile Snapshot last;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile Thread worker;
    private volatile int plannedSeconds = DEFAULT_SECONDS;

    private final RriBeats beats = new RriBeats();
    private long startedAtMs;
    private long endedAtMs;
    private long lastProbeAtMs;
    private long firstPacketAfterMs = -1L;
    private long lastLagMs = -1L;
    private int packets;
    /** 最近一个包里收下的 RRI（毫秒）；给 UI 回放用，worker 写 / UI 读，所以 volatile + 每次新数组。 */
    private volatile int[] lastPacketRris = new int[0];

    /** @param rr 已经认证好的传输（同一个 runner 一次只能有一个消费者，见类注释） */
    public HrLiveSession(RequestRunner rr) { this.rr = rr; }

    /* ── 宿主用的三个方法 ─────────────────────────────────────────────────── */

    /**
     * 开始一次测量（**不阻塞**：自己起一个线程跑）。
     * 已经在跑就忽略。
     *
     * @param seconds  5..180；越界会被夹住；不知道给多少就 {@link #DEFAULT_SECONDS}
     * @param callback 进度回调（可 null）
     */
    public void start(int seconds, Callback callback) {
        synchronized (this) {
            if (running) return;
            running = true;
            stopRequested = false;
            last = Snapshot.starting(System.currentTimeMillis(), clampSeconds(seconds));
            Thread t = new Thread(() -> {
                try {
                    runBlocking(clampSeconds(seconds), callback);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t2) {
                    probe("thread fail | " + t2.getClass().getSimpleName() + ":" + t2.getMessage());
                } finally {
                    running = false;
                }
            }, "bandbridge-hrlive");
            t.setDaemon(true);
            worker = t;
            t.start();
        }
    }

    /** 请求停止（不等它退）。没在跑就什么都不做。 */
    public void stop() {
        stopRequested = true;
    }

    /** 当前 / 最近一次的快照；从来没跑过 = null。 */
    public Snapshot snapshot() { return last; }

    /** 还在测吗。 */
    public boolean isRunning() { return running; }

    /**
     * 等测量结束（给「必须串行使用 runner」的宿主用，比如 {@link BandSession} 的同步循环）。
     *
     * @return true = 已经结束；false = 超时还没结束（宿主自己决定怎么办）
     */
    public boolean awaitFinished(long timeoutMs) {
        Thread t = worker;
        if (t == null) return true;
        try {
            t.join(Math.max(1L, timeoutMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !t.isAlive();
    }

    /* ── 已经有 worker 线程的宿主可以直接用它（阻塞；BandSession 走这条） ── */

    /**
     * 同步跑一次测量（阻塞当前线程）。给「已经有一个 worker 线程」的宿主用，
     * 这样不额外起线程、runner 的所有权清清楚楚。
     *
     * @return 最终快照（不会返回 null）
     * @throws InterruptedException 连接被拆 / 手环断了
     */
    public Snapshot runBlocking(int seconds, Callback callback) throws InterruptedException {
        plannedSeconds = clampSeconds(seconds);
        beats.reset();
        stopRequested = false;
        packets = 0;
        firstPacketAfterMs = -1L;
        lastLagMs = -1L;
        startedAtMs = System.currentTimeMillis();
        endedAtMs = 0L;
        lastProbeAtMs = 0L;
        running = true;
        last = Snapshot.starting(startedAtMs, plannedSeconds);

        String reason = "timeup";
        String error = null;
        Snapshot result;
        try {
            rr.openStream(HrRriTest.id, HrRriTest.RriData.id);   // 先开流：不然 0x19/0x05 会被当成"没人要的包"
            probe("open | sent 0x19/0x01 {01:03} | " + plannedSeconds + "s");
            openAck();

            long deadline = startedAtMs + plannedSeconds * 1000L;
            long firstDeadline = startedAtMs + FIRST_PACKET_TIMEOUT_MS;
            while (true) {
                long now = System.currentTimeMillis();
                if (stopRequested) { reason = "stopped"; break; }
                if (now >= deadline) { reason = "timeup"; break; }
                long wait = Math.min(POLL_SLICE_MS, deadline - now);
                HuaweiPacket p = rr.pollStream(wait);
                if (p == null) {
                    if (firstPacketAfterMs < 0 && System.currentTimeMillis() >= firstDeadline) {
                        reason = "no-data";
                        break;
                    }
                    continue;
                }
                consume(p, callback);
            }
        } catch (InterruptedException ie) {
            reason = "interrupted";
            throw ie;
        } catch (Throwable t) {
            reason = "error";
            error = t.getClass().getSimpleName() + ":" + t.getMessage();
            probe("fail | " + error);
        } finally {
            try { rr.closeStream(); } catch (Throwable ignored) { }
            closeQuietly();
            endedAtMs = System.currentTimeMillis();
            result = snapshot(false, reason, error);
            last = result;
            running = false;
            probe(String.format(Locale.ROOT,
                    "end | reason=%s took=%dms packets=%d beats=%d dropped=%d (sqi0=%d range=%d) bpm=%d avg=%d lag=%dms first-pkt=%dms",
                    reason, endedAtMs - startedAtMs, packets, beats.beats(), beats.dropped(),
                    beats.droppedSqi0(), beats.droppedRange(),
                    beats.bpm(), beats.avgBpm(), lastLagMs, firstPacketAfterMs));
            if (callback != null) { try { callback.onEnd(result); } catch (Throwable ignored) { } }
        }
        return result;
    }

    /* ── 内部 ─────────────────────────────────────────────────────────────── */

    /** 等 0x19/0x01 的应答，把成功码打进日志（真机上是 7f04 0001 86a0）；等不到也继续等数据。 */
    private void openAck() {
        try {
            HuaweiPacket ack = rr.call(new HrRriTest.OpenOrClose.Request(rr.params(), TYPE_OPEN),
                    OPEN_ACK_TIMEOUT_MS, "HrLiveOpen");
            int code = codeOf(ack);
            probe("open-ack | 0x" + Integer.toHexString(code)
                    + (code == SUCCESS_CODE ? " (success)" : " (unexpected)"));
        } catch (RequestRunner.TimeoutException te) {
            probe("open-ack | none in " + OPEN_ACK_TIMEOUT_MS + "ms (继续等数据)");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            probe("open-ack | interrupted");
        } catch (Throwable t) {
            probe("open-ack | fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    /** 关测量。用 sendOnly（不等应答）：被中断时也要能尽量把手环放回去。 */
    private void closeQuietly() {
        try {
            rr.sendOnly(new HrRriTest.OpenOrClose.Request(rr.params(), TYPE_CLOSE));
            probe("close | sent 0x19/0x01 {01:04}");
        } catch (Throwable t) {
            probe("close | fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    /** 处理一个包；不是 RRI 包就当没看见。 */
    private void consume(HuaweiPacket p, Callback callback) {
        if (!(p instanceof HrRriTest.RriData.Response)) {
            probe("other | svc=0x" + Integer.toHexString(p.serviceId & 0xFF)
                    + " cmd=0x" + Integer.toHexString(p.commandId & 0xFF));
            return;
        }
        List<HrRriTest.RriData.Response.rriSqiData> cs = ((HrRriTest.RriData.Response) p).containers;
        if (cs == null || cs.isEmpty()) return;

        long at = System.currentTimeMillis();
        int added = 0;
        int[] accepted = new int[cs.size()];
        for (HrRriTest.RriData.Response.rriSqiData d : cs) {
            // rri 是 short、sqi 是 byte：两端都按无符号读，别把 >127 的拍读成负数
            int rri = d.rri & 0xFFFF;
            if (beats.add(rri, d.sqi & 0xFF, at)) accepted[added++] = rri;
        }
        // 给 UI 的「按包回放」：只留这一包里收下的 RRI（顺序不变）
        lastPacketRris = added == accepted.length ? accepted : Arrays.copyOf(accepted, added);
        packets++;
        lastLagMs = beats.lagMs(at);
        if (firstPacketAfterMs < 0) {
            firstPacketAfterMs = at - startedAtMs;
            probe(String.format(Locale.ROOT, "first-pkt | after=%dms beats=%d bpm=%d",
                    firstPacketAfterMs, added, beats.bpm()));
        }
        if (at - lastProbeAtMs >= PROBE_EVERY_MS) {
            lastProbeAtMs = at;
            probe(String.format(Locale.ROOT, "pkt | #%d +%d total=%d dropped=%d (sqi0=%d range=%d) bpm=%d avg=%d lag=%dms",
                    packets, added, beats.beats(), beats.dropped(), beats.droppedSqi0(), beats.droppedRange(),
                    beats.bpm(), beats.avgBpm(), lastLagMs));
        }
        Snapshot s = snapshot(true, null, null);
        last = s;
        if (callback != null) {
            try { callback.onUpdate(s.bpm, s.bpmAvg, s.beats, s.lagMs, s.lastBeatAgoMs(at)); }
            catch (Throwable ignored) { }
        }
    }

    private Snapshot snapshot(boolean isRunning, String reason, String error) {
        return new Snapshot(isRunning, plannedSeconds, startedAtMs, isRunning ? 0L : endedAtMs,
                beats.bpm(), beats.avgBpm(), beats.instantBpm(), beats.beats(), beats.dropped(),
                beats.droppedSqi0(), beats.droppedRange(),
                packets, lastPacketRris,
                beats.lastBeatAtMs(), lastLagMs, firstPacketAfterMs, beats.windowSpanMs(),
                reason, error);
    }

    /** 0x7f = 手环的返回码（成功 0x000186A0）；没有就是 -3。 */
    private static int codeOf(HuaweiPacket p) {
        if (p == null || p.getTlv() == null) return -3;
        return p.getTlv().getAsInteger(0x7f, -3);
    }

    private static int clampSeconds(int seconds) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
    }

    private static void probe(String text) {
        try { Probe.post("hwlive", text); } catch (Throwable ignored) { }
    }
}
