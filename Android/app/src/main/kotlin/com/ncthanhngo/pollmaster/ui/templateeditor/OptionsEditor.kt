package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.PathKey
import com.ncthanhngo.pollmaster.core.templateeditor.arr
import com.ncthanhngo.pollmaster.core.templateeditor.docPath
import com.ncthanhngo.pollmaster.core.templateeditor.obj
import com.ncthanhngo.pollmaster.core.templateeditor.str

/** Choices of a singleChoice / multiChoice field or column. A saved value stays put (old records hold it); only new choices can change it. */
@Composable
fun OptionsEditor(vm: TemplateEditorViewModel, fieldPath: DocPath) {
    val optPath = fieldPath + PathKey.Key("options")
    val options = vm.node(optPath).arr().orEmpty()
    val fieldId = vm.text(fieldPath, "id")
    SmallTitle(stringResource(R.string.te_options))
    options.forEachIndexed { i, o ->
        val at = optPath + PathKey.Index(i)
        val locked = "$fieldId|${o.obj()?.get("value").str().orEmpty()}" in vm.lockedOptions
        Column {
            PropText(vm, at, "label", R.string.te_option_label)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PropText(vm, at, "value", R.string.te_option_value, Modifier.weight(1f), locked = locked)
                IconButton(enabled = i > 0, onClick = { vm.edit(EditOp.Move(optPath, i, i - 1)) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.te_move_up)) }
                IconButton(enabled = i < options.lastIndex, onClick = { vm.edit(EditOp.Move(optPath, i, i + 1)) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.te_move_down)) }
                IconButton(onClick = { vm.edit(EditOp.Delete(optPath, i)) }) { Icon(Icons.Filled.Close, stringResource(R.string.action_delete)) }
            }
        }
    }
    OutlinedButton(onClick = { vm.edit(EditOp.NewItem(optPath, null, "option", null)) }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.te_add_option), maxLines = 1)
    }
}
