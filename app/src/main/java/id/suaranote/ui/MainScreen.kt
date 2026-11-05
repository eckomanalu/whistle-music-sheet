package id.suaranote.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.suaranote.MainViewModel
import id.suaranote.PlayWhat
import id.suaranote.UiState
import id.suaranote.core.MusicKey
import id.suaranote.core.NotationText
import id.suaranote.render.NumberedRenderer
import id.suaranote.render.StaffRenderer

private val Ink = Color(0xFF1B1B1F)
private val RecordRed = Color(0xFFC62828)

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start()
        else vm.showError("Izin mikrofon ditolak. Aktifkan di Pengaturan > Aplikasi > SuaraNote > Izin.")
    }

    // Android membisukan mikrofon untuk aplikasi di latar belakang, jadi rekaman dihentikan.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }

    fun toggleRecording() {
        if (state.recording) {
            vm.stop()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SuaraNote", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Siulkan atau dengungkan satu bagian lagu (satu nada pada satu waktu), lalu tekan Berhenti. Rekaman dibersihkan dan dianalisis utuh, baru notasinya ditulis.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SettingsCard(state, vm)
        RecordCard(state)

        Button(
            onClick = { toggleRecording() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !state.processing,
            colors = ButtonDefaults.buttonColors(containerColor = if (state.recording) Ink else RecordRed),
        ) {
            Text(if (state.recording) "Berhenti" else if (state.hasRecording) "Rekam ulang" else "Rekam", fontSize = 18.sp)
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.hasRecording && !state.processing) PlayCard(state, vm)
        if (state.hasRecording && !state.processing && state.contour.isNotEmpty()) ContourCard(state)

        if (state.hasRecording && !state.processing) {
            if (state.measures.isEmpty()) {
                Text("Tidak ada nada yang terdeteksi. Coba lebih dekat ke mikrofon (sekitar 10–20 cm), di tempat yang tenang, dengan nada yang ditahan jelas.")
            } else {
                NumberedCard(state)
                StaffCard(state)
            }
        }
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

            Stepper("Tempo: ${s.bpm} ketuk/menit",
                onMinus = { vm.updateSettings { it.copy(bpm = (it.bpm - 5).coerceAtLeast(40)) } },
                onPlus = { vm.updateSettings { it.copy(bpm = (it.bpm + 5).coerceAtMost(200)) } })

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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pembersih derau", fontWeight = FontWeight.Medium)
                Switch(checked = s.denoise, onCheckedChange = { v -> vm.updateSettings { it.copy(denoise = v) } })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Kalibrasi nada otomatis", fontWeight = FontWeight.Medium)
                    Text("Bila Anda konsisten sedikit sharp/flat, nada digeser ke tala standar.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = s.autoTune, onCheckedChange = { v -> vm.updateSettings { it.copy(autoTune = v) } })
            }
            if (state.hasRecording) {
                Text(
                    "Tempo, birama, nada dasar, dan oktaf bisa diubah setelah merekam; notasi langsung dihitung ulang.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

@Composable
private fun RecordCard(state: UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                state.recording -> {
                    val m = (state.elapsedSec / 60).toInt()
                    val sec = (state.elapsedSec % 60).toInt()
                    Text("Merekam  %d:%02d".format(m, sec), style = MaterialTheme.typography.titleMedium, color = RecordRed)
                    LinearProgressIndicator(progress = { state.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("Notasi ditulis setelah Anda menekan Berhenti.", style = MaterialTheme.typography.bodySmall)
                }
                state.processing -> {
                    CircularProgressIndicator()
                    Text("Membersihkan derau dan menganalisis nada…")
                }
                else -> {
                    Text("Siap merekam", fontWeight = FontWeight.Medium)
                    state.tuningCents?.let {
                        if (it != 0) Text(
                            "Rata-rata nada Anda ${if (it > 0) "+$it" else "$it"} sen dari tala standar.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayCard(state: UiState, vm: MainViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dengarkan & bandingkan", fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((what, label) in listOf(PlayWhat.ORIGINAL to "Asli", PlayWhat.CLEAN to "Bersih", PlayWhat.RESULT to "Hasil")) {
                    val on = state.playing == what
                    val enabled = what != PlayWhat.RESULT || state.measures.isNotEmpty()
                    OutlinedButton(
                        onClick = { vm.togglePlay(what) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text(if (on) "■ $label" else "▶ $label") }
                }
            }
            Text(
                "Asli = rekaman mentah. Bersih = setelah derau dibuang. Hasil = nada yang tertulis di notasi. " +
                    "Bandingkan Asli dengan Hasil untuk menilai ketepatan.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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

/** Titik = nada terukur tiap frame; garis = nada tepat. Titik yang jauh dari garis = nyanyian/deteksi meleset. */
@Composable
private fun ContourCard(state: UiState) {
    val key = state.settings.key
    val c = state.contour
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in c) if (!v.isNaN()) { lo = minOf(lo, v); hi = maxOf(hi, v) }
    if (lo > hi) return
    val loI = kotlin.math.floor(lo - 0.5f).toInt()
    val hiI = kotlin.math.ceil(hi + 0.5f).toInt()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Text("Kontur nada", fontWeight = FontWeight.Bold, color = Ink)
            Text("Titik di tengah garis = tepat. Titik di antara dua garis = meleset.", style = MaterialTheme.typography.bodySmall, color = Ink)
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val left = 34.dp.toPx()
                val w = size.width - left
                val span = (hiI - loI).coerceAtLeast(1).toFloat()
                fun y(m: Float) = size.height * (1f - (m - loI) / span)
                for (m in loI..hiI) {
                    val yy = y(m.toFloat())
                    drawLine(lineColor, androidx.compose.ui.geometry.Offset(left, yy), androidx.compose.ui.geometry.Offset(size.width, yy), 1f)
                    if (span <= 24 || m % 12 == 0) {
                        drawText(measurer, id.suaranote.core.Spelling.noteName(m, key),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, yy - 8.dp.toPx()),
                            style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Ink))
                    }
                }
                val n = c.size
                for (i in 0 until n) {
                    val v = c[i]
                    if (v.isNaN()) continue
                    drawCircle(dotColor, 1.8.dp.toPx(), androidx.compose.ui.geometry.Offset(left + w * i / n, y(v)))
                }
            }
        }
    }
}
