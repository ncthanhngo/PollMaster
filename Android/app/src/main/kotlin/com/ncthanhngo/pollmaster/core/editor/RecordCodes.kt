package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.asText
import java.math.BigInteger

/** Proposes the code of a new record: last number of the codes already used by the same template, plus one. */
object RecordCodes {
    private val trailingDigits = Regex("(\\d+)\\s*$")

    /** Codes without a trailing number are ignored; "009" -> "010" keeps the zero padding; nothing used yet -> "001". */
    fun propose(existing: List<String?>): String {
        val numbers = existing.mapNotNull { it?.let(trailingDigits::find)?.groupValues?.get(1) }
        val last = numbers.maxByOrNull { BigInteger(it) } ?: return "001"
        return BigInteger(last).add(BigInteger.ONE).toString().padStart(last.length, '0')
    }

    /** Proposal for a new record of [template] given all stored [records] (other templates are ignored). */
    fun propose(template: Template, records: List<Record>): String? {
        val field = template.allFields().firstOrNull { it.role == "recordCode" } ?: return null
        return propose(records.filter { it.templateId == template.id }.map { it.values[field.id]?.v.asText() })
    }
}
