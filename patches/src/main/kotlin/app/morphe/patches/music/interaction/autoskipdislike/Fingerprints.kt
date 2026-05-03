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

/** Match shd.i() — the method that builds dislike CustomAction. */
internal object SetCustomActionFingerprintA : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
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

/** Match azri.* — second method building CustomAction. */
internal object SetCustomActionFingerprintB : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
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
