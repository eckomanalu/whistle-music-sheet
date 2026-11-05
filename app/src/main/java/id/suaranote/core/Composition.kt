package id.suaranote.core

/**
 * Satu simbol yang diketik: kepala not/rehat ([ext] = false) atau perpanjangannya ([ext] = true, digambar
 * sebagai titik di not angka). Panjang tiap simbol satu "potongan" (1, 2, atau 4 enam-belas); not yang lebih
 * panjang tersusun dari kepala + beberapa perpanjangan. Karena itu Hapus bisa membuang simbol terakhir saja.
 */
data class Symbol(val midi: Int?, val dur16: Int, val ext: Boolean)

/** Simbol not angka yang sudah dipotong di garis birama; [startUnit] mutlak dalam 1/16 sejak awal lagu. */
data class Token(val midi: Int?, val dur16: Int, val ext: Boolean, val startUnit: Int)

object Chunks {
    /** Memecah durasi (1/16) per ketuk: 4, 4, ... lalu sisa (3 menjadi 2 + 1). */
    fun of(dur16: Int): List<Int> {
        val out = ArrayList<Int>()
        var r = dur16
        while (r >= 4) { out += 4; r -= 4 }
        if (r == 3) { out += 2; out += 1 } else if (r > 0) out += r
        return out
    }

    /** Garis di atas not angka: 1/8 satu garis, 1/16 dua garis. */
    fun lines(chunk: Int): Int = when (chunk) { 2 -> 1; 1 -> 2; else -> 0 }
}

/**
 * Lagu yang disusun dari tuts: deretan [Symbol]. Pembagian ke birama (dan ikatan untuk not balok) dihitung
 * oleh [measures]; simbol not angka oleh [tokens].
 */
data class Composition(val symbols: List<Symbol> = emptyList()) {

    private fun checked(dur16: Int) = require(dur16 in 1..MAX_DUR16) { "Panjang di luar batas: $dur16" }

    /** Menambah not/rehat berpanjang [dur16]: satu kepala + perpanjangan per ketuk. */
    fun add(midi: Int?, dur16: Int): Composition {
        checked(dur16)
        val chunks = Chunks.of(dur16)
        return Composition(symbols + chunks.mapIndexed { i, c -> Symbol(midi, c, ext = i > 0) })
    }

    /** Sambung: menambah perpanjangan sebesar [by] pada not/rehat terakhir (digambar sebagai titik). */
    fun extend(by: Int): Composition {
        checked(by)
        val last = symbols.lastOrNull() ?: return this
        if (totalUnits + by > MAX_TOTAL) return this
        return Composition(symbols + Chunks.of(by).map { Symbol(last.midi, it, ext = true) })
    }

    /** Menghapus satu simbol paling akhir (kepala not, rehat, atau satu perpanjangan); kosong = tidak berbuat apa-apa. */
    fun removeLast(): Composition = if (symbols.isEmpty()) this else Composition(symbols.dropLast(1))

    /** Memangkas sampai [count] simbol pertama (dipakai mode live untuk menulis ulang not yang sedang ditahan). */
    fun truncate(count: Int): Composition = Composition(symbols.take(count))

    val isEmpty: Boolean get() = symbols.isEmpty()
    val totalUnits: Int get() = symbols.sumOf { it.dur16 }

    /** Simbol digabung menjadi not/rehat utuh (perpanjangan menyambung ke kepala di depannya). */
    val events: List<NoteEvent>
        get() {
            val out = ArrayList<NoteEvent>()
            for (s in symbols) {
                if (s.ext && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(dur16 = out.last().dur16 + s.dur16)
                else out += NoteEvent(s.midi, s.dur16)
            }
            return out
        }

    /** Awal (1/16) not/rehat utuh yang memuat satuan [unit]. */
    fun eventStart(unit: Int): Int {
        var pos = 0
        for (e in events) {
            if (unit < pos + e.dur16) return pos
            pos += e.dur16
        }
        return pos
    }

    /** Not/rehat mulai dari satuan [unit]; not yang terpotong di [unit] dimainkan sisanya saja. */
    fun eventsFrom(unit: Int): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        var pos = 0
        for (e in events) {
            val end = pos + e.dur16
            if (pos >= unit) out += e else if (end > unit) out += NoteEvent(e.midi, end - unit)
            pos = end
        }
        return out
    }

    fun measures(beatsPerBar: Int): List<Measure> = ScoreBuilder.build(events, beatsPerBar * 4)

    /** Simbol not angka per birama; simbol yang melewati garis birama dipotong, sisanya jadi perpanjangan. */
    fun tokens(beatsPerBar: Int): List<List<Token>> {
        val barLen = beatsPerBar * 4
        val bars = ArrayList<MutableList<Token>>()
        var pos = 0
        for (s in symbols) {
            var remaining = s.dur16
            var first = true
            while (remaining > 0) {
                val take = minOf(remaining, barLen - pos % barLen)
                val pieces = if (take == s.dur16) listOf(take) else Chunks.of(take)
                for ((i, p) in pieces.withIndex()) {
                    val bar = pos / barLen
                    while (bars.size <= bar) bars += ArrayList<Token>()
                    bars[bar] += Token(s.midi, p, s.ext || !first || i > 0, pos)
                    pos += p
                }
                remaining -= take
                first = false
            }
        }
        return bars
    }

    companion object {
        /** Panjang yang bisa dipilih: 4, 3, 2, 1,5, 1, 0,75, 0,5, 0,25 ketuk. */
        val DURATIONS = listOf(16, 12, 8, 6, 4, 3, 2, 1)

        /** Batas panjang sekali masuk (4 birama 4/4). */
        const val MAX_DUR16 = 64
        private const val MAX_TOTAL = 4096
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
