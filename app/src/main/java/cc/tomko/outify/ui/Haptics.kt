package cc.tomko.outify.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

object Haptics {

    private var isHapticsEnabled = true

    fun confirm(context: Context, view: View) {
        vibrate(
            context, view,
            fallbackDuration = 18L,
            fallbackAmplitude = 120,
            build = {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 0)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f, 30)
                    .compose()
            }
        )
    }

    fun toggle(context: Context, view: View) {
        vibrate(
            context, view,
            fallbackDuration = 15L,
            fallbackAmplitude = 100,
            build = {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0)
                    .compose()
            }
        )
    }

    fun textHandleMove(context: Context, view: View) {
        vibrate(
            context, view,
            fallbackDuration = 10L,
            fallbackAmplitude = 70,
            build = {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 0)
                    .compose()
            }
        )
    }

    fun swipeArm(context: Context, view: View) {
        vibrate(
            context, view,
            fallbackDuration = 8L,
            fallbackAmplitude = 55,
            build = {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f, 0)
                    .compose()
            }
        )
    }

    private fun vibrate(
        context: Context,
        view: View,
        fallbackDuration: Long,
        fallbackAmplitude: Int,
        forceVibrate: Boolean = false,
        build: (VibrationEffect.Composition) -> VibrationEffect
    ) {
        if (!isHapticsEnabled && !forceVibrate) return

        val vibrator = getVibrator(context)
        if (vibrator != null && vibrator.hasVibrator()) {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                build(VibrationEffect.startComposition())
            } else {
                VibrationEffect.createWaveform(
                    longArrayOf(0, fallbackDuration),
                    intArrayOf(0, fallbackAmplitude),
                    -1
                )
            }
            vibrator.vibrate(effect)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}