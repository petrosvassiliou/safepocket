package com.safepocket.shared.scanner

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure Kotlin Multiplatform EPC069-12 SEPA GiroCode, Read-Aloud Formatter,
 * ISO/IEC 18004 QR Code Matrix Encoder (Versions 1..6, ECC Level L), and
 * ISO/IEC 15417 Code 128B Barcode Module Encoder.
 *
 * 100% offline, zero external dependencies, Google Play Protect compliant.
 */
object EpcSepaAndQrEngine {

    data class ReadAloudBlock(val index: Int, val chars: String)

    fun buildEpcSepaQrPayload(
        holderName: String,
        iban: String,
        bic: String = "",
        amountEur: String = "",
        remittanceText: String = ""
    ): String? {
        val cleanIban = iban.replace(Regex("[\\s-]+"), "").uppercase().trim()
        if (!Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$").matches(cleanIban)) {
            return null
        }

        val cleanHolder = holderName
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(70)
            .ifBlank { "Account Holder" }

        val rawBic = bic.replace(Regex("\\s+"), "").uppercase()
        val cleanBic = if (Regex("^[A-Z0-9]{8}([A-Z0-9]{3})?$").matches(rawBic)) rawBic else ""

        val formattedAmount = formatSepaAmountEur(amountEur)
        val cleanRemit = remittanceText
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(40)

        val lines = mutableListOf(
            "BCD",
            "002",
            "1",
            "SCT",
            cleanBic,
            cleanHolder,
            cleanIban,
            formattedAmount,
            "",
            "",
            cleanRemit
        )
        while (lines.size > 7 && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }
        return lines.joinToString("\n")
    }

    private fun formatSepaAmountEur(raw: String): String {
        val normalized = raw.trim().replace(',', '.').filter { it.isDigit() || it == '.' }
        val value = normalized.toDoubleOrNull() ?: return ""
        if (value <= 0.0 || value > 999_999_999.99) return ""
        val cents = (value * 100.0).roundToLong()
        val whole = cents / 100L
        val frac = (cents % 100L).toString().padStart(2, '0')
        return "EUR$whole.$frac"
    }

    fun buildReadAloudBlocks(rawValue: String, groupSize: Int = 4): List<ReadAloudBlock> {
        val compact = rawValue.replace(Regex("[\\s-]+"), "").trim()
        if (compact.isEmpty()) return emptyList()
        val step = groupSize.coerceAtLeast(2)
        val out = mutableListOf<ReadAloudBlock>()
        var idx = 1
        var i = 0
        while (i < compact.length) {
            out += ReadAloudBlock(idx++, compact.substring(i, min(compact.length, i + step)))
            i += step
        }
        return out
    }

    // --- ISO/IEC 15417 Code 128B Encoder ---

