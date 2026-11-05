package id.suaranote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.suaranote.audio.AudioPlayer
import id.suaranote.audio.Metronome
import id.suaranote.core.Composition
import id.suaranote.core.LiveTiming
import id.suaranote.core.Measure
import id.suaranote.core.ScoreSettings
import id.suaranote.core.Synth
import id.suaranote.core.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    /** Not balok per birama dan simbol not angka per birama (turunan dari [composition]). */
    val measures: List<Measure> = emptyList(),
    val tokens: List<List<Token>> = emptyList(),
    val playing: Boolean = false,
    /** Satuan waktu (1/16 sejak awal lagu) yang sedang berbunyi saat pemutaran; null bila tidak memutar. */
    val playUnit: Int? = null,
    /** Awal pemutaran yang dipilih (satuan 1/16); [startIsBar] true = dipilih dari birama, bukan dari not. */
    val startUnit: Int? = null,
    val startIsBar: Boolean = false,
    val metronomeOn: Boolean = false,
    /** Mode live: panjang not ditentukan lama tuts ditahan. */
    val liveMode: Boolean = false,
    /** Kelipatan pembulatan mode live dalam 1/16 (1, 2 = 1/8, atau 4 = 1/4). */
    val liveGrid: Int = 2,
    /** Mode live: celah antar-tuts tidak dijadikan rehat; not bersambung ke tuts berikutnya. */
    val liveLegato: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = composition.isEmpty
}

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var songJob: Job? = null
    private var previewJob: Job? = null
    private var metronomeJob: Job? = null

    private class Live(val midi: Int, val pressNs: Long, val startSymbol: Int, var dur: Int, var releaseNs: Long? = null)

    private var live: Live? = null
    private var tickJob: Job? = null
    private var sustainJob: Job? = null

    private fun withComposition(s: UiState, c: Composition, settings: ScoreSettings = s.settings): UiState {
        val total = c.totalUnits
        val keep = s.startUnit?.takeIf { it < total }
        return s.copy(
            composition = c,
            settings = settings,
            measures = c.measures(settings.beatsPerBar),
            tokens = c.tokens(settings.beatsPerBar),
            startUnit = keep,
            startIsBar = if (keep == null) false else s.startIsBar,
            error = null,
        )
    }

    private fun setComposition(c: Composition) {
        _state.update { withComposition(it, c) }
    }

    private fun endLive() {
        live = null
        tickJob?.cancel()
        sustainJob?.cancel()
    }

    private fun edit(change: (Composition) -> Composition) {
        stopSong()
        endLive()
        _state.update { withComposition(it, change(it.composition)) }
    }

    // ---------------------------------------------------------------- tuts

    fun setLiveMode(on: Boolean) {
        endLive()
        _state.update { it.copy(liveMode = on) }
    }

    fun setLegato(on: Boolean) {
        _state.update { it.copy(liveLegato = on) }
    }

    fun setLiveGrid(grid: Int) {
        _state.update { it.copy(liveGrid = grid) }
    }

    /** Tuts disentuh. Mode biasa: tambah not berpanjang terpilih. Mode live: not mulai dan memanjang selama ditahan. */
    fun keyDown(midi: Int) {
        if (_state.value.liveMode) liveDown(midi) else pressKey(midi)
    }

    fun keyUp(midi: Int) {
        if (_state.value.liveMode) liveUp(midi)
    }

    private fun liveDown(midi: Int) {
        val now = System.nanoTime()
        val s = _state.value
        val unit = 60.0 / s.settings.bpm / 4
        val grid = s.liveGrid
        stopSong()
        previewJob?.cancel()
        sustainJob?.cancel()
        tickJob?.cancel()
        var c = s.composition
        val prev = live
        if (prev != null) {
            val release = prev.releaseNs ?: now
            val (note, rest) = LiveTiming.settle(
                (release - prev.pressNs) / 1e9, (now - prev.pressNs) / 1e9, unit, grid, s.liveLegato,
            )
            c = c.truncate(prev.startSymbol).add(prev.midi, note)
            if (rest > 0) c = c.add(null, rest)
        }
        val start = c.symbols.size
        c = c.add(midi, grid)
        val l = Live(midi, now, start, grid)
        live = l
        setComposition(c)
        sustainJob = viewModelScope.launch {
            try {
                AudioPlayer.play(withContext(Dispatchers.Default) { Synth.note(midi, 3.0) }, Synth.RATE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        tickJob = viewModelScope.launch {
            while (true) {
                delay(40)
                if (live !== l || l.releaseNs != null) break
                val u = LiveTiming.quantize((System.nanoTime() - l.pressNs) / 1e9, unit, grid)
                if (u != l.dur) {
                    l.dur = u
                    setComposition(_state.value.composition.truncate(l.startSymbol).add(l.midi, u))
                }
            }
        }
    }

    private fun liveUp(midi: Int) {
        val l = live ?: return
        if (l.midi != midi || l.releaseNs != null) return
        val now = System.nanoTime()
        l.releaseNs = now
        tickJob?.cancel()
        sustainJob?.cancel()
        val s = _state.value
        val unit = 60.0 / s.settings.bpm / 4
        l.dur = LiveTiming.quantize((now - l.pressNs) / 1e9, unit, s.liveGrid)
        setComposition(s.composition.truncate(l.startSymbol).add(l.midi, l.dur))
    }

    /** Mode biasa: tambahkan not dengan panjang terpilih di ujung lagu dan bunyikan. */
    private fun pressKey(midi: Int) {
        val dur = _state.value.selectedDur16
        edit { it.add(midi, dur) }
        preview(midi, dur)
    }

    fun addRest() {
        val s = _state.value
        edit { it.add(null, if (s.liveMode) s.liveGrid else s.selectedDur16) }
    }

    /** Selalu menghapus satu simbol paling akhir: kepala not/rehat atau satu perpanjangan (titik). */
    fun deleteLast() = edit { it.removeLast() }

    /** Sambung: tambah perpanjangan (titik) sebesar panjang terpilih (mode live: kotak pembulatan). */
    fun extendLast() {
        val s = _state.value
        edit { it.extend(if (s.liveMode) s.liveGrid else s.selectedDur16) }
    }

    fun selectDuration(dur16: Int) {
        _state.update { it.copy(selectedDur16 = dur16) }
    }

    fun updateSettings(change: (ScoreSettings) -> ScoreSettings) {
        _state.update { withComposition(it, it.composition, change(it.settings)) }
    }

    fun changeTempo(delta: Int) = updateSettings { it.copy(bpm = (it.bpm + delta).coerceIn(40, 200)) }

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

    // ------------------------------------------------------------- metronom

    fun toggleMetronome() {
        _state.update { it.copy(metronomeOn = !it.metronomeOn) }
        syncMetronome()
    }

    /** Metronom berbunyi sendiri hanya saat aktif dan lagu tidak sedang diputar (lagu memuat tiknya sendiri). */
    private fun syncMetronome() {
        metronomeJob?.cancel()
        metronomeJob = null
        val s = _state.value
        if (!s.metronomeOn || s.playing) return
        metronomeJob = viewModelScope.launch {
            try {
                Metronome.run({ _state.value.settings.bpm }, { _state.value.settings.beatsPerBar })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(metronomeOn = false, error = "Metronom gagal: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------ pemutaran

    /**
     * Pilih awal pemutaran: [unit] = satuan 1/16 yang disentuh. Menyentuh not memilih not itu (awal not utuh);
     * menyentuh ruang kosong birama memilih awal birama. Menyentuh pilihan yang sama membatalkannya.
     * Bila lagu sedang diputar, pemutaran langsung berpindah ke titik itu.
     */
    fun selectStart(unit: Int, bar: Boolean) {
        val s = _state.value
        val u = if (bar) unit else s.composition.eventStart(unit)
        val same = s.startUnit == u && s.startIsBar == bar
        _state.update { it.copy(startUnit = if (same) null else u, startIsBar = if (same) false else bar) }
        if (s.playing) {
            stopSong()
            _state.update { it.copy(playing = false) }
            togglePlay()
        }
    }

    fun togglePlay() {
        if (_state.value.playing) { stopSong(); return }
        val s = _state.value
        if (s.isEmpty) return
        previewJob?.cancel()
        endLive()
        val start = (s.startUnit ?: 0).coerceIn(0, maxOf(0, s.composition.totalUnits - 1))
        val sixteenthSec = 60.0 / s.settings.bpm / 4
        _state.update { it.copy(playing = true, playUnit = start) }
        syncMetronome()
        songJob = viewModelScope.launch {
            val me = kotlin.coroutines.coroutineContext[Job]
            try {
                val data = withContext(Dispatchers.Default) {
                    val pcm = Synth.render(listOf(Measure(s.composition.eventsFrom(start))), s.settings.bpm)
                    if (s.metronomeOn) Synth.mixClicks(pcm, s.settings.bpm, s.settings.beatsPerBar, start) else pcm
                }
                AudioPlayer.play(data, Synth.RATE) { frames ->
                    val u = start + (frames.toDouble() / Synth.RATE / sixteenthSec).toInt()
                    _state.update { if (it.playUnit != u) it.copy(playUnit = u) else it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Gagal memutar: ${e.message}") }
            } finally {
                withContext(NonCancellable) {
                    // jika pemutaran baru sudah menggantikan job ini, jangan menimpa statusnya
                    if (songJob == null || songJob === me) {
                        _state.update { it.copy(playing = false, playUnit = null) }
                        syncMetronome()
                    }
                }
            }
        }
    }

    fun stopSong() {
        songJob?.cancel()
        songJob = null
    }

    /** Dipanggil saat aplikasi ke latar belakang: hentikan semua bunyi. */
    fun silence() {
        stopSong()
        endLive()
        if (_state.value.metronomeOn) { _state.update { it.copy(metronomeOn = false) }; syncMetronome() }
    }

    override fun onCleared() {
        songJob?.cancel()
        previewJob?.cancel()
        metronomeJob?.cancel()
        sustainJob?.cancel()
        tickJob?.cancel()
    }
}
