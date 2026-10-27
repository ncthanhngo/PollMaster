package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.storage.compareVersions
import kotlinx.serialization.json.JsonObject

/** What publishing a draft does on the server (`template-schema.md` 11.2). */
object TemplatePublishRules {
    enum class Kind {
        /** Only title, short name or colour differ from the base: PATCH, no new version. */
        META,
        NEW_VERSION,
        NEW_TEMPLATE,
    }

    private val metaKeys = listOf("title", "shortName", "color")

    fun publishKind(base: TemplateDoc?, draft: TemplateDoc): Kind {
        if (base == null) return Kind.NEW_TEMPLATE
        fun stripped(d: TemplateDoc) = (d.root as? JsonObject)?.let { JsonObject(it - metaKeys.toSet()) }
        val a = stripped(base)
        val b = stripped(draft)
        return if (a != null && b != null && semanticEquals(a, b)) Kind.META else Kind.NEW_VERSION
    }

    /** "1.9" -> "1.10", "2" -> "2.1", "1.0-beta" -> "1.0-beta.1". */
    fun bumpMinor(v: String): String {
        val parts = v.split('.')
        val n = parts.last().toIntOrNull()
        if (parts.size < 2 || n == null) return "$v.1"
        return (parts.dropLast(1) + (n + 1).toString()).joinToString(".")
    }

    /** The version a new release gets: the newest known one bumped; "1.0" when none is known. */
    fun nextVersion(known: List<String>): String = known.maxWithOrNull { a, b -> compareVersions(a, b) }?.let(::bumpMinor) ?: "1.0"

    /** Newest of [known] that is older than [current] ("Quay lại bản trước"); null when there is none. */
    fun previousVersion(current: String, known: List<String>): String? =
        known.filter { compareVersions(current, it) > 0 }.maxWithOrNull { a, b -> compareVersions(a, b) }

    /** Projects the user manages (not archived) that use template [id] (any version); null [id] = every managed project. */
    fun manageable(id: String?, all: List<Project>): List<Project> =
        all.filter { !it.archived && it.canManage && (id == null || it.templates.any { t -> t.id == id }) }

    /** Managed projects on [id] except [target]: the ones a release can move along (ticked by default). */
    fun otherProjects(id: String, target: String?, all: List<Project>): List<Project> = manageable(id, all).filter { it.id != target }
}
