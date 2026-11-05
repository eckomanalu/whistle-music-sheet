package id.suaranote.core

import kotlin.math.ln

/**
 * Nada dasar (tangga nada mayor). [fifths] > 0 = jumlah kres, < 0 = jumlah mol.
 * Label memakai ASCII (Bb, F#) supaya tampil benar di semua perangkat.
 */
enum class MusicKey(val label: String, val tonicPc: Int, val fifths: Int) {
    C("C", 0, 0),
    DFlat("Db", 1, -5),
    D("D", 2, 2),
    EFlat("Eb", 3, -3),
    E("E", 4, 4),
    F("F", 5, -1),
    FSharp("F#", 6, 6),
    G("G", 7, 1),
    AFlat("Ab", 8, -4),
    A("A", 9, 3),
    BFlat("Bb", 10, -2),
    B("B", 11, 5);

    val preferSharps: Boolean get() = fifths >= 0
}

object Pitch {
    fun freqToMidi(freqHz: Double): Double = 69.0 + 12.0 * ln(freqHz / 440.0) / ln(2.0)
}

/**
 * Nada yang sudah dieja. [letter]: 0=C 1=D 2=E 3=F 4=G 5=A 6=B.
 * [alter]: -1 mol, 0 natural, +1 kres. [accidentalSign]: tanda yang harus digambar
 * di depan not (null = tidak perlu karena sudah ada di armatur).
 */
data class SpelledNote(
    val letter: Int,
    val octave: Int,
    val alter: Int,
    val accidentalSign: Int?,
) {
    val diatonicIndex: Int get() = octave * 7 + letter

    val name: String
        get() = "CDEFGAB"[letter].toString() + (if (alter > 0) "#" else if (alter < 0) "b" else "") + octave
}

object Spelling {
    private val NATURAL_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
    private val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)  // B E A D G C F

    /** Perubahan tiap huruf (C..B) menurut armatur nada dasar. */
    fun keySignature(key: MusicKey): IntArray {
        val sig = IntArray(7)
        if (key.fifths > 0) {
            for (i in 0 until key.fifths) sig[SHARP_ORDER[i]] = 1
        } else {
            for (i in 0 until -key.fifths) sig[FLAT_ORDER[i]] = -1
        }
        return sig
    }

    fun spell(midi: Int, key: MusicKey): SpelledNote {
        val sig = keySignature(key)
        val pc = Math.floorMod(midi, 12)
        var letter = -1
        var alter = 0

        // 1) nada tangga nada (diatonis)
        for (l in 0..6) {
            if (Math.floorMod(NATURAL_PC[l] + sig[l], 12) == pc) {
                letter = l; alter = sig[l]; break
            }
        }
        // 2a) pembatalan armatur: huruf ber-kres/mol di armatur dikembalikan ke natural (mis. C-natural di D mayor)
        if (letter < 0) {
            for (l in 0..6) {
                if (sig[l] != 0 && NATURAL_PC[l] == pc) { letter = l; alter = 0; break }
            }
        }
        // 2b) nada kromatis: naikkan (kres) atau turunkan (mol) satu setengah nada
        if (letter < 0) {
            val dirs = if (key.preferSharps) intArrayOf(1, -1) else intArrayOf(-1, 1)
            search@ for (dir in dirs) {
                for (l in 0..6) {
                    val a = sig[l] + dir
                    if (a in -1..1 && Math.floorMod(NATURAL_PC[l] + a, 12) == pc) {
                        letter = l; alter = a; break@search
                    }
                }
            }
        }
        // 3) cadangan (seharusnya tidak pernah terpakai)
        if (letter < 0) {
            letter = NATURAL_PC.indexOfLast { it <= pc }
            alter = pc - NATURAL_PC[letter]
        }

        val octave = Math.floorDiv(midi - NATURAL_PC[letter] - alter, 12) - 1
        val sign = if (alter == sig[letter]) null else alter
        return SpelledNote(letter, octave, alter, sign)
    }

    /** Posisi di paranada kunci G: 0 = garis bawah (E4), tiap +1 = naik setengah spasi. */
    fun trebleStep(note: SpelledNote): Int = note.diatonicIndex - (4 * 7 + 2)

    fun noteName(midi: Int, key: MusicKey): String = spell(midi, key).name
}

