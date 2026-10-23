package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
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
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.jsonOf
import com.ncthanhngo.pollmaster.platform.CompassReader
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun CompassInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { CompassReader(context) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            NumberInput(
                Field(id = field.id, type = "integer", unit = "°", min = 0.0),
                value,
                { e -> onValue(e?.asDouble()?.let { jsonOf(((it % 360) + 360) % 360) }) },
                enabled,
                integer = true,
            )
        }
        OutlinedButton(
            enabled = enabled && reader.isAvailable,
            onClick = { scope.launch { reader.read()?.let { onValue(JsonPrimitive(it)) } } },
        ) { Text(stringResource(if (reader.isAvailable) R.string.compass_read else R.string.compass_unavailable)) }
    }
}

@Composable
internal fun ElapsedInput(field: Field, value: JsonElement?, onValue: (JsonElement?) -> Unit, enabled: Boolean) {
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
