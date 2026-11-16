package com.safepocket.shared.brand

import com.safepocket.shared.model.CanonicalFieldKeys
import com.safepocket.shared.model.CardVisualTheme
import com.safepocket.shared.model.VaultCategory
import com.safepocket.shared.model.VaultItemPayload

/**
 * Offline Zero-Leak Institution & Network/Type Brand Engine across all 7 SafePocket categories.
 *
 * Detects:
 * 1. The **Institution** (e.g. Deutsche Bank `#0018A8`, Sparkasse `#FF0000`, ING `#FF6200`, N26,
 *    Revolut, Techniker Krankenkasse, AOK, Allianz, ADAC, Deutsche Bahn, Lufthansa Miles & More,
 *    PAYBACK, Federal Republic of Germany, EU, Hellenic Republic, etc.) from Title, Issuing Bank,
 *    Insurer, Organization, Domain, BIC/SWIFT, or IBAN routing code (BLZ).
 * 2. The **Card Network / Category Type Badge** (e.g. `VISA`, `MASTERCARD`, `AMEX`, `GIROCARD`,
 *    `MAESTRO`, `SEPA`, `EGK`, `EHIC`, `PKV`, `PASSPORT`, `NATIONAL_ID`, `DRIVERS_LICENSE`,
 *    `BARCODE`, `VAULT_NOTE`) from Card Number BIN/IIN, explicit Network field, or category subtype.
 */
data class InstitutionBrandProfile(
    val id: String,
    val displayName: String,
    val primaryColorHex: String,
    val secondaryColorHex: String,
    val badgeMonogram: String,
    val logoKind: String, // "DB_SLASH" | "SPARKASSE_S" | "COMMERZ_RIBBON" | "ING_LION" | "N26_BAR" | "REVOLUT_R" | "TK_BADGE" | "ADAC_BADGE" | "DB_BAHN" | "EU_STARS" | "MONOGRAM"
    val canonicalDomain: String,
    val keywords: List<String>,
    val bicPrefixes: List<String> = emptyList(),
    val germanBlzPrefixes: List<String> = emptyList()
)

data class ResolvedDualBrand(
    val institution: InstitutionBrandProfile?,
    val inferredDomain: String?,
    val networkOrTypeCode: String, // "VISA" | "MASTERCARD" | "AMEX" | "GIROCARD" | "MAESTRO" | "SEPA" | "EGK" | "EHIC" | "PKV" | "PASSPORT" | "NATIONAL_ID" | "DRIVERS_LICENSE" | "BARCODE" | "NOTE"
    val networkDisplayLabel: String,
    val effectiveColorHex: String?
)

object InstitutionBrandRegistry {

