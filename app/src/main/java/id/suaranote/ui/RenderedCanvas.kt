package id.suaranote.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import id.suaranote.render.DotOp
import id.suaranote.render.Glyph
import id.suaranote.render.GlyphOp
import id.suaranote.render.LineOp
import id.suaranote.render.Rendering
import id.suaranote.render.TextOp
import id.suaranote.render.TieOp

/** Satu butir menu konteks bar. */
class BarAction(val label: String, val enabled: Boolean = true, val run: () -> Unit)

/** Menafsirkan daftar operasi gambar dari StaffRenderer / NumberedRenderer ke Canvas Compose. */
private val PlayColor = Color(0xFFE65100) // simbol yang sedang berbunyi
private val StartColor = Color(0xFF1565C0) // titik terpilih (awal pemutaran / titik sisip)
private val BlockColor = Color(0xFFFFA000) // blok birama terpilih

/** Warna tiap suara (suara 1 memakai warna dasar). */
val VoiceColors = listOf(Color(0xFF1B1B1F), Color(0xFF00695C), Color(0xFF6A1B9A), Color(0xFF8D6E63))

@Composable
fun RenderedCanvas(
    rendering: Rendering,
    baseColor: Color,
    modifier: Modifier = Modifier,
    playUnit: Int? = null,
    startUnit: Int? = null,
    startIsBar: Boolean = false,
    activeVoice: Int = 0,
    voiceCount: Int = 1,
    blockFrom: Int? = null,
    blockTo: Int? = null,
    onTap: ((unit: Int, bar: Boolean, voice: Int) -> Unit)? = null,
    onLyricTap: ((unit: Int, voice: Int) -> Unit)? = null,
    /** Menu konteks saat bar ditekan lama: (nomor bar dari 0, suara) -> aksi. */
    barMenu: ((measure: Int, voice: Int) -> List<BarAction>)? = null,
) {
    var menuAt by remember { mutableStateOf<Triple<Int, Int, Offset>?>(null) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val glyphPaths = remember {
        Glyph.values().associateWith { PathParser().parsePathString(it.path).toPath() }
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }
    val heightDp = with(density) { rendering.height.toDp() }

    // warna per operasi gambar: warna suara, sorotan pemutaran (oranye), titik terpilih (biru)
    val opColor = remember(rendering, playUnit, startUnit, startIsBar, activeVoice) {
        val arr = arrayOfNulls<Color>(rendering.ops.size)
        for (h in rendering.hits) {
            val c = when {
                playUnit != null && playUnit >= h.startUnit && playUnit < h.endUnit -> PlayColor
                startUnit != null && !startIsBar && h.voice == activeVoice && startUnit == h.startUnit -> StartColor
                h.voice > 0 -> VoiceColors[h.voice % VoiceColors.size]
                else -> null
            }
            if (c != null) for (i in h.ops) arr[i] = c
        }
        arr
    }
    val tapModifier = if (onTap == null) Modifier else Modifier.pointerInput(rendering) {
        detectTapGestures(
            onTap = { p ->
                val hit = rendering.hits.firstOrNull { p.x >= it.left && p.x < it.right && p.y >= it.top && p.y < it.bottom }
                if (hit != null) {
                    if (onLyricTap != null && p.y >= hit.lyricTop) onLyricTap(hit.startUnit, hit.voice)
                    else onTap(hit.startUnit, false, hit.voice)
                } else {
                    val bar = rendering.bars.firstOrNull { p.x >= it.left && p.x < it.right && p.y >= it.top && p.y < it.bottom }
                    if (bar != null) onTap(bar.startUnit, true, bar.voice)
                }
            },
            onLongPress = { p ->
                if (barMenu != null) {
                    val bar = rendering.bars.firstOrNull { p.x >= it.left && p.x < it.right && p.y >= it.top && p.y < it.bottom }
                    if (bar != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuAt = Triple(bar.measure, bar.voice, p)
                    }
                }
            },
        )
    }

    Box {
    Canvas(modifier.fillMaxWidth().height(heightDp).then(tapModifier)) {
        fun tint(b: id.suaranote.render.Bar, c: Color, alpha: Float) =
            drawRect(c.copy(alpha = alpha), Offset(b.left, b.top), androidx.compose.ui.geometry.Size(b.right - b.left, b.bottom - b.top))
        // latar: suara aktif (bila ada beberapa), blok terpilih, birama titik terpilih, simbol yang sedang berbunyi
        for (b in rendering.bars) {
            if (voiceCount > 1 && b.voice == activeVoice) tint(b, VoiceColors[b.voice % VoiceColors.size], 0.07f)
            if (blockFrom != null && blockTo != null && b.voice == activeVoice && b.measure in blockFrom..blockTo) tint(b, BlockColor, 0.22f)
            if (startUnit != null && startIsBar && b.voice == activeVoice && b.startUnit == startUnit) tint(b, StartColor, 0.14f)
        }
        if (playUnit != null) {
            for (h in rendering.hits) if (playUnit >= h.startUnit && playUnit < h.endUnit) {
                drawRect(PlayColor.copy(alpha = 0.16f), Offset(h.left, h.top), androidx.compose.ui.geometry.Size(h.right - h.left, h.bottom - h.top))
            }
        }
        for ((opIndex, op) in rendering.ops.withIndex()) {
            val color = opColor[opIndex] ?: baseColor
            when (op) {
                is LineOp -> drawLine(color, Offset(op.x1, op.y1), Offset(op.x2, op.y2), strokeWidth = op.width)

                is GlyphOp -> {
                    val path = glyphPaths.getValue(op.glyph)
                    val s = op.sp / Glyph.UNITS_PER_SPACE
                    withTransform({
                        translate(op.x, op.y)
                        scale(s, -s, Offset.Zero) // font: y ke atas -> layar: y ke bawah
                    }) {
                        drawPath(path, color)
                    }
                }

                is DotOp -> drawCircle(color, op.r, Offset(op.x, op.y))

                is TieOp -> {
                    val sg = if (op.up) -1f else 1f
                    val xm = (op.x1 + op.x2) / 2
                    val p = Path().apply {
                        moveTo(op.x1, op.y)
                        quadraticBezierTo(xm, op.y + sg * 2 * op.height, op.x2, op.y)
                        quadraticBezierTo(xm, op.y + sg * 2 * (op.height - op.thickness), op.x1, op.y)
                        close()
                    }
                    drawPath(p, color)
                }

                is TextOp -> {
                    paint.color = color.toArgb()
                    paint.textSize = op.size
                    drawContext.canvas.nativeCanvas.drawText(op.text, op.x, op.baseline, paint)
                }
            }
        }
    }
    val m = menuAt
    if (m != null && barMenu != null) {
        Box(Modifier.offset { IntOffset(m.third.x.toInt(), m.third.y.toInt()) }) {
            DropdownMenu(expanded = true, onDismissRequest = { menuAt = null }) {
                for (a in barMenu(m.first, m.second)) {
                    DropdownMenuItem(
                        text = { Text(a.label) },
                        enabled = a.enabled,
                        onClick = { menuAt = null; a.run() },
                    )
                }
            }
        }
    }
    }
}
