package io.github.bandbridge;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把本机存档（filesDir 下的 hw_*.json）导出成人能读的文件：
 *
 *   - 一个 JSON：把所有存档原样打包（备份 / 丢给 AI 用，字段一个不少）；
 *   - 每个存档一个 CSV：按「日期 + 字段」摊平，能直接用 Excel / Numbers 打开。
 *
 * 写到手机的 Download 目录（Android 10+ 用 MediaStore，不需要存储权限）；
 * Android 8/9 没有 MediaStore.Downloads，退回 App 自己的外部目录（也不需要权限）。
 * 只写本机，永远不上传。App 里的「导出数据」按钮调的就是这里。
 */
public final class DataExport {

    private DataExport() {}

    /** 导出目录名（Download 目录下） */
    private static final String DIR = "HuaweiBandBridge";

    /** 行里出现这些键时，拿它算「日期」列（取第一个像时间戳的数字）。 */
    private static final String[] DATE_KEYS = {"fall", "wake", "bed", "rise", "start", "t", "ts", "time", "begin"};

    /** 导出结果：写了哪些文件、写在哪，给 UI 用。 */
    public static final class Result {
        public final List<String> written = new ArrayList<>();
        public String folder = "";

        public String summary() {
            if (written.isEmpty()) return "没有可导出的数据（先连上手环同步一次）";
            StringBuilder sb = new StringBuilder("导出好了，共 ").append(written.size()).append(" 个文件：\n");
            sb.append(folder).append("\n");
            for (String name : written) sb.append("· ").append(name).append('\n');
            return sb.toString().trim();
        }
    }