/** Not angka: [degree] 1..7, [accidental] -1/0/+1, [octaveShift] titik di atas (+) atau di bawah (-). */
data class NumberedNote(val degree: Int, val accidental: Int, val octaveShift: Int)

object Numbered {
    private val SHARP_DEG = intArrayOf(1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6, 7)
    private val SHARP_ACC = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
    private val FLAT_DEG = intArrayOf(1, 2, 2, 3, 3, 4, 5, 5, 6, 6, 7, 7)
    private val FLAT_ACC = intArrayOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)

    /** Do (1) tanpa titik = tonika di atas C tengah: 1=C -> C4, 1=G -> G4, 1=B -> B4. */
    fun referenceTonic(key: MusicKey): Int = 60 + key.tonicPc

    /**
     * [octaveOffset] menggeser tampilan oktaf: +1 menaikkan not satu oktaf di tampilan
     * (menambah titik atas / mengurangi titik bawah); berguna untuk suara rendah atau tinggi.
     */
    fun of(midi: Int, key: MusicKey, octaveOffset: Int = 0): NumberedNote {
        val rel = midi - referenceTonic(key)
        val octave = Math.floorDiv(rel, 12) + octaveOffset
        val semis = Math.floorMod(rel, 12)
        return if (key.preferSharps) {
            NumberedNote(SHARP_DEG[semis], SHARP_ACC[semis], octave)
        } else {
            NumberedNote(FLAT_DEG[semis], FLAT_ACC[semis], octave)
        }
    }
}

/**
 * Cara menggambar durasi.
 * [underlines]: garis bawah not angka / jumlah bendera not balok (1 = 1/8, 2 = 1/16).
 * [dotted]: bertitik. [dashes]: tanda "-" perpanjangan not angka.
 */
data class DurationStyle(val underlines: Int, val dotted: Boolean, val dashes: Int)

fun durationStyle(dur16: Int): DurationStyle = when (dur16) {
    1 -> DurationStyle(2, false, 0)
    2 -> DurationStyle(1, false, 0)
    3 -> DurationStyle(1, true, 0)
    4 -> DurationStyle(0, false, 0)
    6 -> DurationStyle(0, true, 0)
    8 -> DurationStyle(0, false, 1)
    12 -> DurationStyle(0, false, 2)
    16 -> DurationStyle(0, false, 3)
    else -> DurationStyle(0, false, (dur16 / 4 - 1).coerceAtLeast(0))
}

object NotationText {
    /**
     * Not angka sebagai teks, mis. `| 1 1 5 5 | 6 6 5 . |`. ' = oktaf atas, , = oktaf bawah, _ = garis di atas
     * (1/8 satu, 1/16 dua), # / b = kres / mol, . = perpanjangan (titik), 0 = rehat.
     */
    fun numbered(c: Composition, key: MusicKey, beatsPerBar: Int = 4, octaveOffset: Int = 0): String {
        if (c.isEmpty) return ""
        val sb = StringBuilder("1 = ${key.label}\n|")
        for (bar in c.tokens(beatsPerBar)) {
            for (t in bar) {
                sb.append(' ')
                val midi = t.midi
                if (midi == null) {
                    sb.append('0')
                } else if (t.ext) {
                    sb.append('.')
                } else {
                    val n = Numbered.of(midi, key, octaveOffset)
                    if (n.accidental > 0) sb.append('#') else if (n.accidental < 0) sb.append('b')
                    sb.append(n.degree)
                    repeat(n.octaveShift.coerceAtLeast(0)) { sb.append('\'') }
                    repeat((-n.octaveShift).coerceAtLeast(0)) { sb.append(',') }
                }
                repeat(Chunks.lines(t.dur16)) { sb.append('_') }
            }
            sb.append(" |")
        }
        return sb.toString()
    }
}
