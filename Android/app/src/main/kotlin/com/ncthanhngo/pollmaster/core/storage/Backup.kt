package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.sanitizeFileName

/** Rules of the automatic backup (ux-spec A). */
object Backup {
    /** Shown to the user and used as the MediaStore relative path. */
    const val FOLDER = "Download/PollMaster"

    /** True when the record changed after its last backup (or was never backed up). */
    fun needsBackup(record: Record): Boolean {
        val backup = parseInstant(record.backupAt) ?: return true
        val updated = parseInstant(record.updatedAt) ?: return false
        return updated.isAfter(backup)
    }

    /**
     * After the zip of [backedUp] is written: stamp `backupAt` only if the record still exists unchanged ([current] has the same
     * `updatedAt` and project). Edited, moved or deleted meanwhile means the backup is stale, so nothing is written back.
     */
    fun shouldStamp(backedUp: Record, current: Record?): Boolean =
        current != null && current.updatedAt == backedUp.updatedAt && current.projectId == backedUp.projectId

    /** MediaStore relative path of one project's backups: `Download/PollMaster/{projectCode}/` (schema 9.4). */
    fun folder(projectCode: String?): String = if (projectCode.isNullOrEmpty()) FOLDER else "$FOLDER/$projectCode"

    /**
     * `{projectCode}_{recordCode}_{first 8 of record id}_backup.zip`, or `{projectCode}_{recordId}_backup.zip` without a code
     * (no project prefix when [projectCode] is null). Stable per record so a new backup overwrites the old one.
     */
    fun fileName(recordCode: String?, recordId: String, projectCode: String? = null): String {
        val prefix = projectCode?.takeIf { it.isNotEmpty() }?.let { "${it}_" }.orEmpty()
        val code = recordCode?.let(::sanitizeFileName)?.takeIf { it.isNotEmpty() } ?: return "$prefix${recordId}_backup.zip"
        return "$prefix${code}_${recordId.take(8)}_backup.zip"
    }
}
