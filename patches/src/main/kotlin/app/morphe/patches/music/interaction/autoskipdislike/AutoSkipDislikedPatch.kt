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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
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
    description = "Auto-skips songs rated thumbs-down before audio plays.",
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

        // ---- Hook 1: capture the app context via MusicLikeDislikeButton.onFinishInflate
        val ctxMethod = MusicLikeDislikeButtonOnFinishInflateFingerprint.method
        val dlIputIndex = ctxMethod.implementation!!.instructions.withIndex().firstOrNull { (_, ins) ->
            ins.opcode == Opcode.IPUT_OBJECT &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass + "->" + it.name + ":" + it.type
                } == DISLIKE_FIELD
        }?.index ?: error("dislike-button field assignment not found")
        val touchImageViewReg =
            ctxMethod.getInstruction<OneRegisterInstruction>(dlIputIndex).registerA
        ctxMethod.addInstructions(
            dlIputIndex + 1,
            "invoke-static { p0, v$touchImageViewReg }, $EXTENSION_CLASS->install(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // ---- Hook 2: every PlaybackStateCompat$CustomAction(String, CharSequence, int, Bundle) constructor invocation
        val match = CustomActionConstructorCallFingerprint
        val targetMethod = match.method

        // Find all invoke-direct sites for the CustomAction constructor inside this method
        val instructionsList = targetMethod.implementation!!.instructions.toList()
        val callSites = instructionsList.withIndex().filter { (_, ins) ->
            if (ins.opcode != Opcode.INVOKE_DIRECT) return@filter false
            val ref = (ins as? ReferenceInstruction)?.reference
            ref?.toString()?.startsWith(
                "Landroid/support/v4/media/session/PlaybackStateCompat\$CustomAction;-><init>",
            ) == true
        }.map { it.index }

        // Insert in reverse so earlier indices stay valid
        callSites.asReversed().forEach { idx ->
            val invoke = targetMethod.getInstruction<FiveRegisterInstruction>(idx)
            // invoke-direct {receiver, action, name, icon, bundle}: registerC=receiver, D=action, E=name, F=icon, G=bundle
            val nameReg = invoke.registerE
            // After the constructor returns, call our hook with the name CharSequence
            targetMethod.addInstructions(
                idx + 1,
                "invoke-static { v$nameReg }, $EXTENSION_CLASS->onCustomAction(Ljava/lang/CharSequence;)V",
            )
        }
    }
}
