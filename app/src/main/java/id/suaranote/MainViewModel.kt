package id.suaranote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.suaranote.audio.AudioPlayer
import id.suaranote.audio.Metronome
import id.suaranote.core.Composition
import id.suaranote.core.LiveTiming
import id.suaranote.core.Measure
import id.suaranote.core.Score
import id.suaranote.core.ScoreSettings
import id.suaranote.core.Symbol
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

const val MAX_VOICES = 4

data class UiState(
    val score: Score = Score(),
    /** Suara (lapisan) yang sedang ditulisi. */
    val activeVoice: Int = 0,
    val settings: ScoreSettings = ScoreSettings(),
    /** Panjang not yang dipakai tuts berikutnya, dalam 1/16 (4 = 1 ketuk). */
    val selectedDur16: Int = 4,
    /** Turunan dari [score]: not balok dan simbol not angka, per suara lalu per birama. */
    val measures: List<List<Measure>> = listOf(emptyList()),
    val tokens: List<List<List<Token>>> = listOf(emptyList()),
    val playing: Boolean = false,
    /** Satuan waktu (1/16 sejak awal lagu) yang sedang berbunyi saat pemutaran; null bila tidak memutar. */
    val playUnit: Int? = null,
    /**
     * Titik terpilih (satuan 1/16): awal pemutaran sekaligus titik sisip tempat not yang diketik masuk.
     * Null = pemutaran dari awal dan not ditambah di ujung suara. [startIsBar] true = dipilih dari birama.
     */
    val startUnit: Int? = null,
    val startIsBar: Boolean = false,
    val blockMode: Boolean = false,
    /** Blok birama terpilih (indeks dari 0, inklusif) dan titik awal blok yang menunggu ujungnya. */
    val blockFrom: Int? = null,
    val blockTo: Int? = null,
    val blockAnchor: Int? = null,
    /** Isi papan klip (simbol sepanjang [clipboardBars] birama). */
    val clipboard: List<Symbol>? = null,
    val clipboardBars: Int = 0,
    val metronomeOn: Boolean = false,
    /** Mode live: panjang not ditentukan lama tuts ditahan. */
    val liveMode: Boolean = false,
    /** Kelipatan pembulatan mode live dalam 1/16 (1, 2 = 1/8, atau 4 = 1/4). */
    val liveGrid: Int = 2,
    /** Mode live: celah antar-tuts tidak dijadikan rehat; not bersambung ke tuts berikutnya. */
    val liveLegato: Boolean = false,
    val error: String? = null,
) {
    val composition: Composition get() = score.voices[activeVoice]
    val isEmpty: Boolean get() = score.totalUnits == 0
    val barLen: Int get() = settings.beatsPerBar * 4
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

    private fun withScore(s: UiState, score: Score, settings: ScoreSettings = s.settings): UiState {
        val bpb = settings.beatsPerBar
        return s.copy(
            score = score,
            settings = settings,
            activeVoice = s.activeVoice.coerceIn(0, score.voices.size - 1),
            measures = score.measures(bpb),
            tokens = score.tokens(bpb),
            error = null,
        )
    }

    /** Menulis ulang suara aktif (dipakai mode live yang selalu menambah di ujung). */
    private fun setComposition(c: Composition) {
        _state.update { withScore(it, it.score.withVoice(it.activeVoice, c)) }
    }

    private fun endLive() {
        live = null
        tickJob?.cancel()
        sustainJob?.cancel()
    }

    /**
     * Penyuntingan suara aktif. [change] menerima isi suara dan titik terpilih (null = ujung) dan mengembalikan
     * isi baru beserta titik terpilih yang baru.
     */
    private fun editActive(change: (Composition, Int?) -> Pair<Composition, Int?>) {
        stopSong()
        endLive()
        _state.update { s ->
            val (c, cursor) = change(s.composition, s.startUnit)
            val moved = s.copy(startUnit = cursor, startIsBar = if (cursor == null) false else s.startIsBar)
            withScore(moved, s.score.withVoice(s.activeVoice, c))
        }
    }

    // ---------------------------------------------------------------- suara

    fun selectVoice(index: Int) {
        _state.update { if (index in it.score.voices.indices) it.copy(activeVoice = index) else it }
    }

    fun addVoice() {
        stopSong()
        _state.update {
            if (it.score.voices.size >= MAX_VOICES) it
            else withScore(it.copy(activeVoice = it.score.voices.size), Score(it.score.voices + Composition()))
        }
    }

    /** Menghapus suara aktif beserta isinya (minimal tersisa satu suara). */
    fun removeVoice() {
        stopSong()
        endLive()
        _state.update {
            if (it.score.voices.size <= 1) it
            else withScore(
                it.copy(activeVoice = maxOf(0, it.activeVoice - 1)),
                Score(it.score.voices.filterIndexed { i, _ -> i != it.activeVoice }),
            )
        }
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

    /** Mode biasa: tambah not di titik terpilih (disisip, isi sesudahnya bergeser) atau di ujung suara. */
    private fun pressKey(midi: Int) {
        val dur = _state.value.selectedDur16
        editActive { c, cur ->
            if (cur == null) c.add(midi, dur) to null else c.insertNote(cur, midi, dur) to cur + dur
        }
        preview(midi, dur)
    }

    fun addRest() {
        val s = _state.value
        val dur = if (s.liveMode) s.liveGrid else s.selectedDur16
        editActive { c, cur ->
            if (cur == null) c.add(null, dur) to null else c.insertNote(cur, null, dur) to cur + dur
        }
    }

    /**
     * Hapus: tanpa titik terpilih menghapus satu simbol paling akhir suara aktif (kepala not, rehat, atau satu
     * titik perpanjangan). Dengan titik terpilih menghapus satu simbol tepat sebelum titik itu (seperti backspace).
     */
    fun deleteLast() {
        editActive { c, cur ->
            if (cur == null) {
                c.removeLast() to null
            } else {
                val before = c.splitAt(cur).symbolBefore(cur)
                if (before == null) c to cur else c.removeBefore(cur) to cur - before.dur16
            }
        }
    }

    /** Sambung: tambah perpanjangan (titik) sebesar panjang terpilih (mode live: kotak pembulatan). */
    fun extendLast() {
        val s = _state.value
        val by = if (s.liveMode) s.liveGrid else s.selectedDur16
        editActive { c, cur ->
            if (cur == null) c.extend(by) to null else c.insertExtension(cur, by) to cur + by
        }
    }

    fun selectDuration(dur16: Int) {
        _state.update { it.copy(selectedDur16 = dur16) }
    }

    fun updateSettings(change: (ScoreSettings) -> ScoreSettings) {
        _state.update { withScore(it, it.score, change(it.settings)) }
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

    // -------------------------------------------------- pilihan titik dan blok

    /** Sentuhan pada notasi: memilih titik (mode biasa) atau birama untuk blok (mode blok). */
    fun tap(unit: Int, bar: Boolean, voice: Int) {
        if (_state.value.blockMode) selectBlockBar(unit / _state.value.barLen, voice) else selectStart(unit, bar, voice)
    }

    /**
     * Pilih titik: [unit] = satuan 1/16 yang disentuh pada [voice]. Menyentuh not memilih awal not itu; menyentuh
     * ruang kosong birama memilih awal birama. Menyentuh pilihan yang sama membatalkannya. Titik ini menjadi awal
     * pemutaran dan titik sisip. Bila lagu sedang diputar, pemutaran langsung berpindah ke titik itu.
     */
    fun selectStart(unit: Int, bar: Boolean, voice: Int) {
        val s = _state.value
        val v = voice.coerceIn(0, s.score.voices.size - 1)
        val u = if (bar) unit else s.score.voices[v].eventStart(unit)
        val same = s.startUnit == u && s.startIsBar == bar && s.activeVoice == v
        _state.update {
            it.copy(activeVoice = v, startUnit = if (same) null else u, startIsBar = if (same) false else bar)
        }
        if (s.playing) {
            stopSong()
            _state.update { it.copy(playing = false) }
            togglePlay()
        }
    }

    /** Kembali menulis di ujung suara (membatalkan titik sisip). */
    fun clearStart() {
        _state.update { it.copy(startUnit = null, startIsBar = false) }
    }

    fun toggleBlockMode() {
        _state.update { it.copy(blockMode = !it.blockMode, blockAnchor = null) }
    }

    private fun selectBlockBar(bar: Int, voice: Int) {
        _state.update {
            val a = it.blockAnchor
            if (a == null) it.copy(activeVoice = voice, blockFrom = bar, blockTo = bar, blockAnchor = bar)
            else it.copy(activeVoice = voice, blockFrom = minOf(a, bar), blockTo = maxOf(a, bar), blockAnchor = null)
        }
    }

    fun clearBlock() {
        _state.update { it.copy(blockFrom = null, blockTo = null, blockAnchor = null) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message) }
    }

    /** Salin birama terpilih (suara aktif) ke papan klip. */
    fun copyBlock() {
        val s = _state.value
        val from = s.blockFrom ?: return fail("Pilih blok bar dulu: nyalakan Blok bar lalu sentuh bar awal dan bar akhir.")
        val to = s.blockTo ?: from
        val clip = s.composition.slice(from * s.barLen, (to + 1) * s.barLen)
        _state.update { it.copy(clipboard = clip, clipboardBars = to - from + 1, error = null) }
    }

    /** Tempel di awal bar tujuan (bar tempat titik terpilih). [overwrite] false = sisip, true = timpa. */
    fun paste(overwrite: Boolean) {
        val s = _state.value
        val clip = s.clipboard ?: return fail("Papan klip kosong: salin blok bar dulu.")
        val target = s.startUnit ?: return fail("Pilih bar tujuan dulu: matikan Blok bar lalu sentuh bar tujuan.")
        val at = target / s.barLen * s.barLen
        val len = s.clipboardBars * s.barLen
        editActive { c, _ ->
            val result = if (overwrite) c.replaceRange(at, at + len, clip) else c.insertAt(at, clip)
            result to at + len
        }
    }

    /** Salin blok terpilih lalu tempel setelah bar terakhir lagu. */
    fun copyBlockToEnd() {
        val s = _state.value
        val from = s.blockFrom ?: return fail("Pilih blok bar dulu: nyalakan Blok bar lalu sentuh bar awal dan bar akhir.")
        val to = s.blockTo ?: from
        val clip = s.composition.slice(from * s.barLen, (to + 1) * s.barLen)
        val end = s.score.barCount(s.settings.beatsPerBar) * s.barLen
        editActive { c, cur -> c.padTo(end).append(clip) to cur }
    }

    // ------------------------------------------------------------ pemutaran

    fun togglePlay() {
        if (_state.value.playing) { stopSong(); return }
        val s = _state.value
        if (s.isEmpty) return
        previewJob?.cancel()
        endLive()
        val start = (s.startUnit ?: 0).coerceIn(0, maxOf(0, s.score.totalUnits - 1))
        val sixteenthSec = 60.0 / s.settings.bpm / 4
        _state.update { it.copy(playing = true, playUnit = start) }
        syncMetronome()
        songJob = viewModelScope.launch {
            val me = kotlin.coroutines.coroutineContext[Job]
            try {
                val data = withContext(Dispatchers.Default) {
                    val pcm = Synth.renderVoices(s.score.voices.map { it.eventsFrom(start) }, s.settings.bpm)
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