    /**
     * 导出全部本机存档。可能会跑一会儿（读文件 + 写文件），请在工作线程调用。
     *
     * @throws IOException 写文件失败
     */
    public static Result export(Context ctx) throws Exception {
        Result result = new Result();

        List<File> dataFiles = new ArrayList<>();
        File dir = ctx.getFilesDir();
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null) {
            for (File f : all) {
                String name = f.getName();
                if (f.isFile() && name.startsWith("hw_") && name.endsWith(".json")) dataFiles.add(f);
            }
        }
        dataFiles.sort((a, b) -> a.getName().compareTo(b.getName()));

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());

        // 1) 一个 JSON：原样打包，不做任何改写
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"app\": \"Huawei Band Bridge\",\n");
        json.append("  \"version\": \"").append(BuildInfo.VERSION).append("\",\n");
        json.append("  \"exportedAt\": \"").append(isoNow()).append("\",\n");
        json.append("  \"note\": \"本机存档原样导出；hw_samples 每行是 [秒级时间戳, 心率, 血氧, 步数, 静息心率]。\",\n");
        json.append("  \"files\": {\n");
        boolean first = true;
        for (File f : dataFiles) {
            String raw = readText(f);
            if (raw == null || raw.trim().isEmpty()) continue;
            if (!first) json.append(",\n");
            first = false;
            json.append("    \"").append(escape(f.getName())).append("\": ").append(raw.trim());
        }
        json.append("\n  }\n}\n");
        result.written.add(write(ctx, "bandbridge-export-" + stamp + ".json", "application/json",
                json.toString().getBytes(StandardCharsets.UTF_8)));

        // 2) 每个存档一个 CSV：能摊平的才写，摊不平的跳过（JSON 里仍然完整）
        for (File f : dataFiles) {
            try {
                String csv = toCsv(readText(f));
                if (csv == null) continue;
                String base = f.getName().substring("hw_".length(), f.getName().length() - ".json".length());
                result.written.add(write(ctx, "bandbridge-" + base + "-" + stamp + ".csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8)));
            } catch (Throwable ignored) {
                // 某个文件格式不认识就跳过，不影响其它文件
            }
        }

        result.folder = folderDisplay();
        return result;
    }

    /* ── CSV ── */

    /**
     * 把一份存档摊平成 CSV，返回 null 表示这份格式不适合表格。
     * 兼容三种存法：数组的数组（分钟数据）、数组的对象（科学睡眠）、
     * 对象里套数组（压力 / 情绪：{recs:[...]}）。
     */
    private static String toCsv(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) return null;

        Object root;
        try {
            root = new JSONArray(raw);
        } catch (Throwable notArray) {
            try {
                root = new JSONObject(raw);
            } catch (Throwable notObject) {
                return null;
            }
        }

        List<Map<String, String>> rows = new ArrayList<>();

        if (root instanceof JSONArray) {
            JSONArray array = (JSONArray) root;
            if (array.length() == 0) return null;
            if (isPositional(array)) {
                return samplesCsv(array);
            }
            appendObjectRows(array, rows);
        } else {
            JSONObject object = (JSONObject) root;
            JSONArray nested = firstArray(object);
            if (nested != null) {
                appendObjectRows(nested, rows);
            } else {
                rows.add(flattenOne(object));
            }
        }

        if (rows.isEmpty()) return null;
        return renderCsv(rows);
    }

    /** 分钟数据存的是 [t, hr, spo, steps, rhr]。 */
    private static boolean isPositional(JSONArray array) {
        try {
            Object first = array.get(0);
            return first instanceof JSONArray;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** hw_samples.json：date,time,t_epoch_sec,hr,spo2,steps,rest_hr */
    private static String samplesCsv(JSONArray array) {
        StringBuilder sb = new StringBuilder("date,time,t_epoch_sec,hr,spo2,steps,rest_hr\n");
        for (int i = 0; i < array.length(); i++) {
            try {
                JSONArray row = array.getJSONArray(i);
                long t = row.optLong(0, 0);
                sb.append(dateOf(t)).append(',')
                        .append(timeOf(t)).append(',')
                        .append(t).append(',')
                        .append(row.optInt(1, 0)).append(',')
                        .append(row.optInt(2, 0)).append(',')
                        .append(row.optInt(3, 0)).append(',')
                        .append(row.optInt(4, 0)).append('\n');
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private static void appendObjectRows(JSONArray array, List<Map<String, String>> rows) {
        for (int i = 0; i < array.length(); i++) {
            try {
                Object item = array.get(i);
                if (item instanceof JSONObject) rows.add(flattenOne((JSONObject) item));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 一个对象摊成一行；嵌套的对象/数组写成 JSON 文本，保证信息不丢。 */
    private static Map<String, String> flattenOne(JSONObject object) {
        Map<String, String> row = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL) {
                row.put(key, "");
            } else if (value instanceof JSONObject || value instanceof JSONArray) {
                row.put(key, String.valueOf(value));
            } else {
                row.put(key, String.valueOf(value));
            }
        }
        return row;
    }

    private static JSONArray firstArray(JSONObject object) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object value = object.opt(keys.next());
            if (value instanceof JSONArray) return (JSONArray) value;
        }
        return null;
    }

    /** 列顺序：date 在最前，其余按第一次出现的顺序。 */
    private static String renderCsv(List<Map<String, String>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        columns.add("date");
        for (Map<String, String> row : rows) columns.addAll(row.keySet());
        columns.remove("date");
        List<String> ordered = new ArrayList<>();
        ordered.add("date");
        ordered.addAll(columns);

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", ordered)).append('\n');
        for (Map<String, String> row : rows) {
            for (int i = 0; i < ordered.size(); i++) {
                if (i > 0) sb.append(',');
                String column = ordered.get(i);
                if ("date".equals(column)) {
                    sb.append(csvCell(dateOfRow(row)));
                } else {
                    sb.append(csvCell(row.get(column)));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String dateOfRow(Map<String, String> row) {
        for (String key : DATE_KEYS) {
            String value = row.get(key);
            if (value == null || value.isEmpty()) continue;
            try {
                long raw = Long.parseLong(value.trim());
                String date = dateOf(normalizeEpoch(raw));
                if (!date.isEmpty()) return date;
            } catch (NumberFormatException ignored) {
            }
        }
        return "";
    }

    private static String dateOf(long epoch) {
        long ms = normalizeEpoch(epoch);
        if (ms <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(ms));
    }

    private static String timeOf(long epoch) {
        long ms = normalizeEpoch(epoch);
        if (ms <= 0) return "";
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ms));
    }

    /** 存档里时间戳有秒有毫秒（科学睡眠是毫秒，分钟数据是秒），按大小猜。 */
    private static long normalizeEpoch(long value) {
        if (value >= 100_000_000_000L) return value;          // 毫秒
        if (value >= 100_000_000L) return value * 1000L;      // 秒
        return 0;                                             // 不像时间戳
    }

    private static String csvCell(String value) {
        if (value == null) return "";
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /* ── 写文件 ── */

    private static String folderDisplay() {
        if (Build.VERSION.SDK_INT >= 29) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + DIR;
        }
        return "Android/data/io.github.bandbridge/files/exports";
    }

    private static String write(Context ctx, String name, String mime, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore 拒绝了写入：" + name);
            OutputStream out = ctx.getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("打开输出流失败：" + name);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            return name;
        }

        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File outDir = new File(base, "exports");
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("建目录失败：" + outDir);
        File outFile = new File(outDir, name);
        FileOutputStream out = new FileOutputStream(outFile);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        return outFile.getAbsolutePath();
    }

    /* ── 小工具 ── */

    private static String readText(File file) {
        try {
            long length = file.length();
            if (length <= 0 || length > 32L * 1024 * 1024) return null;
            byte[] bytes = new byte[(int) length];
            FileInputStream in = new FileInputStream(file);
            try {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new String(bytes, 0, read, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date());
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
