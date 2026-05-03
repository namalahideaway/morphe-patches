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
 * Match any private/public method (any return type, any params) that calls
 * PlaybackStateCompat$CustomAction.<init>(String, CharSequence, int, Bundle).
 * We don't constrain access flags or return type — different YT Music builds
 * use different signatures (V or Lip etc).
 */
internal object SetCustomActionFingerprint : Fingerprint(
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
