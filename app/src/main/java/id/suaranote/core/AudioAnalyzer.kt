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
) {
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
        return AnalysisResult(y, frames, offset, strength)
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
