package io.github.bandbridge.core.proto;

import io.github.bandbridge.core.testing.T;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 自测 {@link LegacySleepNights}：老款手环的基础睡眠段 → 一晚一晚（跟 TruSleep 一样的 JSON 形状）。 */
public final class LegacySleepNightsSelfTest {

    private LegacySleepNightsSelfTest() {}

    private static final long T0 = 1764000000L;   // 某天 00:00 左右，秒

    public static void run() throws Exception {
        T.group("LegacySleepNights（Band 7 基础睡眠拼成一晚）");

        T.eq("空输入 ⇒ 没有晚", 0, LegacySleepNights.build(new ArrayList<>()).size());
        T.eq("0x07 ⇒ 深睡 3", 3, LegacySleepNights.codeOf(0x07));
        T.eq("0x06 ⇒ 浅睡 1", 1, LegacySleepNights.codeOf(0x06));

        // 一晚：浅 60 分 → 醒 10 分（空档）→ 深 30 分 → 浅 20 分；早上 2 小时后小睡 15 分
        List<LegacySleepNights.Seg> in = new ArrayList<>();
        in.add(new LegacySleepNights.Seg(T0, T0 + 60 * 60, 0x06));
        in.add(new LegacySleepNights.Seg(T0 + 70 * 60, T0 + 100 * 60, 0x07));
        in.add(new LegacySleepNights.Seg(T0 + 100 * 60, T0 + 120 * 60, 0x06));
        in.add(0, in.remove(2));   // 打乱顺序，build 要自己排
        long nap0 = T0 + 120 * 60 + 2 * 3600;
        in.add(new LegacySleepNights.Seg(nap0, nap0 + 15 * 60, 0x06));

        List<JSONObject> out = LegacySleepNights.build(in);
        T.eq("夜里一觉 + 一次小睡 = 2 条", 2, out.size());
        JSONObject n = out.get(0);
        T.eq("入睡 = 第一段开始（毫秒）", T0 * 1000L, n.optLong("fall", -1));
        T.eq("醒来 = 最后一段结束", (T0 + 120 * 60) * 1000L, n.optLong("wake", -1));
        JSONObject bc = n.optJSONObject("byCode");
        T.eq("浅睡 80 分", 80, bc.optInt("1", -1));
        T.eq("深睡 30 分", 30, bc.optInt("3", -1));
        T.eq("中间空档 10 分记清醒", 10, bc.optInt("4", -1));
        T.eq("醒来 1 次", 1, n.optInt("wakeCount", -1));
        T.eq("来源标 basic", "basic", n.optString("src"));
        JSONArray runs = n.optJSONArray("runs");
        T.eq("4 段：浅 / 醒 / 深 / 浅", 4, runs.length());
        T.eq("第 3 段是深睡", 3, ((JSONArray) runs.get(2)).get(2));
        T.eq("第 3 段 30 分", 30, ((JSONArray) runs.get(2)).get(1));
        T.eq("小睡 15 分", 15, out.get(1).optJSONObject("byCode").optInt("1", -1));
    }
}
