package app.morphe.extension.music.patches;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs that are already rated thumbs-down.
 *
 * Hook strategy: every time YT Music constructs a PlaybackStateCompat custom
 * action, we observe the (id, name) pair. When the name shows "Undo dislike"
 * (which only happens when the currently-loaded song is rated thumbs-down),
 * we dispatch KEYCODE_MEDIA_NEXT — pre-emptively, before audio plays.
 *
 * This call site fires on every song change (the player rebuilds custom
 * actions when state changes), so previously-disliked tracks are caught
 * during the buffering phase before the first audio frame.
 *
 * Per-track dedup: only fires once per visible "Undo dislike" appearance to
 * avoid retrigger loops while the skip propagates.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile Context appContext = null;
    private static volatile boolean inflightSkip = false;
    private static volatile long lastSkipNs = 0L;
    private static final long SKIP_DEDUP_NS = 1_500_000_000L; // 1.5s

    private AutoSkipDislikedPatch() {}

    /** Called from MusicLikeDislikeButton.onFinishInflate just to capture the app context. */
    public static void install(final Object containerUnused, final Object dislikeButton) {
        Log.i(TAG, "install() entry");
        try {
            if (dislikeButton instanceof android.view.View) {
                appContext = ((android.view.View) dislikeButton).getContext().getApplicationContext();
                Log.i(TAG, "  captured app context");
            }
        } catch (Throwable t) {
            Log.w(TAG, "install ctx capture failed", t);
        }
    }

    /**
     * Hook called from inside YT Music's PlaybackStateCompat.CustomAction
     * builder. Args mirror the CustomAction constructor: (id, name, icon).
     * When name signals the current song is already rated thumbs-down, fire
     * MEDIA_NEXT.
     */
    public static void onCustomAction(CharSequence name) {
        if (name == null) return;
        if (!isEnabled()) return;
        String s = name.toString();
        // YT Music sets the dislike action's NAME to "Undo dislike" when the
        // current song is rated down, and "Dislike" when it's neutral.
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

        Log.i(TAG, "DETECTED disliked song via custom-action name=\"" + s + "\" -> firing MEDIA_NEXT");

        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) {
                        Log.w(TAG, "  no app context — skipping dispatch");
                        return;
                    }
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return;
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
                } catch (Throwable t) {
                    Log.w(TAG, "  dispatch failed", t);
                } finally {
                    MAIN_HANDLER.postDelayed(new Runnable() {
                        @Override public void run() { inflightSkip = false; }
                    }, 1500);
                }
            }
        });
    }

    private static boolean isEnabled() {
        try { return Settings.AUTO_SKIP_DISLIKED.get(); }
        catch (Throwable t) { return true; }
    }
}
