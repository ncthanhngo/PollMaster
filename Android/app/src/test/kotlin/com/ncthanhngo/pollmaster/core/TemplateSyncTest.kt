package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.RecordCreator
import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDraftStore
import com.ncthanhngo.pollmaster.core.sync.ProjectTemplatesInfo
import com.ncthanhngo.pollmaster.core.sync.ServerTemplate
import com.ncthanhngo.pollmaster.core.sync.ServerTemplateRef
import com.ncthanhngo.pollmaster.core.sync.TemplateRemote
import com.ncthanhngo.pollmaster.core.sync.TemplateSync
import com.ncthanhngo.pollmaster.core.sync.TemplateSyncFailure
import com.ncthanhngo.pollmaster.core.sync.TemplateSyncPlanner
import com.ncthanhngo.pollmaster.core.sync.TemplateVersionExists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun ref(s: String) = s.split("@", limit = 2).let { TemplateRef(it[0], it[1]) }

/** In-memory ERP templates of one project. */
private class FakeTemplates : TemplateRemote {
    val library = LinkedHashMap<String, JsonElement>() // "id@version"
    var assigned = mutableListOf<String>()
    val removed = sortedSetOf<String>()
    var canManage = true
    val posts = mutableListOf<String>()
    val patches = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    var refuse: String? = null
    var versionExists = false

    override suspend fun list(projectId: String): ProjectTemplatesInfo {
        val refs = assigned.mapIndexedNotNull { i, id ->
            val key = library.keys.sorted().lastOrNull { it.startsWith("$id@") } ?: return@mapIndexedNotNull null
            ServerTemplateRef(id, key.removePrefix("$id@"), i)
        }
        return ProjectTemplatesInfo(canManage, refs, removed.toList(), refs.mapNotNull { r -> library["${r.templateId}@${r.version}"]?.let { ServerTemplate(r.templateId, r.version, it) } })
    }

    override suspend fun add(projectId: String, content: JsonElement) {
        refuse?.let { throw TemplateSyncFailure(it) }
        if (versionExists) throw TemplateVersionExists()
        val id = content.jsonObject["id"]!!.jsonPrimitive.content
        val v = content.jsonObject["version"]!!.jsonPrimitive.content
        posts += "$id@$v"
        library["$id@$v"] = content
        removed.remove(id)
        assigned.remove(id)
        assigned += id
    }

    override suspend fun patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) {
        refuse?.let { throw TemplateSyncFailure(it) }
        patches += "$templateId@${version ?: "-"}|${title.orEmpty()}|${color.orEmpty()}"
        if (templateId !in assigned) throw TemplateSyncFailure("404")
        val key = if (version != null) "$templateId@$version" else library.keys.first { it.startsWith("$templateId@") }
        if (title != null) library[key] = JsonObject(library.getValue(key).jsonObject + ("title" to JsonPrimitive(title)))
    }

    override suspend fun remove(projectId: String, templateId: String): Boolean {
        deletes += templateId
        if (templateId !in assigned && templateId !in removed) return false
        assigned.remove(templateId); removed += templateId
        return true
    }
}

class TemplateSyncTest {
    private val pid = "11111111-1111-1111-1111-111111111111"
    private val vectors: JsonObject = PollJson.parseToJsonElement(File("../../shared/sync-test-vectors.json").readText()).jsonObject
    private fun strings(a: JsonElement) = a.jsonArray.map { it.jsonPrimitive.content }

