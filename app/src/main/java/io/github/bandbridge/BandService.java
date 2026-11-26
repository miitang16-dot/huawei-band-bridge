package io.github.bandbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import io.github.bandbridge.core.BandSession;
import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.data.DeviceInit;
import io.github.bandbridge.core.data.HrLiveSession;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that keeps the band connection (BandSession) and the MCP server alive
 * while the screen is off. Swiping the app away from recents does not stop it; the
 * "断开" button in the app (or the notification action) does.
 */
public class BandService extends Service {

    static final String SP = "bandbridge";
    static final int MCP_PORT = 8848;
    private static final String CH = "bandbridge";
    private static final int NID = 1;

    /** UI hook: the Activity sets this to refresh itself. Called on arbitrary threads. */
    interface Ui { void refresh(); }
    static volatile Ui ui;
    static volatile BandService instance;

    static final ArrayDeque<String> LOG = new ArrayDeque<>();
    /** 初始化 + 手环主动问的东西（hwinit / hwasync），单独留着给「复制诊断信息」用，不会被别的日志挤掉 */
    static final ArrayDeque<String> DIAG = new ArrayDeque<>();
    static volatile String calib = "";            // calibration progress text for the UI

    BandSession session;
    McpServer mcp;
    private Media media;
    private volatile long wxFetchAt = 0;

    static void start(Context c) {
        Intent i = new Intent(c, BandService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, BandService.class)); }

    static String token(Context c) {
        SharedPreferences sp = c.getSharedPreferences(SP, MODE_PRIVATE);
        String t = sp.getString("token", "");
        if (t.isEmpty()) {
            final String abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 24; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
            t = sb.toString();
            sp.edit().putString("token", t).apply();
        }
        return t;
    }

    static void newToken(Context c) { c.getSharedPreferences(SP, MODE_PRIVATE).edit().remove("token").apply(); }
    static boolean mcpWanted(Context c) { return c.getSharedPreferences(SP, MODE_PRIVATE).getBoolean("mcp", true); }

    private static void ping() { Ui u = ui; if (u != null) try { u.refresh(); } catch (Throwable ignored) { } }

    @Override public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
        instance = this;
        Probe.setSink((kind, text) -> {
            synchronized (LOG) {
                LOG.addLast(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + "  " + kind + " | " + text);
                while (LOG.size() > 80) LOG.removeFirst();
            }
            if ("hwinit".equals(kind) || "hwasync".equals(kind)) {
                synchronized (DIAG) {
                    DIAG.addLast(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + "  " + kind + " | " + text);
                    while (DIAG.size() > 150) DIAG.removeFirst();
                }
            }
            ping();
        });
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "手环桥 · 连接", NotificationManager.IMPORTANCE_LOW));
        Notification n = note("正在连接手环…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NID, n);

        media = new Media(this, () -> { BandSession s = session; if (s != null) s.nowPlayingChanged(); });
        media.start();

        session = new BandSession(this, new BandSession.Hooks() {
            @Override public JSONObject weather() {
                JSONObject w = WeatherFetch.cached(BandService.this, 6 * 3600_000L);
                if (w == null || System.currentTimeMillis() - wxAge() > 50 * 60_000L) weatherNeeded();
                return w;
            }
            @Override public void weatherNeeded() {
                if (System.currentTimeMillis() - wxFetchAt < 10 * 60_000L) return;
                wxFetchAt = System.currentTimeMillis();
                new Thread(() -> { if (WeatherFetch.fetch(BandService.this)) { BandSession s = session; if (s != null) s.weatherChanged(); } }, "bandbridge-weather").start();
            }
            @Override public JSONObject nowPlaying() { return media.nowPlaying(); }
            @Override public void musicButton(int b) { media.button(b); }
            @Override public DeviceInit.Profile profile() { return loadProfile(BandService.this); }
            @Override public void onState(String st) { updateNote(); ping(); }
            @Override public void onData(int n) { updateNote(); ping(); }
            @Override public void onCalibration(String st, int a, int b) {
                calib = "measuring".equals(st) ? "测量中… 还剩 " + a + " 秒（收到 " + b + " 次心跳间隔）"
                        : "done".equals(st) ? "校准好了：这一分钟的压力 " + a + "。手环之后会自己测。"
                        : "没测成（心跳信号太少）。手环戴紧一点、坐着别动，再试一次。";
                ping();
            }
            @Override public void onHrLive(HrLiveSession.Snapshot snapshot) { ping(); }
        });
        session.start();
        if (mcpWanted(this)) startMcp();
        // the user asked for a connection (this also covers a restart by the system or the watchdog)
        getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("auto", true).apply();
        KeepAlive.scheduleWatchdog(this);
    }

    private long wxAge() { return getSharedPreferences(WeatherFetch.SP, MODE_PRIVATE).getLong("at", 0); }

    void startMcp() {
        if (mcp != null && mcp.isRunning()) return;
        try {
            mcp = new McpServer(MCP_PORT, token(this), new BandTools(session));
            mcp.start();
        } catch (Throwable t) { Probe.post("mcp", "start fail | " + t.getMessage()); mcp = null; }
        ping();
    }

    void stopMcp() { if (mcp != null) mcp.stop(); mcp = null; ping(); }

    void restartMcp() { stopMcp(); startMcp(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            // 断开 from the notification: same as the button in the app, so the watchdog leaves it off
            getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("auto", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** Swiped away from recents: the foreground service normally survives; if the ROM kills it anyway, check back soon. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (KeepAlive.wanted(this)) KeepAlive.scheduleWatchdog(this, 60_000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        instance = null;
        if (KeepAlive.wanted(this)) KeepAlive.scheduleWatchdog(this, 30_000L);   // died without 断开: come back
        else KeepAlive.cancelWatchdog(this);
        try { session.stop(); } catch (Throwable ignored) { }
        try { stopMcp(); } catch (Throwable ignored) { }
        try { media.stop(); } catch (Throwable ignored) { }
        ping();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /* ── notification ── */

    String stateText() {
        BandSession s = session;
        if (s == null) return "没在运行";
        switch (s.phase()) {
            case BandSession.PHASE_READY: return "已连接" + (s.model().isEmpty() ? "" : " · " + s.model()) + (s.battery() >= 0 ? " · 电量 " + s.battery() + "%" : "");
            case BandSession.PHASE_AUTH: return "认证中…（第一次连接时手环上可能要点确认）";
            case BandSession.PHASE_SCAN: return "在找手环…";
            default: return s.lastError().isEmpty() ? "没连上" : "没连上：" + s.lastError();
        }
    }

    private Notification note(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BandService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("手环桥")
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "断开", stop).build())
                .setOngoing(true)
                .build();
    }

    private void updateNote() {
        try { getSystemService(NotificationManager.class).notify(NID, note(stateText() + (mcp != null && mcp.isRunning() ? " · MCP 开着" : ""))); } catch (Throwable ignored) { }
    }

    /* ── profile (only on this phone) ── */

    static DeviceInit.Profile loadProfile(Context c) {
        SharedPreferences sp = c.getSharedPreferences(SP, MODE_PRIVATE);
        int h = sp.getInt("height", 0);
        float w = sp.getFloat("weight", 0);
        String b = sp.getString("birthday", "");
        if (h <= 0 || w <= 0 || !b.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        String[] p = b.split("-");
        return new DeviceInit.Profile(h, w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), sp.getInt("gender", 2));
    }

    static List<String> logLines() { synchronized (LOG) { return new ArrayList<>(LOG); } }
    static List<String> diagLines() { synchronized (DIAG) { return new ArrayList<>(DIAG); } }
}
