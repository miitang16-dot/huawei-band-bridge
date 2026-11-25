package io.github.bandbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One day's sleep = the main night + naps.
 *
 * The band stores each sleep (a night, an afternoon nap) as its own TruSleep record.
 * Picking "the record that woke up last" lets a 30-minute nap at 10:00 replace the whole night;
 * picking "the longest" drops the naps from the total. Both the health page and the
 * {@code band_sleep} tool use this instead, so they always agree:
 * among the records that woke up inside the day, the longest one is the night,
 * the rest are naps, and the day's total is night + naps.
 */
final class SleepDay {

    final JSONObject main;
    final List<JSONObject> naps;
    final int mainMin, napMin;

    private SleepDay(JSONObject main, List<JSONObject> naps, int mainMin, int napMin) {
        this.main = main; this.naps = naps; this.mainMin = mainMin; this.napMin = napMin;
    }

    int totalMin() { return mainMin + napMin; }

    static String hhmm(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** Minutes asleep in one record: light + REM + deep (+ code 5 nap); awake (4) not counted; no stages ⇒ wake − fall. */
    static int minutes(JSONObject n) {
        if (n == null) return 0;
        int m = 0;
        JSONObject by = n.optJSONObject("byCode");
        if (by != null) {
            JSONArray ks = by.names();
            if (ks != null) for (int i = 0; i < ks.length(); i++) {
                String k = ks.optString(i);
                if (!"4".equals(k)) m += by.optInt(k);
            }
        }
        if (m <= 0) m = (int) Math.max(0, (n.optLong("wake") - n.optLong("fall")) / 60_000L);
        return m;
    }

    /** Records that woke up in [fromMs, toMs) ⇒ night + naps; null when there are none. */
    static SleepDay of(JSONArray nights, long fromMs, long toMs) {
        if (nights == null) return null;
        List<JSONObject> day = new ArrayList<>();
        for (int i = 0; i < nights.length(); i++) {
            JSONObject n = nights.optJSONObject(i);
            if (n == null) continue;
            long w = n.optLong("wake"), f = n.optLong("fall");
            if (f <= 0 || w <= f || w < fromMs || w >= toMs) continue;
            day.add(n);
        }
        if (day.isEmpty()) return null;
        JSONObject main = day.get(0);
        for (JSONObject n : day) if (minutes(n) > minutes(main)) main = n;
        List<JSONObject> naps = new ArrayList<>();
        int napMin = 0;
        for (JSONObject n : day) if (n != main) { naps.add(n); napMin += minutes(n); }
        naps.sort((a, b) -> Long.compare(a.optLong("fall"), b.optLong("fall")));
        return new SleepDay(main, naps, minutes(main), napMin);
    }
}
