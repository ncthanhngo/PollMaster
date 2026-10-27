package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.ui.common.TemplateBadge
import com.ncthanhngo.pollmaster.ui.common.hexColor
import kotlinx.serialization.json.JsonPrimitive

/** Name, short name, colour, subtitle and instructions of the template; the code can be typed until a new template's first release. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateInfoForm(vm: TemplateEditorViewModel) {
    val root: DocPath = emptyList()
    val color = vm.text(root, "color").ifEmpty { null }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormColumn {
            PropText(vm, root, "title", R.string.template_rename_label)
            PropText(vm, root, "shortName", R.string.template_short_name, maxLength = 12, supporting = R.string.template_short_name_hint)
            Text(stringResource(R.string.template_color), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Project.COLORS.forEach { hex ->
                    val selected = hex.equals(color, ignoreCase = true)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(hexColor(hex))
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { vm.edit(EditOp.Set(root, "color", JsonPrimitive(hex))) })
                            .semantics { contentDescription = hex },
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White) }
                }
            }
            TemplateBadge(vm.text(root, "shortName").trim().ifEmpty { vm.text(root, "id") }, color ?: Project.COLORS.first())
            PropText(vm, root, "subtitle", R.string.te_subtitle)
            PropText(vm, root, "instructions", R.string.te_instructions, multiline = true)
            if (vm.canEditTemplateId) PropText(vm, root, "id", R.string.te_template_id, supporting = R.string.te_code_hint)
            else Text(stringResource(R.string.te_id_version, vm.text(root, "id"), vm.text(root, "version")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            JsonButton(vm, root)
        }
    }
}
