package com.mauray.glyphstudio.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mauray.glyphstudio.audio.PcmAudio
import java.io.File
import java.nio.ByteOrder

/** Encodes PCM to an Ogg Opus file with the platform encoder (Android 10+). */
object OpusTranscoder {

    private const val OPUS_RATE = 48_000
    private const val BITRATE = 128_000
    private const val TIMEOUT_US = 10_000L

    fun encode(pcm: PcmAudio, output: File, onProgress: (Float) -> Unit = {}) {
        val channels = minOf(pcm.channels, 2)
        val samples = resample(downmix(pcm, channels), pcm.sampleRate, OPUS_RATE, channels)
        val totalFrames = samples.size / channels

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
        var inputPos = 0 // in samples (all channels)
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val count = minOf(buf.capacity() / 2, samples.size - inputPos)
                        val timeUs = (inputPos / channels).toLong() * 1_000_000 / OPUS_RATE
                        if (count <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(samples, inputPos, count)
                            codec.queueInputBuffer(inIndex, 0, count * 2, timeUs, 0)
                            inputPos += count
                            onProgress(inputPos.toFloat() / samples.size)
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

    private fun downmix(pcm: PcmAudio, channels: Int): ShortArray {
        if (pcm.channels == channels) return pcm.samples
        val frames = pcm.frameCount
        val out = ShortArray(frames * channels)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                // Fold extra channels evenly into left/right.
                var sum = 0
                var n = 0
                for (src in c until pcm.channels step channels) { sum += pcm.samples[i * pcm.channels + src]; n++ }
                out[i * channels + c] = (sum / n).toShort()
            }
        }
        return out
    }

    /** Linear-interpolation resampler; fine for ringtones, not for mastering. */
    private fun resample(input: ShortArray, fromRate: Int, toRate: Int, channels: Int): ShortArray {
        if (fromRate == toRate) return input
        val inFrames = input.size / channels
        val outFrames = (inFrames.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(outFrames * channels)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outFrames) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            for (c in 0 until channels) {
                val a = input[i0 * channels + c]
                val b = input[i1 * channels + c]
                out[i * channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }
}
