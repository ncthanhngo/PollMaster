package com.ncthanhngo.pollmaster.core.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** One row of ERP `GET /projects` (only what the app keeps). */
@Serializable
data class ErpProject(
    val id: String,
    val code: String = "",
    val name: String = "",
    @SerialName("short_name") val shortName: String? = null,
    /** "company", "internal" or "personal". */
    @SerialName("project_type") val type: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    /** Effective avatar: the project's own photo, else the customer logo; may be relative (`/uploads/…`). */
    @SerialName("customer_logo_url") val logo: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    /** The Phoenix project gets the templates already on the device when it first arrives (owner's decision). */
    val startsWithLibrary: Boolean get() = listOfNotNull(code, name, shortName).any { it.contains("phoenix", ignoreCase = true) }

    /** Name shown in the app: the short name when the ERP has one (same rule as the ERP mobile app). */
    val displayName: String get() = shortName?.trim()?.takeIf { it.isNotEmpty() } ?: name.ifEmpty { code }
}

/** `{data: {items, total}}` of a paged ERP list. */
@Serializable
data class ErpPage<T>(val items: List<T> = emptyList(), val total: Int = 0)

/** `{data, error}` envelope of every ERP response. */
@Serializable
data class ErpEnvelope<T>(val data: T? = null, val error: String? = null)

/** A project whose avatar must be downloaded again ([url]) or removed (null). */
data class LogoChange(val projectId: String, val url: String?)

/** Projects come from the ERP: the device keeps one folder per ERP project (its templates, sites, colour, records). */
object ErpProjectSync {
    /**
     * Writes the ERP list into the store: ERP fields refreshed, device fields (templates, colour, sites) kept, new projects get the
     * next free colour; personal projects and projects no longer listed are archived (hidden, records kept). [library] (newest version
     * of each template) goes to a new project that [ErpProject.startsWithLibrary].
     */
    fun apply(list: List<ErpProject>, store: ProjectStore, now: String, library: List<TemplateRef> = emptyList()): List<LogoChange> {
        val listed = list.filter { it.type in Project.KINDS && isSafeId(it.id) } // the id becomes a folder name
        val ids = listed.map { it.id }.toSet()
        val all = store.list().toMutableList()
        all.filter { it.id !in ids && !it.archived }.forEach { store.save(it.copy(archived = true)) }
        val changes = mutableListOf<LogoChange>()
        for (e in listed) {
            val old = all.firstOrNull { it.id == e.id }
            val base = old ?: Project(
                id = e.id, color = Project.nextColor(all.mapNotNull { it.color }), templates = if (e.startsWithLibrary) library else emptyList(),
                createdAt = now, updatedAt = now,
            )
            val logo = e.logo?.takeIf { it.isNotEmpty() }
            if (logo != base.logo || (logo != null && store.avatarFile(e.id) == null)) changes += LogoChange(e.id, logo)
            val code = ProjectCodes.normalize(e.code)
            val p = base.copy(
                code = code.ifEmpty { ProjectCodes.fromName(e.displayName) },
                name = e.displayName,
                client = e.customerName?.trim()?.takeIf { it.isNotEmpty() },
                kind = e.type,
                logo = logo,
                archived = false,
                createdAt = e.createdAt ?: base.createdAt,
            )
            if (p != old) store.save(p.copy(updatedAt = now))
            if (old == null) all += p
        }
        return changes
    }

    private val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")

    /** An ERP project id is used as a folder name, so only letters, digits and `-` (1-64) are accepted. */
    fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    /** Absolute URL of an ERP file path: uploads are served from the API host root, outside `/api/v1`. */
    fun fileUrl(path: String, host: String): String = if (path.startsWith("http")) path else host.trimEnd('/') + "/" + path.trimStart('/')

    /** Folders of records and projects on the device (what a first-login wipe would delete), as (records, projects). */
    fun localDataCounts(root: File): Pair<Int, Int> =
        listOf("records", "projects").map { File(root, it).listFiles { f -> f.isDirectory }?.size ?: 0 }.let { it[0] to it[1] }

    /** One-time wipe when the app switches to ERP projects: projects and records made on the device go, templates stay. */
    fun resetLocalData(root: File) {
        listOf("projects", "records").forEach { File(root, it).deleteRecursively() }
    }
}
