package id.suaranote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.suaranote.MainViewModel
import id.suaranote.UiState
import id.suaranote.core.MusicKey
import id.suaranote.core.NotationText
import id.suaranote.core.Spelling
import id.suaranote.render.NumberedRenderer
import id.suaranote.render.StaffRenderer

private val Ink = Color(0xFF1B1B1F)
private val RecordRed = Color(0xFFC62828)
private const val FIRST_MIDI = 60 // C4
private const val LAST_MIDI = 84 // C6

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.silence() }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SuaraNote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SettingsCard(state, vm)
            if (state.isEmpty) {
                Text(
                    "Pilih panjang nada, lalu tekan tuts piano di bawah. Not balok dan not angka muncul langsung. " +
                        "Tombol Hapus selalu menghapus satu nada paling akhir.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                NumberedCard(state)
                StaffCard(state)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        InputPanel(state, vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(state: UiState, vm: MainViewModel) {
    val s = state.settings
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nada dasar (1 = ${s.key.label})", fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in MusicKey.values()) {
                    FilterChip(
                        selected = s.key == k,
                        onClick = { vm.updateSettings { it.copy(key = k) } },
                        label = { Text(k.label) },
                    )
                }
            }
            Text("Birama", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in listOf(2, 3, 4)) {
                    FilterChip(
                        selected = s.beatsPerBar == b,
                        onClick = { vm.updateSettings { it.copy(beatsPerBar = b) } },
                        label = { Text("$b/4") },
                    )
                }
            }
            Stepper(
                "Geser oktaf not angka: " + (if (s.octaveOffset > 0) "+" else "") + s.octaveOffset,
                onMinus = { vm.updateSettings { it.copy(octaveOffset = (it.octaveOffset - 1).coerceAtLeast(-2)) } },
                onPlus = { vm.updateSettings { it.copy(octaveOffset = (it.octaveOffset + 1).coerceAtMost(2)) } },
            )
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("−") }
            OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(40.dp)) { Text("+") }
        }
    }
}


private val DURATION_LABELS = listOf(16 to "4", 12 to "3", 8 to "2", 6 to "1½", 4 to "1", 3 to "¾", 2 to "½", 1 to "¼")

