package tech.mmarca.openwatch.wear

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import tech.mmarca.openwatch.wear.ui.theme.SleepAwakeColor
import tech.mmarca.openwatch.wear.ui.theme.SleepDeepColor
import tech.mmarca.openwatch.wear.ui.theme.SleepLightColor
import tech.mmarca.openwatch.wear.ui.theme.SleepRemColor

/** Same stages as the phone app's sleep screen, top to bottom of a hypnogram. */
enum class SleepStage(@param:StringRes val label: Int, val color: Color) {
    AWAKE(R.string.sleep_awake, SleepAwakeColor),
    REM(R.string.sleep_rem, SleepRemColor),
    LIGHT(R.string.sleep_light, SleepLightColor),
    DEEP(R.string.sleep_deep, SleepDeepColor),
}
