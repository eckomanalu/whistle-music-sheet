package id.suaranote.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Menafsirkan daftar operasi gambar dari StaffRenderer / NumberedRenderer ke Canvas Compose. */
private val PlayColor = Color(0xFFE65100) // simbol yang sedang berbunyi
private val StartColor = Color(0xFF1565C0) // awal pemutaran yang dipilih

@Composable
fun RenderedCanvas(
    rendering: Rendering,
    baseColor: Color,
    modifier: Modifier = Modifier,
    playUnit: Int? = null,
    startUnit: Int? = null,
    startIsBar: Boolean = false,
    onTap: ((unit: Int, bar: Boolean) -> Unit)? = null,
) {
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

    // warna per operasi gambar: sorotan pemutaran (oranye) dan awal terpilih (biru)
    val opColor = remember(rendering, playUnit, startUnit, startIsBar) {
        val arr = arrayOfNulls<Color>(rendering.ops.size)
        for (h in rendering.hits) {
            val c = when {
                playUnit != null && playUnit >= h.startUnit && playUnit < h.endUnit -> PlayColor
                startUnit != null && !startIsBar && startUnit == h.startUnit -> StartColor
                else -> null
            }
            if (c != null) for (i in h.ops) arr[i] = c
        }
        arr
    }
    val tapModifier = if (onTap == null) Modifier else Modifier.pointerInput(rendering) {
        detectTapGestures { p ->
            val hit = rendering.hits.firstOrNull { p.x >= it.left && p.x < it.right && p.y >= it.top && p.y < it.bottom }
            if (hit != null) {
                onTap(hit.startUnit, false)
            } else {
                val bar = rendering.bars.firstOrNull { p.x >= it.left && p.x < it.right && p.y >= it.top && p.y < it.bottom }
                if (bar != null) onTap(bar.startUnit, true)
            }
        }
    }

    Canvas(modifier.fillMaxWidth().height(heightDp).then(tapModifier)) {
        // latar: birama terpilih dan simbol yang sedang berbunyi
        if (startUnit != null && startIsBar) {
            for (b in rendering.bars) if (b.startUnit == startUnit) {
                drawRect(StartColor.copy(alpha = 0.12f), Offset(b.left, b.top), androidx.compose.ui.geometry.Size(b.right - b.left, b.bottom - b.top))
            }
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
}
