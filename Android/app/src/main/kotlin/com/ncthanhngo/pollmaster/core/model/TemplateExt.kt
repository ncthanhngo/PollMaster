package com.ncthanhngo.pollmaster.core.model

/** Header fields followed by every top-level field in sections (table columns excluded). */
fun Template.allFields(): List<Field> =
    header + sections.flatMap { s -> s.items.filterIsInstance<Field>() }

fun Template.tables(): List<TableItem> = sections.flatMap { it.items.filterIsInstance<TableItem>() }

fun Template.findField(id: String): Field? = allFields().firstOrNull { it.id == id }

fun Template.findTable(id: String): TableItem? = tables().firstOrNull { it.id == id }

fun Template.sectionOf(itemId: String): Section? =
    sections.firstOrNull { s -> s.items.any { it.id == itemId } }

/** Number of input fields (header + sections + table columns) used for the template list. */
fun Template.fieldCount(): Int = allFields().size + tables().sumOf { it.columns.size }

/** Record code (role = recordCode) made safe for use in file names. */
fun Template.recordCode(record: Record): String? {
    val field = header.firstOrNull { it.role == "recordCode" } ?: allFields().firstOrNull { it.role == "recordCode" }
    val raw = field?.let { record.values[it.id]?.v.asText() }?.trim().orEmpty()
    return raw.takeIf { it.isNotEmpty() }?.let(::sanitizeFileName)
}

fun sanitizeFileName(raw: String): String =
    raw.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "-").trim('-')
