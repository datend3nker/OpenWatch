package tech.mmarca.openwatch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import tech.mmarca.openwatch.core.geo.GeoUri
import tech.mmarca.openwatch.core.geo.SharedGeoPoint
import tech.mmarca.openwatch.core.geo.SharedLocationText
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.core.pairing.CompanionDevicePairing
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.navigation.AppNavigation
import tech.mmarca.openwatch.navigation.Screen
import tech.mmarca.openwatch.ui.components.AppBarState
import tech.mmarca.openwatch.ui.components.LocalAppBarState
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferences: OpenWatchPreferences
    @Inject lateinit var healthConnect: HealthConnectGateway
    @Inject lateinit var unitFormatter: UnitFormatter
    @Inject lateinit var companionDevicePairing: CompanionDevicePairing

    private var externalNavigationRoute by mutableStateOf<String?>(null)
    private var startDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectHealthConnectOnboardingIfNeeded(intent)) {
            return
        }
        enableEdgeToEdge()
        // The companion association dialog needs an Activity launcher. Without this
        // attach, no watch or scale is ever associated.
        companionDevicePairing.attachToActivity(this)
        // A recreated Activity still holds the launch intent and its back stack comes
        // back on its own; applying the intent again would push a second copy.
        if (savedInstanceState == null) {
            updateExternalNavigationRoute(intent)
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                )
            }

            OpenWatchTheme(dynamicColor = true) {
                val resolvedStartDestination = startDestination ?: run {
                    if (
                        preferences.onboardingDone &&
                        healthConnect.availability() == HealthConnectAvailability.AVAILABLE
                    ) {
                        Screen.Home.route
                    } else {
                        Screen.HealthConnectSetup.route
                    }
                }.also { destination ->
                    if (startDestination == null) {
                        startDestination = destination
                    }
                }
                val rememberedAppBarState = remember { AppBarState() }

                // Where each screen declares what it wants in the app bar.
                CompositionLocalProvider(LocalAppBarState provides rememberedAppBarState) {
                    AppNavigation(
                        unitFormatter = unitFormatter,
                        startDestination = resolvedStartDestination,
                        externalNavigationRoute = externalNavigationRoute,
                        onExternalNavigationHandled = { externalNavigationRoute = null },
                        onSetupComplete = {
                            preferences.onboardingDone = true
                            startDestination = Screen.Home.route
                        },
                    )
                }
            }
        }
    }

    /**
     * Set while a screen that gives another app trust shows (Connected apps): a touch that
     * reached this window through another app's window above it is dropped, so an overlay
     * cannot steer a tap. The check `View.filterTouchesWhenObscured` makes, done here because
     * Compose's view does not make it.
     */
    var dropObscuredTouches: Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (dropObscuredTouches && event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        companionDevicePairing.detachFromActivity(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (redirectHealthConnectOnboardingIfNeeded(intent)) {
            return
        }
        updateExternalNavigationRoute(intent)
    }

    private fun redirectHealthConnectOnboardingIfNeeded(intent: Intent?): Boolean {
        if (intent?.isHealthConnectOnboardingIntent() != true) return false
        startActivity(
            Intent(this, HealthConnectOnboardingActivity::class.java).apply {
                action = intent.action
                data = intent.data
                putExtras(intent)
            },
        )
        finish()
        return true
    }

    private fun updateExternalNavigationRoute(intent: Intent?) {
        externalNavigationRoute = intent?.sendPointRoute()
            ?: Screen.ConnectedApps.route.takeIf { intent?.action == ACTION_OPENLINK_CONSENT }
    }

    companion object {
        /** The consent screen an OpenLink client opens for a pending request. */
        const val ACTION_OPENLINK_CONSENT = "tech.mmarca.openwatch.action.OPENLINK_CONSENT"
    }
}

private fun Intent.isHealthConnectOnboardingIntent(): Boolean {
    val action = action ?: return false
    return action == "androidx.health.ACTION_SHOW_ONBOARDING" ||
        action == "android.health.connect.action.SHOW_ONBOARDING"
}

/**
 * The send-a-point route for a place another app shared: a `geo:` link, or
 * text holding one, a maps URL or bare coordinates. Built here from parsed
 * numbers, so nothing from the intent reaches the navigator as a route.
 */
internal fun Intent.sendPointRoute(): String? = when (action) {
    Intent.ACTION_VIEW -> sharedPlaceRoute(geoLink = dataString, text = null)
    Intent.ACTION_SEND -> sharedPlaceRoute(
        geoLink = null,
        text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            .takeIf { type?.startsWith("text/") == true },
    )
    else -> null
}

/** Null when there is nothing place-like at all. A share with no position still opens the form, which says so. */
internal fun sharedPlaceRoute(geoLink: String?, text: String?): String? {
    val point = when {
        geoLink != null -> GeoUri.parse(geoLink) ?: return null
        text != null -> SharedLocationText.parse(text) ?: SharedGeoPoint()
        else -> return null
    }
    return Screen.WatchSendPoint.createRoute(
        latitude = point.latitude,
        longitude = point.longitude,
        name = point.name?.take(SharedPlaceNameLimit),
        unreadable = !point.hasPosition,
    )
}

/** The watch keeps 31 bytes. This only stops a whole shared page becoming a route. */
private const val SharedPlaceNameLimit = 64
