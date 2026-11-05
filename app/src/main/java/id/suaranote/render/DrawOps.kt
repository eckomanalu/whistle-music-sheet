package id.suaranote.render

/**
 * Operasi gambar netral (tanpa dependensi Android) yang dihasilkan mesin tata letak.
 * Semua koordinat dalam piksel, sumbu y ke BAWAH.
 */
sealed interface DrawOp

data class LineOp(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val width: Float,
) : DrawOp

/** Glyph Bravura. [sp] = tinggi satu spasi paranada dalam piksel. (x, y) = origin glyph menurut SMuFL. */
data class GlyphOp(val glyph: Glyph, val x: Float, val y: Float, val sp: Float) : DrawOp

data class DotOp(val x: Float, val y: Float, val r: Float) : DrawOp

/** Busur ikatan (tie) berbentuk bulan sabit dari (x1, y) ke (x2, y), melengkung ke atas bila [up]. */
data class TieOp(
    val x1: Float,
    val x2: Float,
    val y: Float,
    val up: Boolean,
    val height: Float,
    val thickness: Float,
) : DrawOp

/** Teks rata tengah pada [x] dengan garis dasar [baseline]. */
data class TextOp(val text: String, val x: Float, val baseline: Float, val size: Float) : DrawOp

/**
 * Satu simbol yang bisa disorot/disentuh: [startUnit]..[endUnit] (1/16, mutlak sejak awal lagu), kotaknya,
 * dan indeks operasi gambar miliknya (untuk mewarnai).
 */
data class Hit(
    val startUnit: Int,
    val endUnit: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val ops: List<Int>,
    val voice: Int = 0,
)

/** Satu birama (untuk memilih awal pemutaran dari birama). */
data class Bar(
    val measure: Int,
    val startUnit: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val voice: Int = 0,
)

data class Rendering(
    val width: Float,
    val height: Float,
    val ops: List<DrawOp>,
    val hits: List<Hit> = emptyList(),
    val bars: List<Bar> = emptyList(),
)
