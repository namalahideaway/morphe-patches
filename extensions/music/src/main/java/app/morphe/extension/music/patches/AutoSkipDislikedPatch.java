package app.morphe.extension.music.patches;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs already rated thumbs-down.
 *
 * Hook fires when YT Music constructs the "Dislike" custom action of its
 * PlaybackState. When the action's NAME is "Undo dislike" (vs "Dislike"),
 * the current song is rated down. We skip to next via a direct broadcast
 * of ACTION_MEDIA_BUTTON to YT Music's own MediaButtonReceiver — this
 * avoids the MEDIA_CONTENT_CONTROL permission requirement that
 * dispatchMediaKeyEvent() needs.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final String MBR_CLASS = "androidx.media.session.MediaButtonReceiver";
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

        Log.i(TAG, "DETECTED disliked song name=\"" + s + "\" -> SKIP");

        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) {
                        Log.w(TAG, "  no app context");
                        return;
                    }
                    sendMediaButton(ctx, KeyEvent.ACTION_DOWN);
                    sendMediaButton(ctx, KeyEvent.ACTION_UP);
                    Log.i(TAG, "  skip dispatched via MediaButtonReceiver broadcast");
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

    /** Broadcast ACTION_MEDIA_BUTTON to YT Music's own receiver inside this process. */
    private static void sendMediaButton(Context ctx, int action) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setComponent(new ComponentName(SELF_PKG, MBR_CLASS));
        KeyEvent ke = new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_NEXT);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, ke);
        try {
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "  sendBroadcast failed: " + t);
            // Fallback path
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.dispatchMediaKeyEvent(ke);
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isEnabled() {
        try { return Settings.AUTO_SKIP_DISLIKED.get(); }
        catch (Throwable t) { return true; }
    }
}
