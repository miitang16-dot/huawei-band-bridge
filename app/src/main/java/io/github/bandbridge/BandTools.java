package io.github.bandbridge;

import io.github.bandbridge.core.BandSession;
import io.github.bandbridge.core.data.HrLiveSession;
import io.github.bandbridge.core.proto.HuaweiStressParser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * The MCP tools: read what {@link BandSession} stored on the phone and answer in compact JSON.
 * Rules (kept from the app this came from):
 *  - gaps stay gaps: a minute without a reading is "no data", never 0 and never filled in;
 *  - every answer says when it was recorded and at what resolution;
 *  - "now" means "latest record" (the band stores ~1 sample/min). A live beat-by-beat measurement
 *    is a separate thing: {@code band_hr_live} (service 0x19 RRI stream) — never mix the two up.
 */
final class BandTools {

    private final BandSession s;
    BandTools(BandSession s) { this.s = s; }

    /* ── tool list (MCP tools/list) ─────────────────────────────────────────── */

    static JSONArray list() throws Exception {
        JSONArray a = new JSONArray();
        a.put(tool("band_status", "Connection state of the Huawei band: connected or not, model, battery, time of the newest data, whether stress is calibrated.", props()));
        a.put(tool("band_now", "Latest readings: heart rate, SpO2, resting HR, today's steps, latest stress and emotion, last night's sleep. "
                + "'Latest' = newest record (the band stores ~1 sample per minute; each value comes with the time it was recorded).",
                props().put("what", str("Optional: hr | spo | steps | rest | stress | emotion | sleep. Omit for everything."))));
        a.put(tool("band_day", "Heart rate / SpO2 / steps for one day or a time window, with min/max/avg and a per-interval series. "
                + "Long windows are coarsened (step_min in the answer); empty intervals mean no reading.",
                props().put("date", str("YYYY-MM-DD, default today"))
                        .put("from", str("Optional HH:MM start (local time)"))
                        .put("to", str("Optional HH:MM end (local time)"))));
        a.put(tool("band_sleep", "TruSleep for the night that ended on the given date: fall asleep / wake time, score, minutes of deep / light / REM / awake, "
                + "wake-ups, sleep heart rate and SpO2, and the stage timeline. Naps that ended the same day are listed separately (naps / napMin; dayTotalMin = night + naps).",
                props().put("date", str("YYYY-MM-DD of the wake-up day, default today"))));
        a.put(tool("band_stress", "Stress scores the band measured on a day (1-99: 1-29 relaxed, 30-59 normal, 60-79 medium, 80-99 high). "
                + "Needs a one-time calibration in the app.",
                props().put("date", str("YYYY-MM-DD, default today"))));
        a.put(tool("band_emotion", "Emotion records the band measured on a day: valence (0-100, higher = more pleasant), arousal (0-100) and the band's state code "
                + "(1/2/3 ≈ unpleasant / calm / pleasant, inferred from the data, not documented by Huawei).",
                props().put("date", str("YYYY-MM-DD, default today"))));
        a.put(tool("band_hr_live", "Beat-by-beat heart rate from the band's RRI stream (service 0x19): one value per beat, inside a limited measurement window. "
                + "Omit `seconds` to read the latest/ongoing measurement; pass `seconds` (5-180) to START one — it takes that long and the band's optical sensor runs during it. "
                + "Answer fields: ok, running, bpm, bpm_avg, beats, window_ms, lag_ms, last_beat_ms_ago, started_at (epoch ms), device, unverified. "
                + "Failure: {ok:false, running:false, error:'band_not_connected'|'hr_live_not_running'|'bad_request'}. "
                + "When nothing is being measured it says so (hr_live_not_running) — do NOT invent or fill in numbers. "
                + "bpm/bpm_avg/beats = 0 and lag_ms/last_beat_ms_ago = -1 mean 'no beat yet'. "
                + "lag_ms = packet arrival minus the newest beat (small = really 'now', large = catching up on older beats); "
                + "window_ms = how long this measurement has been running.",
                props().put("seconds", num("Optional 5-180: start a measurement of this many seconds. Omit to only read the latest one."))));
        return a;
    }

