package com.ncthanhngo.pollmaster.ui.editor

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDate
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.format.displayMonth
import com.ncthanhngo.pollmaster.core.format.displayTime
import com.ncthanhngo.pollmaster.core.format.toIso
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun pickDate(context: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

private fun pickTime(context: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}

/** Date then time; result is an ISO-8601 string with the device offset. */
fun pickDateTime(context: Context, initialIso: String?, onPicked: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val initial = runCatching { OffsetDateTime.parse(initialIso).atZoneSameInstant(zone).toLocalDateTime() }.getOrDefault(LocalDateTime.now())
    pickDate(context, initial.toLocalDate()) { date ->
        pickTime(context, initial.toLocalTime()) { time ->
            onPicked(LocalDateTime.of(date, time).atZone(zone).toOffsetDateTime().let(::toIso))
        }
    }
}

@Composable
fun PickerInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean, compact: Boolean) {
    val context = LocalContext.current
    val text = value.asText()
    val shown = when {
        text == null -> stringResource(
            when (field.type) {
                "date" -> R.string.pick_date
                "time" -> R.string.pick_time
                "month" -> R.string.pick_month
                else -> R.string.pick_datetime
            },
        )
        field.type == "date" -> displayDate(text)
        field.type == "month" -> displayMonth(text)
        field.type == "datetime" -> displayDateTime(text)
        else -> text
    }
    OutlinedButton(
        enabled = enabled,
        modifier = if (compact) Modifier else Modifier.fillMaxWidth(),
        onClick = {
            when (field.type) {
                "date" -> pickDate(context, runCatching { LocalDate.parse(text) }.getOrDefault(LocalDate.now())) { onValue(JsonPrimitive(it.toString())) }
                "month" -> pickDate(context, runCatching { YearMonth.parse(text).atDay(1) }.getOrDefault(LocalDate.now())) {
                    onValue(JsonPrimitive(YearMonth.from(it).toString()))
                }
                "time" -> pickTime(context, runCatching { LocalTime.parse(text) }.getOrDefault(LocalTime.now())) {
                    onValue(JsonPrimitive(it.format(timeFmt)))
                }
                else -> pickDateTime(context, text) { onValue(JsonPrimitive(it)) }
            }
        },
    ) { Text(shown + (field.unit?.let { " $it" } ?: "")) }
}

/** One line `18:41 → …` with ✎; the pencil opens a sheet to edit start and end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTimeInput(value: JsonElement?, enabled: Boolean, onValue: (JsonElement?) -> Unit) {
    val context = LocalContext.current
    val obj = value.asObject()
    val start = obj?.get("start").asText()
    val end = obj?.get("end").asText()
    var editing by remember { mutableStateOf(false) }
    fun set(key: String, iso: String) {
        val map = LinkedHashMap<String, JsonElement>(obj ?: emptyMap())
        map[key] = JsonPrimitive(iso)
        if ("end" !in map) map["end"] = JsonNull
        onValue(JsonObject(map))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            (start?.let { displayTime(it) } ?: "—") + " → " + (end?.let { displayTime(it) } ?: "…"),
            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
        )
        IconButton(enabled = enabled, onClick = { editing = true }) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
        }
    }
    if (editing) {
        ModalBottomSheet(onDismissRequest = { editing = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("start" to (R.string.session_start to start), "end" to (R.string.session_end to end)).forEach { (key, spec) ->
                    val (label, iso) = spec
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${stringResource(label)}: ${iso?.let { displayDateTime(it) } ?: "—"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { pickDateTime(context, iso) { set(key, it) } }) { Text(stringResource(R.string.action_edit)) }
                    }
                }
                TextButton(onClick = { editing = false }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
