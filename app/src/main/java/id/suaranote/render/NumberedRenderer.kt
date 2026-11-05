package id.suaranote.render

import id.suaranote.core.Measure
import id.suaranote.core.MusicKey
import id.suaranote.core.NoteEvent
import id.suaranote.core.Numbered
import id.suaranote.core.NumberedNote
import id.suaranote.core.durationStyle

/**
 * Tata letak not angka: angka 1..7, titik oktaf, garis bawah (1/8, 1/16), titik durasi,
 * tanda "-" perpanjangan, rehat "0", dan busur ikatan. [fs] = ukuran huruf angka dalam piksel.
 */
object NumberedRenderer {
    private const val PAD = 0.7f
    private const val MARGIN = 0.4f
    private const val ROW_H = 3.3f
    private const val BASELINE = 1.8f
    private const val CAP = 0.72f

    private class Ev(val e: NoteEvent, val n: NumberedNote?) {
        val underlines = durationStyle(e.dur16).underlines
        val dotted = durationStyle(e.dur16).dotted
        val dashes = durationStyle(e.dur16).dashes
        val accW = if (n != null && n.accidental != 0) 0.5f else 0f
        val digitW = when (underlines) { 0 -> 1.5f; 1 -> 1.25f; else -> 1.05f }
        val widthFs = accW + digitW + (if (dotted) 0.55f else 0f) + dashes * 1.3f
        var cx = 0f
        var row = 0
        var stretch = 1f
        var group: List<Ev>? = null
    }

    private class Meas(val evs: List<Ev>) {
        val widthFs = 2 * PAD + evs.sumOf { it.widthFs.toDouble() }.toFloat()
    }

    fun render(
        measures: List<Measure>,
        key: MusicKey,
        octaveOffset: Int,
        width: Float,
        fs: Float,
    ): Rendering {
        if (measures.isEmpty()) return Rendering(width, 0f, emptyList())

        val meas = measures.map { m ->
            Meas(m.evs(key, octaveOffset))
        }

        val xLeft = MARGIN * fs
        val xRight = width - MARGIN * fs
        val availFs = (xRight - xLeft) / fs

        // baris
        val rows = ArrayList<IntRange>()
        var i = 0
        while (i < meas.size) {
            var j = i
            var sum = 0f
            while (j < meas.size && (j == i || sum + meas[j].widthFs <= availFs)) {
                sum += meas[j].widthFs
                j++
            }
            rows += i until j
            i = j
        }

        val ops = ArrayList<DrawOp>()
        val flat = ArrayList<Ev>()
        val barX = FloatArray(meas.size)
        for ((ri, r) in rows.withIndex()) {
            val isLast = ri == rows.size - 1
            val natural = r.sumOf { meas[it].widthFs.toDouble() }.toFloat()
            val f = (availFs / natural).coerceIn(0.6f, if (isLast) 1.2f else 1.6f)
            var x = xLeft
            for (mi in r) {
                var cum = PAD
                for (ev in meas[mi].evs) {
                    ev.row = ri
                    ev.stretch = f
                    ev.cx =x + (cum + ev.accW + ev.digitW / 2) * fs * f
                    flat += ev
                    cum += ev.widthFs
                }
                x += meas[mi].widthFs * fs * f
                barX[mi] = x
            }
        }

        // kelompok garis bawah: not 1/8 atau 1/16 pada ketukan yang sama
        for (m in meas) {
            var pos = 0
            var cur = ArrayList<Ev>()
            var curBeat = -1
            fun flush() {
                if (cur.size >= 2) for (ev in cur) ev.group = cur
                cur = ArrayList()
            }
            for (ev in m.evs) {
                val under = ev.n != null && ev.underlines > 0
                val beat = pos / 4
                if (under && cur.isNotEmpty() && curBeat == beat) {
                    cur += ev
                } else {
                    flush()
                    if (under) { cur += ev; curBeat = beat }
                }
                pos += ev.e.dur16
            }
            flush()
        }

        fun baseline(row: Int) = row * ROW_H * fs + BASELINE * fs

        for ((ri, r) in rows.withIndex()) {
            val by = baseline(ri)
            for (mi in r) {
                for (ev in meas[mi].evs) drawEvent(ev, by, ops, fs)
                val bx = barX[mi]
                if (mi == meas.size - 1) {
                    ops += LineOp(bx - 0.35f * fs, by - 1.0f * fs, bx - 0.35f * fs, by + 0.55f * fs, 0.07f * fs)
                    ops += LineOp(bx - 0.12f * fs, by - 1.0f * fs, bx - 0.12f * fs, by + 0.55f * fs, 0.2f * fs)
                } else {
                    ops += LineOp(bx, by - 1.0f * fs, bx, by + 0.55f * fs, 0.07f * fs)
                }
            }
        }

        // garis bawah menyambung dalam satu kelompok
        val seen = HashSet<List<Ev>>()
        for (ev in flat) {
            val g = ev.group ?: continue
            if (!seen.add(g)) continue
            val by = baseline(g.first().row)
            ops += LineOp(g.first().cx - 0.42f * fs, by + 0.17f * fs, g.last().cx + 0.42f * fs, by + 0.17f * fs, 0.07f * fs)
            for ((idx, e) in g.withIndex()) {
                if (e.underlines < 2) continue
                val next = g.getOrNull(idx + 1)
                val prev = g.getOrNull(idx - 1)
                val y2 = by + 0.34f * fs
                if (next != null && next.underlines >= 2) {
                    ops += LineOp(e.cx - 0.42f * fs, y2, next.cx + 0.42f * fs, y2, 0.07f * fs)
                } else if (prev == null || prev.underlines < 2) {
                    ops += LineOp(e.cx - 0.42f * fs, y2, e.cx + 0.42f * fs, y2, 0.07f * fs)
                }
            }
        }

        // ikatan (tie)
        for (k in 0 until flat.size - 1) {
            val a = flat[k]
            if (!a.e.tiedToNext) continue
            val b = flat[k + 1]
            val above = a.n?.let { (it.octaveShift).coerceAtLeast(0) } ?: 0
            val ya = baseline(a.row) - CAP * fs - above * 0.2f * fs - 0.45f * fs
            if (a.row == b.row) {
                ops += TieOp(a.cx + 0.2f * fs, b.cx - 0.2f * fs, ya, true, 0.3f * fs, 0.07f * fs)
            } else {
                ops += TieOp(a.cx + 0.2f * fs, xRight, ya, true, 0.25f * fs, 0.07f * fs)
                val aboveB = b.n?.let { (it.octaveShift).coerceAtLeast(0) } ?: 0
                val yb = baseline(b.row) - CAP * fs - aboveB * 0.2f * fs - 0.45f * fs
                ops += TieOp(xLeft, b.cx - 0.2f * fs, yb, true, 0.25f * fs, 0.07f * fs)
            }
        }

        return Rendering(width, rows.size * ROW_H * fs, ops)
    }

