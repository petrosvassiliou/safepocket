package com.safepocket.shared.model

import kotlinx.serialization.Serializable

/**
 * SafePocket strictly excludes website logins, password generators, and browser autofill.
 * Focuses 100% on personal life admin, physical wallet cards, official IDs, and sensitive notes.
 */
@Serializable
enum class VaultCategory {
    PAYMENT_CARD,
    BANK_ACCOUNT,
    INSURANCE_CARD,
    MEMBERSHIP_CARD,
    IDENTITY,
    OFFICIAL_DOCUMENT,
    SECURE_NOTE
}

@Serializable
enum class ThemePreference {
    LIGHT,  // Default out-of-the-box design
    DARK,   // Optional dark mode
    SYSTEM
}

@Serializable
enum class VaultStorageMode {
    LOCAL_ONLY, // Device-only encrypted SQLCipher / SQLite WASM DB; zero cloud sync
    CLOUD_SYNC  // Cloud-authoritative Firebase zero-knowledge store + automatic encrypted offline mirror/outbox
}

@Serializable
enum class DocumentSubtype {
    PASSPORT,
    NATIONAL_ID,
    DRIVERS_LICENSE,
    RESIDENCE_PERMIT_VISA
}

@Serializable
enum class FieldValueType {
    TEXT,
    SENSITIVE_SECRET,   // Triggers masked UI + 30-second automatic clipboard scrub
    CARD_NUMBER,        // Triggers 4-digit group formatting + last-4 masking + 30s scrub
    IBAN,
    DATE_ISO,           // Formatted via locale-aware Intl rules; drives expiry warnings
    PHONE,
    EMAIL,
    MULTILINE_ADDRESS,
    MARKDOWN            // Rendered with copyable code/text blocks
}

/**
 * Language-agnostic canonical field keys stored in encrypted JSON payloads.
 * Switching the app language dynamically translates all standard field labels without mutating data.
 */
object CanonicalFieldKeys {
    // Generic Editor Keys
    const val CATEGORY = "field_category"
    const val TITLE = "field_title"
    const val SUBTITLE = "field_subtitle"
    const val INSTITUTION_DOMAIN = "field_institution_domain"
    const val NOTES = "field_notes"

    // Payment Cards & Bank Accounts
    const val CARDHOLDER = "field_cardholder"
    const val CARD_NUMBER = "field_card_number"
    const val CARD_NETWORK = "field_card_network"
    const val CARD_TYPE = "field_card_type"
    const val VALID_FROM = "field_valid_from"
    const val EXPIRY_DATE = "field_expiry_date"
    const val CVV = "field_cvv"
    const val PIN = "field_pin"
    const val ISSUING_BANK = "field_issuing_bank"
    const val CREDIT_LIMIT = "field_credit_limit"
    const val IBAN = "field_iban"
    const val BIC = "field_bic"
    const val BILLING_ADDRESS = "field_billing_address"
    const val ONLINE_PASSCODE = "field_online_passcode"
    const val SUPPORT_PHONE = "field_support_phone"
    const val EMERGENCY_PHONE = "field_emergency_phone"
    const val ACCOUNT_HOLDER = "field_account_holder"
    const val ACCOUNT_NUMBER = "field_account_number"
    const val ROUTING_CODE = "field_routing_code"
    const val ACCOUNT_TYPE = "field_account_type"
    const val CURRENCY = "field_currency"
    const val ONLINE_BANKING_ID = "field_online_banking_id"
    const val BRANCH_ADDRESS = "field_branch_address"

    // Insurance Cards (eGK / EHIC / PKV)
    const val INSURER_NAME = "field_insurer_name"
    const val INSURANCE_NUMBER = "field_insurance_number"
    const val INSURER_ID = "field_insurer_id"
    const val GROUP_NUMBER = "field_group_number"
    const val COVERAGE_PLAN = "field_coverage_plan"

    // Membership & Loyalty Cards
    const val MEMBER_NAME = "field_member_name"
    const val ORGANIZATION = "field_organization"
    const val MEMBERSHIP_ID = "field_membership_id"
    const val BARCODE_VALUE = "field_barcode_value"
    const val MEMBERSHIP_TIER = "field_membership_tier"
    const val MEMBER_SINCE = "field_member_since"

