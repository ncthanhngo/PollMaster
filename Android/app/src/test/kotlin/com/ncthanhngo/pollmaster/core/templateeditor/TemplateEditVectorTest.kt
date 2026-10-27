package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.storage.TemplateParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Reads `shared/template-edit-vectors.json`, the same file the iOS tests run. */
class TemplateEditVectorTest {
    private val shared = File("../../shared")
    private val vectors = PollJson.parseToJsonElement(File(shared, "template-edit-vectors.json").readText()).jsonObject

    private fun docOf(v: JsonElement?): TemplateDoc? = when {
        v is JsonPrimitive && v.isString -> TemplateDoc.of(File(shared, v.content).readText())
        v is JsonObject -> TemplateDoc(v)
        else -> null
    }

    private fun path(v: JsonElement?): DocPath = v?.jsonArray.orEmpty().map { p ->
        p.jsonPrimitive.let { if (it.isString) PathKey.Key(it.content) else PathKey.Index(it.int) }
    }

    private fun op(j: JsonElement): EditOp {
        val o = j.jsonObject
        fun int(k: String) = o.getValue(k).jsonPrimitive.int
        return when (val name = o.getValue("op").jsonPrimitive.content) {
            "set" -> EditOp.Set(path(o["path"]), o.getValue("key").jsonPrimitive.content, o["value"])
            "insert" -> EditOp.Insert(path(o["path"]), int("index"), o.getValue("node"))
            "delete" -> EditOp.Delete(path(o["path"]), int("index"))
            "move" -> EditOp.Move(path(o["path"]), int("from"), int("to"))
            "moveItem" -> EditOp.MoveItem(int("fromSection"), int("index"), int("toSection"))
            "duplicate" -> EditOp.Duplicate(path(o["path"]), int("index"))
            "replace" -> if (o["json"] != null) EditOp.ReplaceText(path(o["path"]), o.getValue("json").jsonPrimitive.content) else EditOp.Replace(path(o["path"]), o.getValue("node"))
            "newItem" -> EditOp.NewItem(path(o["path"]), o["index"]?.jsonPrimitive?.intOrNull, o.getValue("kind").jsonPrimitive.content, o["type"]?.jsonPrimitive?.contentOrNull)
            else -> error("unknown op $name")
        }
    }

    private fun patched(base: TemplateDoc, patches: JsonArray): TemplateDoc = patches.fold(base) { doc, p ->
        val o = p.jsonObject
        doc.updating(path(o["path"])) { v -> JsonObject(v!!.jsonObject + (o.getValue("key").jsonPrimitive.content to (o["value"] ?: JsonNull))) }
    }

    private fun lintKeys(issues: List<TemplateIssue>) = issues.map { "${it.code}|${it.path}" }.sorted()

    @Test fun cases() {
        val cases = vectors.getValue("cases").jsonArray
        assertTrue(cases.size >= 30)
        for (c in cases) {
            val o = c.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val base = docOf(o["base"])!!
            val ops = o.getValue("ops").jsonArray.map(::op)
            val expect = o["expect"]?.jsonObject
            val code = expect?.get("error")?.jsonPrimitive?.contentOrNull
            if (code != null) {
                try { TemplateEdit.apply(base, ops); fail("$name: expected $code") } catch (e: TemplateEditException) { assertEquals(name, code, e.code) }
                continue
            }
            val got = TemplateEdit.apply(base, ops)
            val want = when {
                expect?.get("unchanged") != null -> base
                o["expectPatch"] != null -> patched(base, o.getValue("expectPatch").jsonArray)
                else -> TemplateDoc(o.getValue("expect"))
            }
            assertTrue("$name:\n${got.pretty()}\n---\n${want.pretty()}", got.semanticEquals(want))
            assertTrue("$name: pretty round trip", TemplateDoc.of(got.pretty()).semanticEquals(got))
            val lint = o["expectLint"]?.jsonArray
            if (lint != null) {
                val isNew = o["newTemplate"]?.jsonPrimitive?.boolean ?: false
                val wantKeys = lint.map { "${it.jsonObject.getValue("code").jsonPrimitive.content}|${it.jsonObject.getValue("path").jsonPrimitive.content}" }.sorted()
                assertEquals("$name lint", wantKeys, lintKeys(TemplateLint.check(got, base, isNew)))
            } else {
                assertEquals(name, emptyList<String>(), TemplateParser.problems(got.pretty()))
            }
        }
    }

