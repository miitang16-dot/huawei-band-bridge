package io.github.bandbridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import io.github.bandbridge.core.Probe;

/**
 * Keeping the band connection alive on phones that kill background apps (HarmonyOS especially).
 *
 * <ul>
 *   <li>Battery-optimisation exemption: asked for from the app; it also lets the watchdog start
 *       the foreground service from the background on Android 12+.</li>
 *   <li>Boot: reconnect after the phone restarts (if the user left it connected and the switch is on).</li>
 *   <li>Watchdog: an inexact alarm every 15 minutes restarts the service if the system killed it.</li>
 *   <li>Restart soon after the service dies without the user pressing 断开 (task removed, process killed).</li>
 * </ul>
 *
 * "Wanted" means the user pressed 连接 and has not pressed 断开 since (pref {@code auto}).
 */
final class KeepAlive {

    static final String ACTION_WATCHDOG = "io.github.bandbridge.WATCHDOG";
    private static final long WATCHDOG_MS = 15 * 60_000L;

    private KeepAlive() {}

    static boolean wanted(Context c) { return c.getSharedPreferences(BandService.SP, Context.MODE_PRIVATE).getBoolean("auto", false); }
    static boolean bootWanted(Context c) { return c.getSharedPreferences(BandService.SP, Context.MODE_PRIVATE).getBoolean("boot", true); }
    static void setBoot(Context c, boolean on) { c.getSharedPreferences(BandService.SP, Context.MODE_PRIVATE).edit().putBoolean("boot", on).apply(); }

    static boolean batteryExempt(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Opens the system dialog "允许 手环桥 始终在后台运行?"; falls back to the battery-optimisation list. */
    @SuppressLint("BatteryLife")
    static void askBatteryExempt(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable t) {
            try { a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Throwable ignored) { }
        }
    }

    /** This app's system info page (on Huawei phones the launch / battery options are reachable from there). */
    static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
        } catch (Throwable ignored) { }
    }

    /** Start the service if the user wants a connection and it is not running. Safe to call from anywhere. */
    static void ensureRunning(Context c, String why) {
        if (!wanted(c) || BandService.instance != null) return;
        try {
            BandService.start(c);
            Probe.post("keepalive", "start | " + why);
        } catch (Throwable t) {
            // Android 12+: starting a foreground service from the background is refused unless exempt
            Probe.post("keepalive", "start refused | " + why + " | " + t.getClass().getSimpleName());
        }
    }

    private static PendingIntent watchdogIntent(Context c) {
        Intent i = new Intent(c, Receiver.class).setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void scheduleWatchdog(Context c, long inMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs, watchdogIntent(c));
        } catch (Throwable ignored) { }
    }

    static void scheduleWatchdog(Context c) { scheduleWatchdog(c, WATCHDOG_MS); }

    static void cancelWatchdog(Context c) {
        try { ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(watchdogIntent(c)); } catch (Throwable ignored) { }
    }

    /** Boot, app update and the watchdog alarm all land here. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            if (!wanted(c)) return;
            if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
                if (bootWanted(c)) ensureRunning(c, "boot");
            } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                ensureRunning(c, "updated");
            } else if (ACTION_WATCHDOG.equals(a)) {
                ensureRunning(c, "watchdog");
            }
            scheduleWatchdog(c);
        }
    }
}
