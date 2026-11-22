package io.github.bandbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * HarmonyOS phones have no adb, so a crash leaves no trace we can read. This keeps the last
 * crash (stack trace only, no health data) in SharedPreferences and shows it on the next launch
 * on a plain screen that uses no other app code.
 */
final class CrashGuard {

    private static final String SP = "bandbridge_crash";

    static void install(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof Handler) return;
        Thread.setDefaultUncaughtExceptionHandler(new Handler(app, prev));
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        final Context app; final Thread.UncaughtExceptionHandler prev;
        Handler(Context app, Thread.UncaughtExceptionHandler prev) { this.app = app; this.prev = prev; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            save(app, "线程 " + t.getName(), e);
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    static void save(Context c, String where, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String text = "手环桥 " + BuildInfo.VERSION + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                    + Build.MANUFACTURER + " " + Build.MODEL + "\n" + where + "\n\n" + sw;
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString("last", text).commit();
        } catch (Throwable ignored) { }
    }

    static String last(Context c) { return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("last", null); }

    static void clear(Context c) { c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().remove("last").commit(); }

    /** Plain error screen; onContinue == null hides the "继续打开" button. */
    static void show(Activity a, String text, Runnable onContinue) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.WHITE);
        col.setPadding(40, 140, 40, 140);
        TextView h = new TextView(a);
        h.setText("手环桥上次崩溃了。请截图发给开发者（下面只有出错位置，没有健康数据）：");
        h.setTextColor(Color.BLACK);
        h.setTextSize(15);
        col.addView(h);
        Button copy = new Button(a);
        copy.setText("复制错误信息");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("crash", text));
            copy.setText("已复制");
        });
        col.addView(copy);
        if (onContinue != null) {
            Button go = new Button(a);
            go.setText("清掉记录，继续打开");
            go.setOnClickListener(v -> { clear(a); onContinue.run(); });
            col.addView(go);
        }
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xFF333333);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        col.addView(t);
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(col);
        a.setContentView(sv);
    }
}
