package id.suaranote.core

import kotlin.math.PI
import kotlin.math.sin

/** Nada sintetis untuk pratinjau tuts dan pemutaran lagu. */
object Synth {
    const val RATE = 22050

    /** Satu nada tunggal, untuk bunyi saat tuts ditekan. */
    fun note(midi: Int, seconds: Double): ShortArray =
        render(listOf(Measure(listOf(NoteEvent(midi, 1)))), (60.0 / (seconds * 4)).toInt().coerceAtLeast(1))

    fun render(measures: List<Measure>, bpm: Int, detuneSemitones: Double = 0.0): ShortArray {
        class Item(val midi: Int?, var dur: Int)
        val items = ArrayList<Item>()
        var extend = false
        for (m in measures) for (e in m.events) {
            if (extend && items.isNotEmpty()) items.last().dur += e.dur16 else items += Item(e.midi, e.dur16)
            extend = e.tiedToNext && e.midi != null
        }

        val sixteenth = 60.0 / bpm / 4.0
        val out = ArrayList<Short>()
        var phase = 0.0
        for (it in items) {
            val n = Math.round(it.dur * sixteenth * RATE).toInt()
            if (it.midi == null) {
                repeat(n) { out += 0 }
                continue
            }
            val f = 440.0 * Math.pow(2.0, (it.midi + detuneSemitones - 69.0) / 12.0)
            val attack = (0.008 * RATE).toInt().coerceAtLeast(1)
            val release = minOf((0.04 * RATE).toInt(), n / 2).coerceAtLeast(1)
            for (i in 0 until n) {
                phase += 2 * PI * f / RATE
                val tone = sin(phase) + 0.3 * sin(2 * phase) + 0.1 * sin(3 * phase)
                val env = minOf(1.0, i / attack.toDouble(), (n - i) / release.toDouble())
                out += (tone * env * 0.3 * 32767 / 1.4).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
