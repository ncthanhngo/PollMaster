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

    /** `{recordCode}_{first 8 of record id}_backup.zip`, or `{recordId}_backup.zip` without a code. Stable per record so a new backup overwrites the old one. */
    fun fileName(recordCode: String?, recordId: String): String {
        val code = recordCode?.let(::sanitizeFileName)?.takeIf { it.isNotEmpty() } ?: return "${recordId}_backup.zip"
        return "${code}_${recordId.take(8)}_backup.zip"
    }
}
