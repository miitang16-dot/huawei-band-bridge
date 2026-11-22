package io.github.bandbridge;

import android.content.Context;

import io.github.bandbridge.core.BandSession;
import io.github.bandbridge.core.data.DictStore;
import io.github.bandbridge.core.data.DictSync;
import io.github.bandbridge.core.data.SampleStore;
import io.github.bandbridge.core.data.StressSync;
import io.github.bandbridge.core.data.TruSleepStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the health page shows, read from the local stores on a worker thread.
 *
 * <p>Same data the MCP tools read: minute samples (heart rate / SpO2 / steps / resting HR),
 * TruSleep nights, stress records and emotion records. Nothing is fetched from the band here and
 * nothing is invented: a missing reading stays missing (empty array / 0), and every value carries
 * the time it was recorded.</p>
 *
 * <p>Works with or without a running session: when the service is off the stores are opened
 * directly from the app's private directory, so past data is still visible.</p>
 */
final class HealthData {

    long nowMs, dayStartMs;
    long lastDataMs;                     // newest minute sample (0 = none)

    /* connection */
    boolean serviceRunning;
    boolean connected;
    String model = "";
    int battery = -1;
    boolean calibrated;

    /* heart rate (today) */
    boolean hasHr;
    int hr, restingHr;
    long hrAtMs;
    int hrMin, hrMax, hrAvg, hrCount;
    long[] hrT = new long[0];
    int[] hrV = new int[0];
    int[] hrSpark = new int[0];          // 96 bins of 15 minutes over today

    /* SpO2 (today) */
    boolean hasSpo;
    int spo;
    long spoAtMs;
    int spoMin, spoMax, spoAvg, spoCount;

    /* steps (today) */
    int stepsToday;
    long stepsAtMs;
    int[] stepsByHour = new int[24];

    /* last night's TruSleep */
    boolean hasSleep;
    long sleepFallMs, sleepWakeMs;
    int sleepScore, sleepTotalMin, sleepDeepMin, sleepLightMin, sleepRemMin, sleepAwakeMin;
    int sleepAvgHr, sleepMinSpo, sleepAvgBreath;
    long[] sleepRunStart = new long[0];
    int[] sleepRunMin = new int[0];
    int[] sleepRunCode = new int[0];

    /* stress (today for the timeline, newest for the tile) */
    boolean hasStress;
    int stressScore, stressLevel;
    long stressAtMs;
    long[] stressT = new long[0];
    int[] stressV = new int[0];
    int stressAvg, stressMax, stressCount;
    int[] stressDist = new int[5];

    /* emotion */
    boolean hasEmotion;
    int emoCode, emoValence = -1, emoArousal = -1;
    long emoAtMs;
    long[] emoStart = new long[0];
    long[] emoEnd = new long[0];
    int[] emoCodeArr = new int[0];
    int[] emoCnt = new int[4];

    boolean anything;

    private HealthData() { }

    static HealthData load(Context ctx, BandSession session) {
        HealthData d = new HealthData();
        d.nowMs = System.currentTimeMillis();
        d.dayStartMs = dayStart(d.nowMs);
        d.serviceRunning = session != null;
        d.connected = session != null && session.isReady();
        if (session != null) {
            d.model = session.model() == null ? "" : session.model();
            d.battery = session.battery();
            d.calibrated = session.calibration() != null;
        } else {
            d.calibrated = StressSync.loadCalib(ctx) != null;
        }

        SampleStore samples = session != null ? session.samples() : new SampleStore(ctx);
        TruSleepStore sleeps = session != null ? session.truSleep() : new TruSleepStore(ctx);
        DictStore stress = session != null ? session.stress() : new DictStore(ctx, "stress");
        DictStore emotion = session != null ? session.emotion() : new DictStore(ctx, DictSync.EMOTION);

        readSamples(d, samples);
        readSleep(d, sleeps);
        readStress(d, stress);
        readEmotion(d, emotion);
        d.anything = d.hasHr || d.hasSpo || d.stepsToday > 0 || d.hasSleep || d.hasStress || d.hasEmotion;
        return d;
    }

