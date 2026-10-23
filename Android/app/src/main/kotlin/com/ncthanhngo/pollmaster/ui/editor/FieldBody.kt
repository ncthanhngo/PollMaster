package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Part
import com.ncthanhngo.pollmaster.core.model.asList
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.isVisible
import com.ncthanhngo.pollmaster.core.model.sample
import com.ncthanhngo.pollmaster.core.model.withSample
import kotlinx.serialization.json.JsonElement

private const val WIDE_SCREEN_DP = 600

/** Input area of a field card, chosen by field type; also samples, parts and attached photos. */
@Composable
fun FieldBody(field: Field, fv: FieldValue?, scope: ValueScope, env: EditorEnv, enabled: Boolean, quick: Boolean = false) {
    val key = scope.key(field.id)
    fun setV(element: JsonElement?) {
        scope.update(field.id) { it.copy(v = element) }
        env.controller.touch(key)
    }
    fun warn(min: Double?, max: Double?, unit: String?, suffix: String = "") =
        if (min != null || max != null) RangeWarn(min, max, unit, key + suffix, env.controller) else null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            field.type == "composite" -> Unit
            field.type == "sessionTime" -> SessionTimeInput(fv?.v, enabled, ::setV)
            field.type == "gps" -> GpsInput(field, fv?.v, enabled, env.checker.siteDistance(env.record, field), ::setV)
            field.type == "audio" -> AudioField(field, fv, scope, env, enabled)
            field.isMedia -> MediaField(field, fv, scope, env, enabled, asValue = true)
            field.samples != null -> {
                val n = field.samples
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(n) { i ->
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sample_n, i + 1), style = MaterialTheme.typography.labelMedium)
                            ScalarInput(
                                field = field,
                                value = fv.sample(i),
                                onValue = { e -> setV(fv.withSample(i, n, e)) },
                                enabled = enabled,
                                compact = true,
                                warn = warn(field.warnMin, field.warnMax, field.unit, "#$i"),
                            )
                        }
                    }
                }
            }
            field.lookup != null -> LookupInput(field, fv, scope, env, enabled, ::setV)
            else -> {
                ScalarInput(field, fv?.v, ::setV, enabled, warn = warn(field.warnMin, field.warnMax, field.unit), quick = quick)
                OtherTexts(field, fv, scope, enabled)
            }
        }
        val visible = field.parts.filter { it.isVisible(fv, scope::lookup) }
        if (visible.isNotEmpty()) {
            PartsGrid(visible) { part ->
                Column {
                    Text(part.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScalarInput(
                        field = Field(id = part.id, label = part.label, type = part.type, unit = part.unit, options = part.options),
                        value = fv?.parts?.get(part.id),
                        onValue = { e ->
                            scope.update(field.id) { it.copy(parts = if (e == null) it.parts - part.id else it.parts + (part.id to e)) }
                            env.controller.touch(key)
                        },
                        enabled = enabled,
                        compact = true,
                        warn = warn(part.warnMin, part.warnMax, part.unit, "." + part.id),
                    )
                }
            }
        }
        if (field.attachPhoto) MediaField(field, fv, scope, env, enabled, asValue = false)
    }
}

/** Parts in a grid: 2 columns, or 3 when there are at least 3 parts and the screen is >= 600 dp wide. */
@Composable
private fun PartsGrid(parts: List<Part>, cell: @Composable (Part) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
    val columns = if (parts.size >= 3 && wide) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { part -> Box(Modifier.weight(1f)) { cell(part) } }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Free-text boxes for selected options that carry `withText`. */
@Composable
private fun OtherTexts(field: Field, fv: FieldValue?, scope: ValueScope, enabled: Boolean) {
    val selected: List<String> = when (field.type) {
        "singleChoice" -> listOfNotNull(fv?.v.asText())
        "multiChoice" -> fv?.v.asList().mapNotNull { it.asText() }
        else -> emptyList()
    }
    field.options.filter { it.withText && it.value in selected }.forEach { opt ->
        OutlinedTextField(
            value = fv?.other?.get(opt.value).orEmpty(),
            onValueChange = { text -> scope.update(field.id) { it.copy(other = it.other + (opt.value to text)) } },
            label = { Text(opt.label) },
            placeholder = { Text(stringResource(R.string.other_text)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
