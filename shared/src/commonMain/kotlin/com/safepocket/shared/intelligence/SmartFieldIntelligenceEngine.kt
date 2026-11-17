package com.safepocket.shared.intelligence

import com.safepocket.shared.brand.InstitutionBrandProfile
import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.model.CanonicalFieldKeys
import com.safepocket.shared.model.FieldValueType
import com.safepocket.shared.model.VaultCategory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header

data class DropdownOptionPreset(
    val id: String,
    val displayLabel: String,
    val storedValue: String = displayLabel
)

data class CountryDialCode(
    val iso2: String,
    val flagEmoji: String,
    val dialCode: String,
    val label: String
)

sealed interface WebsiteReachabilityResult {
    data object Idle : WebsiteReachabilityResult
    data class Checking(val normalizedUrl: String) : WebsiteReachabilityResult
    data class Reachable(val normalizedUrl: String, val httpStatus: Int) : WebsiteReachabilityResult
    data class Unreachable(val normalizedUrl: String, val reason: String) : WebsiteReachabilityResult
}

data class IbanAnalysisResult(
    val formattedIban: String,
    val isCompleteLength: Boolean,
    val isMod97Valid: Boolean,
    val countryCode: String,
    val extractedRoutingOrBlz: String?,
    val extractedAccountNumber: String?,
    val matchedInstitution: InstitutionBrandProfile?,
    val suggestedBic: String?,
    val suggestedWebsiteHttps: String?,
    val suggestedCurrency: String?
)

data class CardNumberAnalysisResult(
    val formattedPan: String,
    val digitsCount: Int,
    val isLuhnValid: Boolean,
    val detectedNetworkCode: String,
    val detectedNetworkLabel: String
)

/**
 * SafePocket v0.3 Intelligent Field Engine:
 * - Prefixes `https://` automatically on website/domain inputs and probes live HTTPS reachability
 * - Provides localized standard dropdown presets (Bank Account Type, Card Network/Type, Currency,
 *   Insurance Plan, Document Subtype, Salutation, Country)
 * - Auto-formats international phone numbers (`+49 ...`), IBANs (4-char blocks + ISO Mod-97 +
 *   cross-field Bank/BIC/BLZ/Account/Website autofill), Payment Card PANs (Luhn + network), BICs,
 *   ISO Dates, and Currency Limits.
 */
object SmartFieldIntelligenceEngine {

    val COUNTRY_DIAL_CODES: List<CountryDialCode> = listOf(
        CountryDialCode("DE", "🇩🇪", "+49", "DE +49"),
        CountryDialCode("AT", "🇦🇹", "+43", "AT +43"),
        CountryDialCode("CH", "🇨🇭", "+41", "CH +41"),
        CountryDialCode("GR", "🇬🇷", "+30", "GR +30"),
        CountryDialCode("IT", "🇮🇹", "+39", "IT +39"),
        CountryDialCode("FR", "🇫🇷", "+33", "FR +33"),
        CountryDialCode("ES", "🇪🇸", "+34", "ES +34"),
        CountryDialCode("NL", "🇳🇱", "+31", "NL +31"),
        CountryDialCode("BE", "🇧🇪", "+32", "BE +32"),
        CountryDialCode("LU", "🇱🇺", "+352", "LU +352"),
        CountryDialCode("GB", "🇬🇧", "+44", "UK +44"),
        CountryDialCode("US", "🇺🇸", "+1", "US +1")
    )

    private val IBAN_LENGTH_BY_COUNTRY = mapOf(
        "DE" to 22, "AT" to 20, "CH" to 21, "LI" to 21, "GR" to 27, "IT" to 27,
        "FR" to 27, "ES" to 24, "NL" to 18, "BE" to 16, "LU" to 20, "PT" to 25,
        "IE" to 22, "GB" to 22, "PL" to 28, "SE" to 24, "NO" to 15, "DK" to 18,
        "FI" to 18, "CZ" to 24, "CY" to 28
    )

    fun defaultDialCodeForLocale(localeCode: String): String = when (localeCode.lowercase()) {
        "de" -> "+49"
        "el" -> "+30"
        "it" -> "+39"
        "fr" -> "+33"
        "es" -> "+34"
        else -> "+49"
    }

