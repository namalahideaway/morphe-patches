package app.morphe.extension.music.patches;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs already rated thumbs-down in YouTube Music.
 *
 * Detection:
 *   PlaybackStateCompat$CustomAction.<init> is invoked inside shd.i() / azri.l()
 *   when YT Music publishes the MediaSession's playback state for the current
 *   song. When the dislike action's NAME is "Undo dislike" the current track
 *   is rated thumbs-down — so we advance the queue.
 *
 * Skip mechanism:
 *   We call patch_playNextInQueue() DIRECTLY on the Lasvr (MedialibPlayer)
 *   instance captured at Lasvr;->p() (playVideo). No MEDIA_BUTTON broadcast,
 *   no Handler.post — synchronous in-process method dispatch. This eliminates
 *   the ~300ms broadcast round-trip that made disliked songs visible/audible
 *   on screen. Falls back to broadcast if reflection lookup fails.
 *
 * Dedup:
 *   Per-song counter, not per-time. Lasvr;->p() bumps `playCounter` for every
 *   new song. onCustomAction skips at most ONCE per playCounter value, so
 *   multiple state re-publishes for the same song never trigger a second skip,
 *   AND chained-dislikes (A→B→C all disliked) work because each new playVideo
 *   bumps the counter and unlocks the next skip.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // Last-known Lasvr (MedialibPlayer) instance, captured at Lasvr;->p().
    private static volatile Object playerInstance = null;
    // Reflection handle for ((MedialibPlayerAccess) playerInstance).patch_playNextInQueue()
    private static volatile Method playNextMethod = null;
    // Application context for the broadcast fallback.
    private static volatile Context appContext = null;

    // Per-song counter, bumped on every Lasvr;->p() invocation. Each value
    // represents one song-start. We skip at most once per counter value.
    private static final AtomicInteger playCounter = new AtomicInteger(0);
    private static volatile int lastSkipPlayId = -1;

    // Hard floor on skip frequency, just in case playVideo isn't bumping
    // (e.g. on app versions where capturePlayer didn't run).
    private static volatile long lastFireMs = 0L;
    private static final long MIN_FIRE_INTERVAL_MS = 250L;

    private AutoSkipDislikedPatch() {}

    /** Hook 1: called from MusicLikeDislikeButton.onFinishInflate to grab a Context. */
    public static void install(final Object containerUnused, final Object dislikeButton) {
        Log.i(TAG, "install() entry");
        try {
            if (dislikeButton instanceof View) {
                appContext = ((View) dislikeButton).getContext().getApplicationContext();
            }
        } catch (Throwable ignored) {}
    }

    /** Hook 2: called at the top of Lasvr;->p() (playVideo). p0 = the player. */
    public static void capturePlayer(Object player) {
        if (player == null) return;
        playerInstance = player;
        if (playNextMethod == null) {
            try {
                playNextMethod = player.getClass().getMethod("patch_playNextInQueue");
            } catch (Throwable t) {
                Log.w(TAG, "patch_playNextInQueue lookup failed", t);
            }
        }
        // Bump per-song counter — this unlocks the next skip.
        int n = playCounter.incrementAndGet();
        if ((n & 0xF) == 0) Log.i(TAG, "playVideo #" + n);
    }

    /** Hook 3: called for every CustomAction.<init> in shd.i() / azri.l(). */
    public static void onCustomAction(CharSequence name) {
        if (name == null) return;
        if (!isEnabled()) return;
        String s = name.toString();

        boolean isDislikeRemoval =
            s.equalsIgnoreCase("Undo dislike")
                || s.contains("Remove from disliked")
                || s.contains("undo dislike");
        if (!isDislikeRemoval) return;

        // Per-song dedup: only skip once per playVideo invocation.
        int currentPlay = playCounter.get();
        if (currentPlay == lastSkipPlayId) return;

        // Hard rate-limit, defensive.
        long now = SystemClock.uptimeMillis();
        if (now - lastFireMs < MIN_FIRE_INTERVAL_MS) return;

        lastSkipPlayId = currentPlay;
        lastFireMs = now;
        Log.i(TAG, "SKIP play=" + currentPlay);

        // Direct path: in-process method dispatch on the captured player.
        if (tryDirectSkip()) return;

        // Fallback: MEDIA_BUTTON broadcast (slower; only if direct path missing).
        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) return;
                    sendOneSkipBroadcast(ctx);
                } catch (Throwable t) {
                    Log.w(TAG, "broadcast skip failed", t);
                }
            }
        });
    }

    /** True if we successfully invoked patch_playNextInQueue on the player. */
    private static boolean tryDirectSkip() {
        Object p = playerInstance;
        Method m = playNextMethod;
        if (p == null || m == null) return false;
        try {
            m.invoke(p);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "direct skip failed", t);
            return false;
        }
    }

    private static void sendOneSkipBroadcast(Context ctx) {
        long t = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0);
        KeyEvent up   = new KeyEvent(t, t + 1, KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_MEDIA_NEXT, 0);
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
