package app.morphe.extension.music.patches;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs already rated thumbs-down in YouTube Music.
 *
 * Two complementary skip paths:
 *
 *  1. Pre-empt at playVideo entry (zero-frame, persistent set):
 *     preCheckSkip() runs at the top of Lasvr;->p() (playVideo). It reads
 *     the videoId of what's about to play via reflection on the player's
 *     queue chain (Lasvr.c.p() — Latsr;->p() returns the videoId String).
 *     If that videoId is in our persisted disliked set, we return true and
 *     the smali wrapper calls Lasvr;->o() (playNextInQueue) and returns,
 *     so the disliked track NEVER STARTS — no audio loads, no UI flash.
 *
 *  2. CustomAction observer (first-encounter detection):
 *     PlaybackStateCompat$CustomAction.<init> is invoked inside shd.i() /
 *     azri.l() when YT Music publishes the MediaSession's playback state.
 *     When the dislike action's NAME is "Undo dislike" the current track
 *     is rated thumbs-down. On that signal we (a) record the current
 *     videoId in the persisted disliked set so future encounters get
 *     pre-empted (path 1), and (b) skip the current track via direct
 *     in-process patch_playNextInQueue.
 *
 * Persistence: SharedPreferences "morphe_autoskip_disliked", key "video_ids"
 * holds a Set<String>. Populated automatically on every dislike-state
 * observation. Survives app restarts.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final String PREF_FILE = "morphe_autoskip_disliked";
    private static final String PREF_KEY_IDS = "video_ids";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // Last-known Lasvr (MedialibPlayer) instance, captured at Lasvr;->p().
    private static volatile Object playerInstance = null;
    // Reflection handle for ((MedialibPlayerAccess) playerInstance).patch_playNextInQueue()
    private static volatile Method playNextMethod = null;
    // Application context for the broadcast fallback + SharedPreferences.
    private static volatile Context appContext = null;

    // Reflection cache for (Lasvr).c → Latsr → Latsr.p() → videoId String.
    // Resolved lazily on first preCheckSkip / extractCurrentVideoId call.
    private static volatile Field queueChainField = null;     // Lasvr.c
    private static volatile Method queueVideoIdMethod = null; // Latsr.p()

    // In-memory mirror of SharedPreferences disliked set, hot-path.
    // Loaded once on first persistence access.
    private static final Set<String> dislikedSet = ConcurrentHashMap.newKeySet();
    private static volatile boolean dislikedSetLoaded = false;

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
                ensureDislikedSetLoaded();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Hook 2a: PRE-CHECK at Lasvr;->p() entry. Returns true to tell the smali
     * wrapper to early-exit p() via o() (playNextInQueue) before any audio
     * loads — i.e. the disliked track never plays a frame.
     *
     * Returns false when (a) the feature is disabled, (b) videoId can't be
     * resolved, or (c) videoId isn't in the persisted disliked set.
     */
    public static boolean preCheckSkip(Object player) {
        if (player == null) return false;
        if (!isEnabled()) return false;
        try {
            if (dislikedSet.isEmpty() && !dislikedSetLoaded) {
                ensureDislikedSetLoaded();
            }
            if (dislikedSet.isEmpty()) return false;

            String videoId = extractCurrentVideoId(player);
            if (videoId == null || videoId.isEmpty()) return false;

            if (dislikedSet.contains(videoId)) {
                Log.i(TAG, "preCheckSkip HIT vid=" + videoId);
                lastFireMs = SystemClock.uptimeMillis();
                return true;
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "preCheckSkip failed", t);
            return false;
        }
    }

    /** Hook 2b: called at the top of Lasvr;->p() (playVideo). p0 = the player. */
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

        // Persist the current videoId so future encounters get pre-empted at
        // playVideo entry (zero-frame). This is the in-session learning path.
        try {
            String videoId = extractCurrentVideoId(playerInstance);
            if (videoId != null && !videoId.isEmpty()) {
                rememberDisliked(videoId);
                Log.i(TAG, "SKIP+REMEMBER play=" + currentPlay + " vid=" + videoId);
            } else {
                Log.i(TAG, "SKIP play=" + currentPlay + " (no videoId)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "remember disliked failed", t);
        }

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

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static synchronized void ensureDislikedSetLoaded() {
        if (dislikedSetLoaded) return;
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            Set<String> persisted = sp.getStringSet(PREF_KEY_IDS, null);
            if (persisted != null) {
                dislikedSet.addAll(persisted);
                Log.i(TAG, "loaded " + persisted.size() + " disliked videoIds");
            }
            dislikedSetLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "load disliked set failed", t);
        }
    }

    private static void rememberDisliked(String videoId) {
        if (!dislikedSet.add(videoId)) return; // already known
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            // Re-snapshot to a fresh HashSet so SharedPreferences detects the change.
            Set<String> snapshot = new HashSet<>(dislikedSet);
            sp.edit().putStringSet(PREF_KEY_IDS, snapshot).apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist disliked set failed", t);
        }
    }

    // ------------------------------------------------------------------
    // VideoId extraction via reflection on the player's queue chain.
    //   Lasvr;->c:Latsr;       (queue chain field)
    //   Latsr;->p()Ljava/lang/String;   (returns current videoId)
    // ------------------------------------------------------------------

    private static String extractCurrentVideoId(Object player) {
        if (player == null) return null;
        try {
            Field f = queueChainField;
            if (f == null) {
                f = player.getClass().getDeclaredField("c");
                f.setAccessible(true);
                queueChainField = f;
            }
            Object queueChain = f.get(player);
            if (queueChain == null) return null;

            Method m = queueVideoIdMethod;
            // Cache by class to handle obfuscation drift across builds — the
            // chain object's class may differ across YT Music versions but
            // the no-arg p()->String accessor lives on the same interface.
            if (m == null || !m.getDeclaringClass().isInstance(queueChain)) {
                m = queueChain.getClass().getMethod("p");
                queueVideoIdMethod = m;
            }
            Object out = m.invoke(queueChain);
            return out instanceof String ? (String) out : null;
        } catch (Throwable t) {
            return null;
        }
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
