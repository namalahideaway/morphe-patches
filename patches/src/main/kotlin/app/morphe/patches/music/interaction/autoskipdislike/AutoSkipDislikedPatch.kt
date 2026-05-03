package app.morphe.patches.music.interaction.autoskipdislike

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/AutoSkipDislikedPatch;"
private const val DISLIKE_FIELD =
    "Lcom/google/android/apps/youtube/music/watchpage/MusicLikeDislikeButton;->d:" +
        "Lcom/google/android/libraries/youtube/common/ui/TouchImageView;"

@Suppress("unused")
val autoSkipDislikedPatch = bytecodePatch(
    name = "Auto-skip disliked songs",
    description = "Automatically skips a song when the user taps the thumbs-down button.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_auto_skip_disliked"),
        )

        val method = MusicLikeDislikeButtonOnFinishInflateFingerprint.method

        val dlIputIndex = method.implementation!!.instructions.withIndex().firstOrNull { (_, ins) ->
            ins.opcode == Opcode.IPUT_OBJECT &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass + "->" + it.name + ":" + it.type
                } == DISLIKE_FIELD
        }?.index ?: error("Could not locate dislike-button field assignment in onFinishInflate")

        val touchImageViewReg =
            method.getInstruction<OneRegisterInstruction>(dlIputIndex).registerA

        method.addInstructions(
            dlIputIndex + 1,
            """
                invoke-static { p0, v$touchImageViewReg }, $EXTENSION_CLASS->install(Ljava/lang/Object;Landroid/widget/ImageView;)V
            """,
        )
    }
}
