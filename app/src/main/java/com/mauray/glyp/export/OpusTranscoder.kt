package com.mauray.glyp.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mauray.glyp.audio.PcmAudio
import java.io.File
import java.nio.ByteOrder

/** Encodes PCM to an Ogg Opus file with the platform encoder (Android 10+). */
object OpusTranscoder {

    private const val OPUS_RATE = 48_000
    private const val BITRATE = 128_000
    private const val TIMEOUT_US = 10_000L

    fun encode(pcm: PcmAudio, output: File, onProgress: (Float) -> Unit = {}) {
        val channels = minOf(pcm.channels, 2)
        // Read a frame at a time: a resampled copy of a long song doesn't fit next to the PCM.
        val source = Resampled(pcm, channels, OPUS_RATE)
        val totalFrames = source.frameCount

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        val info = MediaCodec.BufferInfo()
        var inputFrame = 0
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val frames = minOf(buf.capacity() / (2 * channels), totalFrames - inputFrame)
                        val timeUs = inputFrame.toLong() * 1_000_000 / OPUS_RATE
                        if (frames <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val shorts = buf.asShortBuffer()
                            for (f in inputFrame until inputFrame + frames) {
                                for (c in 0 until channels) shorts.put(source[f, c])
                            }
                            codec.queueInputBuffer(inIndex, 0, frames * channels * 2, timeUs, 0)
                            inputFrame += frames
                            onProgress(inputFrame.toFloat() / totalFrames)
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && track >= 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (track >= 0) muxer.stop()
            muxer.release()
        }
        check(totalFrames > 0) { "Audio is empty" }
    }
}

/**
 * [pcm] with extra channels folded evenly into [channels] and linearly resampled to [rate]
 * (fine for ringtones, not for mastering), read a frame at a time.
 */
internal class Resampled(private val pcm: PcmAudio, private val channels: Int, rate: Int) {
    private val step = pcm.sampleRate.toDouble() / rate
    val frameCount = (pcm.frameCount.toLong() * rate / pcm.sampleRate).toInt()

    operator fun get(frame: Int, channel: Int): Short {
        val pos = frame * step
        val i0 = pos.toInt().coerceAtMost(pcm.frameCount - 1)
        val i1 = (i0 + 1).coerceAtMost(pcm.frameCount - 1)
        val a = folded(i0, channel)
        val b = folded(i1, channel)
        return (a + (b - a) * (pos - i0)).toInt().toShort()
    }

    private fun folded(frame: Int, channel: Int): Int {
        if (pcm.channels == channels) return pcm[frame, channel].toInt()
        var sum = 0
        var n = 0
        for (src in channel until pcm.channels step channels) { sum += pcm[frame, src]; n++ }
        return sum / n
    }
}
