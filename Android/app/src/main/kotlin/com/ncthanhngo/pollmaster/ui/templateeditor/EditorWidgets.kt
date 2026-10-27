package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.text

/** Field types a form offers for new fields and columns (the others, e.g. computed, are edited in the JSON). */
val FIELD_TYPES = listOf(
    "text", "multiline", "number", "integer", "singleChoice", "multiChoice", "yesNo", "rating", "date", "time", "datetime", "month",
    "gps", "compass", "photo", "video", "audio", "elapsed",
)
val COLUMN_TYPES = FIELD_TYPES

fun typeLabelRes(type: String): Int? = when (type) {
    "text" -> R.string.ftype_text
    "multiline" -> R.string.ftype_multiline
    "number" -> R.string.ftype_number
    "integer" -> R.string.ftype_integer
    "singleChoice" -> R.string.ftype_singleChoice
    "multiChoice" -> R.string.ftype_multiChoice
    "yesNo" -> R.string.ftype_yesNo
    "rating" -> R.string.ftype_rating
    "date" -> R.string.ftype_date
    "time" -> R.string.ftype_time
    "datetime" -> R.string.ftype_datetime
    "sessionTime" -> R.string.ftype_sessionTime
    "month" -> R.string.ftype_month
    "gps" -> R.string.ftype_gps
    "compass" -> R.string.ftype_compass
    "photo" -> R.string.ftype_photo
    "video" -> R.string.ftype_video
    "audio" -> R.string.ftype_audio
    "elapsed" -> R.string.ftype_elapsed
    "composite" -> R.string.ftype_composite
    "computed" -> R.string.ftype_computed
    "signature" -> R.string.ftype_signature
    else -> null
}

@Composable
fun typeLabel(type: String): String = typeLabelRes(type)?.let { stringResource(it) } ?: type

/** A text key of the node at [path]; every keystroke is an edit of the draft. [locked] shows a padlock and refuses typing. */
@Composable
fun PropText(
    vm: TemplateEditorViewModel, path: DocPath, key: String, label: Int,
    modifier: Modifier = Modifier, multiline: Boolean = false, locked: Boolean = false, supporting: Int? = null, maxLength: Int? = null,
) {
    val value = vm.text(path, key)
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (!locked) vm.set(path, key, maxLength?.let { v.take(it) } ?: v) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        readOnly = locked,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        trailingIcon = if (locked) ({ Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.te_code_locked)) }) else null,
        supportingText = (if (locked) R.string.te_code_locked else supporting)?.let { { Text(stringResource(it)) } },
    )
}

/** A number key; what is typed stays on screen even while it is not a number yet ("1."). */
@Composable
fun PropNumber(vm: TemplateEditorViewModel, path: DocPath, key: String, label: Int, modifier: Modifier = Modifier) {
    val stored = vm.number(path, key)
    var typed by remember(path.text, key) { mutableStateOf(stored) }
    // a change from elsewhere (the JSON editor) replaces what is shown; a half-typed value ("1." or "-") is not a change
    LaunchedEffect(stored) {
        val same = if (typed.isBlank()) stored.isEmpty() else typed.trim().toDoubleOrNull() == stored.toDoubleOrNull()
        if (!same) typed = stored
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it; vm.setNumber(path, key, it) },
        modifier = modifier,
        label = { Text(stringResource(label), maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
fun PropSwitch(vm: TemplateEditorViewModel, path: DocPath, key: String, label: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = vm.flag(path, key), onCheckedChange = { vm.setFlag(path, key, it) })
    }
}

@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
fun SmallTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
