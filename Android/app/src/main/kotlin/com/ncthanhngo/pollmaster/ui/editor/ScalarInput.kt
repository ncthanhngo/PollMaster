package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.format.formatNumber
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.jsonOf
import com.ncthanhngo.pollmaster.core.validation.outsideWarnRange
import com.ncthanhngo.pollmaster.core.validation.rangeWarningMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Plausible-range check of a numeric input: asks once for confirmation when the user leaves an odd value. */
class RangeWarn(val min: Double?, val max: Double?, val unit: String?, val key: String, val controller: EditorController)

/** Editor for a single scalar value of [field]'s type (also used for samples and parts). */
@Composable
fun ScalarInput(
    field: Field,
    value: JsonElement?,
    onValue: (JsonElement?) -> Unit,
    enabled: Boolean,
    compact: Boolean = false,
    warn: RangeWarn? = null,
    quick: Boolean = false,
) {
    when (field.type) {
        "multiline" -> TextInput(field, value, onValue, enabled, singleLine = false)
        "number" -> NumberInput(field, value, onValue, enabled, integer = false, warn = warn)
        "integer" -> NumberInput(field, value, onValue, enabled, integer = true, warn = warn)
        "singleChoice" -> SingleChoiceInput(field, value, onValue, enabled, quick)
        "multiChoice" -> MultiChoiceInput(field, value, onValue, enabled)
        "yesNo" -> YesNoInput(field, value, onValue, enabled, quick)
        "rating" -> RatingInput(field, value, onValue, enabled)
        "date", "time", "datetime", "month" -> PickerInput(field, value, onValue, enabled, compact)
        "compass" -> CompassInput(field, value, onValue, enabled)
        "elapsed" -> ElapsedInput(field, value, onValue, enabled)
        else -> TextInput(field, value, onValue, enabled, singleLine = true)
    }
}

@Composable
private fun Hint(field: Field): @Composable () -> Unit = { Text(field.hint ?: stringResource(R.string.input_placeholder)) }

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
        placeholder = Hint(field),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
    )
}

@Composable
internal fun NumberInput(
    field: Field,
    value: JsonElement?,
    onValue: (JsonElement?) -> Unit,
    enabled: Boolean,
    integer: Boolean,
    warn: RangeWarn? = null,
) {
    fun show(e: JsonElement?) = e.asDouble()?.let { formatNumber(it, field.decimals ?: 10) }.orEmpty()
    var text by rememberSaveable { mutableStateOf(show(value)) }
    var hadFocus by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    // Re-sync when the stored value changes from elsewhere (e.g. "clear value"), but keep partial input like "1.".
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value.asDouble()) text = show(value)
    }
    // Digits, a decimal point for non-integers, and a minus sign only where negatives make sense.
    val signed = (field.min ?: 0.0) < 0 || (field.warnMin ?: 0.0) < 0
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val allowed = raw.filter { it.isDigit() || (signed && it == '-') || (!integer && (it == '.' || it == ',')) }
            text = allowed
            onValue(allowed.replace(',', '.').toDoubleOrNull()?.let(::jsonOf))
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (state.isFocused) {
                hadFocus = true
            } else if (hadFocus) {
                hadFocus = false
                val n = text.replace(',', '.').toDoubleOrNull()
                if (warn != null && n != null && outsideWarnRange(n, warn.min, warn.max) && !warn.controller.isWarningAcknowledged(warn.key)) asking = true
            }
        },
        singleLine = true,
        suffix = field.unit?.let { { Text(it) } },
        placeholder = Hint(field),
        leadingIcon = if (signed && enabled) ({ SignToggle(text) { text = it; onValue(it.replace(',', '.').toDoubleOrNull()?.let(::jsonOf)) } }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
    if (asking && warn != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.warn_confirm_title)) },
            text = { Text("$text — " + rangeWarningMessage(warn.min, warn.max, warn.unit) + ". " + stringResource(R.string.warn_confirm_message)) },
            confirmButton = { TextButton(onClick = { warn.controller.acknowledgeWarning(warn.key); asking = false }) { Text(stringResource(R.string.warn_keep)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.warn_recheck)) } },
        )
    }
}

/** "±" button: Android's decimal keypad has no minus key. */
@Composable
private fun SignToggle(text: String, onText: (String) -> Unit) {
    TextButton(onClick = { onText(if (text.startsWith("-")) text.drop(1) else "-$text") }) { Text("±") }
}
