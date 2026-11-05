package id.suaranote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import id.suaranote.core.PitchDetector
import id.suaranote.core.PitchFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Merekam dari mikrofon dan memancarkan satu [PitchFrame] setiap ~23 ms.
 * Rekaman berhenti (dan mikrofon dilepas) saat koleksi flow dibatalkan.
 * Izin RECORD_AUDIO harus sudah diberikan sebelum flow dikoleksi.
 */
object PitchRecorder {
    const val SAMPLE_RATE = 44100
    private const val HOP = 1024

    @SuppressLint("MissingPermission")
    fun pitchFrames(): Flow<PitchFrame> = flow {
        val detector = PitchDetector(SAMPLE_RATE)
        val need = detector.requiredSamples

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, HOP * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Mikrofon tidak bisa dibuka. Periksa izin atau aplikasi lain yang memakai mikrofon.")
        }

        try {
            rec.startRecording()
            val window = FloatArray(need)
            val chunk = ShortArray(HOP)
            var total = 0L
            while (currentCoroutineContext().isActive) {
                var read = 0
                while (read < HOP) {
                    val n = rec.read(chunk, read, HOP - read)
                    if (n < 0) throw IllegalStateException("Gagal membaca mikrofon (kode $n)")
                    read += n
                }
                System.arraycopy(window, HOP, window, 0, need - HOP)
                for (i in 0 until HOP) window[need - HOP + i] = chunk[i] / 32768f
                total += HOP
                if (total < need) continue
                val centerSec = (total - need / 2.0) / SAMPLE_RATE
                emit(detector.analyze(window, centerSec))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)
}
