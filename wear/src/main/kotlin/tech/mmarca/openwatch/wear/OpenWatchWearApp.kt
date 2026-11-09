package tech.mmarca.openwatch.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import tech.mmarca.openwatch.wear.features.dashboard.DashboardScreen
import tech.mmarca.openwatch.wear.features.dashboard.TileEditorScreen
import tech.mmarca.openwatch.wear.features.measure.MeasureListScreen
import tech.mmarca.openwatch.wear.features.measure.Measurement
import tech.mmarca.openwatch.wear.features.measure.MeasurementRoute
import tech.mmarca.openwatch.wear.features.metric.MetricDetailScreen
import tech.mmarca.openwatch.wear.features.quicklog.BloodPressureEntryScreen
import tech.mmarca.openwatch.wear.features.quicklog.BreatheScreen
import tech.mmarca.openwatch.wear.features.quicklog.EntryType
import tech.mmarca.openwatch.wear.features.quicklog.LogValueScreen
import tech.mmarca.openwatch.wear.features.quicklog.LoggedEntry
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogEditorScreen
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogScreen
import tech.mmarca.openwatch.wear.features.quicklog.WaterServingMl
import tech.mmarca.openwatch.wear.features.quicklog.WeightEntryScreen
import tech.mmarca.openwatch.wear.features.settings.SettingsScreen
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/** Starting weight on the weight stepper before anything was logged. */
private const val DefaultWeightKg = 70.0

/**
 * The whole watch UI: theme, scaffold and navigation. It renders [state] and
 * hands entries to [onLog]; nothing here reads a sensor except through a
 * screen's own ViewModel. A workout runs in the recorder's own screen
 * ([workoutScreen]), and tile choices are persisted via
 * [WearTilePreferences]. [deepLinkRoute] is a screen a tile asked for.
 */