    // Identities (rendered with locale-aware regional labels, e.g., Steuer-ID / Codice Fiscale / ΑΦΜ)
    const val FULL_NAME = "field_full_name"
    const val SALUTATION = "field_salutation"
    const val BIRTHDATE = "field_birthdate"
    const val PLACE_OF_BIRTH = "field_place_of_birth"
    const val ADDRESS = "field_address"
    const val EMAIL = "field_email"
    const val PHONE = "field_phone"
    const val TAX_ID = "field_tax_id"
    const val SSN = "field_ssn"
    const val HEALTH_INSURANCE = "field_health_insurance"

    // Official Documents
    const val DOCUMENT_SUBTYPE = "field_document_subtype"
    const val DOCUMENT_NUMBER = "field_document_number"
    const val ISSUING_COUNTRY = "field_issuing_country"
    const val ISSUING_AUTHORITY = "field_issuing_authority"
    const val ISSUE_DATE = "field_issue_date"
    const val NATIONALITY = "field_nationality"
    const val LICENSE_CLASSES = "field_license_classes"

    // Secure Notes
    const val MARKDOWN_NOTE = "field_markdown_note"

    val SENSITIVE_CLIPBOARD_KEYS = setOf(
        CARD_NUMBER, CVV, PIN, ONLINE_PASSCODE, IBAN, ACCOUNT_NUMBER, INSURANCE_NUMBER,
        TAX_ID, SSN, HEALTH_INSURANCE, DOCUMENT_NUMBER
    )
}

data class FieldTemplateSpec(
    val key: String,
    val valueType: FieldValueType,
    val placeholder: String = ""
)

/**
 * Complete category-specific field templates shared across Android, ChromeOS, and macOS editors.
 */
