package id.suaranote.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object Dsp {
    fun toFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    fun toShort(x: FloatArray): ShortArray =
        ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

    /** High-pass biquad orde-2 (Butterworth): membuang DC dan dengung rendah. In-place. */
    fun highPass(x: FloatArray, sampleRate: Int, fcHz: Double = 60.0) {
        val w0 = 2 * PI * fcHz / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / (2 * 0.7071)
        val a0 = 1 + alpha
        val b0 = (1 + cosw) / 2 / a0
        val b1 = -(1 + cosw) / a0
        val b2 = b0
        val a1 = -2 * cosw / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i].toDouble()
            val y = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = y
            x[i] = y.toFloat()
        }
    }

    private fun sinc(t: Double) = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)

    /** Turunkan laju sampel 2x (mis. 44100 -> 22050) dengan FIR low-pass windowed-sinc 63 tap. */
    fun decimate2(x: FloatArray): FloatArray {
        val taps = 63
        val half = taps / 2
        val fc = 0.23 // dinormalisasi terhadap laju asal; ~10 kHz pada 44100 Hz
        val h = DoubleArray(taps) { k ->
            2 * fc * sinc(2 * fc * (k - half)) * (0.54 - 0.46 * cos(2 * PI * k / (taps - 1)))
        }
        val sum = h.sum()
        for (k in h.indices) h[k] /= sum
        val out = FloatArray(x.size / 2)
        for (i in out.indices) {
            var acc = 0.0
            val c = 2 * i
            for (k in 0 until taps) {
                val idx = c + k - half
                if (idx in x.indices) acc += h[k] * x[idx]
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    /** Skala agar puncak = [target]. In-place. */
    fun peakNormalize(x: FloatArray, target: Float = 0.9f) {
        var peak = 0f
        for (v in x) if (abs(v) > peak) peak = abs(v)
        if (peak < 1e-6f) return
        val g = target / peak
        for (i in x.indices) x[i] *= g
    }
}
