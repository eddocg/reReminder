package com.olaf.rereminder.ui.settings

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olaf.rereminder.R
import com.olaf.rereminder.data.TimeWindow
import com.olaf.rereminder.ui.components.CollapsingHeader
import com.olaf.rereminder.ui.components.SectionCard
import com.olaf.rereminder.ui.components.SectionDivider
import com.olaf.rereminder.ui.components.SectionItem
import com.olaf.rereminder.ui.components.SectionSwitchRow
import com.olaf.rereminder.ui.components.rememberCollapseProgress
import com.olaf.rereminder.ui.editor.WindowDialog
import com.olaf.rereminder.ui.format.windowLabel
import com.olaf.rereminder.ui.theme.Motion
import com.olaf.rereminder.ui.theme.ReReminderTheme
import com.olaf.rereminder.ui.theme.tap
import com.olaf.rereminder.ui.theme.tappable
import com.olaf.rereminder.utils.AlarmBlocker
import com.olaf.rereminder.utils.AlertPlayer
import com.olaf.rereminder.utils.AlertSound
import com.olaf.rereminder.utils.GentleSound
import com.olaf.rereminder.utils.PreferenceHelper
import com.olaf.rereminder.utils.Reliability
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The user may have just changed a permission or the alarm volume in the system UI.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshSystemStatus()
        onPauseOrDispose { }
    }

    SettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onGentleSound = viewModel::setGentleSound,
        onAlarmVolume = viewModel::setAlarmVolumePercent,
        onSoundTimeout = viewModel::setSoundTimeoutSeconds,
        onSnoozeChange = viewModel::setSnoozeMinutes,
        onVibrationEnabledChange = viewModel::setVibrationEnabled,
        onVibrationPatternChange = viewModel::setVibrationPattern,
        onWorkingHours = viewModel::setWorkingHours,
        // Each lands on this app's own switch rather than a list of every installed app.
        onFix = { blocker -> context.openFirstAvailable(Reliability.intentsFor(context, blocker)) },
        onFixBattery = { context.openFirstAvailable(Reliability.batteryIntents(context)) },
        onOpenDkma = { context.startActivitySafely(Intent(Intent.ACTION_VIEW, uiState.guideUrl.toUri())) },
        onOpenSource = { context.startActivitySafely(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri())) },
    )
}

/** Where the corresponding source of this build lives, as the GPL asks. */
private const val SOURCE_URL = "https://github.com/eddocg/reReminder"

/** Not every OEM ships the system screens these intents point at. */
private fun Context.startActivitySafely(intent: Intent) {
    runCatching { startActivity(intent) }
        .onFailure {
            Toast.makeText(this, R.string.reliability_screen_missing, Toast.LENGTH_SHORT).show()
        }
}

