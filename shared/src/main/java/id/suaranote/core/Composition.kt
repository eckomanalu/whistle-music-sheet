package id.suaranote.core

/**
 * Satu simbol yang diketik: kepala not/rehat ([ext] = false) atau perpanjangannya ([ext] = true, digambar
 * sebagai titik di not angka). Panjang tiap simbol satu "potongan" (1, 2, atau 4 enam-belas); not yang lebih
 * panjang tersusun dari kepala + beberapa perpanjangan. Karena itu Hapus bisa membuang simbol terakhir saja.
 */
data class Symbol(val midi: Int?, val dur16: Int, val ext: Boolean, val lyric: String? = null)

/** Simbol not angka yang sudah dipotong di garis birama; [startUnit] mutlak dalam 1/16 sejak awal lagu. */
data class Token(val midi: Int?, val dur16: Int, val ext: Boolean, val startUnit: Int, val lyric: String? = null)

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
        return Composition(symbols + symbolsFor(midi, dur16))
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

    // ------------------------------------------------------------------
    // Penyuntingan di titik sembarang (satuan waktu mutlak, 1/16)
    // ------------------------------------------------------------------

    /** Menambah rehat di ujung sampai lagu sepanjang [unit] (tidak berbuat apa-apa bila sudah sepanjang itu). */
    fun padTo(unit: Int): Composition {
        var c = this
        while (c.totalUnits < unit) c = c.add(null, minOf(MAX_DUR16, unit - c.totalUnits))
        return c
    }

    /** Membuat batas simbol tepat di [unit] (memotong simbol yang melintasi titik itu menjadi dua). */
    fun splitAt(unit: Int): Composition {
        var pos = 0
        for ((i, s) in symbols.withIndex()) {
            val end = pos + s.dur16
            if (unit > pos && unit < end) {
                val first = Chunks.of(unit - pos).mapIndexed { k, c -> Symbol(s.midi, c, ext = s.ext || k > 0, lyric = if (k == 0) s.lyric else null) }
                val second = Chunks.of(end - unit).map { Symbol(s.midi, it, ext = true) }
                return Composition(symbols.take(i) + first + second + symbols.drop(i + 1))
            }
            pos = end
        }
        return this
    }

    /** Jumlah simbol yang mulai sebelum [unit] = indeks sisip untuk titik itu. */
    private fun indexAt(unit: Int): Int {
        var pos = 0
        var i = 0
        while (i < symbols.size && pos < unit) { pos += symbols[i].dur16; i++ }
        return i
    }

    /**
     * Perpanjangan yang kehilangan kepala (mis. kepalanya terhapus atau tergantikan) dijadikan kepala
     * baru, supaya tidak "menempel" ke not lain di depannya.
     */
    private fun fixOrphans(): Composition {
        var changed = false
        val out = symbols.mapIndexed { i, s ->
            if (s.ext && (i == 0 || symbols[i - 1].midi != s.midi)) { changed = true; s.copy(ext = false) } else s
        }
        return if (changed) Composition(out) else this
    }

    /** Menyisipkan [syms] mulai [unit]; isi sesudahnya bergeser. Bila [unit] di luar ujung, diisi rehat dulu. */
    fun insertAt(unit: Int, syms: List<Symbol>): Composition {
        val c = padTo(unit).splitAt(unit)
        val idx = c.indexAt(unit)
        return Composition(c.symbols.take(idx) + syms + c.symbols.drop(idx)).fixOrphans()
    }

    fun insertNote(unit: Int, midi: Int?, dur16: Int): Composition {
        checked(dur16)
        return insertAt(unit, symbolsFor(midi, dur16))
    }

    /** Menyisipkan perpanjangan (titik) sebesar [by] tepat setelah not/rehat yang berakhir di [unit]. */
    fun insertExtension(unit: Int, by: Int): Composition {
        checked(by)
        val c = splitAt(unit)
        val prev = c.symbolBefore(unit) ?: return this
        return c.insertAt(unit, Chunks.of(by).map { Symbol(prev.midi, it, ext = true) })
    }

    /** Simbol yang berakhir tepat di [unit] (null bila [unit] di awal atau di luar lagu). */
    fun symbolBefore(unit: Int): Symbol? {
        var pos = 0
        for (s in symbols) {
            pos += s.dur16
            if (pos == unit) return s
            if (pos > unit) return null
        }
        return null
    }

    /** Menghapus satu simbol yang berakhir di [unit] (seperti backspace); isi sesudahnya bergeser maju. */
    fun removeBefore(unit: Int): Composition {
        val c = splitAt(unit)
        var pos = 0
        for ((i, s) in c.symbols.withIndex()) {
            pos += s.dur16
            if (pos == unit) return Composition(c.symbols.take(i) + c.symbols.drop(i + 1)).fixOrphans()
            if (pos > unit) break
        }
        return this
    }

    /** Menghapus satuan [a, b) dan menggeser isi sesudahnya maju (hapus birama). */
    fun deleteRange(a: Int, b: Int): Composition {
        if (a >= totalUnits || b <= a) return this
        val c = splitAt(a).splitAt(b)
        val from = c.indexAt(a)
        val to = c.indexAt(b)
        return Composition(c.symbols.take(from) + c.symbols.drop(maxOf(from, to))).fixOrphans()
    }

    /** Memasang suku kata [text] pada not yang memuat [unit] (kosong/null = hapus). Rehat tidak bisa diberi suku kata. */
    fun withLyric(unit: Int, text: String?): Composition {
        val start = eventStart(unit)
        var pos = 0
        for ((i, s) in symbols.withIndex()) {
            if (pos == start && !s.ext && s.midi != null) {
                val l = text?.trim()?.ifEmpty { null }
                return Composition(symbols.take(i) + s.copy(lyric = l) + symbols.drop(i + 1))
            }
            pos += s.dur16
        }
        return this
    }

    /** Menempelkan [syms] di ujung (kepala pertama tetap kepala). */
    fun append(syms: List<Symbol>): Composition = Composition(symbols + syms).fixOrphans()

    /** Isi satuan [a, b) sebagai simbol yang berdiri sendiri; bagian yang melewati ujung lagu diisi rehat. */
    fun slice(a: Int, b: Int): List<Symbol> {
        val out = ArrayList<Symbol>()
        var pos = 0
        for (s in symbols) {
            val end = pos + s.dur16
            val lo = maxOf(pos, a)
            val hi = minOf(end, b)
            if (hi > lo) {
                if (hi - lo == s.dur16) out += s
                else out += Chunks.of(hi - lo).mapIndexed { k, c -> Symbol(s.midi, c, ext = (lo == pos && s.ext) || k > 0, lyric = if (k == 0 && lo == pos) s.lyric else null) }
            }
            pos = end
        }
        val got = out.sumOf { it.dur16 }
        var missing = (b - a) - got
        while (missing > 0) {
            val n = minOf(MAX_DUR16, missing)
            out += symbolsFor(null, n)
            missing -= n
        }
        if (out.isNotEmpty() && out[0].ext) out[0] = out[0].copy(ext = false)
        return out
    }

    /** Menimpa satuan [a, b) dengan [syms] (yang sepanjang b - a); not lama di rentang itu terhapus. */
    fun replaceRange(a: Int, b: Int, syms: List<Symbol>): Composition {
        val c = padTo(a).splitAt(a).splitAt(b)
        val from = c.indexAt(a)
        val to = c.indexAt(b)
        return Composition(c.symbols.take(from) + syms + c.symbols.drop(maxOf(from, to))).fixOrphans()
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
                    val isExt = s.ext || !first || i > 0
                    bars[bar] += Token(s.midi, p, isExt, pos, if (isExt) null else s.lyric)
                    pos += p
                }
                remaining -= take
                first = false
            }
        }
        return bars
    }

    companion object {
        /** Simbol untuk satu not/rehat berpanjang [dur16]: satu kepala + perpanjangan per ketuk. */
        fun symbolsFor(midi: Int?, dur16: Int): List<Symbol> =
            Chunks.of(dur16).mapIndexed { i, c -> Symbol(midi, c, ext = i > 0) }

        /** Panjang yang bisa dipilih: 4, 3, 2, 1,5, 1, 0,75, 0,5, 0,25 ketuk. */
        val DURATIONS = listOf(16, 12, 8, 6, 4, 3, 2, 1)

        /** Batas panjang sekali masuk (4 birama 4/4). */
        const val MAX_DUR16 = 64
        private const val MAX_TOTAL = 4096
    }
}

/** Beberapa suara (lapisan) dengan sumbu waktu yang sama; birama antar-suara selalu sejajar. */
data class Score(val voices: List<Composition> = listOf(Composition())) {
    val totalUnits: Int get() = voices.maxOfOrNull { it.totalUnits } ?: 0

    fun barCount(beatsPerBar: Int): Int = (totalUnits + beatsPerBar * 4 - 1) / (beatsPerBar * 4)

    fun withVoice(index: Int, c: Composition): Score =
        Score(voices.mapIndexed { i, v -> if (i == index) c else v })

    /** Not balok per suara; suara yang lebih pendek diberi birama kosong supaya jumlah birama sama. */
    fun measures(beatsPerBar: Int): List<List<Measure>> {
        val n = barCount(beatsPerBar)
        return voices.map { v ->
            val m = v.measures(beatsPerBar)
            m + List(maxOf(0, n - m.size)) { Measure(emptyList()) }
        }
    }

    fun tokens(beatsPerBar: Int): List<List<List<Token>>> {
        val n = barCount(beatsPerBar)
        return voices.map { v ->
            val t = v.tokens(beatsPerBar)
            t + List(maxOf(0, n - t.size)) { emptyList<Token>() }
        }
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
