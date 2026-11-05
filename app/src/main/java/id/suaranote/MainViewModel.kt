package id.suaranote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.suaranote.audio.PitchRecorder
import id.suaranote.core.Measure
import id.suaranote.core.Numbered
import id.suaranote.core.Pitch
import id.suaranote.core.PitchFrame
import id.suaranote.core.Spelling
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
import kotlin.math.roundToInt

/** Nada yang sedang terdengar: nama (A4), not angka (6), dan selisih dalam sen. */
data class LiveNote(val name: String, val numbered: String, val cents: Int)

data class UiState(
    val recording: Boolean = false,
    val elapsedSec: Double = 0.0,
    val live: LiveNote? = null,
    val settings: TranscriptionSettings = TranscriptionSettings(),
    val measures: List<Measure> = emptyList(),
    val hasRecording: Boolean = false,
    val error: String? = null,
)

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Hanya disentuh dari thread utama (koleksi flow berjalan di viewModelScope / Main).
    private val frames = ArrayList<PitchFrame>()
    private var recordJob: Job? = null
    private var transcribeJob: Job? = null

    fun start() {
        if (recordJob?.isActive == true) return
        frames.clear()
        _state.update {
            it.copy(recording = true, elapsedSec = 0.0, live = null, measures = emptyList(), hasRecording = false, error = null)
        }
        recordJob = viewModelScope.launch {
            try {
                PitchRecorder.pitchFrames().collect { f ->
                    frames += f
                    val live = f.freqHz?.let { toLive(it) }
                    _state.update { it.copy(live = live, elapsedSec = frames.size * HOP_SEC) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Terjadi kesalahan saat merekam") }
            } finally {
                withContext(NonCancellable) { finishRecording() }
            }
        }
    }

    fun stop() {
        recordJob?.cancel()
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun updateSettings(change: (TranscriptionSettings) -> TranscriptionSettings) {
        _state.update { it.copy(settings = change(it.settings)) }
        if (!_state.value.recording && frames.isNotEmpty()) transcribe()
    }

    private suspend fun finishRecording() {
        _state.update { it.copy(recording = false, live = null) }
        if (frames.isEmpty()) return
        val snapshot = frames.toList()
        val settings = _state.value.settings
        val measures = withContext(Dispatchers.Default) { Transcriber.transcribe(snapshot, settings) }
        _state.update { it.copy(measures = measures, hasRecording = true) }
    }

    private fun transcribe() {
        transcribeJob?.cancel()
        val snapshot = frames.toList()
        val settings = _state.value.settings
        transcribeJob = viewModelScope.launch {
            val measures = withContext(Dispatchers.Default) { Transcriber.transcribe(snapshot, settings) }
            _state.update { it.copy(measures = measures, hasRecording = true) }
        }
    }

    private fun toLive(freq: Double): LiveNote {
        val key = _state.value.settings.key
        val exact = Pitch.freqToMidi(freq)
        val midi = exact.roundToInt()
        val n = Numbered.of(midi, key, _state.value.settings.octaveOffset)
        val acc = if (n.accidental > 0) "#" else if (n.accidental < 0) "b" else ""
        return LiveNote(
            name = Spelling.noteName(midi, key),
            numbered = acc + n.degree,
            cents = ((exact - midi) * 100).roundToInt(),
        )
    }

    override fun onCleared() {
        recordJob?.cancel()
    }

    private companion object {
        const val HOP_SEC = 1024.0 / PitchRecorder.SAMPLE_RATE
    }
}