object CategoryFieldTemplates {
    fun forCategory(category: VaultCategory): List<FieldTemplateSpec> = when (category) {
        VaultCategory.PAYMENT_CARD -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.CARDHOLDER, FieldValueType.TEXT, "CLARA SOFIA MÜLLER"),
            FieldTemplateSpec(CanonicalFieldKeys.CARD_NUMBER, FieldValueType.CARD_NUMBER, "4532 0192 8834 1109"),
            FieldTemplateSpec(CanonicalFieldKeys.CARD_NETWORK, FieldValueType.TEXT, "VISA / Mastercard / Amex / Girocard"),
            FieldTemplateSpec(CanonicalFieldKeys.CARD_TYPE, FieldValueType.TEXT, "Credit / Debit / Prepaid / Virtual"),
            FieldTemplateSpec(CanonicalFieldKeys.VALID_FROM, FieldValueType.DATE_ISO, "2025-11"),
            FieldTemplateSpec(CanonicalFieldKeys.EXPIRY_DATE, FieldValueType.DATE_ISO, "2029-11"),
            FieldTemplateSpec(CanonicalFieldKeys.CVV, FieldValueType.SENSITIVE_SECRET, "742"),
            FieldTemplateSpec(CanonicalFieldKeys.PIN, FieldValueType.SENSITIVE_SECRET, "1234"),
            FieldTemplateSpec(CanonicalFieldKeys.ISSUING_BANK, FieldValueType.TEXT, "Deutsche Bank"),
            FieldTemplateSpec(CanonicalFieldKeys.INSTITUTION_DOMAIN, FieldValueType.TEXT, "deutsche-bank.de"),
            FieldTemplateSpec(CanonicalFieldKeys.CREDIT_LIMIT, FieldValueType.TEXT, "5.000 EUR"),
            FieldTemplateSpec(CanonicalFieldKeys.IBAN, FieldValueType.IBAN, "DE12 1007 0000 0123 4567 00"),
            FieldTemplateSpec(CanonicalFieldKeys.BIC, FieldValueType.TEXT, "DEUTDEFFXXX"),
            FieldTemplateSpec(CanonicalFieldKeys.BILLING_ADDRESS, FieldValueType.MULTILINE_ADDRESS, "Torstraße 114, 10119 Berlin"),
            FieldTemplateSpec(CanonicalFieldKeys.ONLINE_PASSCODE, FieldValueType.SENSITIVE_SECRET, "3D Secure / PhotoTAN PIN"),
            FieldTemplateSpec(CanonicalFieldKeys.EMERGENCY_PHONE, FieldValueType.PHONE, "+49 116 116"),
            FieldTemplateSpec(CanonicalFieldKeys.SUPPORT_PHONE, FieldValueType.PHONE, "+49 69 910-10000"),
            FieldTemplateSpec(CanonicalFieldKeys.NOTES, FieldValueType.MARKDOWN, "")
        )

        VaultCategory.BANK_ACCOUNT -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.ACCOUNT_HOLDER, FieldValueType.TEXT, "Clara Sofia Müller"),
            FieldTemplateSpec(CanonicalFieldKeys.ISSUING_BANK, FieldValueType.TEXT, "Deutsche Bank / ING / Sparkasse"),
            FieldTemplateSpec(CanonicalFieldKeys.INSTITUTION_DOMAIN, FieldValueType.TEXT, "deutsche-bank.de"),
            FieldTemplateSpec(CanonicalFieldKeys.ACCOUNT_TYPE, FieldValueType.TEXT, "Girokonto / Tagesgeld / Depot"),
            FieldTemplateSpec(CanonicalFieldKeys.IBAN, FieldValueType.IBAN, "DE89 3704 0044 0532 0130 00"),
            FieldTemplateSpec(CanonicalFieldKeys.BIC, FieldValueType.TEXT, "DEUTDEFFXXX"),
            FieldTemplateSpec(CanonicalFieldKeys.ACCOUNT_NUMBER, FieldValueType.SENSITIVE_SECRET, "0532013000"),
            FieldTemplateSpec(CanonicalFieldKeys.ROUTING_CODE, FieldValueType.TEXT, "37040044"),
            FieldTemplateSpec(CanonicalFieldKeys.CURRENCY, FieldValueType.TEXT, "EUR"),
            FieldTemplateSpec(CanonicalFieldKeys.ONLINE_BANKING_ID, FieldValueType.TEXT, "Filiale / Kontonummer / Kennung"),
            FieldTemplateSpec(CanonicalFieldKeys.PIN, FieldValueType.SENSITIVE_SECRET, "Online / Telebanking PIN"),
            FieldTemplateSpec(CanonicalFieldKeys.CREDIT_LIMIT, FieldValueType.TEXT, "2.500 EUR Dispo"),
            FieldTemplateSpec(CanonicalFieldKeys.BRANCH_ADDRESS, FieldValueType.MULTILINE_ADDRESS, "Unter den Linden 13, 10117 Berlin"),
            FieldTemplateSpec(CanonicalFieldKeys.SUPPORT_PHONE, FieldValueType.PHONE, "+49 69 910-10000"),
            FieldTemplateSpec(CanonicalFieldKeys.NOTES, FieldValueType.MARKDOWN, "")
        )

        VaultCategory.INSURANCE_CARD -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.FULL_NAME, FieldValueType.TEXT, "Clara Sofia Müller"),
            FieldTemplateSpec(CanonicalFieldKeys.BIRTHDATE, FieldValueType.DATE_ISO, "1992-04-19"),
            FieldTemplateSpec(CanonicalFieldKeys.INSURER_NAME, FieldValueType.TEXT, "Techniker Krankenkasse (TK) / Allianz"),
            FieldTemplateSpec(CanonicalFieldKeys.INSTITUTION_DOMAIN, FieldValueType.TEXT, "tk.de"),
            FieldTemplateSpec(CanonicalFieldKeys.COVERAGE_PLAN, FieldValueType.TEXT, "eGK / EHIC / PKV Vollversicherung"),
            FieldTemplateSpec(CanonicalFieldKeys.INSURANCE_NUMBER, FieldValueType.SENSITIVE_SECRET, "T940281753"),
            FieldTemplateSpec(CanonicalFieldKeys.INSURER_ID, FieldValueType.TEXT, "101575519"),
            FieldTemplateSpec(CanonicalFieldKeys.GROUP_NUMBER, FieldValueType.TEXT, "Tarif / Gruppe"),
            FieldTemplateSpec(CanonicalFieldKeys.VALID_FROM, FieldValueType.DATE_ISO, "2024-01-01"),
            FieldTemplateSpec(CanonicalFieldKeys.EXPIRY_DATE, FieldValueType.DATE_ISO, "2029-12-31"),
            FieldTemplateSpec(CanonicalFieldKeys.EMERGENCY_PHONE, FieldValueType.PHONE, "+49 40 4606 6191 00"),
            FieldTemplateSpec(CanonicalFieldKeys.SUPPORT_PHONE, FieldValueType.PHONE, "+49 800 285 85 85"),
            FieldTemplateSpec(CanonicalFieldKeys.NOTES, FieldValueType.MARKDOWN, "")
        )

        VaultCategory.MEMBERSHIP_CARD -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.ORGANIZATION, FieldValueType.TEXT, "ADAC / BahnCard / Miles & More"),
            FieldTemplateSpec(CanonicalFieldKeys.INSTITUTION_DOMAIN, FieldValueType.TEXT, "adac.de"),
            FieldTemplateSpec(CanonicalFieldKeys.MEMBER_NAME, FieldValueType.TEXT, "Clara Sofia Müller"),
            FieldTemplateSpec(CanonicalFieldKeys.MEMBERSHIP_ID, FieldValueType.TEXT, "7081 4920 1184"),
            FieldTemplateSpec(CanonicalFieldKeys.BARCODE_VALUE, FieldValueType.TEXT, "708149201184"),
            FieldTemplateSpec(CanonicalFieldKeys.MEMBERSHIP_TIER, FieldValueType.TEXT, "Plus / Gold / BahnCard 50"),
            FieldTemplateSpec(CanonicalFieldKeys.MEMBER_SINCE, FieldValueType.DATE_ISO, "2020-05"),
            FieldTemplateSpec(CanonicalFieldKeys.EXPIRY_DATE, FieldValueType.DATE_ISO, "2027-09-30"),
            FieldTemplateSpec(CanonicalFieldKeys.PIN, FieldValueType.SENSITIVE_SECRET, "PIN / Kundenpasswort"),
            FieldTemplateSpec(CanonicalFieldKeys.SUPPORT_PHONE, FieldValueType.PHONE, "+49 89 76 76 70"),
            FieldTemplateSpec(CanonicalFieldKeys.NOTES, FieldValueType.MARKDOWN, "")
        )

        VaultCategory.IDENTITY -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.SALUTATION, FieldValueType.TEXT, "Dr. / Ms. / Mr."),
            FieldTemplateSpec(CanonicalFieldKeys.FULL_NAME, FieldValueType.TEXT, "Clara Sofia Müller"),
            FieldTemplateSpec(CanonicalFieldKeys.BIRTHDATE, FieldValueType.DATE_ISO, "1992-04-19"),
            FieldTemplateSpec(CanonicalFieldKeys.PLACE_OF_BIRTH, FieldValueType.TEXT, "Berlin"),
            FieldTemplateSpec(CanonicalFieldKeys.NATIONALITY, FieldValueType.TEXT, "Deutsch / DE"),
            FieldTemplateSpec(CanonicalFieldKeys.TAX_ID, FieldValueType.SENSITIVE_SECRET, "65 928 417 039"),
            FieldTemplateSpec(CanonicalFieldKeys.SSN, FieldValueType.SENSITIVE_SECRET, "Sozialversicherungsnummer"),
            FieldTemplateSpec(CanonicalFieldKeys.HEALTH_INSURANCE, FieldValueType.SENSITIVE_SECRET, "Krankenversichertennummer"),
            FieldTemplateSpec(CanonicalFieldKeys.ADDRESS, FieldValueType.MULTILINE_ADDRESS, "Torstraße 114, 10119 Berlin"),
            FieldTemplateSpec(CanonicalFieldKeys.EMAIL, FieldValueType.EMAIL, "clara@domain.eu"),
            FieldTemplateSpec(CanonicalFieldKeys.PHONE, FieldValueType.PHONE, "+49 170 0000000"),
            FieldTemplateSpec(CanonicalFieldKeys.ORGANIZATION, FieldValueType.TEXT, "Unternehmen / Arbeitgeber"),
            FieldTemplateSpec(CanonicalFieldKeys.EMERGENCY_PHONE, FieldValueType.PHONE, "Notfallkontakt"),
            FieldTemplateSpec(CanonicalFieldKeys.NOTES, FieldValueType.MARKDOWN, "")
        )

        VaultCategory.OFFICIAL_DOCUMENT -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.DOCUMENT_SUBTYPE, FieldValueType.TEXT, "Passport / Personalausweis / Führerschein"),
            FieldTemplateSpec(CanonicalFieldKeys.FULL_NAME, FieldValueType.TEXT, "Clara Sofia Müller"),
            FieldTemplateSpec(CanonicalFieldKeys.DOCUMENT_NUMBER, FieldValueType.SENSITIVE_SECRET, "C01X00T47"),
            FieldTemplateSpec(CanonicalFieldKeys.ISSUING_COUNTRY, FieldValueType.TEXT, "DE / Deutschland"),
            FieldTemplateSpec(CanonicalFieldKeys.NATIONALITY, FieldValueType.TEXT, "DEU"),
            FieldTemplateSpec(CanonicalFieldKeys.BIRTHDATE, FieldValueType.DATE_ISO, "1992-04-19"),
            FieldTemplateSpec(CanonicalFieldKeys.PLACE_OF_BIRTH, FieldValueType.TEXT, "Berlin"),
            FieldTemplateSpec(CanonicalFieldKeys.ISSUE_DATE, FieldValueType.DATE_ISO, "2021-06-15"),
            FieldTemplateSpec(CanonicalFieldKeys.EXPIRY_DATE, FieldValueType.DATE_ISO, "2031-06-14"),
            FieldTemplateSpec(CanonicalFieldKeys.ISSUING_AUTHORITY, FieldValueType.TEXT, "Landesamt für Bürger- und Ordnungsangelegenheiten Berlin"),
            FieldTemplateSpec(CanonicalFieldKeys.PIN, FieldValueType.SENSITIVE_SECRET, "eID PIN / 6-stellige CAN"),
            FieldTemplateSpec(CanonicalFieldKeys.LICENSE_CLASSES, FieldValueType.TEXT, "B, BE, AM, L"),
            FieldTemplateSpec(CanonicalFieldKeys.NOTES, FieldValueType.MARKDOWN, "")
        )

        VaultCategory.SECURE_NOTE -> listOf(
            FieldTemplateSpec(CanonicalFieldKeys.ORGANIZATION, FieldValueType.TEXT, "Institut / Thema (optional)"),
            FieldTemplateSpec(CanonicalFieldKeys.INSTITUTION_DOMAIN, FieldValueType.TEXT, "finanzamt.de"),
            FieldTemplateSpec(CanonicalFieldKeys.DOCUMENT_NUMBER, FieldValueType.TEXT, "Aktenzeichen / Referenz"),
            FieldTemplateSpec(CanonicalFieldKeys.PIN, FieldValueType.SENSITIVE_SECRET, "Geheimcode / Recovery-PIN"),
            FieldTemplateSpec(CanonicalFieldKeys.MARKDOWN_NOTE, FieldValueType.MARKDOWN, "")
        )
    }
}

