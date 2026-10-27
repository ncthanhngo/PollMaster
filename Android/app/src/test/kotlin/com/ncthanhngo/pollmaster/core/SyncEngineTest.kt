package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.sync.PendingDelete
import com.ncthanhngo.pollmaster.core.sync.PendingDeletes
import com.ncthanhngo.pollmaster.core.sync.SyncApply
import com.ncthanhngo.pollmaster.core.sync.downloadMedia
import com.ncthanhngo.pollmaster.core.sync.SyncEngine
import com.ncthanhngo.pollmaster.core.sync.SyncHooks
import com.ncthanhngo.pollmaster.core.sync.SyncMediaInfo
import com.ncthanhngo.pollmaster.core.sync.SyncPage
import com.ncthanhngo.pollmaster.core.sync.SyncPlanner
import com.ncthanhngo.pollmaster.core.sync.SyncPutBody
import com.ncthanhngo.pollmaster.core.sync.SyncPutResult
import com.ncthanhngo.pollmaster.core.sync.SyncRecord
import com.ncthanhngo.pollmaster.core.sync.SyncRef
import com.ncthanhngo.pollmaster.core.sync.SyncRemote
import com.ncthanhngo.pollmaster.core.sync.SyncRecordFailure
import com.ncthanhngo.pollmaster.core.sync.isSyncDirty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** In-memory ERP: one project, records with rev, media by name. */
private open class FakeRemote : SyncRemote {
    val server = LinkedHashMap<String, SyncRecord>()
    val blobs = HashMap<String, ByteArray>()
    var pages: List<List<SyncRecord>>? = null
    var conflictOnPut: SyncRecord? = null
    var canPost = true
    var offline = false
    val puts = mutableListOf<SyncPutBody>()
    val uploaded = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val pulls = mutableListOf<Pair<String?, String?>>()

    override suspend fun pull(projectId: String, since: String?, cursor: String?): SyncPage {
        pulls += since to cursor
        if (offline) throw IOException("offline")
        val ids = server.values.map { SyncRef(it.recordId, it.rev) }
        val p = pages ?: return SyncPage("T1", canPost, false, server.values.toList(), ids)
        val n = cursor?.toInt() ?: 0
        return SyncPage("T${n + 1}", canPost, false, p[n], if (n == 0) ids else emptyList(), if (n + 1 < p.size) "${n + 1}" else "")
    }

    override suspend fun put(projectId: String, recordId: String, body: SyncPutBody): SyncPutResult {
        puts += body
        conflictOnPut?.let { return SyncPutResult.Conflict(it) }
        val missing = body.media.keys.filter { "$recordId/$it" !in blobs }.sorted()
        if (missing.isNotEmpty()) return SyncPutResult.MediaMissing(missing)
        val s = SyncRecord(recordId, (server[recordId]?.rev ?: 0) + 1, 7, "u1", "An", body.data, body.media.map { SyncMediaInfo(it.key, it.value) })
        server[recordId] = s
        return SyncPutResult.Ok(s)
    }

    override suspend fun putMedia(projectId: String, recordId: String, name: String, file: File, sha256: String) {
        blobs["$recordId/media/$name"] = file.readBytes(); uploaded += name
    }

    override suspend fun getMedia(projectId: String, recordId: String, name: String, dest: File) {
        dest.writeBytes(blobs["$recordId/media/$name"] ?: throw SyncRecordFailure("404"))
    }

    override suspend fun putPdf(projectId: String, recordId: String, file: File) {}

    override suspend fun delete(projectId: String, recordId: String) { deleted += recordId; server.remove(recordId) }
}

