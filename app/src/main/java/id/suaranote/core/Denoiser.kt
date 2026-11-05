package id.suaranote.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pengurangan derau spektral. Profil derau = persentil ke-25 magnitudo tiap bin frekuensi
 * sepanjang rekaman (siulan/dengungan menempati sebuah bin hanya sebagian waktu, derau latar
 * dan dengung listrik hadir hampir terus-menerus), lalu tiap frame dikurangi dengan gain Wiener-like.
 */
object Denoiser {
    private const val N = 1024
    private const val HOP = 256

    fun spectralSubtract(x: FloatArray, oversubtract: Double = 2.0, minGain: Double = 0.1): FloatArray {
        if (x.size < N * 4) return x.copyOf()
        val fft = Fft(N)
        val bins = N / 2 + 1
        val win = DoubleArray(N) { sqrt(0.5 - 0.5 * cos(2 * PI * it / N)) } // akar Hann
        val nFrames = (x.size - N) / HOP + 1
        val re = DoubleArray(N)
        val im = DoubleArray(N)

        // lintasan 1: magnitudo semua frame
        val mags = FloatArray(nFrames * bins)
        for (f in 0 until nFrames) {
            val s = f * HOP
            for (i in 0 until N) { re[i] = x[s + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im)
            for (k in 0 until bins) mags[f * bins + k] = hypot(re[k], im[k]).toFloat()
        }

        // profil derau per bin
        val noise = DoubleArray(bins)
        val col = FloatArray(nFrames)
        val idx = (nFrames * 0.25).toInt()
        for (k in 0 until bins) {
            for (f in 0 until nFrames) col[f] = mags[f * bins + k]
            col.sort()
            noise[k] = col[idx].toDouble()
        }

        // lintasan 2: terapkan gain, overlap-add
        val out = DoubleArray(x.size)
        val norm = DoubleArray(x.size)
        val raw = DoubleArray(bins)
        val gPrev = DoubleArray(bins) { 1.0 }
        for (f in 0 until nFrames) {
            val s = f * HOP
            for (i in 0 until N) { re[i] = x[s + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im)
            for (k in 0 until bins) {
                val m = mags[f * bins + k].toDouble()
                raw[k] = if (m <= 1e-9) minGain else max(minGain, 1.0 - oversubtract * noise[k] / m)
            }
            for (k in 0 until bins) {
                val a = raw[if (k > 0) k - 1 else k]
                val c = raw[if (k < bins - 1) k + 1 else k]
                val gs = (a + 2 * raw[k] + c) / 4 // haluskan antar-bin
                val gt = 0.6 * gPrev[k] + 0.4 * gs // haluskan antar-waktu
                gPrev[k] = gt
                re[k] *= gt
                im[k] *= gt
                if (k in 1 until N / 2) { re[N - k] = re[k]; im[N - k] = -im[k] }
            }
            fft.transform(re, im, inverse = true)
            for (i in 0 until N) {
                out[s + i] += re[i] * win[i]
                norm[s + i] += win[i] * win[i]
            }
        }
        return FloatArray(x.size) { i -> if (norm[i] > 1e-6) (out[i] / norm[i]).toFloat() else x[i] }
    }
}
