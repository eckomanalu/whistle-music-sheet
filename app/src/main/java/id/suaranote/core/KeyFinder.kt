package id.suaranote.core

import kotlin.math.sqrt

/** Menebak nada dasar (tonika mayor) dari histogram kelas-nada berbobot durasi (profil Krumhansl-Schmuckler). */
object KeyFinder {
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)

    /** [notes] = (midi, durasi detik) berurutan waktu. Null bila terlalu sedikit not. */
    fun detect(notes: List<Pair<Int, Double>>): MusicKey? {
        if (notes.size < 3) return null
        val hist = DoubleArray(12)
        for ((m, d) in notes) hist[((m % 12) + 12) % 12] += d
        val first = ((notes.first().first % 12) + 12) % 12
        val last = ((notes.last().first % 12) + 12) % 12
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (t in 0 until 12) {
            val x = DoubleArray(12) { hist[(t + it) % 12] }
            var score = corr(x, MAJOR)
            if (last == t) score += 0.12
            if (first == t) score += 0.08
            if (score > bestScore) { bestScore = score; best = t }
        }
        return MusicKey.values().first { it.tonicPc == best }
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }
}
