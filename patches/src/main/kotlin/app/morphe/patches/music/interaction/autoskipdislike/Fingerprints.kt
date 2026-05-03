package app.morphe.patches.music.interaction.autoskipdislike

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MusicLikeDislikeButtonOnFinishInflateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/music/watchpage/MusicLikeDislikeButton;" &&
            method.name == "onFinishInflate"
    },
)

/**
 * Matches the method that constructs PlaybackStateCompat$CustomAction objects.
 * YT Music calls this constructor for every playback-state custom action it
 * publishes (Like, Dislike, Shuffle, Repeat). The dislike action's NAME is
 * "Undo dislike" exactly when the current song is rated thumbs-down.
 */
internal object CustomActionConstructorCallFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            definingClass = "Landroid/support/v4/media/session/PlaybackStateCompat\$CustomAction;",
            name = "<init>",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;", "I", "Landroid/os/Bundle;"),
            returnType = "V",
        ),
    ),
)
