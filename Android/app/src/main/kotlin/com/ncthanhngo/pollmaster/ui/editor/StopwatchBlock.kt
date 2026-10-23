package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.editor.Stopwatch
import com.ncthanhngo.pollmaster.core.format.formatElapsed
import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.model.Cue
import com.ncthanhngo.pollmaster.core.model.Row as TableRow
import com.ncthanhngo.pollmaster.core.model.StopwatchDef
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.theme.TOUCH_ONE_HAND
import kotlinx.coroutines.delay
import java.time.Instant

private const val CUE_SECONDS = 8_000L

/**
 * Stopwatch stuck to the top of the row form. Large (mm:ss + T0 / mark / undo) while the list is at the top, a thin
 * strip `mm:ss · [Ghi T4] · ↶` once it is scrolled ([collapsed]). Every press vibrates; a mark with a `cue` shows a
 * reminder banner for 8 s with a shortcut to the cue's field ([onOpenField]). State lives in the row (survives a kill).
 */
@Composable
fun PinnedStopwatch(
    tableId: String,
    row: TableRow,
    def: StopwatchDef,
    controller: EditorController,
    collapsed: Boolean,
    onOpenField: (String) -> Unit,
) {
    val start = parseInstant(row.stopwatchStart)
    val next = Stopwatch.nextColumn(def, row)
    val running = start != null && next != null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running, row.stopwatchStart) {
        while (running) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val shown = if (start != null && running) Stopwatch.secondsSince(start, Instant.ofEpochMilli(now)) else 0L

    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    val haptic = LocalHapticFeedback.current
    var cue by remember { mutableStateOf<Cue?>(null) }
    LaunchedEffect(cue) {
        if (cue != null) {
            delay(CUE_SECONDS)
            cue = null
        }
    }

    fun press() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (start == null) {
            controller.startStopwatch(tableId, row.id)
        } else {
            controller.markStopwatch(tableId, row.id, Stopwatch.secondsSince(start, Instant.now()))
                ?.let { column -> cue = Stopwatch.cue(def, column) }
        }
    }
    fun undo() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        controller.undoStopwatch(tableId, row.id)
    }
    val label = when {
        start == null -> stringResource(R.string.stopwatch_start)
        next != null -> stringResource(R.string.stopwatch_mark, next)
        else -> stringResource(R.string.stopwatch_done)
    }
    val touch = if (LocalUiPrefs.current.oneHand) TOUCH_ONE_HAND else 56.dp

    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            if (collapsed) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(formatElapsed(shown.toDouble()), style = MaterialTheme.typography.headlineSmall)
                    Button(
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        enabled = start == null || next != null,
                        onClick = ::press,
                    ) { Text(label, maxLines = 1) }
                    OutlinedButton(enabled = start != null, onClick = ::undo, modifier = Modifier.heightIn(min = 44.dp)) { Text("↶") }
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        formatElapsed(shown.toDouble()),
                        fontSize = 56.sp,
                        style = MaterialTheme.typography.displayMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.weight(1f).heightIn(min = touch), enabled = start == null || next != null, onClick = ::press) { Text(label) }
                        OutlinedButton(modifier = Modifier.heightIn(min = touch), enabled = start != null, onClick = ::undo) {
                            Text(stringResource(R.string.stopwatch_undo))
                        }
                    }
                }
            }
            cue?.let { c ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        c.field?.let { field ->
                            TextButton(onClick = { cue = null; onOpenField(field) }) { Text(stringResource(R.string.cue_open, field)) }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
