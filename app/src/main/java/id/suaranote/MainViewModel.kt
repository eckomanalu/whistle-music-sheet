package id.suaranote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.suaranote.audio.AudioPlayer
import id.suaranote.core.Composition
import id.suaranote.core.Measure
import id.suaranote.core.ScoreSettings
import id.suaranote.core.Synth
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

data class UiState(
    val composition: Composition = Composition(),
    val settings: ScoreSettings = ScoreSettings(),
    /** Panjang not yang dipakai tuts berikutnya, dalam 1/16 (4 = 1 ketuk). */
    val selectedDur16: Int = 4,
    val measures: List<Measure> = emptyList(),
    val playing: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = composition.events.isEmpty()
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var songJob: Job? = null
    private var previewJob: Job? = null

    private fun edit(change: (Composition) -> Composition) {
        stopSong()
        _state.update {
            val c = change(it.composition)
            it.copy(composition = c, measures = c.measures(it.settings.beatsPerBar), error = null)
        }
    }

    /** Tuts ditekan: tambahkan not dengan panjang terpilih di ujung lagu dan bunyikan. */
    fun pressKey(midi: Int) {
        val dur = _state.value.selectedDur16
        edit { it.add(midi, dur) }
        preview(midi, dur)
    }

    fun addRest() {
        val dur = _state.value.selectedDur16
        edit { it.add(null, dur) }
    }

    /** Selalu menghapus satu not/rehat paling ujung. */
    fun deleteLast() = edit { it.removeLast() }

    fun selectDuration(dur16: Int) {
        _state.update { it.copy(selectedDur16 = dur16) }
    }

    fun updateSettings(change: (ScoreSettings) -> ScoreSettings) {
        _state.update {
            val s = change(it.settings)
            it.copy(settings = s, measures = it.composition.measures(s.beatsPerBar))
        }
    }

    private fun preview(midi: Int, dur16: Int) {
        previewJob?.cancel()
        val bpm = _state.value.settings.bpm
        val seconds = (dur16 * 60.0 / bpm / 4).coerceIn(0.2, 1.2)
        previewJob = viewModelScope.launch {
            try {
                AudioPlayer.play(withContext(Dispatchers.Default) { Synth.note(midi, seconds) }, Synth.RATE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun togglePlay() {
        if (_state.value.playing) { stopSong(); return }
        val s = _state.value
        if (s.isEmpty) return
        previewJob?.cancel()
        songJob = viewModelScope.launch {
            _state.update { it.copy(playing = true) }
            try {
                val data = withContext(Dispatchers.Default) { Synth.render(s.measures, s.settings.bpm) }
                AudioPlayer.play(data, Synth.RATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Gagal memutar: ${e.message}") }
            } finally {
                withContext(NonCancellable) { _state.update { it.copy(playing = false) } }
            }
        }
    }

    fun stopSong() {
        songJob?.cancel()
        songJob = null
    }

    override fun onCleared() {
        songJob?.cancel()
        previewJob?.cancel()
    }
}
