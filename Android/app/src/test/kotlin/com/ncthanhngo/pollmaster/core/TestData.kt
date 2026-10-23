package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

object TestData {
    private val shared = File("../../shared")

    val templateText: String get() = File(shared, "templates/bb-tdp-01.json").readText()
    val template: Template by lazy { TemplateParser.parse(templateText) }
    val vectorsText: String get() = File(shared, "formula-test-vectors.json").readText()

    fun emptyRecord(t: Template = template) = Record(
        id = "rec-1", templateId = t.id, templateVersion = t.version,
        createdAt = "2026-01-01T08:00:00+07:00", updatedAt = "2026-01-01T08:00:00+07:00",
    )

    fun text(s: String) = FieldValue(v = JsonPrimitive(s))

    fun tempDir(): File = kotlin.io.path.createTempDirectory("pollmaster-test").toFile()

    @Suppress("unused")
    val json = PollJson
}