    private fun Measure.evs(key: MusicKey, octaveOffset: Int): List<Ev> =
        events.map { e -> Ev(e, e.midi?.let { Numbered.of(it, key, octaveOffset) }) }

    private fun drawEvent(ev: Ev, by: Float, ops: MutableList<DrawOp>, fs: Float) {
        val n = ev.n
        val cx = ev.cx
        if (n == null) {
            ops += TextOp("0", cx, by, fs)
        } else {
            ops += TextOp(n.degree.toString(), cx, by, fs)
            if (n.accidental != 0) {
                ops += TextOp(if (n.accidental > 0) "#" else "b", cx - 0.62f * fs, by - 0.4f * fs, fs * 0.6f)
            }
            // titik oktaf
            if (n.octaveShift > 0) {
                for (k in 0 until n.octaveShift) ops += DotOp(cx, by - CAP * fs - 0.2f * fs - k * 0.2f * fs, 0.07f * fs)
            } else if (n.octaveShift < 0) {
                val start = by + 0.17f * fs * ev.underlines + 0.3f * fs
                for (k in 0 until -n.octaveShift) ops += DotOp(cx, start + k * 0.2f * fs, 0.07f * fs)
            }
        }
        // garis bawah tunggal (bukan kelompok) digambar di sini; yang berkelompok digambar menyambung
        if (ev.group == null && ev.underlines > 0 && n != null) {
            for (u in 0 until ev.underlines) {
                val yy = by + (0.17f + 0.17f * u) * fs
                ops += LineOp(cx - 0.42f * fs, yy, cx + 0.42f * fs, yy, 0.07f * fs)
            }
        }
        // titik durasi
        val u = fs * ev.stretch
        var xAfter = cx + ev.digitW / 2 * u
        if (ev.dotted) {
            ops += DotOp(xAfter + 0.12f * fs, by - 0.25f * fs, 0.08f * fs)
            xAfter += 0.55f * u
        }
        // tanda perpanjangan "-"
        for (d in 0 until ev.dashes) {
            val dx = xAfter + 0.65f * u + d * 1.3f * u
            ops += LineOp(dx - 0.3f * fs, by - 0.27f * fs, dx + 0.3f * fs, by - 0.27f * fs, 0.08f * fs)
        }
    }
}
