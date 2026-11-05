package id.suaranote.core

import kotlin.math.sqrt

data class PitchResult(val freqHz: Double, val clarity: Double)

/**
 * Pendeteksi nada monofonik dengan algoritma YIN (de Cheveigne & Kawahara, 2002).
 * Satu instance tidak thread-safe (memakai buffer kerja internal).
 */
class PitchDetector(
    private val sampleRate: Int,
    minFreqHz: Double = 80.0,
    maxFreqHz: Double = 1100.0,
    private val threshold: Double = 0.15,
    private val windowSize: Int = 1024,
) {
    private val tauMin = maxOf(2, (sampleRate / maxFreqHz).toInt())
    private val tauMax = (sampleRate / minFreqHz).toInt()

    /** Jumlah sampel minimum yang dibutuhkan mulai dari posisi awal jendela. */
    val requiredSamples: Int = windowSize + tauMax

    /** Rentang lag yang dicari (dalam sampel). */
    val minLag: Int get() = tauMin
    val maxLag: Int get() = tauMax

    private val diff = DoubleArray(tauMax + 2)
    private val cmnd = DoubleArray(tauMax + 2)

    /** Hasil terakhir [computeCmnd]: indeks 0..[maxLag]. Jangan diubah oleh pemanggil. */
    val cmndArray: DoubleArray get() = cmnd

    /** Hitung cumulative-mean-normalized difference untuk jendela yang diawali [start]. */
    fun computeCmnd(buf: FloatArray, start: Int = 0) {
        require(buf.size >= start + requiredSamples) { "Buffer terlalu pendek" }
        for (tau in 1..tauMax) {
            var sum = 0.0
            val a = start
            val b = start + tau
            for (j in 0 until windowSize) {
                val d = (buf[a + j] - buf[b + j]).toDouble()
                sum += d * d
            }
            diff[tau] = sum
        }
        cmnd[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            running += diff[tau]
            cmnd[tau] = if (running == 0.0) 1.0 else diff[tau] * tau / running
        }
    }

    /** Mengembalikan frekuensi dasar, atau null bila sinyal tidak cukup periodik. */
    fun detect(buf: FloatArray): PitchResult? {
        computeCmnd(buf, 0)

        var tauEst = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                tauEst = tau
                break
            }
            tau++
        }
        if (tauEst == -1) {
            var best = tauMin
            for (t in tauMin..tauMax) if (cmnd[t] < cmnd[best]) best = t
            if (cmnd[best] < FALLBACK_LIMIT) tauEst = best else return null
        }

        var refined = tauEst.toDouble()
        if (tauEst > 1 && tauEst < tauMax) {
            val s0 = cmnd[tauEst - 1]
            val s1 = cmnd[tauEst]
            val s2 = cmnd[tauEst + 1]
            val denom = 2.0 * (2.0 * s1 - s2 - s0)
            if (denom != 0.0) refined += (s2 - s0) / denom
        }
        return PitchResult(sampleRate / refined, 1.0 - cmnd[tauEst])
    }

    /** Analisis satu jendela audio: gerbang keheningan (RMS) lalu deteksi nada. */
    fun analyze(buf: FloatArray, timeSec: Double, silenceRms: Double = DEFAULT_SILENCE_RMS): PitchFrame {
        var energy = 0.0
        val from = requiredSamples - ENERGY_SAMPLES
        for (i in from until requiredSamples) energy += buf[i] * buf[i]
        val rms = sqrt(energy / ENERGY_SAMPLES)
        if (rms < silenceRms) return PitchFrame(timeSec, null, rms)
        val result = detect(buf)
        return PitchFrame(timeSec, result?.freqHz, rms)
    }

    companion object {
        const val DEFAULT_SILENCE_RMS = 0.012
        const val ENERGY_SAMPLES = 1024
        private const val FALLBACK_LIMIT = 0.30
    }
}
