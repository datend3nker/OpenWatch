package tech.mmarca.openwatch.wear

import androidx.annotation.StringRes
import tech.mmarca.openwatch.wear.features.metric.DefaultZoneBasis
import tech.mmarca.openwatch.wear.features.metric.ZoneBasis
import tech.mmarca.openwatch.wear.features.quicklog.LoggedEntry
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.health.WearCapabilities

/** Everything the screens render. A null value means "no reading yet". */
data class WearUiState(
    /**
     * The metrics this watch has a sensor for. Only these are offered as
     * tiles. Every metric until sensor discovery narrows it.
     */
    val availableMetrics: Set<MetricTile> = MetricTile.entries.toSet(),
    /** A metric without an entry renders as "no reading yet". */
    val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
    val settings: SettingsUiState = SettingsUiState(),
    val preferences: DisplayPreferences = DisplayPreferences(),
    /** What the watch can measure; gates measurement buttons and workouts. */
    val capabilities: WearCapabilities = WearCapabilities(),
    /** Last night's minutes per stage, as synced from the phone. */
    val sleepStages: Map<SleepStage, Double> = emptyMap(),
    /** Quick logs and spot measurements made on this watch, oldest first. */
    val entries: List<LoggedEntry> = emptyList(),
)

/** One metric's readings, in the base unit documented on [MetricTile]. */
data class MetricUiState(
    /** Today's total for a cumulative metric, the latest sample otherwise. */
    val current: Double? = null,
    /** A daily goal. Only meaningful for a cumulative metric. */
    val goal: Double? = null,
    /** Today, oldest first: hourly totals for a cumulative metric, samples otherwise. */
    val today: List<Double> = emptyList(),
    /** The last days, oldest first, today last: daily totals or daily averages. */
    val week: List<Double> = emptyList(),
)

/**
 * Display settings the watch does not own: the phone app is where they are
 * chosen, and a later sync writes them here. Readings stay metric; only
 * their display changes.
 */
data class DisplayPreferences(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** What the heart rate zones rest on: the phone sends the profile's maximum, the resting rate is the latest night's unless the profile has one. */
    val zones: ZoneBasis = DefaultZoneBasis,
    /** Daily water goal in millilitres. */
    val waterGoalMl: Int = 2_000,
)

data class RecordingUiState(
    val activityType: ActivityType = ActivityType.WALK,
    /** The sport's own name, when it is more precise than [activityType]'s. */
    @param:StringRes val title: Int? = null,
    val elapsedSeconds: Long = 0,
    val heartRateBpm: Int? = null,
    val distanceMeters: Int? = null,
    val caloriesKcal: Int? = null,
    val elevationGainMeters: Int? = null,
    val speedMetersPerSecond: Double? = null,
    val averageHeartRateBpm: Int? = null,
    val paused: Boolean = false,
    /** A route sport without a usable GPS fix yet. */
    val waitingForFix: Boolean = false,
)

data class SettingsUiState(
    val phoneConnected: Boolean = false,
    val sensorAccessGranted: Boolean = false,
) {
    companion object {
        /**
         * A phone counts as connected while one is allowed, still paired and
         * the link listens. Not while a request is open: the phone only holds
         * a connection for as long as a pull takes, so no client at this
         * moment says nothing about the link.
         */
        fun from(link: WearLinkUiState, sensorAccessGranted: Boolean): SettingsUiState =
            SettingsUiState(
                phoneConnected = link.listening &&
                    link.trusted.any { phone -> link.bondLost.none { it.address == phone.address } },
                sensorAccessGranted = sensorAccessGranted,
            )
    }
}
