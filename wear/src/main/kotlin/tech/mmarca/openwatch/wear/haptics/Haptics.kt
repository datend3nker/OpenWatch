package tech.mmarca.openwatch.wear.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays a [HapticCue] on the watch's vibrator. Does nothing if the watch has none. */
object Haptics {
    fun play(context: Context, cue: HapticCue?) {
        if (cue == null) return
        val vibrator = vibrator(context.applicationContext) ?: return
        if (!vibrator.hasVibrator()) return
        // -1: play the pattern once.
        vibrator.vibrate(VibrationEffect.createWaveform(cue.timings, -1))
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
