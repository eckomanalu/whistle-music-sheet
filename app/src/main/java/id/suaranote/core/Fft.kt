package id.suaranote.core

/** FFT radix-2 in-place (panjang harus pangkat dua). */
class Fft(val n: Int) {
    init {
        require(n > 1 && (n and (n - 1)) == 0) { "n harus pangkat dua" }
    }

    private val cosT = DoubleArray(n / 2) { Math.cos(2 * Math.PI * it / n) }
    private val sinT = DoubleArray(n / 2) { Math.sin(2 * Math.PI * it / n) }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** [inverse] = true menghitung IFFT (sudah dibagi n). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half]
                    val xi = im[j + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
        if (inverse) {
            for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }
    }
}
