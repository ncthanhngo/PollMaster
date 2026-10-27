package com.ncthanhngo.pollmaster.core.project

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** A template used by a project, referenced by id + version (schema 9.1). */
@Serializable
data class TemplateRef(val id: String, val version: String)

/** `<AppData>/projects/<projectId>/project.json` (schema 9.1). Reading is tolerant: only `id` is required. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Project(
    @EncodeDefault val schemaVersion: Int = 1,
    val id: String,
    val code: String = "",
    val name: String = "",
    val client: String? = null,
    val description: String? = null,
    val color: String? = null,
    /** ERP `project_type`: "company" or "internal". */
    val kind: String? = null,
    /** ERP avatar/logo URL the local `avatar.jpg` was downloaded from. */
    val logo: String? = null,
    @EncodeDefault val templates: List<TemplateRef> = emptyList(),
    @EncodeDefault val archived: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    companion object {
        /** ERP `project_type` values the app lists, in section order ("Dự án công ty", "Nội bộ"). */
        val KINDS = listOf("company", "internal")

        /** Identity colours offered in the project form, all dark enough for white initials (>= 4.5:1); same set as iOS. */
        val COLORS = listOf(
            "#1B6B5A", "#1F5FA8", "#7B4FA3", "#C2571A", "#B8312F", "#5F6B73",
            "#0E7490", "#A3367A", "#8A6D0B", "#3F4BA8",
        )

        /** Colour for a new project: the first palette colour no project uses yet, else the least used one. */
        fun nextColor(used: List<String>): String {
            val counts = used.groupingBy { it.uppercase() }.eachCount()
            return COLORS.minBy { counts[it.uppercase()] ?: 0 }
        }

        /** Up to two initials for the avatar: first letters of the first two words ("Khảo sát…" → "KS"). */
        fun initials(name: String): String {
            val letters = name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).map { it.first().uppercase() }
            return letters.joinToString("").ifEmpty { "?" }
        }
    }
}

/** The `{id, code, name}` triple exports carry about their project (schema 9.4). */
data class ProjectRef(val id: String, val code: String, val name: String) {
    /** `Name (CODE)`. */
    fun display(): String = "$name ($code)"
}

fun Project.ref() = ProjectRef(id, code, name)
