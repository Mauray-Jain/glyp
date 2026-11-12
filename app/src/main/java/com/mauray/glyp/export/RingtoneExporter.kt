package com.mauray.glyp.export

import com.mauray.glyp.audio.PcmAudio
import com.mauray.glyp.audio.Timebase
import com.mauray.glyp.model.LightTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything an export needs: the song as its original file and as decoded [pcm] (for
 * transcoding), and [lights] as a snapshot, since it is read off the main thread. With
 * [range] (light frames) only that part of the song is exported.
 */
class ExportJob(
    val title: String,
    val audioFile: File,
    val pcm: PcmAudio,
    val lights: LightTrack,
    val durationMs: Long,
    val range: IntRange? = null,
)

/**
 * Exports a project as a Glyph ringtone: gets the song as Ogg Opus (as is, or transcoded),
 * tags it with [GlyphRingtone] and hands it to the [installer].
 */
class RingtoneExporter(private val workDir: File, private val installer: RingtoneInstaller) {

    /** Cancelling stops the encoding at its next progress report; nothing is saved then. */
    suspend fun export(job: ExportJob, onProgress: (Float) -> Unit = {}): Ringtone = withContext(Dispatchers.IO) {
        val context = coroutineContext
        val audio = opusAudio(job) { context.ensureActive(); onProgress(it) }
        // A part of the song: its lights start at the part's first frame, as does its audio.
        val range = job.range
        val lights = if (range == null) job.lights else job.lights.slice(range)
        val durationMs = if (range == null) job.durationMs else Timebase.msAt(range.last + 1) - Timebase.msAt(range.first)
        val ringtone = GlyphRingtone.build(job.title, lights, durationMs, audio)
        ensureActive()
        installer.install(ringtone, job.title).also { onProgress(1f) }
    }

    /**
     * The original file when it already is Ogg Opus and the whole song is wanted, otherwise
     * the song (or its part) transcoded: an Opus original can't be cut losslessly here.
     */
    private fun opusAudio(job: ExportJob, onProgress: (Float) -> Unit): ByteArray {
        val range = job.range
        if (range == null) {
            val original = job.audioFile.readBytes()
            if (OggOpus.isOggOpus(original)) return original
        }
        val opus = File(workDir.apply { mkdirs() }, "audio.ogg")
        try {
            OpusTranscoder.encode(job.pcm, opus, range?.let { pcmFrames(job.pcm, it) }) { onProgress(it * 0.9f) }
            return opus.readBytes()
        } finally {
            opus.delete()
        }
    }

    internal companion object {
        /** The PCM frames that play during light frames [range], clamped to the song. */
        fun pcmFrames(pcm: PcmAudio, range: IntRange): IntRange {
            fun at(frame: Int) = (Timebase.msAt(frame) * pcm.sampleRate / 1000).toInt().coerceIn(0, pcm.frameCount)
            return at(range.first) until at(range.last + 1)
        }
    }
}
