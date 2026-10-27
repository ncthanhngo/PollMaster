package com.ncthanhngo.pollmaster.core.format

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template

/**
 * "01 - {template title} dd/MM/yyyy": the record's number among its project's records of that template, then the creation day.
 * Records made before numbering have no number and show only the template title and day.
 */
fun recordTitle(template: Template, record: Record): String =
    listOfNotNull(record.seq?.toString()?.padStart(2, '0'), "${template.title} ${displayDayOf(record.createdAt)}").joinToString(" - ")

/** Number for a new record of [templateId] in [projectId]: one above the highest so far (numbers are never reused). */
fun nextRecordSeq(records: List<Record>, projectId: String?, templateId: String): Int =
    (records.filter { it.projectId == projectId && it.templateId == templateId }.mapNotNull { it.seq }.maxOrNull() ?: 0) + 1
