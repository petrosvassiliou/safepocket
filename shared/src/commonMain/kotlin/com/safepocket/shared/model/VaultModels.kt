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
    // Payment Cards & Bank Accounts
    const val CARDHOLDER = "field_cardholder"
    const val CARD_NUMBER = "field_card_number"
    const val EXPIRY_DATE = "field_expiry_date"
    const val CVV = "field_cvv"
    const val PIN = "field_pin"
    const val ISSUING_BANK = "field_issuing_bank"
    const val IBAN = "field_iban"
    const val BIC = "field_bic"
    const val SUPPORT_PHONE = "field_support_phone"
    const val ACCOUNT_HOLDER = "field_account_holder"
    const val ACCOUNT_NUMBER = "field_account_number"
    const val ROUTING_CODE = "field_routing_code"
    const val ACCOUNT_TYPE = "field_account_type"

    // Insurance Cards (eGK / EHIC / PKV)
    const val INSURER_NAME = "field_insurer_name"
    const val INSURANCE_NUMBER = "field_insurance_number"
    const val INSURER_ID = "field_insurer_id"
    const val COVERAGE_PLAN = "field_coverage_plan"

    // Membership & Loyalty Cards
    const val MEMBER_NAME = "field_member_name"
    const val ORGANIZATION = "field_organization"
    const val MEMBERSHIP_ID = "field_membership_id"
    const val MEMBERSHIP_TIER = "field_membership_tier"

    // Identities (rendered with locale-aware regional labels, e.g., Steuer-ID / Codice Fiscale / ΑΦΜ)
    const val FULL_NAME = "field_full_name"
    const val BIRTHDATE = "field_birthdate"
    const val ADDRESS = "field_address"
    const val EMAIL = "field_email"
    const val PHONE = "field_phone"
    const val TAX_ID = "field_tax_id"
    const val SSN = "field_ssn"
    const val HEALTH_INSURANCE = "field_health_insurance"

    // Official Documents
    const val DOCUMENT_SUBTYPE = "field_document_subtype"
    const val DOCUMENT_NUMBER = "field_document_number"
    const val ISSUING_AUTHORITY = "field_issuing_authority"
    const val ISSUE_DATE = "field_issue_date"
    const val NATIONALITY = "field_nationality"

    // Secure Notes
    const val MARKDOWN_NOTE = "field_markdown_note"

    val SENSITIVE_CLIPBOARD_KEYS = setOf(
        CARD_NUMBER, CVV, PIN, IBAN, ACCOUNT_NUMBER, INSURANCE_NUMBER,
        TAX_ID, SSN, HEALTH_INSURANCE, DOCUMENT_NUMBER
    )
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

@Serializable
data class CardVisualTheme(
    val paletteId: String = "obsidian_slate", // obsidian_slate | alpine_emerald | aegean_cobalt | terracotta_gold
    val networkBrand: String? = null          // Visa | Mastercard | Amex | SEPA
)

@Serializable
data class VaultItemPayload(
    val schemaVersion: Int = 1,
    val id: String,
    val category: VaultCategory,
    val title: String,
    val subtitle: String = "",
    val favorite: Boolean = false,
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
}

enum class ExpirySeverity {
    NONE,
    VALID,
    EXPIRING_SOON, // <= 90 days remaining
    EXPIRED
}

data class ExpiryStatus(
    val severity: ExpirySeverity,
    val daysDelta: Long
)
