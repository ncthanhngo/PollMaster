package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.formatElapsed
import com.ncthanhngo.pollmaster.core.format.formatNumber
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.asBool
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asList
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.jsonOf
import com.ncthanhngo.pollmaster.platform.CompassReader
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Editor for a single scalar value of [field]'s type (also used for samples and parts). */
@Composable
fun ScalarInput(
    field: Field,
    value: JsonElement?,
    onValue: (JsonElement?) -> Unit,
    enabled: Boolean,
    compact: Boolean = false,
) {
    when (field.type) {
        "multiline" -> TextInput(field, value, onValue, enabled, singleLine = false)
        "number" -> NumberInput(field, value, onValue, enabled, integer = false)
        "integer" -> NumberInput(field, value, onValue, enabled, integer = true)
        "singleChoice" -> SingleChoiceInput(field, value, onValue, enabled)
        "multiChoice" -> MultiChoiceInput(field, value, onValue, enabled)
        "yesNo" -> YesNoInput(field, value, onValue, enabled)
        "rating" -> RatingInput(field, value, onValue, enabled)
        "date", "time", "datetime", "month" -> PickerInput(field, value, onValue, enabled, compact)
        "compass" -> CompassInput(field, value, onValue, enabled)
        "elapsed" -> ElapsedInput(field, value, onValue, enabled)
        else -> TextInput(field, value, onValue, enabled, singleLine = true)
    }
}

@Composable
private fun TextInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean, singleLine: Boolean) {
    OutlinedTextField(
        value = value.asText().orEmpty(),
        onValueChange = { onValue(if (it.isEmpty()) null else JsonPrimitive(it)) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        prefix = field.prefix?.let { { Text(it) } },
        suffix = field.unit?.let { { Text(it) } },
        placeholder = field.hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
    )
}

@Composable
private fun NumberInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean, integer: Boolean) {
    fun show(e: JsonElement?) = e.asDouble()?.let { formatNumber(it, field.decimals ?: 10) }.orEmpty()
    var text by rememberSaveable { mutableStateOf(show(value)) }
    // Re-sync when the stored value changes from elsewhere (e.g. "clear value"), but keep partial input like "1.".
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value.asDouble()) text = show(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val allowed = raw.filter { it.isDigit() || it == '-' || (!integer && (it == '.' || it == ',')) }
            text = allowed
            onValue(allowed.replace(',', '.').toDoubleOrNull()?.let(::jsonOf))
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        suffix = field.unit?.let { { Text(it) } },
        placeholder = field.hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleChoiceInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val selected = value.asText()
    if (field.options.size > 6) {
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
            OutlinedTextField(
                value = field.options.firstOrNull { it.value == selected }?.label.orEmpty(),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.choose)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("—") }, onClick = { onValue(null); open = false })
                field.options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt.label) }, onClick = { onValue(JsonPrimitive(opt.value)); open = false })
                }
            }
        }
    } else {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field.options.forEach { opt ->
                FilterChip(
                    selected = opt.value == selected,
                    onClick = { onValue(if (opt.value == selected) null else JsonPrimitive(opt.value)) },
                    label = { Text(opt.label) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun MultiChoiceInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val selected = value.asList().mapNotNull { it.asText() }
    Column {
        field.options.forEach { opt ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = opt.value in selected,
                    enabled = enabled,
                    onCheckedChange = { on ->
                        val next = field.options.map { it.value }.filter { if (it == opt.value) on else it in selected }
                        onValue(if (next.isEmpty()) null else JsonArray(next.map(::JsonPrimitive)))
                    },
                )
                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YesNoInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val current: Any? = value.asBool() ?: value.asText()
    val yes = field.labels?.getOrNull(0) ?: stringResource(R.string.yes)
    val no = field.labels?.getOrNull(1) ?: stringResource(R.string.no)
    @Composable
    fun choice(label: String, v: Any) {
        FilterChip(
            selected = current == v,
            onClick = { onValue(if (current == v) null else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v as String)) },
            label = { Text(label) },
            enabled = enabled,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choice(yes, true)
        choice(no, false)
        if (field.allowUnknown) choice(stringResource(R.string.unknown), "unknown")
    }
}

@Composable
private fun RatingInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val max = field.max?.toInt() ?: 5
    val current = value.asDouble()?.toInt()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..max) {
                FilterChip(
                    selected = current == i,
                    onClick = { onValue(if (current == i) null else JsonPrimitive(i)) },
                    label = { Text(i.toString()) },
                    enabled = enabled,
                )
            }
        }
        if (field.minLabel != null || field.maxLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 = ${field.minLabel.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                Text("$max = ${field.maxLabel.orEmpty()}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CompassInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { CompassReader(context) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            NumberInput(
                Field(id = field.id, type = "integer", unit = "°"),
                value,
                { e -> onValue(e?.asDouble()?.let { jsonOf(((it % 360) + 360) % 360) }) },
                enabled,
                integer = true,
            )
        }
        androidx.compose.material3.OutlinedButton(
            enabled = enabled && reader.isAvailable,
            onClick = { scope.launch { reader.read()?.let { onValue(JsonPrimitive(it)) } } },
        ) { Text(stringResource(if (reader.isAvailable) R.string.compass_read else R.string.compass_unavailable)) }
    }
}

@Composable
private fun ElapsedInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    fun show(e: JsonElement?) = e.asDouble()?.let(::formatElapsed).orEmpty()
    fun parse(s: String): Double? {
        val parts = s.split(':')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> {
                val m = parts[0].toDoubleOrNull() ?: return null
                val sec = parts[1].toDoubleOrNull() ?: return null
                m * 60 + sec
            }
            else -> null
        }
    }
    var text by rememberSaveable { mutableStateOf(show(value)) }
    LaunchedEffect(value) {
        if (parse(text) != value.asDouble()) text = show(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() || it == ':' }
            onValue(parse(text)?.let(::jsonOf))
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(field.hint ?: "mm:ss") },
        suffix = { Text(field.unit ?: "mm:ss") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}
