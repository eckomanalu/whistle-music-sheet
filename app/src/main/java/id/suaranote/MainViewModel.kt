package id.suaranote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.suaranote.audio.AudioCapture
import id.suaranote.audio.AudioPlayer
import id.suaranote.core.AnalysisResult
import id.suaranote.core.AudioAnalyzer
import id.suaranote.core.Dsp
import id.suaranote.core.Measure
import id.suaranote.core.Synth
import id.suaranote.core.Transcriber
import id.suaranote.core.TranscriptionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PlayWhat { ORIGINAL, CLEAN, RESULT }

data class UiState(
    val recording: Boolean = false,
    val elapsedSec: Double = 0.0,
    val level: Float = 0f,
    val processing: Boolean = false,
    val settings: TranscriptionSettings = TranscriptionSettings(),
    val measures: List<Measure> = emptyList(),
    val hasRecording: Boolean = false,
    val playing: PlayWhat? = null,
    /** Selisih tala rata-rata (sen) yang terdeteksi, atau null bila tidak cukup konsisten. */
    val tuningCents: Int? = null,
    /** Kontur nada hasil analisis (nomor MIDI pecahan per frame, NaN = tanpa nada) dan lebar frame (detik). */
    val contour: FloatArray = FloatArray(0),
    val contourHopSec: Float = 0.0116f,
    val error: String? = null,
)

/**
 * Alur: rekam utuh (tanpa notasi) -> berhenti -> bersihkan derau -> analisis nada -> tulis notasi.
 * Pengaturan tempo/birama/nada dasar hanya menghitung ulang notasi; mengubah pembersih derau
 * mengulang analisis dari rekaman asli.
 */
class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pcm = ShortArray(AudioCapture.SAMPLE_RATE * 10)
    private var pcmLen = 0
    private var analysis: AnalysisResult? = null
    private var recordJob: Job? = null
    private var analyzeJob: Job? = null
    private var playJob: Job? = null

    fun start() {
        if (recordJob?.isActive == true) return
        stopPlayback()
        analyzeJob?.cancel()
        pcmLen = 0
        analysis = null
        _state.update {
            it.copy(recording = true, elapsedSec = 0.0, level = 0f, measures = emptyList(),
                hasRecording = false, processing = false, tuningCents = null, error = null)
        }
        recordJob = viewModelScope.launch {
            try {
                AudioCapture.chunks().collect { c ->
                    append(c)
                    var peak = 0
                    for (v in c) peak = maxOf(peak, abs(v.toInt()))
                    val sec = pcmLen.toDouble() / AudioCapture.SAMPLE_RATE
                    _state.update { it.copy(level = peak / 32768f, elapsedSec = sec) }
                    if (sec >= MAX_SEC) throw StopRecording()
                }
            } catch (_: StopRecording) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Terjadi kesalahan saat merekam") }
            } finally {
                withContext(NonCancellable) { _state.update { it.copy(recording = false, level = 0f) } }
                if (pcmLen > AudioCapture.SAMPLE_RATE / 2) analyze()
            }
        }
    }

    private class StopRecording : RuntimeException()

    private fun append(c: ShortArray) {
        if (pcmLen + c.size > pcm.size) pcm = pcm.copyOf(pcm.size * 2)
        System.arraycopy(c, 0, pcm, pcmLen, c.size)
        pcmLen += c.size
    }

    fun stop() {
        recordJob?.cancel()
        stopPlayback()
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun updateSettings(change: (TranscriptionSettings) -> TranscriptionSettings) {
        val old = _state.value.settings
        val new = change(old)
        _state.update { it.copy(settings = new) }
        if (_state.value.recording || pcmLen == 0) return
        if (new.denoise != old.denoise) analyze() else transcribeOnly()
    }

    private fun analyze() {
        analyzeJob?.cancel()
        val snapshot = pcm.copyOf(pcmLen)
        val settings = _state.value.settings
        _state.update { it.copy(processing = true, hasRecording = true, error = null) }
        analyzeJob = viewModelScope.launch {
            try {
                val (res, measures) = withContext(Dispatchers.Default) {
                    val r = AudioAnalyzer.analyze(snapshot, AudioCapture.SAMPLE_RATE, settings.denoise)
                    r to Transcriber.transcribe(r.frames, settings, r.effectiveOffset(settings.autoTune))
                }
                analysis = res
                val off = res.effectiveOffset(settings.autoTune)
                val contour = FloatArray(res.frames.size) { i ->
                    res.frames[i].freqHz?.let { (id.suaranote.core.Pitch.freqToMidi(it) - off).toFloat() } ?: Float.NaN
                }
                _state.update { it.copy(measures = measures, processing = false, tuningCents = cents(res), contour = contour) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(processing = false, error = "Analisis gagal: ${e.message}") }
            }
        }
    }

    private fun transcribeOnly() {
        val res = analysis ?: return
        analyzeJob?.cancel()
        val settings = _state.value.settings
        analyzeJob = viewModelScope.launch {
            val measures = withContext(Dispatchers.Default) {
                Transcriber.transcribe(res.frames, settings, res.effectiveOffset(settings.autoTune))
            }
            val off = res.effectiveOffset(settings.autoTune)
            val contour = FloatArray(res.frames.size) { i ->
                res.frames[i].freqHz?.let { (id.suaranote.core.Pitch.freqToMidi(it) - off).toFloat() } ?: Float.NaN
            }
            _state.update { it.copy(measures = measures, contour = contour) }
        }
    }

    private fun cents(r: AnalysisResult): Int? =
        if (r.tuningStrength >= 0.4) (r.tuningOffset * 100).roundToInt() else null

    fun togglePlay(what: PlayWhat) {
        if (_state.value.playing == what) { stopPlayback(); return }
        stopPlayback()
        if (_state.value.recording || pcmLen == 0) return
        val s = _state.value
        playJob = viewModelScope.launch {
            _state.update { it.copy(playing = what) }
            try {
                when (what) {
                    PlayWhat.ORIGINAL -> AudioPlayer.play(pcm.copyOf(pcmLen), AudioCapture.SAMPLE_RATE)
                    PlayWhat.CLEAN -> analysis?.let {
                        AudioPlayer.play(withContext(Dispatchers.Default) { Dsp.toShort(it.clean) }, AudioAnalyzer.ANALYSIS_RATE)
                    }
                    PlayWhat.RESULT -> {
                        val off = analysis?.effectiveOffset(s.settings.autoTune) ?: 0.0
                        val data = withContext(Dispatchers.Default) { Synth.render(s.measures, s.settings.bpm, off) }
                        AudioPlayer.play(data, Synth.RATE)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Gagal memutar: ${e.message}") }
            } finally {
                withContext(NonCancellable) { _state.update { if (it.playing == what) it.copy(playing = null) else it } }
            }
        }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
    }

    override fun onCleared() {
        recordJob?.cancel()
        playJob?.cancel()
    }

    private companion object {
        const val MAX_SEC = 180.0
    }
}
