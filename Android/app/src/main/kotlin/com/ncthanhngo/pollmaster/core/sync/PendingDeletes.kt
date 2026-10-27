package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.storage.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A record deleted on the device that the server still has (schema 10.5). */
@Serializable
data class PendingDelete(val projectId: String, val recordId: String)

/** `<AppData>/sync/pending-deletes.json`. */
class PendingDeletes(root: File) {
    private val file = File(root, "sync/pending-deletes.json")
    private val ser = ListSerializer(PendingDelete.serializer())

    @Synchronized
    fun load(): List<PendingDelete> =
        if (!file.isFile) emptyList() else runCatching { Json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun add(item: PendingDelete) {
        val list = load()
        if (item !in list) write(list + item)
    }

    @Synchronized
    fun remove(item: PendingDelete) {
        val list = load()
        if (item in list) write(list - item)
    }

    private fun write(list: List<PendingDelete>) = writeAtomically(file, Json.encodeToString(ser, list).toByteArray(Charsets.UTF_8))
}
