package com.mauray.glyphstudio.export

import com.mauray.glyphstudio.audio.AudioDecoder
import com.mauray.glyphstudio.model.LightTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Everything an export needs; [lights] is a snapshot, read off the main thread. */
class ExportJob(val title: String, val audioFile: File, val lights: LightTrack, val durationMs: Long)

/**
 * Exports a project as a Glyph ringtone: gets the song as Ogg Opus (as is, or transcoded),
 * tags it with [GlyphRingtone] and hands it to the [installer].
 */
class RingtoneExporter(private val workDir: File, private val installer: RingtoneInstaller) {

    suspend fun export(job: ExportJob, onProgress: (Float) -> Unit = {}): Ringtone = withContext(Dispatchers.IO) {
        val ringtone = GlyphRingtone.build(job.title, job.lights, job.durationMs, opusAudio(job.audioFile, onProgress))
        installer.install(ringtone, job.title).also { onProgress(1f) }
    }

    private fun opusAudio(audioFile: File, onProgress: (Float) -> Unit): ByteArray {
        val original = audioFile.readBytes()
        if (OggOpus.isOggOpus(original)) return original
        val pcm = AudioDecoder.decode(audioFile) { onProgress(it * 0.4f) }
        val opus = File(workDir.apply { mkdirs() }, "audio.ogg")
        try {
            OpusTranscoder.encode(pcm, opus) { onProgress(0.4f + it * 0.5f) }
            return opus.readBytes()
        } finally {
            opus.delete()
        }
    }
}
