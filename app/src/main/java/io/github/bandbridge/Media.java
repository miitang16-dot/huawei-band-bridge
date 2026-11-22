package io.github.bandbridge;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.List;

/**
 * Phone playback ⇄ band music page.
 *  - With notification access granted (MediaListener): reads the active player's title / artist / state
 *    and controls it through its MediaController.
 *  - Without it: buttons still work through media key events; the band just shows no title.
 */
final class Media {

    interface Changed { void onChanged(); }

    private final Context app;
    private final Changed changed;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaController cur;
    private MediaSessionManager msm;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { changed.onChanged(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { changed.onChanged(); }
        @Override public void onSessionDestroyed() { pick(null); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb = this::pick;

    Media(Context c, Changed changed) { this.app = c.getApplicationContext(); this.changed = changed; }

    static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    void start() {
        if (!hasAccess(app)) return;
        try {
            msm = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(app, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessionsCb, cn, h);
            pick(msm.getActiveSessions(cn));
        } catch (Throwable ignored) { }
    }

    void stop() {
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsCb); } catch (Throwable ignored) { }
        if (cur != null) { try { cur.unregisterCallback(cb); } catch (Throwable ignored) { } cur = null; }
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) for (MediaController m : list) {
            PlaybackState st = m.getPlaybackState();
            if (best == null || (st != null && st.getState() == PlaybackState.STATE_PLAYING)) best = m;
        }
        if (cur != null) { try { cur.unregisterCallback(cb); } catch (Throwable ignored) { } }
        cur = best;
        if (cur != null) { try { cur.registerCallback(cb, h); } catch (Throwable ignored) { } }
        changed.onChanged();
    }

    /** {title, artist, playing} or null */
    JSONObject nowPlaying() {
        MediaController m = cur;
        if (m == null) return null;
        try {
            MediaMetadata md = m.getMetadata();
            PlaybackState st = m.getPlaybackState();
            JSONObject o = new JSONObject();
            o.put("title", md == null ? "" : String.valueOf(md.getString(MediaMetadata.METADATA_KEY_TITLE)));
            o.put("artist", md == null ? "" : String.valueOf(md.getString(MediaMetadata.METADATA_KEY_ARTIST)));
            o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
            return o;
        } catch (Throwable t) { return null; }
    }

    /** 1 play, 2 pause, 3 previous, 4 next, 5 volume up, 6 volume down */
    void button(int b) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (b == 5) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); return; }
        if (b == 6) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); return; }
        MediaController m = cur;
        if (m != null) {
            MediaController.TransportControls tc = m.getTransportControls();
            switch (b) { case 1: tc.play(); return; case 2: tc.pause(); return; case 3: tc.skipToPrevious(); return; case 4: tc.skipToNext(); return; default: return; }
        }
        int key = b == 1 ? KeyEvent.KEYCODE_MEDIA_PLAY : b == 2 ? KeyEvent.KEYCODE_MEDIA_PAUSE : b == 3 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : b == 4 ? KeyEvent.KEYCODE_MEDIA_NEXT : 0;
        if (key == 0) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }
}
