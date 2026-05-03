package app.morphe.extension.music.patches;

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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.music.settings.Settings;

public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final String SELF_PKG = "app.morphe.android.apps.youtube.music";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final Set<String> DISLIKED_IDS = Collections.synchronizedSet(new HashSet<String>());

    private static volatile Context appContext = null;
    private static volatile boolean inflightSkip = false;
    private static volatile long lastSkipNs = 0L;
    private static volatile String lastSeenVideoId = null;
    private static final long SKIP_DEDUP_NS = 800000000L;

    private AutoSkipDislikedPatch() {}

    public static void install(final Object containerUnused, final Object dislikeButton) {
        Log.i(TAG, "install() entry");
        try {
            if (dislikeButton instanceof View) {
                appContext = ((View) dislikeButton).getContext().getApplicationContext();
            }
        } catch (Throwable t) {}
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
        String vid = lastSeenVideoId;
        if (vid != null) {
            if (DISLIKED_IDS.add(vid)) {
                Log.i(TAG, "BLACKLIST + " + vid + " (now " + DISLIKED_IDS.size() + " entries)");
            }
        }
        fireSkip("Undo dislike state");
    }

    public static void onLoadVideo(String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        lastSeenVideoId = videoId;
        if (!isEnabled()) return;
        Log.i(TAG, "onLoadVideo: " + videoId.substring(0, Math.min(60, videoId.length())));
        if (DISLIKED_IDS.contains(videoId)) {
            Log.i(TAG, "PRE-EMPT " + videoId);
            fireSkip("blacklist hit on loadVideo");
        }
    }

    private static void fireSkip(String reason) {
        long now = System.nanoTime();
        if (inflightSkip) return;
        if (now - lastSkipNs < SKIP_DEDUP_NS) return;
        inflightSkip = true;
        lastSkipNs = now;

        Log.i(TAG, "SKIP -> " + reason);

        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = appContext;
                    if (ctx == null) return;
                    boolean ok = trySkipChain(ctx);
                    Log.i(TAG, "  dispatched=" + ok);
                } finally {
                    MAIN_HANDLER.postDelayed(new Runnable() {
                        @Override public void run() { inflightSkip = false; }
                    }, 800);
                }
            }
        });
    }

    private static boolean trySkipChain(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> sessions = msm.getActiveSessions(null);
                for (MediaController c : sessions) {
                    if (SELF_PKG.equals(c.getPackageName())) {
                        c.getTransportControls().skipToNext();
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

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
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "broadcast failed", t);
        }

        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long t = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(t, t + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
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
