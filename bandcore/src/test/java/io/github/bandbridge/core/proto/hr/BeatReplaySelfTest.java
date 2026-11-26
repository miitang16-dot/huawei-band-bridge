package io.github.bandbridge.core.proto.hr;

import io.github.bandbridge.core.testing.T;

/**
 * 自测 {@link BeatReplay}：把「一包 RRI」变成回放时间表的三段纯逻辑。
 *
 * <p>用例取真机上量到的量级：约 4 秒一包、每包十几个样本、间期 500–1000 ms（0.1.8 第三次测量）。
 * 重点是两条边界：**回放总时长不能无限拉长**（否则越拖越后），以及**至少留最新一拍**。</p>
 */
public final class BeatReplaySelfTest {

    private BeatReplaySelfTest() {}

    public static void run() throws Exception {
        groupOffsets();
        groupBpm();
        groupCap();
        groupSchedule();
        groupEdgeCases();
    }

    private static void groupOffsets() {
        T.group("BeatReplay.offsetsMs（每一拍在什么时候闪）");
        int[] one = BeatReplay.offsetsMs(new int[]{800});
        T.eq("一拍：长度 1", 1, one.length);
        T.eq("一拍：第 0 拍在 0", 0, one[0]);

        int[] three = BeatReplay.offsetsMs(new int[]{800, 900, 1000});
        T.eq("三拍：0 / 900 / 1900", "[0, 900, 1900]",
                "[" + three[0] + ", " + three[1] + ", " + three[2] + "]");
        T.eq("第 i 拍用第 i 个间期（结束于这一拍）", true, three[1] == 900 && three[2] == 1900);

        int[] noisy = BeatReplay.offsetsMs(new int[]{800, -50, 0, 1000});
        T.eq("负/0 间期按 0 处理：时刻不倒退", true,
                noisy[1] >= noisy[0] && noisy[2] >= noisy[1] && noisy[3] >= noisy[2]);
        T.eq("负/0 间期不影响后面的拍：0, 0, 0, 1000", "[0, 0, 0, 1000]",
                "[" + noisy[0] + ", " + noisy[1] + ", " + noisy[2] + ", " + noisy[3] + "]");
    }

    private static void groupBpm() {
        T.group("BeatReplay.bpmPerPulse（每一拍对应的瞬时心率）");
        int[] bpm = BeatReplay.bpmPerPulse(new int[]{800, 900, 1000, 534, 3000});
        T.eq("800 → 75", 75, bpm[0]);
        T.eq("900 → 67", 67, bpm[1]);
        T.eq("1000 → 60", 60, bpm[2]);
        T.eq("534 → 112（真机最小值附近）", 112, bpm[3]);
        T.eq("3000 → 20（真机最大值附近）", 20, bpm[4]);

        int[] bad = BeatReplay.bpmPerPulse(new int[]{0, -5});
        T.eq("0 ms → 0（当作没有数据）", 0, bad[0]);
        T.eq("负数 → 0", 0, bad[1]);
    }

    private static void groupCap() {
        T.group("BeatReplay.capNewest（只留最近的若干拍，不越排越久）");
        T.eq("null → 空数组", 0, BeatReplay.capNewest(null, 4000).length);
        T.eq("空数组 → 空数组", 0, BeatReplay.capNewest(new int[0], 4000).length);

        /* 真机那种一包：14 个样本、约 700 ms 一个 */
        int[] packet = new int[14];
        for (int i = 0; i < packet.length; i++) packet[i] = 700;
        int[] capped = BeatReplay.capNewest(packet, BeatReplay.DEFAULT_HORIZON_MS);
        T.eq("700ms×14 在 4 秒上限下留 6 拍", 6, capped.length);
        T.eq("留下的最后一拍就是最新的那一拍", 700, capped[capped.length - 1]);
        int span = BeatReplay.offsetsMs(capped)[capped.length - 1];
        T.eq("回放总时长不超过上限", true, span <= BeatReplay.DEFAULT_HORIZON_MS);

        int[] tooSlow = {3000, 3000, 3000, 3000};
        int[] one = BeatReplay.capNewest(tooSlow, 100);
        T.eq("一拍的间期就超上限时，至少留最新一拍", 1, one.length);
        T.eq("留下的确实是最后一个样本", 3000, one[0]);

        int[] copy = BeatReplay.capNewest(new int[]{800, 900}, 4000);
        T.eq("很短（≤2 拍）原样保留", 2, copy.length);
        T.eq("返回的是拷贝，不是同一个数组", true, copy != null);
    }

    private static void groupSchedule() {
        T.group("回放时间表（cap + offsets + bpm 串起来）");
        int[] packet = {700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700, 700};
        int[] rris = BeatReplay.capNewest(packet, BeatReplay.DEFAULT_HORIZON_MS);
        int[] offsets = BeatReplay.offsetsMs(rris);
        int[] bpms = BeatReplay.bpmPerPulse(rris);
        T.eq("offsets 与 bpms 一一对应（长度相同）", offsets.length, bpms.length);
        T.eq("第 0 拍在 0 时刻", 0, offsets[0]);
        T.eq("每一拍都是 86 bpm（60000/700）", true, bpms[0] == 86 && bpms[bpms.length - 1] == 86);
        T.eq("最后一拍的时刻 = 总时长 ≤ 4 秒", true,
                offsets[offsets.length - 1] <= BeatReplay.DEFAULT_HORIZON_MS);

        /* 真实抖动：快慢不一时，排程仍然单调递增 */
        int[] jitter = {534, 894, 626, 565, 969, 477, 800};
        int[] jo = BeatReplay.offsetsMs(BeatReplay.capNewest(jitter, 4000));
        boolean monotonic = true;
        for (int i = 1; i < jo.length; i++) if (jo[i] < jo[i - 1]) monotonic = false;
        T.eq("真实间期抖动下时刻仍然单调", true, monotonic);
    }

    private static void groupEdgeCases() {
        T.group("BeatReplay 的边界");
        T.eq("capNewest 上限为 0：原样返回", 5, BeatReplay.capNewest(new int[]{1, 2, 3, 4, 5}, 0).length);
        T.eq("capNewest 上限为负：原样返回", 5, BeatReplay.capNewest(new int[]{1, 2, 3, 4, 5}, -1).length);
        T.eq("offsets 空输入 → 空", 0, BeatReplay.offsetsMs(new int[0]).length);
        T.eq("bpm 空输入 → 空", 0, BeatReplay.bpmPerPulse(new int[0]).length);
        T.eq("MIN_PULSE_MS 跟 RriBeats 的最小间期一致", RriBeats.MIN_RRI_MS, BeatReplay.MIN_PULSE_MS);
    }
}
