package id.suaranote.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
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
@Composable
fun RenderedCanvas(rendering: Rendering, color: Color, modifier: Modifier = Modifier) {
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

    Canvas(modifier.fillMaxWidth().height(heightDp)) {
        for (op in rendering.ops) {
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
