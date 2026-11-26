package io.github.bandbridge.core.proto.hr;

import java.util.Arrays;

/**
 * 把一包 RRI 变成「逐拍回放」的时间表：给 UI 用，按真实间隔把刚收到的这几拍再演一遍。
 *
 * <p>为什么要「回放」而不是「实时跳」：真机测过（Band 10，0.1.8）手环是**攒几秒再成批发**的
 * —— 约 4 秒一包、每包十几个样本、`lag` 5.9 秒。所以这些拍是「几秒前」的，UI 只能按包里的
 * 间期把它们演出来，**不能当作「此刻这一拍」**。这条边界写在 ROADMAP 的 P1 里。</p>
 *
 * <p>三个纯函数（没有 Android、没有状态，离线自测覆盖）：</p>
 * <ul>
 *   <li>{@link #offsetsMs(int[])} —— 相对时刻：第 0 拍在 0，之后每隔「前一拍的间期」一拍；
 *       第 i 拍的间期就是 {@code rris[i]}（0x19 的样本语义：这个间期结束于这一拍）。</li>
 *   <li>{@link #bpmPerPulse(int[])} —— 每一拍对应的瞬时心率（{@code 60000 / rri}，非法间期给 0）。</li>
 *   <li>{@link #capNewest(int[], int)} —— 只保留最近的若干拍，让回放总时长不超过上限；
 *       否则一包 14 拍、约 4 秒一包，回放会越排越久、越拖越后。</li>
 * </ul>
 *
 * <p>回放时长怎么定：{@link #DEFAULT_HORIZON_MS} 跟实测的包间隔（约 4 秒）对齐 ——
 * 新包到的时候，上一包回放差不多演完，既不断档也不会积压。</p>
 */
public final class BeatReplay {

    /** 默认最多排多远的回放（毫秒）：跟真机上「约 4 秒一包」对齐。 */
    public static final int DEFAULT_HORIZON_MS = 4000;
    /** 再短的间期也不可能（跟 {@link RriBeats#MIN_RRI_MS} 一致）；比这短的样本在采集时已被丢掉。 */
    public static final int MIN_PULSE_MS = RriBeats.MIN_RRI_MS;

    private BeatReplay() { }

    /**
     * 每一拍的相对时刻（毫秒），第 0 拍 = 0。
     *
     * <p>第 i 拍的间期是 {@code rris[i]}：真实的心跳间隔是「结束于这一拍」的，所以第 i 拍
     * （i ≥ 1）出现在第 i-1 拍之后 {@code rris[i]} 毫秒。{@code rris[0]} 是这一包第一拍自己的间期，
     * 它对应的时间在包到达之前，不参与排程。</p>
     *
     * <p>防御：null / 空 → 空数组；负数或 0 的间期按 0 处理（时刻不倒退）。</p>
     */
    public static int[] offsetsMs(int[] rris) {
        if (rris == null || rris.length == 0) return new int[0];
        int[] out = new int[rris.length];
        out[0] = 0;
        for (int i = 1; i < rris.length; i++) {
            out[i] = out[i - 1] + Math.max(0, rris[i]);
        }
        return out;
    }

    /**
     * 每一拍对应的瞬时心率（{@code 60000 / rri}，四舍五入）；间期非法时给 0。
     * 长度与 {@link #offsetsMs(int[])} 一一对应（同一个 {@code rris} 输入）。
     */
    public static int[] bpmPerPulse(int[] rris) {
        if (rris == null || rris.length == 0) return new int[0];
        int[] out = new int[rris.length];
        for (int i = 0; i < rris.length; i++) out[i] = RriBeats.bpmOf(rris[i]);
        return out;
    }

    /**
     * 只保留最近的若干拍，使回放总时长（{@code offsetsMs} 的最后一项）不超过 {@code maxSpanMs}。
     *
     * <p>从最新的往前收，收到放不下为止；至少保留最新的一拍（否则 UI 没东西可演）。
     * 上限 ≤ 0 时原样返回（拷贝一份，避免调用方被共享数组坑到）。</p>
     */
    public static int[] capNewest(int[] rris, int maxSpanMs) {
        if (rris == null || rris.length == 0) return new int[0];
        if (maxSpanMs <= 0) return Arrays.copyOf(rris, rris.length);
        // 从最新一拍开始往前收：把「第 i-1 拍 → 第 i 拍」的间期 rris[i] 算进总时长
        int start = rris.length - 1;
        int span = 0;
        while (start > 0) {
            int add = Math.max(0, rris[start]);
            if (span + add > maxSpanMs) break;
            span += add;
            start--;
        }
        return Arrays.copyOfRange(rris, start, rris.length);
    }
}
