package id.suaranote.core

/** Satu frame hasil analisis nada. [freqHz] null berarti tidak ada nada (diam / tidak jelas). */
data class PitchFrame(
    val timeSec: Double,
    val freqHz: Double?,
    val rms: Double,
)

/**
 * Satu not atau rehat dalam birama.
 * [midi] null = rehat. [dur16] dalam satuan 1/16 not (1 ketuk = 4).
 * [tiedToNext] true bila not ini disambung (ikatan/tie) ke not berikutnya.
 */
data class NoteEvent(
    val midi: Int?,
    val dur16: Int,
    val tiedToNext: Boolean = false,
)

data class Measure(val events: List<NoteEvent>)

data class TranscriptionSettings(
    val key: MusicKey = MusicKey.C,
    val bpm: Int = 80,
    val beatsPerBar: Int = 4,
    /** Geser oktaf tampilan not angka (tidak memengaruhi not balok). */
    val octaveOffset: Int = 0,
)
