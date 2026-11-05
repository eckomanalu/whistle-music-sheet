package id.suaranote.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Memutar PCM 16-bit mono. Pembatalan coroutine menghentikan suara seketika. */
object AudioPlayer {
    suspend fun play(pcm: ShortArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var pos = 0
            while (pos < pcm.size && currentCoroutineContext().isActive) {
                val n = track.write(pcm, pos, minOf(4096, pcm.size - pos))
                if (n < 0) break
                pos += n
            }
            // tunggu sisa buffer habis terputar
            val endFrames = pcm.size
            while (currentCoroutineContext().isActive && track.playbackHeadPosition < endFrames) {
                kotlinx.coroutines.delay(30)
            }
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
