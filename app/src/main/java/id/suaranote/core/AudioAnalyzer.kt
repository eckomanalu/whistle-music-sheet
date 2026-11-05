package id.suaranote.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hasil analisis satu rekaman.
 * [clean] = audio 22050 Hz yang sudah dibersihkan dan dinormalkan (untuk diputar ulang).
 * [tuningOffset] = rata-rata selisih nyanyian dari tala standar (setengah nada), [tuningStrength] 0..1.
 */
class AnalysisResult(
    val clean: FloatArray,
    val frames: List<PitchFrame>,
    val tuningOffset: Double,
    val tuningStrength: Double,
    /** Koreksi tala per frame (setengah nada): mengikuti nyanyian yang pelan-pelan makin flat/sharp. */
    val tuningTrack: DoubleArray = DoubleArray(0),
) {
    fun effectiveTrack(autoTune: Boolean): DoubleArray =
        if (autoTune && tuningTrack.size == frames.size) tuningTrack else DoubleArray(frames.size)

    /** Offset yang dipakai transkripsi: hanya bila kalibrasi aktif dan pergeseran cukup konsisten. */
    fun effectiveOffset(autoTune: Boolean): Double =
        if (autoTune && tuningStrength >= 0.4) tuningOffset else 0.0
}

object AudioAnalyzer {
    const val ANALYSIS_RATE = 22050

    /** [pcm] harus mono 16-bit pada 44100 Hz. */
    fun analyze(pcm: ShortArray, sampleRate: Int, denoise: Boolean): AnalysisResult {
        require(sampleRate == 44100) { "Hanya 44100 Hz yang didukung" }
        val x = Dsp.toFloat(pcm)
        Dsp.highPass(x, sampleRate, 60.0)
        var y = Dsp.decimate2(x)
        if (denoise) y = Denoiser.spectralSubtract(y)
        Dsp.peakNormalize(y)
        val frames = PyinAnalyzer(ANALYSIS_RATE).analyze(y)
        val (offset, strength) = estimateTuning(frames)
        return AnalysisResult(y, frames, offset, strength, estimateTrack(frames, offset, strength))
    }

    /**
     * Seperti [estimateTuning] tetapi dalam jendela +-1,5 detik, sehingga pergeseran tala yang pelan-pelan
     * berubah (penyanyi tanpa iringan sering makin flat) ikut terkoreksi. Hasil dibuat kontinu (tanpa lompatan
     * satu setengah nada) agar nada yang ditahan tidak terbelah.
     */
    fun estimateTrack(frames: List<PitchFrame>, globalOffset: Double, globalStrength: Double): DoubleArray {
        val n = frames.size
        val cs = DoubleArray(n + 1)
        val sn = DoubleArray(n + 1)
        val ct = IntArray(n + 1)
        for (i in 0 until n) {
            cs[i + 1] = cs[i]; sn[i + 1] = sn[i]; ct[i + 1] = ct[i]
            val hz = frames[i].freqHz ?: continue
            val m = Pitch.freqToMidi(hz)
            val a = 2 * PI * (m - floor(m))
            cs[i + 1] += cos(a); sn[i + 1] += sin(a); ct[i + 1]++
        }
        val hop = if (n > 1) (frames.last().timeSec - frames.first().timeSec) / (n - 1) else 0.0116
        val w = (1.5 / hop).toInt()
        val out = DoubleArray(n)
        var prev = if (globalStrength >= 0.4) globalOffset else 0.0
        for (i in 0 until n) {
            val lo = maxOf(0, i - w)
            val hi = minOf(n, i + w + 1)
            val cnt = ct[hi] - ct[lo]
            if (cnt >= 25) {
                val mc = (cs[hi] - cs[lo]) / cnt
                val ms = (sn[hi] - sn[lo]) / cnt
                if (hypot(mc, ms) >= 0.4) {
                    var o = atan2(ms, mc) / (2 * PI)
                    o += Math.rint(prev - o)
                    prev = o
                }
            }
            out[i] = prev
        }
        return out
    }

    /**
     * Rata-rata melingkar dari pecahan nada (m - floor(m)): bila seluruh nyanyian bergeser konsisten
     * (mis. selalu 30 sen terlalu tinggi), offset ini mengembalikannya ke tala standar.
     */
    fun estimateTuning(frames: List<PitchFrame>): Pair<Double, Double> {
        var c = 0.0
        var s = 0.0
        var n = 0
        for (f in frames) {
            val hz = f.freqHz ?: continue
            val m = Pitch.freqToMidi(hz)
            val a = 2 * PI * (m - floor(m))
            c += cos(a); s += sin(a); n++
        }
        if (n < 20) return 0.0 to 0.0
        val mc = c / n
        val ms = s / n
        return atan2(ms, mc) / (2 * PI) to hypot(mc, ms)
    }
}
