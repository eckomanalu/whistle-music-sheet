package id.suaranote.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import id.suaranote.core.Synth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Metronom: tik tiap ketuk sampai coroutine dibatalkan. Tempo dibaca ulang tiap ketuk, jadi bisa diubah saat berbunyi. */
object Metronome {
    suspend fun run(bpm: () -> Int, beatsPerBar: () -> Int) = withContext(Dispatchers.IO) {
        val minBuf = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                    .setSampleRate(Synth.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            var beat = 0
            while (currentCoroutineContext().isActive) {
                val n = (Synth.RATE * 60.0 / bpm().coerceAtLeast(1)).toInt()
                val buf = ShortArray(n)
                val click = Synth.click(beat % beatsPerBar().coerceAtLeast(1) == 0)
                System.arraycopy(click, 0, buf, 0, minOf(click.size, n))
                var pos = 0
                while (pos < n && currentCoroutineContext().isActive) {
                    val w = track.write(buf, pos, minOf(1024, n - pos))
                    if (w < 0) return@withContext
                    pos += w
                }
                beat++
            }
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
