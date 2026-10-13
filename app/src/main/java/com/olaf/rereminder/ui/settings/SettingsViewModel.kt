package com.olaf.rereminder.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.olaf.rereminder.data.BreakTimer
import com.olaf.rereminder.data.ReminderRepository
import com.olaf.rereminder.data.TimeWindow
import com.olaf.rereminder.service.ReminderScheduler
import com.olaf.rereminder.utils.AlarmReadiness
import com.olaf.rereminder.utils.GentleSound
import com.olaf.rereminder.utils.PreferenceHelper
import com.olaf.rereminder.utils.Reliability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The break alarm's settings: how it sounds, when it may ring, and what could stop it. */
data class SettingsUiState(
    val gentleSound: GentleSound = GentleSound.DEFAULT,
    val alarmVolumePercent: Int = PreferenceHelper.DEFAULT_ALARM_VOLUME,
    val soundTimeoutSeconds: Int = PreferenceHelper.DEFAULT_SOUND_TIMEOUT_SECONDS,
    val snoozeMinutes: Int = PreferenceHelper.DEFAULT_SNOOZE_MINUTES,
    val vibrationEnabled: Boolean = true,
    val vibrationPattern: Int = 1,
    /** Null for "any time". */
    val workingHours: TimeWindow? = null,
    /** System-level conditions that decide whether the alarm actually rings and shows. */
    val readiness: AlarmReadiness = AlarmReadiness(),
    /** Empty when the manufacturer could not be read. */
    val vendorName: String = "",
    /** The dontkillmyapp.com page for this device, or its general guide. */
    val guideUrl: String = "https://dontkillmyapp.com/general",
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceHelper(application)
    private val repository = ReminderRepository.get(application)
    private val scheduler = ReminderScheduler(application)

    private val _uiState = MutableStateFlow(readSettings())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun readSettings(): SettingsUiState {
        repository.mutate(BreakTimer::withDefault)
        val status = Reliability.status(getApplication())
        return SettingsUiState(
            gentleSound = preferences.getGentleSound(),
            alarmVolumePercent = preferences.getAlarmVolumePercent(),
            soundTimeoutSeconds = preferences.getSoundTimeoutSeconds(),
            snoozeMinutes = preferences.getSnoozeMinutes(),
            vibrationEnabled = preferences.isVibrationEnabled(),
            vibrationPattern = preferences.getVibrationPattern(),
            workingHours = BreakTimer.find(repository.reminders.value)?.activeWindows?.firstOrNull(),
            readiness = Reliability.readiness(getApplication()),
            vendorName = status.vendorName,
            guideUrl = status.guideUrl,
        )
    }

    /** All of these can change outside the app, so re-read them whenever the screen resumes. */
    fun refreshSystemStatus() {
        _uiState.update { it.copy(readiness = Reliability.readiness(getApplication())) }
    }

    fun setGentleSound(sound: GentleSound) {
        preferences.setGentleSound(sound)
        _uiState.update { it.copy(gentleSound = sound) }
    }

    fun setAlarmVolumePercent(percent: Int) {
        preferences.setAlarmVolumePercent(percent)
        _uiState.update { it.copy(alarmVolumePercent = preferences.getAlarmVolumePercent()) }
    }

    fun setSoundTimeoutSeconds(seconds: Int) {
        preferences.setSoundTimeoutSeconds(seconds)
        _uiState.update { it.copy(soundTimeoutSeconds = seconds) }
    }

    fun setSnoozeMinutes(minutes: Int) {
        preferences.setSnoozeMinutes(minutes)
        _uiState.update { it.copy(snoozeMinutes = minutes) }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        preferences.setVibrationEnabled(enabled)
        _uiState.update { it.copy(vibrationEnabled = enabled) }
    }

    fun setVibrationPattern(pattern: Int) {
        preferences.setVibrationPattern(pattern)
        _uiState.update { it.copy(vibrationPattern = pattern) }
    }

    /**
     * Working hours only filter the break grid, they never restart it, so a running countdown
     * carries on and the scheduler merely picks the next tick that falls inside them.
     */
    fun setWorkingHours(window: TimeWindow?) {
        val id = BreakTimer.find(repository.reminders.value)?.id ?: return
        repository.update(id) { BreakTimer.withWorkingHours(it, window) }
        scheduler.sync()
        _uiState.update { it.copy(workingHours = window) }
    }
}
