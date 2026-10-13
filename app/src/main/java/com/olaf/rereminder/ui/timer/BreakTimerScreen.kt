package com.olaf.rereminder.ui.timer

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olaf.rereminder.R
import com.olaf.rereminder.data.BreakPhase
import com.olaf.rereminder.data.BreakTimer
import com.olaf.rereminder.data.BreakTimerState
import com.olaf.rereminder.data.TimeWindow
import com.olaf.rereminder.ui.format.intervalLabel
import com.olaf.rereminder.ui.format.windowLabel
import com.olaf.rereminder.ui.theme.ReReminderTheme
import com.olaf.rereminder.ui.theme.tap
import com.olaf.rereminder.utils.AlarmBlocker
import com.olaf.rereminder.utils.AlarmReadiness
import com.olaf.rereminder.utils.Reliability
import com.olaf.rereminder.utils.TimeLabels

@Composable
fun BreakTimerRoute(
    onSettings: () -> Unit,
    viewModel: BreakTimerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    BreakTimerScreen(
        uiState = uiState,
        onStart = viewModel::start,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onStop = viewModel::stop,
        onInterval = viewModel::setInterval,
        onSettings = onSettings,
        onFix = { blocker ->
            if (!Reliability.startFirstAvailable(context, Reliability.intentsFor(context, blocker))) {
                Toast.makeText(context, R.string.reliability_screen_missing, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

/**
 * The whole app at a glance: one big ring counting down to the next break, and the controls for
 * it. Everything else lives one tap away in Settings.
 */
@Composable
fun BreakTimerScreen(
    uiState: BreakUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onInterval: (Int) -> Unit,
    onSettings: () -> Unit,
    onFix: (AlarmBlocker) -> Unit,
) {
    val view = LocalView.current
    val timer = uiState.timer
    var showIntervalDialog by rememberSaveable { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(onClick = { view.tap(); onSettings() }) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_button_label))
                }
            }

            AnimatedVisibility(
                visible = uiState.loaded && !uiState.readiness.isReady,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ReadinessCard(uiState.readiness, onFix, Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(24.dp))

            CountdownRing(
                timer = timer,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.82f),
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = statusLine(timer),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            AssistChip(
                onClick = { view.tap(); showIntervalDialog = true },
                label = { Text(stringResource(R.string.break_every, intervalLabel(timer.intervalMinutes))) },
                leadingIcon = { Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )

            Spacer(Modifier.height(28.dp))

            Controls(
                phase = timer.phase,
                onStart = { view.tap(); onStart() },
                onPause = { view.tap(); onPause() },
                onResume = { view.tap(); onResume() },
                onStop = { view.tap(); onStop() },
            )

            Spacer(Modifier.height(20.dp))

            WorkingHoursLine(uiState.workingHours, onClick = { view.tap(); onSettings() })
        }
    }

    if (showIntervalDialog) {
        IntervalDialog(
            initialMinutes = timer.intervalMinutes,
            running = timer.phase == BreakPhase.RUNNING,
            onDismiss = { showIntervalDialog = false },
            onConfirm = { minutes ->
                onInterval(minutes)
                showIntervalDialog = false
            },
        )
    }
}

@Composable
private fun CountdownRing(timer: BreakTimerState, modifier: Modifier = Modifier) {
    val running = timer.phase == BreakPhase.RUNNING
    // Eased between the quarter-second ticks so the arc glides instead of stepping.
    val sweep by animateFloatAsState(
        targetValue = timer.remainingFraction,
        animationSpec = tween(durationMillis = 400),
        label = "ringSweep",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.primary.copy(alpha = if (running) 1f else 0.38f)

    val center = when {
        timer.waitingForWorkingHours -> TimeLabels.clock(timer.nextAlertAt)
        else -> BreakTimer.formatMinutesSeconds(timer.remainingMillis)
    }
    val caption = stringResource(
        when {
            timer.phase == BreakPhase.PAUSED -> R.string.break_state_paused
            timer.phase == BreakPhase.IDLE -> R.string.break_state_ready
            timer.waitingForWorkingHours -> R.string.break_state_next_break
            timer.isSnoozed -> R.string.break_state_snoozed
            else -> R.string.break_state_until_break
        }
    )
    val totalSeconds = BreakTimer.ceilToSecond(timer.remainingMillis) / 1_000L
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    val spoken = if (timer.waitingForWorkingHours) {
        "$caption $center"
    } else {
        stringResource(
            R.string.break_countdown_a11y,
            caption,
            pluralStringResource(R.plurals.countdown_minutes, minutes, minutes),
            pluralStringResource(R.plurals.countdown_seconds, seconds, seconds),
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.055f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (sweep > 0f) {
                drawArc(
                    color = arc,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = center,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    // Equal-width digits, so the time doesn't jiggle as it counts down.
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (running) 1f else 0.7f),
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusLine(timer: BreakTimerState): String = when (timer.phase) {
    BreakPhase.IDLE -> stringResource(R.string.break_status_idle, intervalLabel(timer.intervalMinutes))
    BreakPhase.PAUSED -> stringResource(R.string.break_status_paused, intervalLabel(timer.intervalMinutes))
    BreakPhase.RUNNING -> when {
        timer.nextAlertAt <= 0L -> stringResource(R.string.break_status_not_scheduled)
        timer.isSnoozed -> stringResource(R.string.break_status_snoozed, TimeLabels.clock(timer.nextAlertAt))
        timer.waitingForWorkingHours -> stringResource(
            R.string.break_status_outside_hours,
            TimeLabels.moment(LocalContext.current, timer.nextAlertAt),
        )
        else -> stringResource(R.string.break_status_running, TimeLabels.clock(timer.nextAlertAt))
    }
}

@Composable
private fun Controls(
    phase: BreakPhase,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val buttonHeight = Modifier.height(56.dp)
    when (phase) {
        BreakPhase.IDLE -> Button(onClick = onStart, modifier = buttonHeight.fillMaxWidth(0.7f)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.break_start), style = MaterialTheme.typography.titleMedium)
        }

        BreakPhase.RUNNING, BreakPhase.PAUSED -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (phase == BreakPhase.RUNNING) {
                FilledTonalButton(onClick = onPause, modifier = buttonHeight.weight(1f)) {
                    Icon(Icons.Rounded.Pause, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.break_pause), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(onClick = onResume, modifier = buttonHeight.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.break_resume), style = MaterialTheme.typography.titleMedium)
                }
            }
            OutlinedButton(onClick = onStop, modifier = buttonHeight.weight(1f)) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.break_stop), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun WorkingHoursLine(window: TimeWindow?, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            text = if (window == null) {
                stringResource(R.string.working_hours_any_time)
            } else {
                stringResource(R.string.working_hours_summary, windowLabel(window))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReadinessCard(readiness: AlarmReadiness, onFix: (AlarmBlocker) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.readiness_title), style = MaterialTheme.typography.titleSmall)
            }
            readiness.blockers.forEach { blocker ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(blocker.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onFix(blocker) }) { Text(stringResource(R.string.readiness_fix)) }
                }
            }
        }
    }
}

