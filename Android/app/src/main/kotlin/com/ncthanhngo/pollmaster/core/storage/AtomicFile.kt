package com.ncthanhngo.pollmaster.core.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes [bytes] to [target] via a temp file in the same directory, then renames over the target. */
fun writeAtomically(target: File, bytes: ByteArray) {
    val dir = target.parentFile ?: error("No parent for $target")
    dir.mkdirs()
    val tmp = File(dir, target.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
