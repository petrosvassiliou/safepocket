package com.safepocket.shared.backup

import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.model.*
import com.safepocket.shared.update.AppBuildInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class VaultImportReport(
    val importedItems: List<VaultItemPayload>,
    val skippedLoginCount: Int = 0,
    val sourceFormat: String
)

/**
 * Bidirectional CSV & 1Password (`.1pux` ZIP + 1Password CSV) Portability Engine.
 *
 * Supports:
 * - Import from 1Password `.1pux` (ZIP archive containing `export.data` JSON)
 * - Import from 1Password CSV & SafePocket / Generic CSV
 * - Export to 1Password `.1pux`
 * - Export to 1Password CSV
 * - Export to SafePocket CSV & JSON
 */
object CsvAndOnePasswordPortability {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val compactJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // =========================================================================
    // 1. 1PASSWORD `.1pux` IMPORT & EXPORT
    // =========================================================================

    fun importFrom1Password1Pux(
        rawBytes: ByteArray,
        includeLoginsAsNotes: Boolean = false,
        nowEpochMs: Long = 1763320000000L,
        zipExtractor: ((ByteArray) -> Map<String, ByteArray>)? = null
    ): VaultImportReport {
        val exportDataJson: String = if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte()
        ) {
            val entries = zipExtractor?.invoke(rawBytes) ?: extractStoredZipEntries(rawBytes)
            val dataEntry = entries.entries.firstOrNull {
                it.key.endsWith("export.data") || it.key == "export.data"
            }?.value ?: error("Invalid .1pux archive: export.data not found")
            dataEntry.decodeToString()
        } else {
            // Also accept raw unwrapped export.data JSON directly
            rawBytes.decodeToString()
        }

