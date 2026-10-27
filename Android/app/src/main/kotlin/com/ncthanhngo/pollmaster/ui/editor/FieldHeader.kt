package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.KDD
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue

/** Small, pale code badge (A-01); [warn] gives the stronger flag look used for ~ / KĐĐ. */
@Composable
fun Badge(text: String, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (warn) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), maxLines = 1)
    }
}

/** Label (semibold) followed directly by a red asterisk when required, then "~ Ước lượng" / "KĐĐ" while that flag is on. */
@Composable
fun FieldTitle(field: Field, modifier: Modifier = Modifier, fv: FieldValue? = null) {
    val star = MaterialTheme.colorScheme.error
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append(field.label)
                if (field.required) withStyle(SpanStyle(color = star, fontWeight = FontWeight.Bold)) { append(" *") }
            },
            Modifier.weight(1f, fill = false).padding(end = 6.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        when (fv?.flag) {
            FieldValue.FLAG_ESTIMATE -> Badge(stringResource(R.string.flag_estimate_badge), warn = true)
            FieldValue.FLAG_NOT_MEASURABLE -> Badge(KDD, warn = true)
        }
    }
}

/** The ⋯ menu (ước lượng, KĐĐ, note, clear); ~ / KĐĐ live only here and show as badges after the label when on. */
@Composable
fun FieldActions(
    field: Field,
    fv: FieldValue?,
    onNote: () -> Unit,
    update: ((FieldValue) -> FieldValue) -> Unit,
    menuOpen: Boolean = false,
    onMenuOpen: (Boolean) -> Unit = {},
    /** One-hand mode hides the ⋯ button: the menu opens with a long press on the card instead. */
    showMenuButton: Boolean = true,
) {
    FieldMenu(field, fv, onNote, update, menuOpen, onMenuOpen, showMenuButton)
}

@Composable
private fun FieldMenu(
    field: Field,
    fv: FieldValue?,
    onNote: () -> Unit,
    update: ((FieldValue) -> FieldValue) -> Unit,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    showButton: Boolean,
) {
    Box {
        if (showButton) {
            IconButton(onClick = { onOpen(true) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.field_menu_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
            if (field.flagsEnabled) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.field_menu_estimate)) },
                    onClick = {
                        onOpen(false)
                        update { it.copy(flag = if (it.flag == FieldValue.FLAG_ESTIMATE) null else FieldValue.FLAG_ESTIMATE, reason = null) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.field_menu_kdd)) },
                    onClick = {
                        onOpen(false)
                        update { it.copy(flag = if (it.flag == FieldValue.FLAG_NOT_MEASURABLE) null else FieldValue.FLAG_NOT_MEASURABLE) }
                    },
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.field_menu_note)) }, onClick = { onOpen(false); onNote() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.field_menu_clear)) },
                onClick = { onOpen(false); update { FieldValue(note = fv?.note, review = fv?.review) } },
            )
        }
    }
}
