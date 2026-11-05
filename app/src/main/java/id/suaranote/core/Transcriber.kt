package id.suaranote.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Mengubah deretan [PitchFrame] menjadi birama berisi not dan rehat.
 *
 * Tahapan: buang bunyi sangat pendek -> median filter (hilangkan lonjakan oktaf) ->
 * segmentasi nada dengan histeresis -> pisah not berulang lewat lembah energi ->
 * kuantisasi ke grid 1/16 menurut tempo -> potong di garis birama dengan ikatan.
 * Semua ambang durasi dinyatakan dalam detik sehingga tidak bergantung pada jarak antar-frame.
 */
object Transcriber {
    private const val MIN_NOTE_SEC = 0.06
    private const val MEDIAN_SEC = 0.04
    private const val MAX_DROPOUT_SEC = 0.03
    private const val GLIDE_SEC = 0.15
    private const val SWITCH_TOLERANCE = 0.65 // setengah nada
    private val PARTS = intArrayOf(16, 12, 8, 6, 4, 3, 2, 1)

    private class Seg(var midi: Int, var start: Int, var end: Int) // end eksklusif

    /** [tuningOffset] (setengah nada) dikurangkan dari nada terukur sebelum dibulatkan. */
    fun transcribe(
        frames: List<PitchFrame>,
        settings: TranscriptionSettings,
        tuningOffset: Double = 0.0,
    ): List<Measure> {
        val n = frames.size
        val hop = hopSeconds(frames)
        val minFrames = maxOf(3, ceil(MIN_NOTE_SEC / hop - 1e-9).toInt())
        val medianRadius = maxOf(2, (MEDIAN_SEC / hop).roundToInt())
        if (n < minFrames) return emptyList()

        val raw = DoubleArray(n) { i ->
            frames[i].freqHz?.let { Pitch.freqToMidi(it) - tuningOffset } ?: Double.NaN
        }
        val rms = DoubleArray(n) { frames[it].rms }
        fillDropouts(raw, rms, maxOf(2, ceil(MAX_DROPOUT_SEC / hop - 1e-9).toInt()))
        dropShortRuns(raw, minFrames)
        val med = medianFilter(raw, medianRadius)

        val segs = ArrayList<Seg>()
        var i = 0
        while (i < n) {
            if (med[i].isNaN()) { i++; continue }
            var j = i
            while (j < n && !med[j].isNaN()) j++
            for (s in segmentRun(med, i, j, minFrames, hop)) segs += splitByEnergy(rms, s, minFrames)
            i = j
        }
        if (segs.isEmpty()) return emptyList()

        // garis waktu: not dan rehat (rehat = celah di antara dua not)
        val t0 = frames[segs.first().start].timeSec - hop / 2
        val unitsPerSec = settings.bpm / 60.0 * 4.0
        val timeline = ArrayList<Pair<Int?, Double>>() // (midi atau null=rehat, waktu akhir)
        for ((idx, s) in segs.withIndex()) {
            val st = frames[s.start].timeSec - hop / 2
            val en = frames[s.end - 1].timeSec + hop / 2
            if (idx > 0) {
                val prevEnd = timeline.last().second
                if (st - prevEnd > 1e-6) timeline += Pair(null, st)
            }
            timeline += Pair(s.midi, en)
        }

        // kuantisasi: tiap akhir not dibulatkan ke grid 1/16, awal = akhir peristiwa sebelumnya
        val quantized = ArrayList<Pair<Int?, Int>>()
        var cursor = 0
        for ((midi, endSec) in timeline) {
            var endU = ((endSec - t0) * unitsPerSec).roundToInt()
            if (endU <= cursor) {
                if (midi == null) continue
                endU = cursor + 1
            }
            quantized += Pair(midi, endU - cursor)
            cursor = endU
        }

        return buildMeasures(quantized, settings.beatsPerBar * 4)
    }

    private fun hopSeconds(frames: List<PitchFrame>): Double =
        if (frames.size > 1) (frames.last().timeSec - frames.first().timeSec) / (frames.size - 1) else 0.023

    /**
     * Lubang pendek tanpa nada di tengah bunyi yang sama (pelacak sempat gagal, tetapi energi tidak turun)
     * ditambal dengan interpolasi. Jeda artikulasi sungguhan ditandai penurunan energi, jadi tidak ditambal.
     */
    private fun fillDropouts(x: DoubleArray, rms: DoubleArray, maxGap: Int) {
        var i = 0
        while (i < x.size) {
            if (!x[i].isNaN()) { i++; continue }
            var j = i
            while (j < x.size && x[j].isNaN()) j++
            val len = j - i
            if (i > 0 && j < x.size && len <= maxGap) {
                val a = x[i - 1]
                val b = x[j]
                val ref = minOf(rms[i - 1], rms[j])
                var ok = abs(a - b) < 0.7
                for (k in i until j) if (rms[k] < 0.6 * ref) ok = false
                if (ok) for (k in i until j) x[k] = a + (b - a) * (k - i + 1) / (len + 1)
            }
            i = j
        }
    }

    /** Bunyi bersuara yang lebih pendek dari [minFrames] dianggap derau. */
    private fun dropShortRuns(x: DoubleArray, minFrames: Int) {
        var i = 0
        while (i < x.size) {
            if (x[i].isNaN()) { i++; continue }
            var j = i
            while (j < x.size && !x[j].isNaN()) j++
            if (j - i < minFrames) for (k in i until j) x[k] = Double.NaN
            i = j
        }
    }

