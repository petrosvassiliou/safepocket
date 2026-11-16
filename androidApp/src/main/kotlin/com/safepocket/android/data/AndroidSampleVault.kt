package com.safepocket.android.data

import com.safepocket.shared.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provides initial multilingual sample vault items across all 7 SafePocket categories
 * and JSON serialization helpers for local Android persistence.
 */
object AndroidSampleVault {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeItems(items: List<VaultItemPayload>): String = json.encodeToString(items)

    fun decodeItemsOrDefault(raw: String?): List<VaultItemPayload> {
        if (raw.isNullOrBlank()) return createSampleItems()
        return runCatching { json.decodeFromString<List<VaultItemPayload>>(raw) }
            .getOrElse { createSampleItems() }
    }

    fun createSampleItems(): List<VaultItemPayload> {
        val now = 1763294400000L
        return listOf(
            VaultItemPayload(
                id = "item-card-n26-metal",
                category = VaultCategory.PAYMENT_CARD,
                title = "N26 Metal Mastercard",
                subtitle = "•••• 7804 • N26 Bank",
                favorite = true,
                pinned = true,
                tags = listOf("personal", "travel"),
                cardTheme = CardVisualTheme(paletteId = "obsidian_slate", networkBrand = "Mastercard"),
                fields = listOf(
                    VaultField("f1", CanonicalFieldKeys.CARDHOLDER, value = "Clara Sofia Müller"),
                    VaultField("f2", CanonicalFieldKeys.CARD_NUMBER, value = "5355 8910 4421 7804", valueType = FieldValueType.CARD_NUMBER, isSensitive = true),
                    VaultField("f3", CanonicalFieldKeys.EXPIRY_DATE, value = "2028-09", valueType = FieldValueType.DATE_ISO),
                    VaultField("f4", CanonicalFieldKeys.CVV, value = "842", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("f5", CanonicalFieldKeys.ISSUING_BANK, value = "N26 Bank SE")
                ),
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            ),
            VaultItemPayload(
                id = "item-bank-ing-giro",
                category = VaultCategory.BANK_ACCOUNT,
                title = "ING Girokonto",
                subtitle = "DE89 •••• 0130 00 • SEPA",
                favorite = true,
                tags = listOf("banking", "sepa"),
                cardTheme = CardVisualTheme(paletteId = "aegean_cobalt", networkBrand = "SEPA"),
                fields = listOf(
                    VaultField("b1", CanonicalFieldKeys.ACCOUNT_HOLDER, value = "Clara Sofia Müller"),
                    VaultField("b2", CanonicalFieldKeys.IBAN, value = "DE89 3704 0044 0532 0130 00", valueType = FieldValueType.IBAN, isSensitive = true),
                    VaultField("b3", CanonicalFieldKeys.BIC, value = "INGDDEFFXXX"),
                    VaultField("b4", CanonicalFieldKeys.ISSUING_BANK, value = "ING-DiBa AG")
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
                cardTheme = CardVisualTheme(paletteId = "arctic_cyan"),
                fields = listOf(
                    VaultField("i1", CanonicalFieldKeys.INSURER_NAME, value = "Techniker Krankenkasse (TK)"),
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
                cardTheme = CardVisualTheme(paletteId = "terracotta_gold", barcodeFormat = BarcodeFormat.CODE_128),
                fields = listOf(
                    VaultField("m1", CanonicalFieldKeys.MEMBER_NAME, value = "Clara Sofia Müller"),
                    VaultField("m2", CanonicalFieldKeys.ORGANIZATION, value = "ADAC e.V."),
                    VaultField("m3", CanonicalFieldKeys.MEMBERSHIP_ID, value = "ADAC-70814920"),
                    VaultField("m4", CanonicalFieldKeys.MEMBERSHIP_TIER, value = "Plus Europa")
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
                cardTheme = CardVisualTheme(paletteId = "alpine_emerald", showVisualCard = true),
                fields = listOf(
                    VaultField("id1", CanonicalFieldKeys.FULL_NAME, value = "Clara Sofia Müller"),
                    VaultField("id2", CanonicalFieldKeys.BIRTHDATE, value = "1992-04-19", valueType = FieldValueType.DATE_ISO),
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
                cardTheme = CardVisualTheme(paletteId = "crimson_ruby"),
                fields = listOf(
                    VaultField("d1", CanonicalFieldKeys.DOCUMENT_NUMBER, value = "ΑΤ8492017", valueType = FieldValueType.SENSITIVE_SECRET, isSensitive = true),
                    VaultField("d2", CanonicalFieldKeys.ISSUING_AUTHORITY, value = "Αρχηγείο Ελληνικής Αστυνομίας"),
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
        )
    }
}
