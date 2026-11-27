import Foundation
import SwiftUI

// MARK: - 6-Language Localization Engine (Loads Bundled i18n/*.arb.json Catalogs)

enum MacSupportedLocale: String, CaseIterable, Identifiable {
    case en = "en"
    case de = "de"
    case it = "it"
    case fr = "fr"
    case es = "es"
    case el = "el"

    var id: String { rawValue }

    var nativeDisplayName: String {
        switch self {
        case .en: return "English"
        case .de: return "Deutsch"
        case .it: return "Italiano"
        case .fr: return "Français"
        case .es: return "Español"
        case .el: return "Ελληνικά"
        }
    }

    var flagEmoji: String {
        switch self {
        case .en: return "🇬🇧"
        case .de: return "🇩🇪"
        case .it: return "🇮🇹"
        case .fr: return "🇫🇷"
        case .es: return "🇪🇸"
        case .el: return "🇬🇷"
        }
    }
}

final class MacLocalizationEngine {
    private var catalogs: [MacSupportedLocale: [String: String]] = [:]

    init() {
        for loc in MacSupportedLocale.allCases {
            catalogs[loc] = Self.loadCatalog(for: loc.rawValue)
        }
    }

    static var detectedSystemLocale: MacSupportedLocale {
        let code = (Locale.preferredLanguages.first ?? Locale.current.identifier)
            .prefix(2)
            .lowercased()
        return MacSupportedLocale(rawValue: code) ?? .en
    }

    func resolveActiveLocale(overrideCode: String) -> MacSupportedLocale {
        if overrideCode == "__system__" || overrideCode.isEmpty {
            return Self.detectedSystemLocale
        }
        return MacSupportedLocale(rawValue: overrideCode) ?? Self.detectedSystemLocale
    }

    func hasKey(_ key: String) -> Bool {
        catalogs[.en]?[key] != nil || Self.builtInFallback[key] != nil
    }

    func t(_ key: String, localeCode: String, params: [String: String] = [:]) -> String {
        let active = resolveActiveLocale(overrideCode: localeCode)
        let raw = catalogs[active]?[key]
            ?? catalogs[.en]?[key]
            ?? Self.builtInFallback[key]
            ?? key
        guard !params.isEmpty else { return raw }
        return params.reduce(raw) { acc, pair in
            acc.replacingOccurrences(of: "{\(pair.key)}", with: pair.value)
        }
    }

    func resolveCategorySingularName(category: String, localeCode: String) -> String {
        switch category {
        case "PAYMENT_CARD": return t("category_singular_payment_card", localeCode: localeCode)
        case "BANK_ACCOUNT": return t("category_singular_bank_account", localeCode: localeCode)
        case "INSURANCE_CARD": return t("category_singular_insurance_card", localeCode: localeCode)
        case "MEMBERSHIP_CARD": return t("category_singular_membership_card", localeCode: localeCode)
        case "IDENTITY": return t("category_singular_identity", localeCode: localeCode)
        case "OFFICIAL_DOCUMENT": return t("category_singular_official_document", localeCode: localeCode)
        case "SECURE_NOTE": return t("category_singular_secure_note", localeCode: localeCode)
        default: return t("category_singular_secure_note", localeCode: localeCode)
        }
    }

