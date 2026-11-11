package com.mauray.glyp.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Interleaved 16-bit PCM: the first [frameCount] frames of [samples]. The array may be longer,
 * so a whole song is never copied just to trim it.
 */
class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Int = samples.size / channels,
) {
    val durationMs get() = frameCount * 1000L / sampleRate

    /** Channel [c] of frame [i]. */
    operator fun get(i: Int, c: Int): Short = samples[i * channels + c]

    /** Frame [i] folded to mono, in -1..1. */
    fun monoAt(i: Int): Float {
        var sum = 0
        for (c in 0 until channels) sum += samples[i * channels + c]
        return sum / (channels * 32768f)
    }

    /** Mono float copy in -1..1 at 1/[decimation] of the rate, each value the mean of its frames. */
    fun toMono(decimation: Int = 1): FloatArray {
        val out = FloatArray(frameCount / decimation)
        for (i in out.indices) {
            var sum = 0f
            for (j in 0 until decimation) sum += monoAt(i * decimation + j)
            out[i] = sum / decimation
        }
        return out
    }
}

/** Decodes any format the platform supports (mp3, aac, ogg, opus, flac, wav, ...) to PCM. */
object AudioDecoder {

    fun decode(file: File, onProgress: (Float) -> Unit = {}): PcmAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in ${file.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val out = ShortGrowable(if (durationUs > 0) (durationUs * sampleRate * channels / 1_000_000).toInt() + 4096 else 1 shl 20)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset).limit(info.offset + info.size)
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            while (fb.hasRemaining()) out.add((fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.hasRemaining()) out.add(sb.get())
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        onProgress(1f)
        return PcmAudio(out.array, sampleRate, channels, out.size / channels)
    }

    private const val TIMEOUT_US = 10_000L
}

/** The first [size] values of [array] are the data; the rest is spare room. */
private class ShortGrowable(initial: Int) {
    var array = ShortArray(maxOf(initial, 1024))
        private set
    var size = 0
        private set

    fun add(v: Short) {
        // Grow by half: a whole song may already be in here.
        if (size == array.size) array = array.copyOf(array.size + array.size / 2)
        array[size++] = v
    }
}
