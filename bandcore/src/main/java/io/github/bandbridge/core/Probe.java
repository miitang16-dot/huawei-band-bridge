package io.github.bandbridge.core;

import android.util.Log;

/**
 * Diagnostic log for the protocol stack ("kind | text" lines such as hwauth / hwinit / hwtru).
 * By default it goes to logcat. An app can install a {@link Sink} to also keep the lines
 * (e.g. show them on screen, or write them to a file the user can share when reporting a bug).
 * Nothing is ever sent over the network by this class.
 */
public final class Probe {
    public interface Sink { void onProbe(String kind, String text); }

    private static volatile Sink sink = null;

    private Probe() {}

    public static void setSink(Sink s) { sink = s; }

    public static void post(String kind, String text) {
        try { Log.d("BandBridge", kind + " | " + text); } catch (Throwable ignored) { }
        Sink s = sink;
        if (s != null) { try { s.onProbe(kind, text); } catch (Throwable ignored) { } }
    }
}
