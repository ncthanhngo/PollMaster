package com.ncthanhngo.pollmaster.ui.templateeditor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.PathKey
import com.ncthanhngo.pollmaster.core.templateeditor.arr
import com.ncthanhngo.pollmaster.core.templateeditor.docPath
import com.ncthanhngo.pollmaster.core.templateeditor.obj
import com.ncthanhngo.pollmaster.core.templateeditor.str
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
private fun pageTitle(vm: TemplateEditorViewModel): String {
    fun nodeTitle(path: List<PathKey>) = vm.text(path, "label").ifEmpty { vm.text(path, "title") }.ifEmpty { vm.text(path, "id") }
    val templateTitle = vm.text(emptyList(), "title").ifEmpty { vm.text(emptyList(), "id") }
    return when (val p = vm.page) {
        EditorPage.Index -> templateTitle
        EditorPage.Info -> stringResource(R.string.te_info)
        is EditorPage.Json -> stringResource(R.string.te_json_advanced)
        is EditorPage.Item -> nodeTitle(p.listPath + PathKey.Index(p.index))
        is EditorPage.Items -> when ((p.path.last() as PathKey.Key).name) {
            "header" -> stringResource(R.string.te_header)
            "signatures" -> stringResource(R.string.te_signatures)
            "items" -> nodeTitle(p.path.dropLast(1))
            else -> nodeTitle(p.path.dropLast(1))
        }
    }
}

/** The template editor: the index and the pages under it (forms, JSON), with preview and release. The draft saves itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(draftId: String, onBack: () -> Unit, onPreview: (recordId: String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: TemplateEditorViewModel = viewModel(key = draftId, factory = TemplateEditorViewModel.factory(container, draftId))
    var issuesOpen by rememberSaveable { mutableStateOf(false) }
    var publishing by rememberSaveable { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flush() }
    LaunchedEffect(vm) { DebugLaunch.takePath()?.let(vm::jump) }
    fun goBack() { if (!vm.back()) onBack() }
    BackHandler(onBack = ::goBack)

    val draft = vm.draft
    if (draft == null) {
        Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) }) { Text(stringResource(R.string.te_draft_missing), Modifier.padding(it).padding(24.dp)) }
        return
    }

    fun preview() {
        if (vm.errors.isNotEmpty()) { issuesOpen = true; return }
        val template = runCatching { TemplateParser.parse(vm.doc!!.pretty()) }.getOrNull()
        if (template == null) { issuesOpen = true; return }
        vm.flush()
        onPreview(container.records.openPreview(template, context.cacheDir))
    }

    val targets = if (draft.baseId == null) container.templateDrafts.publishTargets() else container.templateDrafts.managedProjects(draft.templateId)
    val mayPublish = targets.any { container.templatePublisher.canPublish(it) }
    val canTap = vm.errors.isEmpty() && mayPublish

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle(vm), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(::goBack) },
                actions = { IssuesChip(vm.issues, { issuesOpen = true }, Modifier.padding(end = 12.dp)) },
            )
        },
        bottomBar = {
            if (vm.page == EditorPage.Index) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = ::preview, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.te_preview), maxLines = 1) }
                        Button(
                            onClick = {
                                when {
                                    vm.errors.isNotEmpty() -> issuesOpen = true
                                    !mayPublish -> Toast.makeText(context, context.getString(R.string.te_publish_needs), Toast.LENGTH_SHORT).show()
                                    else -> publishing = true
                                }
                            },
                            modifier = Modifier.weight(1f).alpha(if (canTap) 1f else 0.45f),
                        ) { Text(stringResource(R.string.te_publish), maxLines = 1) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (val p = vm.page) {
                EditorPage.Index -> IndexPage(vm, onDiscard = { discarding = true })
                EditorPage.Info -> TemplateInfoForm(vm)
                is EditorPage.Items -> ItemsPage(vm, p.path)
                is EditorPage.Item -> ItemPage(vm, p.listPath, p.index)
                is EditorPage.Json -> JsonNodeEditor(vm, p.path)
            }
        }
    }

    if (issuesOpen) IssuesSheet(vm.issues, onJump = { issuesOpen = false; vm.jump(it.path) }, onDismiss = { issuesOpen = false })
    if (publishing) {
        PublishDialog(
            vm, onDismiss = { publishing = false }, onDone = { publishing = false; onBack() },
            onShowIssues = { issuesOpen = true }, onTakenId = { vm.jump("id") },
        )
    }
    if (discarding) {
        ConfirmDialog(
            title = stringResource(R.string.te_discard_title), message = stringResource(R.string.te_discard_message),
            confirmLabel = stringResource(R.string.te_discard_draft),
            onConfirm = { scope.launch { vm.discard(); onBack() } },
            onDismiss = { discarding = false },
        )
    }
}

/** Table of contents: the template's info, header, sections (↑ ↓ ⋯), signatures, then the JSON of the whole template. */
@Composable
private fun IndexPage(vm: TemplateEditorViewModel, onDiscard: () -> Unit) {
    val root = emptyList<PathKey>()
    val sectionsPath = docPath("sections")
    val header = vm.node(docPath("header")).arr()?.size ?: 0
    val signatures = vm.node(docPath("signatures")).arr()?.size ?: 0
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NavRow(stringResource(R.string.te_info)) { vm.open(EditorPage.Info) }
        NavRow(stringResource(R.string.te_header) + " · " + stringResource(R.string.te_count_fields, header)) { vm.open(EditorPage.Items(docPath("header"))) }
        SmallTitle(stringResource(R.string.te_sections), Modifier.padding(top = 4.dp))
        NodeList(vm, sectionsPath, duplicable = false, onOpen = { vm.open(EditorPage.Items(docPath("sections", it, "items"))) }) { s ->
            val id = s["id"].str().orEmpty()
            RowText(s["title"].str().orEmpty().ifEmpty { id }, "$id · " + stringResource(R.string.te_count_elements, s["items"].arr()?.size ?: 0))
        }
        OutlinedButton(onClick = {
            val index = vm.node(sectionsPath).arr()?.size ?: 0
            if (vm.edit(EditOp.NewItem(sectionsPath, null, "section", null))) vm.open(EditorPage.Items(docPath("sections", index, "items")))
        }) { Text(stringResource(R.string.te_add_section), maxLines = 1) }
        NavRow(stringResource(R.string.te_signatures) + " · " + stringResource(R.string.te_count_elements, signatures)) { vm.open(EditorPage.Items(docPath("signatures"))) }
        OutlinedButton(onClick = { vm.open(EditorPage.Json(root)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.te_json_all), maxLines = 1) }
        TextButton(onClick = onDiscard) { Text(stringResource(R.string.te_discard_draft), color = MaterialTheme.colorScheme.error) }
        vm.editError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
