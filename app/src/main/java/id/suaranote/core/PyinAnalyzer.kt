package id.suaranote.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pelacak nada ala pYIN (Mauch & Dixon, 2014), versi ringkas:
 * tiap frame menghasilkan beberapa kandidat nada dari lembah CMNDF (bukan satu ambang saja),
 * lalu jalur nada terbaik dicari dengan Viterbi atas seluruh rekaman (HMM: bin nada + keadaan
 * "tanpa nada"). Ini menekan lonjakan oktaf dan nada nyasar yang muncul bila tiap frame
 * diputuskan sendiri-sendiri.
 */
class PyinAnalyzer(
    private val sampleRate: Int = AudioAnalyzer.ANALYSIS_RATE,
    minFreqHz: Double = 80.0,
    maxFreqHz: Double = 3000.0,
) {
    companion object {
        const val HOP = 256
        const val WINDOW = 512
        private const val BINS_PER_SEMI = 4
        private const val MAX_CAND = 5
        private const val SIGMA_BINS = 1.5
        private const val TRANS_SIGMA = 6.0
        private const val MAX_JUMP = 24
        private val LOG_V2U = ln(0.05)
        private val LOG_U2V = ln(0.01)
        private val LOG_UU = ln(0.9)
        private val LOG_VV = ln(0.95)
    }

    private val detector = PitchDetector(sampleRate, minFreqHz, maxFreqHz, windowSize = WINDOW)
    private val midiMin = Pitch.freqToMidi(minFreqHz)
    private val midiMax = Pitch.freqToMidi(maxFreqHz)
    private val nBins = ceil((midiMax - midiMin) * BINS_PER_SEMI).toInt() + 1
    // Skor bertahan di bin yang sama = ln(0.95); melompat d bin dikenai denda Gaussian.
    // Sengaja tidak dibagi lebar kernel: kemungkinan "bernada" dan "tanpa nada" harus sebanding.
    private val trans = DoubleArray(MAX_JUMP + 1) { d ->
        LOG_VV - (d.toDouble() * d) / (2 * TRANS_SIGMA * TRANS_SIGMA)
    }

    fun analyze(x: FloatArray): List<PitchFrame> {
        val need = detector.requiredSamples
        if (x.size < need + HOP) return emptyList()
        val frameCount = (x.size - need) / HOP + 1

        // energi per frame (blok HOP di tengah jendela)
        val rms = DoubleArray(frameCount)
        for (t in 0 until frameCount) {
            val s = t * HOP + need / 2 - HOP / 2
            var e = 0.0
            for (i in s until s + HOP) e += x[i] * x[i]
            rms[t] = sqrt(e / HOP)
        }
        val sorted = rms.sortedArray()
        val floorRms = sorted[(frameCount * 0.1).toInt()]
        val p95 = sorted[min(frameCount - 1, (frameCount * 0.95).toInt())]
        val gate = max(max(min(3 * floorRms, 0.1 * p95), 0.03 * p95), 1e-4)

        // kandidat nada tiap frame
        val candMidi = FloatArray(frameCount * MAX_CAND)
        val candW = FloatArray(frameCount * MAX_CAND)
        val candN = IntArray(frameCount)
        val lo = detector.minLag
        val hi = detector.maxLag
        val tauR = DoubleArray(hi + 1)
        val valR = DoubleArray(hi + 1)
        for (t in 0 until frameCount) {
            detector.computeCmnd(x, t * HOP)
            val c = detector.cmndArray
            var n = 0
            for (tau in lo until hi) {
                if (c[tau] < 0.5 && c[tau] < c[tau - 1] && c[tau] <= c[tau + 1]) {
                    var r = tau.toDouble()
                    val denom = 2.0 * (2.0 * c[tau] - c[tau + 1] - c[tau - 1])
                    if (denom != 0.0) r += (c[tau + 1] - c[tau - 1]) / denom
                    tauR[n] = r
                    valR[n] = c[tau]
                    n++
                }
            }
            // Pada nada murni, kelipatan lag (2T, 3T, ...) bernilai sedikit lebih rendah daripada T sendiri,
            // jadi memilih "nilai terendah" justru menyingkirkan nada dasar. Ambil lag terpendek yang
            // masih bagus (dalam 0.25 dari lembah terbaik), urut menurut lag naik.
            var vmin = 1.0
            for (i in 0 until n) vmin = min(vmin, valR[i])
            val order = (0 until n).filter { valR[it] <= vmin + 0.25 }.sortedBy { tauR[it] }.take(MAX_CAND)
            var k = 0
            for (i in order) {
                val m = Pitch.freqToMidi(sampleRate / tauR[i])
                if (m < midiMin - 0.5 || m > midiMax + 0.5) continue
                val w = (1 - valR[i]) * (1 - valR[i]) * exp(-0.35 * k)
                candMidi[t * MAX_CAND + k] = m.toFloat()
                candW[t * MAX_CAND + k] = w.toFloat()
                k++
            }
            candN[t] = k
        }

        // Viterbi
        val s = nBins
        val u = s // indeks keadaan "tanpa nada"
        val back = ShortArray(frameCount * (s + 1))
        var prev = DoubleArray(s + 1)
        var cur = DoubleArray(s + 1)
        val obs = DoubleArray(s)
        val lv = DoubleArray(s)

        fun observe(t: Int): Double { // mengisi lv, mengembalikan log-likelihood tanpa-nada
            if (rms[t] < gate) {
                java.util.Arrays.fill(lv, ln(1e-6))
                return 0.0
            }
            java.util.Arrays.fill(obs, 0.0)
            var total = 0.0
            for (k in 0 until candN[t]) total += candW[t * MAX_CAND + k]
            val scale = if (total > 0.99) 0.99 / total else 1.0
            for (k in 0 until candN[t]) {
                val center = (candMidi[t * MAX_CAND + k] - midiMin) * BINS_PER_SEMI
                val w = candW[t * MAX_CAND + k] * scale.toDouble()
                val b0 = floor(center).toInt()
                for (b in b0 - 3..b0 + 4) {
                    if (b < 0 || b >= s) continue
                    val z = (b - center) / SIGMA_BINS
                    obs[b] += w * exp(-0.5 * z * z)
                }
            }
            for (b in 0 until s) lv[b] = ln(obs[b] + 1e-4)
            return ln(1.0 - min(total, 0.99) + 0.01)
        }

        var lu = observe(0)
        for (b in 0 until s) prev[b] = ln(0.5 / s) + lv[b]
        prev[u] = ln(0.5) + lu

        for (t in 1 until frameCount) {
            lu = observe(t)
            var bestV = Double.NEGATIVE_INFINITY
            var bestVIdx = 0
            for (b in 0 until s) if (prev[b] > bestV) { bestV = prev[b]; bestVIdx = b }
            val base = t * (s + 1)
            for (b in 0 until s) {
                var best = prev[u] + LOG_U2V
                var arg = u
                val from = max(0, b - MAX_JUMP)
                val to = min(s - 1, b + MAX_JUMP)
                for (p in from..to) {
                    val sc = prev[p] + trans[abs(p - b)]
                    if (sc > best) { best = sc; arg = p }
                }
                cur[b] = best + lv[b]
                back[base + b] = arg.toShort()
            }
            var bu = prev[u] + LOG_UU
            var au = u
            if (bestV + LOG_V2U > bu) { bu = bestV + LOG_V2U; au = bestVIdx }
            cur[u] = bu + lu
            back[base + u] = au.toShort()
            val tmp = prev; prev = cur; cur = tmp
        }

        // telusuri balik
        val path = IntArray(frameCount)
        var state = 0
        for (i in 1..s) if (prev[i] > prev[state]) state = i
        for (t in frameCount - 1 downTo 0) {
            path[t] = state
            if (t > 0) state = back[t * (s + 1) + state].toInt()
        }

        // frame keluaran: pakai kandidat terdekat bin agar presisi lebih halus dari 25 sen
        return List(frameCount) { t ->
            val time = (t * HOP + need / 2).toDouble() / sampleRate
            val st = path[t]
            if (st == u) {
                PitchFrame(time, null, rms[t])
            } else {
                val binMidi = midiMin + st.toDouble() / BINS_PER_SEMI
                var chosen = binMidi
                var bestW = 0f
                for (k in 0 until candN[t]) {
                    val m = candMidi[t * MAX_CAND + k].toDouble()
                    val w = candW[t * MAX_CAND + k]
                    if (abs(m - binMidi) < 0.5 && w > bestW) { bestW = w; chosen = m }
                }
                PitchFrame(time, 440.0 * Math.pow(2.0, (chosen - 69.0) / 12.0), rms[t])
            }
        }
    }
}
