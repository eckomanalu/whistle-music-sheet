package id.suaranote.core

/**
 * Pembulatan waktu untuk mode live. Satuan: 1/16 not (1 ketuk = 4); [unitSec] = detik per 1/16;
 * [grid] = kelipatan terkecil yang dipakai (1 = 1/16, 2 = 1/8, 4 = 1/4).
 */
object LiveTiming {
    /** Durasi tahan [seconds] dibulatkan ke kelipatan [grid], minimal satu kotak, maksimal [Composition.MAX_DUR16]. */
    fun quantize(seconds: Double, unitSec: Double, grid: Int): Int {
        val cells = Math.round(seconds / (unitSec * grid)).toInt()
        return (maxOf(1, cells) * grid).coerceAtMost(Composition.MAX_DUR16)
    }

    /**
     * Panjang akhir sebuah not setelah not berikutnya ditekan.
     * [heldSec] = lama tuts ditahan, [ioiSec] = jarak tekan ke tekan berikutnya.
     * Hasil: (panjang not, panjang rehat sesudahnya; 0 = tanpa rehat). Celah kecil (kurang dari satu kotak,
     * kebiasaan melepas tuts sedikit lebih cepat) dianggap legato dan menyatu ke not; celah besar jadi rehat, kecuali [legato].
     */
    fun settle(heldSec: Double, ioiSec: Double, unitSec: Double, grid: Int, legato: Boolean = false): Pair<Int, Int> {
        val held = quantize(heldSec, unitSec, grid)
        val ioi = quantize(ioiSec, unitSec, grid)
        val rest = ioi - held
        // legato: not selalu bersambung sampai tuts berikutnya, tanpa rehat
        return if (!legato && rest >= maxOf(2, grid)) held to rest else ioi to 0
    }
}
