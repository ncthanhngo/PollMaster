package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.formula.FormulaEngine
import com.ncthanhngo.pollmaster.core.formula.RecordSource
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.Record
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaVectorsTest {
    private val root = PollJson.parseToJsonElement(TestData.vectorsText).jsonObject
    private val record = Record(
        id = "v", templateId = "v", templateVersion = "1", createdAt = "", updatedAt = "",
        values = PollJson.decodeFromJsonElement(MapSerializer(String.serializer(), FieldValue.serializer()), root.getValue("values")),
        tables = PollJson.decodeFromJsonElement(
            MapSerializer(String.serializer(), ListSerializer(Row.serializer())), root.getValue("tables"),
        ),
    )
    private val cases = root.getValue("cases").jsonArray

    @Test fun hasAllCases() {
        assertTrue(cases.size >= 43)
    }

    @Test fun allVectorsPass() {
        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val samples = o["samples"]?.jsonPrimitive?.int
            val actual = FormulaEngine(RecordSource(null, record)).eval(o.getValue("formula").jsonPrimitive.content, samples)
            val problem = compare(o.getValue("expected"), actual)
            if (problem != null) failures += "$name: $problem (actual=$actual)"
        }
        assertTrue("Failed vectors:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun compare(expected: kotlinx.serialization.json.JsonElement, actual: Any?): String? = when {
        expected is JsonNull -> if (actual == null) null else "expected null"
        expected is JsonArray -> {
            val list = actual as? List<*>
            if (list == null || list.size != expected.size) "expected list of ${expected.size}"
            else expected.indices.firstNotNullOfOrNull { i -> compare(expected[i], list[i])?.let { "[$i] $it" } }
        }
        expected is JsonPrimitive && expected.booleanOrNull != null -> if (actual == expected.booleanOrNull) null else "expected ${expected.booleanOrNull}"
        expected is JsonPrimitive -> {
            val e = expected.content.toDouble()
            val a = actual as? Double
            if (a != null && kotlin.math.abs(a - e) < 1e-6) null else "expected $e"
        }
        else -> "unsupported"
    }

    @Test fun malformedFormulaYieldsNull() {
        val t = TestData.template
        val engine = FormulaEngine(RecordSource(t, TestData.emptyRecord()))
        assertNull(engine.eval("{X} +", null))
    }

    @Test fun templateComputedFieldsEvaluate() {
        val t = TestData.template
        val engine = FormulaEngine(RecordSource(t, TestData.emptyRecord()))
        val computed = t.sections.flatMap { it.items }.filterIsInstance<com.ncthanhngo.pollmaster.core.model.Field>().filter { it.type == "computed" }
        assertEquals(2, computed.count { it.samples != null })
        computed.forEach { engine.computeField(it) } // must not throw on empty record
    }
}
