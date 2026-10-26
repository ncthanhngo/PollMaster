package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.RoundEntryRules
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.sample

/**
 * Heading with `roundEntry` and its `samples` fields: a "Lần 1 · Lần 2 · Lần 3" bar, and every field shows only the
 * box of the selected round. [focus] (a cue / review jump) selects the first round still empty for that field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundGroup(heading: HeadingItem, fields: List<Field>, env: EditorEnv, focus: String?, onTouch: (String) -> Unit) {
    val entry = heading.roundEntry ?: return
    val start = fields.firstOrNull { it.id == focus }?.let { RoundEntryRules.firstEmptySample(env.record.values[it.id], entry.samples) } ?: 0
    var selected by rememberSaveable(heading.id, focus) { mutableIntStateOf(start) }
    val round = RoundContext(selected.coerceIn(0, entry.samples - 1), entry)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeading(heading)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for (i in 0 until entry.samples) {
                val hasData = fields.any { !env.record.values[it.id].sample(i).isEmptyJson() }
                SegmentedButton(
                    selected = round.index == i,
                    onClick = { selected = i },
                    shape = SegmentedButtonDefaults.itemShape(i, entry.samples),
                    label = { Text(stringResource(R.string.sample_n, i + 1) + if (hasData) " •" else "") },
                )
            }
        }
        FieldGroupCard(fields) { f -> androidx.compose.foundation.layout.Box(Modifier.trackTouch { onTouch(f.id) }) { FieldCard(f, env.scope, env, round = round) } }
    }
}