fun AlarmBlocker.labelRes(): Int = when (this) {
    AlarmBlocker.NOTIFICATIONS -> R.string.readiness_notifications
    AlarmBlocker.EXACT_ALARMS -> R.string.readiness_exact_alarms
    AlarmBlocker.FULL_SCREEN -> R.string.readiness_full_screen
    AlarmBlocker.ALARM_VOLUME -> R.string.readiness_alarm_volume
}

@Composable
private fun IntervalDialog(
    initialMinutes: Int,
    running: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val view = LocalView.current
    var minutes by rememberSaveable { mutableIntStateOf(initialMinutes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.interval_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { view.tap(); minutes = BreakTimer.clampInterval(minutes - 1) },
                        enabled = minutes > BreakTimer.MIN_INTERVAL_MINUTES,
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.interval_decrease))
                    }
                    Text(
                        text = intervalLabel(minutes),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(min = 140.dp)
                            .padding(horizontal = 12.dp),
                    )
                    FilledTonalIconButton(
                        onClick = { view.tap(); minutes = BreakTimer.clampInterval(minutes + 1) },
                        enabled = minutes < BreakTimer.MAX_INTERVAL_MINUTES,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.interval_increase))
                    }
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BreakTimer.INTERVAL_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = minutes == preset,
                            onClick = { view.tap(); minutes = preset },
                            label = { Text(intervalLabel(preset)) },
                        )
                    }
                }
                if (running) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.interval_restarts_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { view.tap(); onConfirm(minutes) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun BreakTimerRunningPreview() {
    val now = System.currentTimeMillis()
    ReReminderTheme {
        BreakTimerScreen(
            uiState = BreakUiState(
                timer = BreakTimerState(
                    phase = BreakPhase.RUNNING,
                    intervalMinutes = 20,
                    remainingMillis = 12 * 60_000L + 34_000L,
                    remainingFraction = 0.63f,
                    nextAlertAt = now + 12 * 60_000L,
                    isSnoozed = false,
                    waitingForWorkingHours = false,
                    hasWorkingHours = true,
                ),
                workingHours = TimeWindow(),
                loaded = true,
            ),
            onStart = {}, onPause = {}, onResume = {}, onStop = {}, onInterval = {}, onSettings = {}, onFix = {},
        )
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BreakTimerIdleDarkPreview() {
    ReReminderTheme {
        BreakTimerScreen(
            uiState = BreakUiState(
                timer = BreakTimer.stateOf(BreakTimer.defaultReminder(), 0L, 5),
                readiness = AlarmReadiness(fullScreenAllowed = false),
                loaded = true,
            ),
            onStart = {}, onPause = {}, onResume = {}, onStop = {}, onInterval = {}, onSettings = {}, onFix = {},
        )
    }
}
