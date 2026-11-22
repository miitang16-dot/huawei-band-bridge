package io.github.bandbridge.core.proto;

import android.util.Log;

/**
 * 从 Gadgetbridge 原样搬过来的解析器用的是 slf4j（LOG.info("x {} y", a)）。
 * 这里给一个同名用法的薄壳，转成 android.util.Log，"{}" 按顺序替换成参数。搬代码时只改 import 和那一行声明。
 */
public final class GbLog {
    private final String tag;

    private GbLog(String tag) { this.tag = tag.length() > 23 ? tag.substring(0, 23) : tag; }

    public static GbLog get(Class<?> c) { return new GbLog("Gb" + c.getSimpleName()); }

    private static String fmt(String f, Object... a) {
        if (f == null) return "";
        if (a == null || a.length == 0) return f;
        StringBuilder sb = new StringBuilder();
        int ai = 0, i = 0;
        while (i < f.length()) {
            int j = f.indexOf("{}", i);
            if (j < 0 || ai >= a.length) { sb.append(f, i, f.length()); break; }
            sb.append(f, i, j).append(a[ai++]);
            i = j + 2;
        }
        return sb.toString();
    }

    public void debug(String f, Object... a) { Log.d(tag, fmt(f, a)); }
    public void info(String f, Object... a) { Log.i(tag, fmt(f, a)); }
    public void warn(String f, Object... a) { Log.w(tag, fmt(f, a)); }
    public void error(String f, Object... a) { Log.e(tag, fmt(f, a)); }
}
