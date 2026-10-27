package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.format.parseInstant
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import com.ncthanhngo.pollmaster.core.sync.ConflictInfo
import com.ncthanhngo.pollmaster.core.sync.RecordChange
import com.ncthanhngo.pollmaster.core.sync.SyncApply
import com.ncthanhngo.pollmaster.core.sync.SyncEngine
import com.ncthanhngo.pollmaster.core.sync.SyncHooks
import com.ncthanhngo.pollmaster.core.sync.SyncPage
import com.ncthanhngo.pollmaster.core.sync.SyncPlanner
import com.ncthanhngo.pollmaster.core.sync.SyncPutBody
import com.ncthanhngo.pollmaster.core.sync.SyncPutResult
import com.ncthanhngo.pollmaster.core.sync.SyncRecord
import com.ncthanhngo.pollmaster.core.sync.SyncRecordFailure
import com.ncthanhngo.pollmaster.core.sync.SyncRef
import com.ncthanhngo.pollmaster.core.sync.SyncRemote
import com.ncthanhngo.pollmaster.core.sync.inspectConflict
import com.ncthanhngo.pollmaster.core.sync.isSyncDirty
import com.ncthanhngo.pollmaster.core.sync.keepMine
import com.ncthanhngo.pollmaster.core.sync.takeServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A server that honours `base_rev`: stale revision -> 409 with the current copy; same content -> 200 (what the real backend does). */
private class RevRemote : SyncRemote {
    val server = LinkedHashMap<String, SyncRecord>()
    var log: List<RecordChange> = emptyList()
    val puts = mutableListOf<SyncPutBody>()
    val pdfs = mutableListOf<String>()

    override suspend fun pull(projectId: String, since: String?, cursor: String?) =
        SyncPage("T1", true, false, emptyList(), server.values.map { SyncRef(it.recordId, it.rev) })

    var gets = 0
    override suspend fun get(projectId: String, recordId: String): SyncRecord? { gets++; return server[recordId] }

    override suspend fun put(projectId: String, recordId: String, body: SyncPutBody): SyncPutResult {
        puts += body
        val cur = server[recordId]
        if (cur != null && body.baseRev != cur.rev) {
            return if (SyncPlanner.sameContent(body.data, cur.data)) SyncPutResult.Ok(cur) else SyncPutResult.Conflict(cur)
        }
        return SyncPutResult.Ok(SyncRecord(recordId, (cur?.rev ?: 0) + 1, 1, "u1", "An", body.data).also { server[recordId] = it })
    }

    override suspend fun putMedia(projectId: String, recordId: String, name: String, file: File, sha256: String) {}
    override suspend fun getMedia(projectId: String, recordId: String, name: String, dest: File) { throw SyncRecordFailure("404") }
    override suspend fun putPdf(projectId: String, recordId: String, file: File) { pdfs += recordId }
    override suspend fun delete(projectId: String, recordId: String) {}
    override suspend fun changes(projectId: String, recordId: String) = log
}

class SyncResolveTest {
    private val pid = "11111111-1111-1111-1111-111111111111"

    private class Env(val root: File, val remote: RevRemote, val record: Record) { val store = RecordStore(root) }

    private fun setup(): Env {
        val root = TestData.tempDir()
        TemplateStore(root).install(TestData.templateText, overwrite = true)
        ProjectStore(root).save(Project(id = pid, code = "P", name = "P"))
        val old = "2026-10-27T08:00:00+07:00"
        val r = TestData.emptyRecord().copy(
            id = "rec-1", projectId = pid, seq = 1, createdBy = "u1", serverRev = 1, updatedAt = old, erpUploadedAt = "2026-10-27T07:00:00+07:00",
            values = mapOf("P1" to FieldValue(v = JsonPrimitive("mine"))), syncConflict = true,
        )
        RecordStore(root).save(r)
        val remote = RevRemote()
        val theirs = r.copy(values = mapOf("P1" to FieldValue(v = JsonPrimitive("theirs"))))
        remote.server[r.id] = SyncRecord(r.id, 3, 1, "u1", "An", SyncApply.pushData(theirs), updatedByName = "Bình")
        return Env(root, remote, r)
    }

    private fun engine(e: Env, hooks: SyncHooks = SyncHooks()) = SyncEngine(e.root, e.remote, hooks)