/** Answers 409 for a put with base_rev 0 of a record the server has (what the backend does), 200 when the content is the same. */
private class Conflicting(val inner: FakeRemote) : FakeRemote() {
    override suspend fun pull(projectId: String, since: String?, cursor: String?) = inner.pull(projectId, since, cursor)
    override suspend fun put(projectId: String, recordId: String, body: SyncPutBody): SyncPutResult {
        val cur = inner.server[recordId]
        if (body.baseRev == 0 && cur != null) return if (SyncPlanner.sameContent(body.data, cur.data)) SyncPutResult.Ok(cur) else SyncPutResult.Conflict(cur)
        return inner.put(projectId, recordId, body)
    }
    override suspend fun putMedia(projectId: String, recordId: String, name: String, file: File, sha256: String) = inner.putMedia(projectId, recordId, name, file, sha256)
    override suspend fun getMedia(projectId: String, recordId: String, name: String, dest: File) = inner.getMedia(projectId, recordId, name, dest)
    override suspend fun delete(projectId: String, recordId: String) = inner.delete(projectId, recordId)
}

class SyncEngineTest {
    private val pid = "11111111-1111-1111-1111-111111111111"
    private val t = TestData.template

    private class Env(val root: File, val remote: FakeRemote) {
        val store = RecordStore(root)
        val projects = ProjectStore(root)
    }

    private fun setup(): Env {
        val root = TestData.tempDir()
        TemplateStore(root).install(TestData.templateText, overwrite = true)
        ProjectStore(root).save(Project(id = pid, code = "P", name = "P"))
        return Env(root, FakeRemote())
    }

    private var counter = 0
    private fun local(e: Env, ago: Int = 100): Record {
        val r = TestData.emptyRecord().copy(id = "rec-${++counter}", projectId = pid, seq = 1)
        e.store.save(r)
        return r
    }

    private fun serverCopy(r: Record, rev: Int, by: String = "u2", media: List<SyncMediaInfo> = emptyList()) =
        SyncRecord(r.id, rev, r.seq, by, "Bình", SyncApply.pushData(r), media)

    private fun addMedia(e: Env, r: Record, name: String, bytes: String): Record {
        File(e.store.mediaDir(r.id), name).writeText(bytes)
        val fv = r.values["P1"] ?: FieldValue()
        return r.copy(values = r.values + ("P1" to fv.copy(media = fv.media + Media("media/$name")))).also { e.store.save(it) }
    }

    private fun run(e: Env, remote: SyncRemote = e.remote, hooks: SyncHooks = SyncHooks(), me: String = "u1") =
        runBlocking { SyncEngine(e.root, remote, hooks).sync(listOf(pid), me).getValue(pid) }

    @Test fun newRecordIsPushedWithItsMediaAndTheAnswerIsRemembered() {
        val e = setup()
        val r = addMedia(e, local(e), "a.jpg", "photo")
        val rep = run(e)
        assertEquals(1, rep.pushed); assertTrue(rep.failures.isEmpty())
        assertEquals(2, e.remote.puts.size); assertEquals(0, e.remote.puts[0].baseRev)
        assertEquals(listOf("a.jpg"), e.remote.uploaded)
        val data = e.remote.puts[1].data.toString()
        assertFalse(data.contains("serverRev") || data.contains("erpUploadedAt"))
        val saved = e.store.load(r.id)!!
        assertEquals(1, saved.serverRev); assertEquals(7, saved.seq); assertEquals("u1", saved.createdBy); assertEquals("An", saved.createdByName)
        assertFalse(saved.isSyncDirty); assertEquals(r.updatedAt, saved.updatedAt)
        assertEquals("T1", e.projects.get(pid)!!.recordsSyncedAt)
    }