    /* ── minute samples ─────────────────────────────────────────────────── */

    private static void readSamples(HealthData d, SampleStore store) {
        d.lastDataMs = store.lastTs() * 1000L;
        JSONObject today = store.query(d.dayStartMs / 1000L, d.nowMs / 1000L);
        JSONArray t = today.optJSONArray("t"), hr = today.optJSONArray("hr"), spo = today.optJSONArray("spo"),
                st = today.optJSONArray("steps"), rhr = today.optJSONArray("rhr");
        if (t == null) t = new JSONArray();
        if (hr == null) hr = new JSONArray();
        if (spo == null) spo = new JSONArray();
        if (st == null) st = new JSONArray();
        if (rhr == null) rhr = new JSONArray();

        int minHr = Integer.MAX_VALUE, maxHr = 0, sumHr = 0, nHr = 0;
        int minSpo = 999, maxSpo = 0, sumSpo = 0, nSpo = 0;
        int steps = 0, rest = 0;
        long stepsAt = 0;
        List<Long> seriesT = new ArrayList<>();
        List<Integer> seriesV = new ArrayList<>();
        int[] bins = new int[96];
        int[] binN = new int[96];
        for (int i = 0; i < t.length(); i++) {
            long tm = t.optLong(i);
            int h = hr.optInt(i), sp = spo.optInt(i), s = st.optInt(i), r = rhr.optInt(i);
            if (h > 0) {
                minHr = Math.min(minHr, h); maxHr = Math.max(maxHr, h); sumHr += h; nHr++;
                seriesT.add(tm); seriesV.add(h);
                int bin = (int) Math.min(95, Math.max(0, (tm - d.dayStartMs) / 900_000L));
                bins[bin] += h; binN[bin]++;
            }
            if (sp > 0) { minSpo = Math.min(minSpo, sp); maxSpo = Math.max(maxSpo, sp); sumSpo += sp; nSpo++; }
            if (s > 0) { steps += s; stepsAt = tm; }
            if (r > 0) rest = r;
        }
        if (nHr > 0) {
            d.hrMin = minHr; d.hrMax = maxHr; d.hrAvg = Math.round(sumHr / (float) nHr); d.hrCount = nHr;
            d.hasHr = true;
            d.restingHr = rest;
            d.hrT = new long[seriesT.size()];
            d.hrV = new int[seriesV.size()];
            for (int i = 0; i < seriesT.size(); i++) { d.hrT[i] = seriesT.get(i); d.hrV[i] = seriesV.get(i); }
            d.hrSpark = new int[96];
            for (int i = 0; i < 96; i++) d.hrSpark[i] = binN[i] == 0 ? 0 : Math.round(bins[i] / (float) binN[i]);
        }
        if (nSpo > 0) { d.hasSpo = true; d.spoMin = minSpo; d.spoMax = maxSpo; d.spoAvg = Math.round(sumSpo / (float) nSpo); d.spoCount = nSpo; }
        d.stepsToday = steps;
        d.stepsAtMs = stepsAt;
        for (int i = 0; i < t.length(); i++) {
            int s = st.optInt(i);
            if (s <= 0) continue;
            int hour = hourOf(t.optLong(i));
            d.stepsByHour[hour] += s;
        }

        /* newest readings, allowed to be from yesterday evening (a page opened at 00:10 should
           still show the last reading instead of "no data") */
        JSONObject recent = store.query((d.nowMs - 36 * 3600_000L) / 1000L, d.nowMs / 1000L);
        JSONArray rt = recent.optJSONArray("t"), rhrArr = recent.optJSONArray("hr"), rspo = recent.optJSONArray("spo");
        if (rt != null && rhrArr != null) {
            for (int i = rt.length() - 1; i >= 0; i--) {
                int h = rhrArr.optInt(i);
                if (h > 0) { d.hr = h; d.hrAtMs = rt.optLong(i); break; }
            }
        }
        if (rt != null && rspo != null) {
            for (int i = rt.length() - 1; i >= 0; i--) {
                int v = rspo.optInt(i);
                if (v > 0) { d.spo = v; d.spoAtMs = rt.optLong(i); break; }
            }
        }
        d.hasSpo = d.hasSpo || d.spo > 0;
        d.hasHr = d.hasHr || d.hr > 0;
    }

