package id.suaranote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Uji dengan suara sintetis: gelombang berharmonik + vibrato + derau. */
class TranscriberTest {

    private val sr = 44100

    /** freq <= 0 berarti diam. Setiap not punya envelope sendiri sehingga ada lembah di antara not. */
    private data class Tone(val freq: Double, val sec: Double)

    private fun synth(tones: List<Tone>, noise: Double = 0.004, seed: Long = 7): FloatArray {
        val total = tones.sumOf { (it.sec * sr).toInt() } + sr / 4
        val out = FloatArray(total)
        val rnd = Random(seed)
        var pos = 0
        var phase = 0.0
        for (t in tones) {
            val len = (t.sec * sr).toInt()
            if (t.freq > 0) {
                for (i in 0 until len) {
                    val time = (pos + i).toDouble() / sr
                    val f = t.freq * (1 + 0.012 * sin(2 * PI * 5.5 * time)) // vibrato +-20 sen
                    phase += 2 * PI * f / sr
                    var s = 0.0
                    for (h in 1..4) s += sin(h * phase) / h
                    val attack = minOf(1.0, i / (0.025 * sr))
                    val release = minOf(1.0, (len - i) / (0.040 * sr))
                    out[pos + i] = (0.25 * s * attack * release).toFloat()
                }
            }
            pos += len
        }
        for (i in out.indices) out[i] += ((rnd.nextDouble() - 0.5) * 2 * noise).toFloat()
        return out
    }

    private fun frames(signal: FloatArray): List<PitchFrame> {
        val det = PitchDetector(sr)
        val need = det.requiredSamples
        val hop = 1024
        val list = ArrayList<PitchFrame>()
        var end = need
        while (end <= signal.size) {
            val window = signal.copyOfRange(end - need, end)
            list += det.analyze(window, (end - need / 2.0) / sr)
            end += hop
        }
        return list
    }

    private fun transcribe(tones: List<Tone>, key: MusicKey = MusicKey.C, bpm: Int = 90, beats: Int = 4): String {
        val m = Transcriber.transcribe(frames(synth(tones)), TranscriptionSettings(key, bpm, beats))
        return NotationText.numbered(m, key)
    }

    private val c4 = 261.63
    private val d4 = 293.66
    private val e4 = 329.63
    private val f4 = 349.23
    private val g4 = 392.00
    private val a4 = 440.00
    private val beat = 60.0 / 90 // bpm 90

    @Test
    fun detectorFindsFrequencyWithinAFewCents() {
        val det = PitchDetector(sr)
        for (f in doubleArrayOf(110.0, 196.0, 261.63, 440.0, 880.0)) {
            val sig = synth(listOf(Tone(f, 0.5)), noise = 0.0)
            val buf = sig.copyOfRange(10000, 10000 + det.requiredSamples)
            val r = det.detect(buf)
            assertTrue("tidak terdeteksi pada $f", r != null)
            val cents = 1200 * Math.log(r!!.freqHz / f) / Math.log(2.0)
            assertTrue("$f Hz meleset $cents sen", Math.abs(cents) < 35) // vibrato +-20 sen
        }
    }

    @Test
    fun silenceIsNotPitch() {
        val det = PitchDetector(sr)
        val quiet = FloatArray(det.requiredSamples)
        assertEquals(null, det.analyze(quiet, 0.0).freqHz)
    }

    @Test
    fun twinkleTwinkle() {
        val gap = 0.03
        fun n(f: Double, beats: Double) = listOf(Tone(f, beats * beat - gap), Tone(0.0, gap))
        val tones = n(c4, 1.0) + n(c4, 1.0) + n(g4, 1.0) + n(g4, 1.0) +
            n(a4, 1.0) + n(a4, 1.0) + n(g4, 2.0)
        assertEquals("1 = C\n| 1 1 5 5 | 6 6 5 - |", transcribe(tones))
    }

    @Test
    fun repeatedNotesWithoutGapAreSplit() {
        // tanpa jeda: hanya envelope per not yang memisahkan
        val tones = listOf(Tone(c4, beat), Tone(c4, beat), Tone(g4, beat), Tone(g4, beat))
        assertEquals("1 = C\n| 1 1 5 5 |", transcribe(tones))
    }

    @Test
    fun otherKeyUsesRelativeDegrees() {
        val d5 = 587.33
        val e5 = 659.25
        val gap = 0.03
        fun n(f: Double, beats: Double) = listOf(Tone(f, beats * beat - gap), Tone(0.0, gap))
        val tones = n(g4, 1.0) + n(g4, 1.0) + n(d5, 1.0) + n(d5, 1.0) + n(e5, 1.0) + n(e5, 1.0) + n(d5, 2.0)
        assertEquals("1 = G\n| 1 1 5 5 | 6 6 5 - |", transcribe(tones, key = MusicKey.G))
    }

    @Test
    fun eighthNotesAndRests() {
        val gap = 0.02
        fun n(f: Double, beats: Double) = listOf(Tone(f, beats * beat - gap), Tone(0.0, gap))
        val tones = n(c4, 0.5) + n(d4, 0.5) + n(e4, 0.5) + n(f4, 0.5) + n(g4, 2.0) +
            n(a4, 1.0) + listOf(Tone(0.0, beat)) + n(e4, 1.0)
        assertEquals("1 = C\n| 1_ 2_ 3_ 4_ 5 - | 6 0 3 |", transcribe(tones))
    }

    @Test
    fun longNoteIsTiedAcrossBarline() {
        val gap = 0.03
        // tiga ketuk lalu not 2 ketuk -> melewati garis birama 4/4
        val tones = listOf(Tone(c4, 3 * beat - gap), Tone(0.0, gap), Tone(e4, 2 * beat - gap))
        val m = Transcriber.transcribe(frames(synth(tones)), TranscriptionSettings(MusicKey.C, 90, 4))
        assertEquals(2, m.size)
        assertEquals(listOf(NoteEvent(60, 12), NoteEvent(64, 4, tiedToNext = true)), m[0].events)
        assertEquals(listOf(NoteEvent(64, 4)), m[1].events)
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
