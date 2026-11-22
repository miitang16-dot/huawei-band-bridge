package io.github.bandbridge.core.proto.hr;

import io.github.bandbridge.core.testing.T;

/**
 * 自测 {@link RriBeats}：间期 → 心率、拍的时间累加、窗口收缩、脏数据过滤。
 *
 * <p>这些用例是照着压力校准那次真机数据（60 秒 85 拍、间期 477~969 ms）取的量级，
 * 以及 BLE 上常见的坏点（sqi=0、漏拍导致的 3 秒长间隔、双倍拍导致的 200 ms 短间隔）。</p>
 */
public final class RriBeatsSelfTest {

    private RriBeatsSelfTest() {}

    public static void run() throws Exception {
        groupBpmOf();
        groupBeatsAndTimes();
        groupRejectsNoise();
        groupWindow();
        groupEmpty();
        groupFirstBeatAnchor();
        groupDropBreakdown();
        groupReset();
    }

    private static void groupBpmOf() {
        T.group("RriBeats.bpmOf(间期 → 心率)");
        T.eq("800 ms", 75, RriBeats.bpmOf(800));
        T.eq("1000 ms", 60, RriBeats.bpmOf(1000));
        T.eq("600 ms", 100, RriBeats.bpmOf(600));
        T.eq("534 ms（真机最小值）", 112, RriBeats.bpmOf(534));
        T.eq("969 ms（真机最大值）", 62, RriBeats.bpmOf(969));
        T.eq("250 ms = 240 bpm", 240, RriBeats.bpmOf(RriBeats.MIN_RRI_MS));
        T.eq("3000 ms = 20 bpm", 20, RriBeats.bpmOf(RriBeats.MAX_RRI_MS));
        T.eq("0 当没有数据", 0, RriBeats.bpmOf(0));
        T.eq("负数当没有数据", 0, RriBeats.bpmOf(-5));
    }

    private static void groupBeatsAndTimes() {
        T.group("收拍 / 时间累加 / 三个心率口径");
        RriBeats b = new RriBeats();
        T.yes("800 ms 被收下", b.add(800, 3, 1_000L));
        T.eq("beats", 1, b.beats());
        T.eq("lastRri", 800, b.lastRriMs());
        T.eq("bpm = 75", 75, b.bpm());
        T.eq("instantBpm = 75", 75, b.instantBpm());
        T.eq("avgBpm = 75", 75, b.avgBpm());
        T.eq("第一拍用包到达时刻", 1_000L, b.firstBeatAtMs());
        T.eq("lastBeatAt", 1_000L, b.lastBeatAtMs());

        T.yes("第二拍", b.add(1_000, 2, 1_900L));
        T.yes("第三拍", b.add(1_200, 1, 3_200L));
        T.eq("三拍时刻累加 = 1000+1000+1200", 3_200L, b.lastBeatAtMs());
        T.eq("beats", 3, b.beats());
        T.eq("窗口均值 (800+1000+1200)/3=1000 → 60", 60, b.bpm());
        T.eq("全程均值也是 60", 60, b.avgBpm());
        T.eq("instantBpm 用最后一拍 1200 → 50", 50, b.instantBpm());
        T.eq("lag 在最后一拍时刻 = 0", 0L, b.lagMs(3_200L));
        T.eq("lag 晚 400 ms", 400L, b.lagMs(3_600L));
        T.eq("窗口跨度 2200", 2_200, b.windowSpanMs());
    }

    private static void groupRejectsNoise() {
        T.group("脏拍过滤（sqi=0 / 间期离谱）");
        RriBeats b = new RriBeats();
        T.no("sqi=0 丢掉", b.add(800, 0, 1_000L));
        T.eq("dropped 记一笔", 1, b.dropped());
        T.eq("beats 没变", 0, b.beats());
        T.no("249 ms（漏拍造成的双倍拍）丢掉", b.add(249, 2, 1_000L));
        T.no("3001 ms（断拍）丢掉", b.add(3001, 2, 1_000L));
        T.no("0 ms 丢掉", b.add(0, 2, 1_000L));
        T.no("-100 ms 丢掉", b.add(-100, 2, 1_000L));
        T.eq("dropped 一共 5", 5, b.dropped());
        T.yes("250 ms（240 bpm，运动里会有）能收", b.add(250, 1, 5_000L));
        T.yes("3000 ms（20 bpm）能收", b.add(3000, 1, 8_000L));
        T.eq("两拍都算进", 2, b.beats());
    }

