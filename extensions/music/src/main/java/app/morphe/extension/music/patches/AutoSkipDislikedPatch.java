package app.morphe.extension.music.patches;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.music.settings.Settings;

/**
 * Auto-skip disliked songs in YouTube Music.
 *
 * Called from MusicLikeDislikeButton.onFinishInflate (via patch) right after the
 * dislike-button reference is stored. Installs a touch listener that fires
 * KEYCODE_MEDIA_NEXT ~500ms after the user taps the dislike thumb. The 500ms
 * delay allows YT Music's own dislike handler to run first so the rating is
 * persisted before the skip dispatches.
 *
 * @noinspection unused
 */
public final class AutoSkipDislikedPatch {

    private static final int TAG_KEY = 0x6d6f7270; // "morp"
    private static final long SKIP_DELAY_MS = 500L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private AutoSkipDislikedPatch() {}

    public static void install(final Object containerUnused, final ImageView dislikeButton) {
        if (dislikeButton == null) return;

        // Avoid stacking multiple listeners on the same view across re-inflations.
        Object tag = dislikeButton.getTag(TAG_KEY);
        if (tag != null) return;
        dislikeButton.setTag(TAG_KEY, Boolean.TRUE);

        dislikeButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_UP) return false;
                if (!isEnabled()) return false;

                final Context ctx = v.getContext().getApplicationContext();
                MAIN_HANDLER.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                            if (am == null) return;
                            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
                        } catch (Throwable ignored) {
                        }
                    }
                }, SKIP_DELAY_MS);
                return false;
            }
        });
    }

    private static boolean isEnabled() {
        try {
            return Settings.AUTO_SKIP_DISLIKED.get();
        } catch (Throwable t) {
            return true;
        }
    }
}