    func resolveDisplaySubtitle(subtitle: String, category: String, localeCode: String) -> String {
        let trimmed = subtitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return resolveCategorySingularName(category: category, localeCode: localeCode)
        }
        switch trimmed.uppercased() {
        case "PAYMENT_CARD", "BANK_ACCOUNT", "INSURANCE_CARD", "MEMBERSHIP_CARD",
             "IDENTITY", "OFFICIAL_DOCUMENT", "SECURE_NOTE":
            return resolveCategorySingularName(category: trimmed.uppercased(), localeCode: localeCode)
        default:
            break
        }
        if hasKey(trimmed) {
            return t(trimmed, localeCode: localeCode)
        }
        return trimmed
    }

    func resolveFieldLabel(key: String, isCustom: Bool, category: String, localeCode: String) -> String {
        if isCustom { return key }
        if category == "BANK_ACCOUNT" && key == "field_issuing_bank" {
            return t("field_bank_name", localeCode: localeCode)
        }
        if category == "IDENTITY" {
            if key == "field_tax_id" { return t("identity_template_tax_id", localeCode: localeCode) }
            if key == "field_ssn" { return t("identity_template_ssn", localeCode: localeCode) }
            if key == "field_health_insurance" { return t("identity_template_health_insurance", localeCode: localeCode) }
        }
        return t(key, localeCode: localeCode)
    }

    private static func loadCatalog(for code: String) -> [String: String] {
        var candidateURLs: [URL] = []
        if let resURL = Bundle.main.resourceURL {
            candidateURLs.append(resURL.appendingPathComponent("i18n/\(code).arb.json"))
            candidateURLs.append(resURL.appendingPathComponent("\(code).arb.json"))
        }
        // Development / swift run fallback relative to executable or current directory
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        candidateURLs.append(cwd.appendingPathComponent("i18n/\(code).arb.json"))
        candidateURLs.append(cwd.appendingPathComponent("../i18n/\(code).arb.json"))

        for url in candidateURLs {
            if let data = try? Data(contentsOf: url),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                var out: [String: String] = [:]
                for (k, v) in obj where !k.hasPrefix("@") {
                    if let s = v as? String {
                        out[k] = s
                    }
                }
                if !out.isEmpty {
                    return out
                }
            }
        }
        return [:]
    }

    private static let builtInFallback: [String: String] = [
        "app_name": "SafePocket",
        "app_tagline": "Zero-Knowledge Personal Vault",
        "nav_all_items": "All Items",
        "nav_favorites": "Favorites",
        "nav_settings": "Settings",
        "action_done": "Done",
        "category_payment_card": "Payment Cards",
        "category_bank_account": "Bank Accounts",
        "category_insurance_card": "Insurance Cards",
        "category_membership_card": "Membership Cards",
        "category_identity": "Identities",
        "category_official_document": "Official Documents",
        "category_secure_note": "Secure Notes",
        "category_singular_payment_card": "Payment Card",
        "category_singular_bank_account": "Bank Account",
        "category_singular_insurance_card": "Insurance Policy",
        "category_singular_membership_card": "Membership Card",
        "category_singular_identity": "Identity & Tax ID",
        "category_singular_official_document": "Official Document",
        "category_singular_secure_note": "Secure Note",
        "security_auto_lock_never": "Never",
        "security_lock_on_standby_toggle": "Lock vault when computer / device goes to standby",
        "security_lock_on_standby_desc": "Immediately wipes encryption keys from memory on system sleep, lid close, or screen lock — even if inactivity auto-lock is set to Never.",
        "storage_mode_section_title": "Database & Sync",
        "storage_mode_local_only_title": "Local DB",
        "storage_mode_local_only_desc": "100% Offline on this device (SQLite)",
        "storage_mode_cloud_only_title": "Cloud Sync",
        "storage_mode_cloud_only_desc": "Local DB + Real-time backup across all devices",
        "storage_mode_cloud_auth_required": "Sign in or create your Firebase Cloud account below to activate Cloud Sync.",
        "cloud_auth_section_title": "Cloud Sync Account",
        "cloud_auth_email": "Email Address",
        "cloud_auth_password": "Cloud Sync Password",
        "cloud_auth_create_btn": "Create Account",
        "cloud_auth_signin_btn": "Sign In",
        "cloud_auth_signout_btn": "Sign Out",
        "cloud_config_title": "Cloud Endpoint Configuration",
        "sync_now": "Sync Delta Queue Now",
        "settings_language_title": "Language & Regional Templates",
        "settings_language_system": "System Default ({locale})",
        "settings_theme_title": "Appearance & Theme",
        "theme_light": "Light",
        "theme_dark": "Dark",
        "theme_system": "System",
        "portability_section_title": "Backup, Import & Export",
        "security_section_title": "Zero-Knowledge Security",
        "update_section_title": "Automated App Updates",
        "update_check_btn": "Check for Updates",
        "action_lock_vault": "Lock Vault",
        "action_unlock_vault": "Unlock Vault",
        "action_new_item": "New Item",
        "shortcut_hint_search": "Search"
    ]
}

// MARK: - Accent Color Schemes (Parity with PWA & Android)

enum MacMaterialAccent: String, CaseIterable, Identifiable {
    case emerald = "emerald"
    case ocean = "ocean"
    case terracotta = "terracotta"
    case slate = "slate"

    var id: String { rawValue }

    var labelKey: String {
        switch self {
        case .emerald: return "material_color_emerald"
        case .ocean: return "material_color_ocean"
        case .terracotta: return "material_color_terracotta"
        case .slate: return "material_color_slate"
        }
    }

    var swiftUIColor: Color {
        switch self {
        case .emerald: return Color(red: 0.08, green: 0.56, blue: 0.45)
        case .ocean: return Color(red: 0.13, green: 0.45, blue: 0.78)
        case .terracotta: return Color(red: 0.76, green: 0.34, blue: 0.19)
        case .slate: return Color(red: 0.27, green: 0.35, blue: 0.44)
        }
    }
}

