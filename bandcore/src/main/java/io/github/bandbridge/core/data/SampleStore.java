package io.github.bandbridge.core.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;

/**
 * 手环每分钟数据的本地存档（filesDir/hw_samples.json）。按秒级时间戳去重，只留最近 KEEP_DAYS 天。
 * 一分钟一条 ⇒ 10 天最多 1.4 万条，JSON 几百 KB，整读整写够用。
 * 只在 worker 线程写；PluginMethod 读（synchronized）。
 */
public final class SampleStore {

    private static final int KEEP_DAYS = 10;

    private final File file;
    private final TreeMap<Long, int[]> map = new TreeMap<>();   // t(秒) → {hr, spo, steps, rhr}
    private boolean loaded = false;

    public SampleStore(Context app) { this.file = new File(app.getFilesDir(), "hw_samples.json"); }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!file.exists()) return;
            byte[] b = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.getJSONArray(i);
                map.put(r.getLong(0), new int[]{ r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4) });
            }
        } catch (Throwable ignored) { map.clear(); }
    }

    public synchronized long lastTs() { load(); return map.isEmpty() ? 0 : map.lastKey(); }

    public synchronized int merge(List<StepSync.Sample> in) {
        load();
        int added = 0;
        for (StepSync.Sample s : in) { if (map.put(s.t, new int[]{ s.hr, s.spo, s.steps, s.rhr }) == null) added++; }
        long cut = System.currentTimeMillis() / 1000L - KEEP_DAYS * 86400L;
        while (!map.isEmpty() && map.firstKey() < cut) map.pollFirstEntry();
        save();
        return added;
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (java.util.Map.Entry<Long, int[]> e : map.entrySet()) {
                int[] v = e.getValue();
                a.put(new JSONArray().put(e.getKey()).put(v[0]).put(v[1]).put(v[2]).put(v[3]));
            }
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (Throwable ignored) { }
    }

    /** [fromSec, toSec] 内的数据，列式返回（前端画图方便）：t 是毫秒 */
    public synchronized JSONObject query(long fromSec, long toSec) {
        load();
        JSONArray t = new JSONArray(), hr = new JSONArray(), spo = new JSONArray(), steps = new JSONArray(), rhr = new JSONArray();
        for (java.util.Map.Entry<Long, int[]> e : map.subMap(fromSec, true, toSec, true).entrySet()) {
            int[] v = e.getValue();
            t.put(e.getKey() * 1000L); hr.put(v[0]); spo.put(v[1]); steps.put(v[2]); rhr.put(v[3]);
        }
        JSONObject o = new JSONObject();
        try { o.put("t", t); o.put("hr", hr); o.put("spo", spo); o.put("steps", steps); o.put("rhr", rhr); o.put("last", lastTs() * 1000L); } catch (Throwable ignored) { }
        return o;
    }
}