    /* ── TruSleep ───────────────────────────────────────────────────────── */

    private static void readSleep(HealthData d, TruSleepStore store) {
        JSONObject night = lastNight(store, d.nowMs - 36 * 3600_000L);
        if (night == null) night = lastNight(store, d.nowMs - 7 * 86_400_000L);
        if (night == null) return;
        long fall = night.optLong("fall"), wake = night.optLong("wake");
        if (fall <= 0 || wake <= fall) return;
        JSONObject by = night.optJSONObject("byCode");
        int light = by == null ? 0 : by.optInt("1"), rem = by == null ? 0 : by.optInt("2"),
                deep = by == null ? 0 : by.optInt("3"), awake = by == null ? 0 : by.optInt("4");
        int total = light + rem + deep;
        if (total <= 0) total = (int) Math.max(0, (wake - fall) / 60_000L);
        d.hasSleep = true;
        d.sleepFallMs = fall; d.sleepWakeMs = wake;
        d.sleepScore = night.optInt("score");
        d.sleepTotalMin = total;
        d.sleepDeepMin = deep; d.sleepLightMin = light; d.sleepRemMin = rem; d.sleepAwakeMin = awake;
        d.sleepAvgHr = night.optInt("avgHr");
        d.sleepMinSpo = night.optInt("minSpo");
        d.sleepAvgBreath = night.optInt("avgBreath");
        JSONArray runs = night.optJSONArray("runs");
        if (runs != null && runs.length() > 0) {
            d.sleepRunStart = new long[runs.length()];
            d.sleepRunMin = new int[runs.length()];
            d.sleepRunCode = new int[runs.length()];
            for (int i = 0; i < runs.length(); i++) {
                JSONArray r = runs.optJSONArray(i);
                if (r == null || r.length() < 3) { d.sleepRunMin[i] = 0; continue; }
                d.sleepRunStart[i] = r.optLong(0);
                d.sleepRunMin[i] = r.optInt(1);
                d.sleepRunCode[i] = r.optInt(2);
            }
        }
    }

    private static JSONObject lastNight(TruSleepStore store, long fromMs) {
        JSONArray nights = store.query(fromMs, System.currentTimeMillis()).optJSONArray("nights");
        if (nights == null) return null;
        JSONObject best = null;
        long bestWake = 0;
        for (int i = 0; i < nights.length(); i++) {
            JSONObject n = nights.optJSONObject(i);
            if (n == null) continue;
            long w = n.optLong("wake");
            if (w > bestWake) { bestWake = w; best = n; }
        }
        return best;
    }

    /* ── stress ─────────────────────────────────────────────────────────── */