/** Walks the intent cascade, and says so when the device offers none of them. */
private fun Context.openFirstAvailable(intents: List<Intent>) {
    if (!Reliability.startFirstAvailable(this, intents)) {
        Toast.makeText(this, R.string.reliability_screen_missing, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onGentleSound: (GentleSound) -> Unit,
    onAlarmVolume: (Int) -> Unit,
    onSoundTimeout: (Int) -> Unit,
    onSnoozeChange: (Int) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
    onVibrationPatternChange: (Int) -> Unit,
    onWorkingHours: (TimeWindow?) -> Unit,
    onFix: (AlarmBlocker) -> Unit,
    onFixBattery: () -> Unit,
    onOpenDkma: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scrollState = rememberScrollState()
    val collapseProgress by rememberCollapseProgress(scrollState)

    var showVibrationDialog by rememberSaveable { mutableStateOf(false) }
    var showSnoozeDialog by rememberSaveable { mutableStateOf(false) }
    var showTimeoutDialog by rememberSaveable { mutableStateOf(false) }
    var showWindowDialog by rememberSaveable { mutableStateOf(false) }

    // One preview at a time, at the volume an alarm would really use; it stops with the screen.
    var previewing by remember { mutableStateOf<GentleSound?>(null) }
    var player by remember { mutableStateOf<AlertPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.stop() } }

    fun stopPreview() {
        player?.stop()
        player = null
        previewing = null
    }

    fun preview(sound: GentleSound, volumePercent: Int) {
        stopPreview()
        val next = AlertPlayer(
            context = context,
            usage = AudioAttributes.USAGE_ALARM,
            loop = false,
            maxMillis = PREVIEW_MILLIS,
            gain = volumePercent / 100f,
        )
        player = next
        previewing = sound
        next.play(AlertSound.Tone(sound.uri(context)), vibration = null) {
            if (player === next) {
                player = null
                previewing = null
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CollapsingHeader(
                title = stringResource(R.string.settings_title),
                progress = collapseProgress,
                navigationIcon = {
                    IconButton(onClick = { view.tap(); stopPreview(); onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Padding inside the scroll, so the content passes behind the collapsed header
                // instead of being clipped at the height the expanded one reserves.
                .verticalScroll(scrollState)
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_group_alarm)) {
                GentleSound.entries.forEach { sound ->
                    SoundRow(
                        sound = sound,
                        selected = uiState.gentleSound == sound,
                        playing = previewing == sound,
                        onSelect = { onGentleSound(sound) },
                        onPreview = {
                            if (previewing == sound) stopPreview() else preview(sound, uiState.alarmVolumePercent)
                        },
                    )
                }
                SectionDivider()
                VolumeRow(
                    percent = uiState.alarmVolumePercent,
                    onChange = onAlarmVolume,
                    onChangeFinished = { percent -> preview(uiState.gentleSound, percent) },
                )
                SectionDivider()
                SectionItem(
                    title = stringResource(R.string.sound_timeout_title),
                    subtitle = soundTimeoutLabel(uiState.soundTimeoutSeconds),
                    onClick = { showTimeoutDialog = true },
                )
                SectionItem(
                    title = stringResource(R.string.snooze_title),
                    subtitle = stringResource(R.string.snooze_minutes, uiState.snoozeMinutes),
                    onClick = { showSnoozeDialog = true },
                )
                SectionDivider()
                SectionSwitchRow(
                    title = stringResource(R.string.vibration_enabled_label),
                    checked = uiState.vibrationEnabled,
                    onCheckedChange = onVibrationEnabledChange,
                )
                SectionItem(
                    title = stringResource(R.string.vibration_pattern_title),
                    subtitle = stringResource(vibrationPatternLabel(uiState.vibrationPattern)),
                    onClick = { showVibrationDialog = true },
                    enabled = uiState.vibrationEnabled,
                )
            }

            Text(
                text = stringResource(R.string.settings_alarm_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            SectionCard(title = stringResource(R.string.settings_group_working_hours)) {
                SectionSwitchRow(
                    title = stringResource(R.string.working_hours_switch),
                    subtitle = stringResource(
                        if (uiState.workingHours != null) R.string.working_hours_on_hint else R.string.working_hours_off_hint
                    ),
                    checked = uiState.workingHours != null,
                    onCheckedChange = { on -> onWorkingHours(if (on) TimeWindow() else null) },
                )
                AnimatedVisibility(
                    visible = uiState.workingHours != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    uiState.workingHours?.let { window ->
                        SectionItem(
                            title = stringResource(R.string.working_hours_edit),
                            subtitle = windowLabel(window),
                            onClick = { showWindowDialog = true },
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.settings_group_reliability)) {
                val readiness = uiState.readiness
                StatusItem(
                    title = stringResource(R.string.reliability_notifications),
                    ok = readiness.notificationsAllowed,
                    subtitle = stringResource(
                        if (readiness.notificationsAllowed) R.string.reliability_notifications_on else R.string.reliability_notifications_off
                    ),
                    onClick = { onFix(AlarmBlocker.NOTIFICATIONS) },
                )
                SectionDivider()
                StatusItem(
                    title = stringResource(R.string.reliability_exact_alarms),
                    ok = readiness.exactAlarmsAllowed,
                    subtitle = stringResource(
                        if (readiness.exactAlarmsAllowed) R.string.reliability_exact_alarms_on else R.string.reliability_exact_alarms_off
                    ),
                    onClick = { onFix(AlarmBlocker.EXACT_ALARMS) },
                )
                SectionDivider()
                StatusItem(
                    title = stringResource(R.string.reliability_full_screen),
                    ok = readiness.fullScreenAllowed,
                    subtitle = stringResource(
                        if (readiness.fullScreenAllowed) R.string.reliability_full_screen_on else R.string.reliability_full_screen_off
                    ),
                    onClick = { onFix(AlarmBlocker.FULL_SCREEN) },
                )
                SectionDivider()
                StatusItem(
                    title = stringResource(R.string.reliability_alarm_volume),
                    ok = readiness.alarmVolumeAudible,
                    subtitle = stringResource(
                        if (readiness.alarmVolumeAudible) R.string.reliability_alarm_volume_on else R.string.reliability_alarm_volume_off
                    ),
                    onClick = { onFix(AlarmBlocker.ALARM_VOLUME) },
                )
                SectionDivider()
                StatusItem(
                    title = stringResource(R.string.reliability_battery),
                    ok = readiness.batteryUnrestricted,
                    subtitle = stringResource(
                        if (readiness.batteryUnrestricted) R.string.reliability_battery_on else R.string.reliability_battery_off
                    ),
                    onClick = onFixBattery,
                )
                SectionDivider()
                SectionItem(
                    title = stringResource(R.string.reliability_manufacturer),
                    // Naming the vendor tells the user the link is about their phone, not a
                    // generic disclaimer — and it is where the link actually goes.
                    subtitle = if (uiState.vendorName.isBlank()) {
                        stringResource(R.string.reliability_manufacturer_summary)
                    } else {
                        stringResource(R.string.reliability_manufacturer_summary_vendor, uiState.vendorName)
                    },
                    onClick = onOpenDkma,
                )
            }

            Text(
                text = stringResource(R.string.reliability_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            SectionCard(title = stringResource(R.string.settings_group_about)) {
                SectionItem(
                    title = stringResource(R.string.about_title, appVersion(context)),
                    subtitle = stringResource(R.string.about_body),
                    onClick = onOpenSource,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showVibrationDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.vibration_pattern_title),
            options = VibrationPatterns.map { stringResource(it) },
            selectedIndex = uiState.vibrationPattern,
            onSelected = {
                onVibrationPatternChange(it)
                showVibrationDialog = false
            },
            onDismiss = { showVibrationDialog = false },
        )
    }

    if (showSnoozeDialog) {
        val choices = PreferenceHelper.SNOOZE_CHOICES
        SingleChoiceDialog(
            title = stringResource(R.string.snooze_title),
            options = choices.map { stringResource(R.string.snooze_minutes, it) },
            selectedIndex = choices.indexOf(uiState.snoozeMinutes).coerceAtLeast(0),
            onSelected = {
                onSnoozeChange(choices[it])
                showSnoozeDialog = false
            },
            onDismiss = { showSnoozeDialog = false },
        )
    }

    if (showTimeoutDialog) {
        val choices = PreferenceHelper.SOUND_TIMEOUT_CHOICES
        SingleChoiceDialog(
            title = stringResource(R.string.sound_timeout_title),
            options = choices.map { soundTimeoutLabel(it) },
            selectedIndex = choices.indexOf(uiState.soundTimeoutSeconds).coerceAtLeast(0),
            onSelected = {
                onSoundTimeout(choices[it])
                showTimeoutDialog = false
            },
            onDismiss = { showTimeoutDialog = false },
            footer = stringResource(R.string.sound_timeout_hint),
        )
    }

    if (showWindowDialog) {
        uiState.workingHours?.let { window ->
            WindowDialog(
                initial = window,
                accent = MaterialTheme.colorScheme.primary,
                onDismiss = { showWindowDialog = false },
                onConfirm = {
                    onWorkingHours(it)
                    showWindowDialog = false
                },
            )
        }
    }
}

private const val PREVIEW_MILLIS = 6_000L

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull()
        .orEmpty()

@Composable
private fun soundTimeoutLabel(seconds: Int): String =
    if (seconds < 60) {
        stringResource(R.string.duration_seconds, seconds)
    } else {
        stringResource(R.string.duration_minutes, seconds / 60)
    }

/** One bundled sound: tap the row to pick it, the button to hear it. */
@Composable
private fun SoundRow(
    sound: GentleSound,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    val name = stringResource(sound.labelRes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tappable(pressedScale = 0.99f, onClick = onSelect)
            .semantics { role = Role.RadioButton }
            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val previewLabel = stringResource(if (playing) R.string.sound_preview_stop_named else R.string.sound_preview_named, name)
        IconButton(onClick = onPreview, modifier = Modifier.semantics { contentDescription = previewLabel }) {
            Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
        }
    }
}

@Composable
private fun VolumeRow(percent: Int, onChange: (Int) -> Unit, onChangeFinished: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.alarm_volume_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.alarm_volume_value, value.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                val rounded = value.roundToInt()
                onChange(rounded)
                onChangeFinished(rounded)
            },
            valueRange = PreferenceHelper.MIN_ALARM_VOLUME.toFloat()..100f,
            // 10, 20, … 100.
            steps = 8,
        )
        Text(
            text = stringResource(R.string.alarm_volume_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val VibrationPatterns = listOf(
    R.string.vibration_pattern_short,
    R.string.vibration_pattern_default,
    R.string.vibration_pattern_long,
    R.string.vibration_pattern_pulsating,
)

private fun vibrationPatternLabel(pattern: Int): Int =
    VibrationPatterns.getOrElse(pattern) { R.string.vibration_pattern_default }

/** A settings row that also reports whether the system condition behind it is healthy. */
@Composable
private fun StatusItem(
    title: String,
    subtitle: String,
    ok: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (ok) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        animationSpec = Motion.fade(),
        label = "statusTint",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tappable(pressedScale = 0.99f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixing a restriction in system settings and coming back should visibly land.
        AnimatedContent(
            targetState = ok,
            transitionSpec = {
                (scaleIn(Motion.expressive(), initialScale = 0.4f) + fadeIn())
                    .togetherWith(scaleOut(Motion.snappy(), targetScale = 0.4f) + fadeOut())
            },
            label = "statusIcon",
        ) { healthy ->
            Icon(
                imageVector = if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                label = "statusSubtitle",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else tint,
                )
            }
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tappable(pressedScale = 0.98f) { onSelected(index) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedIndex == index,
                            onClick = { onSelected(index) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (footer != null) {
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    ReReminderTheme {
        SettingsScreen(
            uiState = SettingsUiState(workingHours = TimeWindow()),
            onBack = {},
            onGentleSound = {},
            onAlarmVolume = {},
            onSoundTimeout = {},
            onSnoozeChange = {},
            onVibrationEnabledChange = {},
            onVibrationPatternChange = {},
            onWorkingHours = {},
            onFix = {},
            onFixBattery = {},
            onOpenDkma = {},
            onOpenSource = {},
        )
    }
}
