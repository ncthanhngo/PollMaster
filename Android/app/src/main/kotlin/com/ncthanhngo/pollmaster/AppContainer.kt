package com.ncthanhngo.pollmaster

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.data.BackupService
import com.ncthanhngo.pollmaster.data.ErpClient
import com.ncthanhngo.pollmaster.data.ErpSession
import com.ncthanhngo.pollmaster.data.SyncService
import com.ncthanhngo.pollmaster.data.ExportService
import com.ncthanhngo.pollmaster.data.ProjectBundleService
import com.ncthanhngo.pollmaster.data.ProjectExportService
import com.ncthanhngo.pollmaster.data.ProjectsRepository
import com.ncthanhngo.pollmaster.data.SitesRepository
import com.ncthanhngo.pollmaster.data.RecordsRepository
import com.ncthanhngo.pollmaster.data.Settings
import com.ncthanhngo.pollmaster.data.TemplatesRepository
import com.ncthanhngo.pollmaster.data.UpdateChecker
import com.ncthanhngo.pollmaster.platform.MediaProcessor
import com.ncthanhngo.pollmaster.platform.isOnline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide wiring of stores and repositories. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(context)
    val recordStore = RecordStore(context.filesDir)
    val templateStore = TemplateStore(context.filesDir)
    val projectStore = ProjectStore(context.filesDir)
    val templates = TemplatesRepository(context, templateStore, recordStore, settings, projectStore)
    val sites = SitesRepository(projectStore)
    val projects = ProjectsRepository(projectStore, sites, settings)
    val records: RecordsRepository = RecordsRepository(
        scope, recordStore, templates, settings, visibleProjects = { projects.projects.value.map { it.id }.toSet() },
        me = { erp.account.value?.userId }, projectOf = projects::get,
    )
    val backup = BackupService(context, scope, recordStore, templates, records, settings, projects)
    init {
        records.onBackupTick = backup::requestIfNeeded
        templates.onReplaced = records::releaseUsing
    }
    val export = ExportService(context, recordStore, templates, records, projects)
    val projectExport = ProjectExportService(context, projects, recordStore, templates, records)
    val bundles = ProjectBundleService(context, projects, templateStore, templates)
    /** Package the user opened from another app, waiting for the preview dialog. */
    val pendingBundle = MutableStateFlow<Uri?>(null)
    val media = MediaProcessor(context, settings)
    val updates = UpdateChecker(context)
    private val erpClient = ErpClient(context)
    val erp: ErpSession = ErpSession(context, context.filesDir, erpClient, projects, records, templates, media, backup, scope)
    val sync = SyncService(context, context.filesDir, erpClient, erp, projects, records, templates, backup, scope) { isOnline(context) }

    init {
        erp.onProjectsSynced = { sync.request() }
        erp.onLogout = sync::cancel
        records.onCreated = { sync.request(it.projectId) }
        records.onDeleted = sync::queueRemoteDelete
        records.onMoved = { old, new -> sync.queueRemoteDelete(old); sync.request(new.projectId) }
        records.onEditorOpened = sync::fetchMediaOnOpen
        // network back after being gone: project list and records are fetched again
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { erp.sync() } }
        })
    }
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
