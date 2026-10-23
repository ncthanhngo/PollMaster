package com.ncthanhngo.pollmaster.core.format

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields

private val placeholder = Regex("\\{([^{}]+)\\}")
private val edgeChars = charArrayOf(' ', '–', '-', '/', '·')

/**
 * Title of a record from the template's `recordTitle` pattern, with separators trimmed from both ends.
 * Falls back to the template title when there is no pattern or every placeholder is empty.
 */
fun recordTitle(template: Template, record: Record): String {
    val pattern = template.recordTitle ?: return template.title
    val fields = template.allFields().associateBy { it.id }
    val formatter = ValueFormatter { record.values[it] }
    var anyFilled = false
    val title = placeholder.replace(pattern) { m ->
        val field = fields[m.groupValues[1]]
        val text = field?.let { formatter.display(it, record.values[it.id]) }.orEmpty()
        if (text.isNotEmpty()) anyFilled = true
        text
    }
    return if (anyFilled) title.trim(*edgeChars) else template.title
}