    @Test fun keepMineOverwritesOnTopOfTheServerRevision() = runBlocking {
        val e = setup()
        engine(e).keepMine(pid, e.record.id)
        assertEquals(listOf(1, 3), e.remote.puts.map { it.baseRev })
        assertEquals(4, e.remote.server.getValue(e.record.id).rev)
        assertTrue(e.remote.server.getValue(e.record.id).data.toString().contains("mine"))
        val saved = e.store.load(e.record.id)!!
        assertEquals(4, saved.serverRev); assertFalse(saved.syncConflict); assertFalse(saved.isSyncDirty)
    }

    @Test fun takeServerReplacesTheDeviceCopyAndKeepsItsPlace() = runBlocking {
        val e = setup()
        e.store.save(e.record.copy(lastPosition = com.ncthanhngo.pollmaster.core.model.Position("A", "A-01")))
        val got = engine(e).takeServer(pid, e.record.id)
        assertEquals(1, e.remote.gets); assertTrue(e.remote.puts.isEmpty()); assertEquals(3, e.remote.server.getValue(e.record.id).rev)      // read with GET, nothing written
        val saved = e.store.load(e.record.id)!!
        assertEquals(got, saved)
        assertEquals(JsonPrimitive("theirs"), saved.values["P1"]?.v)
        assertEquals(3, saved.serverRev); assertFalse(saved.syncConflict); assertFalse(saved.isSyncDirty)
        assertEquals("A-01", saved.lastPosition?.field)
    }

    @Test fun takeServerWithIdenticalContentJustClearsTheFlag() = runBlocking {
        val e = setup()
        e.remote.server[e.record.id] = SyncRecord(e.record.id, 3, 1, "u1", "An", SyncApply.pushData(e.record))
        engine(e).takeServer(pid, e.record.id)
        val saved = e.store.load(e.record.id)!!
        assertEquals(3, saved.serverRev); assertFalse(saved.syncConflict); assertEquals(JsonPrimitive("mine"), saved.values["P1"]?.v)
    }

    @Test fun takeServerOfARecordTheServerLostFailsAndKeepsTheDeviceCopy() = runBlocking {
        val e = setup()
        e.remote.server.clear()
        val failure = runCatching { engine(e).takeServer(pid, e.record.id) }.exceptionOrNull()
        assertTrue(failure is SyncRecordFailure)
        val kept = e.store.load(e.record.id)!!
        assertEquals(JsonPrimitive("mine"), kept.values["P1"]?.v); assertTrue(kept.syncConflict); assertTrue(e.remote.puts.isEmpty())
    }

    @Test fun inspectTellsWhoEditedOrThatTheServerLostIt() = runBlocking {
        val e = setup()
        e.remote.log = listOf(RecordChange(3, "Bình", "2026-10-27T09:05:00Z"))
        assertEquals(ConflictInfo.Edited("Bình", parseInstant("2026-10-27T09:05:00Z")), engine(e).inspectConflict(pid, e.record.id))
        e.remote.server.clear()
        assertEquals(ConflictInfo.Deleted, engine(e).inspectConflict(pid, e.record.id))
    }

    @Test fun pdfIsMadeAndSentOnlyForACompletedRecord() = runBlocking {
        val e = setup()
        var made = 0
        val hooks = SyncHooks(makePdf = { made++; File("/tmp/x.pdf") })
        e.store.save(e.record.copy(syncConflict = false, serverRev = null))
        e.remote.server.clear()
        SyncEngine(e.root, e.remote, hooks).sync(listOf(pid), "u1")
        assertEquals(0, made); assertTrue(e.remote.pdfs.isEmpty()); assertEquals(1, e.store.load(e.record.id)!!.serverRev)
        e.store.save(e.store.load(e.record.id)!!.copy(status = Record.STATUS_COMPLETED, completedAt = "2026-10-27T12:00:00+07:00",
            updatedAt = "2026-10-27T12:00:00+07:00", erpUploadedAt = "2026-10-27T11:00:00+07:00"))
        SyncEngine(e.root, e.remote, hooks).sync(listOf(pid), "u1")
        assertEquals(1, made); assertEquals(listOf(e.record.id), e.remote.pdfs)
    }
}