    @Test fun versionsIdsAndKinds() {
        for (c in vectors.getValue("versions").jsonArray) {
            val known = c.jsonObject.getValue("known").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$known", c.jsonObject.getValue("expect").jsonPrimitive.content, TemplatePublishRules.nextVersion(known))
        }
        for (c in vectors.getValue("ids").jsonArray) {
            val o = c.jsonObject
            val p = path(o["path"])
            val got = TemplateEdit.apply(TemplateDoc(o.getValue("base")), EditOp.NewItem(p, null, o.getValue("kind").jsonPrimitive.content, null))
            assertEquals(o.getValue("name").jsonPrimitive.content, o.getValue("expectId").jsonPrimitive.content, got.node(p)!!.jsonArray.last().jsonObject.getValue("id").jsonPrimitive.content)
        }
        for (c in vectors.getValue("optionValues").jsonArray) {
            val o = c.jsonObject
            val taken = o.getValue("taken").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(o.getValue("name").jsonPrimitive.content, o.getValue("expect").jsonPrimitive.content, TemplateIds.optionValue(o.getValue("label").jsonPrimitive.content, taken))
        }
        for (c in vectors.getValue("templateIds").jsonArray) {
            val o = c.jsonObject
            val randoms = ArrayDeque(o.getValue("randoms").jsonArray.map { it.jsonPrimitive.content })
            val id = TemplateIds.templateId(o["shortName"]?.jsonPrimitive?.contentOrNull, o.getValue("title").jsonPrimitive.content,
                o.getValue("taken").jsonArray.map { it.jsonPrimitive.content }.toSet()) { randoms.removeFirst() }
            assertEquals(o.getValue("name").jsonPrimitive.content, o.getValue("expect").jsonPrimitive.content, id)
        }
        for (c in vectors.getValue("publishKinds").jsonArray) {
            val o = c.jsonObject
            val base = docOf(o["base"])
            val draft = TemplateEdit.apply(base ?: TemplateDoc(JsonObject(emptyMap())), o.getValue("ops").jsonArray.map(::op))
            assertEquals(o.getValue("name").jsonPrimitive.content, o.getValue("expect").jsonPrimitive.content.lowercase(), TemplatePublishRules.publishKind(base, draft).name.lowercase().replace("_", ""))
        }
        for (c in vectors.getValue("pretty").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("name").jsonPrimitive.content, o.getValue("expect").jsonPrimitive.content, TemplateDoc(o.getValue("doc")).pretty())
        }
    }

    @Test fun packagedTemplatesSurvivePrettyAndLint() {
        for (file in listOf("bb-tdp-01.json", "ks-vf-swap-01.json")) {
            val doc = TemplateDoc.of(File(shared, "templates/$file").readText())
            assertTrue(file, TemplateDoc.of(doc.pretty()).semanticEquals(doc))
            assertEquals(file, emptyList<String>(), TemplateParser.problems(doc.pretty()))
            assertEquals(file, emptyList<TemplateIssue>(), TemplateLint.check(doc, doc))
        }
    }

    @Test fun pathTextRoundTrip() {
        val p = docPath("sections", 2, "items", 5, "options")
        assertEquals("sections[2].items[5].options", p.text)
        assertEquals(p, parseDocPath(p.text))
        assertEquals(docPath("header", 0), parseDocPath("header[0]"))
        assertNull(parseDocPath("a[x]"))
        assertEquals(emptyList<PathKey>(), parseDocPath(""))
    }
}
