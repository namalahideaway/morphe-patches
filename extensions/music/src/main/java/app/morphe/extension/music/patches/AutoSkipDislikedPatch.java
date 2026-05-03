package app.morphe.extension.music.patches;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.util.List;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs already rated thumbs-down.
 * Skip via several fallback methods, since dispatchMediaKeyEvent silently no-ops
 * without MEDIA_CONTENT_CONTROL and broadcast routing to MusicBrowserService
 * is sometimes ignored.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Context appContext = null;
    private static volatile boolean inflightSkip = false;
    private static volatile long lastSkipNs = 0L;
    private static final long SKIP_DEDUP_NS = 1_500_000_000L;

    private AutoSkipDislikedPatch() {}

    public static void install(final Object containerUnused, final Object dislikeButton) {
        Log.i(TAG, "install() entry");
        try {
            if (dislikeButton instanceof View) {
                appContext = ((View) dislikeButton).getContext().getApplicationContext();
                Log.i(TAG, "  captured app context");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ctx capture failed", t);
        }
    }

    public static void onCustomAction(CharSequence name) {
        if (name == null) return;
        if (!isEnabled()) return;
        String s = name.toString();
        if (!s.equalsIgnoreCase("Undo dislike")
                && !s.contains("Remove from disliked")
                && !s.contains("undo dislike")) {
            return;
        }

        long now = System.nanoTime();
        if (inflightSkip) return;
        if (now - lastSkipNs < SKIP_DEDUP_NS) return;
        inflightSkip = true;
        lastSkipNs = now;

        Log.i(TAG, "DETECTED disliked song -> SKIP");

        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) {
                        Log.w(TAG, "  no app context");
                        return;
                    }
                    boolean dispatched = trySkip(ctx);
                    Log.i(TAG, "  skip dispatched=" + dispatched);
                } catch (Throwable t) {
                    Log.w(TAG, "  skip dispatch failed", t);
                } finally {
                    MAIN_HANDLER.postDelayed(new Runnable() {
                        @Override public void run() { inflightSkip = false; }
                    }, 1500);
                }
            }
        });
    }

    /** Try several skip mechanisms; return true on first success. */
    private static boolean trySkip(Context ctx) {
        // (1) MediaController via session token from same process — best path,
        //     no permission needed since we are the session owner.
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> sessions = msm.getActiveSessions(null);
                for (MediaController c : sessions) {
                    if (SELF_PKG.equals(c.getPackageName())) {
                        c.getTransportControls().skipToNext();
                        Log.i(TAG, "  used MediaController.skipToNext()");
                        return true;
                    }
                }
            }
        } catch (SecurityException se) {
            Log.i(TAG, "  no MEDIA_CONTENT_CONTROL: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "  MediaController path failed", t);
        }

        // (2) Broadcast ACTION_MEDIA_BUTTON to MusicBrowserService (the actual session host)
        try {
            sendMediaButton(ctx, "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService");
            Log.i(TAG, "  sent MEDIA_BUTTON to MusicBrowserService");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "  MusicBrowserService path failed", t);
        }

        // (3) AudioManager fallback (needs perm normally)
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
                Log.i(TAG, "  used AudioManager.dispatchMediaKeyEvent");
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private static void sendMediaButton(Context ctx, String serviceClassName) {
        Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
        down.setComponent(new ComponentName(SELF_PKG, serviceClassName));
        down.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));

        Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
        up.setComponent(new ComponentName(SELF_PKG, serviceClassName));
        up.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));

        // Try as service start first, then broadcast
        try {
            ctx.startService(down);
            ctx.startService(up);
        } catch (Throwable t) {
            ctx.sendBroadcast(down);
            ctx.sendBroadcast(up);
        }
    }

    private static boolean isEnabled() {
        try { return Settings.AUTO_SKIP_DISLIKED.get(); }
        catch (Throwable t) { return true; }
    }
}
