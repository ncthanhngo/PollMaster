package com.ncthanhngo.pollmaster.core.templateeditor

import com.ncthanhngo.pollmaster.core.TestData
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.sync.ProjectTemplatesInfo
import com.ncthanhngo.pollmaster.core.sync.PublishOutcome
import com.ncthanhngo.pollmaster.core.sync.ServerTemplate
import com.ncthanhngo.pollmaster.core.sync.ServerTemplateRef
import com.ncthanhngo.pollmaster.core.sync.TemplateRemote
import com.ncthanhngo.pollmaster.core.sync.TemplateSync
import com.ncthanhngo.pollmaster.core.sync.TemplateSyncFailure
import com.ncthanhngo.pollmaster.core.sync.TemplateVersionExists
import com.ncthanhngo.pollmaster.core.sync.previousVersion
import com.ncthanhngo.pollmaster.core.sync.publish
import com.ncthanhngo.pollmaster.core.sync.publishLocally
import com.ncthanhngo.pollmaster.core.sync.rollBack
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

private fun template(id: String = "T-1", version: String, label: String = "Tên", title: String = "Mẫu"): JsonObject {
    fun s(v: String) = JsonPrimitive(v)
    val item = JsonObject(mapOf("id" to s("A-01"), "label" to s(label), "type" to s("text")))
    val section = JsonObject(mapOf("id" to s("A"), "title" to s("Chung"), "items" to JsonArray(listOf(item))))
    return JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "id" to s(id), "version" to s(version), "title" to s(title), "sections" to JsonArray(listOf(section))))
}

/** One project's template calls on a fake server; every POST/PATCH is logged. */
private class PublishFake : TemplateRemote {
    var assigned: String? = null
    var responses = ArrayDeque<String>()
    var failPatch = emptySet<String>()
    var canManage = true
    val calls = mutableListOf<String>()

    override suspend fun list(projectId: String): ProjectTemplatesInfo {
        val v = assigned ?: return ProjectTemplatesInfo(canManage = canManage)
        return ProjectTemplatesInfo(canManage, listOf(ServerTemplateRef("T-1", v)), emptyList(), listOf(ServerTemplate("T-1", v, template(version = v))))
    }

    override suspend fun add(projectId: String, content: JsonElement) {
        val id = content.jsonObject.getValue("id").jsonPrimitive.content
        val v = content.jsonObject.getValue("version").jsonPrimitive.content
        calls += "add:$projectId:$id@$v"
        when (if (responses.isEmpty()) "200" else responses.removeFirst()) {
            "409" -> throw TemplateVersionExists()
            "400" -> throw TemplateSyncFailure("Mẫu không hợp lệ.")
            "net" -> throw IOException("offline")
            else -> if (id == "T-1") assigned = v
        }
    }

    override suspend fun patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) {
        calls += "patch:$projectId:$templateId@${version ?: "meta"}"
        if (projectId in failPatch) throw TemplateSyncFailure("Không đổi được $projectId.")
    }

    override suspend fun remove(projectId: String, templateId: String) = false
}

private class Rig {
    val root: File = TestData.tempDir()
    val fake = PublishFake()
    val drafts = TemplateDraftStore(root)
    val projects = ProjectStore(root)
    val sync get() = TemplateSync(projects, TemplateStore(root), fake, drafts)

    fun install(version: String) { TemplateStore(root).install(TemplateDoc(template(version = version)).pretty(), overwrite = true) }

    fun draft(baseVersion: String?, content: JsonElement, publishVersion: String? = null): TemplateDraft {
        val d = TemplateDraft(baseId = if (baseVersion == null) null else "T-1", baseVersion = baseVersion, projectId = "P1", createdAt = "t0", updatedAt = "t1",
            publishVersion = publishVersion, content = content)
        drafts.save(d)
        return d
    }
}

class TemplatePublishTest {
    private val vectors = PollJson.parseToJsonElement(File("../../shared/sync-test-vectors.json").readText()).jsonObject.getValue("publish").jsonArray

    @Test fun publishVectors() = runBlocking {
        assertTrue(vectors.size >= 15)
        for (c in vectors) {
            val o = c.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val want = o.getValue("expect").jsonObject
            val rig = Rig()
            val baseVersion = o["baseVersion"]?.jsonPrimitive?.contentOrNull
            o.getValue("library").jsonArray.forEach { rig.install(it.jsonPrimitive.content) }
            rig.fake.assigned = o["assigned"]?.jsonPrimitive?.contentOrNull
            rig.fake.responses = ArrayDeque(o.getValue("responses").jsonArray.map { it.jsonPrimitive.content })
            rig.fake.failPatch = o.getValue("failPatch").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val content = when (o.getValue("draft").jsonPrimitive.content) {
                "newVersion" -> template(version = baseVersion!!, label = "Tên khác")
                "meta" -> template(version = baseVersion!!, title = "Tên mới")
                "invalid" -> template(version = baseVersion!!, label = "")
                else -> template(id = "NEW-1", version = "1.0")
            }
            val draft = rig.draft(baseVersion, content, o["publishVersion"]?.jsonPrimitive?.contentOrNull)
            val also = o.getValue("also").jsonArray.map { it.jsonPrimitive.content }
            val result = try { rig.sync.publish(draft, "P1", also, o.getValue("force").jsonPrimitive.boolean) } catch (_: Exception) { null }
            val outcome = when (result) {
                is PublishOutcome.Published -> {
                    assertEquals("$name: version", want["version"]?.jsonPrimitive?.contentOrNull, result.version)
                    assertEquals("$name: failed", want["failed"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }, result.failedProjects.map { it.projectId })
                    "published"
                }
                is PublishOutcome.StaleBase -> { assertEquals(name, want["serverVersion"]?.jsonPrimitive?.contentOrNull, result.serverVersion); "staleBase" }
                is PublishOutcome.Invalid -> { assertTrue(name, result.issues.isNotEmpty()); "invalid" }
                PublishOutcome.IdTaken -> "idTaken"
                null -> "throws"
            }
            assertEquals(name, want.getValue("outcome").jsonPrimitive.content, outcome)
            assertEquals(name, want.getValue("calls").jsonArray.map { it.jsonPrimitive.content }, rig.fake.calls)
            val kept = rig.drafts.load(draft.draftId)
            assertEquals("$name: draft kept", want.getValue("draftKept").jsonPrimitive.boolean, kept != null)
            want["publishVersion"]?.jsonPrimitive?.contentOrNull?.let { assertEquals("$name: publishVersion", it, kept?.publishVersion) }
        }
    }