    @Test fun pullCreatesRecordWithPhotosAndLeavesVideosForWhenOpened() {
        val e = setup()
        var r = addMedia(e, local(e), "a.jpg", "photo")
        r = addMedia(e, r, "v.mp4", "video")
        e.store.delete(r.id)
        e.remote.server[r.id] = serverCopy(r, 3)
        e.remote.blobs["${r.id}/media/a.jpg"] = "photo".toByteArray(); e.remote.blobs["${r.id}/media/v.mp4"] = "video".toByteArray()
        val engine = SyncEngine(e.root, e.remote)
        val rep = runBlocking { engine.sync(listOf(pid), "u1").getValue(pid) }
        val got = e.store.load(r.id)!!
        assertEquals(1, rep.created)
        assertEquals(3, got.serverRev); assertEquals("u2", got.createdBy); assertEquals("Bình", got.createdByName); assertFalse(got.isSyncDirty); assertEquals(pid, got.projectId)
        assertTrue(File(e.store.dir(r.id), "media/a.jpg").isFile)
        assertFalse(File(e.store.dir(r.id), "media/v.mp4").isFile)
        runBlocking { engine.downloadMedia(pid, r.id) }
        assertTrue(File(e.store.dir(r.id), "media/v.mp4").isFile)
    }

    @Test fun serverNewerOverwritesCleanButFlagsDirtyAsConflict() {
        val e = setup()
        val clean = local(e).let { it.copy(serverRev = 1, erpUploadedAt = it.updatedAt, createdBy = "u2").also(e.store::save) }
        val dirty = local(e).let { it.copy(serverRev = 1, createdBy = "u1", erpUploadedAt = "2025-12-31T08:00:00+07:00").also(e.store::save) }
        e.remote.server[clean.id] = serverCopy(clean.copy(values = mapOf("P1" to TestData.text("server")), updatedAt = "2026-02-01T08:00:00+07:00"), 2)
        e.remote.server[dirty.id] = serverCopy(dirty.copy(values = mapOf("P1" to TestData.text("theirs"))), 2, by = "u1")
        val rep = run(e)
        assertEquals(1, rep.overwritten); assertEquals(1, rep.conflicts); assertTrue(e.remote.puts.isEmpty())
        assertEquals(JsonPrimitive("server"), e.store.load(clean.id)!!.values["P1"]!!.v); assertEquals(2, e.store.load(clean.id)!!.serverRev)
        assertTrue(e.store.load(dirty.id)!!.syncConflict); assertNull(e.store.load(dirty.id)!!.values["P1"])
    }

    @Test fun conflictOn409AndDeletedOnServer() {
        val e = setup()
        val r = local(e).let { it.copy(serverRev = 1, createdBy = "u1", erpUploadedAt = "2025-12-31T08:00:00+07:00").also(e.store::save) }
        val gone = local(e).let { it.copy(serverRev = 4, erpUploadedAt = it.updatedAt).also(e.store::save) }
        e.remote.server[r.id] = serverCopy(r, 1, "u1")
        e.remote.conflictOnPut = serverCopy(r, 2, "u1")
        val rep = run(e)
        assertEquals(1, rep.conflicts); assertEquals(1, rep.deletedLocal)
        assertTrue(e.store.load(r.id)!!.syncConflict); assertEquals(1, e.store.load(r.id)!!.serverRev)
        assertNull(e.store.load(gone.id))
    }

    @Test fun recordSentByOlderBuildIsAdoptedOrReplaced() {
        val e = setup()
        val same = local(e).let { it.copy(erpUploadedAt = it.updatedAt).also(e.store::save) }
        val differs = local(e).let { it.copy(erpUploadedAt = it.updatedAt).also(e.store::save) }
        e.remote.server[same.id] = serverCopy(same, 5)
        e.remote.server[differs.id] = serverCopy(differs.copy(values = mapOf("P1" to TestData.text("edited on the web")), updatedAt = "2026-02-01T08:00:00+07:00"), 6)
        val rep = run(e, Conflicting(e.remote))
        assertEquals(1, rep.pushed); assertEquals(1, rep.overwritten); assertEquals(0, rep.conflicts)
        assertEquals(5, e.store.load(same.id)!!.serverRev); assertFalse(e.store.load(same.id)!!.syncConflict)
        assertEquals(6, e.store.load(differs.id)!!.serverRev); assertEquals(JsonPrimitive("edited on the web"), e.store.load(differs.id)!!.values["P1"]!!.v)
    }

