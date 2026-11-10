package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wear.ui.ambient.LocalAmbientRequest
import tech.mmarca.openwatch.wear.ui.ambient.LocalAmbient
import tech.mmarca.openwatch.wear.ui.ambient.AmbientRequest
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.compose.runtime.CompositionLocalProvider
import tech.mmarca.openwatch.wear.workout.WorkoutScreen
import tech.mmarca.openwatch.wear.workout.WorkoutState
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.wear.features.settings.PhoneScreen
import tech.mmarca.openwatch.wear.features.settings.SensorAccess
import tech.mmarca.openwatch.wear.features.settings.SensorAccessScreen
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * The watch app: the dashboard, tiles' screens and quick log, with the link
 * and recorder status one step down in Settings. Asks for the permissions
 * the link and the recorders need; each part starts on its own once
 * granted. The recording itself lives in [WearAppService], so closing the
 * app changes nothing.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WearAppViewModel by viewModels()
    private val store by lazy { MetricStore(this) }
    private val trust by lazy { WearTrustStore(this) }

    private var permissionsVersion by mutableStateOf(0)

    /** True while the system shows the dimmed ambient screen. Only set while a screen asked for ambient mode. */
    private var ambient by mutableStateOf(false)
    private var ambientObserver: AmbientLifecycleObserver? = null
    private var ambientAsks = 0

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambient = true
        }

        override fun onExitAmbient() {
            ambient = false
        }
    }

    /**
     * The observer is added only while the workout screen or a running
     * measurement asks for it. With it the app stays on screen when the
     * display dims, so every other screen would have to draw an ambient
     * variant too; without it the system hands over to the watch face.
     */
    private val ambientRequest = object : AmbientRequest {
        override fun acquire() {
            if (++ambientAsks == 1) {
                val observer = AmbientLifecycleObserver(this@MainActivity, ambientCallback)
                ambientObserver = observer
                lifecycle.addObserver(observer)
            }
        }

        override fun release() {
            if (ambientAsks > 0 && --ambientAsks == 0) {
                ambientObserver?.let {
                    lifecycle.removeObserver(it)
                    // Removing does not unregister from the system; destroying does.
                    it.onDestroy(this@MainActivity)
                }
                ambientObserver = null
                ambient = false
            }
        }
    }

    /** A screen a tile asked for, opened once and then cleared. */
    private var pendingRoute by mutableStateOf<String?>(null)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsVersion++
        WearAppService.startIfPermitted(this)
        // The background grant is asked on its own, and only after the foreground one.
        requestBackgroundHeartRateIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()

        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val link by WearLinkState.state.collectAsStateWithLifecycle()
            // Read again on every resume: a grant can change in the system settings.
            val settings = remember(link, permissionsVersion) {
                SettingsUiState.from(
                    link = link,
                    sensorAccessGranted = WearPermissions.hasHeartRate(this) &&
                        WearPermissions.hasHeartRateInBackground(this),
                )
            }
            CompositionLocalProvider(LocalAmbient provides ambient, LocalAmbientRequest provides ambientRequest) {
                // A running or just finished workout takes the screen.
                val workout by WorkoutState.state.collectAsStateWithLifecycle()
                if (workout.running || workout.finished) {
                    OpenWatchWearTheme { WorkoutScreen(zones = uiState.preferences.zones, onClose = {}) }
                    return@CompositionLocalProvider
                }
                OpenWatchWearApp(
                    state = uiState.copy(settings = settings),
                    onLog = viewModel::log,
                    deepLinkRoute = pendingRoute,
                    onDeepLinkHandled = { pendingRoute = null },
                    workoutScreen = { onClose -> WorkoutScreen(zones = uiState.preferences.zones, onClose = onClose) },
                    statusScreen = { StatusScreen() },
                    phoneScreen = { PhoneRoute() },
                    sensorScreen = { onOpenDetails -> SensorAccessRoute(onOpenDetails) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onResume() {
        super.onResume()
        // Also catches a grant made in the system settings.
        permissionsVersion++
        WearAppService.startIfPermitted(this)
        WearAppService.setInteractive(this, true)
    }

    override fun onPause() {
        WearAppService.setInteractive(this, false)
        super.onPause()
    }

    /** What the watch records, whether the phone can reach it, and which phones may. */
    @Composable
    private fun StatusScreen() {
        WatchStatusScreen(
            permissionsVersion = permissionsVersion,
            hasHeartRate = { WearPermissions.hasHeartRate(this) },
            hasBluetooth = { WearPermissions.hasBluetooth(this) },
            hasSteps = { WearPermissions.hasActivityRecognition(this) },
            hasStepCounter = StepRecorder.hasStepCounter(this),
            readLatest = { store.latest(WearMetrics.HEART_RATE) },
            readCount = { store.count(WearMetrics.HEART_RATE) },
            readMinuteCount = { store.count(WearMetrics.SLEEP_MINUTES) },
            readStepMinuteCount = { store.count(WearMetrics.STEPS) },
            ppgLogAvailable = PpgRawLogger(this).isAvailable,
            readSamsungDiagnostics = { SamsungDiagnostics.consentOn(contentResolver) },
            onTogglePpgLog = { WearAppService.togglePpgLog(this) },
            onGrant = ::requestMissingPermissions,
            onAllow = { pending ->
                trust.trust(pending.address, pending.token, pending.name)
                WearPhoneRequests.cancel(this)
            },
            onBlock = { pending ->
                trust.block(pending.address)
                WearPhoneRequests.cancel(this)
            },
            onOpenBluetoothSettings = ::openBluetoothSettings,
        )
    }

    /** The phones that may read this watch, and the link they use. */
    @Composable
    private fun PhoneRoute() {
        val link by WearLinkState.state.collectAsStateWithLifecycle()
        PhoneScreen(
            link = link,
            bluetoothGranted = remember(permissionsVersion) { WearPermissions.hasBluetooth(this) },
            onAllow = { pending ->
                trust.trust(pending.address, pending.token, pending.name)
                WearPhoneRequests.cancel(this)
            },
            onBlock = { pending ->
                trust.block(pending.address)
                WearPhoneRequests.cancel(this)
            },
            onOpenBluetoothSettings = ::openBluetoothSettings,
        )
    }

    /** One row per grant the watch app uses. */
    @Composable
    private fun SensorAccessRoute(onOpenDetails: () -> Unit) {
        val access = remember(permissionsVersion) {
            SensorAccess(
                heartRate = WearPermissions.hasHeartRate(this),
                heartRateInBackground = WearPermissions.hasHeartRateInBackground(this),
                steps = WearPermissions.hasActivityRecognition(this).takeIf { StepRecorder.hasStepCounter(this) },
                nearbyDevices = WearPermissions.hasBluetooth(this),
            )
        }
        SensorAccessScreen(access = access, onGrant = ::requestMissingPermissions, onOpenDetails = onOpenDetails)
    }

    private fun openBluetoothSettings() {
        // Not every watch has a Bluetooth settings activity; the system settings are the fallback.
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(WearPermissions.heartRate)
            add(android.Manifest.permission.ACTIVITY_RECOGNITION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            // The link's ongoing notification and the phone requests; the service runs without it, just unseen.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            requestBackgroundHeartRateIfNeeded()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun requestBackgroundHeartRateIfNeeded() {
        val background = WearPermissions.heartRateInBackground ?: return
        if (!WearPermissions.hasHeartRate(this) || WearPermissions.hasHeartRateInBackground(this)) return
        requestPermissions.launch(arrayOf(background))
    }

    companion object {
        /** Extra a tile sets to open a screen; see [WearRoutes.isDeepLinkable]. */
        const val EXTRA_ROUTE = "tech.mmarca.openwatch.wear.ROUTE"
    }
}
