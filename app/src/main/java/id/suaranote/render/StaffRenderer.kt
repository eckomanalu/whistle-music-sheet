package id.suaranote.render

import id.suaranote.core.Measure
import id.suaranote.core.MusicKey
import id.suaranote.core.NoteEvent
import id.suaranote.core.SpelledNote
import id.suaranote.core.Spelling
import id.suaranote.core.durationStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Tata letak not balok (kunci G) dengan pemenggalan baris otomatis.
 * Ukuran dasar: [sp] piksel per spasi paranada.
 */
object StaffRenderer {
    private const val HEAD_W = 1.18f
    private const val WHOLE_W = 1.69f
    private const val STEM_T = 0.12f
    private const val STAFF_T = 0.13f
    private const val LEDGER_T = 0.16f
    private const val BAR_T = 0.16f
    private const val BEAM_T = 0.5f
    private const val PAD = 0.9f
    private const val MARGIN = 0.5f
    private const val SYSTEM_GAP = 1.5f

    /** Di not balok, 3/8, 3/16 dan 3/4 ketuk (3, 6, 12 enam-belas) ditulis dengan titik. */
    private fun isDotted(dur16: Int) = dur16 == 3 || dur16 == 6 || dur16 == 12

    private val SHARP_STEPS = intArrayOf(8, 5, 9, 6, 3, 7, 4)
    private val FLAT_STEPS = intArrayOf(4, 7, 3, 6, 2, 5, 1)

    private class Group(val evs: List<Ev>, val up: Boolean, val beamStep: Int)

    private class Ev(
        val e: NoteEvent,
        val spelled: SpelledNote?,
        val step: Int,
        val baseSp: Float,
        val accSp: Float,
        val dotSp: Float,
    ) {
        val totalSp: Float get() = baseSp + accSp + dotSp
        var stemUp = false
        var group: Group? = null
        var cx = 0f
        var sys = 0
        var left = 0f
        var right = 0f
        var startUnit = 0
        var opFrom = 0
        var opTo = 0
        val isNote: Boolean get() = e.midi != null
        val stemEndStep: Int
            get() = group?.beamStep ?: if (stemUp) max(step + 7, 4) else min(step - 7, 4)
    }

    private class Meas(val evs: List<Ev>, minWidthSp: Float = 0f) {
        val ownSp: Float = 2 * PAD + evs.sumOf { it.totalSp.toDouble() }.toFloat()
        val widthSp: Float = maxOf(ownSp, minWidthSp, 2 * PAD + 2f)
        var xStart = 0f
        var xEnd = 0f
        var startUnit = 0
    }

    private class Sys(val first: Int, val last: Int) { // indeks birama, inklusif
        var topStep = 12
        var bottomStep = -4
        var yBottomLine = 0f
        var contentLeft = 0f
        var rightEdge = 0f
    }

    /** Lebar alami tiap birama (satuan spasi paranada); dipakai untuk menyamakan lebar antar-suara. */
    fun naturalWidths(measures: List<Measure>, key: MusicKey): FloatArray =
        FloatArray(measures.size) { maxOf(prepare(measures[it], key).ownSp, 2 * PAD + 2f) }

    private fun headerSp(keyCount: Int, first: Boolean): Float =
        3.6f + (if (keyCount > 0) keyCount * 1.1f + 0.6f else 0f) + (if (first) 2.4f else 0f)