    @Test fun pendingDeleteIsSentAndRecordNotBroughtBack() {
        val e = setup()
        val r = local(e)
        e.store.delete(r.id)
        e.remote.server[r.id] = serverCopy(r, 2, "u1")
        PendingDeletes(e.root).add(PendingDelete(pid, r.id))
        val rep = run(e)
        assertEquals(listOf(r.id), e.remote.deleted); assertEquals(1, rep.deletedRemote); assertEquals(0, rep.created)
        assertTrue(PendingDeletes(e.root).load().isEmpty()); assertNull(e.store.load(r.id))
    }

    @Test fun allPagesAreReadAndTheCursorIsSavedOnlyAtTheEnd() {
        val e = setup()
        val a = local(e); val b = local(e)
        e.store.delete(a.id); e.store.delete(b.id)
        val sa = serverCopy(a, 1); val sb = serverCopy(b, 1)
        e.remote.server[a.id] = sa; e.remote.server[b.id] = sb
        e.remote.pages = listOf(listOf(sa), listOf(sb))
        run(e)
        assertNotNull(e.store.load(a.id)); assertNotNull(e.store.load(b.id))
        assertEquals(2, e.remote.pulls.size); assertEquals("1", e.remote.pulls[1].second)
        assertEquals("T1", e.projects.get(pid)!!.recordsSyncedAt)
        run(e)
        assertEquals("T1", e.remote.pulls[2].first)
    }

    @Test fun openRecordIsNotOverwrittenAndKeepsTheCursorBack() {
        val e = setup()
        val r = local(e).let { it.copy(serverRev = 1, erpUploadedAt = it.updatedAt, createdBy = "u2").also(e.store::save) }
        e.remote.server[r.id] = serverCopy(r.copy(values = mapOf("P1" to TestData.text("server"))), 2)
        val rep = run(e, hooks = SyncHooks(isOpen = { true }))
        assertTrue(rep.incomplete); assertEquals(1, e.store.load(r.id)!!.serverRev)
        assertNull(e.projects.get(pid)!!.recordsSyncedAt)
    }

    @Test fun permissionsAreStoredAndOthersDirtyRecordsAreNotPushed() {
        val e = setup()
        val theirs = local(e).copy(createdBy = "u2", serverRev = 1).also(e.store::save)
        e.remote.server[theirs.id] = serverCopy(theirs, 1)
        run(e)
        assertTrue(e.remote.puts.isEmpty())
        e.remote.canPost = false
        run(e)
        assertFalse(e.projects.get(pid)!!.canPost)
    }

    @Test fun offlineStopsTheRunAndKeepsCursor() {
        val e = setup()
        local(e)
        e.remote.offline = true
        try { run(e); fail("expected IOException") } catch (_: IOException) {}
        assertNull(e.projects.get(pid)!!.recordsSyncedAt)
    }

    @Test fun pruneRemovesCleanRecordsOfOtherAccountAndLostProjects() {
        val e = setup()
        val old = local(e).let { it.copy(createdBy = "u1", erpUploadedAt = it.updatedAt).also(e.store::save) }
        val mine = local(e).let { it.copy(createdBy = "u2", erpUploadedAt = it.updatedAt).also(e.store::save) }
        val dirtyOld = local(e).copy(createdBy = "u1").also(e.store::save)
        val engine = SyncEngine(e.root, e.remote)
        assertEquals(listOf(old.id), runBlocking { engine.prune("u2", "u1", emptySet()) })
        assertNotNull(e.store.load(mine.id)); assertNotNull(e.store.load(dirtyOld.id))
        assertEquals(listOf(mine.id), runBlocking { engine.prune("u2", "u2", setOf(pid)) })
        assertNotNull(e.store.load(dirtyOld.id))
    }
}
