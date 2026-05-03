package app.morphe.extension.music.patches;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs already rated thumbs-down in YouTube Music.
 *
 * Detection: hooks the PlaybackStateCompat$CustomAction constructor inside
 * shd.i() / azri.l() — the methods YT Music uses to publish state to its
 * MediaSession. When the dislike action's NAME is "Undo dislike" the current
 * track is rated thumbs-down, so we send ONE skip broadcast.
 *
 * Strict de-duplication via a state-transition gate: only fires when we see
 * a "non-disliked" action (Like / Dislike) followed later by an "Undo dislike"
 * action. This means a genuine song change. The gate stays disarmed until the
 * next non-disliked observation, so YT Music re-emitting the same Undo state
 * during the post-skip transition does NOT trigger another broadcast — which
 * is what was causing Bluetooth A2DP to stutter.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Context appContext = null;

    // Re-armed whenever a NON-disliked custom action is observed. The next
    // "Undo dislike" we see fires exactly one skip and disarms the gate.
    private static volatile boolean armed = true;
    private static volatile long lastFireMs = 0L;
    private static final long MIN_FIRE_INTERVAL_MS = 3000L;

    private AutoSkipDislikedPatch() {}

    public static void install(final Object containerUnused, final Object dislikeButton) {
        Log.i(TAG, "install() entry");
        try {
            if (dislikeButton instanceof View) {
                appContext = ((View) dislikeButton).getContext().getApplicationContext();
            }
        } catch (Throwable t) {}
    }

    /** Called from the patch for every CustomAction.<init> in shd.i() / azri.l(). */
    public static void onCustomAction(CharSequence name) {
        if (name == null) return;
        if (!isEnabled()) return;
        String s = name.toString();

        boolean isDislikeRemoval =
            s.equalsIgnoreCase("Undo dislike")
                || s.contains("Remove from disliked")
                || s.contains("undo dislike");

        if (!isDislikeRemoval) {
            armed = true;
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (!armed) return;
        if (now - lastFireMs < MIN_FIRE_INTERVAL_MS) return;

        armed = false;
        lastFireMs = now;
        Log.i(TAG, "SKIP (Undo dislike observed)");

        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) return;
                    sendOneSkip(ctx);
                } catch (Throwable t) {
                    Log.w(TAG, "skip dispatch failed", t);
                }
            }
        });
    }

    private static void sendOneSkip(Context ctx) {
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
    }

    private static boolean isEnabled() {
        try { return Settings.AUTO_SKIP_DISLIKED.get(); }
        catch (Throwable t) { return true; }
    }
}