    private static void groupWindow() {
        T.group("滑动窗口（只留最近一段算 bpm）");
        RriBeats b = new RriBeats(2_000);
        for (int i = 0; i < 4; i++) b.add(800, 2, 1_000L);   // 时刻 1000 1800 2600 3400
        T.eq("四拍都收下（beats 不缩）", 4, b.beats());
        T.eq("窗口里只留 3 拍（跨度 1600 ≤ 2000）", 3, b.beatsInWindow());
        T.eq("窗口跨度 1600", 1_600, b.windowSpanMs());
        T.eq("bpm 用窗口里的拍 = 75", 75, b.bpm());
        T.eq("avgBpm 用全程 = 75", 75, b.avgBpm());
        T.eq("windowMs 就是传进去的", 2_000, b.windowMs());

        RriBeats tiny = new RriBeats(10);
        T.eq("窗口小于 1 秒按 1 秒算", 1_000, tiny.windowMs());

        RriBeats slow = new RriBeats(2_000);
        slow.add(1_000, 2, 0L);      // t=0
        slow.add(1_000, 2, 0L);      // t=1000
        slow.add(1_000, 2, 0L);      // t=2000
        T.eq("2000 毫秒窗口正好留 3 拍", 3, slow.beatsInWindow());
        slow.add(1_000, 2, 0L);      // t=3000
        T.eq("再来一拍把最老的挤掉", 3, slow.beatsInWindow());
        T.eq("bpm 仍然是 60", 60, slow.bpm());
    }

    private static void groupEmpty() {
        T.group("还没有拍的时候（不要算出 0 bpm 当数据）");
        RriBeats b = new RriBeats();
        T.eq("beats", 0, b.beats());
        T.eq("bpm = 0（表示没有）", 0, b.bpm());
        T.eq("avgBpm = 0", 0, b.avgBpm());
        T.eq("instantBpm = 0", 0, b.instantBpm());
        T.eq("lastRri = -1", -1, b.lastRriMs());
        T.eq("avgRri = -1", -1, b.avgRriMs());
        T.eq("lastBeatAt = -1", -1L, b.lastBeatAtMs());
        T.eq("lag = -1", -1L, b.lagMs(1_000L));
        T.eq("windowSpan = 0", 0, b.windowSpanMs());
    }

    private static void groupFirstBeatAnchor() {
        T.group("第一拍的时间锚点（包到达时刻）");
        RriBeats b = new RriBeats();
        b.add(700, 3, 5_000L);
        T.eq("第一拍 = 包里到达的时刻", 5_000L, b.lastBeatAtMs());
        T.eq("lag 在 5300 时 = 300", 300L, b.lagMs(5_300L));
        b.add(700, 3, 9_999L);          // 第二拍的时间不看到达时刻，看累加
        T.eq("第二拍 = 5000+700", 5_700L, b.lastBeatAtMs());
        T.eq("lag 在 6000 时 = 300", 300L, b.lagMs(6_000L));
    }

    private static void groupDropBreakdown() {
        T.group("丢弃分两类（sqi=0 / 间期离谱），总数 = 两类之和");
        RriBeats b = new RriBeats();
        b.add(800, 0, 1_000L);      // sqi=0 → sqi0
        b.add(900, 0, 2_000L);      // sqi=0 → sqi0
        b.add(200, 2, 3_000L);      // 太短 → range
        b.add(3_500, 2, 4_000L);    // 太长 → range
        T.eq("sqi0 两条", 2, b.droppedSqi0());
        T.eq("range 两条", 2, b.droppedRange());
        T.eq("dropped = 4", 4, b.dropped());
        T.eq("dropped = sqi0 + range", b.droppedSqi0() + b.droppedRange(), b.dropped());
        T.yes("好拍还是收", b.add(800, 3, 5_000L));
        T.eq("收下 1 拍", 1, b.beats());

        /* sqi=0 优先归类：间期也离谱时只记 sqi0 一次，别双重计数 */
        RriBeats p = new RriBeats();
        p.add(100, 0, 1_000L);
        T.eq("sqi=0 且间期离谱 → 只算 sqi0", 1, p.droppedSqi0());
        T.eq("range 不重复计", 0, p.droppedRange());
        T.eq("dropped 也只 +1", 1, p.dropped());

        p.reset();
        T.eq("reset 清 sqi0", 0, p.droppedSqi0());
        T.eq("reset 清 range", 0, p.droppedRange());

        /* 真机那次的量级：279 收 / 337 丢（丢掉里几乎全是 sqi=0），这里验算分类不丢数 */
        RriBeats real = new RriBeats();
        for (int i = 0; i < 300; i++) real.add(800, 0, i * 800L);
        for (int i = 0; i < 37; i++) real.add(3_600, 2, i * 800L);
        T.eq("337 丢全在 sqi0", 300, real.droppedSqi0());
        T.eq("337 丢全在 range", 37, real.droppedRange());
        T.eq("合计 337", 337, real.dropped());
        for (int i = 0; i < 279; i++) real.add(800, 2, i * 800L);
        T.eq("收 279", 279, real.beats());
        T.eq("收下 + 丢掉 = 616 条样本，一条没漏", 616, real.beats() + real.dropped());
    }

    private static void groupReset() {
        T.group("reset 之后重新开始");
        RriBeats b = new RriBeats();
        b.add(800, 2, 1_000L);
        b.add(0, 2, 1_000L);            // dropped=1
        b.reset();
        T.eq("beats 清零", 0, b.beats());
        T.eq("dropped 清零", 0, b.dropped());
        T.eq("beatsInWindow 清零", 0, b.beatsInWindow());
        T.eq("lastBeatAt 回到 -1", -1L, b.lastBeatAtMs());
        T.yes("还能继续收", b.add(900, 2, 2_000L));
        T.eq("新窗口 bpm = 67", 67, b.bpm());   // 60000/900 = 66.67 → 67
    }
}
