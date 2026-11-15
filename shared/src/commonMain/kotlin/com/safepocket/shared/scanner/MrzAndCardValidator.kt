package com.safepocket.shared.scanner

import com.safepocket.shared.model.DocumentSubtype

data class ParsedIcaoMrz(
    val format: String,
    val documentSubtype: DocumentSubtype,
    val issuingState: String,
    val nationality: String,
    val documentNumber: String,
    val holderFullName: String,
    val birthDateIso: String,
    val expiryDateIso: String,
    val checkDigitsValid: Boolean
)

/**
 * Shared KMP 100% Offline ICAO Doc 9303 MRZ Parser (TD3 Passports & TD1 National IDs)
 * and Luhn / ISO-13616 IBAN Validators for Android 17+, Googlebook OS, ChromeOS, and macOS.
 */
object MrzAndCardValidator {
    private val weights = intArrayOf(7, 3, 1)

    fun computeIcaoCheckDigit(field: String): Int {
        var sum = 0
        val upper = field.uppercase()
        for (i in upper.indices) {
            val v = when (val c = upper[i]) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> 0
            }
            sum += v * weights[i % 3]
        }
        return sum % 10
    }

    fun parseMrz(rawMrz: String): ParsedIcaoMrz {
        val lines = rawMrz
            .lines()
            .map { it.trim().uppercase().replace(" ", "") }
            .filter { it.isNotEmpty() }

        if (lines.size == 2 && lines[0].length == 44 && lines[1].length == 44) {
            val l1 = lines[0]
            val l2 = lines[1]
            val docField = l2.substring(0, 9)
            val dobField = l2.substring(13, 19)
            val expField = l2.substring(21, 27)
            val valid = computeIcaoCheckDigit(docField) == l2[9].digitToIntOrNull() &&
                computeIcaoCheckDigit(dobField) == l2[19].digitToIntOrNull() &&
                computeIcaoCheckDigit(expField) == l2[27].digitToIntOrNull()

            return ParsedIcaoMrz(
                format = "TD3_PASSPORT",
                documentSubtype = DocumentSubtype.PASSPORT,
                issuingState = l1.substring(2, 5).replace("<", ""),
                nationality = l2.substring(10, 13).replace("<", ""),
                documentNumber = docField.replace("<", ""),
                holderFullName = parseHolderName(l1.substring(5)),
                birthDateIso = expandDate(dobField, isExpiry = false),
                expiryDateIso = expandDate(expField, isExpiry = true),
                checkDigitsValid = valid
            )
        }

        if (lines.size == 3 && lines.all { it.length == 30 }) {
            val (l1, l2, l3) = lines
            val docField = l1.substring(5, 14)
            val dobField = l2.substring(0, 6)
            val expField = l2.substring(8, 14)
            val valid = computeIcaoCheckDigit(docField) == l1[14].digitToIntOrNull() &&
                computeIcaoCheckDigit(dobField) == l2[6].digitToIntOrNull() &&
                computeIcaoCheckDigit(expField) == l2[14].digitToIntOrNull()

            return ParsedIcaoMrz(
                format = "TD1_NATIONAL_ID",
                documentSubtype = DocumentSubtype.NATIONAL_ID,
                issuingState = l1.substring(2, 5).replace("<", ""),
                nationality = l2.substring(15, 18).replace("<", ""),
                documentNumber = docField.replace("<", ""),
                holderFullName = parseHolderName(l3),
                birthDateIso = expandDate(dobField, isExpiry = false),
                expiryDateIso = expandDate(expField, isExpiry = true),
                checkDigitsValid = valid
            )
        }

        throw IllegalArgumentException("Unsupported MRZ geometry")
    }

    fun validateLuhn(rawPan: String): Boolean {
        val digits = rawPan.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        var dbl = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (dbl) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            dbl = !dbl
        }
        return sum % 10 == 0
    }

    fun validateIsoIban(rawIban: String): Boolean {
        val cleaned = rawIban.replace(" ", "").uppercase()
        if (!cleaned.matches(Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$"))) return false
        val rearranged = cleaned.substring(4) + cleaned.substring(0, 4)
        var rem = 0
        for (c in rearranged) {
            val exp = if (c in 'A'..'Z') (c.code - 55).toString() else c.toString()
            for (d in exp) {
                rem = (rem * 10 + (d - '0')) % 97
            }
        }
        return rem == 1
    }

    private fun parseHolderName(segment: String): String {
        val parts = segment.split("<<", limit = 2)
        val surname = parts.getOrElse(0) { "" }.replace("<", " ").trim()
        val given = parts.getOrElse(1) { "" }.replace("<", " ").trim()
        return listOf(given, surname).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun expandDate(yyMmDd: String, isExpiry: Boolean): String {
        if (yyMmDd.length != 6 || !yyMmDd.all { it.isDigit() }) return ""
        val yy = yyMmDd.substring(0, 2).toInt()
        val century = if (isExpiry || yy <= 35) 2000 else 1900
        return "${century + yy}-${yyMmDd.substring(2, 4)}-${yyMmDd.substring(4, 6)}"
    }
}