// MARK: - Structured Multi-Field Vault Models & Category Templates

enum MacFieldValueType: String, Codable, CaseIterable {
    case text = "TEXT"
    case sensitiveSecret = "SENSITIVE_SECRET"
    case cardNumber = "CARD_NUMBER"
    case iban = "IBAN"
    case dateIso = "DATE_ISO"
    case phone = "PHONE"
    case url = "URL"
    case email = "EMAIL"
    case multiline = "MULTILINE_ADDRESS"
    case markdown = "MARKDOWN"
}

struct MacVaultField: Identifiable, Codable, Equatable {
    var id: String
    var labelKey: String
    var isCustomLabel: Bool = false
    var value: String
    var valueType: MacFieldValueType = .text
    var isSensitive: Bool = false
}

struct MacVaultAttachment: Identifiable, Codable, Equatable {
    var id: String
    var fileName: String
    var mimeType: String
    var side: String? = "front"
    var sizeBytes: Int64
    var sha256Hex: String
    var ivHex: String
    var fileKeyHex: String? = nil
    var ciphertextBase64: String? = nil
    var blobSynced: Bool = false
    var storageObjectPath: String? = nil
}

struct MacVaultEntry: Identifiable, Codable, Equatable {
    var id: String
    var category: String
    var title: String
    var subtitle: String
    var fields: [MacVaultField]
    var tags: [String] = []
    var attachments: [MacVaultAttachment] = []
    var customColorHex: String?
    var networkBrand: String?
    var pinned: Bool
    var favorite: Bool
    var isSampleItem: Bool
    var updatedAt: Date
    var hlcPacked: String = ""

    enum CodingKeys: String, CodingKey {
        case id, category, title, subtitle, fields, tags, attachments
        case customColorHex, networkBrand, pinned, favorite, isSampleItem, updatedAt, hlcPacked
    }