    val CATALOG: List<InstitutionBrandProfile> = listOf(
        // --- German & European Banks / FinTechs ---
        InstitutionBrandProfile(
            id = "deutsche_bank",
            displayName = "Deutsche Bank",
            primaryColorHex = "#0018A8",
            secondaryColorHex = "#002B80",
            badgeMonogram = "DB",
            logoKind = "DB_SLASH",
            canonicalDomain = "deutsche-bank.de",
            keywords = listOf("deutsche bank", "deutsche-bank", "norisbank"),
            bicPrefixes = listOf("DEUTDE", "DEUTDEDB", "DEUTDEFF"),
            germanBlzPrefixes = listOf("10070000", "10070024", "50070010", "50070024", "70070010", "70070024", "20070000", "37070024")
        ),
        InstitutionBrandProfile(
            id = "sparkasse",
            displayName = "Sparkasse",
            primaryColorHex = "#E2001A",
            secondaryColorHex = "#9E0012",
            badgeMonogram = "S",
            logoKind = "SPARKASSE_S",
            canonicalDomain = "sparkasse.de",
            keywords = listOf("sparkasse", "berliner sparkasse", "haspa", "stadtsparkasse", "kreissparkasse", "frankfurter sparkasse", "nospa"),
            bicPrefixes = listOf("BELADEBE", "HASPDEHH", "SSKMDEMM", "HELADEF1", "COLSDE33"),
            germanBlzPrefixes = listOf("10050000", "20050550", "70150000", "50050201", "37050198")
        ),
        InstitutionBrandProfile(
            id = "commerzbank",
            displayName = "Commerzbank",
            primaryColorHex = "#002E3C",
            secondaryColorHex = "#004B61",
            badgeMonogram = "CB",
            logoKind = "COMMERZ_RIBBON",
            canonicalDomain = "commerzbank.de",
            keywords = listOf("commerzbank", "comdirect"),
            bicPrefixes = listOf("COBADE", "COBADEFF", "COBADEHD"),
            germanBlzPrefixes = listOf("10040000", "37040044", "50040000", "20041111", "20041133")
        ),
        InstitutionBrandProfile(
            id = "ing",
            displayName = "ING",
            primaryColorHex = "#FF6200",
            secondaryColorHex = "#1E2656",
            badgeMonogram = "ING",
            logoKind = "ING_LION",
            canonicalDomain = "ing.de",
            keywords = listOf("ing-diba", "ing diba", "ing bank", "ing giro", "ing"),
            bicPrefixes = listOf("INGDDEFF", "INGBNL2A", "BBRUBEBB"),
            germanBlzPrefixes = listOf("50010517")
        ),
        InstitutionBrandProfile(
            id = "dkb",
            displayName = "DKB",
            primaryColorHex = "#148DEA",
            secondaryColorHex = "#0B2545",
            badgeMonogram = "DKB",
            logoKind = "MONOGRAM",
            canonicalDomain = "dkb.de",
            keywords = listOf("dkb", "deutsche kreditbank"),
            bicPrefixes = listOf("BYLADEM1001"),
            germanBlzPrefixes = listOf("12030000")
        ),
        InstitutionBrandProfile(
            id = "n26",
            displayName = "N26",
            primaryColorHex = "#1A7F72",
            secondaryColorHex = "#0F2928",
            badgeMonogram = "N26",
            logoKind = "N26_BAR",
            canonicalDomain = "n26.com",
            keywords = listOf("n26", "n26 bank", "n26 metal"),
            bicPrefixes = listOf("NTSBDEB1"),
            germanBlzPrefixes = listOf("10011001")
        ),
        InstitutionBrandProfile(
            id = "revolut",
            displayName = "Revolut",
            primaryColorHex = "#0B1426",
            secondaryColorHex = "#1E3A8A",
            badgeMonogram = "R",
            logoKind = "REVOLUT_R",
            canonicalDomain = "revolut.com",
            keywords = listOf("revolut"),
            bicPrefixes = listOf("REVOLT21", "REVODEB2")
        ),
        InstitutionBrandProfile(
            id = "wise",
            displayName = "Wise",
            primaryColorHex = "#163300",
            secondaryColorHex = "#2D6A00",
            badgeMonogram = "W",
            logoKind = "MONOGRAM",
            canonicalDomain = "wise.com",
            keywords = listOf("wise", "transferwise"),
            bicPrefixes = listOf("TRWIBEB1", "TRWIGB2L")
        ),
        InstitutionBrandProfile(
            id = "postbank",
            displayName = "Postbank",
            primaryColorHex = "#0018A8",
            secondaryColorHex = "#D4A000",
            badgeMonogram = "PB",
            logoKind = "MONOGRAM",
            canonicalDomain = "postbank.de",
            keywords = listOf("postbank"),
            bicPrefixes = listOf("PBNKDEFF"),
            germanBlzPrefixes = listOf("10010010", "50010060", "70010080", "20010020")
        ),
        InstitutionBrandProfile(
            id = "volksbank",
            displayName = "Volksbank Raiffeisenbank",
            primaryColorHex = "#003D7C",
            secondaryColorHex = "#EA5B0C",
            badgeMonogram = "VR",
            logoKind = "MONOGRAM",
            canonicalDomain = "vr.de",
            keywords = listOf("volksbank", "raiffeisenbank", "vr bank", "berliner volksbank", "gls bank", "sparda"),
            bicPrefixes = listOf("BEVODEBB", "GENODE")
        ),
        InstitutionBrandProfile(
            id = "santander",
            displayName = "Santander",
            primaryColorHex = "#EC0000",
            secondaryColorHex = "#990000",
            badgeMonogram = "SAN",
            logoKind = "MONOGRAM",
            canonicalDomain = "santander.de",
            keywords = listOf("santander"),
            bicPrefixes = listOf("SCFBDE33", "BSCHESMM")
        ),
        InstitutionBrandProfile(
            id = "unicredit",
            displayName = "HypoVereinsbank • UniCredit",
            primaryColorHex = "#007A91",
            secondaryColorHex = "#004957",
            badgeMonogram = "HVB",
            logoKind = "MONOGRAM",
            canonicalDomain = "hvb.de",
            keywords = listOf("hypovereinsbank", "unicredit", "hvb"),
            bicPrefixes = listOf("HYVEDEMM", "UNCRITMM")
        ),
        InstitutionBrandProfile(
            id = "trade_republic",
            displayName = "Trade Republic",
            primaryColorHex = "#111418",
            secondaryColorHex = "#2A3038",
            badgeMonogram = "TR",
            logoKind = "MONOGRAM",
            canonicalDomain = "traderepublic.com",
            keywords = listOf("trade republic", "traderepublic"),
            bicPrefixes = listOf("TRBKDEBB")
        ),
        InstitutionBrandProfile(
            id = "consorsbank",
            displayName = "Consorsbank • BNP Paribas",
            primaryColorHex = "#008755",
            secondaryColorHex = "#005538",
            badgeMonogram = "BNP",
            logoKind = "MONOGRAM",
            canonicalDomain = "consorsbank.de",
            keywords = listOf("consorsbank", "bnp paribas", "hello bank"),
            bicPrefixes = listOf("CSDBDE71", "BNPAFRPP")
        ),
        InstitutionBrandProfile(
            id = "amex_issuer",
            displayName = "American Express",
            primaryColorHex = "#006FCF",
            secondaryColorHex = "#003E78",
            badgeMonogram = "AMEX",
            logoKind = "MONOGRAM",
            canonicalDomain = "americanexpress.com",
            keywords = listOf("american express", "amex", "payback american express")
        ),
        InstitutionBrandProfile(
            id = "barclays",
            displayName = "Barclays",
            primaryColorHex = "#00AEEF",
            secondaryColorHex = "#00395D",
            badgeMonogram = "BAR",
            logoKind = "MONOGRAM",
            canonicalDomain = "barclays.de",
            keywords = listOf("barclays", "barclaycard"),
            bicPrefixes = listOf("BARCDEFF", "BARCGB22")
        ),
        InstitutionBrandProfile(
            id = "hanseatic",
            displayName = "Hanseatic Bank",
            primaryColorHex = "#C5003E",
            secondaryColorHex = "#7A0026",
            badgeMonogram = "HB",
            logoKind = "MONOGRAM",
            canonicalDomain = "hanseaticbank.de",
            keywords = listOf("hanseatic", "genialcard", "awa7")
        ),
        InstitutionBrandProfile(
            id = "klarna",
            displayName = "Klarna",
            primaryColorHex = "#17120F",
            secondaryColorHex = "#6B244B",
            badgeMonogram = "K.",
            logoKind = "MONOGRAM",
            canonicalDomain = "klarna.com",
            keywords = listOf("klarna")
        ),
        InstitutionBrandProfile(
            id = "paypal",
            displayName = "PayPal",
            primaryColorHex = "#003087",
            secondaryColorHex = "#0070BA",
            badgeMonogram = "PP",
            logoKind = "MONOGRAM",
            canonicalDomain = "paypal.com",
            keywords = listOf("paypal")
        ),
        InstitutionBrandProfile(
            id = "alpha_bank",
            displayName = "Alpha Bank",
            primaryColorHex = "#003366",
            secondaryColorHex = "#0055A5",
            badgeMonogram = "Α",
            logoKind = "MONOGRAM",
            canonicalDomain = "alpha.gr",
            keywords = listOf("alpha bank", "alphabank", "αλφα"),
            bicPrefixes = listOf("CRBAGRAA")
        ),
        InstitutionBrandProfile(
            id = "eurobank",
            displayName = "Eurobank",
            primaryColorHex = "#002F6C",
            secondaryColorHex = "#C8102E",
            badgeMonogram = "EB",
            logoKind = "MONOGRAM",
            canonicalDomain = "eurobank.gr",
            keywords = listOf("eurobank", "γιουρομπανκ"),
            bicPrefixes = listOf("EFGBGRAA")
        ),
        InstitutionBrandProfile(
            id = "nbg",
            displayName = "National Bank of Greece",
            primaryColorHex = "#005F6B",
            secondaryColorHex = "#003840",
            badgeMonogram = "ΕΤΕ",
            logoKind = "MONOGRAM",
            canonicalDomain = "nbg.gr",
            keywords = listOf("national bank of greece", "εθνικη τραπεζα", "nbg"),
            bicPrefixes = listOf("ETHNGRAA")
        ),
        InstitutionBrandProfile(
            id = "piraeus",
            displayName = "Piraeus Bank",
            primaryColorHex = "#003A5D",
            secondaryColorHex = "#D49F00",
            badgeMonogram = "Π",
            logoKind = "MONOGRAM",
            canonicalDomain = "piraeusbank.gr",
            keywords = listOf("piraeus", "πειραιως", "winbank"),
            bicPrefixes = listOf("PIRBGRAA")
        ),
        InstitutionBrandProfile(
            id = "intesa",
            displayName = "Intesa Sanpaolo",
            primaryColorHex = "#258900",
            secondaryColorHex = "#145200",
            badgeMonogram = "ISP",
            logoKind = "MONOGRAM",
            canonicalDomain = "intesasanpaolo.com",
            keywords = listOf("intesa", "sanpaolo", "isybank"),
            bicPrefixes = listOf("BCITITMM")
        ),
        InstitutionBrandProfile(
            id = "bbva",
            displayName = "BBVA",
            primaryColorHex = "#072146",
            secondaryColorHex = "#1464A5",
            badgeMonogram = "BBVA",
            logoKind = "MONOGRAM",
            canonicalDomain = "bbva.es",
            keywords = listOf("bbva"),
            bicPrefixes = listOf("BBVAESMM")
        ),
        InstitutionBrandProfile(
            id = "ubs",
            displayName = "UBS",
            primaryColorHex = "#E60000",
            secondaryColorHex = "#4A0000",
            badgeMonogram = "UBS",
            logoKind = "MONOGRAM",
            canonicalDomain = "ubs.com",
            keywords = listOf("ubs", "credit suisse"),
            bicPrefixes = listOf("UBSWCHZH")
        ),
        InstitutionBrandProfile(
            id = "chase",
            displayName = "JPMorgan Chase",
            primaryColorHex = "#117ACA",
            secondaryColorHex = "#0A4370",
            badgeMonogram = "CH",
            logoKind = "MONOGRAM",
            canonicalDomain = "chase.com",
            keywords = listOf("chase", "jpmorgan", "sapphire")
        ),

        // --- Insurance Providers (GKV / PKV / International) ---
        InstitutionBrandProfile(
            id = "tk",
            displayName = "Techniker Krankenkasse",
            primaryColorHex = "#009FE3",
            secondaryColorHex = "#005F8C",
            badgeMonogram = "TK",
            logoKind = "TK_BADGE",
            canonicalDomain = "tk.de",
            keywords = listOf("techniker krankenkasse", "tk ", "tk-", "(tk)", "die techniker")
        ),
        InstitutionBrandProfile(
            id = "aok",
            displayName = "AOK Gesundheitskasse",
            primaryColorHex = "#005E3F",
            secondaryColorHex = "#008559",
            badgeMonogram = "AOK",
            logoKind = "MONOGRAM",
            canonicalDomain = "aok.de",
            keywords = listOf("aok", "gesundheitskasse")
        ),
        InstitutionBrandProfile(
            id = "barmer",
            displayName = "BARMER",
            primaryColorHex = "#007A3D",
            secondaryColorHex = "#004F27",
            badgeMonogram = "BM",
            logoKind = "MONOGRAM",
            canonicalDomain = "barmer.de",
            keywords = listOf("barmer")
        ),
        InstitutionBrandProfile(
            id = "dak",
            displayName = "DAK-Gesundheit",
            primaryColorHex = "#E85C0D",
            secondaryColorHex = "#9E3B00",
            badgeMonogram = "DAK",
            logoKind = "MONOGRAM",
            canonicalDomain = "dak.de",
            keywords = listOf("dak", "dak-gesundheit")
        ),
        InstitutionBrandProfile(
            id = "allianz",
            displayName = "Allianz",
            primaryColorHex = "#003781",
            secondaryColorHex = "#0057B8",
            badgeMonogram = "AZ",
            logoKind = "MONOGRAM",
            canonicalDomain = "allianz.de",
            keywords = listOf("allianz")
        ),
        InstitutionBrandProfile(
            id = "axa",
            displayName = "AXA",
            primaryColorHex = "#00008F",
            secondaryColorHex = "#D2001E",
            badgeMonogram = "AXA",
            logoKind = "MONOGRAM",
            canonicalDomain = "axa.de",
            keywords = listOf("axa")
        ),
        InstitutionBrandProfile(
            id = "huk",
            displayName = "HUK-COBURG",
            primaryColorHex = "#22252A",
            secondaryColorHex = "#B58E00",
            badgeMonogram = "HUK",
            logoKind = "MONOGRAM",
            canonicalDomain = "huk.de",
            keywords = listOf("huk", "huk-coburg", "huk24")
        ),
        InstitutionBrandProfile(
            id = "debeka",
            displayName = "Debeka",
            primaryColorHex = "#005CA9",
            secondaryColorHex = "#003B6F",
            badgeMonogram = "DBK",
            logoKind = "MONOGRAM",
            canonicalDomain = "debeka.de",
            keywords = listOf("debeka")
        ),
        InstitutionBrandProfile(
            id = "ergo",
            displayName = "ERGO",
            primaryColorHex = "#C80022",
            secondaryColorHex = "#7A0015",
            badgeMonogram = "ERGO",
            logoKind = "MONOGRAM",
            canonicalDomain = "ergo.de",
            keywords = listOf("ergo", "dkv")
        ),

        // --- Membership, Mobility & Loyalty ---
        InstitutionBrandProfile(
            id = "adac",
            displayName = "ADAC",
            primaryColorHex = "#212121",
            secondaryColorHex = "#B38F00",
            badgeMonogram = "ADAC",
            logoKind = "ADAC_BADGE",
            canonicalDomain = "adac.de",
            keywords = listOf("adac")
        ),
        InstitutionBrandProfile(
            id = "deutsche_bahn",
            displayName = "Deutsche Bahn • BahnCard",
            primaryColorHex = "#EC0016",
            secondaryColorHex = "#99000E",
            badgeMonogram = "DB",
            logoKind = "DB_BAHN",
            canonicalDomain = "bahn.de",
            keywords = listOf("bahncard", "deutsche bahn", "db navigator", "deutschlandticket", "d-ticket")
        ),
        InstitutionBrandProfile(
            id = "miles_and_more",
            displayName = "Lufthansa • Miles & More",
            primaryColorHex = "#05164D",
            secondaryColorHex = "#0A2A88",
            badgeMonogram = "LH",
            logoKind = "MONOGRAM",
            canonicalDomain = "miles-and-more.com",
            keywords = listOf("miles & more", "miles and more", "lufthansa", "senator", "frequent traveller")
        ),
        InstitutionBrandProfile(
            id = "payback",
            displayName = "PAYBACK",
            primaryColorHex = "#0046AA",
            secondaryColorHex = "#002B6B",
            badgeMonogram = "PB",
            logoKind = "MONOGRAM",
            canonicalDomain = "payback.de",
            keywords = listOf("payback")
        ),
        InstitutionBrandProfile(
            id = "ikea",
            displayName = "IKEA Family",
            primaryColorHex = "#0058A3",
            secondaryColorHex = "#003B6F",
            badgeMonogram = "IKEA",
            logoKind = "MONOGRAM",
            canonicalDomain = "ikea.com",
            keywords = listOf("ikea")
        ),
        InstitutionBrandProfile(
            id = "amazon",
            displayName = "Amazon Prime",
            primaryColorHex = "#131921",
            secondaryColorHex = "#232F3E",
            badgeMonogram = "AMZ",
            logoKind = "MONOGRAM",
            canonicalDomain = "amazon.de",
            keywords = listOf("amazon", "prime")
        ),
        InstitutionBrandProfile(
            id = "costco",
            displayName = "Costco Wholesale",
            primaryColorHex = "#005DAA",
            secondaryColorHex = "#E31837",
            badgeMonogram = "COST",
            logoKind = "MONOGRAM",
            canonicalDomain = "costco.com",
            keywords = listOf("costco")
        ),
        InstitutionBrandProfile(
            id = "urban_sports",
            displayName = "Urban Sports Club",
            primaryColorHex = "#102A43",
            secondaryColorHex = "#1E4E68",
            badgeMonogram = "USC",
            logoKind = "MONOGRAM",
            canonicalDomain = "urbansportsclub.com",
            keywords = listOf("urban sports", "fitx", "mcfit")
        ),

        // --- Official Issuers & National Document Authorities ---
        InstitutionBrandProfile(
            id = "gov_de",
            displayName = "Bundesrepublik Deutschland",
            primaryColorHex = "#5A1828",
            secondaryColorHex = "#3A0F1A",
            badgeMonogram = "DE",
            logoKind = "EU_STARS",
            canonicalDomain = "bund.de",
            keywords = listOf("bundesrepublik", "personalausweis", "reisepass", "berlin", "buergeramt", "bürgeramt", "deutschland", "finanzamt")
        ),
        InstitutionBrandProfile(
            id = "gov_gr",
            displayName = "Ελληνική Δημοκρατία",
            primaryColorHex = "#0D3B66",
            secondaryColorHex = "#1D5B96",
            badgeMonogram = "GR",
            logoKind = "EU_STARS",
            canonicalDomain = "gov.gr",
            keywords = listOf("ελληνικ", "διαβατήριο", "ταυτότητα", "αθήνα", "hellenic", "greece")
        ),
        InstitutionBrandProfile(
            id = "gov_eu",
            displayName = "European Union",
            primaryColorHex = "#003399",
            secondaryColorHex = "#001F66",
            badgeMonogram = "EU",
            logoKind = "EU_STARS",
            canonicalDomain = "europa.eu",
            keywords = listOf("european", "ehic", "schengen", "eu ")
        )
    )

