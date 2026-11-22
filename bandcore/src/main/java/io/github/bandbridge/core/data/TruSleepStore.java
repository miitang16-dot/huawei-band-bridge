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

/** 科学睡眠每一晚的存档（filesDir/hw_trusleep.json）：按入睡时间去重（新的覆盖旧的），留 60 晚。 */
public final class TruSleepStore {

    private static final int KEEP = 60;

    private final File file;
    private final TreeMap<Long, JSONObject> map = new TreeMap<>();
    private boolean loaded = false;

    public TruSleepStore(Context app) { this.file = new File(app.getFilesDir(), "hw_trusleep.json"); }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!file.exists()) return;
            byte[] b = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); map.put(o.optLong("fall"), o); }
        } catch (Throwable ignored) { map.clear(); }
    }

    /** 最后一晚醒来的时间（秒）；没有就 0 */
    public synchronized long lastWakeSec() {
        load();
        long m = 0;
        for (JSONObject o : map.values()) m = Math.max(m, o.optLong("wake") / 1000L);
        return m;
    }

    public synchronized int merge(List<JSONObject> nights) {
        load();
        int added = 0;
        for (JSONObject n : nights) { if (n.optLong("fall") <= 0) continue; if (map.put(n.optLong("fall"), n) == null) added++; }
        while (map.size() > KEEP) map.pollFirstEntry();
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<Long, JSONObject> e : map.entrySet()) a.put(e.getValue());
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (Throwable ignored) { }
        return added;
    }

    /** 入睡时间在 [fromMs, toMs] 里的那些晚 */
    public synchronized JSONObject query(long fromMs, long toMs) {
        load();
        JSONArray a = new JSONArray();
        for (JSONObject o : map.subMap(fromMs, true, toMs, true).values()) a.put(o);
        JSONObject r = new JSONObject();
        try { r.put("nights", a); } catch (Throwable ignored) { }
        return r;
    }
}
