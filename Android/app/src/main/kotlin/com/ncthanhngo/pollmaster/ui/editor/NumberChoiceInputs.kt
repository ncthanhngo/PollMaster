package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.ChipsRange
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.asDouble
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Integer as one-tap chips `min…max` plus "Khác" for a number outside the range (schema 8 `chipsRange`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipsInput(
    field: Field,
    range: ChipsRange,
    max: Int,
    value: JsonElement?,
    onValue: (JsonElement?) -> Unit,
    enabled: Boolean,
    warn: RangeWarn?,
) {
    val current = value.asDouble()?.takeIf { it == Math.rint(it) }?.toInt()
    val outside = value.asDouble() != null && (current == null || current !in range.min..max)
    var other by remember { mutableStateOf(outside) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in range.min..max) {
                ChoiceChip(n.toString(), current == n && !other, enabled) { other = false; onValue(if (current == n) null else JsonPrimitive(n)) }
            }
            ChoiceChip(stringResource(R.string.chips_other), other || outside, enabled) { other = !other }
        }
        if (other || outside) NumberInput(field, value, onValue, enabled, integer = true, warn = warn)
    }
}

/** `−  n  +` for integers (step 1, within min/max); the number in the middle can still be typed. */
@Composable
internal fun StepperInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean, warn: RangeWarn?) {
    val current = value.asDouble()?.toInt()
    val min = field.min?.toInt()
    val max = field.max?.toInt()
    fun set(n: Int) = onValue(JsonPrimitive(n.coerceIn(min ?: Int.MIN_VALUE, max ?: Int.MAX_VALUE)))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(
            enabled = enabled && current != null && (min == null || current > min),
            onClick = { set((current ?: 0) - 1) },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        ) { Text("−", style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
        Column(Modifier.weight(1f)) { NumberInput(field, value, onValue, enabled, integer = true, warn = warn) }
        FilledTonalIconButton(
            enabled = enabled && (max == null || (current ?: 0) < max),
            onClick = { set((current ?: ((min ?: 0) - 1)) + 1) },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        ) { Text("+", style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }
    }
}