        return parse1PasswordExportDataJson(exportDataJson, includeLoginsAsNotes, nowEpochMs)
    }

    fun parse1PasswordExportDataJson(
        rawJson: String,
        includeLoginsAsNotes: Boolean = false,
        nowEpochMs: Long = 1763320000000L
    ): VaultImportReport {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val accounts = root["accounts"]?.jsonArray ?: JsonArray(emptyList())
        val imported = mutableListOf<VaultItemPayload>()
        var skippedLogins = 0
        var counter = 0

        for (accElem in accounts) {
            val vaults = accElem.jsonObject["vaults"]?.jsonArray ?: continue
            for (vaultElem in vaults) {
                val items = vaultElem.jsonObject["items"]?.jsonArray ?: continue
                for (itemElem in items) {
                    val itemObj = itemElem.jsonObject
                    val state = itemObj["state"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    if (state == "trashed") continue

                    val catUuid = itemObj["categoryUuid"]?.jsonPrimitive?.contentOrNull ?: "003"
                    val isLogin = catUuid in setOf("001", "100", "101", "102", "111", "112")
                    if (isLogin && !includeLoginsAsNotes) {
                        skippedLogins++
                        continue
                    }

                    val uuid = itemObj["uuid"]?.jsonPrimitive?.contentOrNull
                        ?: "item-1p-${nowEpochMs}-${counter++}"
                    val overview = itemObj["overview"]?.jsonObject ?: JsonObject(emptyMap())
                    val details = itemObj["details"]?.jsonObject ?: JsonObject(emptyMap())
                    val title = overview["title"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: "Imported Item"
                    val subtitle = overview["ainfo"]?.jsonPrimitive?.contentOrNull
                        ?: overview["ps"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val url = overview["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val tags = overview["tags"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()
                    val fav = (itemObj["favIndex"]?.jsonPrimitive?.intOrNull ?: 0) > 0
                    val createdSec = itemObj["createdAt"]?.jsonPrimitive?.longOrNull
                    val updatedSec = itemObj["updatedAt"]?.jsonPrimitive?.longOrNull
                    val createdMs = createdSec?.let { if (it < 100_000_000_000L) it * 1000L else it } ?: nowEpochMs
                    val updatedMs = updatedSec?.let { if (it < 100_000_000_000L) it * 1000L else it } ?: createdMs

                    val targetCategory = map1PasswordCategoryUuid(catUuid)
                    val rawAttrs = mutableListOf<Triple<String, String, String>>() // (id/name, title, value)

                    // Collect loginFields if importing Login as Secure Note
                    details["loginFields"]?.jsonArray?.forEach { lf ->
                        val lfObj = lf.jsonObject
                        val desig = lfObj["designation"]?.jsonPrimitive?.contentOrNull
                            ?: lfObj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val v = lfObj["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (v.isNotBlank()) rawAttrs += Triple(desig, desig, v)
                    }

                    // Collect sectioned fields from details.sections[].fields[]
                    details["sections"]?.jsonArray?.forEach { sec ->
                        val secObj = sec.jsonObject
                        secObj["fields"]?.jsonArray?.forEach { fld ->
                            val fObj = fld.jsonObject
                            val fid = fObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val fTitle = fObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val valObj = fObj["value"]?.jsonObject
                            val extracted = extract1PasswordFieldValue(valObj)
                            if (extracted.isNotBlank()) {
                                rawAttrs += Triple(fid, fTitle, extracted)
                            }
                        }
                    }

                    val notesPlain = details["notesPlain"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val passwordTop = details["password"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val mappedFields = map1PasswordFieldsToSafePocket(
                        category = targetCategory,
                        rawAttrs = rawAttrs,
                        notesPlain = notesPlain,
                        url = url,
                        topPassword = passwordTop,
                        isLoginAsNote = isLogin
                    )

                    val baseItem = VaultItemPayload(
                        id = if (uuid.startsWith("item-")) uuid else "1p-$uuid",
                        category = targetCategory,
                        title = title,
                        subtitle = subtitle,
                        favorite = fav,
                        tags = tags,
                        fields = mappedFields,
                        createdAtEpochMs = createdMs,
                        updatedAtEpochMs = updatedMs
                    )
                    imported += InstitutionBrandRegistry.applyAutoBranding(baseItem)
                }
            }
        }

        return VaultImportReport(
            importedItems = imported,
            skippedLoginCount = skippedLogins,
            sourceFormat = "1Password (.1pux)"
        )
    }

    fun exportTo1Password1Pux(
        items: List<VaultItemPayload>,
        exportedAtEpochSeconds: Long = 1763320000L,
        zipCreator: ((Map<String, ByteArray>) -> ByteArray)? = null
    ): ByteArray {
        val attributesJson = buildJsonObject {
            put("version", 3)
            put("description", "SafePocket v${AppBuildInfo.CURRENT_VERSION_NAME} 1Password Export")
            put("createdAt", exportedAtEpochSeconds)
        }.toString()

        val exportItems = buildJsonArray {
            for (item in items) {
                add(serializeItemTo1PasswordJson(item, exportedAtEpochSeconds))
            }
        }

        val exportDataJson = buildJsonObject {
            putJsonArray("accounts") {
                addJsonObject {
                    putJsonObject("attrs") {
                        put("accountName", "SafePocket Vault")
                        put("name", "SafePocket User")
                        put("domain", "safepocket.local")
                    }
                    putJsonArray("vaults") {
                        addJsonObject {
                            putJsonObject("attrs") {
                                put("uuid", "safepocket-primary-vault")
                                put("name", "SafePocket")
                                put("type", "P")
                            }
                            put("items", exportItems)
                        }
                    }
                }
            }
        }.let { json.encodeToString(JsonObject.serializer(), it) }

        val entries = linkedMapOf(
            "export.attributes" to attributesJson.encodeToByteArray(),
            "export.data" to exportDataJson.encodeToByteArray()
        )
        return zipCreator?.invoke(entries) ?: createStoredZipArchive(entries)
    }

    // =========================================================================
    // 2. CSV IMPORT & EXPORT (1Password CSV + SafePocket / Generic CSV)
    // =========================================================================

    fun importFromCsv(
        rawCsv: String,
        includeLoginsAsNotes: Boolean = false,
        nowEpochMs: Long = 1763320000000L
    ): VaultImportReport {
        val cleaned = rawCsv.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) return VaultImportReport(emptyList(), 0, "CSV")

        val rows = parseRfc4180Csv(cleaned)
        if (rows.size < 2) return VaultImportReport(emptyList(), 0, "CSV")

        val headers = rows[0].map { it.trim() }
        val lowerHeaders = headers.map { it.lowercase() }

        val isSafePocketCsv = "category" in lowerHeaders &&
            ("fields_json" in lowerHeaders || "fields_summary" in lowerHeaders)

        val imported = mutableListOf<VaultItemPayload>()
        var skippedLogins = 0

        for (rIdx in 1 until rows.size) {
            val row = rows[rIdx]
            if (row.all { it.isBlank() }) continue
            val map = linkedMapOf<String, String>()
            for (cIdx in headers.indices) {
                val cell = row.getOrElse(cIdx) { "" }.trim()
                if (cell.isNotEmpty()) {
                    map[headers[cIdx]] = cell
                }
            }
            if (map.isEmpty()) continue

            if (isSafePocketCsv) {
                val item = parseSafePocketCsvRow(map, nowEpochMs + rIdx)
                if (item != null) imported += InstitutionBrandRegistry.applyAutoBranding(item)
                continue
            }

            // 1Password CSV / Generic CSV row
            val rawType = getByAliases(map, "type", "category", "kategorie", "item type").lowercase()
            val isLogin = rawType in setOf("login", "password", "passwort", "server")
            if (isLogin && !includeLoginsAsNotes) {
                skippedLogins++
                continue
            }

            val category = inferCategoryFromCsvRow(rawType, map)
            val title = getByAliases(map, "title", "name", "bezeichnung", "titel")
                .ifBlank { getByAliases(map, "bank", "issuing bank", "organization", "insurer") }
                .ifBlank { "Imported Item #$rIdx" }
            val subtitle = getByAliases(map, "subtitle", "untertitel", "account type", "type")
            val tags = getByAliases(map, "tags", "tag", "schlagworte")
                .split(',', '|', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val favorite = getByAliases(map, "favorite", "fav", "favorit").lowercase() in setOf("true", "1", "yes", "ja")

            val fields = buildFieldsFromCsvRow(category, map, isLogin)
            val base = VaultItemPayload(
                id = "csv-${nowEpochMs}-$rIdx",
                category = category,
                title = title,
                subtitle = subtitle,
                favorite = favorite,
                tags = tags,
                fields = fields,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs
            )
            imported += InstitutionBrandRegistry.applyAutoBranding(base)
        }

        return VaultImportReport(
            importedItems = imported,
            skippedLoginCount = skippedLogins,
            sourceFormat = if (isSafePocketCsv) "SafePocket CSV" else "1Password / CSV"
        )
    }

    fun exportToSafePocketCsv(items: List<VaultItemPayload>): String {
        val header = listOf(
            "id", "category", "title", "subtitle", "favorite", "pinned", "tags",
            "institution", "network_or_type", "card_color_hex", "fields_json"
        ).joinToString(",")

        val lines = items.map { item ->
            val brand = InstitutionBrandRegistry.resolveForItem(item)
            val fieldsJson = compactJson.encodeToString(item.fields)
            listOf(
                escCsv(item.id),
                escCsv(item.category.name),
                escCsv(item.title),
                escCsv(item.subtitle),
                item.favorite.toString(),
                item.pinned.toString(),
                escCsv(item.tags.joinToString("|")),
                escCsv(item.cardTheme?.institutionName ?: brand.institution?.displayName.orEmpty()),
                escCsv(item.cardTheme?.networkBrand ?: brand.networkDisplayLabel),
                escCsv(item.cardTheme?.customColorHex ?: brand.effectiveColorHex.orEmpty()),
                escCsv(fieldsJson)
            ).joinToString(",")
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    fun exportTo1PasswordCsv(items: List<VaultItemPayload>): String {
        val columns = listOf(
            "Title", "Type", "Cardholder Name", "Number", "Brand", "Expiry Date",
            "Verification Number", "PIN", "Issuing Bank", "IBAN", "SWIFT",
            "Account Number", "Routing Number", "Full Name", "Birthdate",
            "Document Number", "Organization", "Membership ID", "Website", "Notes", "Tags"
        )
        val rows = mutableListOf(columns.joinToString(","))

        for (item in items) {
            val typeLabel = when (item.category) {
                VaultCategory.PAYMENT_CARD -> "Credit Card"
                VaultCategory.BANK_ACCOUNT -> "Bank Account"
                VaultCategory.INSURANCE_CARD -> "Medical Record"
                VaultCategory.MEMBERSHIP_CARD -> "Membership"
                VaultCategory.IDENTITY -> "Identity"
                VaultCategory.OFFICIAL_DOCUMENT -> "Passport"
                VaultCategory.SECURE_NOTE -> "Secure Note"
            }
            val notesCombined = listOf(
                item.fieldValue(CanonicalFieldKeys.MARKDOWN_NOTE),
                item.fieldValue(CanonicalFieldKeys.NOTES)
            ).filter { it.isNotBlank() }.joinToString("\n\n")

            val values = listOf(
                item.title,
                typeLabel,
                item.fieldValue(CanonicalFieldKeys.CARDHOLDER).ifBlank { item.fieldValue(CanonicalFieldKeys.ACCOUNT_HOLDER) },
                item.fieldValue(CanonicalFieldKeys.CARD_NUMBER),
                item.fieldValue(CanonicalFieldKeys.CARD_NETWORK).ifBlank { item.cardTheme?.networkBrand.orEmpty() },
                item.fieldValue(CanonicalFieldKeys.EXPIRY_DATE),
                item.fieldValue(CanonicalFieldKeys.CVV),
                item.fieldValue(CanonicalFieldKeys.PIN),
                item.fieldValue(CanonicalFieldKeys.ISSUING_BANK).ifBlank { item.fieldValue(CanonicalFieldKeys.INSURER_NAME) },
                item.fieldValue(CanonicalFieldKeys.IBAN),
                item.fieldValue(CanonicalFieldKeys.BIC),
                item.fieldValue(CanonicalFieldKeys.ACCOUNT_NUMBER).ifBlank { item.fieldValue(CanonicalFieldKeys.INSURANCE_NUMBER) },
                item.fieldValue(CanonicalFieldKeys.ROUTING_CODE).ifBlank { item.fieldValue(CanonicalFieldKeys.INSURER_ID) },
                item.fieldValue(CanonicalFieldKeys.FULL_NAME).ifBlank { item.fieldValue(CanonicalFieldKeys.MEMBER_NAME) },
                item.fieldValue(CanonicalFieldKeys.BIRTHDATE),
                item.fieldValue(CanonicalFieldKeys.DOCUMENT_NUMBER),
                item.fieldValue(CanonicalFieldKeys.ORGANIZATION),
                item.fieldValue(CanonicalFieldKeys.MEMBERSHIP_ID),
                item.fieldValue(CanonicalFieldKeys.INSTITUTION_DOMAIN).ifBlank { item.cardTheme?.institutionDomain.orEmpty() },
                notesCombined,
                item.tags.joinToString(",")
            )
            rows += values.joinToString(",") { escCsv(it) }
        }
        return rows.joinToString("\n")
    }

    fun exportToSafePocketJson(items: List<VaultItemPayload>): String =
        json.encodeToString(items)

    // =========================================================================
    // INTERNAL MAPPING HELPERS
    // =========================================================================

    private fun map1PasswordCategoryUuid(catUuid: String): VaultCategory = when (catUuid) {
        "002" -> VaultCategory.PAYMENT_CARD
        "104" -> VaultCategory.BANK_ACCOUNT
        "108" -> VaultCategory.INSURANCE_CARD
        "105", "107" -> VaultCategory.MEMBERSHIP_CARD
        "004" -> VaultCategory.IDENTITY
        "103", "106", "109", "110" -> VaultCategory.OFFICIAL_DOCUMENT
        else -> VaultCategory.SECURE_NOTE
    }

    private fun extract1PasswordFieldValue(valObj: JsonObject?): String {
        if (valObj == null) return ""
        for ((k, v) in valObj) {
            when (v) {
                is JsonPrimitive -> {
                    val c = v.contentOrNull.orEmpty()
                    if (k == "monthYear" && c.length == 6 && c.all { it.isDigit() }) {
                        return "${c.substring(0, 4)}-${c.substring(4, 6)}"
                    }
                    if (k == "date" && c.all { it.isDigit() }) {
                        return c
                    }
                    if (c.isNotBlank()) return c
                }
                is JsonObject -> {
                    val street = v["street"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val zip = v["zip"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val city = v["city"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val country = v["country"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val addr = listOf(street, "$zip $city".trim(), country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (addr.isNotBlank()) return addr
                }
                else -> {}
            }
        }
        return ""
    }

    private fun map1PasswordFieldsToSafePocket(
        category: VaultCategory,
        rawAttrs: List<Triple<String, String, String>>,
        notesPlain: String,
        url: String,
        topPassword: String,
        isLoginAsNote: Boolean
    ): List<VaultField> {
        val out = mutableListOf<VaultField>()
        var idx = 0
        fun addStd(key: String, value: String, type: FieldValueType = FieldValueType.TEXT) {
            if (value.isBlank()) return
            if (out.any { !it.isCustomLabel && it.labelKey == key }) return
            out += VaultField(
                id = "f-1p-${idx++}",
                labelKey = key,
                value = value.trim(),
                valueType = type
            )
        }

        if (isLoginAsNote) {
            val summary = buildString {
                if (url.isNotBlank()) appendLine("URL: $url")
                rawAttrs.forEach { (_, t, v) -> appendLine("${t.ifBlank { "Field" }}: $v") }
                if (topPassword.isNotBlank()) appendLine("Password: $topPassword")
                if (notesPlain.isNotBlank()) {
                    appendLine()
                    append(notesPlain)
                }
            }.trim()
            if (url.isNotBlank()) addStd(CanonicalFieldKeys.INSTITUTION_DOMAIN, url)
            addStd(CanonicalFieldKeys.MARKDOWN_NOTE, summary, FieldValueType.MARKDOWN)
            return out
        }

        if (url.isNotBlank()) {
            addStd(CanonicalFieldKeys.INSTITUTION_DOMAIN, InstitutionBrandRegistry.cleanDomain(url).ifEmpty { url })
        }

        var firstName = ""
        var lastName = ""

        for ((fid, fTitle, value) in rawAttrs) {
            val k = "${fid.lowercase()} ${fTitle.lowercase()}"
            when {
                "firstname" in k || fid == "firstname" -> firstName = value
                "lastname" in k || fid == "lastname" -> lastName = value
                "cardholder" in k -> addStd(CanonicalFieldKeys.CARDHOLDER, value)
                "ccnum" in k || "card number" in k -> addStd(CanonicalFieldKeys.CARD_NUMBER, value, FieldValueType.CARD_NUMBER)
                fid == "type" && category == VaultCategory.PAYMENT_CARD -> addStd(CanonicalFieldKeys.CARD_NETWORK, value)
                "validfrom" in k -> addStd(CanonicalFieldKeys.VALID_FROM, value, FieldValueType.DATE_ISO)
                "expiry" in k || "expiration" in k -> addStd(CanonicalFieldKeys.EXPIRY_DATE, value, FieldValueType.DATE_ISO)
                "cvv" in k || "verification number" in k -> addStd(CanonicalFieldKeys.CVV, value, FieldValueType.SENSITIVE_SECRET)
                fid == "pin" || "telephonepin" in k || "card pin" in k -> addStd(CanonicalFieldKeys.PIN, value, FieldValueType.SENSITIVE_SECRET)
                "bankname" in k || fid == "bank" || "issuing bank" in k -> addStd(CanonicalFieldKeys.ISSUING_BANK, value)
                "creditlimit" in k -> addStd(CanonicalFieldKeys.CREDIT_LIMIT, value)
                "iban" in k -> addStd(CanonicalFieldKeys.IBAN, value, FieldValueType.IBAN)
                "swift" in k || "bic" in k -> addStd(CanonicalFieldKeys.BIC, value)
                "accountno" in k || "account number" in k -> addStd(CanonicalFieldKeys.ACCOUNT_NUMBER, value, FieldValueType.SENSITIVE_SECRET)
                "routingno" in k || "routing" in k -> addStd(CanonicalFieldKeys.ROUTING_CODE, value)
                "accounttype" in k -> addStd(CanonicalFieldKeys.ACCOUNT_TYPE, value)
                "owner" in k -> addStd(CanonicalFieldKeys.ACCOUNT_HOLDER, value)
                "org_name" in k || "organization" in k || "company" in k -> {
                    if (category == VaultCategory.INSURANCE_CARD) addStd(CanonicalFieldKeys.INSURER_NAME, value)
                    else addStd(CanonicalFieldKeys.ORGANIZATION, value)
                }
                "member_name" in k -> addStd(CanonicalFieldKeys.MEMBER_NAME, value)
                "membership_no" in k || "member id" in k -> addStd(CanonicalFieldKeys.MEMBERSHIP_ID, value)
                "member_since" in k -> addStd(CanonicalFieldKeys.MEMBER_SINCE, value, FieldValueType.DATE_ISO)
                "fullname" in k -> addStd(CanonicalFieldKeys.FULL_NAME, value)
                "birthdate" in k || fid == "dob" -> addStd(CanonicalFieldKeys.BIRTHDATE, value, FieldValueType.DATE_ISO)
                "issuing_country" in k -> addStd(CanonicalFieldKeys.ISSUING_COUNTRY, value)
                "nationality" in k -> addStd(CanonicalFieldKeys.NATIONALITY, value)
                "issuing_authority" in k -> addStd(CanonicalFieldKeys.ISSUING_AUTHORITY, value)
                "issue_date" in k -> addStd(CanonicalFieldKeys.ISSUE_DATE, value, FieldValueType.DATE_ISO)
                fid == "number" && category == VaultCategory.OFFICIAL_DOCUMENT ->
                    addStd(CanonicalFieldKeys.DOCUMENT_NUMBER, value, FieldValueType.SENSITIVE_SECRET)
                fid == "number" && category == VaultCategory.INSURANCE_CARD ->
                    addStd(CanonicalFieldKeys.INSURANCE_NUMBER, value, FieldValueType.SENSITIVE_SECRET)
                "address" in k -> {
                    if (category == VaultCategory.PAYMENT_CARD) addStd(CanonicalFieldKeys.BILLING_ADDRESS, value, FieldValueType.MULTILINE_ADDRESS)
                    else if (category == VaultCategory.BANK_ACCOUNT) addStd(CanonicalFieldKeys.BRANCH_ADDRESS, value, FieldValueType.MULTILINE_ADDRESS)
                    else addStd(CanonicalFieldKeys.ADDRESS, value, FieldValueType.MULTILINE_ADDRESS)
                }
                "email" in k -> addStd(CanonicalFieldKeys.EMAIL, value, FieldValueType.EMAIL)
                "phone" in k -> addStd(CanonicalFieldKeys.SUPPORT_PHONE, value, FieldValueType.PHONE)
                else -> {
                    out += VaultField(
                        id = "f-1p-custom-${idx++}",
                        labelKey = fTitle.ifBlank { fid.ifBlank { "Field" } },
                        isCustomLabel = true,
                        value = value.trim()
                    )
                }
            }
        }

        if (firstName.isNotBlank() || lastName.isNotBlank()) {
            addStd(CanonicalFieldKeys.FULL_NAME, "$firstName $lastName".trim())
        }
        if (notesPlain.isNotBlank()) {
            val noteKey = if (category == VaultCategory.SECURE_NOTE) {
                CanonicalFieldKeys.MARKDOWN_NOTE
            } else {
                CanonicalFieldKeys.NOTES
            }
            addStd(noteKey, notesPlain, FieldValueType.MARKDOWN)
        }
        return out
    }

    private fun serializeItemTo1PasswordJson(item: VaultItemPayload, exportedSec: Long): JsonObject {
        val catUuid = when (item.category) {
            VaultCategory.PAYMENT_CARD -> "002"
            VaultCategory.BANK_ACCOUNT -> "104"
            VaultCategory.INSURANCE_CARD -> "108"
            VaultCategory.MEMBERSHIP_CARD -> "105"
            VaultCategory.IDENTITY -> "004"
            VaultCategory.OFFICIAL_DOCUMENT -> "109"
            VaultCategory.SECURE_NOTE -> "003"
        }

        val sectionFields = buildJsonArray {
            for (f in item.fields) {
                if (f.labelKey == CanonicalFieldKeys.MARKDOWN_NOTE || f.labelKey == CanonicalFieldKeys.NOTES) continue
                val onePassId = mapSafePocketKeyTo1PasswordFieldId(f.labelKey, f.isCustomLabel)
                val valTypeKey = if (f.isSensitive) "concealed" else "string"
                addJsonObject {
                    put("id", onePassId)
                    put("title", if (f.isCustomLabel) f.labelKey else onePassId)
                    putJsonObject("value") {
                        put(valTypeKey, f.value)
                    }
                }
            }
        }

        val notesPlain = listOf(
            item.fieldValue(CanonicalFieldKeys.MARKDOWN_NOTE),
            item.fieldValue(CanonicalFieldKeys.NOTES)
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        return buildJsonObject {
            put("uuid", item.id.removePrefix("1p-"))
            put("categoryUuid", catUuid)
            put("favIndex", if (item.favorite) 1 else 0)
            put("createdAt", item.createdAtEpochMs / 1000L)
            put("updatedAt", (item.updatedAtEpochMs / 1000L).coerceAtLeast(exportedSec))
            put("state", "active")
            putJsonObject("overview") {
                put("title", item.title)
                put("ainfo", item.subtitle)
                val dom = item.fieldValue(CanonicalFieldKeys.INSTITUTION_DOMAIN)
                    .ifBlank { item.cardTheme?.institutionDomain.orEmpty() }
                if (dom.isNotBlank()) put("url", if (dom.startsWith("http")) dom else "https://$dom")
                putJsonArray("tags") {
                    item.tags.forEach { add(it) }
                }
            }
            putJsonObject("details") {
                put("notesPlain", notesPlain)
                putJsonArray("sections") {
                    addJsonObject {
                        put("name", "details")
                        put("title", "")
                        put("fields", sectionFields)
                    }
                }
            }
        }
    }

    private fun mapSafePocketKeyTo1PasswordFieldId(key: String, isCustom: Boolean): String {
        if (isCustom) return key
        return when (key) {
            CanonicalFieldKeys.CARDHOLDER -> "cardholder"
            CanonicalFieldKeys.CARD_NUMBER -> "ccnum"
            CanonicalFieldKeys.CARD_NETWORK -> "type"
            CanonicalFieldKeys.VALID_FROM -> "validFrom"
            CanonicalFieldKeys.EXPIRY_DATE -> "expiry"
            CanonicalFieldKeys.CVV -> "cvv"
            CanonicalFieldKeys.PIN -> "pin"
            CanonicalFieldKeys.ISSUING_BANK -> "bank"
            CanonicalFieldKeys.CREDIT_LIMIT -> "creditLimit"
            CanonicalFieldKeys.IBAN -> "iban"
            CanonicalFieldKeys.BIC -> "swift"
            CanonicalFieldKeys.ACCOUNT_HOLDER -> "owner"
            CanonicalFieldKeys.ACCOUNT_NUMBER -> "accountNo"
            CanonicalFieldKeys.ROUTING_CODE -> "routingNo"
            CanonicalFieldKeys.ACCOUNT_TYPE -> "accountType"
            CanonicalFieldKeys.ORGANIZATION, CanonicalFieldKeys.INSURER_NAME -> "org_name"
            CanonicalFieldKeys.MEMBER_NAME -> "member_name"
            CanonicalFieldKeys.MEMBERSHIP_ID -> "membership_no"
            CanonicalFieldKeys.FULL_NAME -> "fullname"
            CanonicalFieldKeys.BIRTHDATE -> "dob"
            CanonicalFieldKeys.DOCUMENT_NUMBER, CanonicalFieldKeys.INSURANCE_NUMBER -> "number"
            CanonicalFieldKeys.ISSUING_COUNTRY -> "issuing_country"
            CanonicalFieldKeys.ISSUING_AUTHORITY -> "issuing_authority"
            CanonicalFieldKeys.NATIONALITY -> "nationality"
            CanonicalFieldKeys.ISSUE_DATE -> "issue_date"
            else -> key.removePrefix("field_")
        }
    }

    private fun parseSafePocketCsvRow(map: Map<String, String>, fallbackEpochMs: Long): VaultItemPayload? {
        val catRaw = getByAliases(map, "category").uppercase()
        val category = VaultCategory.entries.firstOrNull { it.name == catRaw } ?: VaultCategory.SECURE_NOTE
        val title = getByAliases(map, "title").ifBlank { return null }
        val subtitle = getByAliases(map, "subtitle")
        val id = getByAliases(map, "id").ifBlank { "sp-csv-$fallbackEpochMs" }
        val favorite = getByAliases(map, "favorite").equals("true", ignoreCase = true)
        val pinned = getByAliases(map, "pinned").equals("true", ignoreCase = true)
        val tags = getByAliases(map, "tags").split('|', ',').map { it.trim() }.filter { it.isNotEmpty() }

        val fieldsJson = getByAliases(map, "fields_json")
        val fields: List<VaultField> = if (fieldsJson.isNotBlank()) {
            runCatching { compactJson.decodeFromString<List<VaultField>>(fieldsJson) }.getOrDefault(emptyList())
        } else {
            val summary = getByAliases(map, "fields_summary")
            summary.split("; ").mapIndexedNotNull { i, pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else VaultField(
                    id = "f-sp-$i",
                    labelKey = pair.substring(0, idx).trim(),
                    isCustomLabel = !pair.substring(0, idx).trim().startsWith("field_"),
                    value = pair.substring(idx + 1).trim()
                )
            }
        }

        return VaultItemPayload(
            id = id,
            category = category,
            title = title,
            subtitle = subtitle,
            favorite = favorite,
            pinned = pinned,
            tags = tags,
            fields = fields,
            createdAtEpochMs = fallbackEpochMs,
            updatedAtEpochMs = fallbackEpochMs
        )
    }

    private fun inferCategoryFromCsvRow(rawType: String, map: Map<String, String>): VaultCategory {
        when {
            "card" in rawType && "insur" !in rawType && "member" !in rawType -> return VaultCategory.PAYMENT_CARD
            "kreditkarte" in rawType || "credit" in rawType || "debit" in rawType -> return VaultCategory.PAYMENT_CARD
            "bank" in rawType || "konto" in rawType -> return VaultCategory.BANK_ACCOUNT
            "insur" in rawType || "medical" in rawType || "versicherung" in rawType || "krankenkasse" in rawType ->
                return VaultCategory.INSURANCE_CARD
            "member" in rawType || "reward" in rawType || "loyalty" in rawType || "mitglied" in rawType ->
                return VaultCategory.MEMBERSHIP_CARD
            "identit" in rawType -> return VaultCategory.IDENTITY
            "passport" in rawType || "driver" in rawType || "license" in rawType || "reisepass" in rawType || "ausweis" in rawType || "ssn" in rawType ->
                return VaultCategory.OFFICIAL_DOCUMENT
            "note" in rawType || "notiz" in rawType || "login" in rawType -> return VaultCategory.SECURE_NOTE
        }
        if (getByAliases(map, "number", "card number", "ccnum", "kartennummer").isNotBlank()) return VaultCategory.PAYMENT_CARD
        if (getByAliases(map, "iban").isNotBlank()) return VaultCategory.BANK_ACCOUNT
        if (getByAliases(map, "membership id", "mitgliedsnummer").isNotBlank()) return VaultCategory.MEMBERSHIP_CARD
        return VaultCategory.SECURE_NOTE
    }

    private fun buildFieldsFromCsvRow(
        category: VaultCategory,
        map: Map<String, String>,
        isLogin: Boolean
    ): List<VaultField> {
        val out = mutableListOf<VaultField>()
        var idx = 0
        fun add(key: String, v: String, t: FieldValueType = FieldValueType.TEXT) {
            if (v.isNotBlank() && out.none { it.labelKey == key }) {
                out += VaultField("f-csv-${idx++}", key, false, v.trim(), t)
            }
        }

        if (isLogin) {
            val u = getByAliases(map, "username", "benutzername")
            val p = getByAliases(map, "password", "passwort")
            val url = getByAliases(map, "url", "website")
            val n = getByAliases(map, "notes", "notizen")
            if (url.isNotBlank()) add(CanonicalFieldKeys.INSTITUTION_DOMAIN, url)
            val md = listOfNotNull(
                url.takeIf { it.isNotBlank() }?.let { "URL: $it" },
                u.takeIf { it.isNotBlank() }?.let { "Username: $it" },
                p.takeIf { it.isNotBlank() }?.let { "Password: $it" },
                n.takeIf { it.isNotBlank() }
            ).joinToString("\n")
            add(CanonicalFieldKeys.MARKDOWN_NOTE, md, FieldValueType.MARKDOWN)
            return out
        }

        add(CanonicalFieldKeys.CARDHOLDER, getByAliases(map, "cardholder name", "cardholder", "karteninhaber"))
        add(CanonicalFieldKeys.CARD_NUMBER, getByAliases(map, "number", "card number", "ccnum", "kartennummer"), FieldValueType.CARD_NUMBER)
        add(CanonicalFieldKeys.CARD_NETWORK, getByAliases(map, "brand", "network", "kartennetzwerk"))
        add(CanonicalFieldKeys.VALID_FROM, getByAliases(map, "valid from", "gültig ab"), FieldValueType.DATE_ISO)
        add(CanonicalFieldKeys.EXPIRY_DATE, getByAliases(map, "expiry date", "expiration date", "ablaufdatum", "gültig bis"), FieldValueType.DATE_ISO)
        add(CanonicalFieldKeys.CVV, getByAliases(map, "verification number", "cvv", "cvc"), FieldValueType.SENSITIVE_SECRET)
        add(CanonicalFieldKeys.PIN, getByAliases(map, "pin"), FieldValueType.SENSITIVE_SECRET)
        if (category == VaultCategory.INSURANCE_CARD) {
            add(CanonicalFieldKeys.INSURER_NAME, getByAliases(map, "issuing bank", "insurer", "provider", "krankenkasse", "versicherung"))
            add(CanonicalFieldKeys.INSURANCE_NUMBER, getByAliases(map, "account number", "policy number", "versichertennummer"), FieldValueType.SENSITIVE_SECRET)
        } else {
            add(CanonicalFieldKeys.ISSUING_BANK, getByAliases(map, "issuing bank", "bank", "bank name"))
            add(CanonicalFieldKeys.ACCOUNT_NUMBER, getByAliases(map, "account number", "kontonummer"), FieldValueType.SENSITIVE_SECRET)
        }
        add(CanonicalFieldKeys.ACCOUNT_HOLDER, getByAliases(map, "account holder", "owner", "kontoinhaber"))
        add(CanonicalFieldKeys.IBAN, getByAliases(map, "iban"), FieldValueType.IBAN)
        add(CanonicalFieldKeys.BIC, getByAliases(map, "swift", "bic"))
        add(CanonicalFieldKeys.ROUTING_CODE, getByAliases(map, "routing number", "blz"))
        add(CanonicalFieldKeys.FULL_NAME, getByAliases(map, "full name", "fullname", "name_full"))
        add(CanonicalFieldKeys.BIRTHDATE, getByAliases(map, "birthdate", "date of birth", "geburtsdatum"), FieldValueType.DATE_ISO)
        add(CanonicalFieldKeys.DOCUMENT_NUMBER, getByAliases(map, "document number", "ausweisnummer"), FieldValueType.SENSITIVE_SECRET)
        add(CanonicalFieldKeys.ORGANIZATION, getByAliases(map, "organization", "company", "verein"))
        add(CanonicalFieldKeys.MEMBERSHIP_ID, getByAliases(map, "membership id", "member id", "mitgliedsnummer"))
        add(CanonicalFieldKeys.INSTITUTION_DOMAIN, getByAliases(map, "website", "url", "domain"))
        add(CanonicalFieldKeys.EMAIL, getByAliases(map, "email", "e-mail"), FieldValueType.EMAIL)
        add(CanonicalFieldKeys.PHONE, getByAliases(map, "phone", "telefon"), FieldValueType.PHONE)
        add(CanonicalFieldKeys.ADDRESS, getByAliases(map, "address", "adresse"), FieldValueType.MULTILINE_ADDRESS)

        val notes = getByAliases(map, "notes", "notizen", "markdown_note")
        if (notes.isNotBlank()) {
            val nk = if (category == VaultCategory.SECURE_NOTE) CanonicalFieldKeys.MARKDOWN_NOTE else CanonicalFieldKeys.NOTES
            add(nk, notes, FieldValueType.MARKDOWN)
        }
        return out
    }

    private fun getByAliases(map: Map<String, String>, vararg aliases: String): String {
        for (a in aliases) {
            val hit = map.entries.firstOrNull { it.key.equals(a, ignoreCase = true) }?.value
            if (!hit.isNullOrBlank()) return hit
        }
        return ""
    }

    fun parseRfc4180Csv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        // Detect ';' vs ',' on first line outside quotes
        val firstLine = input.lineSequence().firstOrNull().orEmpty()
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','

        while (i < input.length) {
            val ch = input[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') {
                        cell.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    delimiter -> {
                        currentRow += cell.toString()
                        cell.clear()
                    }
                    '\r' -> {
                        if (i + 1 < input.length && input[i + 1] == '\n') i++
                        currentRow += cell.toString()
                        cell.clear()
                        rows += currentRow
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow += cell.toString()
                        cell.clear()
                        rows += currentRow
                        currentRow = mutableListOf()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += cell.toString()
            rows += currentRow
        }
        return rows
    }

    private fun escCsv(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    // =========================================================================
    // PURE-KOTLIN PKZIP STORED (METHOD 0) FALLBACK FOR NON-JVM TARGETS
    // =========================================================================

    private fun createStoredZipArchive(entries: Map<String, ByteArray>): ByteArray {
        val localChunks = mutableListOf<ByteArray>()
        val centralChunks = mutableListOf<ByteArray>()
        var offset = 0

        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)

            val local = ByteArray(30 + nameBytes.size + data.size)
            putLe32(local, 0, 0x04034b50)
            putLe16(local, 4, 20)
            putLe16(local, 6, 0x0800) // UTF-8
            putLe16(local, 8, 0)      // STORED
            putLe16(local, 10, 0)
            putLe16(local, 12, 0)
            putLe32(local, 14, crc)
            putLe32(local, 18, data.size)
            putLe32(local, 22, data.size)
            putLe16(local, 26, nameBytes.size)
            putLe16(local, 28, 0)
            nameBytes.copyInto(local, 30)
            data.copyInto(local, 30 + nameBytes.size)
            localChunks += local

            val central = ByteArray(46 + nameBytes.size)
            putLe32(central, 0, 0x02014b50)
            putLe16(central, 4, 20)
            putLe16(central, 6, 20)
            putLe16(central, 8, 0x0800)
            putLe16(central, 10, 0)
            putLe16(central, 12, 0)
            putLe16(central, 14, 0)
            putLe32(central, 16, crc)
            putLe32(central, 20, data.size)
            putLe32(central, 24, data.size)
            putLe16(central, 28, nameBytes.size)
            putLe16(central, 30, 0)
            putLe16(central, 32, 0)
            putLe16(central, 34, 0)
            putLe16(central, 36, 0)
            putLe32(central, 38, 0)
            putLe32(central, 42, offset)
            nameBytes.copyInto(central, 46)
            centralChunks += central

            offset += local.size
        }

        val centralSize = centralChunks.sumOf { it.size }
        val eocd = ByteArray(22)
        putLe32(eocd, 0, 0x06054b50)
        putLe16(eocd, 8, entries.size)
        putLe16(eocd, 10, entries.size)
        putLe32(eocd, 12, centralSize)
        putLe32(eocd, 16, offset)

        val total = offset + centralSize + 22
        val out = ByteArray(total)
        var pos = 0
        for (c in localChunks) { c.copyInto(out, pos); pos += c.size }
        for (c in centralChunks) { c.copyInto(out, pos); pos += c.size }
        eocd.copyInto(out, pos)
        return out
    }

    private fun extractStoredZipEntries(zip: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var pos = 0
        while (pos + 30 <= zip.size) {
            val sig = readLe32(zip, pos)
            if (sig != 0x04034b50) break
            val method = readLe16(zip, pos + 8)
            val compSize = readLe32(zip, pos + 18)
            val nameLen = readLe16(zip, pos + 26)
            val extraLen = readLe16(zip, pos + 28)
            val dataStart = pos + 30 + nameLen + extraLen
            if (dataStart + compSize > zip.size) break
            val name = zip.copyOfRange(pos + 30, pos + 30 + nameLen).decodeToString()
            if (method == 0) {
                out[name] = zip.copyOfRange(dataStart, dataStart + compSize)
            }
            pos = dataStart + compSize
        }
        return out
    }

    private fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            for (k in 0 until 8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor -0x12477ce0 else (crc ushr 1)
            }
        }
        return crc.inv()
    }

    private fun putLe16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun readLe16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
}