    init(
        id: String,
        category: String,
        title: String,
        subtitle: String,
        fields: [MacVaultField],
        tags: [String] = [],
        attachments: [MacVaultAttachment] = [],
        customColorHex: String? = nil,
        networkBrand: String? = nil,
        pinned: Bool,
        favorite: Bool,
        isSampleItem: Bool,
        updatedAt: Date,
        hlcPacked: String = ""
    ) {
        self.id = id
        self.category = category
        self.title = title
        self.subtitle = subtitle
        self.fields = fields
        self.tags = tags
        self.attachments = attachments
        self.customColorHex = customColorHex
        self.networkBrand = networkBrand
        self.pinned = pinned
        self.favorite = favorite
        self.isSampleItem = isSampleItem
        self.updatedAt = updatedAt
        self.hlcPacked = hlcPacked
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.category = try c.decode(String.self, forKey: .category)
        self.title = try c.decode(String.self, forKey: .title)
        self.subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle) ?? ""
        self.fields = try c.decodeIfPresent([MacVaultField].self, forKey: .fields) ?? []
        self.tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        self.attachments = try c.decodeIfPresent([MacVaultAttachment].self, forKey: .attachments) ?? []
        self.customColorHex = try c.decodeIfPresent(String.self, forKey: .customColorHex)
        self.networkBrand = try c.decodeIfPresent(String.self, forKey: .networkBrand)
        self.pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        self.favorite = try c.decodeIfPresent(Bool.self, forKey: .favorite) ?? false
        self.isSampleItem = try c.decodeIfPresent(Bool.self, forKey: .isSampleItem) ?? false
        self.updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
        self.hlcPacked = try c.decodeIfPresent(String.self, forKey: .hlcPacked) ?? ""
    }

    var primaryField: MacVaultField? {
        let priority: [String: [String]] = [
            "PAYMENT_CARD": ["field_card_number", "field_iban", "field_cvv"],
            "BANK_ACCOUNT": ["field_iban", "field_account_number", "field_bic"],
            "INSURANCE_CARD": ["field_insurance_number", "field_insurer_id"],
            "MEMBERSHIP_CARD": ["field_membership_id", "field_barcode_value"],
            "IDENTITY": ["field_tax_id", "field_ssn", "field_email", "field_phone"],
            "OFFICIAL_DOCUMENT": ["field_document_number", "field_pin"],
            "SECURE_NOTE": ["field_markdown_note", "field_pin"]
        ]
        for key in priority[category] ?? [] {
            if let f = fields.first(where: { !$0.isCustomLabel && $0.labelKey == key && !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
                return f
            }
        }
        return fields.first(where: { !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
    }

    var primaryLabel: String {
        primaryField?.labelKey ?? "Primary Value"
    }

    var primaryValue: String {
        primaryField?.value ?? ""
    }

    var websiteUrl: String {
        fields.first(where: { $0.labelKey == "field_institution_domain" || $0.valueType == .url })?.value ?? ""
    }

    var expiryIsoDate: String? {
        fields.first(where: { $0.labelKey == "field_expiry_date" && !$0.value.isEmpty })?.value
    }

    func daysUntilExpiry(referenceDate: Date = Date()) -> Int? {
        guard let raw = expiryIsoDate?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        let normalized = raw.count == 7 ? "\(raw)-28" : raw
        formatter.dateFormat = "yyyy-MM-dd"
        guard let dt = formatter.date(from: normalized) else { return nil }
        return Calendar.current.dateComponents([.day], from: referenceDate, to: dt).day
    }
}

struct MacFieldTemplateSpec {
    let key: String
    let valueType: MacFieldValueType
    let sensitive: Bool
    let placeholder: String
}

enum MacCategoryTemplates {
    static let categories: [String] = [
        "PAYMENT_CARD",
        "BANK_ACCOUNT",
        "INSURANCE_CARD",
        "MEMBERSHIP_CARD",
        "IDENTITY",
        "OFFICIAL_DOCUMENT",
        "SECURE_NOTE"
    ]

    static func specs(for category: String) -> [MacFieldTemplateSpec] {
        switch category {
        case "PAYMENT_CARD":
            return [
                .init(key: "field_cardholder", valueType: .text, sensitive: false, placeholder: "CLARA MÜLLER"),
                .init(key: "field_card_number", valueType: .cardNumber, sensitive: true, placeholder: "4532 •••• •••• 0366"),
                .init(key: "field_expiry_date", valueType: .dateIso, sensitive: false, placeholder: "YYYY-MM"),
                .init(key: "field_cvv", valueType: .sensitiveSecret, sensitive: true, placeholder: "•••"),
                .init(key: "field_pin", valueType: .sensitiveSecret, sensitive: true, placeholder: "••••"),
                .init(key: "field_card_network", valueType: .text, sensitive: false, placeholder: "VISA / Mastercard / AMEX"),
                .init(key: "field_card_type", valueType: .text, sensitive: false, placeholder: "Credit / Debit"),
                .init(key: "field_valid_from", valueType: .dateIso, sensitive: false, placeholder: "YYYY-MM"),
                .init(key: "field_issuing_bank", valueType: .text, sensitive: false, placeholder: "Deutsche Bank AG"),
                .init(key: "field_iban", valueType: .iban, sensitive: true, placeholder: "DE89 5007 0010 …"),
                .init(key: "field_bic", valueType: .text, sensitive: false, placeholder: "DEUTDEFFXXX"),
                .init(key: "field_credit_limit", valueType: .text, sensitive: false, placeholder: "10,000 EUR"),
                .init(key: "field_billing_address", valueType: .multiline, sensitive: false, placeholder: "Street, Postal Code, City"),
                .init(key: "field_support_phone", valueType: .phone, sensitive: false, placeholder: "+49 69 910 10000"),
                .init(key: "field_block_hotline", valueType: .phone, sensitive: false, placeholder: "+49 116 116"),
                .init(key: "field_institution_domain", valueType: .url, sensitive: false, placeholder: "https://www.deutsche-bank.de"),
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: false, placeholder: "Notes…")
            ]
        case "BANK_ACCOUNT":
            return [
                .init(key: "field_account_holder", valueType: .text, sensitive: false, placeholder: "Clara Sofia Müller"),
                .init(key: "field_issuing_bank", valueType: .text, sensitive: false, placeholder: "ING-DiBa AG / Deutsche Bank"),
                .init(key: "field_account_type", valueType: .text, sensitive: false, placeholder: "Checking Account (Girokonto)"),
                .init(key: "field_iban", valueType: .iban, sensitive: true, placeholder: "DE89 5001 0517 …"),
                .init(key: "field_bic", valueType: .text, sensitive: false, placeholder: "INGDDEFFXXX"),
                .init(key: "field_account_number", valueType: .sensitiveSecret, sensitive: true, placeholder: "5407324931"),
                .init(key: "field_routing_number", valueType: .text, sensitive: false, placeholder: "50010517"),
                .init(key: "field_currency", valueType: .text, sensitive: false, placeholder: "EUR"),
                .init(key: "field_online_banking_id", valueType: .sensitiveSecret, sensitive: true, placeholder: "Online Banking ID"),
                .init(key: "field_pin", valueType: .sensitiveSecret, sensitive: true, placeholder: "•••••"),
                .init(key: "field_tan_method", valueType: .text, sensitive: false, placeholder: "photoTAN / pushTAN"),
                .init(key: "field_branch_name", valueType: .text, sensitive: false, placeholder: "Frankfurt Main"),
                .init(key: "field_support_phone", valueType: .phone, sensitive: false, placeholder: "+49 69 50 500 105"),
                .init(key: "field_institution_domain", valueType: .url, sensitive: false, placeholder: "https://www.ing.de"),
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: false, placeholder: "Notes…")
            ]
        case "INSURANCE_CARD":
            return [
                .init(key: "field_policy_holder", valueType: .text, sensitive: false, placeholder: "Clara Sofia Müller"),
                .init(key: "field_issuing_authority", valueType: .text, sensitive: false, placeholder: "Techniker Krankenkasse (TK)"),
                .init(key: "field_insurance_number", valueType: .sensitiveSecret, sensitive: true, placeholder: "T940281938"),
                .init(key: "field_group_number", valueType: .text, sensitive: false, placeholder: "Group / Tariff"),
                .init(key: "field_insurer_id", valueType: .text, sensitive: false, placeholder: "101575519"),
                .init(key: "field_coverage_plan", valueType: .text, sensitive: false, placeholder: "eGK / GKV + EHIC"),
                .init(key: "field_issue_date", valueType: .dateIso, sensitive: false, placeholder: "YYYY-MM-DD"),
                .init(key: "field_expiry_date", valueType: .dateIso, sensitive: false, placeholder: "YYYY-MM-DD"),
                .init(key: "field_emergency_phone", valueType: .phone, sensitive: false, placeholder: "+49 40 4606 625300"),
                .init(key: "field_support_phone", valueType: .phone, sensitive: false, placeholder: "+49 800 285 85 85"),
                .init(key: "field_institution_domain", valueType: .url, sensitive: false, placeholder: "https://www.tk.de"),
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: false, placeholder: "Notes…")
            ]
        case "MEMBERSHIP_CARD":
            return [
                .init(key: "field_cardholder", valueType: .text, sensitive: false, placeholder: "Clara Sofia Müller"),
                .init(key: "field_organization", valueType: .text, sensitive: false, placeholder: "Deutsche Bahn / ADAC"),
                .init(key: "field_membership_id", valueType: .sensitiveSecret, sensitive: true, placeholder: "7081 4910 2284"),
                .init(key: "field_tier_level", valueType: .text, sensitive: false, placeholder: "BahnCard 50 / Plus"),
                .init(key: "field_barcode_value", valueType: .text, sensitive: false, placeholder: "708149102284"),
                .init(key: "field_member_since", valueType: .dateIso, sensitive: false, placeholder: "YYYY-MM"),
                .init(key: "field_expiry_date", valueType: .dateIso, sensitive: false, placeholder: "YYYY-MM-DD"),
                .init(key: "field_pin", valueType: .sensitiveSecret, sensitive: true, placeholder: "••••"),
                .init(key: "field_support_phone", valueType: .phone, sensitive: false, placeholder: "+49 30 2970"),
                .init(key: "field_institution_domain", valueType: .url, sensitive: false, placeholder: "https://www.bahn.de"),
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: false, placeholder: "Notes…")
            ]
        case "IDENTITY":
            return [
                .init(key: "field_salutation", valueType: .text, sensitive: false, placeholder: "Ms. / Frau"),
                .init(key: "field_first_name", valueType: .text, sensitive: false, placeholder: "Clara Sofia"),
                .init(key: "field_last_name", valueType: .text, sensitive: false, placeholder: "Müller"),
                .init(key: "field_birth_date", valueType: .dateIso, sensitive: false, placeholder: "1992-04-19"),
                .init(key: "field_birth_place", valueType: .text, sensitive: false, placeholder: "München"),
                .init(key: "field_nationality", valueType: .text, sensitive: false, placeholder: "Germany (DEU)"),
                .init(key: "field_tax_id", valueType: .sensitiveSecret, sensitive: true, placeholder: "Steuer-ID / AFM / Codice Fiscale"),
                .init(key: "field_ssn", valueType: .sensitiveSecret, sensitive: true, placeholder: "Social Security Number"),
                .init(key: "field_health_insurance", valueType: .text, sensitive: false, placeholder: "TK eGK"),
                .init(key: "field_email", valueType: .email, sensitive: false, placeholder: "clara@example.eu"),
                .init(key: "field_phone", valueType: .phone, sensitive: false, placeholder: "+49 151 2345 6789"),
                .init(key: "field_address", valueType: .multiline, sensitive: false, placeholder: "Street, Postal Code, City, Country"),
                .init(key: "field_employer", valueType: .text, sensitive: false, placeholder: "Organization / Company"),
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: false, placeholder: "Notes…")
            ]
        case "OFFICIAL_DOCUMENT":
            return [
                .init(key: "field_document_subtype", valueType: .text, sensitive: false, placeholder: "Passport / National ID"),
                .init(key: "field_document_number", valueType: .sensitiveSecret, sensitive: true, placeholder: "C01X00T47"),
                .init(key: "field_cardholder", valueType: .text, sensitive: false, placeholder: "Clara Sofia Müller"),
                .init(key: "field_nationality", valueType: .text, sensitive: false, placeholder: "Germany (DEU)"),
                .init(key: "field_birth_date", valueType: .dateIso, sensitive: false, placeholder: "1992-04-19"),
                .init(key: "field_issuing_country", valueType: .text, sensitive: false, placeholder: "Germany (DEU)"),
                .init(key: "field_issuing_authority", valueType: .text, sensitive: false, placeholder: "Stadt München"),
                .init(key: "field_issue_date", valueType: .dateIso, sensitive: false, placeholder: "2022-05-10"),
                .init(key: "field_expiry_date", valueType: .dateIso, sensitive: false, placeholder: "2032-05-09"),
                .init(key: "field_pin", valueType: .sensitiveSecret, sensitive: true, placeholder: "eID PIN"),
                .init(key: "field_institution_domain", valueType: .url, sensitive: false, placeholder: "https://www.personalausweisportal.de"),
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: false, placeholder: "Notes…")
            ]
        default: // SECURE_NOTE
            return [
                .init(key: "field_markdown_note", valueType: .markdown, sensitive: true, placeholder: "Confidential markdown note…"),
                .init(key: "field_pin", valueType: .sensitiveSecret, sensitive: true, placeholder: "Optional PIN / Code"),
                .init(key: "field_institution_domain", valueType: .url, sensitive: false, placeholder: "https://…")
            ]
        }
    }
}