    @Test fun plannerVectors() {
        val cases = vectors.getValue("templates").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 12)
        for (c in cases) {
            val got = TemplateSyncPlanner.plan(
                strings(c.getValue("local")).map(::ref), strings(c.getValue("assigned")).map(::ref), strings(c.getValue("removed")),
                c.getValue("canManage").jsonPrimitive.content.toBoolean(), c.getValue("isPhoenix").jsonPrimitive.content.toBoolean(),
                strings(c.getValue("library")).map(::ref), strings(c.getValue("bundled")).map(::ref),
            )
            val name = c.getValue("name").jsonPrimitive.content
            assertEquals("$name: post", strings(c.getValue("expectPost")), got.post.map { "${it.id}@${it.version}" })
            assertEquals("$name: templates", strings(c.getValue("expectTemplates")), got.templates.map { "${it.id}@${it.version}" })
        }
    }

    @Test fun createdByVectors() {
        val t = TestData.template
        for (c in vectors.getValue("created").jsonArray.map { it.jsonObject }) {
            val me = (c["me"] as? JsonObject)?.let { RecordCreator(it["id"]?.jsonPrimitive?.contentOrNull, it["name"]?.jsonPrimitive?.contentOrNull) }
            val r = if (c.getValue("op").jsonPrimitive.content == "make") RecordFactory.create(t, null, projectId = "p", creator = me)
            else {
                val src = c["source"] as JsonObject
                val source = RecordFactory.create(t, null, projectId = "p")
                    .copy(createdBy = src["createdBy"]?.jsonPrimitive?.contentOrNull, createdByName = src["createdByName"]?.jsonPrimitive?.contentOrNull, serverRev = 4)
                RecordFactory.duplicate(t, source, creator = me)
            }
            val want = c.getValue("expect").jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            assertEquals(name, want["createdBy"]?.jsonPrimitive?.contentOrNull, r.createdBy)
            assertEquals(name, want["createdByName"]?.jsonPrimitive?.contentOrNull, r.createdByName)
            if (c.getValue("op").jsonPrimitive.content == "duplicate") assertNull("$name: not synced yet", r.serverRev)
        }
    }

    // ---- engine ----

    private class Env(val root: File, val remote: FakeTemplates, val data: String) {
        val projects = ProjectStore(root)
        val store = TemplateStore(root)
        fun sync() = TemplateSync(projects, store, remote, TemplateDraftStore(root))
    }

    private fun setup(phoenix: Boolean = false): Env {
        val root = TestData.tempDir()
        val data = TestData.templateText
        val t = TestData.template
        TemplateStore(root).install(data, overwrite = true)
        ProjectStore(root).save(Project(id = pid, code = if (phoenix) "PHOENIX" else "P", name = if (phoenix) "Phoenix" else "P", templates = listOf(TemplateRef(t.id, t.version))))
        return Env(root, FakeTemplates(), data)
    }

    private fun content(data: String, title: String? = null, version: String? = null, id: String? = null): JsonElement {
        val o = PollJson.parseToJsonElement(data).jsonObject.toMutableMap()
        title?.let { o["title"] = JsonPrimitive(it) }; version?.let { o["version"] = JsonPrimitive(it) }; id?.let { o["id"] = JsonPrimitive(it) }
        return JsonObject(o)
    }

    private fun variant(e: Env, id: String, version: String = "1.0"): TemplateRef {
        e.store.install(content(e.data, title = "Mới", id = id, version = version).toString(), overwrite = true)
        return TemplateRef(id, version)
    }

    private val bb = "BB-TDP-01@1.2"

    @Test fun firstSyncOfAManagerUploadsTheDeviceList() = runBlocking {
        val e = setup()
        val out = e.sync().sync(pid, emptyList(), emptyList())
        assertEquals(listOf(bb), e.remote.posts); assertEquals(1, out.posted); assertNull(out.failure)
        assertEquals(listOf(ref(bb)), e.projects.get(pid)!!.templates)
    }

    @Test fun serverWinsOnContentAndListAndRemembersRemoved() = runBlocking {
        val e = setup()
        e.remote.canManage = false
        e.remote.library[bb] = content(e.data, title = "Tên của máy chủ")
        e.remote.assigned = mutableListOf("BB-TDP-01"); e.remote.removed += "OLD-1"
        val out = e.sync().sync(pid, emptyList(), emptyList())
        assertTrue(out.changed); assertTrue(e.remote.posts.isEmpty())
        assertEquals("Tên của máy chủ", e.store.get("BB-TDP-01", "1.2")?.title)
        assertEquals(listOf("OLD-1"), e.projects.get(pid)!!.removedTemplateIds)
        assertFalse(e.sync().sync(pid, emptyList(), emptyList()).changed) // same content: nothing rewritten
    }

    @Test fun nonManagerKeepsTheDeviceListUntilTheServerHasData() = runBlocking {
        val e = setup()
        e.remote.canManage = false
        e.sync().sync(pid, emptyList(), emptyList())
        assertTrue(e.remote.posts.isEmpty()); assertEquals(1, e.projects.get(pid)!!.templates.size)
    }

    @Test fun missingApiLeavesTheDeviceAlone() = runBlocking {
        val e = setup()
        val gone = object : TemplateRemote {
            override suspend fun list(projectId: String): ProjectTemplatesInfo? = null
            override suspend fun add(projectId: String, content: JsonElement) {}
            override suspend fun patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) {}
            override suspend fun remove(projectId: String, templateId: String) = true
        }
        val out = TemplateSync(e.projects, e.store, gone, TemplateDraftStore(e.root)).sync(pid, emptyList(), emptyList())
        assertTrue(out.unavailable); assertEquals(1, e.projects.get(pid)!!.templates.size)
    }

    @Test fun phoenixManagerUploadsNewBundledTemplateOnlyOnce() = runBlocking {
        val e = setup(phoenix = true)
        val other = variant(e, "KS-NEW-01")
        e.remote.library[bb] = content(e.data); e.remote.assigned = mutableListOf("BB-TDP-01")
        val bundled = listOf(ref(bb), other)
        e.sync().sync(pid, bundled, bundled)
        assertEquals(listOf("KS-NEW-01@1.0"), e.remote.posts)
        e.sync().unassign(other, pid) // the manager removes it: it stays removed
        e.sync().sync(pid, bundled, bundled)
        assertEquals(listOf("KS-NEW-01@1.0"), e.remote.posts)
        assertEquals(listOf("BB-TDP-01"), e.projects.get(pid)!!.templates.map { it.id })
        assertEquals(listOf("KS-NEW-01"), e.projects.get(pid)!!.removedTemplateIds)
    }

    @Test fun actionsChangeTheDeviceOnlyAfterTheServerAnswered() = runBlocking {
        val e = setup()
        e.remote.library[bb] = content(e.data); e.remote.assigned = mutableListOf("BB-TDP-01")
        val extra = variant(e, "KS-NEW-01")
        e.remote.refuse = "Bạn không có quyền quản lý dự án này."
        assertTrue(runCatching { e.sync().assign(listOf(extra), pid) }.exceptionOrNull() is TemplateSyncFailure)
        assertEquals(listOf("BB-TDP-01"), e.projects.get(pid)!!.templates.map { it.id })
        e.remote.refuse = null
        e.sync().assign(listOf(extra), pid)
        assertEquals(listOf("BB-TDP-01", "KS-NEW-01"), e.projects.get(pid)!!.templates.map { it.id })
        e.sync().unassign(extra, pid)
        assertEquals(listOf("BB-TDP-01"), e.projects.get(pid)!!.templates.map { it.id })
    }

    @Test fun removingATemplateTheServerNeverHadUploadsItFirst() = runBlocking {
        val e = setup()
        e.remote.assigned = mutableListOf("OTHER")
        e.sync().unassign(ref(bb), pid)
        assertEquals(listOf(bb), e.remote.posts); assertEquals(listOf("BB-TDP-01", "BB-TDP-01"), e.remote.deletes); assertEquals(setOf("BB-TDP-01"), e.remote.removed.toSet())
    }

    @Test fun newVersionFallsBackToPatchWhenTheLibraryHasIt() = runBlocking {
        val e = setup()
        e.remote.library[bb] = content(e.data); e.remote.library["BB-TDP-01@1.1"] = content(e.data, version = "1.1")
        e.remote.assigned = mutableListOf("BB-TDP-01"); e.remote.versionExists = true
        val newer = variant(e, "BB-TDP-01", "1.1")
        e.sync().useVersion(newer, pid)
        assertEquals(listOf("BB-TDP-01@1.1||"), e.remote.patches)
    }

    @Test fun editMetaGoesThroughTheServerAndComesBackAsContent() = runBlocking {
        val e = setup()
        e.remote.library[bb] = content(e.data); e.remote.assigned = mutableListOf("BB-TDP-01")
        e.sync().editMeta(ref(bb), pid, "Tên mới", "TM", "#1B6B5A")
        assertEquals("Tên mới", e.store.get("BB-TDP-01", "1.2")?.title)
        e.remote.refuse = "Không có quyền."
        assertTrue(runCatching { e.sync().editMeta(ref(bb), pid, "Khác", "", "") }.exceptionOrNull() is TemplateSyncFailure)
        assertEquals("Tên mới", e.store.get("BB-TDP-01", "1.2")?.title)
    }
}