@Serializable
data class VaultField(
    val id: String,
    val labelKey: String,           // Either CanonicalFieldKeys.* or custom user label
    val isCustomLabel: Boolean = false,
    val value: String,
    val valueType: FieldValueType = FieldValueType.TEXT,
    val isSensitive: Boolean = valueType == FieldValueType.SENSITIVE_SECRET ||
        labelKey in CanonicalFieldKeys.SENSITIVE_CLIPBOARD_KEYS
)

@Serializable
data class EncryptedAttachmentRef(
    val id: String,
    val fileName: String,
    val mimeType: String,           // application/pdf, image/png, image/jpeg, image/webp
    val side: String? = null,       // "front" | "back" | null
    val sizeBytes: Long,
    val sha256Hex: String
)

object CardPalettes {
    val ALL = listOf(
        "obsidian_slate",
        "alpine_emerald",
        "aegean_cobalt",
        "terracotta_gold",
        "crimson_ruby",
        "midnight_navy",
        "arctic_cyan",
        "forest_sage",
        "sunset_coral",
        "espresso_bronze",
        "titanium_silver",
        "pearl_ivory"
    )
}

@Serializable
enum class BarcodeFormat {
    CODE_128,
    EAN_13,
    QR_CODE
}

@Serializable
enum class VaultSortMode {
    PINNED_RECENT,
    ALPHABETICAL,
    EXPIRING_SOON,
    UPDATED_DESC
}

