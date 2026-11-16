package com.safepocket.android.data

import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provides initial multilingual sample vault items across all 7 SafePocket categories
 * (with v0.2 Dual-Brand Institution + Network logos & complete category fields)
 * and JSON serialization helpers for local migration/persistence.
 */
object AndroidSampleVault {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeItems(items: List<VaultItemPayload>): String = json.encodeToString(items)

    fun decodeItemsOrDefault(raw: String?): List<VaultItemPayload> {
        if (raw.isNullOrBlank()) return createSampleItems()
        return runCatching {
            json.decodeFromString<List<VaultItemPayload>>(raw)
                .map { InstitutionBrandRegistry.applyAutoBranding(it) }
        }.getOrElse { createSampleItems() }
    }

    fun createSampleItems(): List<VaultItemPayload> {
        val now = 1763320000000L
        return listOf(
            VaultItemPayload(
                id = "item-card-db-visa",
                category = VaultCategory.PAYMENT_CARD,
                title = "Deutsche Bank VISA Credit Card",
                subtitle = "•••• 1109 • Deutsche Bank",
                favorite = true,
                pinned = true,
                tags = listOf("banking", "visa", "travel"),
                cardTheme = CardVisualTheme(
                    paletteId = "custom",
                    customColorHex = "#0018A8",
                    networkBrand = "VISA",
                    institutionId = "deutsche_bank",
                    institutionName = "Deutsche Bank",
                    institutionDomain = "deutsche-bank.de",
                    autoBrandColor = true
                ),
                fields = listOf(
                    VaultField("db1", CanonicalFieldKeys.CARDHOLDER, value = "Clara Sofia Müller"),
                    VaultField("db2", CanonicalFieldKeys.CARD_NUMBER, value = "4532 0192 8834 1109", valueType = FieldValueType.CARD_NUMBER, isSensitive = true),
                    VaultField("db3", CanonicalFieldKeys.CARD_NETWORK, value = "VISA"),
                    VaultField("db4", CanonicalFieldKeys.CARD_TYPE, value = "Credit Card (Gold)"),
                    VaultField("db5", CanonicalFieldKeys.VALID_FROM, value = "2025-11", valueType = FieldValueType.DATE_ISO),
                    VaultField("db6", CanonicalFieldKeys.EXPIRY_DATE, value = "2029-11", valueType = FieldValueType.DATE_ISO),
                    VaultField("db7", CanonicalFieldKeys.CVV, value = "742", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("db8", CanonicalFieldKeys.PIN, value = "4819", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("db9", CanonicalFieldKeys.ISSUING_BANK, value = "Deutsche Bank AG"),
                    VaultField("db10", CanonicalFieldKeys.INSTITUTION_DOMAIN, value = "deutsche-bank.de"),
                    VaultField("db11", CanonicalFieldKeys.CREDIT_LIMIT, value = "8.500 EUR"),
                    VaultField("db12", CanonicalFieldKeys.IBAN, value = "DE12 1007 0000 0532 0190 00", valueType = FieldValueType.IBAN, isSensitive = true),
                    VaultField("db13", CanonicalFieldKeys.BIC, value = "DEUTDEFFXXX"),
                    VaultField("db14", CanonicalFieldKeys.BILLING_ADDRESS, value = "Torstraße 114, 10119 Berlin", valueType = FieldValueType.MULTILINE_ADDRESS),
                    VaultField("db15", CanonicalFieldKeys.EMERGENCY_PHONE, value = "+49 116 116 / +49 69 910-10000", valueType = FieldValueType.PHONE)
                ),
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            VaultItemPayload(
                id = "item-card-n26-metal",
                category = VaultCategory.PAYMENT_CARD,
                title = "N26 Metal Mastercard",
                subtitle = "•••• 7804 • N26 Bank",
                favorite = true,
                tags = listOf("personal", "mastercard"),
                cardTheme = CardVisualTheme(
                    paletteId = "custom",
                    customColorHex = "#1A7F72",
                    networkBrand = "Mastercard",
                    institutionId = "n26",
                    institutionName = "N26",
                    institutionDomain = "n26.com"
                ),
                fields = listOf(
                    VaultField("f1", CanonicalFieldKeys.CARDHOLDER, value = "Clara Sofia Müller"),
                    VaultField("f2", CanonicalFieldKeys.CARD_NUMBER, value = "5355 8910 4421 7804", valueType = FieldValueType.CARD_NUMBER, isSensitive = true),
                    VaultField("f2b", CanonicalFieldKeys.CARD_NETWORK, value = "Mastercard"),
                    VaultField("f2c", CanonicalFieldKeys.CARD_TYPE, value = "Debit Metal"),
                    VaultField("f3", CanonicalFieldKeys.EXPIRY_DATE, value = "2028-09", valueType = FieldValueType.DATE_ISO),
                    VaultField("f4", CanonicalFieldKeys.CVV, value = "842", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("f4b", CanonicalFieldKeys.PIN, value = "2904", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("f5", CanonicalFieldKeys.ISSUING_BANK, value = "N26 Bank SE"),
                    VaultField("f6", CanonicalFieldKeys.INSTITUTION_DOMAIN, value = "n26.com")
                ),
                createdAtEpochMs = now - 5_000,
                updatedAtEpochMs = now - 5_000
            ),
            VaultItemPayload(
                id = "item-bank-ing-giro",
                category = VaultCategory.BANK_ACCOUNT,
                title = "ING Girokonto",
                subtitle = "DE89 •••• 0130 00 • SEPA",
                favorite = true,
                tags = listOf("banking", "sepa"),
                cardTheme = CardVisualTheme(
                    paletteId = "custom",
                    customColorHex = "#FF6200",
                    networkBrand = "SEPA",
                    institutionId = "ing",
                    institutionName = "ING",
                    institutionDomain = "ing.de"
                ),
                fields = listOf(
                    VaultField("b1", CanonicalFieldKeys.ACCOUNT_HOLDER, value = "Clara Sofia Müller"),
                    VaultField("b4", CanonicalFieldKeys.ISSUING_BANK, value = "ING-DiBa AG"),
                    VaultField("b4d", CanonicalFieldKeys.INSTITUTION_DOMAIN, value = "ing.de"),
                    VaultField("b4t", CanonicalFieldKeys.ACCOUNT_TYPE, value = "Girokonto"),
                    VaultField("b2", CanonicalFieldKeys.IBAN, value = "DE89 5001 0517 0532 0130 00", valueType = FieldValueType.IBAN, isSensitive = true),
                    VaultField("b3", CanonicalFieldKeys.BIC, value = "INGDDEFFXXX"),
                    VaultField("b5", CanonicalFieldKeys.CURRENCY, value = "EUR")
                ),
                createdAtEpochMs = now - 10_000,
                updatedAtEpochMs = now - 10_000
            ),
            VaultItemPayload(
                id = "item-ins-tk-egk",
                category = VaultCategory.INSURANCE_CARD,
                title = "TK Gesundheitskarte (eGK)",
                subtitle = "Techniker Krankenkasse • EHIC",
                tags = listOf("health", "eu"),
                cardTheme = CardVisualTheme(
                    paletteId = "custom",
                    customColorHex = "#009FE3",
                    networkBrand = "EHIC",
                    institutionId = "tk",
                    institutionName = "Techniker Krankenkasse",
                    institutionDomain = "tk.de"
                ),
                fields = listOf(
                    VaultField("i0", CanonicalFieldKeys.FULL_NAME, value = "Clara Sofia Müller"),
                    VaultField("i1", CanonicalFieldKeys.INSURER_NAME, value = "Techniker Krankenkasse (TK)"),
                    VaultField("i1d", CanonicalFieldKeys.INSTITUTION_DOMAIN, value = "tk.de"),
                    VaultField("i1p", CanonicalFieldKeys.COVERAGE_PLAN, value = "GKV eGK + European Health Insurance Card (EHIC)"),
                    VaultField("i2", CanonicalFieldKeys.INSURANCE_NUMBER, value = "T940281753", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("i3", CanonicalFieldKeys.INSURER_ID, value = "101575519"),
                    VaultField("i4", CanonicalFieldKeys.EXPIRY_DATE, value = "2029-12-31", valueType = FieldValueType.DATE_ISO)
                ),
                createdAtEpochMs = now - 20_000,
                updatedAtEpochMs = now - 20_000
            ),
            VaultItemPayload(
                id = "item-mem-adac-plus",
                category = VaultCategory.MEMBERSHIP_CARD,
                title = "ADAC Plus Mitgliedskarte",
                subtitle = "Europe Roadside Assistance",
                tags = listOf("mobility", "club"),
                cardTheme = CardVisualTheme(
                    paletteId = "custom",
                    customColorHex = "#212121",
                    barcodeFormat = BarcodeFormat.CODE_128,
                    networkBrand = "PLUS EUROPA",
                    institutionId = "adac",
                    institutionName = "ADAC",
                    institutionDomain = "adac.de"
                ),
                fields = listOf(
                    VaultField("m2", CanonicalFieldKeys.ORGANIZATION, value = "ADAC e.V."),
                    VaultField("m2d", CanonicalFieldKeys.INSTITUTION_DOMAIN, value = "adac.de"),
                    VaultField("m1", CanonicalFieldKeys.MEMBER_NAME, value = "Clara Sofia Müller"),
                    VaultField("m3", CanonicalFieldKeys.MEMBERSHIP_ID, value = "ADAC-70814920"),
                    VaultField("m3b", CanonicalFieldKeys.BARCODE_VALUE, value = "70814920"),
                    VaultField("m4", CanonicalFieldKeys.MEMBERSHIP_TIER, value = "Plus Europa"),
                    VaultField("m5", CanonicalFieldKeys.SUPPORT_PHONE, value = "+49 89 20 20 4000", valueType = FieldValueType.PHONE)
                ),
                createdAtEpochMs = now - 30_000,
                updatedAtEpochMs = now - 30_000
            ),
            VaultItemPayload(
                id = "item-identity-primary",
                category = VaultCategory.IDENTITY,
                title = "Clara Sofia Müller",
                subtitle = "Berlin, DE • Primary Identity",
                favorite = true,
                tags = listOf("identity", "statutory"),
                cardTheme = CardVisualTheme(
                    paletteId = "alpine_emerald",
                    showVisualCard = true,
                    institutionId = "gov_de",
                    institutionName = "Bundesrepublik Deutschland",
                    networkBrand = "DE"
                ),
                fields = listOf(
                    VaultField("id1", CanonicalFieldKeys.FULL_NAME, value = "Clara Sofia Müller"),
                    VaultField("id2", CanonicalFieldKeys.BIRTHDATE, value = "1992-04-19", valueType = FieldValueType.DATE_ISO),
                    VaultField("id2b", CanonicalFieldKeys.PLACE_OF_BIRTH, value = "Berlin"),
                    VaultField("id2c", CanonicalFieldKeys.NATIONALITY, value = "DE"),
                    VaultField("id3", CanonicalFieldKeys.TAX_ID, value = "65 928 417 039", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("id4", CanonicalFieldKeys.EMAIL, value = "clara.mueller@example.eu", valueType = FieldValueType.EMAIL),
                    VaultField("id5", CanonicalFieldKeys.ADDRESS, value = "Torstraße 114, 10119 Berlin", valueType = FieldValueType.MULTILINE_ADDRESS)
                ),
                createdAtEpochMs = now - 40_000,
                updatedAtEpochMs = now - 40_000
            ),
            VaultItemPayload(
                id = "item-doc-hellenic-passport",
                category = VaultCategory.OFFICIAL_DOCUMENT,
                title = "Ελληνικό Διαβατήριο (Passport)",
                subtitle = "ΑΤ8492017 • Αθήνα",
                documentSubtype = DocumentSubtype.PASSPORT,
                tags = listOf("travel", "passport"),
                cardTheme = CardVisualTheme(
                    paletteId = "custom",
                    customColorHex = "#0D3B66",
                    institutionId = "gov_gr",
                    institutionName = "Ελληνική Δημοκρατία",
                    networkBrand = "PASSPORT"
                ),
                fields = listOf(
                    VaultField("d0", CanonicalFieldKeys.DOCUMENT_SUBTYPE, value = "Passport"),
                    VaultField("d1", CanonicalFieldKeys.DOCUMENT_NUMBER, value = "ΑΤ8492017", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("d2", CanonicalFieldKeys.ISSUING_AUTHORITY, value = "Αρχηγείο Ελληνικής Αστυνομίας"),
                    VaultField("d2c", CanonicalFieldKeys.ISSUING_COUNTRY, value = "GR"),
                    VaultField("d3", CanonicalFieldKeys.NATIONALITY, value = "GRC / DEU"),
                    VaultField("d4", CanonicalFieldKeys.EXPIRY_DATE, value = "2027-01-25", valueType = FieldValueType.DATE_ISO)
                ),
                createdAtEpochMs = now - 50_000,
                updatedAtEpochMs = now - 50_000
            ),
            VaultItemPayload(
                id = "item-note-home-safe",
                category = VaultCategory.SECURE_NOTE,
                title = "Home Safe & Emergency Contacts",
                subtitle = "Encrypted Markdown Note",
                tags = listOf("home", "emergency"),
                fields = listOf(
                    VaultField(
                        "n1",
                        CanonicalFieldKeys.MARKDOWN_NOTE,
                        value = "Safe Combination: 48-19-73\nNotary Reference: MUC-2026-8841",
                        valueType = FieldValueType.SENSITIVE_SECRET,
                        isSensitive = true
                    )
                ),
                createdAtEpochMs = now - 60_000,
                updatedAtEpochMs = now - 60_000
            )
        ).map { InstitutionBrandRegistry.applyAutoBranding(it) }
    }
}