    fun detectPaymentNetwork(cardNumber: String, explicitNetwork: String = "", titleAndSub: String = ""): Pair<String, String> {
        val combined = "$explicitNetwork $titleAndSub".lowercase()
        when {
            "girocard" in combined || "ec-karte" in combined -> return "GIROCARD" to "girocard"
            "visa" in combined -> return "VISA" to "VISA"
            "mastercard" in combined || "master card" in combined -> return "MASTERCARD" to "Mastercard"
            "amex" in combined || "american express" in combined -> return "AMEX" to "AMEX"
            "maestro" in combined -> return "MAESTRO" to "Maestro"
            "v pay" in combined || "vpay" in combined -> return "VPAY" to "V PAY"
            "discover" in combined -> return "DISCOVER" to "Discover"
            "diners" in combined -> return "DINERS" to "Diners"
            "unionpay" in combined -> return "UNIONPAY" to "UnionPay"
            "jcb" in combined -> return "JCB" to "JCB"
        }

        val digits = cardNumber.filter { it.isDigit() }
        if (digits.isNotEmpty()) {
            val two = digits.take(2).toIntOrNull() ?: 0
            val four = digits.take(4).toIntOrNull() ?: 0
            when {
                digits.startsWith("4") -> return "VISA" to "VISA"
                two in 51..55 || four in 2221..2720 -> return "MASTERCARD" to "Mastercard"
                two == 34 || two == 37 -> return "AMEX" to "AMEX"
                digits.startsWith("6011") || digits.startsWith("65") -> return "DISCOVER" to "Discover"
                two == 36 || two == 38 -> return "DINERS" to "Diners"
                two == 62 -> return "UNIONPAY" to "UnionPay"
                two == 35 -> return "JCB" to "JCB"
                two in listOf(50, 56, 57, 58, 67) -> return "MAESTRO" to "Maestro"
            }
        }
        return "CARD" to "CARD"
    }