// MARK: - Institution Dual-Branding & Smart Field Intelligence (macOS Parity)

struct MacInstitutionProfile {
    let id: String
    let displayName: String
    let domain: String
    let primaryHex: String
    let monogram: String
    let defaultBic: String
    let blzCodes: [String]
}

enum MacSmartIntelligence {
    static let dialPrefixes = ["+49", "+30", "+39", "+33", "+34", "+43", "+41", "+31", "+32", "+44", "+1", "+357"]

    static let institutions: [MacInstitutionProfile] = [
        .init(id: "deutsche_bank", displayName: "Deutsche Bank", domain: "deutsche-bank.de", primaryHex: "#0018A8", monogram: "DB", defaultBic: "DEUTDEFFXXX", blzCodes: ["50070010", "10070000", "70070010"]),
        .init(id: "ing", displayName: "ING", domain: "ing.de", primaryHex: "#FF6200", monogram: "ING", defaultBic: "INGDDEFFXXX", blzCodes: ["50010517"]),
        .init(id: "sparkasse", displayName: "Sparkasse", domain: "sparkasse.de", primaryHex: "#E2001A", monogram: "S", defaultBic: "BELADEBEXXX", blzCodes: ["10050000", "70150000"]),
        .init(id: "commerzbank", displayName: "Commerzbank", domain: "commerzbank.de", primaryHex: "#00414B", monogram: "CB", defaultBic: "COBADEFFXXX", blzCodes: ["37040044", "10040000"]),
        .init(id: "dkb", displayName: "DKB", domain: "dkb.de", primaryHex: "#148DEA", monogram: "DKB", defaultBic: "BYLADEM1001", blzCodes: ["12030000"]),
        .init(id: "n26", displayName: "N26", domain: "n26.com", primaryHex: "#1A7F72", monogram: "N26", defaultBic: "NTSBDEB1XXX", blzCodes: ["10011001"]),
        .init(id: "revolut", displayName: "Revolut", domain: "revolut.com", primaryHex: "#191C1F", monogram: "R", defaultBic: "REVOLT21XXX", blzCodes: []),
        .init(id: "tk", displayName: "Techniker Krankenkasse", domain: "tk.de", primaryHex: "#009FE3", monogram: "TK", defaultBic: "", blzCodes: []),
        .init(id: "adac", displayName: "ADAC", domain: "adac.de", primaryHex: "#D4A000", monogram: "ADAC", defaultBic: "", blzCodes: []),
        .init(id: "bahn", displayName: "Deutsche Bahn", domain: "bahn.de", primaryHex: "#EC0016", monogram: "DB", defaultBic: "", blzCodes: []),
        .init(id: "alpha_bank", displayName: "Alpha Bank", domain: "alpha.gr", primaryHex: "#00338D", monogram: "AB", defaultBic: "CRBAGRAAXXX", blzCodes: []),
        .init(id: "intesa", displayName: "Intesa Sanpaolo", domain: "intesasanpaolo.com", primaryHex: "#258900", monogram: "ISP", defaultBic: "BCITITMMXXX", blzCodes: []),
        .init(id: "bnp", displayName: "BNP Paribas", domain: "mabanque.bnpparibas", primaryHex: "#00915A", monogram: "BNP", defaultBic: "BNPAFRPPXXX", blzCodes: [])
    ]