    private static void readStress(HealthData d, DictStore store) {
        long sum = 0;
        int n = 0, max = 0;
        List<long[]> pts = new ArrayList<>();   // {t, v}
        JSONArray recs = store.query(d.dayStartMs, d.nowMs).optJSONArray("recs");
        if (recs != null) {
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r == null) continue;
                JSONObject rv = r.optJSONObject("v");
                int v = rv == null ? 0 : rv.optInt("score");
                if (v <= 0) continue;
                long tm = r.optLong("end", r.optLong("start"));
                pts.add(new long[]{tm, v});
                sum += v; n++; max = Math.max(max, v);
                int lv = Charts.Palette.stressLevel(v);
                if (lv >= 1 && lv <= 4) d.stressDist[lv]++;
            }
        }
        pts.sort((a, b) -> Long.compare(a[0], b[0]));
        d.hasStress = n > 0;
        d.stressCount = n;
        d.stressAvg = n == 0 ? 0 : Math.round(sum / (float) n);
        d.stressMax = max;
        d.stressT = new long[pts.size()];
        d.stressV = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) { d.stressT[i] = pts.get(i)[0]; d.stressV[i] = (int) pts.get(i)[1]; }
        if (n > 0) {
            d.stressScore = d.stressV[n - 1];
            d.stressAtMs = d.stressT[n - 1];
            d.stressLevel = Charts.Palette.stressLevel(d.stressScore);
        }
        /* a page opened before the band measured today: show the newest record, with its real time */
        if (!d.hasStress) {
            JSONObject o = newest(store.query(d.nowMs - 36 * 3600_000L, d.nowMs));
            JSONObject ov = o == null ? null : o.optJSONObject("v");
            int v = ov == null ? 0 : ov.optInt("score");
            if (v > 0) {
                d.hasStress = true;
                d.stressScore = v;
                d.stressLevel = Charts.Palette.stressLevel(v);
                d.stressAtMs = o.optLong("end", o.optLong("start"));
            }
        }
    }

    private static JSONObject newest(JSONObject query) {
        JSONArray recs = query == null ? null : query.optJSONArray("recs");
        if (recs == null || recs.length() == 0) return null;
        JSONObject best = null;
        long bestT = 0;
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r == null) continue;
            long tm = Math.max(r.optLong("start"), Math.max(r.optLong("end"), r.optLong("mod")));
            if (tm >= bestT) { bestT = tm; best = r; }
        }
        return best;
    }

    /* ── emotion ────────────────────────────────────────────────────────── */

    private static void readEmotion(HealthData d, DictStore store) {
        List<long[]> rows = new ArrayList<>();   // {start, end, code}
        JSONArray recs = store.query(d.dayStartMs, d.nowMs).optJSONArray("recs");
        if (recs != null) {
            for (int i = 0; i < recs.length(); i++) {
                JSONObject r = recs.optJSONObject(i);
                if (r == null) continue;
                JSONObject v = r.optJSONObject("v");
                if (v == null) continue;
                int code = (int) Math.round(v.optDouble("500031195"));
                long s = r.optLong("start"), e = r.optLong("end");
                rows.add(new long[]{s, e > s ? e : s + 5 * 60_000L, code});
                if (code >= 1 && code <= 3) d.emoCnt[code]++;
            }
        }
        rows.sort((a, b) -> Long.compare(a[0], b[0]));
        d.hasEmotion = !rows.isEmpty();
        d.emoStart = new long[rows.size()];
        d.emoEnd = new long[rows.size()];
        d.emoCodeArr = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            d.emoStart[i] = rows.get(i)[0];
            d.emoEnd[i] = rows.get(i)[1];
            d.emoCodeArr[i] = (int) rows.get(i)[2];
        }
        JSONObject last = null;
        if (d.hasEmotion) last = recAt(store, d.emoStart[d.emoStart.length - 1]);
        if (last == null) last = newestEmotion(store, d.nowMs - 36 * 3600_000L);
        JSONObject lv = last == null ? null : last.optJSONObject("v");
        if (lv != null) {
            d.emoCode = (int) Math.round(lv.optDouble("500031195"));
            if (lv.has("500031347")) d.emoValence = (int) Math.round(lv.optDouble("500031347"));
            if (lv.has("500031842")) d.emoArousal = (int) Math.round(lv.optDouble("500031842"));
            d.emoAtMs = last.optLong("start");
            d.hasEmotion = d.hasEmotion || d.emoCode > 0;
        }
    }

    private static JSONObject recAt(DictStore store, long startMs) {
        JSONArray recs = store.query(startMs, startMs).optJSONArray("recs");
        return recs == null || recs.length() == 0 ? null : recs.optJSONObject(0);
    }

    private static JSONObject newestEmotion(DictStore store, long fromMs) {
        JSONObject q = store.query(fromMs, System.currentTimeMillis());
        JSONArray recs = q.optJSONArray("recs");
        if (recs == null) return null;
        JSONObject best = null;
        long bestT = 0;
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            if (r == null) continue;
            long tm = r.optLong("start");
            if (tm >= bestT) { bestT = tm; best = r; }
        }
        return best;
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    static long dayStart(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static int hourOf(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.HOUR_OF_DAY);
    }
}