    fun resolveForItem(item: VaultItemPayload): ResolvedDualBrand =
        resolve(
            category = item.category,
            title = item.title,
            subtitle = item.subtitle,
            fields = item.fields.filter { !it.isCustomLabel }.associate { it.labelKey to it.value },
            existingTheme = item.cardTheme
        )

    fun resolve(
        category: VaultCategory,
        title: String,
        subtitle: String,
        fields: Map<String, String>,
        existingTheme: CardVisualTheme? = null
    ): ResolvedDualBrand {
        val bankField = fields[CanonicalFieldKeys.ISSUING_BANK].orEmpty()
        val insurerField = fields[CanonicalFieldKeys.INSURER_NAME].orEmpty()
        val orgField = fields[CanonicalFieldKeys.ORGANIZATION].orEmpty()
        val authorityField = fields[CanonicalFieldKeys.ISSUING_AUTHORITY].orEmpty()
        val countryField = fields[CanonicalFieldKeys.ISSUING_COUNTRY].orEmpty()
        val explicitDomainField = fields[CanonicalFieldKeys.INSTITUTION_DOMAIN].orEmpty()
        val fallbackThemeDomain = existingTheme?.institutionDomain.orEmpty()
        val ibanClean = fields[CanonicalFieldKeys.IBAN].orEmpty().replace(" ", "").uppercase()
        val bicClean = fields[CanonicalFieldKeys.BIC].orEmpty().replace(" ", "").uppercase()

        val searchHaystack = " $title $subtitle $bankField $insurerField $orgField $authorityField $countryField $explicitDomainField ".lowercase()

        var matchedInstitution: InstitutionBrandProfile? = null

        if (matchedInstitution == null && bicClean.length >= 6) {
            matchedInstitution = CATALOG.firstOrNull { brand ->
                brand.bicPrefixes.any { prefix -> bicClean.startsWith(prefix) }
            }
        }

        if (matchedInstitution == null && ibanClean.startsWith("DE") && ibanClean.length >= 12) {
            val blz = ibanClean.substring(4, 12)
            matchedInstitution = CATALOG.firstOrNull { brand ->
                brand.germanBlzPrefixes.any { prefix -> blz.startsWith(prefix) }
            }
        }

        if (matchedInstitution == null && explicitDomainField.isNotBlank()) {
            val cleanDom = cleanDomain(explicitDomainField)
            matchedInstitution = CATALOG.firstOrNull {
                cleanDom == it.canonicalDomain || cleanDom.endsWith(".${it.canonicalDomain}")
            }
        }

        if (matchedInstitution == null) {
            matchedInstitution = CATALOG.firstOrNull { brand ->
                brand.keywords.any { kw -> matchesBrandKeyword(searchHaystack, kw) }
            }
        }

        if (matchedInstitution == null && fallbackThemeDomain.isNotBlank()) {
            val cleanFallbackDom = cleanDomain(fallbackThemeDomain)
            matchedInstitution = CATALOG.firstOrNull {
                cleanFallbackDom == it.canonicalDomain || cleanFallbackDom.endsWith(".${it.canonicalDomain}")
            }
        }

        if (matchedInstitution == null && existingTheme?.institutionId != null) {
            matchedInstitution = CATALOG.firstOrNull { it.id == existingTheme.institutionId }
        }

        val (networkCode, networkLabel) = when (category) {
            VaultCategory.PAYMENT_CARD -> {
                val det = detectPaymentNetwork(
                    cardNumber = fields[CanonicalFieldKeys.CARD_NUMBER].orEmpty(),
                    explicitNetwork = fields[CanonicalFieldKeys.CARD_NETWORK].orEmpty(),
                    titleAndSub = "$title $subtitle"
                )
                if (det.first == "CARD" && !existingTheme?.networkBrand.isNullOrBlank()) {
                    val fb = detectPaymentNetwork("", existingTheme?.networkBrand.orEmpty(), "")
                    if (fb.first == "CARD") "CARD" to existingTheme?.networkBrand.orEmpty() else fb
                } else {
                    det
                }
            }
            VaultCategory.BANK_ACCOUNT -> {
                val acctType = fields[CanonicalFieldKeys.ACCOUNT_TYPE].orEmpty()
                if (ibanClean.isNotEmpty() || "sepa" in searchHaystack) "SEPA" to "SEPA"
                else if (acctType.isNotBlank()) "BANK" to acctType.take(12).uppercase()
                else "SEPA" to "SEPA"
            }
            VaultCategory.INSURANCE_CARD -> {
                val plan = "${fields[CanonicalFieldKeys.COVERAGE_PLAN].orEmpty()} $title $subtitle".lowercase()
                when {
                    "ehic" in plan -> "EHIC" to "EHIC"
                    "pkv" in plan || "privat" in plan -> "PKV" to "PKV"
                    "egk" in plan || "gesundheitskarte" in plan || "gkv" in plan -> "EGK" to "eGK"
                    else -> "INSURANCE" to "POLICY"
                }
            }
            VaultCategory.MEMBERSHIP_CARD -> {
                val tier = fields[CanonicalFieldKeys.MEMBERSHIP_TIER].orEmpty().trim()
                if (tier.isNotEmpty()) "BARCODE" to tier.take(12).uppercase()
                else "BARCODE" to "MEMBER"
            }
            VaultCategory.IDENTITY -> {
                val nat = fields[CanonicalFieldKeys.NATIONALITY].orEmpty().trim()
                "IDENTITY" to (nat.takeIf { it.isNotEmpty() }?.take(8)?.uppercase() ?: "ID")
            }
            VaultCategory.OFFICIAL_DOCUMENT -> {
                val sub = "${fields[CanonicalFieldKeys.DOCUMENT_SUBTYPE].orEmpty()} $title".lowercase()
                when {
                    "passport" in sub || "reisepass" in sub || "διαβατ" in sub || "passaporto" in sub -> "PASSPORT" to "PASSPORT"
                    "driver" in sub || "führerschein" in sub || "patente" in sub || "permis" in sub -> "DRIVERS_LICENSE" to "LICENSE"
                    "personalausweis" in sub || "national id" in sub || "ausweis" in sub -> "NATIONAL_ID" to "eID"
                    else -> "OFFICIAL_DOC" to "ICAO"
                }
            }
            VaultCategory.SECURE_NOTE -> "NOTE" to "AES-256"
        }

        val inferredDomain = cleanDomain(explicitDomainField).takeIf { it.isNotEmpty() }
            ?: matchedInstitution?.canonicalDomain
            ?: inferDomainFromName(bankField.ifBlank { insurerField }.ifBlank { orgField })
            ?: cleanDomain(fallbackThemeDomain).takeIf { it.isNotEmpty() }

        val autoColor = if (existingTheme?.autoBrandColor != false) {
            matchedInstitution?.primaryColorHex ?: existingTheme?.customColorHex
        } else {
            existingTheme.customColorHex
        }

        return ResolvedDualBrand(
            institution = matchedInstitution,
            inferredDomain = inferredDomain,
            networkOrTypeCode = networkCode,
            networkDisplayLabel = networkLabel,
            effectiveColorHex = autoColor
        )
    }

