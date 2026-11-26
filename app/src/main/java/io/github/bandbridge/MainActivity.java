package io.github.bandbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.bandbridge.core.BandSession;
import io.github.bandbridge.core.data.HrLiveSession;
import io.github.bandbridge.core.proto.hr.BeatReplay;

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

/**
 * Main screen. Top to bottom: the connection card (the one emphasised block), a link to the
 * health page, heartbeat live, band features, the MCP server, profile, data and log.
 * Built with {@link Ui}; plain framework views, no libraries.
 */
public class MainActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Ui.Screen screen;

    /* connection */
    private TextView connPill, connTitle, connMeta, connHint, connBtn, syncBtn;
    /* health link */
    private TextView healthSub;
    private volatile boolean healthLoading;
    /* heartbeat live */
    private TextView hrBpm, hrUnit, hrState, hrFoot, hrHelp, hr1Btn, hr3Btn, hrStopBtn;
    private TextView[] hrStat = new TextView[6];
    private PulseView hrPulse;
    private TextView hrPulseTxt;
    private HrLiveSession.Snapshot lastReplaySnap;
    /* features */
    private TextView calibSub, calibBtn, musicSub, musicBtn, bondSub, bondBtn;
    private TextView batterySub, batteryBtn;
    private Switch bootSwitch;
    /* MCP */
    private Switch mcpSwitch;
    private TextView mcpPill, mcpAddr, mcpToken, mcpTokenBtn;
    private boolean showToken = false;
    /* data */
    private TextView exportSub, logView, logToggle;
    private boolean logOpen = false;

    /** false while the crash screen is showing: refresh() must not touch views that were never built. */
    private boolean built = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashGuard.install(this);
        String last = CrashGuard.last(this);
        if (last != null) { CrashGuard.show(this, last, this::recreate); return; }
        try {
            build();
            built = true;
        } catch (Throwable t) {
            CrashGuard.save(this, "MainActivity.build", t);
            CrashGuard.show(this, CrashGuard.last(this), null);
        }
    }

    private void build() {
        ui = new Ui(this);
        Ui.Screen s = ui.screen();
        screen = s;
        ui.barTitle(s, "手环桥", false);
        LinearLayout col = s.body;

        buildConnection(col);
        buildHealthLink(col);
        buildHrLive(col);
        buildFeatures(col);
        buildKeepAlive(col);
        buildMcp(col);
        buildProfile(col);
        buildData(col);

        TextView foot = ui.small("手环桥 " + BuildInfo.VERSION + " · AGPL-3.0 · 不是华为官方应用，数据只存在这台手机上");
        foot.setPadding(ui.dp(4), ui.dp(24), ui.dp(4), 0);
        col.addView(foot);

        askPermissions();
        // opened after the system killed the service: reconnect if the user had left it connected
        if (hasBt()) KeepAlive.ensureRunning(this, "open");
    }

    @Override protected void onResume() {
        super.onResume();
        if (!built) return;
        BandService.ui = () -> h.post(this::refresh);
        refresh();
        h.postDelayed(tick, 5000);
    }
    @Override protected void onPause() { super.onPause(); BandService.ui = null; h.removeCallbacks(tick); }
    /** Every second while heartbeat live runs (countdown, last beat), every 5 s otherwise. */
    private final Runnable tick = new Runnable() { @Override public void run() {
        refresh();
        BandService s = BandService.instance;
        boolean live = s != null && s.session != null && s.session.hrLiveRunning();
        h.postDelayed(this, live ? 1000 : 5000);
    } };

    /* ── build ─────────────────────────────────────────────────────────────── */

    private void buildConnection(LinearLayout col) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18));
        Charts.PanelBg bg = new Charts.PanelBg(ui.t.surface, ui.t.line, 0.75f, 20, getResources().getDisplayMetrics().density);
        bg.setHeroTints(ui.t.accent, Charts.Palette.SLEEP);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.dp(8);
        col.addView(c, lp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        connPill = ui.pill("", ui.t.faint);
        top.addView(connPill);
        c.addView(top);

        connTitle = ui.text("", 24, ui.t.ink, Ui.BOLD);
        connTitle.setPadding(0, ui.dp(14), 0, 0);
        c.addView(connTitle);
        connMeta = ui.meta("");
        connMeta.setPadding(0, ui.dp(6), 0, 0);
        c.addView(connMeta);

        connBtn = ui.primary("连接", v -> toggleConnect());
        syncBtn = ui.tonal("立即同步", v -> {
            BandService s = BandService.instance;
            if (s != null && s.session != null && s.session.isReady()) { s.session.syncNow(); toast("在同步了"); }
            else toast("手环还没连上");
        });
        ui.buttons(c, syncBtn, connBtn);

        connHint = ui.small("第一次用：先在「华为运动健康」里删除这个手环（或把手环恢复出厂）。两个 App 不能同时连着它。");
        connHint.setPadding(0, ui.dp(12), 0, 0);
        c.addView(connHint);
    }

    private void buildHealthLink(LinearLayout col) {
        LinearLayout g = ui.group(col);
        ((LinearLayout.LayoutParams) g.getLayoutParams()).topMargin = ui.dp(12);
        Ui.Chevron chev = new Ui.Chevron(this, ui.t.faint, false);
        chev.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
        healthSub = ui.row(g, "健康", "心率、血氧、步数、睡眠、压力和情绪", chev,
                v -> startActivity(new Intent(this, HealthActivity.class)));
    }

    private void buildHrLive(LinearLayout col) {
        ui.section(col, "心跳直播");
        LinearLayout c = ui.card(col);

        // status pill on its own line, then the number with its unit on a shared baseline
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        hrState = ui.pill("", ui.t.faint);
        top.addView(hrState);
        c.addView(top);

        LinearLayout num = new LinearLayout(this);
        num.setBaselineAligned(true);
        hrBpm = ui.text("—", 44, ui.t.ink, Ui.BOLD);
        hrBpm.setIncludeFontPadding(true);
        num.addView(hrBpm);
        hrUnit = ui.text(" bpm", 15, ui.t.sub, null);
        hrUnit.setIncludeFontPadding(true);
        num.addView(hrUnit);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = ui.dp(10);
        c.addView(num, nlp);

        TextView lead = ui.meta("手环一拍一拍推送心跳间隔，换算成心率。比每分钟一格细得多，测的时候戴紧、坐着别说话。");
        lead.setPadding(0, ui.dp(6), 0, 0);
        c.addView(lead);

        /* 逐拍回放：手环是攒几秒成批推送的（真机约 4 秒一包），所以这里是「按包回放」而不是「此刻这一拍」 */
        LinearLayout pulseRow = new LinearLayout(this);
        pulseRow.setGravity(Gravity.CENTER_VERTICAL);
        hrPulse = new PulseView(this, ui.t.accent, ui.t.track, ui.t.faint);
        pulseRow.addView(hrPulse, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        LinearLayout pulseCol = new LinearLayout(this);
        pulseCol.setOrientation(LinearLayout.VERTICAL);
        pulseCol.setPadding(ui.dp(12), 0, 0, 0);
        pulseCol.addView(ui.text("逐拍回放", 13, ui.t.ink, Ui.MEDIUM));
        hrPulseTxt = ui.small("测的时候，按包里收到的间隔一拍一拍地跳；不是「此刻这一拍」。");
        hrPulseTxt.setPadding(0, ui.dp(3), 0, 0);
        pulseCol.addView(hrPulseTxt);
        pulseRow.addView(pulseCol, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams pulseLp = new LinearLayout.LayoutParams(-1, -2);
        pulseLp.topMargin = ui.dp(10);
        c.addView(pulseRow, pulseLp);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        String[] labels = {"有效拍", "丢弃样本", "每包样本", "lag", "最近一拍", "首包"};
        for (int i = 0; i < 6; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(0, ui.dp(12), ui.dp(8), 0);
            hrStat[i] = ui.text("—", 17, ui.t.ink, Ui.MEDIUM);
            cell.addView(hrStat[i]);
            TextView l = ui.small(labels[i]);
            l.setPadding(0, ui.dp(3), 0, 0);
            cell.addView(l);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f));
            glp.width = 0;
            grid.addView(cell, glp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = ui.dp(4);
        c.addView(grid, glp);

        hrFoot = ui.small("");
        hrFoot.setPadding(0, ui.dp(12), 0, 0);
        c.addView(hrFoot);

        hr1Btn = ui.primary("测 1 分钟", v -> startHrLive(HrLiveSession.DEFAULT_SECONDS));
        hr3Btn = ui.tonal("3 分钟", v -> startHrLive(180));
        hrStopBtn = ui.tonal("停止", v -> {
            BandService s = BandService.instance;
            if (s != null && s.session != null) s.session.hrLiveStop();
        });
        LinearLayout row = ui.buttons(c, hr1Btn, hr3Btn, hrStopBtn);
        ((LinearLayout.LayoutParams) hr1Btn.getLayoutParams()).weight = 1.4f;

        hrHelp = ui.small("丢弃样本：手环自己标为无效的（sqi=0），或者间隔小于 250 ms、大于 3000 ms 的。它们不是漏掉的心跳。\n"
                + "lag：包到达的时间减去最后一拍的时间。手环是攒几秒再成批发的（实测约 4 秒一包），所以 lag 几秒是正常的。\n"
                + "每包样本：一个包里装了几个样本，越大说明攒得越久。3 分钟档在 Band 10 上测通过，其它型号没试过。");
        hrHelp.setLineSpacing(0, 1.4f);
        hrHelp.setPadding(0, ui.dp(10), 0, 0);
        hrHelp.setVisibility(View.GONE);
        TextView more = ui.text("这些数字是什么意思", 13, ui.t.accent, Ui.MEDIUM);
        more.setPadding(0, ui.dp(14), 0, ui.dp(2));
        more.setOnClickListener(v -> {
            boolean open = hrHelp.getVisibility() != View.VISIBLE;
            hrHelp.setVisibility(open ? View.VISIBLE : View.GONE);
            more.setText(open ? "收起说明" : "这些数字是什么意思");
        });
        c.addView(more);
        c.addView(hrHelp);
    }

    private void buildFeatures(LinearLayout col) {
        ui.section(col, "手环功能");
        LinearLayout g = ui.group(col);

        calibBtn = ui.chipButton("校准", v -> {
            BandService s = BandService.instance;
            if (s == null || s.session == null || !s.session.calibrateStress()) toast("手环还没连上");
        });
        calibSub = ui.row(g, "压力校准", "", calibBtn, null);

        /* 登记到手机蓝牙（系统配对）：只在用户手动点时做，绝不自动 */
        bondBtn = ui.chipButton("登记", v -> {
            BandService s = BandService.instance;
            BandSession bs = s == null ? null : s.session;
            if (bs == null) { toast("手环还没连上"); return; }
            String r;
            if (bs.sysBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                r = bs.sysUnbond();
                toast("ok".equals(r) || "already".equals(r) ? "已从手机蓝牙里取消登记" : "取消登记没成功：" + r);
            } else {
                r = bs.sysBond();
                toast("ok".equals(r) ? "已发起配对，手机或手环上可能要确认一下"
                        : "already".equals(r) ? "已经登记过了"
                        : "notready".equals(r) ? "先等手环连上再登记" : "登记没成功：" + r);
            }
            refresh();
        });
        bondSub = ui.row(g, "登记到手机蓝牙", "", bondBtn, null);

        musicBtn = ui.chipButton("去开启", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        musicSub = ui.row(g, "手环音乐页", "", musicBtn, null);

        ui.hairline(g);
        LinearLayout wx = new LinearLayout(this);
        wx.setOrientation(LinearLayout.VERTICAL);
        wx.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));
        wx.addView(ui.text("天气", 15, ui.t.ink, Ui.MEDIUM));
        TextView ws = ui.meta("从 Open-Meteo 获取，只发送约 10 公里精度的位置。下面的名字会显示在手环上。");
        ws.setPadding(0, ui.dp(3), 0, ui.dp(12));
        wx.addView(ws);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.BOTTOM);
        LinearLayout fieldBox = new LinearLayout(this);
        fieldBox.setOrientation(LinearLayout.VERTICAL);
        EditText place = ui.field(fieldBox, "地名", "比如 东京", getSharedPreferences(WeatherFetch.SP, MODE_PRIVATE).getString("place", ""), InputType.TYPE_CLASS_TEXT);
        line.addView(fieldBox, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView save = ui.tonal("保存", v -> {
            getSharedPreferences(WeatherFetch.SP, MODE_PRIVATE).edit().putString("place", place.getText().toString().trim()).putLong("at", 0).apply();
            BandService s = BandService.instance; if (s != null && s.session != null) s.session.weatherChanged();
            toast("保存好了，下次给手环送天气时用这个名字");
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = ui.dp(8);
        line.addView(save, slp);
        wx.addView(line);
        g.addView(wx);
    }

    private void buildKeepAlive(LinearLayout col) {
        ui.section(col, "后台运行");
        LinearLayout g = ui.group(col);

        batteryBtn = ui.chipButton("允许", v -> KeepAlive.askBatteryExempt(this));
        batterySub = ui.row(g, "不受电池优化限制", "", batteryBtn, null);

        bootSwitch = new Switch(this);
        bootSwitch.setChecked(KeepAlive.bootWanted(this));
        bootSwitch.setOnCheckedChangeListener((b, on) -> KeepAlive.setBoot(this, on));
        ui.row(g, "开机自动连接", "手机重启后自己连上手环（前提是你没有点「断开」）", bootSwitch, null);

        ui.row(g, "华为 / 荣耀手机还要设置一步",
                "在「设置 › 应用和服务 › 应用启动管理」里找到手环桥，关掉「自动管理」，把「允许自启动」「允许关联启动」「允许后台活动」三个都打开。",
                ui.chipButton("去设置", v -> KeepAlive.openAppSettings(this)), null);
    }

    private void buildMcp(LinearLayout col) {
        ui.section(col, "给 AI 用 · MCP 服务");
        LinearLayout c = ui.card(col);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.addView(ui.text("MCP 服务", 15, ui.t.ink, Ui.MEDIUM));
        mcpPill = ui.meta("");
        mcpPill.setPadding(0, ui.dp(3), 0, 0);
        tl.addView(mcpPill);
        top.addView(tl, new LinearLayout.LayoutParams(0, -2, 1f));
        mcpSwitch = new Switch(this);
        mcpSwitch.setOnClickListener(v -> toggleMcp());
        top.addView(mcpSwitch);
        c.addView(top);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12));
        box.setBackground(ui.round(ui.t.surface2, 12));
        TextView al = ui.small("地址");
        box.addView(al);
        mcpAddr = ui.text("", 13, ui.t.ink, Ui.MONO);
        mcpAddr.setTextIsSelectable(true);
        mcpAddr.setPadding(0, ui.dp(3), 0, ui.dp(10));
        box.addView(mcpAddr);
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.addView(ui.small("口令"));
        mcpToken = ui.text("", 13, ui.t.ink, Ui.MONO);
        mcpToken.setTextIsSelectable(true);
        mcpToken.setPadding(0, ui.dp(3), 0, 0);
        tcol.addView(mcpToken);
        tr.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        mcpTokenBtn = ui.text("显示", 13, ui.t.accent, Ui.MEDIUM);
        mcpTokenBtn.setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8));
        mcpTokenBtn.setOnClickListener(v -> { showToken = !showToken; refresh(); });
        tr.addView(mcpTokenBtn);
        box.addView(tr);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = ui.dp(14);
        c.addView(box, blp);

        ui.buttons(c,
                ui.primary("复制配置", v -> copyConfig()),
                ui.tonal("换一个口令", v -> {
                    BandService.newToken(this);
                    BandService s = BandService.instance; if (s != null) s.restartMcp();
                    refresh(); toast("旧口令已作废，记得更新 AI 客户端里的配置");
                }));
        TextView n = ui.small("电脑和手机要连同一个 Wi-Fi（或者用 Tailscale 这类组网）。口令等于钥匙，不要发给别人；不要把端口开到公网。");
        n.setPadding(0, ui.dp(12), 0, 0);
        c.addView(n);
    }

    private void buildProfile(LinearLayout col) {
        ui.section(col, "个人资料");
        LinearLayout c = ui.card(col);
        TextView lead = ui.meta("手环用它估算卡路里和压力。只存在这台手机上，也会发给手环。");
        c.addView(lead);
        SharedPreferences sp = getSharedPreferences(BandService.SP, MODE_PRIVATE);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        EditText[] f = new EditText[3];
        LinearLayout[] cells = new LinearLayout[4];
        for (int i = 0; i < 4; i++) {
            cells[i] = new LinearLayout(this);
            cells[i].setOrientation(LinearLayout.VERTICAL);
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2, 1f));
            glp.width = 0;
            glp.topMargin = ui.dp(14);
            if (i % 2 == 0) glp.rightMargin = ui.dp(6); else glp.leftMargin = ui.dp(6);
            grid.addView(cells[i], glp);
        }
        f[0] = ui.field(cells[0], "身高（cm）", "160", sp.getInt("height", 0) > 0 ? String.valueOf(sp.getInt("height", 0)) : "", InputType.TYPE_CLASS_NUMBER);
        f[1] = ui.field(cells[1], "体重（kg）", "50", sp.getFloat("weight", 0) > 0 ? trimFloat(sp.getFloat("weight", 0)) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        f[2] = ui.field(cells[2], "生日", "2008-05-20", sp.getString("birthday", ""), InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);

        // gender: two-option segmented control
        TextView gl = ui.text("性别", 12, ui.t.sub, Ui.MEDIUM);
        gl.setPadding(ui.dp(2), 0, 0, ui.dp(6));
        cells[3].addView(gl);
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        seg.setBackground(ui.round(ui.t.surface2, 12));
        final int[] gender = {sp.getInt("gender", 2)};
        TextView fem = ui.text("女", 15, ui.t.ink, Ui.MEDIUM), male = ui.text("男", 15, ui.t.ink, Ui.MEDIUM);
        TextView[] opts = {fem, male};
        Runnable paint = () -> {
            for (int i = 0; i < 2; i++) {
                boolean on = (i == 0 && gender[0] != 1) || (i == 1 && gender[0] == 1);
                opts[i].setBackground(on ? ui.round(ui.t.surface, 9) : null);
                opts[i].setTextColor(on ? ui.t.ink : ui.t.sub);
            }
        };
        for (int i = 0; i < 2; i++) {
            final int val = i == 0 ? 2 : 1;
            opts[i].setGravity(Gravity.CENTER);
            opts[i].setMinHeight(ui.dp(40));
            opts[i].setOnClickListener(v -> { gender[0] = val; paint.run(); });
            seg.addView(opts[i], new LinearLayout.LayoutParams(0, -2, 1f));
        }
        paint.run();
        cells[3].addView(seg, new LinearLayout.LayoutParams(-1, -2));
        c.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        ui.buttons(c, ui.tonal("保存并发给手环", v -> {
            try {
                String bd = f[2].getText().toString().trim();
                if (!bd.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
                sp.edit().putInt("height", Integer.parseInt(f[0].getText().toString().trim()))
                        .putFloat("weight", Float.parseFloat(f[1].getText().toString().trim()))
                        .putString("birthday", bd)
                        .putInt("gender", gender[0]).apply();
                BandService s = BandService.instance; if (s != null && s.session != null) s.session.profileChanged();
                toast("保存好了");
            } catch (Throwable t) { toast("身高、体重要填数字，生日写成 2008-05-20 这样"); }
        }));
    }

    private void buildData(LinearLayout col) {
        ui.section(col, "数据");
        LinearLayout g = ui.group(col);
        exportSub = ui.row(g, "导出数据", "导出成 JSON 和 CSV（Excel 能打开），只写在手机里",
                ui.chipButton("导出", v -> exportData()), null);

        ui.row(g, "复制诊断信息", "手环型号、固件、命令表、初始化和睡眠同步每一步的结果。别的型号出问题时，把这段发给作者",
                ui.chipButton("复制", v -> copyDiag()), null);

        logToggle = ui.text("展开", 13, ui.t.accent, Ui.MEDIUM);
        ui.row(g, "日志", "排查问题用，只在内存里，退出就清空", logToggle, v -> {
            logOpen = !logOpen;
            logView.setVisibility(logOpen ? View.VISIBLE : View.GONE);
            logToggle.setText(logOpen ? "收起" : "展开");
            refresh();
        });
        logView = ui.text("", 11, ui.t.sub, Ui.MONO);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(0, 1.35f);
        logView.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(16));
        logView.setVisibility(View.GONE);
        g.addView(logView);
    }

    /* ── actions ── */

    private void toggleConnect() {
        if (BandService.instance != null) { getSharedPreferences(BandService.SP, MODE_PRIVATE).edit().putBoolean("auto", false).apply(); BandService.stop(this); }
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

    /** Start one heartbeat-live run (0x19 RRI stream). Returns at once; refresh() reads the snapshot. */
    private void startHrLive(int seconds) {
        BandService s = BandService.instance;
        if (s == null || s.session == null) { toast("手环还没连上"); return; }
        if (!s.session.isReady()) { toast("还在连接，等显示「已连接」再试"); return; }
        if (!s.session.hrLiveStart(seconds)) { toast("已经在测了"); return; }
        toast("开始了，坐着别动，测 " + seconds + " 秒");
        h.removeCallbacks(tick);
        h.post(tick);
        refresh();
    }

    /** Export the local stores (JSON + CSV) on a worker thread. */
    private void exportData() {
        exportSub.setText("导出中…");
        new Thread(() -> {
            try {
                DataExport.Result result = DataExport.export(this);
                runOnUiThread(() -> {
                    exportSub.setText(result.summary());
                    toast(result.written.isEmpty() ? "还没有数据可导出" : "导出好了");
                });
            } catch (Throwable t) {
                String message = String.valueOf(t);
                runOnUiThread(() -> { exportSub.setText("导出失败：" + message); toast("导出失败，原因在日志里"); });
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
            toast("复制好了，里面有口令，只粘到你自己的 AI 客户端");
        } catch (Throwable ignored) { }
    }

    /** 只含 hwinit / hwasync / hwsleep / hwtru / hwfile（型号、固件、命令表、每步回没回、手环主动问了什么、睡眠同步拉到几段），没有口令；睡眠只有段数和分钟合计 */
    private void copyDiag() {
        StringBuilder sb = new StringBuilder();
        sb.append("手环桥 ").append(BuildInfo.VERSION).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        BandService s = BandService.instance;
        BandSession bs = s == null ? null : s.session;
        if (bs != null) sb.append("型号 ").append(bs.model()).append(" · 阶段 ").append(bs.phase()).append('\n');
        List<String> lines = BandService.diagLines();
        if (lines.isEmpty()) sb.append("（还没有诊断记录：先连上手环再复制）\n");
        for (String l : lines) sb.append(l).append('\n');
        try {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("diag", sb.toString()));
            toast("复制好了，里面没有口令和健康数据");
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
        if (!built) return;
        try { refreshViews(); ui.repaint(screen); }
        catch (Throwable t) {
            built = false;
            h.removeCallbacks(tick);
            CrashGuard.save(this, "MainActivity.refresh", t);
            CrashGuard.show(this, CrashGuard.last(this), null);
        }
    }

    private void refreshViews() {
        BandService s = BandService.instance;
        BandSession bs = s == null ? null : s.session;
        renderConnection(s, bs);
        renderHrLive(bs);
        loadHealthLine(bs);

        // features
        if (bs == null) { calibSub.setText("先连上手环"); Ui.enable(calibBtn, false); }
        else {
            calibSub.setText(!BandService.calib.isEmpty() ? BandService.calib
                    : bs.calibration() != null ? "已经校准过，手环会自己测压力"
                    : "还没校准。戴紧手环，坐着不动 1 分钟");
            Ui.enable(calibBtn, bs.isReady() && !bs.calibrating());
            calibBtn.setText(bs.calibration() != null ? "重新校准" : "校准");
        }
        int bond = bs == null ? -1 : bs.sysBondState();
        boolean bonded = bond == android.bluetooth.BluetoothDevice.BOND_BONDED;
        bondSub.setText(bond == -1 ? "连上手环后，可以让它出现在手机的蓝牙设备列表里"
                : bonded ? "手机蓝牙里：已登记（不影响手环桥的连接）"
                : bond == android.bluetooth.BluetoothDevice.BOND_BONDING ? "手机蓝牙里：正在配对…"
                : "手机蓝牙里：没登记。手环桥不需要配对也能用，登记只是让系统蓝牙列表里看得到它");
        bondBtn.setText(bonded ? "取消登记" : "登记");
        Ui.enable(bondBtn, bs != null && (bonded || bs.isReady()));
        boolean music = Media.hasAccess(this);
        musicSub.setText(music ? "手环能切歌，也会显示歌名" : "手环能切歌和暂停。开启通知使用权后还能显示歌名");
        musicBtn.setVisibility(music ? View.GONE : View.VISIBLE);
        boolean exempt = KeepAlive.batteryExempt(this);
        batterySub.setText(exempt ? "已允许，系统不会为了省电停掉连接" : "没允许的话，锁屏久了系统可能会停掉连接");
        batteryBtn.setVisibility(exempt ? View.GONE : View.VISIBLE);

        // MCP
        boolean wanted = BandService.mcpWanted(this);
        boolean mcpOn = s != null && s.mcp != null && s.mcp.isRunning();
        mcpSwitch.setChecked(wanted);
        mcpPill.setText(mcpOn ? "正在运行" : wanted ? "连上手环后自动开启" : "已关闭");
        mcpPill.setTextColor(mcpOn ? ui.t.good : ui.t.sub);
        List<String> ips = ips();
        StringBuilder addr = new StringBuilder();
        if (ips.isEmpty()) addr.append("手机没连 Wi-Fi");
        for (String ip : ips) { if (addr.length() > 0) addr.append('\n'); addr.append("http://").append(ip).append(':').append(BandService.MCP_PORT).append("/mcp"); }
        mcpAddr.setText(addr);
        String tk = BandService.token(this);
        mcpToken.setText(showToken ? tk : tk.substring(0, 4) + " •••• •••• •••• ••••");
        mcpTokenBtn.setText(showToken ? "隐藏" : "显示");

        if (logOpen) {
            StringBuilder lg = new StringBuilder();
            List<String> lines = BandService.logLines();
            Collections.reverse(lines);
            for (int i = 0; i < Math.min(40, lines.size()); i++) { if (i > 0) lg.append('\n'); lg.append(lines.get(i)); }
            logView.setText(lg.length() == 0 ? "还没有日志" : lg);
        }
    }

    private void renderConnection(BandService s, BandSession bs) {
        String meta;
        if (s == null || bs == null) {
            ui.setPill(connPill, "未连接", ui.t.faint);
            connTitle.setText("手环没有连接");
            meta = "点「连接」后会在附近找你的华为手环";
        } else {
            switch (bs.phase()) {
                case BandSession.PHASE_READY:
                    ui.setPill(connPill, "已连接", ui.t.good);
                    connTitle.setText(bs.model().isEmpty() ? "华为手环" : bs.model());
                    break;
                case BandSession.PHASE_AUTH:
                    ui.setPill(connPill, "认证中", ui.t.warn);
                    connTitle.setText("正在认证");
                    break;
                case BandSession.PHASE_SCAN:
                    ui.setPill(connPill, "搜索中", ui.t.warn);
                    connTitle.setText("在找手环");
                    break;
                default:
                    ui.setPill(connPill, "没连上", ui.t.bad);
                    connTitle.setText("没连上手环");
            }
            List<String> bits = new ArrayList<>();
            if (bs.battery() >= 0) bits.add("电量 " + bs.battery() + "%");
            long last = bs.samples().lastTs() * 1000L;
            bits.add(last > 0 ? "数据更新到 " + new SimpleDateFormat("M月d日 HH:mm", Locale.ROOT).format(new Date(last)) : "还没有数据，连上后一分钟内会拉第一次");
            if (bs.phase() == BandSession.PHASE_AUTH) bits.add("第一次连接时，手环上可能要点一下确认");
            if (bs.phase() == BandSession.PHASE_IDLE && !bs.lastError().isEmpty()) bits.add(bs.lastError());
            meta = String.join(" · ", bits);
        }
        connMeta.setText(meta);
        boolean on = s != null;
        connBtn.setText(on ? "断开" : "连接");
        // when connected, "断开" is the secondary action and "立即同步" the primary one
        connBtn.setBackground(ui.ripple(on ? ui.t.surface2 : ui.t.accent, 12));
        connBtn.setTextColor(on ? ui.t.ink : ui.t.onAccent);
        syncBtn.setBackground(ui.ripple(on ? ui.t.accent : ui.t.surface2, 12));
        syncBtn.setTextColor(on ? ui.t.onAccent : ui.t.ink);
        syncBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        // with 立即同步 hidden, 连接 spans the card: drop the gap it kept on its left
        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) connBtn.getLayoutParams();
        int gap = on ? ui.dp(8) : 0;
        if (clp.leftMargin != gap) { clp.leftMargin = gap; connBtn.setLayoutParams(clp); }
        Ui.enable(syncBtn, bs != null && bs.isReady());
        connHint.setVisibility(on && bs != null && bs.isReady() ? View.GONE : View.VISIBLE);
    }

    /** One line under 「健康」: newest heart rate, steps, sleep. Loaded off the UI thread. */
    private void loadHealthLine(BandSession bs) {
        if (healthLoading) return;
        healthLoading = true;
        new Thread(() -> {
            String line = null;
            try {
                HealthData d = HealthData.load(this, bs);
                List<String> bits = new ArrayList<>();
                if (d.hasHr) bits.add("心率 " + d.hr);
                if (d.stepsToday > 0) bits.add(String.format(Locale.ROOT, "%,d 步", d.stepsToday));
                if (d.hasSleep) bits.add("睡了 " + d.sleepTotalMin / 60 + " 小时 " + d.sleepTotalMin % 60 + " 分");
                if (!bits.isEmpty()) line = "今天 · " + String.join(" · ", bits);
                else if (!d.anything) line = "还没同步过数据";
            } catch (Throwable ignored) { }
            final String l = line;
            runOnUiThread(() -> { healthLoading = false; if (l != null) healthSub.setText(l); });
        }, "band-health-line").start();
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

    /* ── heartbeat live ── */

    private void renderHrLive(BandSession bs) {
        HrLiveSession.Snapshot sn = bs == null ? null : bs.hrLiveSnapshot();
        boolean ready = bs != null && bs.isReady();
        boolean running = bs != null && bs.hrLiveRunning();
        Ui.enable(hr1Btn, ready && !running);
        Ui.enable(hr3Btn, ready && !running);
        Ui.enable(hrStopBtn, running);
        long now = System.currentTimeMillis();

        if (sn == null) {
            setBpm(0);
            ui.setPill(hrState, ready ? "还没测过" : "先连上手环", ui.t.faint);
            for (TextView v : hrStat) v.setText("—");
            dimEmptyStats();
            hrFoot.setText(ready ? "点「测 1 分钟」，测完能看到这一分钟里每一拍的心率。" : "");
            hrFoot.setVisibility(ready ? View.VISIBLE : View.GONE);
            updatePulse(null, false, now);
            return;
        }
        setBpm(sn.bpm);
        if (sn.running) {
            long left = Math.max(0, sn.plannedSeconds - sn.elapsedMs(now) / 1000);
            ui.setPill(hrState, "正在测 · 还剩 " + left + " 秒", ui.t.accent);
        } else {
            ui.setPill(hrState, endText(sn.endReason), sn.error != null || "no-data".equals(sn.endReason) ? ui.t.bad : ui.t.good);
        }
        int samples = sn.beats + sn.dropped;
        hrStat[0].setText(String.valueOf(sn.beats));
        hrStat[1].setText(sn.dropped > 0 ? sn.dropped + "" : "0");
        hrStat[2].setText(sn.packets > 0 ? String.format(Locale.ROOT, "%.1f", samples / (float) sn.packets) : "—");
        hrStat[3].setText(sn.lagMs < 0 || sn.lastBeatAtMs <= 0 ? "—" : sn.lagMs + " ms");
        hrStat[4].setText(sn.lastBeatAtMs > 0 ? agoText(sn.lastBeatAgoMs(now)) : "—");
        hrStat[5].setText(sn.firstPacketAfterMs >= 0 ? oneDp(sn.firstPacketAfterMs / 1000.0) + " 秒" : "—");
        dimEmptyStats();

        List<String> foot = new ArrayList<>();
        if (sn.bpmAvg > 0) foot.add("整段平均 " + sn.bpmAvg + " bpm");
        if (sn.dropped > 0) foot.add("丢弃里 sqi=0 有 " + sn.droppedSqi0 + " 个，间隔超范围 " + sn.droppedRange + " 个");
        if (samples > 0) foot.add("样本合计 " + samples + "，共 " + sn.packets + " 个包");
        if (sn.startedAtMs > 0) foot.add(hhmmss(sn.startedAtMs) + (sn.endedAtMs > 0 ? " 到 " + hhmmss(sn.endedAtMs) : " 开始") + "，用时 " + oneDp(sn.elapsedMs(now) / 1000.0) + " 秒");
        if (sn.lastBeatAtMs <= 0) foot.add(sn.running ? "还在等第一拍" : "这次没收到心跳。手环戴紧一点再试");
        if (sn.error != null) foot.add("出错：" + sn.error);
        hrFoot.setText(String.join("\n", foot));
        hrFoot.setVisibility(foot.isEmpty() ? View.GONE : View.VISIBLE);
        updatePulse(sn, running, now);
    }

    /**
     * 心跳回放：每收到一个新包，用这一包收下的 RRI 排一次时间表（{@link BeatReplay}），
     * 让 {@link PulseView} 按真实间隔一跳一跳地演。**手环是攒几秒成批推送的，所以这是
     * 「刚才那几拍」的回放，不是「此刻这一拍」** —— 文案上必须说清楚。
     */
    private void updatePulse(HrLiveSession.Snapshot sn, boolean running, long now) {
        if (hrPulse == null) return;
        if (!running || sn == null) {
            hrPulse.stop();
            lastReplaySnap = null;
            hrPulseTxt.setText("测的时候，按包里收到的间隔一拍一拍地跳；不是「此刻这一拍」。");
            return;
        }
        if (sn == lastReplaySnap) return;             // 同一个包，不重排时间表
        lastReplaySnap = sn;
        int[] rris = BeatReplay.capNewest(sn.lastPacketRris, BeatReplay.DEFAULT_HORIZON_MS);
        if (rris.length == 0) {
            hrPulseTxt.setText("等下一包心跳数据…");
            return;
        }
        hrPulse.start(BeatReplay.offsetsMs(rris), BeatReplay.bpmPerPulse(rris));
        String lag = sn.lagMs >= 0 ? oneDp(sn.lagMs / 1000.0) + " 秒" : "?";
        hrPulseTxt.setText("按这包 " + rris.length + " 个间隔回放 · 这批约 " + lag
                + " 前收到（不是「此刻这一拍」）");
    }

    /** Big number when there is a reading; a quiet「未测量」instead of a lone dash when there is not. */
    private void setBpm(int bpm) {
        if (bpm > 0) {
            hrBpm.setText(String.valueOf(bpm));
            hrBpm.setTextSize(44);
            hrBpm.setTextColor(ui.t.ink);
            hrUnit.setVisibility(View.VISIBLE);
        } else {
            hrBpm.setText("未测量");
            hrBpm.setTextSize(24);
            hrBpm.setTextColor(ui.t.faint);
            hrUnit.setVisibility(View.GONE);
        }
    }

    private void dimEmptyStats() {
        for (TextView v : hrStat) v.setTextColor("—".contentEquals(v.getText()) ? ui.t.faint : ui.t.ink);
    }

    private static String agoText(long ms) {
        if (ms < 1000) return ms + " ms 前";
        if (ms < 60_000) return ms / 1000 + " 秒前";
        if (ms < 3600_000) return ms / 60_000 + " 分钟前";
        return ms / 3600_000 + " 小时前";
    }

    private static String endText(String reason) {
        if (reason == null) return "测完了";
        switch (reason) {
            case "timeup": return "测完了";
            case "stopped": return "手动停止";
            case "no-data": return "没收到数据";
            case "interrupted": return "连接断了";
            case "error": return "出错了";
            default: return reason;
        }
    }

    private static String trimFloat(float f) { return f == (int) f ? String.valueOf((int) f) : String.valueOf(f); }
    private static String oneDp(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String hhmmss(long ms) { return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(ms)); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
