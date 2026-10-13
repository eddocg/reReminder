package com.olaf.rereminder.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.olaf.rereminder.R
import com.olaf.rereminder.data.MessageTemplate
import com.olaf.rereminder.data.Reminder
import com.olaf.rereminder.data.ReminderRepository
import com.olaf.rereminder.data.displayName
import com.olaf.rereminder.ui.alarm.AlarmActivity
import com.olaf.rereminder.utils.AlertMode
import com.olaf.rereminder.utils.AlertPlayer
import com.olaf.rereminder.utils.AlertSound
import com.olaf.rereminder.utils.NotificationHelper
import com.olaf.rereminder.utils.PreferenceHelper
import com.olaf.rereminder.utils.SoundChannels
import com.olaf.rereminder.utils.VibrationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The alarm on screen right now. */
data class RingingAlarm(
    val reminderId: Int,
    val title: String,
    val message: String,
    val eventAt: Long,
    val colorIndex: Int,
    val snoozeMinutes: Int,
    /** The next regular alarm, already armed when this one started ringing; 0 when none. */
    val nextAt: Long = 0L,
    /** The sound timed out; the alarm waits quietly for Dismiss or Snooze. */
    val silenced: Boolean = false,
)

/**
 * Rings an alarm-style reminder (#10): full screen over the lock screen, sound and vibration on a
 * loop until it is dismissed or snoozed.
 *
 * A foreground service, because an alarm has to outlive the broadcast that started it. Its type is
 * `systemExempted`, which Android 14 reserves for apps that hold the exact-alarm permission and
 * use the service to keep an alarm going — exactly this.
 *
 * Nobody around to stop it? After the sound timeout from Settings it goes quiet but stays up, so
 * Dismiss and Snooze are still there when the user comes back. Left alone for
 * [SILENCED_HOLD_MILLIS] after that, it closes with a "missed" note. A second alarm arriving
 * while one is up takes over; the first one becomes "missed" too.
 */
class AlarmService : Service() {

