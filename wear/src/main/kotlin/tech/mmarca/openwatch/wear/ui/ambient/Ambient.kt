package tech.mmarca.openwatch.wear.ui.ambient

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** True while the system shows the dimmed ambient screen. Set by `MainActivity`. */
val LocalAmbient = compositionLocalOf { false }

/**
 * Lets a screen ask the activity to stay on screen in ambient mode instead of
 * handing over to the watch face. While nobody asks, the activity is not
 * ambient-aware and the system leaves the app when the screen times out.
 */
interface AmbientRequest {
    fun acquire()
    fun release()

    object None : AmbientRequest {
        override fun acquire() = Unit
        override fun release() = Unit
    }
}

val LocalAmbientRequest = staticCompositionLocalOf<AmbientRequest> { AmbientRequest.None }

/**
 * Asks for ambient mode while [wanted] and this call is in the composition.
 * Returns whether the screen should draw its ambient variant right now.
 */
@Composable
fun rememberAmbient(wanted: Boolean): Boolean {
    val request = LocalAmbientRequest.current
    DisposableEffect(wanted, request) {
        if (wanted) request.acquire()
        onDispose { if (wanted) request.release() }
    }
    return wanted && LocalAmbient.current
}

/**
 * What a screen draws in ambient mode: black, white and grey text, the main
 * value only, nothing that moves. The OLED panel burns in on bright areas.
 */
data class AmbientLook(
    val animate: Boolean,
    val showProgressArc: Boolean,
    val showSecondaryValues: Boolean,
    val showControls: Boolean,
    val coloured: Boolean,
)

fun ambientLook(ambient: Boolean): AmbientLook = AmbientLook(
    animate = !ambient,
    showProgressArc = !ambient,
    showSecondaryValues = !ambient,
    showControls = !ambient,
    coloured = !ambient,
)

/**
 * The elapsed time as hours and minutes, for ambient mode. The system redraws
 * an ambient screen about once a minute, so seconds would stand still.
 */
fun ambientElapsedText(seconds: Long): String {
    val minutes = (seconds / 60).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)
}