    /**
     * Automatically normalizes bare domains (`ing.de`, `www.ing.de`) and `http://` links to `https://`.
     */
    fun normalizeHttpsUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""
        val withoutScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> trimmed
        }.trim()
        if (withoutScheme.isEmpty()) return ""
        val hostPart = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        val suffix = withoutScheme.substring(hostPart.length)
        return "https://$hostPart$suffix"
    }

    fun isPlausibleHttpsUrl(url: String): Boolean {
        val normalized = normalizeHttpsUrl(url)
        if (!normalized.startsWith("https://")) return false
        val host = normalized.removePrefix("https://").substringBefore('/').substringBefore(':')
        return host.contains('.') && host.length >= 4 && !host.contains(' ') &&
            host.substringAfterLast('.').length >= 2
    }

    /**
     * Checks whether the `https://` website actually resolves and responds over HTTPS.
     */
    suspend fun verifyWebsiteExists(
        rawUrlOrDomain: String,
        httpClient: HttpClient
    ): WebsiteReachabilityResult {
        val normalized = normalizeHttpsUrl(rawUrlOrDomain)
        if (!isPlausibleHttpsUrl(normalized)) {
            return WebsiteReachabilityResult.Unreachable(normalized, "Invalid domain format")
        }

        // Probe normalized URL first, and if bare domain fails without www., also try https://www.<host>
        val candidates = buildList {
            add(normalized)
            val host = normalized.removePrefix("https://").substringBefore('/')
            if (!host.startsWith("www.") && host.count { it == '.' } == 1) {
                add("https://www.$host")
            }
        }

        for (candidate in candidates) {
            val headCode = runCatching {
                httpClient.head(candidate) {
                    header("User-Agent", "SafePocket-LinkVerifier/0.3")
                }.status.value
            }.getOrNull()

            if (headCode != null && headCode in 200..499) {
                return WebsiteReachabilityResult.Reachable(candidate, headCode)
            }

            val getCode = runCatching {
                httpClient.get(candidate) {
                    header("User-Agent", "SafePocket-LinkVerifier/0.3")
                    header("Range", "bytes=0-255")
                }.status.value
            }.getOrNull()

            if (getCode != null && getCode in 200..499) {
                return WebsiteReachabilityResult.Reachable(candidate, getCode)
            }
        }

        return WebsiteReachabilityResult.Unreachable(normalized, "Host did not respond over HTTPS")
    }

    /**
     * Standard localized dropdown selections for structured fields (with custom fallback in UI).
     */
    fun dropdownOptionsForField(
        fieldKey: String,
        category: VaultCategory,
        i18n: LocalizationEngine
    ): List<DropdownOptionPreset> = when (fieldKey) {
        CanonicalFieldKeys.ACCOUNT_TYPE -> listOf(
            DropdownOptionPreset("checking", i18n.t("acct_type_checking")),
            DropdownOptionPreset("savings", i18n.t("acct_type_savings")),
            DropdownOptionPreset("fixed_deposit", i18n.t("acct_type_fixed_deposit")),
            DropdownOptionPreset("joint", i18n.t("acct_type_joint")),
            DropdownOptionPreset("business", i18n.t("acct_type_business")),
            DropdownOptionPreset("brokerage", i18n.t("acct_type_brokerage")),
            DropdownOptionPreset("foreign_currency", i18n.t("acct_type_foreign_currency")),
            DropdownOptionPreset("youth_student", i18n.t("acct_type_youth_student"))
        )

        CanonicalFieldKeys.CARD_NETWORK -> listOf(
            DropdownOptionPreset("visa", "VISA"),
            DropdownOptionPreset("mastercard", "Mastercard"),
            DropdownOptionPreset("amex", "American Express"),
            DropdownOptionPreset("girocard", "girocard"),
            DropdownOptionPreset("maestro", "Maestro"),
            DropdownOptionPreset("vpay", "V PAY"),
            DropdownOptionPreset("discover", "Discover"),
            DropdownOptionPreset("diners", "Diners Club"),
            DropdownOptionPreset("unionpay", "UnionPay"),
            DropdownOptionPreset("jcb", "JCB")
        )

        CanonicalFieldKeys.CARD_TYPE -> listOf(
            DropdownOptionPreset("credit", i18n.t("card_type_credit")),
            DropdownOptionPreset("debit", i18n.t("card_type_debit")),
            DropdownOptionPreset("charge", i18n.t("card_type_charge")),
            DropdownOptionPreset("prepaid", i18n.t("card_type_prepaid")),
            DropdownOptionPreset("virtual", i18n.t("card_type_virtual")),
            DropdownOptionPreset("business", i18n.t("card_type_business"))
        )

        CanonicalFieldKeys.CURRENCY -> listOf(
            DropdownOptionPreset("EUR", "EUR (€)", "EUR"),
            DropdownOptionPreset("USD", "USD ($)", "USD"),
            DropdownOptionPreset("GBP", "GBP (£)", "GBP"),
            DropdownOptionPreset("CHF", "CHF", "CHF"),
            DropdownOptionPreset("SEK", "SEK", "SEK"),
            DropdownOptionPreset("NOK", "NOK", "NOK"),
            DropdownOptionPreset("DKK", "DKK", "DKK"),
            DropdownOptionPreset("PLN", "PLN", "PLN"),
            DropdownOptionPreset("CZK", "CZK", "CZK"),
            DropdownOptionPreset("JPY", "JPY (¥)", "JPY")
        )

        CanonicalFieldKeys.COVERAGE_PLAN -> listOf(
            DropdownOptionPreset("egk", "Gesetzliche Krankenversicherung (eGK / GKV)", "eGK / GKV"),
            DropdownOptionPreset("ehic", "European Health Insurance Card (EHIC)", "EHIC"),
            DropdownOptionPreset("pkv", "Private Krankenversicherung (PKV)", "PKV"),
            DropdownOptionPreset("dental", "Zahnzusatz / Supplementary Dental", "Dental Supplementary"),
            DropdownOptionPreset("travel", "Auslandsreisekrankenversicherung / Travel", "Travel Medical"),
            DropdownOptionPreset("liability", "Haftpflicht /KFZ-Schutzbrief", "Liability / Motor")
        )

        CanonicalFieldKeys.DOCUMENT_SUBTYPE -> listOf(
            DropdownOptionPreset("passport", "Passport / Reisepass"),
            DropdownOptionPreset("national_id", "National ID / Personalausweis (eID)"),
            DropdownOptionPreset("drivers_license", "Driver's License / Führerschein"),
            DropdownOptionPreset("residence_permit", "Residence Permit / Aufenthaltstitel"),
            DropdownOptionPreset("visa", "Visa"),
            DropdownOptionPreset("civil_cert", "Birth / Civil Certificate")
        )

        CanonicalFieldKeys.SALUTATION -> listOf(
            DropdownOptionPreset("ms", "Ms. / Frau"),
            DropdownOptionPreset("mr", "Mr. / Herr"),
            DropdownOptionPreset("mx", "Mx."),
            DropdownOptionPreset("dr", "Dr."),
            DropdownOptionPreset("prof", "Prof. Dr.")
        )

        CanonicalFieldKeys.ISSUING_COUNTRY, CanonicalFieldKeys.NATIONALITY -> listOf(
            DropdownOptionPreset("DE", "🇩🇪 Deutschland (DE)", "DE"),
            DropdownOptionPreset("AT", "🇦🇹 Österreich (AT)", "AT"),
            DropdownOptionPreset("CH", "🇨🇭 Schweiz (CH)", "CH"),
            DropdownOptionPreset("GR", "🇬🇷 Ελλάδα (GR)", "GR"),
            DropdownOptionPreset("IT", "🇮🇹 Italia (IT)", "IT"),
            DropdownOptionPreset("FR", "🇫🇷 France (FR)", "FR"),
            DropdownOptionPreset("ES", "🇪🇸 España (ES)", "ES"),
            DropdownOptionPreset("NL", "🇳🇱 Nederland (NL)", "NL"),
            DropdownOptionPreset("GB", "🇬🇧 United Kingdom (GB)", "GB"),
            DropdownOptionPreset("US", "🇺🇸 United States (US)", "US")
        )

        else -> emptyList()
    }

    fun suggestInstitutions(query: String, limit: Int = 6): List<InstitutionBrandProfile> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return InstitutionBrandRegistry.CATALOG.take(limit)
        return InstitutionBrandRegistry.CATALOG.filter { brand ->
            brand.displayName.lowercase().contains(q) ||
                brand.canonicalDomain.lowercase().contains(q) ||
                brand.keywords.any { it.contains(q) }
        }.take(limit)
    }

    /**
     * Formats phone numbers with international prefix (`+49 170 1234567`, `+49 116 116`).
     */
    fun formatInternationalPhone(raw: String, selectedDialCode: String = "+49"): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val dialDigits = selectedDialCode.filter { it.isDigit() }.ifEmpty { "49" }
        var normalized = trimmed
            .replace(Regex("[^0-9+/#* -]"), "")
            .trim()

        if (normalized.startsWith("00")) {
            normalized = "+" + normalized.substring(2).trimStart()
        } else if (normalized.startsWith("0") && !normalized.startsWith("00")) {
            // Convert national trunk '0' to international '+CC '
            val national = normalized.substring(1).filter { it.isDigit() }
            return groupPhoneDigits(dialDigits, national)
        } else if (!normalized.startsWith("+")) {
            val digitsOnly = normalized.filter { it.isDigit() }
            if (digitsOnly.isEmpty()) return trimmed
            // Special short emergency numbers like 116116
            if (digitsOnly == "116116") return "+$dialDigits 116 116"
            return groupPhoneDigits(dialDigits, digitsOnly)
        }

        // Starts with '+'
        val allDigits = normalized.substring(1).filter { it.isDigit() }
        if (allDigits.isEmpty()) return "+"
        val matchedDial = COUNTRY_DIAL_CODES
            .map { it.dialCode.removePrefix("+") }
            .sortedByDescending { it.length }
            .firstOrNull { allDigits.startsWith(it) }
            ?: allDigits.take(2)
        val subscriber = allDigits.removePrefix(matchedDial)
        return groupPhoneDigits(matchedDial, subscriber)
    }

    private fun groupPhoneDigits(ccDigits: String, subscriberDigits: String): String {
        if (subscriberDigits.isEmpty()) return "+$ccDigits"
        if (subscriberDigits == "116116") return "+$ccDigits 116 116"
        val areaLen = when {
            subscriberDigits.length <= 4 -> subscriberDigits.length
            ccDigits == "49" && subscriberDigits.startsWith("1") -> 3 // German mobile 15x/16x/17x
            ccDigits == "49" && (subscriberDigits.startsWith("30") || subscriberDigits.startsWith("40") || subscriberDigits.startsWith("69") || subscriberDigits.startsWith("89")) -> 2
            subscriberDigits.length >= 9 -> 3
            else -> 2
        }
        val area = subscriberDigits.take(areaLen)
        val rest = subscriberDigits.drop(areaLen)
        return if (rest.isEmpty()) {
            "+$ccDigits $area"
        } else {
            val groupedRest = rest.chunked(4).joinToString(" ")
            "+$ccDigits $area $groupedRest"
        }
    }

    /**
     * Formats IBAN in 4-character blocks, verifies ISO 7064 Mod-97-10, and extracts
     * German BLZ / SEPA bank details for automatic cross-field autofill.
     */
    fun analyzeAndFormatIban(raw: String): IbanAnalysisResult {
        val compact = raw.uppercase().filter { it.isLetterOrDigit() }.take(34)
        val formatted = compact.chunked(4).joinToString(" ")
        val country = compact.take(2).takeIf { it.length == 2 && it.all(Char::isLetter) }.orEmpty()
        val expectedLen = IBAN_LENGTH_BY_COUNTRY[country]
        val complete = if (expectedLen != null) compact.length == expectedLen else compact.length >= 15
        val validMod97 = complete && verifyIbanMod97(compact)

        var routing: String? = null
        var acctNum: String? = null
        var matchedBrand: InstitutionBrandProfile? = null

        if (country == "DE" && compact.length >= 12) {
            val blz = compact.substring(4, 12)
            routing = blz
            if (compact.length == 22) {
                acctNum = compact.substring(12, 22)
            }
            matchedBrand = InstitutionBrandRegistry.CATALOG.firstOrNull { brand ->
                brand.germanBlzPrefixes.any { blz.startsWith(it) }
            }
        }

        val suggestedBic = matchedBrand?.bicPrefixes?.firstOrNull()?.let { prefix ->
            if (prefix.length == 8) "${prefix}XXX" else prefix
        }
        val suggestedWeb = matchedBrand?.canonicalDomain?.let { "https://www.$it" }
        val suggestedCurrency = when (country) {
            "CH", "LI" -> "CHF"
            "GB" -> "GBP"
            "US" -> "USD"
            "SE" -> "SEK"
            "NO" -> "NOK"
            "DK" -> "DKK"
            "PL" -> "PLN"
            "CZ" -> "CZK"
            "" -> null
            else -> "EUR"
        }

        return IbanAnalysisResult(
            formattedIban = formatted,
            isCompleteLength = complete,
            isMod97Valid = validMod97,
            countryCode = country,
            extractedRoutingOrBlz = routing,
            extractedAccountNumber = acctNum,
            matchedInstitution = matchedBrand,
            suggestedBic = suggestedBic,
            suggestedWebsiteHttps = suggestedWeb,
            suggestedCurrency = suggestedCurrency
        )
    }

    private fun verifyIbanMod97(compact: String): Boolean {
        if (compact.length < 15 || !compact[0].isLetter() || !compact[1].isLetter() || !compact[2].isDigit() || !compact[3].isDigit()) {
            return false
        }
        val rearranged = compact.substring(4) + compact.substring(0, 4)
        var remainder = 0
        for (ch in rearranged) {
            val numeric = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                else -> return false
            }
            remainder = if (numeric >= 10) {
                ((remainder * 100) + numeric) % 97
            } else {
                ((remainder * 10) + numeric) % 97
            }
        }
        return remainder == 1
    }

    fun analyzeAndFormatCardNumber(raw: String): CardNumberAnalysisResult {
        val digits = raw.filter { it.isDigit() }.take(19)
        val (netCode, netLabel) = InstitutionBrandRegistry.detectPaymentNetwork(digits, "", "")
        val formatted = if (netCode == "AMEX") {
            buildString {
                digits.forEachIndexed { idx, c ->
                    if (idx == 4 || idx == 10) append(' ')
                    append(c)
                }
            }
        } else {
            digits.chunked(4).joinToString(" ")
        }
        val luhnOk = digits.length in 13..19 && verifyLuhn(digits)
        return CardNumberAnalysisResult(
            formattedPan = formatted,
            digitsCount = digits.length,
            isLuhnValid = luhnOk,
            detectedNetworkCode = netCode,
            detectedNetworkLabel = netLabel
        )
    }

    fun verifyLuhn(digits: String): Boolean {
        if (digits.length < 12 || !digits.all(Char::isDigit)) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    fun formatBicSwift(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }.take(11)

    fun isBicValid(bic: String): Boolean =
        Regex("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$").matches(bic.trim().uppercase())

    fun formatDateIsoInput(raw: String, yearMonthPreferred: Boolean = false): String {
        val cleaned = raw.trim()
        // Convert MM/YY -> 20YY-MM
        val mmYy = Regex("^(\\d{2})/(\\d{2})$").matchEntire(cleaned)
        if (mmYy != null) {
            val mm = mmYy.groupValues[1].toIntOrNull()?.coerceIn(1, 12) ?: 1
            val yy = mmYy.groupValues[2]
            return "20$yy-${mm.toString().padStart(2, '0')}"
        }
        // Convert DD.MM.YYYY -> YYYY-MM-DD
        val deDate = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$").matchEntire(cleaned)
        if (deDate != null) {
            return "${deDate.groupValues[3]}-${deDate.groupValues[2]}-${deDate.groupValues[1]}"
        }
        val digits = cleaned.filter { it.isDigit() }
        if (yearMonthPreferred && digits.length == 6 && !cleaned.contains('-')) {
            return "${digits.substring(0, 4)}-${digits.substring(4, 6)}"
        }
        if (!yearMonthPreferred && digits.length == 8 && !cleaned.contains('-')) {
            return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
        }
        return cleaned
    }

    fun normalizeHexColor(raw: String): String {
        val hexChars = raw.trim().removePrefix("#").uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
        return if (hexChars.isEmpty() && !raw.startsWith("#")) "" else "#$hexChars"
    }

    /**
     * Live keystroke auto-formatter dispatched by field key & valueType.
     */
    fun autoFormatOnChange(
        fieldKey: String,
        valueType: FieldValueType,
        rawInput: String,
        defaultDialCode: String = "+49"
    ): String {
        if (rawInput.isEmpty()) return ""
        return when {
            fieldKey == CanonicalFieldKeys.IBAN || valueType == FieldValueType.IBAN ->
                analyzeAndFormatIban(rawInput).formattedIban

            fieldKey == CanonicalFieldKeys.CARD_NUMBER || valueType == FieldValueType.CARD_NUMBER ->
                analyzeAndFormatCardNumber(rawInput).formattedPan

            fieldKey == CanonicalFieldKeys.BIC ->
                formatBicSwift(rawInput)

            valueType == FieldValueType.EMAIL || fieldKey == CanonicalFieldKeys.EMAIL ->
                rawInput.trimStart().lowercase()

            valueType == FieldValueType.PHONE || fieldKey in setOf(
                CanonicalFieldKeys.PHONE,
                CanonicalFieldKeys.SUPPORT_PHONE,
                CanonicalFieldKeys.EMERGENCY_PHONE
            ) -> {
                // Only auto-format once at least 4 digits or leading 0 / + entered so user can type naturally
                if (rawInput.count { it.isDigit() } >= 4 || rawInput.startsWith("00")) {
                    formatInternationalPhone(rawInput, defaultDialCode)
                } else {
                    rawInput
                }
            }

            else -> rawInput
        }
    }
}
