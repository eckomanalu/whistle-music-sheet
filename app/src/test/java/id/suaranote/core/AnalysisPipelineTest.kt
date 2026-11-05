package id.suaranote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Uji jalur lengkap rekaman -> pembersih -> pYIN -> notasi, dengan siulan/dengungan sintetis berderau. */
class AnalysisPipelineTest {

    private val sr = 44100
    private val beat = 60.0 / 90 // bpm 90

    private class Tone(val freq: Double, val sec: Double)

    private fun midiHz(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    /**
     * @param whistle true = hampir sinus murni (siulan); false = kaya harmonik (dengungan)
     * @param cents pergeseran tala konsisten
     */
    private fun record(
        tones: List<Tone>,
        whistle: Boolean,
        noise: Double = 0.02,
        mains: Double = 0.04,
        cents: Double = 0.0,
        seed: Long = 11,
    ): ShortArray {
        val lead = (0.4 * sr).toInt()
        val total = lead + tones.sumOf { (it.sec * sr).toInt() } + (0.4 * sr).toInt()
        val x = DoubleArray(total)
        var pos = lead
        var phase = 0.0
        val shift = 2.0.pow(cents / 1200.0)
        for (t in tones) {
            val len = (t.sec * sr).toInt()
            if (t.freq > 0) {
                for (i in 0 until len) {
                    val time = (pos + i).toDouble() / sr
                    val f = t.freq * shift * (1 + 0.008 * sin(2 * PI * 5.5 * time)) // vibrato +-14 sen
                    phase += 2 * PI * f / sr
                    val s = if (whistle) sin(phase) + 0.03 * sin(2 * phase)
                    else (1..5).sumOf { h -> sin(h * phase) / h } / 1.8
                    val attack = minOf(1.0, i / (0.03 * sr))
                    val release = minOf(1.0, (len - i) / (0.04 * sr))
                    x[pos + i] = 0.3 * s * attack * release
                }
            }
            pos += len
        }
        val rnd = Random(seed)
        for (i in x.indices) {
            val time = i.toDouble() / sr
            x[i] += (rnd.nextDouble() - 0.5) * 2 * noise +
                mains * sin(2 * PI * 50 * time) + 0.5 * mains * sin(2 * PI * 100 * time)
        }
        return ShortArray(total) { (x[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
    }

    private fun transcribe(
        pcm: ShortArray,
        denoise: Boolean = true,
        autoTune: Boolean = true,
        key: MusicKey = MusicKey.C,
    ): String {
        val res = AudioAnalyzer.analyze(pcm, sr, denoise)
        val settings = TranscriptionSettings(key = key, bpm = 90)
        val m = Transcriber.transcribe(res.frames, settings, res.effectiveOffset(autoTune))
        return NotationText.numbered(m, key)
    }

    private fun melody(midis: IntArray, lastBeats: Double = 2.0): List<Tone> {
        val gap = 0.04
        val out = ArrayList<Tone>()
        for ((i, m) in midis.withIndex()) {
            val beats = if (i == midis.lastIndex) lastBeats else 1.0
            out += Tone(midiHz(m), beats * beat - gap)
            out += Tone(0.0, gap)
        }
        return out
    }

    // C5 C5 G5 G5 | A5 A5 G5(2)
    private val whistleTune = intArrayOf(72, 72, 79, 79, 81, 81, 79)
    private val whistleExpected = "1 = C\n| 1' 1' 5' 5' | 6' 6' 5' - |"

    @Test
    fun whistleInNoiseAndMainsHum() {
        val pcm = record(melody(whistleTune), whistle = true)
        assertEquals(whistleExpected, transcribe(pcm))
    }

    @Test
    fun whistleWithoutDenoiseStillWorks() {
        val pcm = record(melody(whistleTune), whistle = true, noise = 0.005, mains = 0.0)
        assertEquals(whistleExpected, transcribe(pcm, denoise = false))
    }

    @Test
    fun lowHumming() {
        // C3 C3 G3 G3 | A3 A3 G3(2)
        val pcm = record(melody(intArrayOf(48, 48, 55, 55, 57, 57, 55)), whistle = false)
        assertEquals("1 = C\n| 1, 1, 5, 5, | 6, 6, 5, - |", transcribe(pcm))
    }

    @Test
    fun highWhistleAbove1100Hz() {
        // C6 E6 G6 C7 : sebelumnya di luar rentang deteksi
        val pcm = record(melody(intArrayOf(84, 88, 91, 96), lastBeats = 1.0), whistle = true)
        assertEquals("1 = C\n| 1'' 3'' 5'' 1''' |", transcribe(pcm))
    }

    @Test
    fun consistentlySharpSingingIsCalibrated() {
        val pcm = record(melody(whistleTune), whistle = true, cents = 40.0)
        val res = AudioAnalyzer.analyze(pcm, sr, true)
        assertTrue("offset ~ +0.4, dapat ${res.tuningOffset}", Math.abs(res.tuningOffset - 0.4) < 0.08)
        assertEquals(whistleExpected, transcribe(pcm, autoTune = true))
    }

    @Test
    fun silenceGivesNoNotes() {
        val pcm = record(emptyList<Tone>() + Tone(0.0, 2.0), whistle = true, mains = 0.0)
        assertEquals("", transcribe(pcm))
    }

    @Test
    fun synthRendersTiedNotesAsOneSustainedTone() {
        val m = listOf(Measure(listOf(NoteEvent(60, 12, tiedToNext = true), NoteEvent(60, 4), NoteEvent(null, 16))))
        val pcm = Synth.render(m, 120)
        // 12 + 4 + 16 = 32 enam-belas = 8 ketuk; pada 120 bpm = 4 detik
        assertEquals(Synth.RATE * 4, pcm.size)
    }
}
