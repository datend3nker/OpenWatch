package tech.mmarca.openwatch.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.domain.preferences.UnitSystem

/**
 * The few app-wide preferences OpenWatch keeps: whether Health Connect writes
 * are paused, the declared body profile, the exercise type an imported
 * activity falls back to, and whether setup was finished. Plain
 * SharedPreferences, as every store in the device layer.
 */
@Singleton
class OpenWatchPreferences(
    private val prefs: SharedPreferences,
    private val unitSystemProvider: SystemUnitSystemProvider,
) : BodyProfilePreferences {

    @Inject
    constructor(@ApplicationContext context: Context, unitSystemProvider: SystemUnitSystemProvider) : this(
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
        unitSystemProvider,
    )

    private val _healthConnectSyncEnabled = MutableStateFlow(prefs.getBoolean(KEY_HEALTH_CONNECT_SYNC_ENABLED, true))

    /** Off pauses every Health Connect write; the devices keep what they heard until it is on again. */
    val healthConnectSyncEnabledFlow: StateFlow<Boolean> = _healthConnectSyncEnabled.asStateFlow()

    private val _writeRespiratoryRate = MutableStateFlow(prefs.getBoolean(KEY_WRITE_RESPIRATORY_RATE, false))

    /** Whether the watch's breathing rate is written to Health Connect. Off by default: not yet checked against a reference. */
    val writeRespiratoryRateFlow: StateFlow<Boolean> = _writeRespiratoryRate.asStateFlow()

    var writeRespiratoryRate: Boolean
        get() = _writeRespiratoryRate.value
        set(value) {
            prefs.edit { putBoolean(KEY_WRITE_RESPIRATORY_RATE, value) }
            _writeRespiratoryRate.value = value
        }

    private val _writeSleepBesideOthers = MutableStateFlow(prefs.getBoolean(KEY_WRITE_SLEEP_BESIDE_OTHERS, true))

    /**
     * Whether the watch's night is written although another app (Samsung Health, Sleep as Android)
     * already wrote one: Health Connect's data source priority then decides which one apps see.
     * On by default; off leaves the other app's night alone.
     */
    val writeSleepBesideOthersFlow: StateFlow<Boolean> = _writeSleepBesideOthers.asStateFlow()

    var writeSleepBesideOthers: Boolean
        get() = _writeSleepBesideOthers.value
        set(value) {
            prefs.edit { putBoolean(KEY_WRITE_SLEEP_BESIDE_OTHERS, value) }
            _writeSleepBesideOthers.value = value
        }

    var healthConnectSyncEnabled: Boolean
        get() = _healthConnectSyncEnabled.value
        set(value) {
            prefs.edit { putBoolean(KEY_HEALTH_CONNECT_SYNC_ENABLED, value) }
            _healthConnectSyncEnabled.value = value
        }

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))

    val onboardingDoneFlow: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    var onboardingDone: Boolean
        get() = _onboardingDone.value
        set(value) {
            prefs.edit { putBoolean(KEY_ONBOARDING_DONE, value) }
            _onboardingDone.value = value
        }

    /** The system's locale decides; OpenWatch has no override of its own. */
    val unitSystem: UnitSystem
        get() = unitSystemProvider.current()

    private val _bodyProfile = MutableStateFlow(readBodyProfile())

    override val bodyProfileFlow: Flow<BodyProfile> = _bodyProfile.asStateFlow()

    override fun bodyProfile(): BodyProfile = _bodyProfile.value

    override fun setBodyProfile(profile: BodyProfile) {
        val normalized = profile.normalized()
        prefs.edit {
            normalized.birthYear?.let { putInt(KEY_BODY_PROFILE_BIRTH_YEAR, it) }
                ?: remove(KEY_BODY_PROFILE_BIRTH_YEAR)
            normalized.weightKg?.let { putFloat(KEY_BODY_PROFILE_WEIGHT_KG, it.toFloat()) }
                ?: remove(KEY_BODY_PROFILE_WEIGHT_KG)
            normalized.heightCm?.let { putFloat(KEY_BODY_PROFILE_HEIGHT_CM, it.toFloat()) }
                ?: remove(KEY_BODY_PROFILE_HEIGHT_CM)
            normalized.restingHeartRateBpm?.let { putInt(KEY_BODY_PROFILE_RESTING_HR_BPM, it) }
                ?: remove(KEY_BODY_PROFILE_RESTING_HR_BPM)
            normalized.maxHeartRateBpm?.let { putInt(KEY_BODY_PROFILE_MAX_HR_BPM, it) }
                ?: remove(KEY_BODY_PROFILE_MAX_HR_BPM)
            normalized.sex?.let { putString(KEY_BODY_PROFILE_SEX, it.name) } ?: remove(KEY_BODY_PROFILE_SEX)
        }
        _bodyProfile.value = normalized
    }

    private fun readBodyProfile(): BodyProfile =
        BodyProfile(
            birthYear = prefs.getInt(KEY_BODY_PROFILE_BIRTH_YEAR, MISSING_INT).takeIf { it != MISSING_INT },
            weightKg = prefs.getFloat(KEY_BODY_PROFILE_WEIGHT_KG, MISSING_FLOAT)
                .takeIf { it != MISSING_FLOAT }?.toDouble(),
            heightCm = prefs.getFloat(KEY_BODY_PROFILE_HEIGHT_CM, MISSING_FLOAT)
                .takeIf { it != MISSING_FLOAT }?.toDouble(),
            restingHeartRateBpm = prefs.getInt(KEY_BODY_PROFILE_RESTING_HR_BPM, MISSING_INT).takeIf { it != MISSING_INT },
            maxHeartRateBpm = prefs.getInt(KEY_BODY_PROFILE_MAX_HR_BPM, MISSING_INT).takeIf { it != MISSING_INT },
            sex = prefs.getString(KEY_BODY_PROFILE_SEX, null)
                ?.let { name -> BiologicalSex.entries.firstOrNull { it.name == name } },
        ).normalized()

    /** The exercise type the last imported activity carried; an unnamed file takes it. */
    var lastActivityExerciseType: Int?
        get() = prefs.getInt(KEY_LAST_ACTIVITY_EXERCISE_TYPE, MISSING_INT).takeIf { it != MISSING_INT }
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_LAST_ACTIVITY_EXERCISE_TYPE) else putInt(KEY_LAST_ACTIVITY_EXERCISE_TYPE, value)
            }
        }

    /** A preferred exercise type, asked before [lastActivityExerciseType]. OpenWatch has no screen for it yet. */
    var favoriteActivityExerciseType: Int?
        get() = prefs.getInt(KEY_FAVORITE_ACTIVITY_EXERCISE_TYPE, MISSING_INT).takeIf { it != MISSING_INT }
        set(value) {
            prefs.edit {
                if (value == null) {
                    remove(KEY_FAVORITE_ACTIVITY_EXERCISE_TYPE)
                } else {
                    putInt(KEY_FAVORITE_ACTIVITY_EXERCISE_TYPE, value)
                }
            }
        }

    companion object {
        const val PREFS_FILE = "openwatch_prefs"
        private const val KEY_HEALTH_CONNECT_SYNC_ENABLED = "health_connect_sync_enabled"
        private const val KEY_WRITE_RESPIRATORY_RATE = "write_respiratory_rate"
        private const val KEY_WRITE_SLEEP_BESIDE_OTHERS = "write_sleep_beside_others"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_BODY_PROFILE_BIRTH_YEAR = "body_profile_birth_year"
        private const val KEY_BODY_PROFILE_WEIGHT_KG = "body_profile_weight_kg"
        private const val KEY_BODY_PROFILE_HEIGHT_CM = "body_profile_height_cm"
        private const val KEY_BODY_PROFILE_RESTING_HR_BPM = "body_profile_resting_hr_bpm"
        private const val KEY_BODY_PROFILE_MAX_HR_BPM = "body_profile_max_hr_bpm"
        private const val KEY_BODY_PROFILE_SEX = "body_profile_sex"
        private const val KEY_LAST_ACTIVITY_EXERCISE_TYPE = "last_activity_exercise_type"
        private const val KEY_FAVORITE_ACTIVITY_EXERCISE_TYPE = "favorite_activity_exercise_type"
        private const val MISSING_INT = Int.MIN_VALUE
        private const val MISSING_FLOAT = Float.MIN_VALUE
    }
}
