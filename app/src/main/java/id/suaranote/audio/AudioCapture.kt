package id.suaranote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Merekam mikrofon mentah 44100 Hz mono dan memancarkan potongan PCM ~23 ms.
 * Sumber dicoba berurutan UNPROCESSED -> VOICE_RECOGNITION -> MIC supaya peredam derau/AGC bawaan
 * ponsel (yang suka merusak siulan) sedapat mungkin tidak aktif. Izin RECORD_AUDIO harus sudah ada.
 */
object AudioCapture {
    const val SAMPLE_RATE = 44100
    private const val CHUNK = 1024

    @SuppressLint("MissingPermission")
    fun chunks(): Flow<ShortArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, CHUNK * 2 * 8)
        var rec: AudioRecord? = null
        for (src in intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val r = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r?.release()
        }
        if (rec == null) {
            throw IllegalStateException("Mikrofon tidak bisa dibuka. Periksa izin atau aplikasi lain yang memakai mikrofon.")
        }
        try {
            rec.startRecording()
            while (currentCoroutineContext().isActive) {
                val chunk = ShortArray(CHUNK)
                var read = 0
                while (read < CHUNK) {
                    val n = rec.read(chunk, read, CHUNK - read)
                    if (n < 0) throw IllegalStateException("Gagal membaca mikrofon (kode $n)")
                    read += n
                }
                emit(chunk)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }.flowOn(Dispatchers.IO)
}