    static func resolveInstitution(for entry: MacVaultEntry) -> MacInstitutionProfile? {
        let haystack = ([entry.title, entry.subtitle, entry.websiteUrl] + entry.fields.map(\.value))
            .joined(separator: " ")
            .lowercased()
        return institutions.first { inst in
            haystack.contains(inst.displayName.lowercased()) || haystack.contains(inst.domain.lowercased())
        }
    }

    static func resolveNetworkBadge(for entry: MacVaultEntry) -> String {
        if let explicit = entry.fields.first(where: { $0.labelKey == "field_card_network" && !$0.value.isEmpty })?.value {
            return explicit.uppercased()
        }
        if let brand = entry.networkBrand, !brand.isEmpty {
            return brand.uppercased()
        }
        switch entry.category {
        case "BANK_ACCOUNT": return "SEPA"
        case "INSURANCE_CARD": return "eGK / EHIC"
        case "MEMBERSHIP_CARD": return "MEMBER"
        case "OFFICIAL_DOCUMENT": return "ICAO"
        case "IDENTITY": return "ID"
        default: return "AES-256"
        }
    }

    static func colorFromHex(_ hex: String?) -> Color? {
        guard var clean = hex?.trimmingCharacters(in: .whitespacesAndNewlines), !clean.isEmpty else { return nil }
        if clean.hasPrefix("#") { clean.removeFirst() }
        guard clean.count == 6, let val = UInt64(clean, radix: 16) else { return nil }
        let r = Double((val >> 16) & 0xFF) / 255.0
        let g = Double((val >> 8) & 0xFF) / 255.0
        let b = Double(val & 0xFF) / 255.0
        return Color(red: r, green: g, blue: b)
    }