    @Test fun publishedVersionReachesTheDeviceLibrary() = runBlocking {
        val rig = Rig()
        rig.install("1.0")
        rig.fake.assigned = "1.0"
        rig.sync.publish(rig.draft("1.0", template(version = "1.0", label = "Tên khác")), "P1")
        assertNotNull(TemplateStore(rig.root).get("T-1", "1.1"))
    }

    @Test fun publishNeedsManage() = runBlocking {
        val rig = Rig()
        rig.fake.canManage = false
        val d = rig.draft("1.0", template(version = "1.0", label = "Khác"))
        try { rig.sync.publish(d, "P1"); org.junit.Assert.fail("expected failure") } catch (_: TemplateSyncFailure) { }
        assertTrue(rig.fake.calls.isEmpty())
    }

    private fun project(rig: Rig, templates: List<TemplateRef>): Project =
        Project(id = "PX", code = "PX", name = "Dự án", templates = templates).also { rig.projects.save(it) }

    @Test fun publishLocallyInstallsAndSwitchesTheProject() {
        val rig = Rig()
        rig.install("1.0")
        val p = project(rig, listOf(TemplateRef("T-1", "1.0")))
        val d = rig.draft("1.0", template(version = "1.0", label = "Tên khác"))
        assertEquals(PublishOutcome.Published("1.1", emptyList()), rig.sync.publishLocally(d, p.id))
        assertNotNull(TemplateStore(rig.root).get("T-1", "1.1"))
        assertEquals(listOf(TemplateRef("T-1", "1.1")), rig.projects.get(p.id)!!.templates)
        assertNull(rig.drafts.load(d.draftId))
    }

    @Test fun publishLocallyNewTemplateAndMeta() {
        val rig = Rig()
        rig.install("1.0")
        val p = project(rig, emptyList())
        val n = rig.draft(null, template(id = "NEW-1", version = "1.0"))
        assertEquals(PublishOutcome.Published("1.0", emptyList()), rig.sync.publishLocally(n, p.id))
        assertEquals(listOf(TemplateRef("NEW-1", "1.0")), rig.projects.get(p.id)!!.templates)
        val m = rig.draft("1.0", template(version = "1.0", title = "Tên mới"))
        assertEquals(PublishOutcome.Published("1.0", emptyList()), rig.sync.publishLocally(m, p.id))
        assertEquals("Tên mới", TemplateStore(rig.root).get("T-1", "1.0")?.title)
        assertNull(TemplateStore(rig.root).get("T-1", "1.1"))
    }

    @Test fun rollBackUsesThePreviousVersionOnThisDevice() = runBlocking {
        val rig = Rig()
        listOf("1.0", "1.2", "1.10").forEach(rig::install)
        assertEquals("1.2", rig.sync.previousVersion(TemplateRef("T-1", "1.10"))?.version)
        assertNull(rig.sync.previousVersion(TemplateRef("T-1", "1.0")))
        try { rig.sync.rollBack(TemplateRef("T-1", "1.0"), "P1"); org.junit.Assert.fail("expected failure") } catch (_: TemplateSyncFailure) { }
        rig.fake.assigned = "1.10"
        rig.sync.rollBack(TemplateRef("T-1", "1.10"), "P1")
        assertEquals(listOf("add:P1:T-1@1.2"), rig.fake.calls)
    }

    @Test fun previousVersionRules() {
        assertEquals("1.9", TemplatePublishRules.previousVersion("1.10", listOf("1.9", "1.10", "1.2")))
        assertNull(TemplatePublishRules.previousVersion("1.0", listOf("1.0")))
        assertEquals("1.5", TemplatePublishRules.previousVersion("2", listOf("1.5", "1.0")))
    }

    @Test fun otherProjectsAreManagedActiveAndUseTheTemplate() {
        fun p(id: String, manage: Boolean, archived: Boolean = false, uses: String? = "T-1") =
            Project(id = id, code = id, name = id, templates = listOfNotNull(uses?.let { TemplateRef(it, "1.0") }), canManage = manage, archived = archived)
        val all = listOf(p("P1", true), p("P2", true), p("P3", false), p("P4", true, archived = true), p("P5", true, uses = "X"))
        assertEquals(listOf("P2"), TemplatePublishRules.otherProjects("T-1", "P1", all).map { it.id })
        assertEquals(listOf("P1", "P2"), TemplatePublishRules.manageable("T-1", all).map { it.id })
        assertEquals(listOf("P1", "P2", "P5"), TemplatePublishRules.manageable(null, all).map { it.id })
    }

    @Test fun nothingStaleWithoutAssignedTemplateAndFalseHelperSanity() {
        assertFalse(TemplatePublishRules.manageable("T-1", emptyList()).isNotEmpty())
    }
}