    private val CODE128_PATTERNS = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
        "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
        "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
        "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
        "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
        "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
        "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
        "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
        "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
        "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
        "211214", "211232", "2331112"
    )

    fun encodeCode128BModules(rawValue: String): BooleanArray {
        val clean = rawValue.trim().filter { it.code in 32..126 }.ifEmpty { "SAFEPOCKET" }
        val symbols = mutableListOf(104)
        var weightedSum = 104
        for (i in clean.indices) {
            val sym = clean[i].code - 32
            symbols += sym
            weightedSum += (i + 1) * sym
        }
        symbols += (weightedSum % 103)
        symbols += 106

        val modules = ArrayList<Boolean>(symbols.size * 11 + 22)
        repeat(10) { modules += false }
        for (sym in symbols) {
            val pat = CODE128_PATTERNS[sym]
            for (idx in pat.indices) {
                val width = pat[idx].digitToInt()
                val isBar = (idx % 2 == 0)
                repeat(width) { modules += isBar }
            }
        }
        repeat(10) { modules += false }
        return modules.toBooleanArray()
    }

    // --- ISO/IEC 18004 QR Code Matrix Encoder (Versions 1..6, ECC Level L) ---

    private data class QrVersionSpec(
        val version: Int,
        val size: Int,
        val totalDataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignmentCenters: IntArray
    )

    private val QR_SPECS = listOf(
        QrVersionSpec(1, 21, 19, 7, 1, intArrayOf()),
        QrVersionSpec(2, 25, 34, 10, 1, intArrayOf(6, 18)),
        QrVersionSpec(3, 29, 55, 15, 1, intArrayOf(6, 22)),
        QrVersionSpec(4, 33, 80, 20, 1, intArrayOf(6, 26)),
        QrVersionSpec(5, 37, 108, 26, 1, intArrayOf(6, 30)),
        QrVersionSpec(6, 41, 136, 18, 2, intArrayOf(6, 34))
    )

    private fun gf256Mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11d)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xff
    }

    private fun reedSolomonRemainder(data: IntArray, degree: Int): IntArray {
        val gen = IntArray(degree)
        gen[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                gen[j] = gf256Mul(gen[j], root)
                if (j + 1 < degree) {
                    gen[j] = gen[j] xor gen[j + 1]
                }
            }
            root = gf256Mul(root, 0x02)
        }
        val result = IntArray(degree)
        for (b in data) {
            val factor = b xor result[0]
            for (k in 0 until degree - 1) result[k] = result[k + 1]
            result[degree - 1] = 0
            for (j in 0 until degree) {
                result[j] = result[j] xor gf256Mul(gen[j], factor)
            }
        }
        return result
    }

    fun encodeQrMatrix(payload: String): Array<BooleanArray> {
        val utf8 = (payload.ifEmpty { "SAFEPOCKET" }).encodeToByteArray()
        val spec = QR_SPECS.firstOrNull { utf8.size <= it.totalDataCodewords - 2 } ?: QR_SPECS.last()
        val len = min(utf8.size, spec.totalDataCodewords - 2)

        val bits = ArrayList<Int>(spec.totalDataCodewords * 8)
        fun pushBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits += (value ushr i) and 1
            }
        }
        pushBits(0b0100, 4)
        pushBits(len, 8)
        for (i in 0 until len) pushBits(utf8[i].toInt() and 0xff, 8)

        val capBits = spec.totalDataCodewords * 8
        pushBits(0, min(4, capBits - bits.size))
        while (bits.size % 8 != 0) bits += 0

        val dataCw = IntArray(spec.totalDataCodewords)
        val writtenBytes = bits.size / 8
        for (i in 0 until writtenBytes) {
            var v = 0
            for (b in 0 until 8) v = (v shl 1) or bits[i * 8 + b]
            dataCw[i] = v
        }
        var padToggle = true
        for (i in writtenBytes until spec.totalDataCodewords) {
            dataCw[i] = if (padToggle) 0xec else 0x11
            padToggle = !padToggle
        }

        val blockLen = spec.totalDataCodewords / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { b ->
            dataCw.copyOfRange(b * blockLen, (b + 1) * blockLen)
        }
        val ecBlocks = Array(spec.numBlocks) { b ->
            reedSolomonRemainder(dataBlocks[b], spec.ecCodewordsPerBlock)
        }

        val finalCw = ArrayList<Int>(spec.totalDataCodewords + spec.ecCodewordsPerBlock * spec.numBlocks)
        for (i in 0 until blockLen) {
            for (b in 0 until spec.numBlocks) finalCw += dataBlocks[b][i]
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) finalCw += ecBlocks[b][i]
        }

        val n = spec.size
        val modules = Array(n) { BooleanArray(n) }
        val isFunc = Array(n) { BooleanArray(n) }

        fun setFunc(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until n && c in 0 until n) {
                modules[r][c] = dark
                isFunc[r][c] = true
            }
        }

        fun placeFinder(topR: Int, leftC: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val inOuter = dr in 0..6 && dc in 0..6
                    val onBorder = dr == 0 || dr == 6 || dc == 0 || dc == 6
                    val inCore = dr in 2..4 && dc in 2..4
                    setFunc(topR + dr, leftC + dc, inOuter && (onBorder || inCore))
                }
            }
        }
        placeFinder(0, 0)
        placeFinder(0, n - 7)
        placeFinder(n - 7, 0)

        for (i in 8 until n - 8) {
            setFunc(6, i, i % 2 == 0)
            setFunc(i, 6, i % 2 == 0)
        }

        val centers = spec.alignmentCenters
        for (i in centers.indices) {
            for (j in centers.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == centers.lastIndex) || (i == centers.lastIndex && j == 0)) {
                    continue
                }
                val cr = centers[i]
                val cc = centers[j]
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        setFunc(cr + dr, cc + dc, maxOf(abs(dr), abs(dc)) != 1)
                    }
                }
            }
        }

        for (i in 0..8) {
            if (i != 6) {
                isFunc[8][i] = true
                isFunc[i][8] = true
            }
        }
        for (i in 0 until 8) {
            isFunc[8][n - 1 - i] = true
            if (i < 7) isFunc[n - 1 - i][8] = true
        }
        setFunc(n - 8, 8, true)

        var bitIdx = 0
        val totalBits = finalCw.size * 8
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until n) {
                val r = if (((right + 1) and 2) == 0) n - 1 - vert else vert
                for (j in 0..1) {
                    val c = right - j
                    if (!isFunc[r][c]) {
                        var dark = false
                        if (bitIdx < totalBits) {
                            val cw = finalCw[bitIdx ushr 3]
                            dark = ((cw ushr (7 - (bitIdx and 7))) and 1) == 1
                            bitIdx++
                        }
                        modules[r][c] = dark
                    }
                }
            }
            right -= 2
        }

        fun writeFormatBits(mask: Int, target: Array<BooleanArray>) {
            val data5 = (0b01 shl 3) or mask
            var rem = data5
            repeat(10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            val bits15 = ((data5 shl 10) or rem) xor 0x5412
            fun bit(idx: Int): Boolean = ((bits15 ushr idx) and 1) == 1

            for (i in 0..5) target[8][i] = bit(14 - i)
            target[8][7] = bit(8)
            target[8][8] = bit(7)
            target[7][8] = bit(6)
            for (i in 9..14) target[14 - i][8] = bit(14 - i)
            for (i in 0..6) target[n - 1 - i][8] = bit(14 - i)
            for (i in 7..14) target[8][n - 15 + i] = bit(14 - i)
            target[n - 8][8] = true
        }

        fun maskTest(m: Int, r: Int, c: Int): Boolean = when (m) {
            0 -> (r + c) % 2 == 0
            1 -> r % 2 == 0
            2 -> c % 3 == 0
            3 -> (r + c) % 3 == 0
            4 -> ((r / 2) + (c / 3)) % 2 == 0
            5 -> ((r * c) % 2) + ((r * c) % 3) == 0
            6 -> (((r * c) % 2) + ((r * c) % 3)) % 2 == 0
            else -> (((r + c) % 2) + ((r * c) % 3)) % 2 == 0
        }

        var bestPenalty = Int.MAX_VALUE
        var bestGrid = modules
        for (m in 0..7) {
            val cand = Array(n) { r ->
                BooleanArray(n) { c ->
                    if (!isFunc[r][c] && maskTest(m, r, c)) !modules[r][c] else modules[r][c]
                }
            }
            writeFormatBits(m, cand)
            var penalty = 0
            for (r in 0 until n) {
                var run = 1
                for (c in 1 until n) {
                    if (cand[r][c] == cand[r][c - 1]) {
                        run++
                        if (run == 5) penalty += 3 else if (run > 5) penalty += 1
                    } else run = 1
                }
            }
            for (c in 0 until n) {
                var run = 1
                for (r in 1 until n) {
                    if (cand[r][c] == cand[r - 1][c]) {
                        run++
                        if (run == 5) penalty += 3 else if (run > 5) penalty += 1
                    } else run = 1
                }
            }
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestGrid = cand
            }
        }
        return bestGrid
    }
}
