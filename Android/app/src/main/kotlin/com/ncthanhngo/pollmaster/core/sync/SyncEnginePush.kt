package com.ncthanhngo.pollmaster.core.sync

import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.rawRecordCode
import com.ncthanhngo.pollmaster.core.project.Project
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime

internal enum class PushOutcome { Pushed, Conflict, Replaced, Skipped }

/** Largest media file the server takes (Cloudflare caps a request at 100 MB). */
internal const val MAX_MEDIA_BYTES = 95L * 1024 * 1024

private class LocalMedia(val name: String, val file: File, val sha: String)

private fun flatName(rel: String) = rel.removePrefix("media/")

private fun isUploadable(rel: String) = rel.startsWith("media/") && flatName(rel).isNotEmpty() && '/' !in flatName(rel)

internal fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Pushes one record (schema 10.2): PUT record, upload the media the server lacks, PUT again, then remember the answer. */
internal suspend fun SyncEngine.push(id: String, project: Project): PushOutcome {
    val record = records.load(id) ?: return PushOutcome.Skipped
    val template = templates.get(record.templateId, record.templateVersion)
        ?: throw SyncRecordFailure("Thiếu mẫu ${record.templateId} ${record.templateVersion} trên máy.")
    val startedAt = now()
    val files = localMedia(project.id, record)
    val body = SyncPutBody(
        baseRev = record.serverRev ?: 0, title = recordTitle(template, record), recordCode = template.rawRecordCode(record),
        templateTitle = template.title, media = files.associate { it.name to it.sha }, data = SyncApply.pushData(record),
    )
    var result = remote.put(project.id, id, body)
    if (result is SyncPutResult.MediaMissing) {
        for (name in result.names) {
            val f = files.firstOrNull { it.name == name } ?: throw SyncRecordFailure("Thiếu tệp $name trên máy.")
            remote.putMedia(project.id, id, flatName(name), f.file, f.sha)
        }
        result = remote.put(project.id, id, body)
    }
    return when (result) {
        is SyncPutResult.Ok -> {
            val saved = update(id) { SyncApply.pushed(it, project.id, result.record, startedAt) }
            val pdf = saved?.let { s -> hooks.makePdf?.invoke(s) }
            if (pdf != null) try { remote.putPdf(project.id, id, pdf) } catch (_: Exception) { /* best effort */ }
            PushOutcome.Pushed
        }
        is SyncPutResult.Conflict -> resolveConflict(result.current, id, project, startedAt)
        is SyncPutResult.MediaMissing -> throw SyncRecordFailure("Máy chủ chưa nhận được tệp ${result.names.firstOrNull().orEmpty()}")
    }
}

/** 409 `REV_CONFLICT`. A record never synced here (sent by an older build) is matched with the server copy; otherwise the user decides. */
private suspend fun SyncEngine.resolveConflict(current: SyncRecord, id: String, project: Project, startedAt: OffsetDateTime): PushOutcome {
    val local = records.load(id)
    if (local != null && local.serverRev == null) {
        if (SyncPlanner.sameContent(SyncApply.pushData(local), current.data)) {
            update(id) { SyncApply.pushed(it, project.id, current, startedAt) }
            return PushOutcome.Pushed
        }
        if (local.erpUploadedAt != null && !local.isSyncDirty) { // untouched since the old upload: the server copy wins
            if (hooks.isOpen(id)) return PushOutcome.Skipped
            val server = SyncApply.record(current, project.id)
            records.save(SyncApply.overwrite(local, server))
            fetchMedia(project.id, server, current.media, includeVideo = false)
            return PushOutcome.Replaced
        }
    }
    update(id) { it.copy(syncConflict = true) }
    return PushOutcome.Conflict
}

/** Existing media files of the record with their hashes; files missing here are fetched from the server first. */
private suspend fun SyncEngine.localMedia(pid: String, record: Record): List<LocalMedia> {
    val out = mutableListOf<LocalMedia>()
    for (rel in SyncApply.mediaFiles(record).distinct().sorted().filter(::isUploadable)) {
        val file = File(records.dir(record.id), rel)
        if (!file.isFile) {
            try { download(pid, record.id, rel, null) } catch (_: SyncRecordFailure) { throw SyncRecordFailure("Chưa có tệp ${flatName(rel)} trên máy.") }
        }
        if (file.length() > MAX_MEDIA_BYTES) throw SyncRecordFailure("Tệp ${flatName(rel)} lớn hơn 95 MB.")
        out += LocalMedia(rel, file, try { sha256(file) } catch (_: IOException) { throw SyncRecordFailure("Không đọc được tệp ${flatName(rel)}.") })
    }
    return out
}

/**
 * Downloads the record's media that are missing here (or differ from [info]). Videos only with [includeVideo]. Never throws except
 * for cancellation: returns false when something is left for the next run (offline, error).
 */
internal suspend fun SyncEngine.fetchMedia(pid: String, record: Record, info: List<SyncMediaInfo> = emptyList(), includeVideo: Boolean): Boolean {
    val want = info.associate { it.name to it.sha256.lowercase() }
    for (rel in SyncApply.mediaFiles(record).distinct().sorted().filter(::isUploadable)) {
        if (!includeVideo && SyncPlanner.isVideo(rel)) continue
        val dest = File(records.dir(record.id), rel)
        if (dest.isFile && (want[rel] == null || runCatching { sha256(dest) }.getOrNull() == want[rel])) continue
        try {
            download(pid, record.id, rel, want[rel])
        } catch (_: SyncRecordFailure) { // gone or refused: the next run tries again, the others go on
        } catch (e: IOException) { return false } // offline: stop
    }
    return true
}

/** Opening a record: its videos (and anything else missing) come down now. */
suspend fun SyncEngine.downloadMedia(projectId: String, recordId: String) {
    val r = records.load(recordId) ?: return
    fetchMedia(projectId, r, includeVideo = true)
}

private suspend fun SyncEngine.download(pid: String, recordId: String, rel: String, expected: String?) {
    val dest = File(records.dir(recordId), rel)
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, dest.name + ".part")
    tmp.delete()
    try {
        remote.getMedia(pid, recordId, flatName(rel), tmp)
        if (expected != null && sha256(tmp) != expected) throw SyncRecordFailure("Tệp ${flatName(rel)} tải về bị lỗi.")
        java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { tmp.delete() }
}
