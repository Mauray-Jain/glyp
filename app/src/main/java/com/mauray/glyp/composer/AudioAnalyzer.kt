package com.mauray.glyp.composer

import com.mauray.glyp.glyph.GlyphZones
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-frame (60 fps) features of a track. Everything is normalised to roughly 0..1 so the
 * composer styles can map values straight to brightness.
 */
class Analysis(
    val frameCount: Int,
    /** [frameCount] x [BANDS] log-spaced band levels, low to high. */
    val bands: FloatArray,
    val bass: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
    val loudness: FloatArray,
    /** Positive spectral flux per range; peaks mark note/drum onsets. */
    val bassFlux: FloatArray,
    val midFlux: FloatArray,
    val highFlux: FloatArray,
) {
    fun band(frame: Int, band: Int) = bands[frame * BANDS + band]

    companion object {
        /** One band per segment of strip C. */
        val BANDS = GlyphZones.Strip.C.zones.count()
    }
}

object AudioAnalyzer {

    private const val TARGET_RATE = 22_050
    private const val FFT_SIZE = 1024
    private const val MIN_HZ = 40.0
    private const val MAX_HZ = 11_000.0

    fun analyze(mono: FloatArray, sampleRate: Int, frameCount: Int, onProgress: (Float) -> Unit = {}): Analysis {
        val factor = max(1, sampleRate / TARGET_RATE)
        val signal = if (factor == 1) mono else downsample(mono, factor)
        val rate = sampleRate.toDouble() / factor

        val fft = Fft(FFT_SIZE)
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val bins = FFT_SIZE / 2
        val logMag = FloatArray(bins)
        val prevLogMag = FloatArray(bins)

        val nyquist = rate / 2
        val bandEdges = IntArray(Analysis.BANDS + 1) { i ->
            val hz = MIN_HZ * (min(MAX_HZ, nyquist) / MIN_HZ).pow(i.toDouble() / Analysis.BANDS)
            (hz / rate * FFT_SIZE).roundToInt().coerceIn(1, bins)
        }
        val bassRange = binRange(40.0, 160.0, rate, bins)
        val midRange = binRange(160.0, 2_500.0, rate, bins)
        val highRange = binRange(2_500.0, min(10_000.0, nyquist), rate, bins)

        val bands = FloatArray(frameCount * Analysis.BANDS)
        val bass = FloatArray(frameCount)
        val mid = FloatArray(frameCount)
        val high = FloatArray(frameCount)
        val loudness = FloatArray(frameCount)
        val bassFlux = FloatArray(frameCount)
        val midFlux = FloatArray(frameCount)
        val highFlux = FloatArray(frameCount)

        for (t in 0 until frameCount) {
            val center = (t * rate / GlyphZones.FPS).toInt()
            val start = center - FFT_SIZE / 2
            var energy = 0f
            for (i in 0 until FFT_SIZE) {
                val idx = start + i
                val s = if (idx in signal.indices) signal[idx] else 0f
                energy += s * s
                re[i] = s * window[i]
                im[i] = 0f
            }
            loudness[t] = sqrt(energy / FFT_SIZE)
            fft.transform(re, im)
            for (k in 0 until bins) {
                logMag[k] = ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]))
            }
            for (b in 0 until Analysis.BANDS) {
                val lo = bandEdges[b]
                val hi = max(bandEdges[b + 1], lo + 1)
                bands[t * Analysis.BANDS + b] = mean(logMag, lo, hi)
            }
            bass[t] = mean(logMag, bassRange.first, bassRange.last + 1)
            mid[t] = mean(logMag, midRange.first, midRange.last + 1)
            high[t] = mean(logMag, highRange.first, highRange.last + 1)
            if (t > 0) {
                bassFlux[t] = flux(logMag, prevLogMag, bassRange)
                midFlux[t] = flux(logMag, prevLogMag, midRange)
                highFlux[t] = flux(logMag, prevLogMag, highRange)
            }
            logMag.copyInto(prevLogMag)
            if (t % 256 == 0) onProgress(t.toFloat() / frameCount)
        }

        normalize(bass); normalize(mid); normalize(high); normalize(loudness)
        normalize(bassFlux); normalize(midFlux); normalize(highFlux)
        normalizeBands(bands, frameCount)
        onProgress(1f)
        return Analysis(frameCount, bands, bass, mid, high, loudness, bassFlux, midFlux, highFlux)
    }

    /**
     * Picks onset frames from a flux curve: local maxima above an adaptive (moving median)
     * threshold, at least [minGapFrames] apart. [sensitivity] 0..1, higher finds more onsets.
     */
    fun onsets(flux: FloatArray, sensitivity: Float, minGapFrames: Int = 6): BooleanArray {
        val result = BooleanArray(flux.size)
        val multiplier = 2.2f - 1.1f * sensitivity
        val delta = 0.12f - 0.10f * sensitivity
        val radius = 15
        val window = FloatArray(radius * 2 + 1)
        var last = -minGapFrames
        for (t in flux.indices) {
            val lo = max(0, t - radius)
            val hi = min(flux.size - 1, t + radius)
            val n = hi - lo + 1
            flux.copyInto(window, 0, lo, hi + 1)
            window.sort(0, n)
            val threshold = window[n / 2] * multiplier + delta
            if (flux[t] <= threshold || t - last < minGapFrames) continue
            var isPeak = true
            for (j in max(0, t - 3)..min(flux.size - 1, t + 3)) {
                if (flux[j] > flux[t]) { isPeak = false; break }
            }
            if (isPeak) {
                result[t] = true
                last = t
            }
        }
        return result
    }

    /** Per-frame multiplier for an exponential release lasting about [releaseMs]. */
    fun decayFactor(releaseMs: Float): Float =
        exp(-1000f / (max(releaseMs, 1f) * GlyphZones.FPS) * 3f)

    private fun downsample(input: FloatArray, factor: Int): FloatArray {
        val out = FloatArray(input.size / factor)
        for (i in out.indices) {
            var sum = 0f
            for (j in 0 until factor) sum += input[i * factor + j]
            out[i] = sum / factor
        }
        return out
    }

    private fun binRange(loHz: Double, hiHz: Double, rate: Double, bins: Int): IntRange {
        val lo = (loHz / rate * FFT_SIZE).roundToInt().coerceIn(1, bins - 1)
        val hi = (hiHz / rate * FFT_SIZE).roundToInt().coerceIn(lo, bins - 1)
        return lo..hi
    }

    private fun mean(a: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        for (i in from until to) s += a[i]
        return s / (to - from)
    }

    private fun flux(cur: FloatArray, prev: FloatArray, range: IntRange): Float {
        var s = 0f
        for (k in range) s += max(0f, cur[k] - prev[k])
        return s / (range.last - range.first + 1)
    }

    /** Scales so the 98th percentile maps to 1, then clamps. */
    private fun normalize(a: FloatArray) {
        if (a.isEmpty()) return
        val sorted = a.copyOf().also { it.sort() }
        val ref = sorted[((sorted.size - 1) * 0.98).toInt()].takeIf { it > 1e-6f } ?: return
        for (i in a.indices) a[i] = (a[i] / ref).coerceIn(0f, 1f)
    }

    /** Bands share one reference per band so quiet treble still shows movement. */
    private fun normalizeBands(bands: FloatArray, frames: Int) {
        val column = FloatArray(frames)
        for (b in 0 until Analysis.BANDS) {
            for (t in 0 until frames) column[t] = bands[t * Analysis.BANDS + b]
            // Remove each band's floor so steady background noise stays dark.
            val sorted = column.copyOf().also { it.sort() }
            val floor = sorted[(frames - 1) / 5]
            for (t in 0 until frames) column[t] = max(0f, column[t] - floor)
            normalize(column)
            for (t in 0 until frames) bands[t * Analysis.BANDS + b] = column[t]
        }
    }
}