/** Bagian bawah layar: panjang nada, tombol Rehat/Hapus/Putar, dan tuts piano. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputPanel(state: UiState, vm: MainViewModel) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.liveMode,
                onClick = { vm.setLiveMode(!state.liveMode) },
                label = { Text(if (state.liveMode) "Mode live ●" else "Mode live") },
            )
            Text(
                if (state.liveMode) "Tahan tuts selama durasi yang diinginkan" else "Panjang nada (ketuk)",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (state.liveMode) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pembulatan:", style = MaterialTheme.typography.labelMedium)
                for ((g, label) in listOf(1 to "¼ ketuk", 2 to "½ ketuk", 4 to "1 ketuk")) {
                    FilterChip(
                        selected = state.liveGrid == g,
                        onClick = { vm.setLiveGrid(g) },
                        label = { Text(label) },
                    )
                }
            }
            FilterChip(
                selected = state.liveLegato,
                onClick = { vm.setLegato(!state.liveLegato) },
                label = { Text(if (state.liveLegato) "Legato: nada bersambung, tanpa rehat" else "Legato (tanpa rehat otomatis)") },
            )
            if (!state.metronomeOn) {
                Text("Nyalakan Metronom agar ketukan Anda stabil.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((d, label) in DURATION_LABELS) {
                    FilterChip(
                        selected = state.selectedDur16 == d,
                        onClick = { vm.selectDuration(d) },
                        label = { Text(label, fontSize = 16.sp) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tempo", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { vm.changeTempo(-5) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
                Text("−", fontSize = 20.sp)
            }
            Text("${state.settings.bpm}", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton(onClick = { vm.changeTempo(5) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
                Text("+", fontSize = 20.sp)
            }
            FilterChip(
                selected = state.metronomeOn,
                onClick = { vm.toggleMetronome() },
                label = { Text(if (state.metronomeOn) "Metronom ●" else "Metronom") },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.addRest() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) {
                Text("0 Rehat", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { vm.dotLast() },
                enabled = state.composition.canDot(),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
            ) { Text("• Titik", fontSize = 13.sp) }
            OutlinedButton(
                onClick = { vm.extendLast() },
                enabled = !state.isEmpty,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
            ) { Text("– Sambung", fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { vm.deleteLast() },
                enabled = !state.isEmpty,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed),
                contentPadding = PaddingValues(2.dp),
            ) { Text("⌫ Hapus") }
            OutlinedButton(
                onClick = { vm.togglePlay() },
                enabled = !state.isEmpty,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(2.dp),
            ) { Text(if (state.playing) "■ Berhenti" else "▶ Putar") }
        }
        PianoKeyboard(onDown = { vm.keyDown(it) }, onUp = { vm.keyUp(it) })
    }
}

private fun isBlack(midi: Int) = Math.floorMod(midi, 12) in intArrayOf(1, 3, 6, 8, 10)

/** Tuts C4 sampai C6 (dua oktaf). Not ditambahkan begitu tuts disentuh. */
@Composable
private fun PianoKeyboard(onDown: (Int) -> Unit, onUp: (Int) -> Unit) {
    val whites = (FIRST_MIDI..LAST_MIDI).filter { !isBlack(it) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
        val whiteW = maxWidth / whites.size
        val blackW = whiteW * 0.62f
        for ((i, m) in whites.withIndex()) {
            Key(
                midi = m, black = false, onDown = onDown, onUp = onUp,
                modifier = Modifier.offset(x = whiteW * i).width(whiteW).height(150.dp),
                label = if (m % 12 == 0) "C${m / 12 - 1}" else null,
            )
        }
        for ((i, m) in whites.withIndex()) {
            val next = m + 1
            if (next <= LAST_MIDI && isBlack(next)) {
                Key(
                    midi = next, black = true, onDown = onDown, onUp = onUp,
                    modifier = Modifier.offset(x = whiteW * (i + 1) - blackW / 2).width(blackW).height(92.dp),
                    label = null,
                )
            }
        }
    }
}

@Composable
private fun Key(midi: Int, black: Boolean, onDown: (Int) -> Unit, onUp: (Int) -> Unit, modifier: Modifier, label: String?) {
    var down by remember { mutableStateOf(false) }
    val fill = when {
        black -> if (down) Color(0xFF5A5A66) else Color(0xFF1B1B1F)
        else -> if (down) Color(0xFFD0C4F2) else Color.White
    }
    Box(
        modifier
            .background(fill, RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .border(1.dp, Color(0xFF555560), RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .pointerInput(midi) {
                detectTapGestures(onPress = {
                    down = true
                    onDown(midi)
                    try { tryAwaitRelease() } finally { down = false; onUp(midi) }
                })
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (label != null) Text(label, fontSize = 9.sp, color = Ink, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun NumberedCard(state: UiState) {
    val s = state.settings
    val clipboard = LocalClipboardManager.current
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Not angka  ·  1 = ${s.key.label}  ·  ${s.beatsPerBar}/4", fontWeight = FontWeight.Bold, color = Ink)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(NotationText.numbered(state.measures, s.key, s.octaveOffset)))
                }) { Text("Salin teks") }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val fs = with(density) { 22.dp.toPx() }
                val r = remember(state.measures, s.key, s.octaveOffset, widthPx) {
                    NumberedRenderer.render(state.measures, s.key, s.octaveOffset, widthPx, fs)
                }
                RenderedCanvas(r, Ink)
            }
        }
    }
}

@Composable
private fun StaffCard(state: UiState) {
    val s = state.settings
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Text("Not balok", fontWeight = FontWeight.Bold, color = Ink)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val sp = with(density) { 7.5.dp.toPx() }
                val r = remember(state.measures, s.key, s.beatsPerBar, widthPx) {
                    StaffRenderer.render(state.measures, s.key, s.beatsPerBar, widthPx, sp)
                }
                RenderedCanvas(r, Ink)
            }
        }
    }
}

