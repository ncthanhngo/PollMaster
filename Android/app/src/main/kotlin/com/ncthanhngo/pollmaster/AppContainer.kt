package com.ncthanhngo.pollmaster

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.sites.SitesStore
import com.ncthanhngo.pollmaster.data.BackupService
import com.ncthanhngo.pollmaster.data.ExportService
import com.ncthanhngo.pollmaster.data.SitesRepository
import com.ncthanhngo.pollmaster.data.RecordsRepository
import com.ncthanhngo.pollmaster.data.Settings
import com.ncthanhngo.pollmaster.data.TemplatesRepository
import com.ncthanhngo.pollmaster.platform.MediaProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide wiring of stores and repositories. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = Settings(context)
    val recordStore = RecordStore(context.filesDir)
    val templateStore = TemplateStore(context.filesDir)
    val templates = TemplatesRepository(context, templateStore, recordStore, settings)
    val records = RecordsRepository(scope, recordStore, templates, settings)
    val sites = SitesRepository(SitesStore(context.filesDir))
    val backup = BackupService(context, scope, recordStore, templates, records, settings)
    init {
        records.onBackupTick = backup::requestIfNeeded
    }
    val export = ExportService(context, recordStore, templates, records)
    val media = MediaProcessor(context, settings)
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