    static func dropdownOptions(for fieldKey: String, category: String, i18n: MacLocalizationEngine, localeCode: String) -> [String]? {
        if fieldKey == "field_account_type" && category == "BANK_ACCOUNT" {
            return [
                "acct_type_checking", "acct_type_savings", "acct_type_fixed_deposit",
                "acct_type_joint", "acct_type_business", "acct_type_brokerage",
                "acct_type_foreign_currency", "acct_type_youth_student"
            ].map { i18n.t($0, localeCode: localeCode) }
        }
        if fieldKey == "field_card_network" && category == "PAYMENT_CARD" {
            return ["VISA", "Mastercard", "American Express", "girocard", "Maestro", "V PAY", "Discover / Diners"]
        }
        if fieldKey == "field_card_type" && category == "PAYMENT_CARD" {
            return ["card_type_credit", "card_type_debit", "card_type_prepaid", "card_type_virtual", "card_type_charge", "card_type_business"]
                .map { i18n.t($0, localeCode: localeCode) }
        }
        if fieldKey == "field_currency" {
            return ["EUR", "CHF", "USD", "GBP", "SEK", "NOK", "DKK", "PLN", "JPY"]
        }
        if fieldKey == "field_coverage_plan" && category == "INSURANCE_CARD" {
            return ["eGK / GKV + EHIC", "PKV", "Dental / Supplementary", "Travel Medical", "Liability / Home"]
        }
        if fieldKey == "field_document_subtype" && category == "OFFICIAL_DOCUMENT" {
            return [
                i18n.t("doc_type_passport", localeCode: localeCode),
                i18n.t("doc_type_national_id", localeCode: localeCode),
                "Driver's License / Führerschein",
                "Residence Permit / Aufenthaltstitel"
            ]
        }
        if fieldKey == "field_salutation" && category == "IDENTITY" {
            return ["Ms. / Frau", "Mr. / Herr", "Mx.", "Dr.", "Prof. Dr."]
        }
        if fieldKey == "field_nationality" || fieldKey == "field_issuing_country" {
            return ["Germany (DEU)", "Greece (GRC)", "Italy (ITA)", "France (FRA)", "Spain (ESP)", "Austria (AUT)", "Switzerland (CHE)", "United Kingdom (GBR)", "United States (USA)"]
        }
        return nil
    }

