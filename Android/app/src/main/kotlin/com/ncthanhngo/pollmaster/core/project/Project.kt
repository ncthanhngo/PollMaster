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
    @EncodeDefault val templates: List<TemplateRef> = emptyList(),
    @EncodeDefault val archived: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val isDefault: Boolean get() = code == CHUNG_CODE

    companion object {
        /** Fixed id, code and name of the project that receives records created before projects existed (schema 9.2). */
        const val CHUNG_ID = "chung"
        const val CHUNG_CODE = "CHUNG"
        const val CHUNG_NAME = "Chung"

        /** The 6 colours offered in the project form. */
        val COLORS = listOf("#1B6B5A", "#1565C0", "#6A1B9A", "#C62828", "#EF6C00", "#455A64")
    }
}

/** The `{id, code, name}` triple exports carry about their project (schema 9.4). */
data class ProjectRef(val id: String, val code: String, val name: String) {
    /** `Name (CODE)`. */
    fun display(): String = "$name ($code)"
}

fun Project.ref() = ProjectRef(id, code, name)
