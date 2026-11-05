package id.suaranote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uji susunan lagu dari tuts (not, rehat, hapus, birama, ikatan) serta ejaan dan not angka. */
class ScoreTest {

    private fun song(vararg e: Pair<Int?, Int>): Composition =
        e.fold(Composition()) { c, (m, d) -> c.add(m, d) }

    private fun numbered(c: Composition, key: MusicKey = MusicKey.C, beats: Int = 4) =
        NotationText.numbered(c.measures(beats), key)

    @Test
    fun twinkleFromKeyPresses() {
        val c = song(60 to 4, 60 to 4, 67 to 4, 67 to 4, 69 to 4, 69 to 4, 67 to 8)
        assertEquals("1 = C\n| 1 1 5 5 | 6 6 5 - |", numbered(c))
    }

    @Test
    fun eighthNotesAndRests() {
        val c = song(60 to 2, 62 to 2, 64 to 2, 65 to 2, 67 to 8, 69 to 4, null to 4, 64 to 4)
        assertEquals("1 = C\n| 1_ 2_ 3_ 4_ 5 - | 6 0 3 |", numbered(c))
    }

    @Test
    fun longNoteIsTiedAcrossBarline() {
        val m = song(60 to 12, 64 to 8).measures(4)
        assertEquals(2, m.size)
        assertEquals(listOf(NoteEvent(60, 12), NoteEvent(64, 4, tiedToNext = true)), m[0].events)
        assertEquals(listOf(NoteEvent(64, 4)), m[1].events)
    }

    @Test
    fun otherTimeSignatureAndKey() {
        val c = song(67 to 4, 67 to 4, 74 to 4, 74 to 4, 76 to 4, 76 to 4)
        assertEquals("1 = G\n| 1 1 5 | 5 6 6 |", numbered(c, MusicKey.G, beats = 3))
    }

    @Test
    fun deleteAlwaysRemovesOnlyTheLastEntry() {
        var c = song(60 to 4, 62 to 4, 64 to 8, null to 4)
        c = c.removeLast()
        assertEquals(listOf(60, 62, 64), c.events.map { it.midi })
        c = c.removeLast()
        assertEquals(listOf(60, 62), c.events.map { it.midi })
        c = c.removeLast().removeLast()
        assertEquals(emptyList<NoteEvent>(), c.events)
        assertEquals(emptyList<NoteEvent>(), c.removeLast().events) // kosong: tidak error
    }

    @Test
    fun deleteRemovesWholeNoteEvenWhenItIsSplitByBarline() {
        var c = song(60 to 12, 64 to 8)
        assertEquals(2, c.measures(4).size)
        c = c.removeLast()
        assertEquals(1, c.measures(4).size)
        assertEquals(listOf(NoteEvent(60, 12)), c.measures(4)[0].events)
    }

    @Test
    fun metronomeClicksLandOnBeats() {
        val silent = ShortArray(Synth.RATE * 2)
        val mixed = Synth.mixClicks(silent, 120, 4) // tik tiap 0,5 detik
        assertEquals(silent.size, mixed.size)
        fun energy(from: Double) = (0 until 600).sumOf { Math.abs(mixed[(from * Synth.RATE).toInt() + it].toInt()) }
        assertEquals(true, energy(0.0) > 0)
        assertEquals(true, energy(0.5) > 0)
        assertEquals(true, energy(1.5) > 0)
        assertEquals(0, energy(0.25)) // di antara ketukan sunyi
    }

    @Test
    fun dotAndExtend() {
        val c = song(60 to 4, 62 to 8)
        assertEquals(12, c.dotLast().events.last().dur16) // setengah menjadi bertitik
        assertEquals(listOf(60, 62), c.dotLast().events.map { it.midi })
        assertEquals(false, c.dotLast().canDot()) // sudah bertitik: tidak ada titik ganda
        assertEquals(6, song(60 to 4).dotLast().events.last().dur16)
        assertEquals(3, song(60 to 2).dotLast().events.last().dur16)
        assertEquals(song(60 to 1), song(60 to 1).dotLast()) // 1/16 tidak bisa dititik
        assertEquals(Composition(), Composition().dotLast())
        assertEquals(8, song(60 to 4).extendLast(4).events.last().dur16) // sambung satu ketuk
        assertEquals(song(60 to 64), song(60 to 64).extendLast(4)) // batas
        val dotted = Composition().add(60, 4).dotLast().add(62, 2).add(64, 8).add(null, 2)
        assertEquals("1 = C\n| 1. 2_ 3 - | 0_ |", numbered(dotted))
    }