    private static JSONObject props() throws Exception { return new JSONObject(); }
    private static JSONObject str(String d) throws Exception { return new JSONObject().put("type", "string").put("description", d); }
    private static JSONObject num(String d) throws Exception { return new JSONObject().put("type", "integer").put("description", d); }
    private static JSONObject tool(String name, String desc, JSONObject props) throws Exception {
        return new JSONObject().put("name", name).put("description", desc)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false));
    }

    /* ── dispatch (MCP tools/call) ──────────────────────────────────────────── */

    JSONObject call(String name, JSONObject args) throws Exception {
        if (args == null) args = new JSONObject();
        switch (name) {
            case "band_status": return status();
            case "band_now": return now(args.optString("what", "all"));
            case "band_day": return day(args.optString("date", ""), args.optString("from", ""), args.optString("to", ""));
            case "band_sleep": return sleep(args.optString("date", ""));
            case "band_stress": return stress(args.optString("date", ""));
            case "band_emotion": return emotion(args.optString("date", ""));
            case "band_hr_live": return hrLive(args.optInt("seconds", 0));
            default: throw new IllegalArgumentException("unknown tool " + name);
        }
    }

    /* ── helpers ────────────────────────────────────────────────────────────── */

    private static String hhmm(long ms) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms); return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)); }
    private static String ymd(long ms) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(ms); return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)); }

    /** start of the given local day (ms); empty / bad = today */
    private static long dayStart(String date) {
        Calendar c = Calendar.getInstance();
        if (date != null && date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            String[] p = date.split("-");
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    private static long at(long d0, String hm, long def) {
        if (hm == null || !hm.matches("\\d{1,2}:\\d{2}")) return def;
        String[] p = hm.split(":");
        return d0 + (Integer.parseInt(p[0]) * 60L + Integer.parseInt(p[1])) * 60000L;
    }
    static String stressLevel(int v) { return v <= 0 ? "" : v < 30 ? "relaxed" : v < 60 ? "normal" : v < 80 ? "medium" : "high"; }
    static String emotionState(int c) { return c == 3 ? "pleasant" : c == 2 ? "calm" : c == 1 ? "unpleasant" : ""; }

    /* ── tools ─────────────────────────────────────────────────────────────── */

    private JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("connected", s.isReady());
        o.put("state", new String[]{"idle", "scanning", "authenticating", "connected"}[Math.max(0, Math.min(3, s.phase()))]);
        o.put("model", s.model());
        if (s.battery() >= 0) o.put("battery", s.battery());
        long last = s.samples().lastTs() * 1000L;
        if (last > 0) o.put("newestData", ymd(last) + " " + hhmm(last));
        HuaweiStressParser.StressData c = s.calibration();
        o.put("stressCalibrated", c != null);
        if (!s.isReady() && !s.lastError().isEmpty()) o.put("lastError", s.lastError());
        o.put("note", "Data is synced from the band about once a minute while the phone app is running.");
        return o;
    }

    private JSONObject now(String what) throws Exception {
        long nowMs = System.currentTimeMillis(), d0 = dayStart("");
        JSONObject r = s.samples().query((nowMs - 3600_000L) / 1000L, nowMs / 1000L);
        JSONObject today = s.samples().query(d0 / 1000L, nowMs / 1000L);
        JSONObject o = new JSONObject();
        o.put("asOf", hhmm(nowMs));
        boolean all = what == null || what.isEmpty() || "all".equals(what);
        JSONArray t = r.getJSONArray("t"), hr = r.getJSONArray("hr"), spo = r.getJSONArray("spo");
        if (all || "hr".equals(what)) {
            for (int i = t.length() - 1; i >= 0; i--) if (hr.getInt(i) > 0) { o.put("hr", new JSONObject().put("bpm", hr.getInt(i)).put("at", hhmm(t.getLong(i)))); break; }
            if (!o.has("hr")) o.put("hr", JSONObject.NULL);
        }
        if (all || "spo".equals(what)) {
            for (int i = t.length() - 1; i >= 0; i--) if (spo.getInt(i) > 0) { o.put("spo", new JSONObject().put("percent", spo.getInt(i)).put("at", hhmm(t.getLong(i)))); break; }
            if (!o.has("spo")) o.put("spo", JSONObject.NULL);
        }
        if (all || "steps".equals(what) || "rest".equals(what)) {
            JSONArray st = today.getJSONArray("steps"), rh = today.getJSONArray("rhr");
            int steps = 0, rest = 0;
            for (int i = 0; i < st.length(); i++) { steps += st.getInt(i); if (rh.getInt(i) > 0) rest = rh.getInt(i); }
            if (all || "steps".equals(what)) o.put("stepsToday", steps);
            if (all || "rest".equals(what)) o.put("restingHr", rest > 0 ? rest : JSONObject.NULL);
        }
        if (all || "stress".equals(what)) { JSONObject x = stress(""); JSONArray a = x.getJSONArray("records"); o.put("stress", a.length() > 0 ? a.getJSONObject(a.length() - 1) : JSONObject.NULL); }
        if (all || "emotion".equals(what)) { JSONObject x = emotion(""); JSONArray a = x.getJSONArray("records"); o.put("emotion", a.length() > 0 ? a.getJSONObject(a.length() - 1) : JSONObject.NULL); }
        if (all || "sleep".equals(what)) {
            JSONObject sl = sleep("");
            o.put("lastNight", sl.optBoolean("found") ? new JSONObject().put("asleep", sl.opt("asleep")).put("woke", sl.opt("woke"))
                    .put("totalMin", sl.opt("totalMin")).put("score", sl.opt("score")) : JSONObject.NULL);
        }
        o.put("note", "Values are the newest records, not live readings (the band stores ~1 sample/min; SpO2 is measured only a few times per hour). null = no record. "
                + "For a beat-by-beat measurement use band_hr_live.");
        return o;
    }

    private JSONObject day(String date, String fromHm, String toHm) throws Exception {
        long d0 = dayStart(date), d1 = d0 + 86400_000L - 1;
        long from = at(d0, fromHm, d0), to = Math.min(at(d0, toHm, d1), System.currentTimeMillis());
        JSONObject r = s.samples().query(from / 1000L, to / 1000L);
        JSONArray t = r.getJSONArray("t"), hr = r.getJSONArray("hr"), spo = r.getJSONArray("spo"), st = r.getJSONArray("steps");
        long spanMin = Math.max(1, (to - from) / 60000L);
        int step = spanMin <= 120 ? 1 : spanMin <= 360 ? 5 : spanMin <= 720 ? 10 : 15;
        int mn = 999, mx = 0, sum = 0, n = 0, smn = 999, ssum = 0, sn = 0, steps = 0;
        long b0 = from;
        JSONArray series = new JSONArray();
        int bHr = 0, bHrN = 0, bSpo = 0, bSpoN = 0, bSt = 0;
        int i = 0;
        for (long b = from; b <= to; b += step * 60000L) {
            long be = b + step * 60000L;
            bHr = bHrN = bSpo = bSpoN = bSt = 0;
            while (i < t.length() && t.getLong(i) < be) {
                if (t.getLong(i) >= b) {
                    int v = hr.getInt(i), sp = spo.getInt(i), stp = st.getInt(i);
                    if (v > 0) { bHr += v; bHrN++; mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; n++; }
                    if (sp > 0) { bSpo += sp; bSpoN++; smn = Math.min(smn, sp); ssum += sp; sn++; }
                    bSt += stp; steps += stp;
                }
                i++;
            }
            if (bHrN > 0 || bSpoN > 0 || bSt > 0) {
                JSONObject p = new JSONObject().put("t", hhmm(b));
                if (bHrN > 0) p.put("hr", Math.round(bHr / (float) bHrN));
                if (bSpoN > 0) p.put("spo", Math.round(bSpo / (float) bSpoN));
                if (bSt > 0) p.put("steps", bSt);
                series.put(p);
            }
        }
        JSONObject o = new JSONObject();
        o.put("date", ymd(d0)).put("from", hhmm(from)).put("to", hhmm(to)).put("step_min", step);
        o.put("hr", n > 0 ? new JSONObject().put("min", mn).put("max", mx).put("avg", Math.round(sum / (float) n)).put("samples", n) : JSONObject.NULL);
        o.put("spo", sn > 0 ? new JSONObject().put("min", smn).put("avg", Math.round(ssum / (float) sn)).put("samples", sn) : JSONObject.NULL);
        o.put("steps", steps);
        o.put("series", series);
        o.put("note", "series: one entry per " + step + " min with data (hr/spo averaged, steps summed); missing intervals had no reading. Narrow from/to for 1-minute detail.");
        return o;
    }

    private JSONObject sleep(String date) throws Exception {
        long d0 = dayStart(date);
        JSONObject q = s.truSleep().query(d0 - 18 * 3600_000L, d0 + 86400_000L);
        JSONArray nights = q.getJSONArray("nights");
        /* The night = the longest record that woke up on this day; the others are naps.
           (Picking the one that woke up last let a morning nap replace the whole night.) */
        SleepDay day = SleepDay.of(nights, d0, d0 + 86400_000L);
        JSONObject best = day == null ? null : day.main;
        JSONObject o = new JSONObject().put("date", ymd(d0));
        if (best == null) return o.put("found", false).put("note", "No TruSleep record ending on this day (band not worn, or not synced yet).");
        JSONObject bc = best.optJSONObject("byCode");
        int light = bc == null ? 0 : bc.optInt("1"), rem = bc == null ? 0 : bc.optInt("2"), deep = bc == null ? 0 : bc.optInt("3"), awake = bc == null ? 0 : bc.optInt("4");
        o.put("found", true);
        o.put("asleep", hhmm(best.optLong("fall"))).put("woke", hhmm(best.optLong("wake")));
        o.put("totalMin", day.mainMin);
        JSONArray naps = new JSONArray();
        for (JSONObject n : day.naps) naps.put(new JSONObject().put("asleep", hhmm(n.optLong("fall"))).put("woke", hhmm(n.optLong("wake"))).put("min", SleepDay.minutes(n)));
        o.put("naps", naps).put("napMin", day.napMin).put("dayTotalMin", day.totalMin());
        o.put("deepMin", deep).put("lightMin", light).put("remMin", rem).put("awakeMin", awake);
        o.put("score", best.optInt("score"));
        o.put("wakeCount", best.optInt("wakeCount"));
        o.put("sleepHr", new JSONObject().put("avg", best.optInt("avgHr")).put("min", best.optInt("minHr")));
        o.put("sleepSpo", new JSONObject().put("avg", best.optInt("avgSpo")).put("min", best.optInt("minSpo")));
        JSONArray runs = best.optJSONArray("runs"), tl = new JSONArray();
        String[] names = {"", "light", "rem", "deep", "awake"};
        if (runs != null) for (int k = 0; k < runs.length(); k++) {
            JSONArray rr = runs.getJSONArray(k);
            int code = rr.getInt(2);
            tl.put(new JSONObject().put("t", hhmm(rr.getLong(0))).put("min", rr.getInt(1)).put("stage", code >= 1 && code <= 4 ? names[code] : String.valueOf(code)));
        }
        o.put("timeline", tl);
        return o;
    }

    private JSONObject stress(String date) throws Exception {
        long d0 = dayStart(date);
        JSONArray recs = s.stress().query(d0, d0 + 86400_000L - 1).getJSONArray("recs");
        JSONArray out = new JSONArray();
        for (int k = 0; k < recs.length(); k++) {
            JSONObject r = recs.getJSONObject(k);
            int v = r.optJSONObject("v") == null ? 0 : r.getJSONObject("v").optInt("score");
            if (v <= 0) continue;
            out.put(new JSONObject().put("at", hhmm(r.optLong("end", r.optLong("start")))).put("score", v).put("level", stressLevel(v)));
        }
        JSONObject o = new JSONObject().put("date", ymd(d0)).put("records", out);
        if (out.length() == 0) o.put("note", s.calibration() == null ? "Stress is not calibrated yet: do the 1-minute calibration in the app first." : "No stress measurement on this day yet.");
        return o;
    }

    private JSONObject emotion(String date) throws Exception {
        long d0 = dayStart(date);
        JSONArray recs = s.emotion().query(d0, d0 + 86400_000L - 1).getJSONArray("recs");
        JSONArray out = new JSONArray();
        for (int k = 0; k < recs.length(); k++) {
            JSONObject r = recs.getJSONObject(k), v = r.optJSONObject("v");
            if (v == null) continue;
            JSONObject e = new JSONObject().put("at", hhmm(r.optLong("start")));
            if (v.has("500031347")) e.put("valence", Math.round(v.optDouble("500031347")));
            if (v.has("500031842")) e.put("arousal", Math.round(v.optDouble("500031842")));
            if (v.has("500031195")) { int c = (int) Math.round(v.optDouble("500031195")); e.put("state", emotionState(c)).put("code", c); }
            out.put(e);
        }
        return new JSONObject().put("date", ymd(d0)).put("records", out)
                .put("note", "state is inferred from the band's code (not documented by Huawei); valence/arousal are the raw 0-100 values.");
    }

    /**
     * 心跳直播（0x19 逐拍流）。
     *
     * <p><b>返回字段是契约，别改名</b>（Nexus 3.309 按这套字段解析）：
     * {@code ok / running / bpm / bpm_avg / beats / window_ms / lag_ms / last_beat_ms_ago /
     * started_at / device / unverified}；失败只有 {@code ok:false, running:false, error}。</p>
     *
     * <p>{@code seconds > 0} = 开始一次测量（5..180），立刻返回；不传 = 只读当前/最近一次。
     * 没在播就老实说 {@code hr_live_not_running}，不编数字。</p>
     */
    private JSONObject hrLive(int seconds) throws Exception {
        if (!s.isReady()) return hrLiveError("band_not_connected");
        if (seconds < 0 || (seconds > 0 && (seconds < HrLiveSession.MIN_SECONDS || seconds > HrLiveSession.MAX_SECONDS))) {
            return hrLiveError("bad_request");
        }
        if (seconds > 0 && !s.hrLiveRunning()) s.hrLiveStart(seconds);
        HrLiveSession.Snapshot sn = s.hrLiveSnapshot();
        if (!s.hrLiveRunning() || sn == null || !sn.running) return hrLiveError("hr_live_not_running");
        long now = System.currentTimeMillis();
        String model = s.model();
        return new JSONObject()
                .put("ok", true)
                .put("running", true)
                .put("bpm", sn.bpm)
                .put("bpm_avg", sn.bpmAvg)
                .put("beats", sn.beats)
                .put("dropped", sn.dropped)
                .put("dropped_sqi0", sn.droppedSqi0)
                .put("dropped_range", sn.droppedRange)
                .put("samples", sn.beats + sn.dropped)
                .put("packets", sn.packets)
                .put("window_ms", sn.elapsedMs(now))
                .put("lag_ms", sn.lagMs)
                .put("last_beat_ms_ago", sn.lastBeatAgoMs(now))
                .put("started_at", sn.startedAtMs)
                .put("device", model == null || model.isEmpty() ? "huawei band" : model)
                .put("unverified", true);
    }

    private static JSONObject hrLiveError(String code) throws Exception {
        return new JSONObject().put("ok", false).put("running", false).put("error", code);
    }
}
