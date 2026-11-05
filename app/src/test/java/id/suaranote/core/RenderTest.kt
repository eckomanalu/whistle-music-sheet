package id.suaranote.core

import id.suaranote.render.LineOp
import id.suaranote.render.NumberedRenderer
import id.suaranote.render.StaffRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji tata letak: setiap simbol punya kotak sentuh/sorotan dengan satuan waktu yang benar. */
class RenderTest {
    private fun song(vararg e: Pair<Int?, Int>): Composition =
        e.fold(Composition()) { c, (m, d) -> c.add(m, d) }

    @Test
    fun numberedHitsCoverEveryTokenInTimeOrder() {
        val c = song(60 to 4, 62 to 8, 64 to 2, null to 2, 65 to 16, 67 to 4)
        val tokens = c.tokens(4)
        val r = NumberedRenderer.render(listOf(tokens), MusicKey.C, 0, 800f, 40f)
        assertEquals(tokens.sumOf { it.size }, r.hits.size)
        assertEquals(tokens.size, r.bars.size)
        assertEquals(r.hits.map { it.startUnit }, r.hits.map { it.startUnit }.sorted())
        for (h in r.hits) assertTrue("kotak kosong", h.right > h.left && h.bottom > h.top && h.ops.isNotEmpty())
        // setiap satuan waktu 0..total jatuh tepat pada satu simbol
        for (u in 0 until c.totalUnits) assertEquals("unit $u", 1, r.hits.count { u >= it.startUnit && u < it.endUnit })
    }

    @Test
    fun linesAreAboveTheNumberAndSlashMarksAccidentals() {
        val fs = 40f
        // 1/8 pada C (tanpa tanda): satu garis horizontal di atas angka; baseline baris pertama = 2.3*fs
        val eighth = NumberedRenderer.render(listOf(song(60 to 2).tokens(4)), MusicKey.C, 0, 800f, fs)
        val by = 2.3f * fs
        val lines = eighth.ops.filterIsInstance<LineOp>().filter { it.y1 == it.y2 && it.y1 < by - 0.7f * fs && it.x2 - it.x1 > 0.5f * fs }
        assertEquals(1, lines.size)
        // 1/16: dua garis
        val sixteenth = NumberedRenderer.render(listOf(song(60 to 1).tokens(4)), MusicKey.C, 0, 800f, fs)
        assertEquals(2, sixteenth.ops.filterIsInstance<LineOp>().count { it.y1 == it.y2 && it.y1 < by - 0.7f * fs && it.x2 - it.x1 > 0.5f * fs })
        // kres (F# = 66 di C): garis miring naik "/" (y awal lebih besar dari y akhir); mol (Bb = 70 di F bukan akan; Db = 61 di F): turun "\"
        fun slashes(midi: Int, key: MusicKey) = NumberedRenderer.render(listOf(song(midi to 4).tokens(4)), key, 0, 800f, fs)
            .ops.filterIsInstance<LineOp>().filter { it.x1 != it.x2 && it.y1 != it.y2 }
        val sharp = slashes(66, MusicKey.C)
        assertEquals(1, sharp.size)
        assertTrue("kres harus miring naik", sharp[0].x1 < sharp[0].x2 && sharp[0].y1 > sharp[0].y2)
        val flat = slashes(61, MusicKey.F)
        assertEquals(1, flat.size)
        assertTrue("mol harus miring turun", flat[0].x1 < flat[0].x2 && flat[0].y1 < flat[0].y2)
        assertEquals(0, slashes(60, MusicKey.C).size)
    }

    @Test
    fun staffHitsMatchEventsAndUnits() {
        val c = song(60 to 12, 64 to 8, 67 to 4)
        val m = c.measures(4)
        val r = StaffRenderer.render(m, MusicKey.C, 4, 800f, 12f)
        assertEquals(m.sumOf { it.events.size }, r.hits.size)
        assertEquals(m.size, r.bars.size)
        for (u in 0 until c.totalUnits) assertEquals("unit $u", 1, r.hits.count { u >= it.startUnit && u < it.endUnit })
        for (h in r.hits) assertTrue(h.right > h.left && h.bottom > h.top && h.ops.isNotEmpty())
    }

    @Test
    fun twoVoicesStackWithAlignedBarsAndBarNumbers() {
        val a = song(60 to 4, 62 to 4, 64 to 4, 65 to 4, 67 to 8, 69 to 8, 71 to 16)
        val b = song(48 to 16, 50 to 16)
        val score = Score(listOf(a, b))
        val tokens = score.tokens(4)
        val num = NumberedRenderer.render(tokens, MusicKey.C, 0, 800f, 40f, 4)
        assertEquals(setOf(0, 1), num.hits.map { it.voice }.toSet())
        assertEquals(tokens[0].sumOf { it.size } + tokens[1].sumOf { it.size }, num.hits.size)
        // barline sejajar: untuk tiap birama, kiri/kanan sama pada kedua suara
        for (m in 0 until 3) {
            val bars = num.bars.filter { it.measure == m }
            assertEquals(2, bars.size)
            assertEquals(bars[0].left, bars[1].left, 0.01f)
            assertEquals(bars[0].right, bars[1].right, 0.01f)
            assertEquals(m * 16, bars[0].startUnit)
        }
        // nomor birama muncul sekali per birama (hanya suara pertama)
        val numTexts = num.ops.filterIsInstance<id.suaranote.render.TextOp>().filter { it.size < 40f * 0.5f }.map { it.text }
        assertEquals(listOf("1", "2", "3"), numTexts)

        val staff = StaffRenderer.renderVoices(score.measures(4), MusicKey.C, 4, 700f, 12f)
        assertEquals(setOf(0, 1), staff.hits.map { it.voice }.toSet())
        for (m in 0 until 3) {
            val bars = staff.bars.filter { it.measure == m }
            assertEquals(2, bars.size)
            assertEquals(bars[0].left, bars[1].left, 0.5f)
            assertEquals(bars[0].right, bars[1].right, 0.5f)
            assertEquals(true, bars[1].top > bars[0].top) // suara kedua di bawah suara pertama
        }
        // setiap satuan waktu jatuh pada tepat satu simbol per suara
        for (v in 0..1) for (u in 0 until score.voices[v].totalUnits) {
            assertEquals("voice $v unit $u", 1, staff.hits.count { it.voice == v && u >= it.startUnit && u < it.endUnit })
        }
    }
}
