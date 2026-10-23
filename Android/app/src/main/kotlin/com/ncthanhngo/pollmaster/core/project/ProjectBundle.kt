package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.SitesCsv
import com.ncthanhngo.pollmaster.core.storage.TemplateException
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A template carried by a bundle: its parsed form and the original JSON text. */
data class BundleTemplate(val template: Template, val json: String)

/** Parsed, validated content of a `.pmproject`. [sites] is null when the bundle has no `sites.csv`. */
data class BundleContents(val project: Project, val templates: List<BundleTemplate>, val sites: List<Site>?)

/** Every problem found in a bundle, so the user can copy the whole list. */
class BundleException(val errors: List<String>) : Exception(errors.joinToString("\n"))

/** `.pmproject`: a zip with `project.json`, `templates/<id>@<version>.json` and an optional `sites.csv` (schema 9.3). */
object ProjectBundle {
    const val EXTENSION = "pmproject"
    private const val PROJECT = "project.json"
    private const val SITES = "sites.csv"
    private const val TEMPLATES = "templates/"
    private const val MAX_ENTRY_BYTES = 20 * 1024 * 1024
    private const val MAX_ENTRIES = 500
    private const val BOM = "\uFEFF"

    fun fileName(projectCode: String): String = "$projectCode.$EXTENSION"

    fun sitesCsv(sites: List<Site>): String = BOM + "code,address,lat,lng\n" + sites.joinToString("") {
        listOf(it.code, it.address, it.lat?.toString().orEmpty(), it.lng?.toString().orEmpty()).joinToString(",") { c -> csvCell(c) } + "\n"
    }

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Writes [project] with the JSON text of each referenced template (keyed by ref) and, when not null, its sites. */
    fun write(project: Project, templateJson: Map<TemplateRef, String>, sites: List<Site>?, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(PROJECT, PollJson.encodeToString(Project.serializer(), project))
            templateJson.forEach { (ref, json) -> put("$TEMPLATES${safe(ref.id)}@${safe(ref.version)}.json", json) }
            if (!sites.isNullOrEmpty()) put(SITES, sitesCsv(sites))
        }
    }

    /** Validates by content (never by file name): a zip with a readable `project.json`, valid templates and sites. */
    fun read(bytes: ByteArray): BundleContents {
        val errors = mutableListOf<String>()
        var projectText: String? = null
        var sitesText: String? = null
        val templateTexts = linkedMapOf<String, String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++count > MAX_ENTRIES) throw BundleException(listOf("Gói có quá nhiều tệp"))
                    val name = entry.name
                    val wanted = name == PROJECT || name == SITES || (name.startsWith(TEMPLATES) && name.endsWith(".json") && name.count { it == '/' } == 1)
                    if (!wanted) continue
                    val text = readLimited(zip, name)
                    when {
                        name == PROJECT -> projectText = text
                        name == SITES -> sitesText = text
                        else -> templateTexts[name] = text
                    }
                }
            }
        } catch (e: BundleException) {
            throw e
        } catch (e: java.io.IOException) { // not a zip, truncated or corrupt
            throw BundleException(listOf("Không phải gói dự án PollMaster (${e.message ?: "tệp zip lỗi"})"))
        }
        val projectJson = projectText ?: throw BundleException(listOf("Không phải gói dự án PollMaster: thiếu project.json"))
        val project = try {
            PollJson.decodeFromString(Project.serializer(), projectJson)
        } catch (e: Exception) {
            throw BundleException(listOf("project.json: ${e.message?.lineSequence()?.first().orEmpty()}"))
        }
        if (project.name.isBlank()) errors += "project.json: thiếu name"
        if (project.id.isBlank()) errors += "project.json: thiếu id"
        if (!ProjectCodes.isValid(project.code.uppercase())) errors += "project.json: code không hợp lệ \"${project.code}\""
        val templates = templateTexts.mapNotNull { (name, json) ->
            try {
                BundleTemplate(TemplateParser.parse(json), json)
            } catch (e: TemplateException) {
                errors += "$name: ${e.message}"
                null
            }
        }
        val sites = sitesText?.let { text ->
            try {
                SitesCsv.parse(text)
            } catch (e: Exception) {
                errors += "sites.csv: ${e.message}"
                null
            }
        }
        if (errors.isNotEmpty()) throw BundleException(errors)
        return BundleContents(project.copy(code = project.code.uppercase()), templates, sites)
    }

    private fun readLimited(zip: ZipInputStream, name: String): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = zip.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
            if (buffer.size() > MAX_ENTRY_BYTES) throw BundleException(listOf("$name quá lớn"))
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
