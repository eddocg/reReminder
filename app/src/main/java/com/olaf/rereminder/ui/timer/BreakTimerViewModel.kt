package com.olaf.rereminder.ui.timer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olaf.rereminder.data.BreakPhase
import com.olaf.rereminder.data.BreakTimer
import com.olaf.rereminder.data.BreakTimerState
import com.olaf.rereminder.data.Reminder
import com.olaf.rereminder.data.ReminderRepository
import com.olaf.rereminder.data.TimeWindow
import com.olaf.rereminder.service.AlarmService
import com.olaf.rereminder.service.ReminderScheduler
import com.olaf.rereminder.utils.AlarmReadiness
import com.olaf.rereminder.utils.NotificationHelper
import com.olaf.rereminder.utils.PreferenceHelper
import com.olaf.rereminder.utils.Reliability
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class BreakUiState(
    val timer: BreakTimerState = BreakTimer.stateOf(null, 0L, PreferenceHelper.DEFAULT_SNOOZE_MINUTES),
    /** The working-hours window, null for "any time". */
    val workingHours: TimeWindow? = null,
    val readiness: AlarmReadiness = AlarmReadiness(),
    val loaded: Boolean = false,
)

/**
 * Drives the break timer screen.
 *
 * Everything shown is derived from the stored reminder and the trigger time the scheduler wrote
 * back, never from state of its own — so after the process was killed, the device rebooted or the
 * screen was rotated, the countdown shows exactly the alarm that is armed.
 */
class BreakTimerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ReminderRepository.get(application)
    private val scheduler = ReminderScheduler(application)
    private val preferences = PreferenceHelper(application)

    private val readiness = MutableStateFlow(AlarmReadiness())
    private val snoozeMinutes = MutableStateFlow(preferences.getSnoozeMinutes())

    init {
        // One atomic write: a second call (or a racing receiver) finds the list non-empty.
        repository.mutate(BreakTimer::withDefault)
    }

    /** Only runs while [uiState] is collected, i.e. while the screen is actually visible. */
    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MILLIS)
        }
    }

    val uiState: StateFlow<BreakUiState> =
        combine(repository.reminders, readiness, snoozeMinutes, ticker) { reminders, ready, snooze, _ ->
            val reminder = BreakTimer.find(reminders)
            BreakUiState(
                timer = BreakTimer.stateOf(reminder, System.currentTimeMillis(), snooze),
                workingHours = reminder?.activeWindows?.firstOrNull(),
                readiness = ready,
                loaded = true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = BreakUiState(),
        )

    fun start() = transition { reminder, now -> BreakTimer.start(reminder, now) }

    fun resume() = transition { reminder, now -> BreakTimer.resume(reminder, now) }

    fun pause() = transition { reminder, now -> BreakTimer.pause(reminder, now) }

    fun stop() = transition { reminder, _ -> BreakTimer.stop(reminder) }

    fun setInterval(minutes: Int) = transition { reminder, now -> BreakTimer.withInterval(reminder, minutes, now) }

    /**
     * Re-arms the alarm in case anything drifted while the app was away (a no-op when nothing
     * did) and re-reads the system settings the user may just have changed.
     */
    fun refresh() {
        snoozeMinutes.value = preferences.getSnoozeMinutes()
        scheduler.sync()
        readiness.value = Reliability.readiness(getApplication())
    }

    private fun transition(transform: (Reminder, Long) -> Reminder) {
        val id = BreakTimer.find(repository.reminders.value)?.id ?: return
        repository.update(id) { transform(it, System.currentTimeMillis()) }
        scheduler.sync()

        val stored = repository.get(id)
        if (BreakTimer.phaseOf(stored) != BreakPhase.RUNNING) {
            // Pausing or stopping also silences an alarm that is going off right now.
            if (AlarmService.ringing.value?.reminderId == id) {
                AlarmService.send(getApplication(), AlarmService.ACTION_DISMISS)
            }
            NotificationHelper.cancelReminderNotification(getApplication(), id)
        }
    }

    private companion object {
        /** Under a second, so the display never holds one second twice and skips the next. */
        const val TICK_MILLIS = 250L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
