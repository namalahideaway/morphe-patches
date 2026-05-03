package app.morphe.patches.music.interaction.autoskipdislike

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object MusicLikeDislikeButtonOnFinishInflateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == "Lcom/google/android/apps/youtube/music/watchpage/MusicLikeDislikeButton;" &&
            method.name == "onFinishInflate"
    },
)
