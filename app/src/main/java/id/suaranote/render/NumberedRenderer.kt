package id.suaranote.render

import id.suaranote.core.Chunks
import id.suaranote.core.MusicKey
import id.suaranote.core.Numbered
import id.suaranote.core.NumberedNote
import id.suaranote.core.Token

/**
 * Tata letak not angka:
 * - angka 1..7 dengan titik oktaf (atas/bawah);
 * - garis DI ATAS angka: 1/8 satu garis, 1/16 dua garis (berdekatan dalam satu ketuk disambung);
 * - perpanjangan not digambar sebagai titik: 1 ketuk = titik, 1/2 ketuk = titik + satu garis di atas,
 *   1/4 ketuk = titik + dua garis;
 * - kres = angka dicoret garis miring "/", mol = angka dicoret "\\", menempel pada angkanya;
 * - rehat "0" (perpanjangan rehat juga "0").
 * [fs] = ukuran huruf angka dalam piksel.
 */
object NumberedRenderer {
    private const val PAD = 0.7f
    private const val MARGIN = 0.4f
    private const val ROW_H = 4.3f
    private const val BASELINE = 2.3f
    private const val CAP = 0.72f
    private const val LINE_STEP = 0.17f

    private class Tk(val t: Token, val n: NumberedNote?) {
        val lines = Chunks.lines(t.dur16)
        val isDot = t.ext && t.midi != null
        val widthFs = if (isDot) {
            when (lines) { 0 -> 1.1f; 1 -> 0.95f; else -> 0.85f }
        } else {
            when (lines) { 0 -> 1.5f; 1 -> 1.25f; else -> 1.05f }
        }
        var cx = 0f
        var row = 0
        var group: List<Tk>? = null
        val opIds = ArrayList<Int>()
    }

    private class Meas(val tks: List<Tk>) {
        val widthFs = 2 * PAD + tks.sumOf { it.widthFs.toDouble() }.toFloat()
    }

    /**
     * [voices] = suara -> birama -> simbol. Jumlah birama tiap suara harus sama (suara pendek diberi birama kosong).
     * Lebar birama = yang terlebar di antara suara, sehingga garis birama antar-suara sejajar.
     */
    fun render(
        voices: List<List<List<Token>>>,
        key: MusicKey,
        octaveOffset: Int,
        width: Float,
        fs: Float,
        beatsPerBar: Int = 4,
    ): Rendering {
        val nv = voices.size
        val nBars = voices.maxOfOrNull { it.size } ?: 0
        if (nBars == 0) return Rendering(width, 0f, emptyList())

        // tks[suara][birama] -> simbol
        val tks = voices.map { bars ->
            List(nBars) { mi -> (bars.getOrNull(mi) ?: emptyList()).map { Tk(it, it.midi?.let { m -> Numbered.of(m, key, octaveOffset) }) } }
        }
        fun sumW(v: Int, mi: Int) = tks[v][mi].sumOf { it.widthFs.toDouble() }.toFloat()
        val innerW = FloatArray(nBars) { mi -> maxOf(1.5f, (0 until nv).maxOf { sumW(it, mi) }) }
        val measW = FloatArray(nBars) { 2 * PAD + innerW[it] }

        val xLeft = MARGIN * fs
        val xRight = width - MARGIN * fs
        val availFs = (xRight - xLeft) / fs

        // baris
        val rows = ArrayList<IntRange>()
        var i = 0
        while (i < nBars) {
            var j = i
            var sum = 0f
            while (j < nBars && (j == i || sum + measW[j] <= availFs)) {
                sum += measW[j]
                j++
            }
            rows += i until j
            i = j
        }

        val barLeft = FloatArray(nBars)
        val barRight = FloatArray(nBars)
        val tokStretch = Array(nv) { FloatArray(nBars) }
        for ((ri, r) in rows.withIndex()) {
            val isLast = ri == rows.size - 1
            val natural = r.sumOf { measW[it].toDouble() }.toFloat()
            val f = (availFs / natural).coerceIn(0.6f, if (isLast) 1.2f else 1.6f)
            var x = xLeft
            for (mi in r) {
                barLeft[mi] = x
                for (v in 0 until nv) {
                    val own = sumW(v, mi)
                    // suara yang lebih jarang melebar mengisi birama, paling banyak 1,6x
                    val sv = f * if (own > 0f) minOf(innerW[mi] / own, 1.6f) else 1f
                    tokStretch[v][mi] = sv
                    var cum = PAD * f
                    for (tk in tks[v][mi]) {
                        tk.row = ri
                        tk.cx = x + (cum + tk.widthFs * sv / 2) * fs
                        cum += tk.widthFs * sv
                    }
                }
                x += measW[mi] * fs * f
                barRight[mi] = x
            }
        }

        // kelompok garis: simbol bergaris pada ketukan yang sama, berurutan
        for (v in 0 until nv) for (mi in 0 until nBars) {
            var cur = ArrayList<Tk>()
            var curBeat = -1
            fun flush() {
                if (cur.size >= 2) for (tk in cur) tk.group = cur
                cur = ArrayList()
            }
            for (tk in tks[v][mi]) {
                val beat = tk.t.startUnit / 4
                if (tk.lines > 0 && cur.isNotEmpty() && curBeat == beat) {
                    cur += tk
                } else {
                    flush()
                    if (tk.lines > 0) { cur += tk; curBeat = beat }
                }
            }
            flush()
        }

        val rowH = (nv * ROW_H + 0.4f) * fs
        fun baseline(row: Int, v: Int) = row * rowH + (v * ROW_H + BASELINE) * fs

        val ops = ArrayList<DrawOp>()
        val hits = ArrayList<Hit>()
        val bars = ArrayList<Bar>()
        for ((ri, r) in rows.withIndex()) {
            for (v in 0 until nv) {
                val by = baseline(ri, v)
                val top = by - 1.7f * fs
                val bottom = by + 2.0f * fs
                val lyricTop = by + 0.95f * fs
                for (mi in r) {
                    for (tk in tks[v][mi]) {
                        drawToken(tk, by, ops, fs)
                        tk.t.lyric?.let { l -> tk.opIds += ops.size; ops += TextOp(l, tk.cx, by + 1.65f * fs, 0.5f * fs) }
                        val half = tk.widthFs / 2 * fs * tokStretch[v][mi]
                        hits += Hit(
                            tk.t.startUnit, tk.t.startUnit + tk.t.dur16,
                            tk.cx - half, top, tk.cx + half, bottom, tk.opIds.toList(), v, lyricTop,
                        )
                    }
                    bars += Bar(mi, mi * beatsPerBar * 4, barLeft[mi], top, barRight[mi], bottom, v)
                    val bx = barRight[mi]
                    if (mi == nBars - 1) {
                        ops += LineOp(bx - 0.35f * fs, by - 1.0f * fs, bx - 0.35f * fs, by + 0.55f * fs, 0.07f * fs)
                        ops += LineOp(bx - 0.12f * fs, by - 1.0f * fs, bx - 0.12f * fs, by + 0.55f * fs, 0.2f * fs)
                    } else {
                        ops += LineOp(bx, by - 1.0f * fs, bx, by + 0.55f * fs, 0.07f * fs)
                    }
                }
            }
            // nomor birama di atas suara pertama
            for (mi in r) {
                val label = (mi + 1).toString()
                ops += TextOp(label, barLeft[mi] + (0.2f + 0.13f * label.length) * fs, baseline(ri, 0) - 1.45f * fs, 0.42f * fs)
            }
        }

        return Rendering(width, rows.size * rowH, ops, hits, bars)
    }

