package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.StopwatchDef
import kotlinx.coroutines.delay
import java.time.Instant

/** Big mm:ss clock with T0 / next-mark / undo buttons; state lives in the row so it survives process death. */
@Composable
fun StopwatchBlock(tableId: String, row: Row, def: StopwatchDef, controller: EditorController) {
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                formatElapsed(shown.toDouble()),
                fontSize = 56.sp,
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = start == null || next != null,
                    onClick = {
                        if (start == null) {
                            controller.startStopwatch(tableId, row.id)
                        } else {
                            controller.markStopwatch(tableId, row.id, Stopwatch.secondsSince(start, Instant.now()))
                        }
                    },
                ) {
                    Text(
                        when {
                            start == null -> stringResource(R.string.stopwatch_start)
                            next != null -> stringResource(R.string.stopwatch_mark, next)
                            else -> stringResource(R.string.stopwatch_done)
                        },
                    )
                }
                OutlinedButton(enabled = start != null, onClick = { controller.undoStopwatch(tableId, row.id) }) {
                    Text(stringResource(R.string.stopwatch_undo))
                }
            }
        }
    }
}
