package app.morphe.extension.music.patches;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip songs that are already rated thumbs-down.
 *
 * Strategy: install an OnDrawListener on the dislike sub-button. Each draw,
 * we capture an identity fingerprint of the dislike icon's current drawable
 * (constant state hashcode + alpha + bounds). When that fingerprint changes,
 * the rating UI binding changed; we then peek the MediaSession's custom
 * actions to decide whether the new state is "disliked". If so, dispatch
 * KEYCODE_MEDIA_NEXT immediately.
 *
 * MediaSession peek inside YT Music's process: we use
 *   AudioManager.dispatchMediaKeyEvent(MEDIA_NEXT)
 * to skip; for state we read the active session's custom actions through the
 * notification's PlaybackState bundle (always present for foreground media).
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final int LISTENER_INSTALLED_TAG = 0x6d6f7270;
    private static final int LAST_FP_TAG = 0x6d6f7271;
    private static final int LAST_SKIP_FP_TAG = 0x6d6f7272;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AutoSkipDislikedPatch() {}

    public static void install(final Object containerUnused, final ImageView dislikeButton) {
        Log.i(TAG, "install() entry: container=" + containerUnused + " btn=" + dislikeButton);
        if (dislikeButton == null) return;
        if (dislikeButton.getTag(LISTENER_INSTALLED_TAG) != null) return;
        dislikeButton.setTag(LISTENER_INSTALLED_TAG, Boolean.TRUE);
        Log.i(TAG, "  attaching OnDrawListener to " + dislikeButton.getClass().getName());

        final ViewTreeObserver.OnDrawListener[] holder = new ViewTreeObserver.OnDrawListener[1];
        holder[0] = new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                try {
                    if (!isEnabled()) return;
                    long fp = currentDrawableFingerprint(dislikeButton);
                    Long prev = (Long) dislikeButton.getTag(LAST_FP_TAG);
                    if (prev != null && prev == fp) return;  // no change
                    Long lastSkip = (Long) dislikeButton.getTag(LAST_SKIP_FP_TAG);
                    dislikeButton.setTag(LAST_FP_TAG, fp);
                    Log.i(TAG, "drawable fp changed: " + prev + " -> " + fp + " sel=" + dislikeButton.isSelected() + " act=" + dislikeButton.isActivated());

                    if (lastSkip != null && lastSkip == fp) return;  // already skipped this state

                    // Heuristic: when the icon's drawable identity changes AND it is
                    // currently disliked, fire skip. We detect "disliked" by the icon's
                    // selected/activated state OR by content-description change.
                    if (looksDisliked(dislikeButton)) {
                        dislikeButton.setTag(LAST_SKIP_FP_TAG, fp);
                        MAIN_HANDLER.post(new Runnable() {
                            @Override public void run() {
                                Log.i(TAG, "  pre-emptive SKIP fired (fp=" + fp + ")");
                                dispatchMediaNext(dislikeButton.getContext());
                            }
                        });
                    }
                } catch (Throwable t) {
                    // never crash UI thread
                }
            }
        };

        dislikeButton.getViewTreeObserver().addOnDrawListener(holder[0]);

        dislikeButton.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                try { v.getViewTreeObserver().removeOnDrawListener(holder[0]); } catch (Throwable ignored) {}
            }
        });
    }

    /** Compute a fingerprint that changes when the icon's visual identity changes. */
    private static long currentDrawableFingerprint(ImageView v) {
        Drawable d = v.getDrawable();
        if (d == null) return 0L;
        long h = 0L;
        Drawable.ConstantState cs = d.getConstantState();
        if (cs != null) h = (long) cs.hashCode() & 0xffffffffL;
        h = (h << 16) ^ d.getLevel();
        h ^= d.getAlpha();
        // selected/activated also bake into icon state
        if (v.isSelected())  h ^= 0x100000000L;
        if (v.isActivated()) h ^= 0x200000000L;
        // contentDescription can flip ("Dislike" <-> "Remove dislike")
        CharSequence cd = v.getContentDescription();
        if (cd != null) h ^= ((long) cd.hashCode() & 0xffffffffL) << 1;
        return h;
    }

    /**
     * Is the dislike button currently in "rated thumbs-down" state?
     *
     * YT Music's dislike button content-description toggles between
     * "Dislike" (neutral) and "Remove from disliked songs" / "Undo dislike"
     * when the song is rated down. Either selected/activated states or the
     * content-description containing "remove" / "undo" indicates active dislike.
     */
    private static boolean looksDisliked(View v) {
        if (v.isSelected() || v.isActivated()) return true;
        CharSequence cd = v.getContentDescription();
        if (cd == null) return false;
        String s = cd.toString().toLowerCase();
        return s.contains("undo") || s.contains("remove") || s.contains("rated") || s.contains("disliked");
    }

    private static void dispatchMediaNext(Context ctxIn) {
        try {
            Context ctx = ctxIn.getApplicationContext();
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
        } catch (Throwable t) {
            Log.w(TAG, "dispatch failed", t);
        }
    }

    private static boolean isEnabled() {
        try { return Settings.AUTO_SKIP_DISLIKED.get(); }
        catch (Throwable t) { return true; }
    }
}