    @Test
    fun liveLegatoNeverMakesRests() {
        val unit = 60.0 / 90 / 4
        assertEquals(2 to 2, LiveTiming.settle(0.2, 0.67, unit, 2))
        assertEquals(4 to 0, LiveTiming.settle(0.2, 0.67, unit, 2, legato = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDurationOutOfRange() {
        Composition().add(60, 0)
    }

    @Test
    fun liveQuantizeAndSettle() {
        val unit = 60.0 / 90 / 4 // 90 bpm
        assertEquals(1, LiveTiming.quantize(0.17, unit, 1))
        assertEquals(2, LiveTiming.quantize(0.05, unit, 2)) // minimal satu kotak
        assertEquals(4, LiveTiming.quantize(0.7, unit, 2)) // satu ketuk
        assertEquals(64, LiveTiming.quantize(60.0, unit, 2)) // dibatasi
        assertEquals(4 to 0, LiveTiming.settle(0.6, 0.67, unit, 2)) // legato dengan celah kecil
        assertEquals(2 to 2, LiveTiming.settle(0.2, 0.67, unit, 2)) // staccato: not 1/8 + rehat 1/8
        assertEquals(4 to 0, LiveTiming.settle(0.8, 0.67, unit, 2)) // tumpang tindih
    }

    @Test
    fun liveGrowingNoteAndLongNoteTies() {
        var c = Composition().add(60, 2)
        c = c.replaceLast(6).replaceLast(20)
        assertEquals(listOf(NoteEvent(60, 20)), c.events)
        val m = c.measures(4) // 20 > 16: satu birama penuh + 4, diikat
        assertEquals(listOf(NoteEvent(60, 16, tiedToNext = true)), m[0].events)
        assertEquals(listOf(NoteEvent(60, 4)), m[1].events)
        assertEquals(Composition(), Composition().replaceLast(4))
    }

    @Test
    fun spellingOfMajorScalesIsCorrectInAllKeys() {
        val intervals = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        for (key in MusicKey.values()) {
            val tonic = 60 + key.tonicPc
            val spelled = intervals.map { Spelling.spell(tonic + it, key) }
            assertEquals("${key.label}: huruf harus berbeda", 7, spelled.map { it.letter }.toSet().size)
            for ((idx, s) in spelled.withIndex()) {
                val pc = Math.floorMod(intArrayOf(0, 2, 4, 5, 7, 9, 11)[s.letter] + s.alter, 12)
                assertEquals(Math.floorMod(tonic + intervals[idx], 12), pc)
                assertEquals("${key.label}: nada tangga nada tidak butuh tanda tambahan", null, s.accidentalSign)
            }
        }
        // contoh spesifik
        assertEquals("Db4", Spelling.spell(61, MusicKey.DFlat).name)
        assertEquals("Gb4", Spelling.spell(66, MusicKey.DFlat).name)
        assertEquals("F#4", Spelling.spell(66, MusicKey.D).name)
        assertEquals("C#4", Spelling.spell(61, MusicKey.C).name)
        // pembatalan armatur: C natural di D mayor ditulis C dengan tanda natural, bukan B#
        val cNat = Spelling.spell(60, MusicKey.D)
        assertEquals("C4", cNat.name)
        assertEquals(0, cNat.accidentalSign)
        assertEquals("F4", Spelling.spell(65, MusicKey.D).name)
    }

    @Test
    fun spellingCoversEveryPitchInEveryKey() {
        for (key in MusicKey.values()) for (midi in 36..96) {
            val s = Spelling.spell(midi, key)
            val natural = intArrayOf(0, 2, 4, 5, 7, 9, 11)[s.letter]
            assertEquals("${key.label} midi $midi", midi, 12 * (s.octave + 1) + natural + s.alter)
        }
    }

    @Test
    fun numberedNotationOctaves() {
        assertEquals(NumberedNote(1, 0, 0), Numbered.of(60, MusicKey.C))
        assertEquals(NumberedNote(3, 0, 0), Numbered.of(64, MusicKey.C))
        assertEquals(NumberedNote(7, 0, -1), Numbered.of(59, MusicKey.C))
        assertEquals(NumberedNote(1, 0, 1), Numbered.of(72, MusicKey.C))
        assertEquals(NumberedNote(4, 1, 0), Numbered.of(66, MusicKey.C))
        // nada dasar F: do = F4 (65). Bb4 adalah derajat 4, Eb5 adalah b7.
        assertEquals(NumberedNote(4, 0, 0), Numbered.of(70, MusicKey.F))
        assertEquals(NumberedNote(7, -1, 0), Numbered.of(75, MusicKey.F))
        // nada dasar G: do = G4 (67); G3 satu oktaf di bawah
        assertEquals(NumberedNote(1, 0, 0), Numbered.of(67, MusicKey.G))
        assertEquals(NumberedNote(1, 0, -1), Numbered.of(55, MusicKey.G))
        assertEquals(NumberedNote(1, 0, -1), Numbered.of(59, MusicKey.B))
        // geser oktaf tampilan
        assertEquals(NumberedNote(1, 0, 0), Numbered.of(55, MusicKey.G, octaveOffset = 1))
    }
}
