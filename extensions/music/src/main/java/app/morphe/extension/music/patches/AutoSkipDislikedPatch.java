package app.morphe.extension.music.patches;

import android.content.Context;
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
 * Strategy: install an OnDrawListener on the dislike sub-button. On every draw,
 * check the button's drawable state. If the drawable has changed to a NEW state
 * AND that new state is "selected" (i.e. the current song is rated thumbs-down),
 * dispatch KEYCODE_MEDIA_NEXT immediately — before the audio frame actually plays.
 *
 * The drawable state changes the instant the player binds the new song's rating
 * to the UI, which happens during buffering — well before audio starts.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final String TAG = "MorpheAutoSkip";
    private static final int LISTENER_INSTALLED_TAG = 0x6d6f7270;
    private static final int LAST_STATE_TAG = 0x6d6f7271;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AutoSkipDislikedPatch() {}

    public static void install(final Object containerUnused, final ImageView dislikeButton) {
        Log.i(TAG, "install() entry: container=" + containerUnused + " btn=" + dislikeButton);
        if (dislikeButton == null) return;
        if (dislikeButton.getTag(LISTENER_INSTALLED_TAG) != null) return;
        dislikeButton.setTag(LISTENER_INSTALLED_TAG, Boolean.TRUE);
        Log.i(TAG, "install() — attaching OnDrawListener");

        final ViewTreeObserver.OnDrawListener[] holder = new ViewTreeObserver.OnDrawListener[1];
        holder[0] = new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                try {
                    if (!isEnabled()) return;
                    boolean disliked = isCurrentlyDisliked(dislikeButton);
                    Boolean prev = (Boolean) dislikeButton.getTag(LAST_STATE_TAG);
                    boolean prevDisliked = prev != null && prev;
                    if (disliked != prevDisliked) {
                        dislikeButton.setTag(LAST_STATE_TAG, disliked);
                        Log.i(TAG, "dislike state changed: " + prevDisliked + " -> " + disliked);
                        if (disliked) {
                            // The song that just bound to this button is already rated
                            // thumbs-down. Skip immediately — well before audio starts.
                            MAIN_HANDLER.post(new Runnable() {
                                @Override
                                public void run() {
                                    Log.i(TAG, "  firing MEDIA_NEXT (pre-emptive skip)");
                                    dispatchMediaNext(dislikeButton.getContext());
                                }
                            });
                        }
                    }
                } catch (Throwable t) {
                    // Never throw out of an OnDrawListener — would crash UI thread.
                }
            }
        };

        dislikeButton.getViewTreeObserver().addOnDrawListener(holder[0]);

        // Tear down listener if view detaches (otherwise it leaks across activities).
        dislikeButton.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { }
            @Override
            public void onViewDetachedFromWindow(View v) {
                try {
                    v.getViewTreeObserver().removeOnDrawListener(holder[0]);
                } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * "Disliked" detection: the dislike sub-button is in selected/checked state
     * when the current song is rated thumbs-down. We probe via:
     *   - View.isSelected()
     *   - View.isActivated() (some YT Music builds use this for rating state)
     */
    private static boolean isCurrentlyDisliked(View v) {
        return v.isSelected() || v.isActivated();
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
        try {
            return Settings.AUTO_SKIP_DISLIKED.get();
        } catch (Throwable t) {
            return true;
        }
    }
}