    fun applyAutoBranding(item: VaultItemPayload): VaultItemPayload {
        val resolved = resolveForItem(item)
        val prev = item.cardTheme ?: CardVisualTheme()
        val useAutoColor = prev.autoBrandColor
        val nextHex = if (useAutoColor && resolved.institution != null) {
            resolved.institution.primaryColorHex
        } else {
            prev.customColorHex
        }
        val nextPalette = if (nextHex != null) "custom" else prev.paletteId

        val updatedTheme = prev.copy(
            paletteId = nextPalette,
            customColorHex = nextHex,
            networkBrand = resolved.networkDisplayLabel,
            institutionId = resolved.institution?.id ?: prev.institutionId,
            institutionName = resolved.institution?.displayName
                ?: item.fieldValue(CanonicalFieldKeys.ISSUING_BANK).takeIf { it.isNotBlank() }
                ?: item.fieldValue(CanonicalFieldKeys.INSURER_NAME).takeIf { it.isNotBlank() }
                ?: item.fieldValue(CanonicalFieldKeys.ORGANIZATION).takeIf { it.isNotBlank() }
                ?: prev.institutionName,
            institutionDomain = resolved.inferredDomain ?: prev.institutionDomain
        )
        return item.copy(cardTheme = updatedTheme)
    }

    fun cleanDomain(raw: String): String {
        val trimmed = raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
        return if (trimmed.contains('.') && trimmed.length >= 4 && !trimmed.contains(' ')) trimmed else ""
    }

    fun inferDomainFromName(rawInstitutionName: String): String? {
        val cleaned = rawInstitutionName.trim().lowercase()
        if (cleaned.isEmpty()) return null
        if (cleaned.contains('.') && !cleaned.contains(' ')) return cleanDomain(cleaned)
        val slug = cleaned
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("\\b(ag|se|gmbh|e\\.v\\.|ev|bank|group|plc|inc|llc)\\b"), "")
            .trim()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return if (slug.length >= 3) "$slug.com" else null
    }

    private fun matchesBrandKeyword(haystack: String, keyword: String): Boolean {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return false
        if (kw.length > 5) return kw in haystack
        var startIndex = haystack.indexOf(kw)
        while (startIndex >= 0) {
            val beforeOk = startIndex == 0 || !haystack[startIndex - 1].isLetterOrDigit()
            val afterIdx = startIndex + kw.length
            val afterOk = afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            startIndex = haystack.indexOf(kw, startIndex + 1)
        }
        return false
    }
}
