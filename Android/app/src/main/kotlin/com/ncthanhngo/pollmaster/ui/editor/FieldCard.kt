package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.FieldLayoutRules
import com.ncthanhngo.pollmaster.core.format.KDD
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.RoundEntry
import com.ncthanhngo.pollmaster.core.model.asText
import kotlinx.serialization.json.JsonPrimitive
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.theme.warnColor

private const val COMPACT_FRACTION = 0.4f

/** The round (0-based) being entered and the heading option that groups the round fields. */
class RoundContext(val index: Int, val entry: RoundEntry)

/**
 * Card for one field: badge, label with `*`, KĐĐ / ~ toggles, input by type, then reason, note, validation
 * error, warning and photo reference.
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    fv?.review?.takeIf { it.isNotBlank() }?.let { note ->
        ReviewBox(note) { scope.update(field.id) { it.copy(review = null) } }
    }
    AppCard(if (oneHand && editable) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true }) else Modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f - COMPACT_FRACTION - 0.08f)) {
                        FieldLabel(field, showCode, fv)
                    }
                    Box(Modifier.weight(COMPACT_FRACTION)) { input() }
                    // Compact rows keep ~ and KĐĐ in the ⋯ menu to save height (they show as badges when set).
                    if (editable) FieldActions(field, fv, { noteOpen = true }, update, chips = false, menuOpen = menuOpen, onMenuOpen = { menuOpen = it }, showMenuButton = !oneHand)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { FieldLabel(field, showCode, fv) }
                    if (editable) FieldActions(field, fv, { noteOpen = true }, update, menuOpen = menuOpen, onMenuOpen = { menuOpen = it }, showMenuButton = !oneHand)
                }
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

@Composable
private fun FieldLabel(field: Field, showCode: Boolean, fv: FieldValue?) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            if (showCode) Badge(field.id)
            when (fv?.flag) {
                FieldValue.FLAG_ESTIMATE -> Badge("~", warn = true)
                FieldValue.FLAG_NOT_MEASURABLE -> Badge(KDD, warn = true)
            }
            FieldTitle(field, Modifier.weight(1f))
        }
        listOfNotNull(field.method, field.hint).filter { it.isNotBlank() }.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
