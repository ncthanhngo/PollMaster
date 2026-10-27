package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.model.PollJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs `shared/template-validation-vectors.json`, the same file the iOS tests run. */
class TemplateValidationVectorTest {
    private val cases = PollJson.parseToJsonElement(File("../../shared/template-validation-vectors.json").readText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.paths(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun templateText(c: JsonObject): String {
        val t = c.getValue("template")
        return if (t is JsonPrimitive) File("../../shared/${t.content}").readText() else t.toString()
    }

    @Test fun vectors() {
        assertTrue(cases.size >= 15)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val text = templateText(c)
            val problems = TemplateParser.problems(text)
            assertEquals("$name: $problems", c.paths("problemPaths"), problems.map { it.substringBefore(": ") }.sorted())
            assertEquals(name, c.getValue("valid").jsonPrimitive.boolean, problems.isEmpty())
            if (problems.isEmpty()) {
                val warnings = TemplateParser.warnings(TemplateParser.parse(text)).map { it.substringBefore(": ") }.sorted()
                assertEquals("$name warnings", c.paths("warningPaths"), warnings)
            }
        }
    }
}
