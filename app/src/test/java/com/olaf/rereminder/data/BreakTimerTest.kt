package com.olaf.rereminder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The break timer's semantics. The scheduler arms whatever [nextAlertAfter] returns and the
 * receivers re-arm from the stored reminder, so these pure checks are what decides when the
 * phone rings.
 */
class BreakTimerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val minute = 60_000L

    /** Tuesday, 22 Sep 2026, 10:00 in Berlin. */
    private val t0 = at(2026, 9, 22, 10, 0)

    private fun Reminder.nextAlert(from: Long, zone: ZoneId = this@BreakTimerTest.zone): Long? =
        nextAlertAfter(from, zone)?.alertAt

    /** What the scheduler stores after arming at [now]. */
    private fun Reminder.armed(now: Long): Reminder = copy(nextTriggerAt = nextAlert(now) ?: 0L)

    // --- Fresh install -----------------------------------------------------------------------

    @Test
    fun `a fresh install has one 20 minute break alarm that is not running`() {
        val reminders = BreakTimer.withDefault(emptyList())
        val reminder = reminders.single()

        assertFalse(reminder.enabled)
        assertEquals(Repeat(RepeatUnit.TIME, 20), reminder.repeat)
        assertEquals(AlertStyle.ALARM, reminder.alertStyle)
        assertEquals(BreakPhase.IDLE, BreakTimer.phaseOf(reminder))
    }

    @Test
    fun `an existing reminder is never replaced by the default`() {
        val existing = listOf(Reminder(id = 7, repeat = Repeat(RepeatUnit.TIME, 45)))

        assertSame(existing, BreakTimer.withDefault(existing))
    }

    @Test
    fun `the break reminder is the oldest one`() {
        val reminders = listOf(Reminder(id = 5), Reminder(id = 2), Reminder(id = 9))

        assertEquals(2, BreakTimer.find(reminders)?.id)
    }

    // --- Start and recurrence ----------------------------------------------------------------

    @Test
    fun `start rings one full interval later`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)

        assertTrue(running.enabled)
        assertEquals(BreakPhase.RUNNING, BreakTimer.phaseOf(running))
        assertEquals(t0 + 20 * minute, running.nextAlert(t0))
    }

    @Test
    fun `alarms follow a fixed cadence from the start`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)

        var from = t0
        val alerts = List(5) { running.nextAlert(from)!!.also { from = it } }

        assertEquals((1..5).map { t0 + it * 20 * minute }, alerts)
    }

    @Test
    fun `a late dismiss does not shift the next break`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)

        // The 10:20 alarm was dismissed at 10:27; the next one is still 10:40, not 10:47.
        assertEquals(t0 + 40 * minute, running.nextAlert(t0 + 27 * minute))
    }

    @Test
    fun `a delivery a few seconds late is re-armed on the grid`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val ringing = t0 + 20 * minute

        // The receiver re-arms from the moment the alarm fired, before the alarm is answered.
        assertTrue(running.producesAlertAt(ringing, zone))
        assertEquals(t0 + 40 * minute, running.nextAlert(ringing + 3_000L))
    }

    @Test
    fun `a stale alarm left over from before a restart is recognised as stale`() {
        val first = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val restarted = BreakTimer.start(first, t0 + 7 * minute)

        // The old 10:20 broadcast must not ring for a timer now due at 10:27.
        assertFalse(restarted.producesAlertAt(t0 + 20 * minute, zone))
        assertTrue(restarted.producesAlertAt(t0 + 27 * minute, zone))
    }

    @Test
    fun `starting again begins a fresh interval`() {
        val stopped = BreakTimer.stop(BreakTimer.start(BreakTimer.defaultReminder(), t0))
        val restarted = BreakTimer.start(stopped, t0 + 33 * minute)

        assertEquals(t0 + 53 * minute, restarted.nextAlert(t0 + 33 * minute))
    }

    @Test
    fun `start clears a pinned start moment and any leftover snooze`() {
        val leftover = BreakTimer.defaultReminder().copy(
            startAtMillis = t0 + 300 * minute,
            snoozeUntil = t0 + 5 * minute,
            nextTriggerAt = t0 + 3 * minute,
        )
        val running = BreakTimer.start(leftover, t0)

        assertEquals(0L, running.startAtMillis)
        assertEquals(0L, running.snoozeUntil)
        assertEquals(t0 + 20 * minute, running.nextAlert(t0))
    }

    // --- Pause, resume, stop -----------------------------------------------------------------

    @Test
    fun `pause disables alerts and remembers it was paused`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0).armed(t0)
        val paused = BreakTimer.pause(running.copy(snoozeUntil = t0 + 25 * minute), t0 + 8 * minute)

        assertFalse(paused.enabled)
        assertEquals(BreakPhase.PAUSED, BreakTimer.phaseOf(paused))
        assertEquals(0L, paused.snoozeUntil)
        assertEquals(0L, paused.nextTriggerAt)
    }

    @Test
    fun `resume restarts the full interval rather than the time that was left`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val paused = BreakTimer.pause(running, t0 + 8 * minute)
        val resumed = BreakTimer.resume(paused, t0 + 30 * minute)

        assertEquals(BreakPhase.RUNNING, BreakTimer.phaseOf(resumed))
        assertEquals(0L, resumed.pausedAtMillis)
        assertEquals(t0 + 50 * minute, resumed.nextAlert(t0 + 30 * minute))
    }

    @Test
    fun `stop forgets the pause so the screen offers Start again`() {
        val paused = BreakTimer.pause(BreakTimer.start(BreakTimer.defaultReminder(), t0), t0 + minute)
        val stopped = BreakTimer.stop(paused)

        assertFalse(stopped.enabled)
        assertEquals(BreakPhase.IDLE, BreakTimer.phaseOf(stopped))
    }

    @Test
    fun `stop keeps the interval the user chose`() {
        val custom = BreakTimer.withInterval(BreakTimer.defaultReminder(), 45, t0)
        val stopped = BreakTimer.stop(BreakTimer.start(custom, t0))

        assertEquals(45, stopped.repeat.every)
    }

    // --- Interval ----------------------------------------------------------------------------

    @Test
    fun `the interval is clamped to a sane range`() {
        assertEquals(1, BreakTimer.withInterval(BreakTimer.defaultReminder(), 0, t0).repeat.every)
        assertEquals(240, BreakTimer.withInterval(BreakTimer.defaultReminder(), 999, t0).repeat.every)
    }

    @Test
    fun `an unchanged interval leaves the reminder untouched`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)

        assertSame(running, BreakTimer.withInterval(running, 20, t0 + 9 * minute))
    }

    @Test
    fun `changing the interval of a running timer restarts the countdown`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val changed = BreakTimer.withInterval(running, 30, t0 + 9 * minute)

        assertTrue(changed.enabled)
        assertEquals(t0 + 39 * minute, changed.nextAlert(t0 + 9 * minute))
    }

    @Test
    fun `changing the interval of a stopped timer does not start it`() {
        val changed = BreakTimer.withInterval(BreakTimer.defaultReminder(), 30, t0)

        assertFalse(changed.enabled)
        assertEquals(BreakPhase.IDLE, BreakTimer.phaseOf(changed))
        assertEquals(30, changed.repeat.every)
    }

    @Test
    fun `changing the interval while paused keeps it paused`() {
        val paused = BreakTimer.pause(BreakTimer.start(BreakTimer.defaultReminder(), t0), t0 + minute)

        assertEquals(BreakPhase.PAUSED, BreakTimer.phaseOf(BreakTimer.withInterval(paused, 30, t0)))
    }

    // --- Snooze --------------------------------------------------------------------------------

    @Test
    fun `snooze never moves the regular grid`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val snoozed = running.copy(snoozeUntil = t0 + 25 * minute)

        assertEquals(t0 + 40 * minute, snoozed.nextAlert(t0 + 20 * minute))
    }

    @Test
    fun `a snooze that would ring at or after the next break is dropped`() {
        val next = t0 + 40 * minute

        assertEquals(t0 + 25 * minute, snoozeToKeep(t0 + 25 * minute, t0 + 20 * minute, next))
        assertEquals(0L, snoozeToKeep(next, t0 + 20 * minute, next))
        assertEquals(0L, snoozeToKeep(next + minute, t0 + 20 * minute, next))
    }

    @Test
    fun `a snooze that came due while the phone was off is not rung late`() {
        assertEquals(0L, snoozeToKeep(t0, t0, t0 + 20 * minute))
        assertEquals(0L, snoozeToKeep(t0 - minute, t0, t0 + 20 * minute))
    }

    @Test
    fun `a snooze is kept when there is no next regular alert`() {
        assertEquals(t0 + 5 * minute, snoozeToKeep(t0 + 5 * minute, t0, null))
    }

    // --- Screen state ------------------------------------------------------------------------

    @Test
    fun `an idle timer shows its full interval`() {
        val state = BreakTimer.stateOf(BreakTimer.defaultReminder(), t0, snoozeMinutes = 5)

        assertEquals(BreakPhase.IDLE, state.phase)
        assertEquals(20 * minute, state.remainingMillis)
        assertEquals(1f, state.remainingFraction)
        assertEquals(0L, state.nextAlertAt)
        assertEquals("20:00", BreakTimer.formatMinutesSeconds(state.remainingMillis))
    }

    @Test
    fun `a running timer counts down to the armed alarm`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0).armed(t0)
        val state = BreakTimer.stateOf(running, t0 + 15 * minute, snoozeMinutes = 5)

        assertEquals(t0 + 20 * minute, state.nextAlertAt)
        assertEquals(5 * minute, state.remainingMillis)
        assertEquals(0.25f, state.remainingFraction, 0.0001f)
        assertFalse(state.isSnoozed)
        assertFalse(state.waitingForWorkingHours)
    }

    @Test
    fun `the countdown rounds up so it never shows zero before it rings`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0).armed(t0)
        val state = BreakTimer.stateOf(running, t0 + 20 * minute - 400L, snoozeMinutes = 5)

        assertEquals("00:01", BreakTimer.formatMinutesSeconds(state.remainingMillis))
    }

    @Test
    fun `before the scheduler has armed it the timer shows a full interval, not zero`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val state = BreakTimer.stateOf(running, t0, snoozeMinutes = 5)

        assertEquals(20 * minute, state.remainingMillis)
    }

    @Test
    fun `a snooze counts down on its own length`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0).armed(t0)
            .copy(snoozeUntil = t0 + 23 * minute)
        val state = BreakTimer.stateOf(running, t0 + 21 * minute, snoozeMinutes = 5)

        assertTrue(state.isSnoozed)
        assertEquals(t0 + 23 * minute, state.nextAlertAt)
        assertEquals(0.4f, state.remainingFraction, 0.0001f)
    }

    @Test
    fun `an expired snooze is ignored`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
            .copy(snoozeUntil = t0 + 23 * minute, nextTriggerAt = t0 + 40 * minute)
        val state = BreakTimer.stateOf(running, t0 + 24 * minute, snoozeMinutes = 5)

        assertFalse(state.isSnoozed)
        assertEquals(t0 + 40 * minute, state.nextAlertAt)
    }

    @Test
    fun `outside working hours the screen waits rather than counting down for hours`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
            .copy(windows = listOf(TimeWindow()), nextTriggerAt = t0 + 10 * 60 * minute)
        val state = BreakTimer.stateOf(running, t0, snoozeMinutes = 5)

        assertTrue(state.waitingForWorkingHours)
        assertTrue(state.hasWorkingHours)
        assertEquals(0f, state.remainingFraction)
    }

    @Test
    fun `a paused timer shows its full interval and no alarm`() {
        val paused = BreakTimer.pause(BreakTimer.start(BreakTimer.defaultReminder(), t0).armed(t0), t0 + minute)
        val state = BreakTimer.stateOf(paused, t0 + 2 * minute, snoozeMinutes = 5)

        assertEquals(BreakPhase.PAUSED, state.phase)
        assertEquals(20 * minute, state.remainingMillis)
        assertEquals(0L, state.nextAlertAt)
    }

    @Test
    fun `minutes and seconds are zero-padded and minutes grow past an hour`() {
        assertEquals("00:00", BreakTimer.formatMinutesSeconds(0L))
        assertEquals("00:00", BreakTimer.formatMinutesSeconds(-5_000L))
        assertEquals("00:01", BreakTimer.formatMinutesSeconds(1L))
        assertEquals("01:00", BreakTimer.formatMinutesSeconds(59_001L))
        assertEquals("20:00", BreakTimer.formatMinutesSeconds(20 * minute))
        assertEquals("90:00", BreakTimer.formatMinutesSeconds(90 * minute))
    }

    // --- Persistence and process recreation --------------------------------------------------

    @Test
    fun `break state survives a save and reload`() {
        val paused = BreakTimer.pause(BreakTimer.withInterval(BreakTimer.defaultReminder(), 25, t0), t0 + minute)
        val running = BreakTimer.start(BreakTimer.defaultReminder(id = 2), t0).armed(t0)
            .copy(windows = listOf(TimeWindow()), snoozeUntil = t0 + 22 * minute)

        val reloaded = ReminderCodec.decode(ReminderCodec.encode(listOf(paused, running)), t0)

        assertEquals(listOf(paused, running), reloaded)
        assertEquals(BreakPhase.PAUSED, BreakTimer.phaseOf(reloaded[0]))
    }

    @Test
    fun `data saved before pause existed loads as not paused`() {
        val stored = BreakTimer.pause(BreakTimer.defaultReminder(), t0)
        val json = ReminderCodec.encode(listOf(stored))
            .replace(Regex(""","pausedAtMillis":\d+|"pausedAtMillis":\d+,"""), "")
        assertFalse(json.contains("pausedAtMillis"))

        val reloaded = ReminderCodec.decode(json, t0).single()

        assertEquals(0L, reloaded.pausedAtMillis)
        assertEquals(BreakPhase.IDLE, BreakTimer.phaseOf(reloaded))
    }

    @Test
    fun `a recreated process arms the same alarm, wherever it picks up`() {
        val stored = ReminderCodec.decode(
            ReminderCodec.encode(listOf(BreakTimer.start(BreakTimer.defaultReminder(), t0))),
            t0,
        ).single()

        // Killed and restarted at any moment before 10:20, the next alarm is still 10:20.
        listOf(0L, 1L, 5 * minute, 19 * minute + 59_999L).forEach { offset ->
            assertEquals(t0 + 20 * minute, stored.nextAlert(t0 + offset))
        }
    }

    @Test
    fun `re-arming twice gives one alarm, not two`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)
        val once = running.armed(t0 + minute)
        val twice = once.armed(t0 + 2 * minute)

        assertEquals(once.nextTriggerAt, twice.nextTriggerAt)
    }

    // --- Time zones, DST and working hours --------------------------------------------------

    @Test
    fun `a time zone change does not move a break without working hours`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)

        val berlin = running.nextAlert(t0 + minute, ZoneId.of("Europe/Berlin"))
        val tokyo = running.nextAlert(t0 + minute, ZoneId.of("Asia/Tokyo"))
        val newYork = running.nextAlert(t0 + minute, ZoneId.of("America/New_York"))

        assertEquals(berlin, tokyo)
        assertEquals(berlin, newYork)
    }

    @Test
    fun `after the clock is set back the next break is still at most one interval away`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0)

        // Corrected back by 7 min, then by 25 min: the grid continues backwards from the start.
        assertEquals(t0, running.nextAlert(t0 - 7 * minute))
        assertEquals(t0 - 20 * minute, running.nextAlert(t0 - 25 * minute))
    }

    @Test
    fun `a pinned start moment still comes first, however far ahead`() {
        val pinned = BreakTimer.defaultReminder().copy(enabled = true, startAtMillis = t0 + 300 * minute)

        assertEquals(t0 + 300 * minute, pinned.nextAlert(t0))
    }

    @Test
    fun `without working hours a distant alarm is never shown as waiting for them`() {
        val running = BreakTimer.start(BreakTimer.defaultReminder(), t0).copy(nextTriggerAt = t0 + 33 * minute)
        val state = BreakTimer.stateOf(running, t0, snoozeMinutes = 5)

        assertFalse(state.waitingForWorkingHours)
        assertEquals("33:00", BreakTimer.formatMinutesSeconds(state.remainingMillis))
    }

    @Test
    fun `breaks stay 20 minutes apart across the spring DST change`() {
        // Berlin skips 02:00–03:00 on 29 March 2026.
        val start = at(2026, 3, 29, 1, 30)
        val running = BreakTimer.start(BreakTimer.defaultReminder(), start)

        var from = start
        val alerts = List(6) { running.nextAlert(from)!!.also { from = it } }

        assertEquals((1..6).map { start + it * 20 * minute }, alerts)
        // 01:30 + 40 min of real time is 03:10 on the wall clock.
        assertEquals(at(2026, 3, 29, 3, 10), alerts[1])
    }

    @Test
    fun `breaks stay 20 minutes apart across the autumn DST change`() {
        // Berlin repeats 02:00–03:00 on 25 October 2026.
        val start = Instant.parse("2026-10-24T23:30:00Z").toEpochMilli()
        val running = BreakTimer.start(BreakTimer.defaultReminder(), start)

        var from = start
        val alerts = List(8) { running.nextAlert(from)!!.also { from = it } }

        assertEquals((1..8).map { start + it * 20 * minute }, alerts)
    }

    @Test
    fun `working hours filter the grid without restarting it`() {
        // Weekdays 09:00–17:00; started Friday 16:50, so 17:10 is closed.
        val start = at(2026, 9, 25, 16, 50)
        val running = BreakTimer.withWorkingHours(BreakTimer.start(BreakTimer.defaultReminder(), start), TimeWindow())

        val next = running.nextAlert(start)

        assertNotNull(next)
        // Monday morning, still on the 20-minute grid from Friday 16:50.
        assertEquals(at(2026, 9, 28, 9, 10), next)
        assertEquals(0L, (next!! - start) % (20 * minute))
        assertTrue(running.isWithinWindowsAt(next, zone))
    }

    @Test
    fun `working hours are read on the local clock after a time zone change`() {
        val start = at(2026, 9, 22, 10, 0)
        val running = BreakTimer.withWorkingHours(BreakTimer.start(BreakTimer.defaultReminder(), start), TimeWindow())
        val newYork = ZoneId.of("America/New_York")

        // 10:20 in Berlin is 04:20 in New York, before its working day starts.
        val next = running.nextAlert(start, newYork)!!

        assertTrue(running.isWithinWindowsAt(next, newYork))
        assertEquals(0L, (next - start) % (20 * minute))
        assertTrue(next > start + 20 * minute)
    }

    @Test
    fun `clearing working hours lets breaks ring at any time again`() {
        val withHours = BreakTimer.withWorkingHours(BreakTimer.defaultReminder(), TimeWindow())
        val cleared = BreakTimer.withWorkingHours(withHours, null)

        assertTrue(cleared.windows.isEmpty())
    }
}
