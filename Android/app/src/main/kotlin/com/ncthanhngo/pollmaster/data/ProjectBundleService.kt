package com.ncthanhngo.pollmaster.data

import android.content.Context
import android.net.Uri
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.project.BundleContents
import com.ncthanhngo.pollmaster.core.project.BundleException
import com.ncthanhngo.pollmaster.core.project.ImportOutcome
import com.ncthanhngo.pollmaster.core.project.ImportPlan
import com.ncthanhngo.pollmaster.core.project.ProjectBundle
import com.ncthanhngo.pollmaster.core.project.ProjectImporter
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Reads, previews and applies `.pmproject` packages; writes them for sharing (schema 9.3). */
class ProjectBundleService(
    private val context: Context,
    private val projects: ProjectsRepository,
    private val templateStore: TemplateStore,
    private val templates: TemplatesRepository,
) {
    /** Reads and validates the package behind [uri]; throws [BundleException] listing every problem. */
    suspend fun read(uri: Uri): BundleContents = withContext(Dispatchers.IO) {
        val bytes = try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw BundleException(listOf("Không mở được tệp"))
            stream.use { readUpTo(it, MAX_BYTES + 1) }
        } catch (e: IOException) {
            throw BundleException(listOf("Không đọc được tệp: ${e.message.orEmpty()}"))
        } catch (e: SecurityException) {
            throw BundleException(listOf("Không có quyền đọc tệp"))
        }
        if (bytes.size > MAX_BYTES) throw BundleException(listOf("Tệp quá lớn (> 50 MB)"))
        ProjectBundle.read(bytes)
    }

    suspend fun plan(contents: BundleContents): ImportPlan = withContext(Dispatchers.IO) {
        ProjectImporter.plan(contents, projects.store, templateStore)
    }

    suspend fun import(contents: BundleContents, overwriteTemplates: Boolean): ImportOutcome {
        val outcome = withContext(Dispatchers.IO) {
            ProjectImporter.apply(contents, projects.store, templateStore, overwriteTemplates, nowIso())
        }
        templates.refresh()
        projects.refresh()
        return outcome
    }

    /** Writes `{code}.pmproject` to the cache export folder; templates missing from the library are left out. */
    suspend fun export(projectId: String): File = withContext(Dispatchers.IO) {
        val project = projects.store.get(projectId) ?: error("Không tìm thấy dự án")
        val json = project.templates.mapNotNull { ref -> templateStore.raw(ref.id, ref.version)?.let { ref to it } }.toMap()
        val kept = project.copy(templates = json.keys.toList())
        val dir = File(context.cacheDir, "exports").also { it.deleteRecursively(); it.mkdirs() }
        File(dir, ProjectBundle.fileName(project.code)).also { file ->
            file.outputStream().buffered().use { ProjectBundle.write(kept, json, projects.store.sites(projectId).load(), it) }
        }
    }

    /** At most [limit] bytes, so a huge file is rejected without being loaded whole. */
    private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BYTES = 50 * 1024 * 1024
    }
}