    static func formatInternationalPhone(_ raw: String, dialPrefix: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        var digits = trimmed.filter { $0.isNumber || $0 == "+" }
        if digits.hasPrefix("00") {
            digits = "+" + digits.dropFirst(2)
        }
        var activePrefix = dialPrefix
        var subscriber = ""
        if digits.hasPrefix("+") {
            let sorted = dialPrefixes.sorted { $0.count > $1.count }
            if let hit = sorted.first(where: { digits.hasPrefix($0) }) {
                activePrefix = hit
                subscriber = String(digits.dropFirst(hit.count)).filter(\.isNumber)
            } else {
                subscriber = digits.filter(\.isNumber)
            }
        } else {
            subscriber = digits.filter(\.isNumber)
            if subscriber.hasPrefix("0") && subscriber.count > 1 {
                subscriber.removeFirst()
            }
        }
        guard !subscriber.isEmpty else { return activePrefix }
        var groups: [String] = []
        var idx = subscriber.startIndex
        let firstStep = min(3, subscriber.count)
        let firstEnd = subscriber.index(idx, offsetBy: firstStep)
        groups.append(String(subscriber[idx..<firstEnd]))
        idx = firstEnd
        while idx < subscriber.endIndex {
            let rem = subscriber.distance(from: idx, to: subscriber.endIndex)
            let step = rem > 4 ? 4 : rem
            let next = subscriber.index(idx, offsetBy: step)
            groups.append(String(subscriber[idx..<next]))
            idx = next
        }
        return "\(activePrefix) \(groups.joined(separator: " "))"
    }

    static func formatIbanAndInfer(_ raw: String) -> (formatted: String, validMod97: Bool, bank: MacInstitutionProfile?, routing: String?, account: String?) {
        let compact = raw.uppercased().filter { $0.isLetter || $0.isNumber }
        let grouped = stride(from: 0, to: compact.count, by: 4).map { offset -> String in
            let s = compact.index(compact.startIndex, offsetBy: offset)
            let e = compact.index(s, offsetBy: 4, limitedBy: compact.endIndex) ?? compact.endIndex
            return String(compact[s..<e])
        }.joined(separator: " ")

        let valid = validateIbanMod97(compact)
        var bank: MacInstitutionProfile?
        var routing: String?
        var account: String?
        if compact.hasPrefix("DE") && compact.count >= 12 {
            let s = compact.index(compact.startIndex, offsetBy: 4)
            let e = compact.index(s, offsetBy: 8)
            let blz = String(compact[s..<e])
            routing = blz
            bank = institutions.first(where: { $0.blzCodes.contains(blz) })
            if compact.count == 22 {
                let accEnd = compact.index(e, offsetBy: 10)
                account = String(compact[e..<accEnd])
            }
        }
        return (grouped, valid, bank, routing, account)
    }

    static func validateIbanMod97(_ compact: String) -> Bool {
        guard compact.count >= 15 && compact.count <= 34 else { return false }
        let prefix = compact.prefix(4)
        let rearranged = String(compact.dropFirst(4) + prefix)
        var remainder = 0
        for ch in rearranged {
            let expanded: String
            if let d = ch.wholeNumberValue {
                expanded = String(d)
            } else if let ascii = ch.asciiValue, ascii >= 65 && ascii <= 90 {
                expanded = String(Int(ascii) - 55)
            } else {
                return false
            }
            for digitChar in expanded {
                remainder = (remainder * 10 + (digitChar.wholeNumberValue ?? 0)) % 97
            }
        }
        return remainder == 1
    }

    static func formatCardPan(_ raw: String) -> (formatted: String, network: String) {
        let digits = String(raw.filter(\.isNumber).prefix(19))
        let isAmex = digits.hasPrefix("34") || digits.hasPrefix("37")
        let network: String
        if digits.hasPrefix("4") { network = "VISA" }
        else if digits.hasPrefix("5") || digits.hasPrefix("2") { network = "Mastercard" }
        else if isAmex { network = "American Express" }
        else { network = "" }

        if isAmex {
            let p1 = String(digits.prefix(4))
            let p2 = String(digits.dropFirst(4).prefix(6))
            let p3 = String(digits.dropFirst(10).prefix(5))
            return ([p1, p2, p3].filter { !$0.isEmpty }.joined(separator: " "), network)
        }
        let grouped = stride(from: 0, to: digits.count, by: 4).map { offset -> String in
            let s = digits.index(digits.startIndex, offsetBy: offset)
            let e = digits.index(s, offsetBy: 4, limitedBy: digits.endIndex) ?? digits.endIndex
            return String(digits[s..<e])
        }.joined(separator: " ")
        return (grouped, network)
    }
}
