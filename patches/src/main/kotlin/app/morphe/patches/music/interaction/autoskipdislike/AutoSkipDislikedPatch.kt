package app.morphe.patches.music.interaction.autoskipdislike

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.findInstructionIndicesReversed
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
    description = "Skips songs rated thumbs-down. ONE skip per song to avoid Bluetooth stutter.",
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

        // Hook 1: capture an Application context once.
        val ctxMethod = MusicLikeDislikeButtonOnFinishInflateFingerprint.method
        val dlIputIndex = ctxMethod.implementation!!.instructions.withIndex().firstOrNull { (_, ins) ->
            ins.opcode == Opcode.IPUT_OBJECT &&
                ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.let {
                    it.definingClass + "->" + it.name + ":" + it.type
                } == DISLIKE_FIELD
        }?.index ?: error("dislike-button field not found")
        val tivReg = ctxMethod.getInstruction<OneRegisterInstruction>(dlIputIndex).registerA
        ctxMethod.addInstructions(
            dlIputIndex + 1,
            "invoke-static { p0, v$tivReg }, $EXTENSION_CLASS->install(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // Hook 2 + 3: call onCustomAction(name) after every CustomAction
        // constructor in shd.i() / azri.l(). We pass ONLY the name register
        // (already a CharSequence), so no scratch registers are clobbered —
        // this avoids the VerifyError that broke azri.l() in v15.
        fun injectCustomAction(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
            val callIndices = method.findInstructionIndicesReversed(
                methodCall(
                    opcode = Opcode.INVOKE_DIRECT,
                    definingClass = "Landroid/support/v4/media/session/PlaybackStateCompat\$CustomAction;",
                    name = "<init>",
                    parameters = listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;", "I", "Landroid/os/Bundle;"),
                    returnType = "V",
                ),
            )
            callIndices.forEach { idx ->
                val invoke = method.getInstruction<FiveRegisterInstruction>(idx)
                val nameReg = invoke.registerE
                method.addInstructions(
                    idx + 1,
                    "invoke-static { v$nameReg }, $EXTENSION_CLASS->onCustomAction(Ljava/lang/CharSequence;)V",
                )
            }
        }
        runCatching { injectCustomAction(SetCustomActionFingerprintShd.method) }
        runCatching { injectCustomAction(SetCustomActionFingerprintAzri.method) }
    }
}
