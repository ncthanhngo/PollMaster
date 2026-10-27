package com.ncthanhngo.pollmaster.core.model

import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID

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

/** Record code (role = recordCode) exactly as typed, or null when empty. */
fun Template.rawRecordCode(record: Record): String? {
    val field = header.firstOrNull { it.role == "recordCode" } ?: allFields().firstOrNull { it.role == "recordCode" }
    return field?.let { record.values[it.id]?.v.asText() }?.trim()?.takeIf { it.isNotEmpty() }
}

/** Record code (role = recordCode) made safe for use in file names. */
fun Template.recordCode(record: Record): String? = rawRecordCode(record)?.let(::sanitizeFileName)

fun sanitizeFileName(raw: String): String =
    raw.replace(Regex("[/\\\\:*?\"<>|\\s]+"), "-").trim('-')

/** Repeating table paired with [tableId] (either side of `pairedWith`), or null. Fixed-row tables are never paired. */
fun Template.pairedTable(tableId: String): TableItem? {
    val table = findTable(tableId) ?: return null
    val other = table.pairedWith?.let(::findTable) ?: tables().firstOrNull { it.pairedWith == tableId }
    return other?.takeIf { !it.isFixed && !table.isFixed && it.id != tableId }
}

/** Fields (header, then section fields) whose id is a section's item list: the header pseudo-section or a real one. */
fun Template.itemsOf(sectionId: String): List<Item> =
    if (sectionId == HEADER_SECTION_ID) header else sections.firstOrNull { it.id == sectionId }?.items.orEmpty()
