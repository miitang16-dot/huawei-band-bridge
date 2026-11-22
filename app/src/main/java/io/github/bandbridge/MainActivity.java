package io.github.bandbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.bandbridge.core.BandSession;
import io.github.bandbridge.core.data.HrLiveSession;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One screen: connection, heartbeat live view, stress calibration, MCP address + token, music access, profile, log. Plain views, no libraries. */
public class MainActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView state, data, mcpInfo, calibTxt, musicTxt, log, exportTxt, hrTxt;
    private Button connectBtn, mcpBtn, calibBtn, hr1Btn, hr3Btn, hrStopBtn;
    private boolean showToken = false;
    private int ink, sub, card, accent;

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF1ECEE : 0xFF2B2326; sub = dark ? 0xFFA79CA1 : 0xFF7A6E73; card = dark ? 0xFF221D21 : 0xFFFFFFFF; accent = 0xFFE58FA6;
        int bg = dark ? 0xFF16131F : 0xFFFBF2F5;
        getWindow().setStatusBarColor(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(32));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        sv.addView(col);
        setContentView(sv);

        TextView title = text("手环桥", 26, ink, true);
        col.addView(title);
        TextView tag = text("让 AI 直接读你的华为手环 · 不用华为 App、不用账号、数据不出手机", 13, sub, false);
        tag.setPadding(0, dp(4), 0, dp(12));
        col.addView(tag);

        /* 健康页（和 Nexus 健康页同一套视觉）：横幅 / 六块概览 / 图表 */
        LinearLayout health = new LinearLayout(this);
        health.setOrientation(LinearLayout.VERTICAL);
        health.setPadding(dp(16), dp(14), dp(16), dp(16));
        Charts.PanelBg healthBg = new Charts.PanelBg(card, Color.argb(28, 120, 80, 95), 1, 20, getResources().getDisplayMetrics().density);
        healthBg.setHeroTints(Charts.Palette.HR, Charts.Palette.SLEEP);
        health.setBackground(healthBg);
        LinearLayout healthTop = new LinearLayout(this);
        healthTop.setOrientation(LinearLayout.HORIZONTAL);
        healthTop.addView(text("健康", 18, ink, true));
        healthTop.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        healthTop.addView(text("›", 22, sub, false));
        health.addView(healthTop);
        health.addView(text("心率 · 血氧 · 步数 · 睡眠 · 压力 · 情绪 —— 今天的数据，和 Nexus 健康页同一套", 12, sub, false));
        health.setOnClickListener(v -> startActivity(new Intent(this, HealthActivity.class)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(2);
        col.addView(health, hlp);

        /* 连接 */
        LinearLayout c1 = card(col, "手环");
        state = text("", 15, ink, true); c1.addView(state);
        data = text("", 13, sub, false); c1.addView(data);
        connectBtn = button(c1, "连接", v -> toggleConnect());
        button(c1, "立即同步", v -> { BandService s = BandService.instance; if (s != null && s.session != null) s.session.syncNow(); });
        c1.addView(note("第一次用之前：手环要先和「华为运动健康」解绑（App 里删除设备，或手环恢复出厂），两边不能同时连。"));

        /* 心跳直播 */
        LinearLayout ch = card(col, "心跳直播（逐拍心率）");
        ch.addView(note("手环里还有一路「逐拍」数据（RRI）：能算出一拍一拍的心率，比每分钟一格细得多。"
                + "按一下测 1 分钟，戴紧、别说话；能不能连着开更久还没上真机验证。"));
        hrTxt = text("", 13, ink, false);
        hrTxt.setTextIsSelectable(true);
        ch.addView(hrTxt);
        hr1Btn = button(ch, "开始（1 分钟）", v -> startHrLive(HrLiveSession.DEFAULT_SECONDS));
        hr3Btn = button(ch, "试 3 分钟（未验证手环肯开这么久）", v -> startHrLive(180));
        hrStopBtn = button(ch, "停", v -> {
            BandService s = BandService.instance;
            if (s != null && s.session != null) s.session.hrLiveStop();
        });
        ch.addView(note("lag = 包到达时间 − 最后一拍的时间。这个数字小，才是真的「此刻」；大就说明在补几秒前的数据。"));
        ch.addView(note("「丢」是手环标为无效的样本：sqi=0（它自己说这一拍不可信），或间期 <250 ms / >3000 ms（漏拍、断拍、手没贴住）。不是漏掉的心跳。"));

        /* 压力 */
        LinearLayout c2 = card(col, "压力校准");
        c2.addView(note("手环要先「认识」一次你安静时的心跳，才会开始自动测压力。戴紧手环、坐着别动别说话 1 分钟。"));
        calibTxt = text("", 13, ink, false); c2.addView(calibTxt);
        calibBtn = button(c2, "开始校准（1 分钟）", v -> {
            BandService s = BandService.instance;
            if (s == null || s.session == null || !s.session.calibrateStress()) toast("手环还没连上");
        });

        /* MCP */
        LinearLayout c3 = card(col, "MCP 服务（给 AI 用）");
        mcpInfo = text("", 13, ink, false); mcpInfo.setTextIsSelectable(true); c3.addView(mcpInfo);
        mcpBtn = button(c3, "", v -> toggleMcp());
        button(c3, "复制配置", v -> copyConfig());
        button(c3, "显示 / 隐藏口令", v -> { showToken = !showToken; refresh(); });
        button(c3, "换一个口令", v -> { BandService.newToken(this); BandService s = BandService.instance; if (s != null) s.restartMcp(); refresh(); toast("旧口令已作废"); });
        c3.addView(note("AI 和手机要在同一个 Wi-Fi 下（或用 Tailscale 之类的组网）。口令就是钥匙，别发给不认识的人。"));

        /* 音乐 */
        LinearLayout c4 = card(col, "手环音乐页");
        musicTxt = text("", 13, ink, false); c4.addView(musicTxt);
        button(c4, "去开「通知使用权」", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        c4.addView(note("不开也能用手环切歌 / 暂停，只是手环上不显示歌名。本 App 不读取、不保存任何通知内容。"));

        /* 天气 */
        LinearLayout c5 = card(col, "天气地名");
        EditText place = input(c5, "手环上显示的地名（比如 东京）", getSharedPreferences(WeatherFetch.SP, MODE_PRIVATE).getString("place", ""), InputType.TYPE_CLASS_TEXT);
        button(c5, "保存", v -> {
            getSharedPreferences(WeatherFetch.SP, MODE_PRIVATE).edit().putString("place", place.getText().toString().trim()).putLong("at", 0).apply();
            BandService s = BandService.instance; if (s != null && s.session != null) s.session.weatherChanged();
            toast("好了，下次送天气时用这个名字");
        });
        c5.addView(note("天气来自 Open-Meteo，只发送大概位置（约 10 公里精度）。"));

        /* 资料 */
        LinearLayout c6 = card(col, "个人资料（手环算卡路里、压力用）");
        SharedPreferences sp = getSharedPreferences(BandService.SP, MODE_PRIVATE);
        EditText hgt = input(c6, "身高 cm", sp.getInt("height", 0) > 0 ? String.valueOf(sp.getInt("height", 0)) : "", InputType.TYPE_CLASS_NUMBER);
        EditText wgt = input(c6, "体重 kg", sp.getFloat("weight", 0) > 0 ? String.valueOf(sp.getFloat("weight", 0)) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText bd = input(c6, "生日 YYYY-MM-DD", sp.getString("birthday", ""), InputType.TYPE_CLASS_DATETIME);
        EditText gd = input(c6, "性别：女 / 男", sp.getInt("gender", 2) == 1 ? "男" : "女", InputType.TYPE_CLASS_TEXT);
        button(c6, "保存并发给手环", v -> {
            try {
                sp.edit().putInt("height", Integer.parseInt(hgt.getText().toString().trim()))
                        .putFloat("weight", Float.parseFloat(wgt.getText().toString().trim()))
                        .putString("birthday", bd.getText().toString().trim())
                        .putInt("gender", gd.getText().toString().contains("男") ? 1 : 2).apply();
                BandService s = BandService.instance; if (s != null && s.session != null) s.session.profileChanged();
                toast("存好了（只存在这台手机上）");
            } catch (Throwable t) { toast("填得不对，检查一下数字和日期格式"); }
        });

        /* 导出 */
        LinearLayout c7 = card(col, "导出数据");
        exportTxt = text("把本机存档导出成 JSON（原样打包）+ CSV（按日期摊平，Excel 能开）。只写本机，不上传。", 13, ink, false);
        exportTxt.setTextIsSelectable(true);
        c7.addView(exportTxt);
        button(c7, "导出 JSON + CSV", v -> exportData());
        c7.addView(note("Android 10+ 写到「Download/HuaweiBandBridge」；Android 8/9 写到 App 自己的外部目录（都不用存储权限）。"));

        /* 日志 */
        LinearLayout c8 = card(col, "日志（排查问题用，只在本机）");
        log = text("", 11, sub, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        c8.addView(log);

        askPermissions();
    }

    @Override protected void onResume() { super.onResume(); BandService.ui = () -> h.post(this::refresh); refresh(); h.postDelayed(tick, 5000); }
    @Override protected void onPause() { super.onPause(); BandService.ui = null; h.removeCallbacks(tick); }
    private final Runnable tick = new Runnable() { @Override public void run() { refresh(); h.postDelayed(this, 5000); } };

    /* ── actions ── */

    private void toggleConnect() {
        if (BandService.instance != null) { BandService.stop(this); getSharedPreferences(BandService.SP, MODE_PRIVATE).edit().putBoolean("auto", false).apply(); }
        else {
            if (!hasBt()) { askPermissions(); toast("要先允许「附近的设备」权限"); return; }
            getSharedPreferences(BandService.SP, MODE_PRIVATE).edit().putBoolean("auto", true).apply();
            BandService.start(this);
        }
        h.postDelayed(this::refresh, 300);
    }

    private void toggleMcp() {
        boolean on = !BandService.mcpWanted(this);
        getSharedPreferences(BandService.SP, MODE_PRIVATE).edit().putBoolean("mcp", on).apply();
        BandService s = BandService.instance;
        if (s != null) { if (on) s.startMcp(); else s.stopMcp(); }
        refresh();
    }

    /** 开始一次心跳直播（0x19 逐拍流）。立刻返回，进度靠 refresh() 读快照。 */
    private void startHrLive(int seconds) {
        BandService s = BandService.instance;
        if (s == null || s.session == null) { toast("手环还没连上"); return; }
        if (!s.session.isReady()) { toast("还在连，等状态变成「已连接」再点"); return; }
        if (!s.session.hrLiveStart(seconds)) { toast("已经在测了"); return; }
        toast("开始了：安静坐着，让它测完这 " + seconds + " 秒");
        refresh();
    }

    /** 导出本机存档（JSON + CSV）。读文件 + 写下载目录都在工作线程，UI 只更新结果文字。 */
    private void exportData() {
        exportTxt.setText("导出中…");
        new Thread(() -> {
            try {
                DataExport.Result result = DataExport.export(this);
                runOnUiThread(() -> {
                    exportTxt.setText(result.summary());
                    toast(result.written.isEmpty() ? "还没有数据可导出" : "导出好了");
                });
            } catch (Throwable t) {
                String message = String.valueOf(t);
                runOnUiThread(() -> {
                    exportTxt.setText("导出失败：" + message);
                    toast("导出失败，换到「日志」看原因");
                });
            }
        }, "band-export").start();
    }

    private void copyConfig() {
        List<String> ips = ips();
        String url = "http://" + (ips.isEmpty() ? "<手机IP>" : ips.get(0)) + ":" + BandService.MCP_PORT + "/mcp";
        try {
            JSONObject cfg = new JSONObject().put("mcpServers", new JSONObject().put("huawei-band", new JSONObject()
                    .put("type", "http").put("url", url)
                    .put("headers", new JSONObject().put("Authorization", "Bearer " + BandService.token(this)))));
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("mcp", cfg.toString(2)));
            toast("已复制（里面有口令，别乱发）");
        } catch (Throwable ignored) { }
    }

    /* ── permissions ── */

    private boolean hasBt() {
        if (Build.VERSION.SDK_INT >= 31) return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { want.add(android.Manifest.permission.BLUETOOTH_SCAN); want.add(android.Manifest.permission.BLUETOOTH_CONNECT); }
        else want.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        want.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) want.add(android.Manifest.permission.POST_NOTIFICATIONS);
        List<String> miss = new ArrayList<>();
        for (String p : want) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) requestPermissions(miss.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (hasBt() && BandService.instance == null && getSharedPreferences(BandService.SP, MODE_PRIVATE).getBoolean("auto", false)) BandService.start(this);
        refresh();
    }

    /* ── refresh ── */

    private void refresh() {
        BandService s = BandService.instance;
        boolean on = s != null;
        state.setText(on ? s.stateText() : "没在运行");
        connectBtn.setText(on ? "断开" : "连接");
        String d = "";
        if (on && s.session != null) {
            BandSession bs = s.session;
            long last = bs.samples().lastTs() * 1000L;
            d = last > 0 ? "手环数据到 " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date(last)) : "还没有数据（连上后一分钟内会拉第一次）";
            calibTxt.setText(BandService.calib.isEmpty() ? (bs.calibration() != null ? "已校准过 ✓" : "还没校准") : BandService.calib);
            calibBtn.setEnabled(bs.isReady() && !bs.calibrating());
            renderHrLive(bs);
        } else { calibTxt.setText("先连上手环"); calibBtn.setEnabled(false); renderHrLive(null); }
        data.setText(d);

        boolean mcpOn = on && s.mcp != null && s.mcp.isRunning();
        mcpBtn.setText(BandService.mcpWanted(this) ? "关掉 MCP 服务" : "打开 MCP 服务");
        StringBuilder m = new StringBuilder();
        m.append(mcpOn ? "● 在运行\n" : BandService.mcpWanted(this) ? "○ 连上手环后自动开\n" : "○ 关着\n");
        List<String> ips = ips();
        if (ips.isEmpty()) m.append("地址：（手机没连 Wi-Fi）\n");
        for (String ip : ips) m.append("地址：http://").append(ip).append(":").append(BandService.MCP_PORT).append("/mcp\n");
        String tk = BandService.token(this);
        m.append("口令：").append(showToken ? tk : tk.substring(0, 4) + "••••••••••••••••••••");
        mcpInfo.setText(m.toString());

        musicTxt.setText(Media.hasAccess(this) ? "通知使用权已开 ✓ 手环会显示歌名" : "通知使用权没开：手环能切歌，但不显示歌名");

        StringBuilder lg = new StringBuilder();
        List<String> lines = BandService.logLines();
        Collections.reverse(lines);
        for (int i = 0; i < Math.min(40, lines.size()); i++) lg.append(lines.get(i)).append('\n');
        log.setText(lg.length() == 0 ? "（还没有）" : lg.toString());
    }

    private static List<String> ips() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /* ── 心跳直播 ── */

    /** 把一次测量的快照画到卡片上：BPM / 最近一拍多久之前 / lag / 拍数 / 起止。 */
    private void renderHrLive(BandSession bs) {
        HrLiveSession.Snapshot sn = bs == null ? null : bs.hrLiveSnapshot();
        boolean ready = bs != null && bs.isReady();
        boolean running = bs != null && bs.hrLiveRunning();
        hr1Btn.setEnabled(ready && !running);
        hr3Btn.setEnabled(ready && !running);
        hrStopBtn.setEnabled(running);
        if (bs == null) { hrTxt.setText("先连上手环"); return; }
        if (sn == null) {
            hrTxt.setText(ready ? "还没测过。点「开始」—— 60 秒后能看到这一分钟里每一拍的心率。" : "先连上手环");
            return;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(sn.running ? "● 正在测 · " + sn.plannedSeconds + " 秒"
                              : "○ " + endText(sn.endReason) + " · 用时 " + oneDp(sn.elapsedMs(now) / 1000.0) + " 秒")
          .append('\n');
        sb.append("心率 ").append(sn.bpm > 0 ? sn.bpm + " bpm" : "—")
          .append("（整段平均 ").append(sn.bpmAvg > 0 ? sn.bpmAvg + " bpm" : "—").append("）").append('\n');
        sb.append("已收 ").append(sn.beats).append(" 拍");
        if (sn.dropped > 0) {
            sb.append(" · 丢 ").append(sn.dropped).append("（sqi0 ").append(sn.droppedSqi0)
              .append(" / 范围 ").append(sn.droppedRange).append("）");
        }
        sb.append('\n');
        if (sn.lastBeatAtMs > 0) {
            sb.append("最近一拍：").append(sn.lastBeatAgoMs(now)).append(" ms 前").append('\n');
            sb.append("lag 估计：").append(sn.lagMs < 0 ? "—" : sn.lagMs + " ms").append('\n');
        } else if (sn.running) {
            sb.append("还在等第一拍…").append('\n');
        } else {
            sb.append("这次没收到拍（手环没戴紧 / 不肯开 0x19）。").append('\n');
        }
        if (sn.firstPacketAfterMs >= 0) sb.append("首包：开流后 ").append(sn.firstPacketAfterMs).append(" ms").append('\n');
        if (sn.startedAtMs > 0) {
            sb.append("开始 ").append(hhmmss(sn.startedAtMs));
            if (sn.endedAtMs > 0) sb.append(" → ").append(hhmmss(sn.endedAtMs));
            sb.append('\n');
        }
        if (sn.error != null) sb.append("出错：").append(sn.error);
        hrTxt.setText(sb.toString().trim());
    }

    private static String endText(String reason) {
        if (reason == null) return "结束";
        switch (reason) {
            case "timeup": return "到点结束";
            case "stopped": return "手动停的";
            case "no-data": return "没收到数据";
            case "interrupted": return "连接断了";
            case "error": return "出错";
            default: return reason;
        }
    }

    private static String oneDp(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static String hhmmss(long ms) {
        // 这个跟界面上的语言无关，固定用 24 小时制
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(ms));
    }

    /* ── tiny view helpers ── */

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView note(String s) { TextView t = text(s, 12, sub, false); t.setPadding(0, dp(8), 0, 0); return t; }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(card); g.setCornerRadius(dp(18)); g.setStroke(dp(1), Color.argb(28, 120, 80, 95));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        TextView t = text(title, 14, sub, true);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        return c;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(ink); b.setTextSize(14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)));
        g.setCornerRadius(dp(22));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private EditText input(LinearLayout parent, String hint, String val, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(val); e.setInputType(type); e.setTextColor(ink); e.setHintTextColor(sub); e.setTextSize(14);
        e.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
