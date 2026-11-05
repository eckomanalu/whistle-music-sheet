package id.suaranote.core

/**
 * Lagu yang disusun dari tuts: deretan not/rehat berurutan. Panjang tiap not dalam 1/16
 * (1 ketuk = 4). Pembagian ke birama dan ikatan di garis birama dihitung oleh [measures].
 */
data class Composition(val events: List<NoteEvent> = emptyList()) {
    fun add(midi: Int?, dur16: Int): Composition {
        require(dur16 in 1..MAX_DUR16) { "Panjang not di luar batas: $dur16" }
        return Composition(events + NoteEvent(midi, dur16))
    }

    /** Mengubah panjang not/rehat paling akhir (dipakai mode live saat tuts masih ditahan). */
    fun replaceLast(dur16: Int): Composition {
        require(dur16 in 1..MAX_DUR16) { "Panjang not di luar batas: $dur16" }
        if (events.isEmpty()) return this
        return Composition(events.dropLast(1) + events.last().copy(dur16 = dur16))
    }

    /** Menghapus satu not/rehat paling akhir; tidak berbuat apa-apa bila kosong. */
    fun removeLast(): Composition = if (events.isEmpty()) this else Composition(events.dropLast(1))

    fun measures(beatsPerBar: Int): List<Measure> = ScoreBuilder.build(events, beatsPerBar * 4)

    companion object {
        /** Panjang yang bisa dipilih: 4, 3, 2, 1,5, 1, 0,75, 0,5, 0,25 ketuk. */
        val DURATIONS = listOf(16, 12, 8, 6, 4, 3, 2, 1)

        /** Batas panjang satu not (4 birama 4/4); lebih panjang dari satu birama otomatis diikat. */
        const val MAX_DUR16 = 64
    }
}

object ScoreBuilder {
    private val PARTS = intArrayOf(16, 12, 8, 6, 4, 3, 2, 1)

    /** Uraikan durasi (1/16) menjadi nilai not baku. */
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

    /** Memotong not yang melewati garis birama menjadi dua not berikat (tie). */
    fun build(events: List<NoteEvent>, barLen: Int): List<Measure> {
        val measures = ArrayList<Measure>()
        var current = ArrayList<NoteEvent>()
        var pos = 0
        for (ev in events) {
            val parts = ArrayList<Int>()
            var remaining = ev.dur16
            var p = pos
            while (remaining > 0) {
                val chunk = minOf(remaining, barLen - p)
                parts += decompose(chunk)
                remaining -= chunk
                p = (p + chunk) % barLen
            }
            for ((i, part) in parts.withIndex()) {
                val tied = ev.midi != null && i < parts.size - 1
                current += NoteEvent(ev.midi, part, tied)
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
