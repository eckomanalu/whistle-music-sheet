package id.suaranote.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uji susunan lagu dari tuts: simbol, hapus per simbol, birama, ikatan, serta ejaan dan not angka. */
class ScoreTest {

    private fun song(vararg e: Pair<Int?, Int>): Composition =
        e.fold(Composition()) { c, (m, d) -> c.add(m, d) }

    private fun numbered(c: Composition, key: MusicKey = MusicKey.C, beats: Int = 4) =
        NotationText.numbered(c, key, beats)

    @Test
    fun twinkleFromKeyPresses() {
        val c = song(60 to 4, 60 to 4, 67 to 4, 67 to 4, 69 to 4, 69 to 4, 67 to 8)
        assertEquals("1 = C\n| 1 1 5 5 | 6 6 5 . |", numbered(c))
    }

    @Test
    fun eighthNotesRestsAndExtensionDots() {
        val c = song(60 to 2, 62 to 2, 64 to 2, 65 to 2, 67 to 8, 69 to 4, null to 4, 64 to 4)
        assertEquals("1 = C\n| 1_ 2_ 3_ 4_ 5 . | 6 0 3 |", numbered(c))
    }

    @Test
    fun extensionLengthsAreDrawnAsDotsWithLines() {
        assertEquals("1 = C\n| 1 . |", numbered(song(60 to 4).extend(4)))
        assertEquals("1 = C\n| 1 ._ |", numbered(song(60 to 4).extend(2))) // setengah ketuk: titik + satu garis
        assertEquals("1 = C\n| 1 .__ |", numbered(song(60 to 4).extend(1))) // seperempat ketuk: titik + dua garis
        assertEquals("1 = C\n| 1 . ._ |", numbered(song(60 to 4).extend(6))) // 1,5 ketuk: dua simbol
        assertEquals("1 = C\n| 1_ .__ |", numbered(song(60 to 3))) // 3/16 = 1/8 + 1/16
        assertEquals("1 = C\n| 1 ._ |", numbered(song(60 to 6)))
        assertEquals("1 = C\n| 0 0 |", numbered(song(null to 8))) // perpanjangan rehat tetap 0
        assertEquals("", numbered(Composition()))
    }

    @Test
    fun sharpAndFlatAreMarkedInText() {
        assertEquals("1 = C\n| #4 |", numbered(song(66 to 4)))
        assertEquals("1 = F\n| b7 |", numbered(song(75 to 4), MusicKey.F))
    }

    @Test
    fun longNoteIsTiedAcrossBarlineInStaffAndContinuesWithDot() {
        val c = song(60 to 12, 64 to 8)
        val m = c.measures(4)
        assertEquals(2, m.size)
        assertEquals(listOf(NoteEvent(60, 12), NoteEvent(64, 4, tiedToNext = true)), m[0].events)
        assertEquals(listOf(NoteEvent(64, 4)), m[1].events)
        assertEquals("1 = C\n| 1 . . 3 | . |", numbered(c))
    }

    @Test
    fun symbolCrossingBarlineIsSplit() {
        val t = song(60 to 14, 62 to 4).tokens(4)
        assertEquals(2, t.size)
        val last = t[0].last()
        assertEquals(Token(62, 2, false, 14), last)
        assertEquals(Token(62, 2, true, 16), t[1][0])
    }

    @Test
    fun otherTimeSignatureAndKey() {
        val c = song(67 to 4, 67 to 4, 74 to 4, 74 to 4, 76 to 4, 76 to 4)
        assertEquals("1 = G\n| 1 1 5 | 5 6 6 |", numbered(c, MusicKey.G, beats = 3))
    }

    @Test
    fun deleteRemovesOnlyTheLastSymbol() {
        var c = song(60 to 4, 62 to 8, null to 4) // simbol: 1, 2, titik, 0
        c = c.removeLast() // rehat
        assertEquals("1 = C\n| 1 2 . |", numbered(c))
        c = c.removeLast() // hanya titik perpanjangan, nada 2 tetap ada
        assertEquals("1 = C\n| 1 2 |", numbered(c))
        c = c.removeLast()
        assertEquals("1 = C\n| 1 |", numbered(c))
        c = c.removeLast()
        assertEquals(true, c.isEmpty)
        assertEquals(true, c.removeLast().isEmpty) // kosong: tidak error
    }

    @Test
    fun deleteAfterExtendRemovesOnlyTheExtension() {
        val c = song(60 to 4).extend(2).extend(1)
        assertEquals("1 = C\n| 1 ._ .__ |", numbered(c))
        assertEquals("1 = C\n| 1 ._ |", numbered(c.removeLast()))
        assertEquals(listOf(NoteEvent(60, 4)), c.removeLast().removeLast().events)
    }

    @Test
    fun deleteDropsTiedBarSplitPiece() {
        var c = song(60 to 12, 64 to 8)
        assertEquals(2, c.measures(4).size)
        c = c.removeLast() // titik di birama kedua
        assertEquals(1, c.measures(4).size)
        assertEquals(listOf(NoteEvent(60, 12), NoteEvent(64, 4)), c.measures(4)[0].events)
    }

    @Test
    fun startPointsAndPartialPlayback() {
        val c = song(60 to 8, 62 to 4)
        assertEquals(0, c.eventStart(5))
        assertEquals(8, c.eventStart(8))
        assertEquals(listOf(NoteEvent(60, 2), NoteEvent(62, 4)), c.eventsFrom(6))
        assertEquals(listOf(NoteEvent(62, 4)), c.eventsFrom(8))
        assertEquals(c.events, c.eventsFrom(0))
    }

    @Test
    fun metronomeClicksLandOnBeats() {
        val silent = ShortArray(Synth.RATE * 2)
        val mixed = Synth.mixClicks(silent, 120, 4) // tik tiap 0,5 detik
        assertEquals(silent.size, mixed.size)
        fun energy(m: ShortArray, from: Double) = (0 until 600).sumOf { Math.abs(m[(from * Synth.RATE).toInt() + it].toInt()) }
        assertEquals(true, energy(mixed, 0.0) > 0)
        assertEquals(true, energy(mixed, 0.5) > 0)
        assertEquals(true, energy(mixed, 1.5) > 0)
        assertEquals(0, energy(mixed, 0.25)) // di antara ketukan sunyi
        // mulai dari satuan ke-2 (setengah ketuk): tik pertama jatuh 2 satuan (0,25 dtk) kemudian
        val shifted = Synth.mixClicks(silent, 120, 4, offsetUnits = 2)
        assertEquals(0, energy(shifted, 0.0))
        assertEquals(true, energy(shifted, 0.25) > 0)
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
        assertEquals(4 to 0, LiveTiming.settle(0.2, 0.67, unit, 2, legato = true))
    }

    @Test
    fun liveRewriteOfGrowingNote() {
        var c = Composition().add(60, 2)
        val start = 0
        c = c.truncate(start).add(60, 6).truncate(start).add(60, 20)
        assertEquals(5, c.symbols.size)
        val m = c.measures(4) // 20 > 16: satu birama penuh + 4, diikat
        assertEquals(listOf(NoteEvent(60, 16, tiedToNext = true)), m[0].events)
        assertEquals(listOf(NoteEvent(60, 4)), m[1].events)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDurationOutOfRange() {
        Composition().add(60, 0)
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
