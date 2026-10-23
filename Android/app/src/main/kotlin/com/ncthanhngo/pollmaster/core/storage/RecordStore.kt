package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import java.io.File

/** Outcome of reading one record folder. */
sealed interface StoredRecord {
    data class Loaded(val record: Record) : StoredRecord

    /** record.json exists in folder [id] but cannot be decoded. */
    data class Broken(val id: String, val error: String) : StoredRecord
}

/** `<root>/records/<recordId>/record.json` + `media/`. */
class RecordStore(private val root: File) {
    private val recordsDir = File(root, "records")

    fun dir(recordId: String): File = File(recordsDir, recordId)

    fun mediaDir(recordId: String): File = File(dir(recordId), "media").also { it.mkdirs() }

    fun load(recordId: String): Record? = (read(recordId) as? StoredRecord.Loaded)?.record

    /** Reads one folder; null when it has no record.json. */
    fun read(recordId: String): StoredRecord? {
        val file = File(dir(recordId), RECORD_FILE)
        if (!file.isFile) return null
        return try {
            StoredRecord.Loaded(PollJson.decodeFromString(Record.serializer(), file.readText()))
        } catch (e: Exception) { // unreadable file or invalid JSON: report it, never hide it
            StoredRecord.Broken(recordId, (e.message ?: e.javaClass.simpleName).lineSequence().first().take(200))
        }
    }

    /** Every record folder: readable ones most recently updated first, then broken ones by id. */
    fun listAll(): List<StoredRecord> {
        val all = recordsDir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { read(it.name) }
        return all.filterIsInstance<StoredRecord.Loaded>().sortedByDescending { it.record.updatedAt } +
            all.filterIsInstance<StoredRecord.Broken>().sortedBy { it.id }
    }

    /** Readable records only, most recently updated first. */
    fun list(): List<Record> = listAll().filterIsInstance<StoredRecord.Loaded>().map { it.record }

    fun save(record: Record) {
        val bytes = PollJson.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8)
        writeAtomically(File(dir(record.id), RECORD_FILE), bytes)
    }

    fun delete(recordId: String): Boolean = dir(recordId).deleteRecursively()

    /** Bytes used by all records (including media). */
    fun usedBytes(): Long = recordsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val RECORD_FILE = "record.json"
    }
}
