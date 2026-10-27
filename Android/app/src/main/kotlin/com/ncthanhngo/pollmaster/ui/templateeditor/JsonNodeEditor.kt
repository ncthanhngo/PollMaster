package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDoc
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateEdit
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateEditException
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateJsonWriter
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateLint
import com.ncthanhngo.pollmaster.core.templateeditor.text
import kotlinx.coroutines.delay

/** What typing [text] over the node would do: [error] when it cannot be applied, [idRemoved] when it drops a code the base has. */
class JsonCheck(val error: String?, val idRemoved: Boolean = false)

private const val CHECK_DELAY_MS = 300L

fun TemplateEditorViewModel.checkJson(path: DocPath, text: String): JsonCheck {
    val d = draft ?: return JsonCheck(null)
    return checkJson(d.doc, base, d.baseId == null, path, text)
}

fun checkJson(doc: TemplateDoc, base: TemplateDoc?, isNew: Boolean, path: DocPath, text: String): JsonCheck {
    val after = try {
        TemplateEdit.apply(doc, EditOp.ReplaceText(path, text))
    } catch (e: TemplateEditException) {
        return JsonCheck(e.message)
    }
    val known = TemplateLint.check(doc, base, isNew).filter { it.code == "ID_REMOVED" }.map { it.message }.toSet()
    return JsonCheck(null, TemplateLint.check(after, base, isNew).any { it.code == "ID_REMOVED" && it.message !in known })
}

/** Advanced JSON of one node (or the whole template for the empty path): errors show 300 ms after typing, Áp dụng is dimmed while the JSON is broken. */
@Composable
fun JsonNodeEditor(vm: TemplateEditorViewModel, path: DocPath) {
    val original = remember(path) { (if (path.isEmpty()) vm.doc?.root else vm.node(path))?.let(TemplateJsonWriter::write).orEmpty() }
    var text by rememberSaveable(path.text) { mutableStateOf(original) }
    var check by remember { mutableStateOf(JsonCheck(null)) }
    LaunchedEffect(text) {
        delay(CHECK_DELAY_MS)
        check = vm.checkJson(path, text)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            isError = check.error != null,
        )
        val message = check.error ?: vm.editError
        if (message != null) Text(message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else Text(stringResource(R.string.te_json_ok), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (check.idRemoved) Text(stringResource(R.string.te_json_id_warning), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = com.ncthanhngo.pollmaster.ui.theme.warnColor())
        Button(
            enabled = check.error == null && text != original,
            onClick = { if (vm.edit(EditOp.ReplaceText(path, text))) vm.back() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.te_json_apply)) }
    }
}
