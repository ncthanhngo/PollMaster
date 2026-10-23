package com.ncthanhngo.pollmaster.platform

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records AAC audio into an `.m4a` file. One recording at a time. */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    val isRecording: Boolean get() = recorder != null

    fun start(target: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(target.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            target.delete()
            throw e
        }
        recorder = r
    }

    /** Stops and releases; false when nothing usable was recorded (stopped immediately). */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (_: RuntimeException) { // no audio data yet
            false
        } finally {
            r.release()
        }
    }
}

/** Length of an audio file in whole seconds, or null if unreadable. */
fun audioDurationSeconds(file: File): Long? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { (it + 500) / 1000 }
    } finally {
        retriever.release()
    }
}.getOrNull()

/** Simple one-file player; [onDone] fires when playback ends. */
class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onDone: () -> Unit) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { stop(); onDone() }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.release()
        player = null
    }
}
