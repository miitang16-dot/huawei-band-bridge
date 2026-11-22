package io.github.bandbridge.core.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 睡眠分段存档（filesDir/hw_sleep.json）：按开始秒去重，留 30 天。跟 SampleStore 同一套整读整写。 */
public final class SleepStore {

    private static final int KEEP_DAYS = 30;

    private final File file;
    private final TreeMap<Long, long[]> map = new TreeMap<>();   // start → {end, type}
    private boolean loaded = false;

    public SleepStore(Context app) { this.file = new File(app.getFilesDir(), "hw_sleep.json"); }

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
                map.put(r.getLong(0), new long[]{ r.getLong(1), r.getLong(2) });
            }
        } catch (Throwable ignored) { map.clear(); }
    }

    public synchronized long lastEnd() {
        load();
        long m = 0;
        for (long[] v : map.values()) if (v[0] > m) m = v[0];
        return m;
    }

    public synchronized int merge(List<SleepSync.Seg> in) {
        load();
        int added = 0;
        for (SleepSync.Seg s : in) { if (map.put(s.start, new long[]{ s.end, s.type }) == null) added++; }
        long cut = System.currentTimeMillis() / 1000L - KEEP_DAYS * 86400L;
        while (!map.isEmpty() && map.firstKey() < cut) map.pollFirstEntry();
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<Long, long[]> e : map.entrySet()) a.put(new JSONArray().put(e.getKey()).put(e.getValue()[0]).put(e.getValue()[1]));
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (Throwable ignored) { }
        return added;
    }

    /** [fromSec, toSec] 内开始的分段：{ segs: [[startMs, endMs, type], …] } */
    public synchronized JSONObject query(long fromSec, long toSec) {
        load();
        JSONArray segs = new JSONArray();
        for (Map.Entry<Long, long[]> e : map.subMap(fromSec, true, toSec, true).entrySet()) {
            segs.put(new JSONArray().put(e.getKey() * 1000L).put(e.getValue()[0] * 1000L).put(e.getValue()[1]));
        }
        JSONObject o = new JSONObject();
        try { o.put("segs", segs); } catch (Throwable ignored) { }
        return o;
    }
}
