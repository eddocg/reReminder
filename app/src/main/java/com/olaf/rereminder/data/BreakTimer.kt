package com.olaf.rereminder.data

import java.util.Locale

/** What the break timer is doing, as the main screen presents it. */
enum class BreakPhase {
    /** Never started, or stopped: Start begins a fresh interval. */
    IDLE,
    RUNNING,
    /** Paused: no alerts until Resume, which begins a fresh interval. */
    PAUSED,
}

/** The main screen's view of the break timer at one moment. */
data class BreakTimerState(
    val phase: BreakPhase,
    val intervalMinutes: Int,
    /** Whole seconds until the next alert, rounded up; the full interval when not running. */
    val remainingMillis: Long,
    /** Share of the current wait still ahead: 1f right after an alarm, approaching 0f. */
    val remainingFraction: Float,
    /** The armed alert (a snooze when [isSnoozed]), 0 when nothing is armed. */
    val nextAlertAt: Long,
    val isSnoozed: Boolean,
    /**
     * True when the next alert is further away than one interval: the working hours are closed,
     * so the screen shows when the next break is due instead of a long countdown.
     */
    val waitingForWorkingHours: Boolean,
    val hasWorkingHours: Boolean,
)

/**
 * The single recurring break reminder this app is built around.
 *
 * It is an ordinary [Reminder] — an interval timer that rings as an alarm — so it runs on the
 * unchanged reReminder engine: [com.olaf.rereminder.service.ReminderScheduler] arms it on its
 * fixed grid, and the receivers ring and recover it. This object only adds the break-timer
 * vocabulary on top. Everything here is pure, so the semantics are covered by unit tests:
 *
 * - Start (and Resume) anchors the grid at that moment, so the first alarm is one full interval
 *   later and the rest follow on a fixed cadence from it — a late or dismissed alarm never
 *   shifts the ones after it.
 * - Pause and Stop both disable the timer, which cancels its alarms; Pause is only remembered so
 *   the screen can offer Resume instead of Start.
 * - Snooze and Dismiss are handled by the alarm itself and never touch the grid.
 */
object BreakTimer {

    const val DEFAULT_INTERVAL_MINUTES = 20
    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 240

    /** Offered as one-tap choices in the interval dialog. */
    val INTERVAL_PRESETS = listOf(10, 15, 20, 25, 30, 45, 60, 90)

    /**
     * What a fresh install starts with: one 20-minute break alarm, not running until the user
     * presses Start. The name stays empty so the alarm shows the localized default.
     */
    fun defaultReminder(id: Int = 1): Reminder = Reminder(
        id = id,
        enabled = false,
        repeat = Repeat(RepeatUnit.TIME, DEFAULT_INTERVAL_MINUTES),
        alertStyle = AlertStyle.ALARM,
        sound = SoundChoice(SoundMode.DEFAULT),
    )

    /** The break reminder among the stored ones: the oldest, should there ever be several. */
    fun find(reminders: List<Reminder>): Reminder? = reminders.minByOrNull { it.id }

    /** Adds [defaultReminder] to an empty list; leaves any existing list alone. */
    fun withDefault(reminders: List<Reminder>): List<Reminder> =
        reminders.ifEmpty { listOf(defaultReminder()) }

    fun phaseOf(reminder: Reminder?): BreakPhase = when {
        reminder == null -> BreakPhase.IDLE
        reminder.enabled -> BreakPhase.RUNNING
        reminder.pausedAtMillis > 0L -> BreakPhase.PAUSED
        else -> BreakPhase.IDLE
    }

    fun clampInterval(minutes: Int): Int = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    // --- Transitions -----------------------------------------------------------------------
    //
    // Each returns the reminder to store; the caller then lets the scheduler sync, which arms or
    // cancels the alarm to match. Clearing the scheduler's bookkeeping here as well keeps the
    // screen honest in the instant before that sync lands.

