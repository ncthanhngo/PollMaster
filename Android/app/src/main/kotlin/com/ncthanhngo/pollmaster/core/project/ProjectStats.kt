package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.model.Record

/** Counts shown on project cards and in the project detail. */
data class ProjectStats(val draft: Int, val completed: Int, val returned: Int, val lastActivityAt: String?) {
    val total: Int get() = draft + completed

    companion object {
        /** Stats of [projectId]; `draft` includes the returned ones. */
        fun of(projectId: String, records: List<Record>): ProjectStats {
            val mine = records.filter { it.projectId == projectId }
            return ProjectStats(
                draft = mine.count { !it.isCompleted },
                completed = mine.count { it.isCompleted },
                returned = mine.count { it.returned && !it.isCompleted },
                lastActivityAt = mine.maxOfOrNull { it.updatedAt }?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