    private fun drawToken(tk: Tk, by: Float, ops: MutableList<DrawOp>, fs: Float) {
        fun add(op: DrawOp) { tk.opIds += ops.size; ops += op }
        val n = tk.n
        val cx = tk.cx

        when {
            n == null -> add(TextOp("0", cx, by, fs)) // rehat dan perpanjangan rehat
            tk.isDot -> add(DotOp(cx, by - 0.36f * fs, 0.09f * fs))
            else -> {
                add(TextOp(n.degree.toString(), cx, by, fs))
                // kres: garis miring "/" ; mol: garis miring balik "\", menempel pada angka
                if (n.accidental > 0) {
                    add(LineOp(cx - 0.34f * fs, by + 0.02f * fs, cx + 0.34f * fs, by - (CAP + 0.04f) * fs, 0.07f * fs))
                } else if (n.accidental < 0) {
                    add(LineOp(cx - 0.34f * fs, by - (CAP + 0.04f) * fs, cx + 0.34f * fs, by + 0.02f * fs, 0.07f * fs))
                }
                // titik oktaf: atas di atas garis durasi, bawah di bawah angka
                if (n.octaveShift > 0) {
                    for (k in 0 until n.octaveShift) {
                        add(DotOp(cx, by - (CAP + 0.14f + tk.lines * LINE_STEP + 0.2f + k * 0.2f) * fs, 0.07f * fs))
                    }
                } else if (n.octaveShift < 0) {
                    for (k in 0 until -n.octaveShift) add(DotOp(cx, by + (0.3f + k * 0.2f) * fs, 0.07f * fs))
                }
            }
        }

        // garis di atas; tetangga dalam kelompok yang punya garis pada tingkat sama disambung
        val g = tk.group
        val idx = g?.indexOf(tk) ?: -1
        val prev = if (g != null && idx > 0) g[idx - 1].takeIf { it.row == tk.row } else null
        val next = if (g != null && idx >= 0 && idx < g.size - 1) g[idx + 1].takeIf { it.row == tk.row } else null
        for (k in 0 until tk.lines) {
            val y = by - (CAP + 0.14f + k * LINE_STEP) * fs
            val x1 = if (prev != null && prev.lines > k) (prev.cx + cx) / 2 else cx - 0.42f * fs
            val x2 = if (next != null && next.lines > k) (next.cx + cx) / 2 else cx + 0.42f * fs
            add(LineOp(x1, y, x2, y, 0.07f * fs))
        }
    }
}
