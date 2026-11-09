package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wear.features.quicklog.EntryLog
import tech.mmarca.openwatch.wear.features.quicklog.LoggedEntry
import tech.mmarca.openwatch.wear.features.quicklog.withEntries
import tech.mmarca.openwatch.wear.health.CapabilityProbe
import tech.mmarca.openwatch.wear.health.WearCapabilities

/**
 * Puts the app-wide state together: what the watch recorded, what this watch
 * can really measure, and the entries made on it.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val entryLog = EntryLog(application)
    private val capabilities = MutableStateFlow(WearCapabilities())
    private val readings = WearReadingsSource(MetricStore(application), DaySummaryStore(application)::all)

    private val initial = WearUiState()

    val uiState: StateFlow<WearUiState> =
        combine(capabilities, entryLog.entries, readings.metrics()) { capabilities, entries, readings ->
            val metrics = readings.metrics
            initial.copy(
                metrics = metrics,
                sleepStages = readings.sleepStages,
                capabilities = capabilities,
                // Until the probe answers, keep offering what was on offer before.
                availableMetrics = if (capabilities.probed) capabilities.availableMetrics(metrics.keys) else initial.availableMetrics,
            ).withEntries(entries)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), initial)

    init {
        viewModelScope.launch { capabilities.value = CapabilityProbe(application).probe() }
    }

    fun log(entry: LoggedEntry) = entryLog.add(entry)

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