    /** Membagi birama ke baris (sistem): setiap kelompok memuat birama sebanyak yang muat di [width]. */
    fun groupBars(widthsSp: FloatArray, key: MusicKey, width: Float, sp: Float): List<IntRange> {
        val keyCount = kotlin.math.abs(key.fifths)
        val totalSp = (width - 2 * MARGIN * sp) / sp
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < widthsSp.size) {
            val avail = totalSp - headerSp(keyCount, out.isEmpty())
            var j = i
            var sum = 0f
            while (j < widthsSp.size && (j == i || sum + widthsSp[j] <= avail)) {
                sum += widthsSp[j]
                j++
            }
            out += i until j
            i = j
        }
        return out
    }

    /**
     * Beberapa suara: tiap kelompok birama digambar sebagai satu baris per suara (bertumpuk), dengan lebar
     * birama yang sama antar-suara. Hanya suara pertama yang diberi nomor birama.
     */
    fun renderVoices(voices: List<List<Measure>>, key: MusicKey, beatsPerBar: Int, width: Float, sp: Float): Rendering {
        val n = voices.maxOfOrNull { it.size } ?: 0
        if (n == 0) return Rendering(width, 0f, emptyList())
        val padded = voices.map { it + List(n - it.size) { Measure(emptyList()) } }
        val widths = FloatArray(n) { mi -> padded.maxOf { naturalWidths(listOf(it[mi]), key)[0] } }
        val groups = groupBars(widths, key, width, sp)
        val ops = ArrayList<DrawOp>()
        val hits = ArrayList<Hit>()
        val bars = ArrayList<Bar>()
        var y = 0f
        for ((gi, g) in groups.withIndex()) {
            for ((vi, v) in padded.withIndex()) {
                val prevLast = if (g.first > 0) v[g.first - 1].events.lastOrNull() else null
                val r = render(
                    v.subList(g.first, g.last + 1), key, beatsPerBar, width, sp,
                    minWidthsSp = widths.copyOfRange(g.first, g.last + 1),
                    singleSystem = true,
                    firstGroup = gi == 0,
                    lastGroup = gi == groups.size - 1,
                    measureOffset = g.first,
                    incomingTie = prevLast != null && prevLast.tiedToNext && prevLast.midi != null,
                    barNumbers = vi == 0,
                )
                val base = ops.size
                for (op in r.ops) ops += op.shiftY(y)
                for (h in r.hits) hits += h.copy(top = h.top + y, bottom = h.bottom + y, ops = h.ops.map { it + base }, voice = vi)
                for (b in r.bars) bars += b.copy(top = b.top + y, bottom = b.bottom + y, voice = vi)
                y += r.height
            }
            y += 0.8f * sp
        }
        return Rendering(width, y, ops, hits, bars)
    }

    private fun DrawOp.shiftY(dy: Float): DrawOp = when (this) {
        is LineOp -> copy(y1 = y1 + dy, y2 = y2 + dy)
        is GlyphOp -> copy(y = y + dy)
        is DotOp -> copy(y = y + dy)
        is TieOp -> copy(y = y + dy)
        is TextOp -> copy(baseline = baseline + dy)
    }

    fun render(
        measures: List<Measure>,
        key: MusicKey,
        beatsPerBar: Int,
        width: Float,
        sp: Float,
        minWidthsSp: FloatArray? = null,
        singleSystem: Boolean = false,
        firstGroup: Boolean = true,
        lastGroup: Boolean = true,
        measureOffset: Int = 0,
        incomingTie: Boolean = false,
        barNumbers: Boolean = true,
    ): Rendering {
        if (measures.isEmpty()) return Rendering(width, 0f, emptyList())

        val meas = measures.mapIndexed { i, m -> prepare(m, key, minWidthsSp?.getOrNull(i) ?: 0f) }
        val keyCount = kotlin.math.abs(key.fifths)

        // ---- pengelompokan birama ke dalam baris (sistem) ----
        val xLeft = MARGIN * sp
        val xRight = width - MARGIN * sp
        val totalSp = (xRight - xLeft) / sp
        fun headerSp(first: Boolean): Float = headerSp(keyCount, first && firstGroup)

        val widthsAll = FloatArray(meas.size) { meas[it].widthSp }
        val systems = ArrayList<Sys>()
        if (singleSystem) {
            systems += Sys(0, meas.size - 1)
        } else {
            for (g in groupBars(widthsAll, key, width, sp)) systems += Sys(g.first, g.last)
        }

        // satuan waktu mutlak (1/16) tiap birama dan not
        for ((mi, m) in meas.withIndex()) {
            m.startUnit = (measureOffset + mi) * beatsPerBar * 4
            var p = m.startUnit
            for (ev in m.evs) { ev.startUnit = p; p += ev.e.dur16 }
        }

        // ---- posisi horizontal ----
        val ops = ArrayList<DrawOp>()
        val flat = ArrayList<Ev>()
        val barlineX = ArrayList<Float>(meas.size)
        for ((si, s) in systems.withIndex()) {
            val isLast = si == systems.size - 1 && lastGroup
            val avail = totalSp - headerSp(si == 0)
            val natural = (s.first..s.last).sumOf { meas[it].widthSp.toDouble() }.toFloat()
            val stretchCap = if (isLast) 1.15f else 1.5f
            val f = (avail / natural).coerceIn(0.6f, stretchCap)

            s.contentLeft = xLeft + headerSp(si == 0) * sp
            s.rightEdge = xRight
            var x = s.contentLeft
            for (mi in s.first..s.last) {
                val m = meas[mi]
                m.xStart = x
                // birama yang lebih jarang dari lebar bersama (suara lain lebih padat) dilonggarkan, paling banyak 1,6x
                val g = f * minOf(m.widthSp / m.ownSp, 1.6f)
                var cum = PAD
                for (ev in m.evs) {
                    ev.left = x + cum * sp * g
                    ev.cx = x + (cum + ev.accSp + ev.baseSp / 2) * sp * g
                    ev.sys = si
                    cum += ev.totalSp
                    ev.right = x + cum * sp * g
                    flat += ev
                }
                x += m.widthSp * sp * f
                m.xEnd = x
                barlineX += x
            }

            // ekstensi vertikal sistem ini
            for (mi in s.first..s.last) for (ev in meas[mi].evs) {
                if (!ev.isNote) continue
                if (ev.e.dur16 < 16) {
                    s.topStep = max(s.topStep, max(ev.step, if (ev.stemUp) ev.stemEndStep else ev.step) + 2)
                    s.bottomStep = min(s.bottomStep, min(ev.step, if (!ev.stemUp) ev.stemEndStep else ev.step) - 2)
                } else {
                    s.topStep = max(s.topStep, ev.step + 2)
                    s.bottomStep = min(s.bottomStep, ev.step - 2)
                }
            }
        }

        // ---- posisi vertikal ----
        var yCursor = 0f
        for (s in systems) {
            s.yBottomLine = yCursor + s.topStep * 0.5f * sp
            yCursor += (s.topStep - s.bottomStep) * 0.5f * sp + SYSTEM_GAP * sp
        }
        val totalHeight = yCursor

        fun y(sys: Sys, step: Int): Float = sys.yBottomLine - step * 0.5f * sp

        // ---- gambar ----
        for ((si, s) in systems.withIndex()) {
            // garis paranada
            for (line in 0..4) {
                val yy = y(s, line * 2)
                ops += LineOp(xLeft, yy, xRight, yy, STAFF_T * sp)
            }
            // barline awal
            ops += LineOp(xLeft, y(s, 8), xLeft, y(s, 0), BAR_T * sp)

            // kunci G (origin di garis G = langkah 2)
            ops += GlyphOp(Glyph.G_CLEF, xLeft + 0.5f * sp, y(s, 2), sp)

            // armatur
            var kx = xLeft + 3.7f * sp
            for (n in 0 until keyCount) {
                val step = if (key.fifths > 0) SHARP_STEPS[n] else FLAT_STEPS[n]
                ops += GlyphOp(if (key.fifths > 0) Glyph.SHARP else Glyph.FLAT, kx, y(s, step), sp)
                kx += 1.1f * sp
            }
            // birama (hanya sistem pertama)
            if (si == 0 && firstGroup) {
                val tx = s.contentLeft - 2.2f * sp
                val top = when (beatsPerBar) { 2 -> Glyph.TIME_2; 3 -> Glyph.TIME_3; else -> Glyph.TIME_4 }
                ops += GlyphOp(top, tx, y(s, 6), sp)
                ops += GlyphOp(Glyph.TIME_4, tx, y(s, 2), sp)
            }

            for (mi in s.first..s.last) {
                for (ev in meas[mi].evs) {
                    ev.opFrom = ops.size
                    drawEvent(ev, s, ops, sp, ::y)
                    ev.opTo = ops.size
                }

                // garis birama
                val bx = barlineX[mi]
                if (mi == meas.size - 1 && lastGroup) {
                    ops += LineOp(bx - 0.6f * sp, y(s, 8), bx - 0.6f * sp, y(s, 0), BAR_T * sp)
                    ops += LineOp(bx - 0.25f * sp, y(s, 8), bx - 0.25f * sp, y(s, 0), 0.5f * sp)
                } else {
                    ops += LineOp(bx, y(s, 8), bx, y(s, 0), BAR_T * sp)
                }
                // balok (beam) untuk kelompok di birama ini
                val seen = HashSet<Group>()
                for (ev in meas[mi].evs) ev.group?.let { if (seen.add(it)) drawBeams(it, s, ops, sp, ::y) }
            }
        }

        // ---- ikatan (tie) ----
        for (k in 0 until flat.size - 1) {
            val a = flat[k]
            if (!a.e.tiedToNext || !a.isNote) continue
            val b = flat[k + 1]
            val sa = systems[a.sys]
            val up = !a.stemUp // busur di sisi yang berlawanan dengan stem
            val ya = y(sa, a.step) + (if (up) -0.7f else 0.7f) * sp
            if (a.sys == b.sys) {
                ops += TieOp(a.cx + 0.45f * sp, b.cx - 0.45f * sp, ya, up, 0.55f * sp, 0.14f * sp)
            } else {
                val sb = systems[b.sys]
                ops += TieOp(a.cx + 0.45f * sp, sa.rightEdge - 0.2f * sp, ya, up, 0.45f * sp, 0.14f * sp)
                val yb = y(sb, b.step) + (if (!b.stemUp) -0.7f else 0.7f) * sp
                ops += TieOp(sb.contentLeft - 0.6f * sp, b.cx - 0.45f * sp, yb, !b.stemUp, 0.45f * sp, 0.14f * sp)
            }
        }

        // ikatan yang masuk dari baris sebelumnya / keluar ke baris berikutnya (render per kelompok birama)
        if (incomingTie && flat.isNotEmpty() && flat.first().isNote) {
            val b = flat.first()
            val sb = systems[b.sys]
            val yb = y(sb, b.step) + (if (!b.stemUp) -0.7f else 0.7f) * sp
            ops += TieOp(sb.contentLeft - 0.6f * sp, b.cx - 0.45f * sp, yb, !b.stemUp, 0.45f * sp, 0.14f * sp)
        }
        val lastEv = flat.lastOrNull()
        if (!lastGroup && lastEv != null && lastEv.isNote && lastEv.e.tiedToNext) {
            val sa = systems[lastEv.sys]
            val up = !lastEv.stemUp
            val ya = y(sa, lastEv.step) + (if (up) -0.7f else 0.7f) * sp
            ops += TieOp(lastEv.cx + 0.45f * sp, sa.rightEdge - 0.2f * sp, ya, up, 0.45f * sp, 0.14f * sp)
        }

        // nomor birama
        if (barNumbers) {
            for ((mi, m) in meas.withIndex()) {
                val sys = systems.first { mi in it.first..it.last }
                val label = (measureOffset + mi + 1).toString()
                ops += TextOp(label, m.xStart + (0.35f + 0.3f * label.length) * sp, y(sys, sys.topStep) + 0.95f * sp, 0.95f * sp)
            }
        }

        val hits = ArrayList<Hit>()
        val bars = ArrayList<Bar>()
        for ((mi, m) in meas.withIndex()) {
            val sysOfMeasure = systems.first { mi in it.first..it.last }
            val top = y(sysOfMeasure, sysOfMeasure.topStep)
            val bottom = y(sysOfMeasure, sysOfMeasure.bottomStep)
            bars += Bar(mi, m.startUnit, m.xStart, top, m.xEnd, bottom)
            for (ev in m.evs) {
                hits += Hit(ev.startUnit, ev.startUnit + ev.e.dur16, ev.left, top, ev.right, bottom, (ev.opFrom until ev.opTo).toList())
            }
        }

        return Rendering(width, totalHeight, ops, hits, bars)
    }

    // ------------------------------------------------------------------

    private fun prepare(m: Measure, key: MusicKey, minWidthSp: Float = 0f): Meas {
        val evs = ArrayList<Ev>()
        for (e in m.events) {
            val midi = e.midi
            val spelled = midi?.let { Spelling.spell(it, key) }
            val step = spelled?.let { Spelling.trebleStep(it) } ?: 4
            val st = durationStyle(e.dur16)
            val base = 2.0f + 0.32f * e.dur16
            val acc = if (spelled?.accidentalSign != null) 1.35f else 0f
            val dot = if (isDotted(e.dur16)) 0.7f else 0f
            val ev = Ev(e, spelled, step, base, acc, dot)
            if (ev.isNote) ev.stemUp = step < 4
            evs += ev
        }

        // kelompok balok: not bernilai <= 1/8 bertitik dalam ketukan yang sama
        var pos = 0
        var cur = ArrayList<Ev>()
        var curBeat = -1
        fun flush() {
            if (cur.size >= 2) {
                val avg = cur.sumOf { it.step.toDouble() } / cur.size
                val up = avg < 4
                val beamStep = if (up) max(cur.maxOf { it.step } + 7, 4) else min(cur.minOf { it.step } - 7, 4)
                val g = Group(ArrayList(cur), up, beamStep)
                for (ev in cur) { ev.stemUp = up; ev.group = g }
            }
            cur = ArrayList()
        }
        for (ev in evs) {
            val beamable = ev.isNote && ev.e.dur16 in 1..3
            val beat = pos / 4
            if (beamable && cur.isNotEmpty() && curBeat == beat) {
                cur += ev
            } else {
                flush()
                if (beamable) { cur += ev; curBeat = beat }
            }
            pos += ev.e.dur16
        }
        flush()
        return Meas(evs, minWidthSp)
    }

    private fun drawEvent(ev: Ev, s: Sys, ops: MutableList<DrawOp>, sp: Float, y: (Sys, Int) -> Float) {
        val dur = ev.e.dur16
        val st = durationStyle(dur)

        if (!ev.isNote) {
            val (glyph, step) = when (dur) {
                16 -> Glyph.REST_WHOLE to 6
                12, 8 -> Glyph.REST_HALF to 4
                6, 4 -> Glyph.REST_QUARTER to 4
                3, 2 -> Glyph.REST_8TH to 4
                else -> Glyph.REST_16TH to 4
            }
            ops += GlyphOp(glyph, ev.cx - glyph.advanceSp * sp / 2, y(s, step), sp)
            if (isDotted(dur)) ops += DotOp(ev.cx + glyph.advanceSp * sp / 2 + 0.45f * sp, y(s, 5), 0.2f * sp)
            return
        }

        val headW = if (dur == 16) WHOLE_W else HEAD_W
        val headLeft = ev.cx - headW * sp / 2
        val headGlyph = when {
            dur == 16 -> Glyph.HEAD_WHOLE
            dur >= 8 -> Glyph.HEAD_HALF
            else -> Glyph.HEAD_BLACK
        }
        val yh = y(s, ev.step)

        // garis bantu (ledger)
        val ledgerL = headLeft - 0.4f * sp
        val ledgerR = headLeft + headW * sp + 0.4f * sp
        var ls = -2
        while (ls >= ev.step) { ops += LineOp(ledgerL, y(s, ls), ledgerR, y(s, ls), LEDGER_T * sp); ls -= 2 }
        ls = 10
        while (ls <= ev.step) { ops += LineOp(ledgerL, y(s, ls), ledgerR, y(s, ls), LEDGER_T * sp); ls += 2 }

        // tanda kromatik
        ev.spelled?.accidentalSign?.let { sign ->
            val g = when (sign) { 1 -> Glyph.SHARP; -1 -> Glyph.FLAT; else -> Glyph.NATURAL }
            ops += GlyphOp(g, headLeft - 0.25f * sp - g.advanceSp * sp, yh, sp)
        }

        ops += GlyphOp(headGlyph, headLeft, yh, sp)

        if (isDotted(dur)) {
            val dy = if (Math.floorMod(ev.step, 2) == 0) y(s, ev.step + 1) else yh
            ops += DotOp(headLeft + headW * sp + 0.45f * sp, dy, 0.2f * sp)
        }

        if (dur == 16) return

        // stem
        val stemX = if (ev.stemUp) headLeft + (HEAD_W - STEM_T / 2) * sp else headLeft + STEM_T / 2 * sp
        val yEnd = y(s, ev.stemEndStep)
        ops += LineOp(stemX, yh, stemX, yEnd, STEM_T * sp)

        // bendera (hanya not tunggal; yang berkelompok memakai balok)
        if (ev.group == null && st.underlines > 0) {
            val glyph = if (ev.stemUp) {
                if (st.underlines >= 2) Glyph.FLAG_16_UP else Glyph.FLAG_8_UP
            } else {
                if (st.underlines >= 2) Glyph.FLAG_16_DOWN else Glyph.FLAG_8_DOWN
            }
            val fy = if (ev.stemUp) yEnd - 0.04f * sp else yEnd + 0.13f * sp
            ops += GlyphOp(glyph, stemX - STEM_T / 2 * sp, fy, sp)
        }
    }

    private fun drawBeams(g: Group, s: Sys, ops: MutableList<DrawOp>, sp: Float, y: (Sys, Int) -> Float) {
        fun stemX(ev: Ev): Float {
            val headLeft = ev.cx - HEAD_W * sp / 2
            return if (g.up) headLeft + (HEAD_W - STEM_T / 2) * sp else headLeft + STEM_T / 2 * sp
        }
        val yEnd = y(s, g.beamStep)
        val dir = if (g.up) 1f else -1f // arah ke dalam (menuju not) dalam koordinat layar
        val first = stemX(g.evs.first()) - STEM_T / 2 * sp
        val last = stemX(g.evs.last()) + STEM_T / 2 * sp
        // balok utama
        val y1 = yEnd + dir * (BEAM_T / 2) * sp
        ops += LineOp(first, y1, last, y1, BEAM_T * sp)

        // balok kedua untuk 1/16
        val y2 = yEnd + dir * (BEAM_T / 2 + 0.75f) * sp
        for ((idx, ev) in g.evs.withIndex()) {
            if (durationStyle(ev.e.dur16).underlines < 2) continue
            val prev = g.evs.getOrNull(idx - 1)
            val next = g.evs.getOrNull(idx + 1)
            val prevIs16 = prev != null && durationStyle(prev.e.dur16).underlines >= 2
            val nextIs16 = next != null && durationStyle(next.e.dur16).underlines >= 2
            if (nextIs16) {
                ops += LineOp(stemX(ev) - STEM_T / 2 * sp, y2, stemX(next!!) + STEM_T / 2 * sp, y2, BEAM_T * sp)
            } else if (!prevIs16) {
                // 1/16 tunggal: sepotong balok pendek menuju tetangga
                val sx = stemX(ev)
                if (prev != null) ops += LineOp(sx - 1.0f * sp, y2, sx + STEM_T / 2 * sp, y2, BEAM_T * sp)
                else ops += LineOp(sx - STEM_T / 2 * sp, y2, sx + 1.0f * sp, y2, BEAM_T * sp)
            }
        }
    }
}