@Serializable
data class CardVisualTheme(
    val paletteId: String = "obsidian_slate", // Any ID in CardPalettes.ALL or "custom"
    val customColorHex: String? = null,       // #RRGGBB user-picked or auto-detected brand color
    val showVisualCard: Boolean = true,       // Default true for cards, official docs & identities
    val barcodeFormat: BarcodeFormat? = null,
    val networkBrand: String? = null,         // VISA | Mastercard | Amex | Girocard | SEPA | eGK | Passport
    val institutionId: String? = null,        // e.g. "deutsche_bank", "sparkasse", "tk", "adac"
    val institutionName: String? = null,      // e.g. "Deutsche Bank"
    val institutionDomain: String? = null,    // e.g. "deutsche-bank.de"
    val institutionLogoDataUri: String? = null,// Cached downloaded PNG/SVG data URI (encrypted in item)
    val autoBrandColor: Boolean = true        // Automatically apply institution color unless overridden
)

@Serializable
data class VaultItemPayload(
    val schemaVersion: Int = 1,
    val id: String,
    val category: VaultCategory,
    val title: String,
    val subtitle: String = "",
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val isSampleItem: Boolean = false,
    val lastAccessedAtEpochMs: Long? = null,
    val tags: List<String> = emptyList(),
    val regionHint: String? = null, // Optional ISO country override ("DE", "IT", "GR", "FR", "ES")
    val cardTheme: CardVisualTheme? = null,
    val documentSubtype: DocumentSubtype? = null,
    val fields: List<VaultField> = emptyList(),
    val attachments: List<EncryptedAttachmentRef> = emptyList(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    fun expiryIsoDate(): String? =
        fields.firstOrNull { it.labelKey == CanonicalFieldKeys.EXPIRY_DATE }?.value?.takeIf { it.isNotBlank() }

    fun fieldValue(key: String): String =
        fields.firstOrNull { !it.isCustomLabel && it.labelKey == key }?.value.orEmpty()

    fun isBuiltInSample(): Boolean =
        isSampleItem || id in SampleVaultCatalogIds.ALL_KNOWN_SAMPLE_IDS

    fun primaryCopyableField(): VaultField? {
        val priorityKeys = when (category) {
            VaultCategory.PAYMENT_CARD -> listOf(CanonicalFieldKeys.CARD_NUMBER, CanonicalFieldKeys.IBAN, CanonicalFieldKeys.CVV)
            VaultCategory.BANK_ACCOUNT -> listOf(CanonicalFieldKeys.IBAN, CanonicalFieldKeys.ACCOUNT_NUMBER, CanonicalFieldKeys.BIC)
            VaultCategory.INSURANCE_CARD -> listOf(CanonicalFieldKeys.INSURANCE_NUMBER, CanonicalFieldKeys.INSURER_ID)
            VaultCategory.MEMBERSHIP_CARD -> listOf(CanonicalFieldKeys.MEMBERSHIP_ID, CanonicalFieldKeys.BARCODE_VALUE)
            VaultCategory.IDENTITY -> listOf(CanonicalFieldKeys.TAX_ID, CanonicalFieldKeys.SSN, CanonicalFieldKeys.EMAIL, CanonicalFieldKeys.PHONE)
            VaultCategory.OFFICIAL_DOCUMENT -> listOf(CanonicalFieldKeys.DOCUMENT_NUMBER, CanonicalFieldKeys.PIN)
            VaultCategory.SECURE_NOTE -> listOf(CanonicalFieldKeys.MARKDOWN_NOTE, CanonicalFieldKeys.PIN, CanonicalFieldKeys.DOCUMENT_NUMBER)
        }
        for (key in priorityKeys) {
            val found = fields.firstOrNull { !it.isCustomLabel && it.labelKey == key && it.value.isNotBlank() }
            if (found != null) return found
        }
        return fields.firstOrNull { it.value.isNotBlank() }
    }
}

object SampleVaultCatalogIds {
    val ALL_KNOWN_SAMPLE_IDS = setOf(
        "item-card-db-visa",
        "item-card-n26-metal",
        "item-card-alpine-metal",
        "item-bank-ing-giro",
        "item-bank-sepa-main",
        "item-ins-tk-egk",
        "item-ins-ehic-eu",
        "item-mem-bahncard",
        "item-mem-adac-plus",
        "item-identity-primary",
        "item-id-clara",
        "item-id-eu-citizen",
        "item-doc-hellenic-passport",
        "item-doc-cie-italy",
        "item-doc-passport-de",
        "item-doc-passport-eu",
        "item-note-home-safe",
        "item-note-emergency",
        "item-note-emergency-kit",
        "item-note-vault-codes"
    )
}

enum class ExpirySeverity {
    NONE,
    VALID,
    EXPIRING_SOON, // <= warningWindowDays (default 90d)
    CRITICAL_SOON, // <= 30 days remaining
    EXPIRED
}

data class ExpiryStatus(
    val severity: ExpirySeverity,
    val daysDelta: Long
)
