package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.model.Record
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coalesces rapid record changes into one write after [delayMs] of quiet; [flush] writes immediately. */
class DebouncedSaver(
    private val scope: CoroutineScope,
    private val delayMs: Long = 500,
    private val onError: (Throwable) -> Unit = {},
    private val write: (Record) -> Unit,
) {
    private val lock = Any()
    private var pending: Record? = null
    private var job: Job? = null

    fun submit(record: Record) {
        synchronized(lock) {
            pending = record
            job?.cancel()
            job = scope.launch {
                delay(delayMs)
                flush()
            }
        }
    }

    /** Drops the pending change without writing it (the record was deleted or wiped). */
    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
            pending = null
        }
    }

    fun flush() {
        val toWrite = synchronized(lock) {
            job?.cancel()
            job = null
            pending.also { pending = null }
        } ?: return
        try {
            write(toWrite)
        } catch (e: Exception) {
            onError(e)
        }
    }
}
