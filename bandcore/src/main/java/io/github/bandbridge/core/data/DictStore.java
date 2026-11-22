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

/**
 * ★3.301 P2P 字典数据（情绪 / HRV / 皮肤温度…）的本机存档：filesDir/hw_dict_<类别>.json
 * 一条 = {start, end, mod, v:{类型id: 数值}}，按 start 去重（新的覆盖旧的），留 30 天。
 */
public final class DictStore {

    private static final long KEEP_MS = 30L * 86400000L;

    private final File file;
    private final TreeMap<Long, JSONObject> map = new TreeMap<>();
    private boolean loaded = false;

    public DictStore(Context app, int dictClass) { this.file = new File(app.getFilesDir(), "hw_dict_" + dictClass + ".json"); }
    /** 同样格式的别的数据（压力：hw_stress.json）*/
    public DictStore(Context app, String name) { this.file = new File(app.getFilesDir(), "hw_" + name + ".json"); }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!file.exists()) return;
            byte[] b = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } }
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); map.put(o.optLong("start"), o); }
        } catch (Throwable ignored) { map.clear(); }
    }

    /** 最后一条的结束 / 修改时间（毫秒）；没有就 0 */
    public synchronized long lastMs() {
        load();
        long m = 0;
        for (JSONObject o : map.values()) m = Math.max(m, Math.max(o.optLong("start"), Math.max(o.optLong("end"), o.optLong("mod"))));
        return m;
    }

    public synchronized int merge(List<JSONObject> recs) {
        load();
        int added = 0;
        for (JSONObject o : recs) { long s = o.optLong("start"); if (s <= 0) continue; if (map.put(s, o) == null) added++; }
        long cut = System.currentTimeMillis() - KEEP_MS;
        while (!map.isEmpty() && map.firstKey() < cut) map.pollFirstEntry();
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<Long, JSONObject> e : map.entrySet()) a.put(e.getValue());
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
        } catch (Throwable ignored) { }
        return added;
    }

    /** start 在 [fromMs, toMs] 里的记录 */
    public synchronized JSONObject query(long fromMs, long toMs) {
        load();
        JSONArray a = new JSONArray();
        for (JSONObject o : map.subMap(fromMs, true, toMs, true).values()) a.put(o);
        JSONObject r = new JSONObject();
        try { r.put("recs", a); } catch (Throwable ignored) { }
        return r;
    }
}
