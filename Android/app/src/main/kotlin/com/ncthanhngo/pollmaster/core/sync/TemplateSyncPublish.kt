package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.TemplateText
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDoc
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraft
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateEdit
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateIssue
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateLint
import com.ncthanhngo.pollmaster.core.templateeditor.TemplatePublishRules
import com.ncthanhngo.pollmaster.core.templateeditor.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A project that did not take the new version (the main release stands). */
data class PublishFailure(val projectId: String, val message: String)

sealed interface PublishOutcome {
    /** [version] is the one now assigned (the base's for a name/colour-only change). */
    data class Published(val version: String, val failedProjects: List<PublishFailure>) : PublishOutcome

    /** The target project holds another version than the draft started from: ask, then call again with `force`. */
    data class StaleBase(val serverVersion: String) : PublishOutcome

    /** Errors that block publishing. */
    data class Invalid(val issues: List<TemplateIssue>) : PublishOutcome

    /** A new template whose code the organisation already has. */
    data object IdTaken : PublishOutcome
}

private const val MAX_VERSION_TRIES = 5

private fun baseDoc(sync: TemplateSync, d: TemplateDraft): TemplateDoc? {
    val id = d.baseId ?: return null
    val v = d.baseVersion ?: return null
    return sync.templates.raw(id, v)?.let { runCatching { TemplateDoc.of(it) }.getOrNull() }
}

private fun withVersion(doc: TemplateDoc, version: String): TemplateDoc = TemplateEdit.apply(doc, EditOp.Set(emptyList(), "version", JsonPrimitive(version)))

private fun errors(d: TemplateDraft, base: TemplateDoc?) = TemplateLint.check(d.doc, base, d.baseId == null).filter { it.severity == TemplateIssue.Severity.ERROR }

/**
 * Releases a draft into [projectId] through the server (`template-schema.md` 11.2); [also] are other managed projects moved to the new version.
 * Throws what the network or the server refuses (the draft keeps its `publishVersion`, so sending again repeats the same release).
 */
suspend fun TemplateSync.publish(draft: TemplateDraft, projectId: String, also: List<String> = emptyList(), force: Boolean = false): PublishOutcome {
    val doc = draft.doc
    val base = baseDoc(this, draft)
    errors(draft, base).takeIf { it.isNotEmpty() }?.let { return PublishOutcome.Invalid(it) }
    val id = doc.id ?: draft.templateId
    val info = remote.list(projectId) ?: throw TemplateSyncFailure("Máy chủ chưa hỗ trợ mẫu theo dự án.")
    if (!info.canManage) throw TemplateSyncFailure("Cần quyền quản lý dự án và có mạng.")
    val assigned = info.assigned.firstOrNull { it.templateId == id }?.version
    if (draft.baseId != null && !force && draft.publishVersion == null && assigned != null && assigned != draft.baseVersion) return PublishOutcome.StaleBase(assigned)
    val kind = if (draft.baseId == null) TemplatePublishRules.Kind.NEW_TEMPLATE else TemplatePublishRules.publishKind(base, doc)

    var d = draft
    var version = draft.baseVersion ?: "1.0"
    when (kind) {
        TemplatePublishRules.Kind.META -> {
            val o = doc.root as? JsonObject
            remote.patch(projectId, id, null, o?.get("title").str().orEmpty(), o?.get("shortName").str().orEmpty(), o?.get("color").str().orEmpty())
        }
        TemplatePublishRules.Kind.NEW_TEMPLATE -> {
            version = "1.0"
            try { remote.add(projectId, withVersion(doc, version).root) } catch (_: TemplateVersionExists) { return PublishOutcome.IdTaken }
        }
        TemplatePublishRules.Kind.NEW_VERSION -> {
            val known = templates.list().filter { it.id == id }.map { it.version } + info.assigned.filter { it.templateId == id }.map { it.version } +
                info.templates.filter { it.templateId == id }.map { it.version }
            version = draft.publishVersion ?: TemplatePublishRules.nextVersion(known)
            var posted = false
            var tries = 0
            while (!posted && tries++ < MAX_VERSION_TRIES) {
                d = d.copy(publishVersion = version)
                drafts.save(d)
                try { remote.add(projectId, withVersion(doc, version).root); posted = true } catch (_: TemplateVersionExists) { version = TemplatePublishRules.bumpMinor(version) }
            }
            if (!posted) throw TemplateSyncFailure("Không cấp được số phiên bản mới cho mẫu. Thử lại sau.")
        }
    }
    drafts.delete(draft.draftId)

    val failed = ArrayList<PublishFailure>()
    val touched = mutableListOf(projectId)
    if (kind != TemplatePublishRules.Kind.META) {
        for (pid in also.filter { it != projectId }) {
            try { remote.patch(pid, id, version, null, null, null); touched += pid } catch (e: CancellationException) { throw e } catch (e: Exception) {
                failed += PublishFailure(pid, e.message.orEmpty())
            }
        }
    }
    for (pid in touched) { try { refresh(pid) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the library catches up on the next sync */ } }
    return PublishOutcome.Published(version, failed)
}

/** Signed out: the release goes into this device's library and [projectId] (no network). */
fun TemplateSync.publishLocally(draft: TemplateDraft, projectId: String): PublishOutcome {
    val doc = draft.doc
    val base = baseDoc(this, draft)
    errors(draft, base).takeIf { it.isNotEmpty() }?.let { return PublishOutcome.Invalid(it) }
    val id = doc.id ?: draft.templateId
    val kind = if (draft.baseId == null) TemplatePublishRules.Kind.NEW_TEMPLATE else TemplatePublishRules.publishKind(base, doc)
    var version = draft.baseVersion ?: "1.0"
    when (kind) {
        TemplatePublishRules.Kind.META -> {
            val o = doc.root as? JsonObject
            for (t in templates.list().filter { it.id == id }) {
                val raw = templates.raw(id, t.version) ?: continue
                templates.install(TemplateText.edited(raw, o?.get("title").str().orEmpty(), o?.get("shortName").str(), o?.get("color").str()), overwrite = true)
            }
        }
        else -> {
            version = if (kind == TemplatePublishRules.Kind.NEW_TEMPLATE) "1.0" else TemplatePublishRules.nextVersion(templates.list().filter { it.id == id }.map { it.version })
            templates.install(withVersion(doc, version).pretty(), overwrite = true)
            val ref = TemplateRef(id, version)
            val p = projects.get(projectId) ?: throw TemplateSyncFailure("Không tìm thấy dự án.")
            val list = if (kind == TemplatePublishRules.Kind.NEW_TEMPLATE) (p.templates + ref).distinct() else p.templates.map { if (it.id == id) ref else it }
            projects.save(p.copy(templates = list, updatedAt = nowIso()))
        }
    }
    drafts.delete(draft.draftId)
    return PublishOutcome.Published(version, emptyList())
}

/** The newest version older than [ref] that this device has ("Quay lại bản trước"); null when none. */
fun TemplateSync.previousVersion(ref: TemplateRef): TemplateRef? =
    TemplatePublishRules.previousVersion(ref.version, templates.list().filter { it.id == ref.id }.map { it.version })?.let { TemplateRef(ref.id, it) }

/** Switches [projectId] back to the previous version of a template (`useVersion` of [previousVersion]). */
suspend fun TemplateSync.rollBack(ref: TemplateRef, projectId: String) {
    val previous = previousVersion(ref) ?: throw TemplateSyncFailure("Mẫu này chưa có bản trước trên máy.")
    useVersion(previous, projectId)
}