@Composable
fun OpenWatchWearApp(
    state: WearUiState,
    onLog: (LoggedEntry) -> Unit = {},
    deepLinkRoute: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    /** The workout recorder's sport list; the running workout takes the whole screen by itself. */
    workoutScreen: @Composable (onClose: () -> Unit) -> Unit = {},
    /** Link and recorder status, permissions and paired phones; the activity owns its stores. */
    statusScreen: @Composable () -> Unit = {},
    phoneScreen: @Composable () -> Unit = {},
    sensorScreen: @Composable (onOpenDetails: () -> Unit) -> Unit = {},
) {
    // The nav graph is built once, so every destination reads the latest
    // state through this holder instead of a value captured at build time.
    val currentState by rememberUpdatedState(state)

    OpenWatchWearTheme {
        // AppScaffold draws the time once, above every screen.
        AppScaffold {
            val context = LocalContext.current
            val tilePrefs = remember(context) { WearTilePreferences(context) }
            val navController = rememberSwipeDismissableNavController()
            var chosenTiles by remember { mutableStateOf(tilePrefs.loadTiles()) }
            fun updateTiles(updated: List<MetricTile>) {
                chosenTiles = updated
                tilePrefs.saveTiles(updated)
            }
            val quickLogPrefs = remember(context) { WearQuickLogPreferences(context) }
            var chosenQuickLog by remember { mutableStateOf(quickLogPrefs.loadItems()) }
            fun updateQuickLog(updated: List<QuickLogItem>) {
                chosenQuickLog = updated
                quickLogPrefs.saveItems(updated)
            }
            // A chosen tile whose sensor is missing stays chosen but is not shown.
            fun visibleTiles() = chosenTiles.filter { it in currentState.availableMetrics }
            fun log(type: EntryType, value: Double, value2: Double? = null) =
                onLog(LoggedEntry(type, System.currentTimeMillis(), value, value2))

            LaunchedEffect(deepLinkRoute) {
                val route = deepLinkRoute ?: return@LaunchedEffect
                if (WearRoutes.isDeepLinkable(route)) {
                    navController.navigate(route) { popUpTo(WearRoutes.DASHBOARD) }
                }
                onDeepLinkHandled()
            }

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = WearRoutes.DASHBOARD,
            ) {
                composable(WearRoutes.DASHBOARD) {
                    DashboardScreen(
                        tiles = visibleTiles(),
                        metrics = currentState.metrics,
                        unitSystem = currentState.preferences.unitSystem,
                        canMeasure = currentState.capabilities.measurements.isNotEmpty(),
                        onOpenMetric = { navController.navigate(WearRoutes.metric(it)) },
                        onMeasure = { navController.navigate(WearRoutes.MEASURE) },
                        onQuickLog = { navController.navigate(WearRoutes.QUICK_LOG) },
                        onEditTiles = { navController.navigate(WearRoutes.TILE_EDITOR) },
                        onStartActivity = { navController.navigate(WearRoutes.WORKOUT) },
                        onOpenSettings = { navController.navigate(WearRoutes.SETTINGS) },
                    )
                }
                composable(WearRoutes.METRIC) { entry ->
                    val metric = MetricTile.valueOf(checkNotNull(entry.arguments?.getString(WearRoutes.METRIC_ARG)))
                    val measurement = metric.spotMeasurement?.takeIf { it in currentState.capabilities.measurements }
                    MetricDetailScreen(
                        metric = metric,
                        state = currentState.metrics[metric] ?: MetricUiState(),
                        unitSystem = currentState.preferences.unitSystem,
                        zones = currentState.preferences.zones,
                        restingHeartRate = currentState.metrics[MetricTile.RESTING_HEART_RATE]?.current,
                        sleepStages = currentState.sleepStages,
                        onMeasure = measurement?.let { { navController.navigate(WearRoutes.measurement(it)) } },
                        entries = currentState.entries,
                    )
                }
                composable(WearRoutes.TILE_EDITOR) {
                    TileEditorScreen(
                        available = MetricTile.entries.filter { it in currentState.availableMetrics },
                        tiles = visibleTiles(),
                        onToggle = { metric, checked ->
                            updateTiles(if (checked) chosenTiles + metric else chosenTiles - metric)
                        },
                        onMove = { moved, target -> updateTiles(chosenTiles.withTileMoved(moved, target)) },
                        onReset = { chosenTiles = tilePrefs.resetTiles() },
                    )
                }
                composable(WearRoutes.MEASURE) {
                    MeasureListScreen(
                        capabilities = currentState.capabilities,
                        onMeasure = { navController.navigate(WearRoutes.measurement(it)) },
                    )
                }
                composable(WearRoutes.MEASUREMENT) { entry ->
                    val name = entry.arguments?.getString(WearRoutes.MEASUREMENT_ARG)
                    val measurement = Measurement.entries.firstOrNull { it.name == name } ?: Measurement.HEART_RATE
                    val capabilities = currentState.capabilities
                    MeasurementRoute(
                        measurement = measurement,
                        supported = if (capabilities.probed) measurement in capabilities.measurements else null,
                        unitSystem = currentState.preferences.unitSystem,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(WearRoutes.QUICK_LOG) {
                    QuickLogScreen(
                        state = currentState,
                        onAddWater = { log(EntryType.WATER, WaterServingMl) },
                        onLogWeight = { navController.navigate(WearRoutes.LOG_WEIGHT) },
                        onLogBloodPressure = { navController.navigate(WearRoutes.LOG_BLOOD_PRESSURE) },
                        onLogValue = { navController.navigate(WearRoutes.logValue(it)) },
                        onBreathe = { navController.navigate(WearRoutes.BREATHE) },
                        chosen = chosenQuickLog,
                        onEditItems = { navController.navigate(WearRoutes.QUICK_LOG_EDITOR) },
                    )
                }
                composable(WearRoutes.QUICK_LOG_EDITOR) {
                    QuickLogEditorScreen(
                        items = chosenQuickLog,
                        onToggle = { item, checked ->
                            updateQuickLog(if (checked) chosenQuickLog + item else chosenQuickLog - item)
                        },
                        onMove = { moved, target -> updateQuickLog(chosenQuickLog.withItemMoved(moved, target)) },
                        onReset = { chosenQuickLog = quickLogPrefs.resetItems() },
                    )
                }
                composable(WearRoutes.LOG_WEIGHT) {
                    WeightEntryScreen(
                        initialKg = currentState.entries.lastOrNull { it.type == EntryType.WEIGHT }?.value
                            ?: currentState.metrics[MetricTile.WEIGHT]?.current
                            ?: DefaultWeightKg,
                        unitSystem = currentState.preferences.unitSystem,
                        onSave = { kilograms ->
                            log(EntryType.WEIGHT, kilograms)
                            navController.popBackStack()
                        },
                    )
                }
                composable(WearRoutes.LOG_VALUE) { entry ->
                    val type = EntryType.valueOf(checkNotNull(entry.arguments?.getString(WearRoutes.LOG_VALUE_ARG)))
                    LogValueScreen(
                        type = type,
                        initialMetric = currentState.entries.lastOrNull { it.type == type }?.value,
                        unitSystem = currentState.preferences.unitSystem,
                        onSave = { metric ->
                            log(type, metric)
                            navController.popBackStack()
                        },
                    )
                }
                composable(WearRoutes.LOG_BLOOD_PRESSURE) {
                    val last = currentState.entries.lastOrNull { it.type == EntryType.BLOOD_PRESSURE }
                    BloodPressureEntryScreen(
                        initialSystolic = last?.value?.toInt() ?: 120,
                        initialDiastolic = last?.value2?.toInt() ?: 80,
                        onSave = { systolic, diastolic ->
                            log(EntryType.BLOOD_PRESSURE, systolic.toDouble(), diastolic.toDouble())
                            navController.popBackStack()
                        },
                    )
                }
                composable(WearRoutes.BREATHE) {
                    BreatheScreen(
                        onFinished = { log(EntryType.MINDFULNESS, 1.0) },
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(WearRoutes.SETTINGS) {
                    SettingsScreen(
                        state = currentState.settings,
                        unitSystem = currentState.preferences.unitSystem,
                        onPhoneClick = { navController.navigate(WearRoutes.PHONE) },
                        onSensorAccessClick = { navController.navigate(WearRoutes.SENSORS) },
                        onUnitsClick = {},
                    )
                }
                composable(WearRoutes.PHONE) { phoneScreen() }
                composable(WearRoutes.SENSORS) { sensorScreen { navController.navigate(WearRoutes.STATUS) } }
                composable(WearRoutes.STATUS) { statusScreen() }
                composable(WearRoutes.WORKOUT) { workoutScreen { navController.popBackStack() } }
            }
        }
    }
}

/** The spot measurement that refreshes this metric, if there is one. */
private val MetricTile.spotMeasurement: Measurement?
    get() = when (this) {
        MetricTile.HEART_RATE -> Measurement.HEART_RATE
        MetricTile.HRV -> Measurement.HRV
        else -> null
    }
