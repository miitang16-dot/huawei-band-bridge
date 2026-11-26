package io.github.bandbridge.core.proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 老款手环（比如 Band 7）的睡眠：把基础睡眠段（0x07/0x0D，每段 = 类型 + 开始 + 分钟数）拼成「一晚一晚」，
 * 输出跟 {@code TruSleepSync} 一样形状的 JSON，这样健康页和 MCP 的 {@code band_sleep} 不用改就能显示。
 *
 * <ul>
 *   <li>段类型（GB HuaweiSampleProvider.RawTypes）：0x07 深睡 ⇒ 码 3；0x06 浅睡 ⇒ 码 1；其它也当浅睡（码 1）。</li>
 *   <li>相邻两段空档 ≤ {@link #MAX_GAP_SEC} 算同一觉，空档记成清醒（码 4）；更长就是另一觉（小睡由 SleepDay 区分）。</li>
 *   <li>老款手环没有快速眼动 / 睡眠分 / 睡眠心率这些，字段填 0；{@code src = "basic"} 标明来源。</li>
 * </ul>
 * 纯逻辑，不碰 Android，离线自测见 LegacySleepNightsSelfTest。
 */
public final class LegacySleepNights {

    /** 两段之间空多久还算同一觉（秒） */
    public static final long MAX_GAP_SEC = 60L * 60L;

    /** 一段：开始 / 结束（秒）+ 手环原始类型 */
    public static final class Seg {
        public final long start, end;
        public final int type;
        public Seg(long start, long end, int type) { this.start = start; this.end = end; this.type = type; }
    }

    private LegacySleepNights() {}

    /** 手环原始类型 → 健康页用的阶段码（1 浅 / 2 REM / 3 深 / 4 醒） */
    public static int codeOf(int rawType) { return rawType == 0x07 ? 3 : 1; }

    public static List<JSONObject> build(List<Seg> in) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (in == null || in.isEmpty()) return out;
        List<Seg> segs = new ArrayList<>();
        for (Seg s : in) if (s != null && s.start > 0 && s.end > s.start) segs.add(s);
        Collections.sort(segs, (a, b) -> Long.compare(a.start, b.start));

        List<Seg> night = new ArrayList<>();
        long nightEnd = 0;
        for (Seg s : segs) {
            if (!night.isEmpty() && s.start - nightEnd > MAX_GAP_SEC) { out.add(nightJson(night)); night.clear(); }
            night.add(s);
            nightEnd = night.size() == 1 ? s.end : Math.max(nightEnd, s.end);
        }
        if (!night.isEmpty()) out.add(nightJson(night));
        return out;
    }

    private static JSONObject nightJson(List<Seg> night) throws Exception {
        long fall = night.get(0).start, wake = fall;
        for (Seg s : night) wake = Math.max(wake, s.end);

        // 一分钟一格铺出来：先全标清醒，再按段覆盖（重叠时深睡优先）
        int n = (int) ((wake - fall + 59) / 60);
        int[] min = new int[n];
        for (int i = 0; i < n; i++) min[i] = 4;
        for (Seg s : night) {
            int code = codeOf(s.type);
            int a = (int) ((s.start - fall) / 60), b = (int) Math.min(n, (s.end - fall + 59) / 60);
            for (int i = Math.max(0, a); i < b; i++) if (min[i] == 4 || code == 3) min[i] = code;
        }

        JSONArray runs = new JSONArray();
        Map<Integer, Integer> byCode = new TreeMap<>();
        int rc = min[0], rn = 0;
        long rs = fall;
        for (int i = 0; i < n; i++) {
            byCode.put(min[i], (byCode.containsKey(min[i]) ? byCode.get(min[i]) : 0) + 1);
            if (min[i] == rc) { rn++; continue; }
            runs.put(new JSONArray().put(rs * 1000L).put(rn).put(rc));
            rs = fall + i * 60L; rc = min[i]; rn = 1;
        }
        runs.put(new JSONArray().put(rs * 1000L).put(rn).put(rc));

        int wakeCount = 0;
        for (int i = 1; i < n; i++) if (min[i] == 4 && min[i - 1] != 4) wakeCount++;

        JSONObject bc = new JSONObject();
        for (Map.Entry<Integer, Integer> e : byCode.entrySet()) bc.put(String.valueOf(e.getKey()), e.getValue());

        JSONObject o = new JSONObject();
        o.put("src", "basic");
        o.put("fall", fall * 1000L);
        o.put("wake", wake * 1000L);
        o.put("bed", 0L);
        o.put("rise", 0L);
        o.put("score", 0);
        o.put("deepPart", 0);
        o.put("wakeCount", wakeCount);
        o.put("efficiency", 0);
        o.put("latency", 0);
        o.put("minHr", 0); o.put("maxHr", 0); o.put("avgHr", 0);
        o.put("minSpo", 0); o.put("maxSpo", 0); o.put("avgSpo", 0);
        o.put("avgBreath", 0); o.put("avgHrv", 0);
        o.put("runs", runs);
        o.put("byCode", bc);
        return o;
    }
}
