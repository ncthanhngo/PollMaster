package com.ncthanhngo.pollmaster.core.project

import com.ncthanhngo.pollmaster.core.model.sanitizeFileName

/** File names of project-aware exports (schema 9.4). [stamp] is `yyyyMMdd-HHmm`. */
object ProjectNames {
    /** `{projectCode}_{recordCode|recordId}_{stamp}` without extension; the project part is dropped when there is none. */
    fun recordBase(projectCode: String?, recordCode: String?, recordId: String, stamp: String): String {
        val code = recordCode?.let(::sanitizeFileName)?.takeIf { it.isNotEmpty() } ?: recordId
        return listOfNotNull(projectCode?.takeIf { it.isNotEmpty() }, code, stamp).joinToString("_")
    }

    fun summary(projectCode: String, stamp: String) = "${projectCode}_tong-hop_$stamp.xlsx"

    fun recordsZip(projectCode: String, stamp: String) = "${projectCode}_phieu_$stamp.zip"
}
