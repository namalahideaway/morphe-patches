package app.morphe.extension.music.patches;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.util.List;

import app.morphe.extension.music.settings.Settings;

public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Context appContext = null;
    private static volatile boolean inflightSkip = false;
    private static volatile long lastSkipNs = 0L;
    private static final long SKIP_DEDUP_NS = 1_200_000_000L; // 1.2s

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

        // Defer skip slightly so YT Music finishes its initial state setup
        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) return;
                    boolean ok = trySkipChain(ctx);
                    Log.i(TAG, "  chain result=" + ok);
                } finally {
                    MAIN_HANDLER.postDelayed(new Runnable() {
                        @Override public void run() { inflightSkip = false; }
                    }, 1200);
                }
            }
        }, 50L);
    }

    /** Try every skip method we know of, in order. */
    private static boolean trySkipChain(Context ctx) {
        // (1) MediaController via session manager (needs MEDIA_CONTENT_CONTROL — unlikely)
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> sessions = msm.getActiveSessions(null);
                for (MediaController c : sessions) {
                    if (SELF_PKG.equals(c.getPackageName())) {
                        c.getTransportControls().skipToNext();
                        Log.i(TAG, "  MediaController.skipToNext() OK");
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "  (1) MediaController failed: " + t.getMessage());
        }

        // (2) Broadcast media button event with NO target component — let Android route
        //     to the registered media-button receiver for the current media-button session.
        try {
            long t = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0);
            KeyEvent up = new KeyEvent(t, t + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0);

            Intent dIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            dIntent.putExtra(Intent.EXTRA_KEY_EVENT, down);
            dIntent.setPackage(SELF_PKG);
            ctx.sendBroadcast(dIntent);

            Intent uIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            uIntent.putExtra(Intent.EXTRA_KEY_EVENT, up);
            uIntent.setPackage(SELF_PKG);
            ctx.sendBroadcast(uIntent);

            Log.i(TAG, "  (2) sent ACTION_MEDIA_BUTTON broadcasts (pkg-scoped)");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "  (2) broadcast failed: " + t.getMessage());
        }

        // (3) AudioManager fallback
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
                Log.i(TAG, "  (3) AudioManager dispatched");
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private static boolean isEnabled() {
        try { return Settings.AUTO_SKIP_DISLIKED.get(); }
        catch (Throwable t) { return true; }
    }
}
