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

private val customActionInitFilter = methodCall(
    opcode = Opcode.INVOKE_DIRECT,
    definingClass = "Landroid/support/v4/media/session/PlaybackStateCompat\$CustomAction;",
    name = "<init>",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;", "I", "Landroid/os/Bundle;"),
    returnType = "V",
)

internal object SetCustomActionFingerprintShd : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(customActionInitFilter),
)

internal object SetCustomActionFingerprintAzri : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Lip;",
    filters = listOf(customActionInitFilter),
)

internal object PlayVideoFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("playVideo", "MedialibPlayer.playVideo()"),
)
