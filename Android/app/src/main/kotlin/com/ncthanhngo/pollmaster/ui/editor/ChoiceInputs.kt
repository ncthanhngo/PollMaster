package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.theme.TOUCH_ONE_HAND
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.asBool
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asList
import com.ncthanhngo.pollmaster.core.model.asText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val GRID_COLUMNS = 5
private const val GRID_MIN_OPTIONS = 7
private const val GRID_MAX_LABEL = 4

/** Selected = solid primary with a check mark, unselected = outline (ux-spec D). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        modifier = Modifier.minimumInteractiveComponentSize(),
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, null, Modifier.heightIn(max = 18.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected, borderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

/** One-tap button used by the grids. */
@Composable
private fun GridButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val touch = if (LocalUiPrefs.current.oneHand) TOUCH_ONE_HAND else 48.dp
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = touch).clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().heightIn(min = touch)) {
            Text(
                (if (selected) "✓ " else "") + label,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}


@Composable
private fun ButtonGrid(
    options: List<Pair<String, String>>,
    selected: (String) -> Boolean,
    columns: Int,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (value, label) ->
                    GridButton(label, selected(value), enabled, Modifier.weight(1f)) { onPick(value) }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SingleChoiceInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean, quick: Boolean) {
    val selected = value.asText()
    fun toggle(v: String) = onValue(if (v == selected) null else JsonPrimitive(v))
    val shortLabels = field.options.all { it.label.length <= GRID_MAX_LABEL }
    when {
        field.options.size >= GRID_MIN_OPTIONS && shortLabels ->
            ButtonGrid(field.options.map { it.value to it.label }, { it == selected }, GRID_COLUMNS, enabled, ::toggle)
        quick ->
            ButtonGrid(field.options.map { it.value to it.label }, { it == selected }, if (shortLabels) GRID_COLUMNS else 2, enabled, ::toggle)
        field.options.size >= GRID_MIN_OPTIONS -> {
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
        }
        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            field.options.forEach { opt -> ChoiceChip(opt.label, opt.value == selected, enabled) { toggle(opt.value) } }
        }
    }
}

@Composable
internal fun MultiChoiceInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
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
internal fun YesNoInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean, quick: Boolean) {
    val current: Any? = value.asBool() ?: value.asText()
    val yes = field.labels?.getOrNull(0) ?: stringResource(R.string.yes)
    val no = field.labels?.getOrNull(1) ?: stringResource(R.string.no)
    val options = buildList<Pair<Any, String>> {
        add(true to yes)
        add(false to no)
        if (field.allowUnknown) add("unknown" to stringResource(R.string.unknown))
    }
    fun pick(v: Any) = onValue(if (current == v) null else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v as String))
    if (quick) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (v, label) -> GridButton(label, current == v, enabled, Modifier.weight(1f)) { pick(v) } }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> ChoiceChip(label, current == v, enabled) { pick(v) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RatingInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val max = field.max?.toInt() ?: 5
    val current = value.asDouble()?.toInt()
    Column {
        // wraps at large font sizes instead of running off screen
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 1..max) {
                ChoiceChip(i.toString(), current == i, enabled) { onValue(if (current == i) null else JsonPrimitive(i)) }
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