    private var player: AlertPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRinging(
                reminderId = intent.getIntExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1),
                eventAt = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_AT, System.currentTimeMillis()),
            )

            ACTION_DISMISS -> {
                // Dismiss ends this occurrence for good; the regular grid carries on regardless.
                _ringing.value?.let { ReminderScheduler(this).clearSnooze(it.reminderId) }
                stopRinging()
            }

            ACTION_SNOOZE -> {
                _ringing.value?.let { ReminderScheduler(this).snooze(it.reminderId, it.snoozeMinutes) }
                stopRinging()
            }

            // Restarted by the system with no intent: there is nothing left to ring.
            else -> stopRinging()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(reminderId: Int, eventAt: Long) {
        val reminder = ReminderRepository.get(this).get(reminderId)
        val preferences = PreferenceHelper(this)
        val alarm = RingingAlarm(
            reminderId = reminderId,
            title = reminder?.displayName(this) ?: getString(R.string.reminder_default_name),
            message = reminder?.let { MessageTemplate.render(this, it.message, it) }.orEmpty(),
            eventAt = eventAt,
            colorIndex = reminder?.colorIndex ?: 0,
            snoozeMinutes = preferences.getSnoozeMinutes(),
            nextAt = reminder?.nextTriggerAt ?: 0L,
        )

        // startForegroundService() obliges us to go foreground promptly, whatever happens next.
        val foreground = runCatching {
            NotificationHelper.createNotificationChannels(this)
            val notification = buildNotification(alarm)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.ALARM_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(NotificationHelper.ALARM_NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.e(TAG, "Could not ring as an alarm", it) }.isSuccess

        if (!foreground || reminder == null) {
            // Without the foreground service, a plain notification is the honest fallback — on
            // the reminder's sound channel, not the silent one, so the alarm is still heard.
            reminder?.let {
                val plan = SoundChannels.plan(it, preferences, early = false)
                val channelId = SoundChannels.channelFor(this, plan.tone, plan.vibration)
                NotificationHelper.showReminderNotification(this, it, alarm.message, eventAt, 0, channelId)
            }
            stopRinging()
            return
        }

        // Taking over from an alarm nobody answered: that one counts as missed.
        _ringing.value?.let { previous -> markMissed(previous) }
        handler.removeCallbacksAndMessages(null)
        val previousPlayer = player
        player = null
        previousPlayer?.stop()

        val soundMillis = preferences.getSoundTimeoutSeconds() * 1_000L
        _ringing.value = alarm
        acquireWakeLock(soundMillis + WAKE_LOCK_MARGIN_MILLIS)
        play(reminder, alarm, preferences, soundMillis)
    }

    private fun play(reminder: Reminder, alarm: RingingAlarm, preferences: PreferenceHelper, soundMillis: Long) {
        val spoken = listOf(alarm.title, alarm.message).filter { it.isNotBlank() }.joinToString(". ")
        // The top-bar mode wins here too: a muted phone gets a silent alarm screen.
        val mode = preferences.getAlertMode()
        val sound = if (mode == AlertMode.SOUND) {
            AlertPlayer.resolveSound(reminder, preferences, alarm = true, spokenText = spoken)
        } else {
            AlertSound.Silent
        }
        val vibrate = when (mode) {
            AlertMode.SOUND -> reminder.vibrationEnabled && preferences.isVibrationEnabled()
            AlertMode.VIBRATE -> true
            AlertMode.MUTE -> false
        }

        val newPlayer = AlertPlayer(
            context = this,
            usage = AudioAttributes.USAGE_ALARM,
            loop = true,
            maxMillis = soundMillis,
            gain = preferences.getAlarmVolumePercent() / 100f,
            fadeInMillis = FADE_IN_MILLIS,
        )
        player = newPlayer
        newPlayer.play(
            sound = sound,
            vibration = if (vibrate) VibrationHelper.alarmPattern(preferences.getVibrationPattern()) else null,
        ) {
            // Finishing on its own means the sound timeout ran out with nobody there.
            if (player === newPlayer && _ringing.value == alarm) silence(alarm)
        }
    }

    /**
     * Stops the sound and vibration but keeps the alarm up, so it can still be dismissed or
     * snoozed. Nothing needs the CPU while it waits, so the wake lock goes too.
     */
    private fun silence(alarm: RingingAlarm) {
        player = null
        val quiet = alarm.copy(silenced = true)
        _ringing.value = quiet
        releaseWakeLock()
        try {
            NotificationManagerCompat.from(this).notify(NotificationHelper.ALARM_NOTIFICATION_ID, buildNotification(quiet))
        } catch (e: SecurityException) {
            // Notifications were revoked mid-alarm; the alarm screen still offers both actions.
            Log.w(TAG, "Could not update the alarm notification", e)
        }
        // Uptime-based, so a sleeping phone may close it a little later; the next alarm takes
        // over in any case.
        handler.postDelayed({
            if (_ringing.value == quiet) {
                markMissed(quiet)
                stopRinging()
            }
        }, SILENCED_HOLD_MILLIS)
    }

    private fun markMissed(alarm: RingingAlarm) {
        ReminderRepository.get(this).get(alarm.reminderId)?.let {
            NotificationHelper.showMissedNotification(this, it, alarm.eventAt)
        }
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        val current = player
        player = null
        _ringing.value = null
        current?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
        _ringing.value = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(alarm: RingingAlarm) =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ALARMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alarm.title)
            .setContentText(
                when {
                    alarm.silenced -> getString(R.string.alarm_silenced)
                    else -> alarm.message.ifBlank { getString(R.string.alarm_ringing) }
                }
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(alarm.eventAt)
            .setShowWhen(true)
            .setContentIntent(activityIntent())
            .setFullScreenIntent(activityIntent(), true)
            .addAction(
                0,
                getString(R.string.alarm_snooze_minutes, alarm.snoozeMinutes),
                serviceIntent(this, ACTION_SNOOZE),
            )
            .addAction(0, getString(R.string.alarm_dismiss), serviceIntent(this, ACTION_DISMISS))
            .build()

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, AlarmActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireWakeLock(timeoutMillis: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire(timeoutMillis) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val WAKE_LOCK_TAG = "reReminder:alarm"
        private const val WAKE_LOCK_MARGIN_MILLIS = 30_000L
        private const val FADE_IN_MILLIS = 8_000L
        const val SILENCED_HOLD_MILLIS = 30 * 60_000L

        private const val ACTION_START = "com.olaf.rereminder.action.ALARM_START"
        const val ACTION_DISMISS = "com.olaf.rereminder.action.ALARM_DISMISS"
        const val ACTION_SNOOZE = "com.olaf.rereminder.action.ALARM_SNOOZE"

        private val _ringing = MutableStateFlow<RingingAlarm?>(null)

        /** What the alarm screen shows; null once the alarm has been dealt with. */
        val ringing: StateFlow<RingingAlarm?> = _ringing.asStateFlow()

        /** Returns false when Android would not let the service start; the caller then notifies. */
        fun start(context: Context, reminderId: Int, eventAt: Long): Boolean {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
                putExtra(ReminderScheduler.EXTRA_EVENT_AT, eventAt)
            }
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Alarm service not allowed to start", it) }
                .isSuccess
        }

        fun serviceIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** For the alarm screen's buttons; the service is already in the foreground then. */
        fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, AlarmService::class.java).setAction(action))
            }.onFailure { Log.w(TAG, "Could not reach the alarm service", it) }
        }
    }
}