    /** Begins a fresh interval now: the first alarm is one full interval away. */
    fun start(reminder: Reminder, now: Long): Reminder = reminder.copy(
        enabled = true,
        anchorMillis = now,
        // A pinned start moment would override the anchor; the break timer never pins one.
        startAtMillis = 0L,
        pausedAtMillis = 0L,
        snoozeUntil = 0L,
        nextTriggerAt = 0L,
        nextEventAt = 0L,
    )

    /** Resume restarts the countdown with a full interval rather than the time that was left. */
    fun resume(reminder: Reminder, now: Long): Reminder = start(reminder, now)

    fun pause(reminder: Reminder, now: Long): Reminder = reminder.copy(
        enabled = false,
        pausedAtMillis = now,
        snoozeUntil = 0L,
        nextTriggerAt = 0L,
        nextEventAt = 0L,
    )

    fun stop(reminder: Reminder): Reminder = reminder.copy(
        enabled = false,
        pausedAtMillis = 0L,
        snoozeUntil = 0L,
        nextTriggerAt = 0L,
        nextEventAt = 0L,
    )

    /**
     * A new interval. A running timer starts counting the new length from now, as if restarted;
     * a stopped or paused one simply remembers it.
     */
    fun withInterval(reminder: Reminder, minutes: Int, now: Long): Reminder {
        val updated = reminder.copy(repeat = Repeat(RepeatUnit.TIME, clampInterval(minutes)))
        if (updated.repeat == reminder.repeat) return reminder
        return if (reminder.enabled) start(updated, now) else updated
    }

    /** One working-hours window, or none for "any time". */
    fun withWorkingHours(reminder: Reminder, window: TimeWindow?): Reminder =
        reminder.copy(windows = listOfNotNull(window))

    // --- State -----------------------------------------------------------------------------

    /**
     * What the main screen shows at [now]. [snoozeMinutes] is the current snooze length, used to
     * size the ring while a snooze counts down.
     */
    fun stateOf(reminder: Reminder?, now: Long, snoozeMinutes: Int): BreakTimerState {
        val interval = clampInterval(reminder?.repeat?.every ?: DEFAULT_INTERVAL_MINUTES)
        val intervalMillis = interval * MINUTE
        val phase = phaseOf(reminder)
        val hasWindows = reminder?.activeWindows?.isNotEmpty() == true

        if (reminder == null || phase != BreakPhase.RUNNING) {
            return BreakTimerState(
                phase = phase,
                intervalMinutes = interval,
                remainingMillis = intervalMillis,
                remainingFraction = 1f,
                nextAlertAt = 0L,
                isSnoozed = false,
                waitingForWorkingHours = false,
                hasWorkingHours = hasWindows,
            )
        }

        val snoozed = reminder.snoozeUntil > now
        val nextAlertAt = if (snoozed) reminder.snoozeUntil else reminder.nextTriggerAt
        // Not armed yet (the sync is still on its way): show a full interval, not 00:00.
        val remaining = if (nextAlertAt > 0L) ceilToSecond(nextAlertAt - now) else intervalMillis
        val cycle = if (snoozed) snoozeMinutes.coerceAtLeast(1) * MINUTE else intervalMillis
        val waiting = hasWindows && !snoozed && remaining > intervalMillis
        val fraction = if (waiting) 0f else (remaining.toFloat() / cycle).coerceIn(0f, 1f)

        return BreakTimerState(
            phase = phase,
            intervalMinutes = interval,
            remainingMillis = remaining,
            remainingFraction = fraction,
            nextAlertAt = nextAlertAt,
            isSnoozed = snoozed,
            waitingForWorkingHours = waiting,
            hasWorkingHours = hasWindows,
        )
    }

    /** "MM:SS"; the minutes simply grow past 59 ("90:00") as the interval allows. */
    fun formatMinutesSeconds(millis: Long): String {
        val totalSeconds = ceilToSecond(millis) / 1_000L
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /**
     * Rounded up to whole seconds, so the countdown shows 00:01 until the very moment it rings
     * and ticks that differ only in milliseconds compare equal.
     */
    fun ceilToSecond(millis: Long): Long =
        if (millis <= 0L) 0L else (millis + 999L) / 1_000L * 1_000L

    private const val MINUTE = 60_000L
}
