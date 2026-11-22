package io.github.bandbridge.core.proto.hr;

import java.util.ArrayDeque;

/**
 * 把一个一个的 R-R 间期（0x19/0x05 推上来的「拍」）换算成心率。
 *
 * <p>手环推的是**逐拍间期**，不是心率：{@code rri} = 相邻两拍之间多少毫秒，
 * 所以 {@code bpm = 60000 / rri}。压力校准那条路（{@code StressSync}）只看 HRV 特征，
 * 这里换成「人看得懂的心率」。</p>
 *
 * <p>三件事：</p>
 * <ul>
 *   <li>{@link #bpm()} —— 最近一小段（默认 10 秒窗口）的平均心率，也就是「此刻大概多少」；</li>
 *   <li>{@link #avgBpm()} —— 这次测量从头到现在的平均心率；</li>
 *   <li>{@link #lagMs(long)} —— 按拍子累加出来的「最后一拍的时刻」离现在多久，
 *       用来判断数据是「刚刚」还是「几秒前」的。</li>
 * </ul>
 *
 * <p>纯逻辑，不碰 Android / 蓝牙，所以能在离线自测里跑（见 {@code RriBeatsSelfTest}）。
 * 线程不安全：一个测量会话只在一个 worker 线程里用。</p>
 */
public final class RriBeats {

    /** 一次心跳间隔不可能比这更短（250 ms ≈ 240 bpm）——比这快的是噪声/漏拍。 */
    public static final int MIN_RRI_MS = 250;
    /** 也不应该比这更长（3000 ms ≈ 20 bpm）——比这慢的是手指没贴住 / 断拍。 */
    public static final int MAX_RRI_MS = 3000;
    /** 默认用最近 10 秒的拍算「当前心率」：够稳，又不会把 1 分钟前的状态算进来。 */
    public static final int DEFAULT_WINDOW_MS = 10_000;

    private static final class Beat {
        final long atMs;
        final int rriMs;
        Beat(long atMs, int rriMs) { this.atMs = atMs; this.rriMs = rriMs; }
    }

    private final int windowMs;
    private final ArrayDeque<Beat> window = new ArrayDeque<>();

    private int accepted;
    private int dropped;
    private long sumRriMs;
    private long firstBeatAtMs = -1L;
    private long lastBeatAtMs = -1L;

    public RriBeats() { this(DEFAULT_WINDOW_MS); }

    /** @param windowMs 「当前心率」取多长一段；小于 1 秒按 1 秒算 */
    public RriBeats(int windowMs) {
        this.windowMs = Math.max(1000, windowMs);
    }

    /**
     * 收一拍。
     *
     * @param rriMs       相邻两拍的间隔（毫秒）
     * @param sqi         信号质量；0 = 手环自己说这一拍不可信（GB / bandcore 都过滤掉）
     * @param packetAtMs  这个包到达手机的时间（第一拍拿它当时间锚点）
     * @return true = 这一拍被收下；false = 因为太短/太长/sqi=0 被丢掉（计入 {@link #dropped()}）
     */
    public boolean add(int rriMs, int sqi, long packetAtMs) {
        if (sqi == 0 || rriMs < MIN_RRI_MS || rriMs > MAX_RRI_MS) {
            dropped++;
            return false;
        }
        long at = lastBeatAtMs < 0 ? packetAtMs : lastBeatAtMs + rriMs;
        if (firstBeatAtMs < 0) firstBeatAtMs = at;
        lastBeatAtMs = at;
        accepted++;
        sumRriMs += rriMs;
        window.addLast(new Beat(at, rriMs));
        trim();
        return true;
    }

    /** 丢弃比窗口更老的拍；永远至少留一拍，这样窗口里不会空。 */
    private void trim() {
        while (window.size() > 1 && lastBeatAtMs - window.peekFirst().atMs > windowMs) {
            window.removeFirst();
        }
    }

    /** 收下的总拍数（整次测量，不随窗口收缩）。 */
    public int beats() { return accepted; }

    /** 被丢掉的拍数（sqi=0 或间期离谱）。 */
    public int dropped() { return dropped; }

    /** 窗口里还剩几拍（用来算 {@link #bpm()}）。 */
    public int beatsInWindow() { return window.size(); }

    /** 第一拍的时刻（毫秒）；还没拍 = -1。 */
    public long firstBeatAtMs() { return firstBeatAtMs; }

    /** 最后一拍的时刻（毫秒）；还没拍 = -1。 */
    public long lastBeatAtMs() { return lastBeatAtMs; }

    /** 最后一拍的间期；还没拍 = -1。 */
    public int lastRriMs() {
        Beat b = window.peekLast();
        return b == null ? -1 : b.rriMs;
    }

    /** 窗口里的平均间期；还没拍 = -1。 */
    public int avgRriMs() {
        if (window.isEmpty()) return -1;
        int sum = 0;
        for (Beat b : window) sum += b.rriMs;
        return (int) Math.round(sum / (double) window.size());
    }

    /** 「此刻大概多少」：窗口里的平均心率；还没拍 = 0。 */
    public int bpm() { return bpmOf(avgRriMs()); }

    /** 这次测量从头到现在的平均心率；还没拍 = 0。 */
    public int avgBpm() {
        if (accepted == 0) return 0;
        return bpmOf((int) Math.round(sumRriMs / (double) accepted));
    }

    /** 只看最后一拍的心率（跳得快，适合做动画，不适合当数字给她看）；还没拍 = 0。 */
    public int instantBpm() { return bpmOf(lastRriMs()); }

    /** 最后一拍离 {@code nowMs} 多久；还没拍 = -1。 */
    public long lagMs(long nowMs) {
        return lastBeatAtMs < 0 ? -1L : Math.max(0L, nowMs - lastBeatAtMs);
    }

    /** 「当前心率」那个窗口实际覆盖了多长时间（毫秒）；还没拍 = 0。 */
    public int windowSpanMs() {
        Beat first = window.peekFirst();
        if (first == null || lastBeatAtMs < 0) return 0;
        return (int) Math.max(0L, lastBeatAtMs - first.atMs);
    }

    /** 构造时给的窗口长度（毫秒）。 */
    public int windowMs() { return windowMs; }

    /** 清空，准备下一次测量。 */
    public void reset() {
        window.clear();
        accepted = 0;
        dropped = 0;
        sumRriMs = 0;
        firstBeatAtMs = -1L;
        lastBeatAtMs = -1L;
    }

    /**
     * 间期 → 心率。{@code 60000 / rri}，四舍五入；间期非法时返回 0（调用方把它当「还没有数据」，
     * 不要当 0 bpm 显示）。
     */
    public static int bpmOf(int rriMs) {
        if (rriMs <= 0) return 0;
        return (int) Math.round(60000.0 / rriMs);
    }
}
