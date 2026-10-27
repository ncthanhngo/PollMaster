package com.ncthanhngo.pollmaster.data

import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.sync.PublishOutcome
import com.ncthanhngo.pollmaster.core.sync.TemplateRemote
import com.ncthanhngo.pollmaster.core.sync.TemplateSync
import com.ncthanhngo.pollmaster.core.sync.publish
import com.ncthanhngo.pollmaster.core.sync.publishLocally
import com.ncthanhngo.pollmaster.core.sync.rollBack
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraft
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Releasing a draft and switching a project back to the previous version of a template (schema 11.2). Errors come back as a sentence. */
class TemplatePublisher(
    private val root: File,
    private val remote: TemplateRemote,
    private val session: ErpSession,
    private val templates: TemplatesRepository,
    private val projects: ProjectsRepository,
    private val drafts: TemplateDraftsRepository,
    private val online: () -> Boolean,
) {
    /** [outcome] when the call finished (inspect it); else [error] is the sentence to show. */
    class Result(val outcome: PublishOutcome? = null, val error: String? = null)

    private fun sync() = TemplateSync(ProjectStore(root), TemplateStore(root), remote, TemplateDraftStore(root))

    /** May the signed-in user release into [project] now? (nobody is signed in: the device list is free). */
    fun canPublish(project: Project?): Boolean = session.account.value == null || (project?.canManage == true && online())

    private fun failure(e: Exception): String = when {
        e is ErpException -> { if (e.sessionExpired) session.expire(); e.message.orEmpty() }
        e is IOException -> "Không kết nối được máy chủ. Thử lại khi có mạng."
        else -> e.message ?: "Lỗi không rõ."
    }

    private suspend fun reloadAll() { templates.refresh(); projects.refresh(); drafts.refresh() }

    /** Releases [d] into [target] and moves [also] along; signed out it is local. Needs the network and `canManage` when signed in. */
    suspend fun publish(d: TemplateDraft, target: Project, also: List<String>, force: Boolean): Result {
        val result = try {
            withContext(Dispatchers.IO) {
                if (session.account.value == null) Result(sync().publishLocally(d, target.id))
                else if (!canPublish(target)) Result(error = SyncService.TEMPLATE_NEEDS_MANAGE + ".")
                else Result(sync().publish(d, target.id, also, force))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(error = failure(e))
        }
        reloadAll()
        return result
    }

    /** "Quay lại bản trước": [project] uses the previous version of [ref] (through the server when signed in). Returns the error sentence, null when done. */
    suspend fun rollBack(project: Project, ref: TemplateRef): String? {
        val previous = drafts.previousVersion(ref) ?: return "Mẫu này chưa có bản trước trên máy."
        if (session.account.value == null) {
            projects.setTemplates(project.id, project.templates.map { if (it.id == ref.id) previous else it })
            return null
        }
        if (!canPublish(project)) return SyncService.TEMPLATE_NEEDS_MANAGE + "."
        val error = try {
            withContext(Dispatchers.IO) { sync().rollBack(ref, project.id) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e)
        }
        reloadAll()
        return error
    }
}
