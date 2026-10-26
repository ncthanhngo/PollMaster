package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.FieldLayoutRules
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.RoundEntry
import com.ncthanhngo.pollmaster.core.model.asText
import kotlinx.serialization.json.JsonPrimitive
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.theme.attentionColor
import com.ncthanhngo.pollmaster.ui.theme.warnColor

/** Fixed width of the input on a compact row, so inputs line up down the section. */
private val COMPACT_INPUT_WIDTH = 132.dp

/** The round (0-based) being entered and the heading option that groups the round fields. */
class RoundContext(val index: Int, val entry: RoundEntry)

/**
 * One field (a row in a [FieldGroupCard], else its own card): label with `*` and ~ / KĐĐ badges, one meta line
 * (code · method · usual range), ⋯ menu, input by type, then reason, note, validation error, warning and photo reference.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FieldCard(
    field: Field,
    scope: ValueScope,
    env: EditorEnv,
    modifier: Modifier = Modifier,
    showCode: Boolean = true,
    quick: Boolean = false,
    /** Round being entered when the field sits in a `roundEntry` group (shows one sample instead of all). */
    round: RoundContext? = null,
) {
    if (env.reviewMode) {
        ReviewFieldCard(field, scope, env, modifier, showCode)
        return
    }
    val fv = scope.get(field.id)
    val key = scope.key(field.id)
    val kdd = fv?.flag == FieldValue.FLAG_NOT_MEASURABLE
    var noteOpen by rememberSaveable(key) { mutableStateOf(!fv?.note.isNullOrBlank()) }
    val editable = !field.readonly && field.type != "computed"
    val error = env.error(key)
    val warning = if (error == null && !kdd) env.warning(key) else null
    val compact = LocalUiPrefs.current.compact && FieldLayoutRules.isCompactable(field) && !quick
    val oneHand = LocalUiPrefs.current.oneHand
    var menuOpen by remember { mutableStateOf(false) }
    var photoTrigger by remember { mutableIntStateOf(0) }
    val update: ((FieldValue) -> FieldValue) -> Unit = { f -> scope.update(field.id, f); env.controller.touch(key) }

    val input: @Composable () -> Unit = {
        Box(Modifier.alpha(if (kdd) 0.4f else 1f)) {
            when {
                field.type == "computed" -> ComputedBox(field, env, round?.index)
                field.readonly -> ReadOnlyBox(field, fv, scope, env)
                else -> FieldBody(field, fv, scope, env, enabled = !kdd, quick = quick, round = round)
            }
        }
    }

    val stroke = when {
        error != null -> MaterialTheme.colorScheme.error
        !fv?.review.isNullOrBlank() -> attentionColor()
        else -> null
    }
    val menu: @Composable () -> Unit = {
        if (editable) FieldActions(field, fv, { noteOpen = true }, update, menuOpen = menuOpen, onMenuOpen = { menuOpen = it }, showMenuButton = !oneHand)
    }
    FieldContainer(
        modifier.then(if (oneHand && editable) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }) else Modifier),
        stroke = stroke, compact = compact,
    ) {
            fv?.review?.takeIf { it.isNotBlank() }?.let { note ->
                ReviewBox(note) { scope.update(field.id) { it.copy(review = null) } }
            }
            if (compact) {
                // label and input share the first line; the meta line runs full width below so the label keeps its room
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldTitle(field, Modifier.weight(1f), fv)
                    Box(Modifier.width(COMPACT_INPUT_WIDTH)) { input() }
                    menu()
                }
                FieldMeta(field, showCode)
                FieldHint(field)
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        FieldTitle(field, fv = fv)
                        FieldMeta(field, showCode)
                    }
                    menu()
                }
                FieldHint(field)
                input()
            }
            if (kdd) {
                OutlinedTextField(
                    value = fv?.reason.orEmpty(),
                    onValueChange = { text -> scope.update(field.id) { it.copy(reason = text) }; env.controller.touch(key) },
                    label = { Text(stringResource(R.string.field_reason)) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (noteOpen || !fv?.note.isNullOrBlank()) {
                OutlinedTextField(
                    value = fv?.note.orEmpty(),
                    onValueChange = { text -> scope.update(field.id) { it.copy(note = text) } },
                    label = { Text(stringResource(R.string.field_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            field.photoRef?.let { PhotoRefRow(it, env, photoTrigger) }
            if (field.photoRef != null && (field.type == "text" || field.type == "multiline") && !kdd) {
                OcrButton(field, env, editable, onNeedPhoto = { photoTrigger++ }) { line ->
                    update { it.copy(v = JsonPrimitive(if (field.type == "multiline") appendText(it.v.asText(), line, "\n") else line)) }
                }
            }
            if (field.isMedia) PhotoUsers(field.id, env)
            error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            warning?.let { Text("⚠ " + it.message, color = warnColor(), style = MaterialTheme.typography.bodySmall) }
    }
}

/** Reviewer's note shown at the top of a card, with "Đã xử lý" for the surveyor once the issue is fixed. */
@Composable
fun ReviewBox(text: String, onResolved: (() -> Unit)?) {
    AppCard(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        borderColor = MaterialTheme.colorScheme.tertiary,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.review_note_prefix, text),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (onResolved != null) TextButton(onClick = onResolved) { Text(stringResource(R.string.review_resolved)) }
        }
    }
}

/** One secondary line under the label: code · method · usual range (was up to three grey lines). */
@Composable
private fun FieldMeta(field: Field, showCode: Boolean) {
    val text = listOfNotNull(field.method?.takeIf { it.isNotBlank() }, FieldLayoutRules.usualRange(field)).joinToString(" · ")
    if (!showCode && text.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showCode) Badge(field.id)
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Hint under the label, once (never as a placeholder: boxes can be narrow). */
@Composable
private fun FieldHint(field: Field) {
    field.hint?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