    private fun medianFilter(x: DoubleArray, radius: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        val tmp = ArrayList<Double>()
        for (i in x.indices) {
            if (x[i].isNaN()) continue
            tmp.clear()
            for (k in maxOf(0, i - radius)..minOf(x.size - 1, i + radius)) if (!x[k].isNaN()) tmp += x[k]
            tmp.sort()
            out[i] = tmp[tmp.size / 2]
        }
        return out
    }

    private fun medianOf(x: DoubleArray, start: Int, end: Int): Double {
        val list = ArrayList<Double>(end - start)
        for (k in start until end) list += x[k]
        list.sort()
        return list[list.size / 2]
    }

    /** Segmentasi satu rentang bersuara menjadi not-not bernada tetap. */
    private fun segmentRun(med: DoubleArray, start: Int, end: Int, minFrames: Int, hop: Double): List<Seg> {
        val segs = ArrayList<Seg>()
        var cur = med[start].roundToInt()
        var segStart = start
        var k = start + 1
        while (k < end) {
            if (abs(med[k] - cur) > SWITCH_TOLERANCE) {
                val cand = med[k].roundToInt()
                var m = k
                while (m < end && abs(med[m] - cand) <= SWITCH_TOLERANCE) m++
                if (cand != cur && m - k >= minFrames) {
                    segs += Seg(cur, segStart, k)
                    cur = cand
                    segStart = k
                    k = m
                    continue
                }
            }
            k++
        }
        segs += Seg(cur, segStart, end)

        // serpihan pendek (glitch awal nada) digabung ke tetangganya
        var idx = 0
        while (idx < segs.size) {
            val s = segs[idx]
            if (s.end - s.start < minFrames && segs.size > 1) {
                if (idx + 1 < segs.size) segs[idx + 1].start = s.start else segs[idx - 1].end = s.end
                segs.removeAt(idx)
            } else {
                idx++
            }
        }

        for (s in segs) s.midi = medianOf(med, s.start, s.end).roundToInt()

        // Luncuran/scoop: penyanyi sering mulai sedikit di bawah nada lalu naik (atau berpindah legato
        // lewat nada antara). Segmen sangat pendek yang bertetangga dengan nada lebih panjang dalam jarak
        // <= 2 setengah nada dianggap bagian dari nada berikutnya, bukan not sendiri.
        val glideFrames = maxOf(minFrames, ceil(GLIDE_SEC / hop - 1e-9).toInt())
        var gi = 0
        while (gi < segs.size - 1) {
            val g = segs[gi]
            val nx = segs[gi + 1]
            if (g.end - g.start < glideFrames && nx.end - nx.start > g.end - g.start && abs(g.midi - nx.midi) <= 2) {
                nx.start = g.start
                segs.removeAt(gi)
                nx.midi = medianOf(med, nx.start, nx.end).roundToInt()
            } else {
                gi++
            }
        }

        val merged = ArrayList<Seg>()
        for (s in segs) {
            val last = merged.lastOrNull()
            if (last != null && last.midi == s.midi && last.end == s.start) last.end = s.end else merged += s
        }
        return merged
    }

    /** Not berulang pada nada yang sama dipisah di lembah energi (mis. "la-la-la" tanpa jeda). */
    private fun splitByEnergy(rms: DoubleArray, seg: Seg, minFrames: Int): List<Seg> {
        val out = ArrayList<Seg>()
        var from = seg.start
        var peak = rms[seg.start]
        var valley = Double.MAX_VALUE
        var valleyIdx = -1
        var inValley = false
        for (k in seg.start + 1 until seg.end) {
            val r = rms[k]
            if (!inValley) {
                if (r > peak) peak = r else if (r < 0.5 * peak) { inValley = true; valley = r; valleyIdx = k }
            } else {
                if (r < valley) {
                    valley = r; valleyIdx = k
                } else if (r > valley * 1.6) {
                    if (valleyIdx - from >= minFrames && seg.end - valleyIdx >= minFrames) {
                        out += Seg(seg.midi, from, valleyIdx)
                        from = valleyIdx
                    }
                    peak = r
                    inValley = false
                }
            }
        }
        out += Seg(seg.midi, from, seg.end)
        return out
    }

    /** Uraikan durasi (1/16) menjadi nilai not baku: 16, 12, 8, 6, 4, 3, 2, 1. */
    private fun decompose(total: Int): List<Int> {
        val parts = ArrayList<Int>()
        var c = total
        while (c > 0) {
            val p = PARTS.first { it <= c }
            parts += p
            c -= p
        }
        return parts
    }

    private fun buildMeasures(events: List<Pair<Int?, Int>>, barLen: Int): List<Measure> {
        val measures = ArrayList<Measure>()
        var current = ArrayList<NoteEvent>()
        var pos = 0
        for ((midi, dur) in events) {
            val parts = ArrayList<Int>()
            var remaining = dur
            var p = pos
            while (remaining > 0) {
                val chunk = minOf(remaining, barLen - p)
                parts += decompose(chunk)
                remaining -= chunk
                p = (p + chunk) % barLen
            }
            for ((i, part) in parts.withIndex()) {
                val tied = midi != null && i < parts.size - 1
                current += NoteEvent(midi, part, tied)
                pos += part
                if (pos >= barLen) {
                    measures += Measure(current)
                    current = ArrayList()
                    pos = 0
                }
            }
        }
        if (current.isNotEmpty()) measures += Measure(current)
        return measures
    }
}
