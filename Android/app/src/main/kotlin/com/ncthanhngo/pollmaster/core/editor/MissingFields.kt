package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.findField
import com.ncthanhngo.pollmaster.core.model.findTable
import com.ncthanhngo.pollmaster.core.validation.Issue

/** One thing "Điền lần lượt" asks for: a field, a media field or a table (rows / a cell of a row). */
data class MissingStep(val issue: Issue, val kind: Kind) {
    enum class Kind { FIELD, MEDIA, TABLE }

    val key: String get() = issue.key
}

object MissingFields {

    /** Open issues as steps in template order (the validator lists fields before tables), one per issue key. */
    fun ordered(template: Template, issues: List<Issue>): List<MissingStep> {
        val order = HashMap<String, Int>()
        template.header.forEach { order[it.id] = order.size }
        template.sections.forEach { s -> s.items.forEach { order[it.id] = order.size } }
        return issues.distinctBy { it.key }
            .sortedBy { order[it.targetId] ?: Int.MAX_VALUE }
            .map { MissingStep(it, kindOf(template, it)) }
    }

    private fun kindOf(template: Template, issue: Issue): MissingStep.Kind = when {
        issue.rowId != null || template.findTable(issue.targetId) != null -> MissingStep.Kind.TABLE
        template.findField(issue.targetId)?.isMedia == true -> MissingStep.Kind.MEDIA
        else -> MissingStep.Kind.FIELD
    }

    /** First step that was not skipped, or null when everything left is skipped. */
    fun next(steps: List<MissingStep>, skipped: Set<String>): MissingStep? = steps.firstOrNull { it.key !in skipped }
}
