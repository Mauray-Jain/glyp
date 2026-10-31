package com.mauray.glyphstudio.export

import com.mauray.glyphstudio.audio.PcmAudio
import com.mauray.glyphstudio.model.LightTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything an export needs: the song as its original file and as decoded [pcm] (for
 * transcoding), and [lights] as a snapshot, since it is read off the main thread.
 */
class ExportJob(val title: String, val audioFile: File, val pcm: PcmAudio, val lights: LightTrack, val durationMs: Long)

/**
 * Exports a project as a Glyph ringtone: gets the song as Ogg Opus (as is, or transcoded),
 * tags it with [GlyphRingtone] and hands it to the [installer].
 */
class RingtoneExporter(private val workDir: File, private val installer: RingtoneInstaller) {

    suspend fun export(job: ExportJob, onProgress: (Float) -> Unit = {}): Ringtone = withContext(Dispatchers.IO) {
        val ringtone = GlyphRingtone.build(job.title, job.lights, job.durationMs, opusAudio(job, onProgress))
        installer.install(ringtone, job.title).also { onProgress(1f) }
    }

    /** The original file when it already is Ogg Opus, otherwise the song transcoded. */
    private fun opusAudio(job: ExportJob, onProgress: (Float) -> Unit): ByteArray {
        val original = job.audioFile.readBytes()
        if (OggOpus.isOggOpus(original)) return original
        val opus = File(workDir.apply { mkdirs() }, "audio.ogg")
        try {
            OpusTranscoder.encode(job.pcm, opus) { onProgress(it * 0.9f) }
            return opus.readBytes()
        } finally {
            opus.delete()
        }
    }
}
