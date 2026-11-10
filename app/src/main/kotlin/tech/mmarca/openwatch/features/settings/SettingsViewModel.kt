package tech.mmarca.openwatch.features.settings

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.mmarca.openwatch.BuildConfig
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences

data class SettingsUiState(
    val syncEnabled: Boolean = true,
    /** Whether the system lets this app read notifications, for the watch forwarder. */
    val notificationAccessGranted: Boolean = false,
    val writeRespiratoryRate: Boolean = true,
    val writeSleepBesideOthers: Boolean = true,
    val versionName: String = BuildConfig.VERSION_NAME,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: OpenWatchPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            syncEnabled = preferences.healthConnectSyncEnabled,
            writeRespiratoryRate = preferences.writeRespiratoryRate,
            writeSleepBesideOthers = preferences.writeSleepBesideOthers,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update {
            it.copy(
                syncEnabled = preferences.healthConnectSyncEnabled,
                writeRespiratoryRate = preferences.writeRespiratoryRate,
                writeSleepBesideOthers = preferences.writeSleepBesideOthers,
                notificationAccessGranted =
                    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
            )
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        preferences.healthConnectSyncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }

    fun setWriteSleepBesideOthers(enabled: Boolean) {
        preferences.writeSleepBesideOthers = enabled
        _uiState.update { it.copy(writeSleepBesideOthers = enabled) }
    }

    fun setWriteRespiratoryRate(enabled: Boolean) {
        preferences.writeRespiratoryRate = enabled
        _uiState.update { it.copy(writeRespiratoryRate = enabled) }
    }
}
