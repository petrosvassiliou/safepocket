import type { VaultCategory, VaultItem } from '../types';

export interface InstitutionBrandProfile {
  id: string;
  displayName: string;
  primaryColorHex: string;
  secondaryColorHex: string;
  badgeMonogram: string;
  logoKind:
    | 'DB_SLASH'
    | 'SPARKASSE_S'
    | 'COMMERZ_RIBBON'
    | 'ING_LION'
    | 'N26_BAR'
    | 'REVOLUT_R'
    | 'TK_BADGE'
    | 'ADAC_BADGE'
    | 'DB_BAHN'
    | 'EU_STARS'
    | 'MONOGRAM';
  canonicalDomain: string;
  keywords: string[];
  bicPrefixes?: string[];
  germanBlzPrefixes?: string[];
}

export interface ResolvedDualBrand {
  institution: InstitutionBrandProfile | null;
  inferredDomain: string | null;
  networkOrTypeCode: string;
  networkDisplayLabel: string;
  effectiveColorHex: string | null;
}

export const INSTITUTION_BRAND_CATALOG: ReadonlyArray<InstitutionBrandProfile> = [
  {
    id: 'deutsche_bank',
    displayName: 'Deutsche Bank',
    primaryColorHex: '#0018A8',
    secondaryColorHex: '#002B80',
    badgeMonogram: 'DB',
    logoKind: 'DB_SLASH',
    canonicalDomain: 'deutsche-bank.de',
    keywords: ['deutsche bank', 'deutsche-bank', 'norisbank'],
    bicPrefixes: ['DEUTDE', 'DEUTDEDB', 'DEUTDEFF'],
    germanBlzPrefixes: [
      '10070000',
      '10070024',
      '50070010',
      '50070024',
      '70070010',
      '70070024',
      '20070000',
      '37070024'
    ]
  },
  {
    id: 'sparkasse',
    displayName: 'Sparkasse',
    primaryColorHex: '#E2001A',
    secondaryColorHex: '#9E0012',
    badgeMonogram: 'S',
    logoKind: 'SPARKASSE_S',
    canonicalDomain: 'sparkasse.de',
    keywords: [
      'sparkasse',
      'berliner sparkasse',
      'haspa',
      'stadtsparkasse',
      'kreissparkasse',
      'frankfurter sparkasse'
    ],
    bicPrefixes: ['BELADEBE', 'HASPDEHH', 'SSKMDEMM', 'HELADEF1', 'COLSDE33'],
    germanBlzPrefixes: ['10050000', '20050550', '70150000', '50050201', '37050198']
  },
  {
    id: 'commerzbank',
    displayName: 'Commerzbank',
    primaryColorHex: '#002E3C',
    secondaryColorHex: '#004B61',
    badgeMonogram: 'CB',
    logoKind: 'COMMERZ_RIBBON',
    canonicalDomain: 'commerzbank.de',
    keywords: ['commerzbank', 'comdirect'],
    bicPrefixes: ['COBADE', 'COBADEFF', 'COBADEHD'],
    germanBlzPrefixes: ['10040000', '37040044', '50040000', '20041111', '20041133']
  },
  {
    id: 'ing',
    displayName: 'ING',
    primaryColorHex: '#FF6200',
    secondaryColorHex: '#1E2656',
    badgeMonogram: 'ING',
    logoKind: 'ING_LION',
    canonicalDomain: 'ing.de',
    keywords: ['ing-diba', 'ing diba', 'ing bank', 'ing giro', 'ing'],
    bicPrefixes: ['INGDDEFF', 'INGBNL2A', 'BBRUBEBB'],
    germanBlzPrefixes: ['50010517']
  },
  {
    id: 'dkb',
    displayName: 'DKB',
    primaryColorHex: '#148DEA',
    secondaryColorHex: '#0B2545',
    badgeMonogram: 'DKB',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'dkb.de',
    keywords: ['dkb', 'deutsche kreditbank'],
    bicPrefixes: ['BYLADEM1001'],
    germanBlzPrefixes: ['12030000']
  },
  {
    id: 'n26',
    displayName: 'N26',
    primaryColorHex: '#1A7F72',
    secondaryColorHex: '#0F2928',
    badgeMonogram: 'N26',
    logoKind: 'N26_BAR',
    canonicalDomain: 'n26.com',
    keywords: ['n26', 'n26 bank', 'n26 metal'],
    bicPrefixes: ['NTSBDEB1'],
    germanBlzPrefixes: ['10011001']
  },
  {
    id: 'revolut',
    displayName: 'Revolut',
    primaryColorHex: '#0B1426',
    secondaryColorHex: '#1E3A8A',
    badgeMonogram: 'R',
    logoKind: 'REVOLUT_R',
    canonicalDomain: 'revolut.com',
    keywords: ['revolut'],
    bicPrefixes: ['REVOLT21', 'REVODEB2']
  },
  {
    id: 'wise',
    displayName: 'Wise',
    primaryColorHex: '#163300',
    secondaryColorHex: '#2D6A00',
    badgeMonogram: 'W',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'wise.com',
    keywords: ['wise', 'transferwise'],
    bicPrefixes: ['TRWIBEB1', 'TRWIGB2L']
  },
  {
    id: 'postbank',
    displayName: 'Postbank',
    primaryColorHex: '#0018A8',
    secondaryColorHex: '#D4A000',
    badgeMonogram: 'PB',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'postbank.de',
    keywords: ['postbank'],
    bicPrefixes: ['PBNKDEFF'],
    germanBlzPrefixes: ['10010010', '50010060', '70010080', '20010020']
  },
  {
    id: 'volksbank',
    displayName: 'Volksbank Raiffeisenbank',
    primaryColorHex: '#003D7C',
    secondaryColorHex: '#EA5B0C',
    badgeMonogram: 'VR',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'vr.de',
    keywords: ['volksbank', 'raiffeisenbank', 'vr bank', 'gls bank', 'sparda'],
    bicPrefixes: ['BEVODEBB', 'GENODE']
  },
  {
    id: 'santander',
    displayName: 'Santander',
    primaryColorHex: '#EC0000',
    secondaryColorHex: '#990000',
    badgeMonogram: 'SAN',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'santander.de',
    keywords: ['santander'],
    bicPrefixes: ['SCFBDE33', 'BSCHESMM']
  },
  {
    id: 'unicredit',
    displayName: 'HypoVereinsbank • UniCredit',
    primaryColorHex: '#007A91',
    secondaryColorHex: '#004957',
    badgeMonogram: 'HVB',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'hvb.de',
    keywords: ['hypovereinsbank', 'unicredit', 'hvb'],
    bicPrefixes: ['HYVEDEMM', 'UNCRITMM']
  },
  {
    id: 'trade_republic',
    displayName: 'Trade Republic',
    primaryColorHex: '#111418',
    secondaryColorHex: '#2A3038',
    badgeMonogram: 'TR',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'traderepublic.com',
    keywords: ['trade republic', 'traderepublic'],
    bicPrefixes: ['TRBKDEBB']
  },
  {
    id: 'consorsbank',
    displayName: 'Consorsbank • BNP Paribas',
    primaryColorHex: '#008755',
    secondaryColorHex: '#005538',
    badgeMonogram: 'BNP',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'consorsbank.de',
    keywords: ['consorsbank', 'bnp paribas'],
    bicPrefixes: ['CSDBDE71', 'BNPAFRPP']
  },
  {
    id: 'amex_issuer',
    displayName: 'American Express',
    primaryColorHex: '#006FCF',
    secondaryColorHex: '#003E78',
    badgeMonogram: 'AMEX',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'americanexpress.com',
    keywords: ['american express', 'amex']
  },
  {
    id: 'barclays',
    displayName: 'Barclays',
    primaryColorHex: '#00AEEF',
    secondaryColorHex: '#00395D',
    badgeMonogram: 'BAR',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'barclays.de',
    keywords: ['barclays', 'barclaycard'],
    bicPrefixes: ['BARCDEFF', 'BARCGB22']
  },
  {
    id: 'hanseatic',
    displayName: 'Hanseatic Bank',
    primaryColorHex: '#C5003E',
    secondaryColorHex: '#7A0026',
    badgeMonogram: 'HB',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'hanseaticbank.de',
    keywords: ['hanseatic', 'genialcard', 'awa7']
  },
  {
    id: 'alpha_bank',
    displayName: 'Alpha Bank',
    primaryColorHex: '#003366',
    secondaryColorHex: '#0055A5',
    badgeMonogram: 'Α',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'alpha.gr',
    keywords: ['alpha bank', 'alphabank', 'αλφα'],
    bicPrefixes: ['CRBAGRAA']
  },
  {
    id: 'nbg',
    displayName: 'National Bank of Greece',
    primaryColorHex: '#005F6B',
    secondaryColorHex: '#003840',
    badgeMonogram: 'ΕΤΕ',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'nbg.gr',
    keywords: ['national bank of greece', 'εθνικη τραπεζα', 'nbg'],
    bicPrefixes: ['ETHNGRAA']
  },
  {
    id: 'tk',
    displayName: 'Techniker Krankenkasse',
    primaryColorHex: '#009FE3',
    secondaryColorHex: '#005F8C',
    badgeMonogram: 'TK',
    logoKind: 'TK_BADGE',
    canonicalDomain: 'tk.de',
    keywords: ['techniker krankenkasse', 'tk ', 'tk-', '(tk)', 'die techniker']
  },
  {
    id: 'aok',
    displayName: 'AOK Gesundheitskasse',
    primaryColorHex: '#005E3F',
    secondaryColorHex: '#008559',
    badgeMonogram: 'AOK',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'aok.de',
    keywords: ['aok', 'gesundheitskasse']
  },
  {
    id: 'barmer',
    displayName: 'BARMER',
    primaryColorHex: '#007A3D',
    secondaryColorHex: '#004F27',
    badgeMonogram: 'BM',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'barmer.de',
    keywords: ['barmer']
  },
  {
    id: 'dak',
    displayName: 'DAK-Gesundheit',
    primaryColorHex: '#E85C0D',
    secondaryColorHex: '#9E3B00',
    badgeMonogram: 'DAK',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'dak.de',
    keywords: ['dak', 'dak-gesundheit']
  },
  {
    id: 'allianz',
    displayName: 'Allianz',
    primaryColorHex: '#003781',
    secondaryColorHex: '#0057B8',
    badgeMonogram: 'AZ',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'allianz.de',
    keywords: ['allianz']
  },
  {
    id: 'axa',
    displayName: 'AXA',
    primaryColorHex: '#00008F',
    secondaryColorHex: '#D2001E',
    badgeMonogram: 'AXA',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'axa.de',
    keywords: ['axa']
  },
  {
    id: 'huk',
    displayName: 'HUK-COBURG',
    primaryColorHex: '#22252A',
    secondaryColorHex: '#B58E00',
    badgeMonogram: 'HUK',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'huk.de',
    keywords: ['huk', 'huk-coburg', 'huk24']
  },
  {
    id: 'debeka',
    displayName: 'Debeka',
    primaryColorHex: '#005CA9',
    secondaryColorHex: '#003B6F',
    badgeMonogram: 'DBK',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'debeka.de',
    keywords: ['debeka']
  },
  {
    id: 'adac',
    displayName: 'ADAC',
    primaryColorHex: '#212121',
    secondaryColorHex: '#B38F00',
    badgeMonogram: 'ADAC',
    logoKind: 'ADAC_BADGE',
    canonicalDomain: 'adac.de',
    keywords: ['adac']
  },
  {
    id: 'deutsche_bahn',
    displayName: 'Deutsche Bahn • BahnCard',
    primaryColorHex: '#EC0016',
    secondaryColorHex: '#99000E',
    badgeMonogram: 'DB',
    logoKind: 'DB_BAHN',
    canonicalDomain: 'bahn.de',
    keywords: ['bahncard', 'deutsche bahn', 'db navigator', 'deutschlandticket', 'd-ticket']
  },
  {
    id: 'miles_and_more',
    displayName: 'Lufthansa • Miles & More',
    primaryColorHex: '#05164D',
    secondaryColorHex: '#0A2A88',
    badgeMonogram: 'LH',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'miles-and-more.com',
    keywords: ['miles & more', 'miles and more', 'lufthansa', 'senator']
  },
  {
    id: 'payback',
    displayName: 'PAYBACK',
    primaryColorHex: '#0046AA',
    secondaryColorHex: '#002B6B',
    badgeMonogram: 'PB',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'payback.de',
    keywords: ['payback']
  },
  {
    id: 'ikea',
    displayName: 'IKEA Family',
    primaryColorHex: '#0058A3',
    secondaryColorHex: '#003B6F',
    badgeMonogram: 'IKEA',
    logoKind: 'MONOGRAM',
    canonicalDomain: 'ikea.com',
    keywords: ['ikea']
  },
  {
    id: 'gov_de',
    displayName: 'Bundesrepublik Deutschland',
    primaryColorHex: '#5A1828',
    secondaryColorHex: '#3A0F1A',
    badgeMonogram: 'DE',
    logoKind: 'EU_STARS',
    canonicalDomain: 'bund.de',
    keywords: [
      'bundesrepublik',
      'personalausweis',
      'reisepass',
      'berlin',
      'buergeramt',
      'bürgeramt',
      'deutschland'
    ]
  },
  {
    id: 'gov_gr',
    displayName: 'Ελληνική Δημοκρατία',
    primaryColorHex: '#0D3B66',
    secondaryColorHex: '#1D5B96',
    badgeMonogram: 'GR',
    logoKind: 'EU_STARS',
    canonicalDomain: 'gov.gr',
    keywords: ['ελληνικ', 'διαβατήριο', 'ταυτότητα', 'αθήνα', 'hellenic', 'greece']
  },
  {
    id: 'gov_eu',
    displayName: 'European Union',
    primaryColorHex: '#003399',
    secondaryColorHex: '#001F66',
    badgeMonogram: 'EU',
    logoKind: 'EU_STARS',
    canonicalDomain: 'europa.eu',
    keywords: ['european', 'ehic', 'schengen', 'eu ']
  }
];

export function cleanInstitutionDomain(raw: string): string {
  const trimmed = raw
    .trim()
    .toLowerCase()
    .replace(/^https?:\/\//, '')
    .replace(/^www\./, '')
    .split('/')[0]
    .split('?')[0];
  return trimmed.includes('.') && trimmed.length >= 4 && !trimmed.includes(' ')
    ? trimmed
    : '';
}

export function inferDomainFromInstitutionName(rawName: string): string | null {
  const cleaned = rawName.trim().toLowerCase();
  if (!cleaned) return null;
  if (cleaned.includes('.') && !cleaned.includes(' ')) {
    return cleanInstitutionDomain(cleaned) || null;
  }
  const slug = cleaned
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .replace(/\b(ag|se|gmbh|e\.v\.|ev|bank|group|plc|inc|llc)\b/g, '')
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug.length >= 3 ? `${slug}.com` : null;
}

export function detectPaymentNetwork(
  cardNumber: string,
  explicitNetwork = '',
  titleAndSub = ''
): { code: string; label: string } {
  const combined = `${explicitNetwork} ${titleAndSub}`.toLowerCase();
  if (combined.includes('girocard') || combined.includes('ec-karte')) {
    return { code: 'GIROCARD', label: 'girocard' };
  }
  if (combined.includes('visa')) return { code: 'VISA', label: 'VISA' };
  if (combined.includes('mastercard') || combined.includes('master card')) {
    return { code: 'MASTERCARD', label: 'Mastercard' };
  }
  if (combined.includes('amex') || combined.includes('american express')) {
    return { code: 'AMEX', label: 'AMEX' };
  }
  if (combined.includes('maestro')) return { code: 'MAESTRO', label: 'Maestro' };
  if (combined.includes('v pay') || combined.includes('vpay')) {
    return { code: 'VPAY', label: 'V PAY' };
  }
  if (combined.includes('discover')) return { code: 'DISCOVER', label: 'Discover' };
  if (combined.includes('diners')) return { code: 'DINERS', label: 'Diners' };

  const digits = cardNumber.replace(/\D+/g, '');
  if (digits.length > 0) {
    const two = parseInt(digits.slice(0, 2), 10) || 0;
    const four = parseInt(digits.slice(0, 4), 10) || 0;
    if (digits.startsWith('4')) return { code: 'VISA', label: 'VISA' };
    if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
      return { code: 'MASTERCARD', label: 'Mastercard' };
    }
    if (two === 34 || two === 37) return { code: 'AMEX', label: 'AMEX' };
    if (digits.startsWith('6011') || digits.startsWith('65')) {
      return { code: 'DISCOVER', label: 'Discover' };
    }
    if ([50, 56, 57, 58, 67].includes(two)) {
      return { code: 'MAESTRO', label: 'Maestro' };
    }
  }
  return { code: 'CARD', label: 'CARD' };
}

function matchesBrandKeyword(haystack: string, keyword: string): boolean {
  const kw = keyword.trim().toLowerCase();
  if (!kw) return false;
  if (kw.length <= 5) {
    const escaped = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp(`(^|[^\\p{L}\\p{N}])${escaped}($|[^\\p{L}\\p{N}])`, 'u').test(haystack);
  }
  return haystack.includes(kw);
}

export function getFieldValue(item: VaultItem, key: string): string {
  return item.fields.find((f) => !f.isCustomLabel && f.labelKey === key)?.value ?? '';
}

export function resolveDualBrand(item: VaultItem): ResolvedDualBrand {
  const fields: Record<string, string> = {};
  for (const f of item.fields) {
    if (!f.isCustomLabel && f.value) fields[f.labelKey] = f.value;
  }
  return resolveDualBrandFromFields(
    item.category,
    item.title,
    item.subtitle,
    fields,
    item.cardTheme
  );
}

export function resolveDualBrandFromFields(
  category: VaultCategory,
  title: string,
  subtitle: string,
  fields: Record<string, string>,
  existingTheme?: VaultItem['cardTheme']
): ResolvedDualBrand {
  const bankField = fields.field_issuing_bank ?? '';
  const insurerField = fields.field_insurer_name ?? '';
  const orgField = fields.field_organization ?? '';
  const authorityField = fields.field_issuing_authority ?? '';
  const countryField = fields.field_issuing_country ?? '';
  const explicitDomainField = fields.field_institution_domain ?? '';
  const fallbackThemeDomain = existingTheme?.institutionDomain ?? '';
  const ibanClean = (fields.field_iban ?? '').replace(/\s+/g, '').toUpperCase();
  const bicClean = (fields.field_bic ?? '').replace(/\s+/g, '').toUpperCase();

  const haystack =
    ` ${title} ${subtitle} ${bankField} ${insurerField} ${orgField} ${authorityField} ${countryField} ${explicitDomainField} `.toLowerCase();

  let matched: InstitutionBrandProfile | null = null;

  if (!matched && bicClean.length >= 6) {
    matched =
      INSTITUTION_BRAND_CATALOG.find((b) =>
        (b.bicPrefixes ?? []).some((p) => bicClean.startsWith(p))
      ) ?? null;
  }

  if (!matched && ibanClean.startsWith('DE') && ibanClean.length >= 12) {
    const blz = ibanClean.slice(4, 12);
    matched =
      INSTITUTION_BRAND_CATALOG.find((b) =>
        (b.germanBlzPrefixes ?? []).some((p) => blz.startsWith(p))
      ) ?? null;
  }

  if (!matched && explicitDomainField) {
    const cleanDom = cleanInstitutionDomain(explicitDomainField);
    if (cleanDom) {
      matched =
        INSTITUTION_BRAND_CATALOG.find(
          (b) =>
            cleanDom === b.canonicalDomain ||
            cleanDom.endsWith(`.${b.canonicalDomain}`)
        ) ?? null;
    }
  }

  if (!matched) {
    matched =
      INSTITUTION_BRAND_CATALOG.find((b) =>
        b.keywords.some((kw) => matchesBrandKeyword(haystack, kw))
      ) ?? null;
  }

  if (!matched && fallbackThemeDomain) {
    const cleanFallbackDom = cleanInstitutionDomain(fallbackThemeDomain);
    if (cleanFallbackDom) {
      matched =
        INSTITUTION_BRAND_CATALOG.find(
          (b) =>
            cleanFallbackDom === b.canonicalDomain ||
            cleanFallbackDom.endsWith(`.${b.canonicalDomain}`)
        ) ?? null;
    }
  }

  if (!matched && existingTheme?.institutionId) {
    matched =
      INSTITUTION_BRAND_CATALOG.find(
        (b) => b.id === existingTheme.institutionId
      ) ?? null;
  }

  let networkCode = 'CARD';
  let networkLabel = 'CARD';

  if (category === 'PAYMENT_CARD') {
    const det = detectPaymentNetwork(
      fields.field_card_number ?? '',
      fields.field_card_network ?? '',
      `${title} ${subtitle}`
    );
    if (det.code === 'CARD' && existingTheme?.networkBrand) {
      const fbDet = detectPaymentNetwork('', existingTheme.networkBrand, '');
      networkCode = fbDet.code;
      networkLabel =
        fbDet.code === 'CARD' ? existingTheme.networkBrand : fbDet.label;
    } else {
      networkCode = det.code;
      networkLabel = det.label;
    }
  } else if (category === 'BANK_ACCOUNT') {
    networkCode = 'SEPA';
    networkLabel = 'SEPA';
  } else if (category === 'INSURANCE_CARD') {
    const plan = `${fields.field_coverage_plan ?? ''} ${title} ${subtitle}`.toLowerCase();
    if (plan.includes('ehic')) {
      networkCode = 'EHIC';
      networkLabel = 'EHIC';
    } else if (plan.includes('pkv') || plan.includes('privat')) {
      networkCode = 'PKV';
      networkLabel = 'PKV';
    } else {
      networkCode = 'EGK';
      networkLabel = 'eGK';
    }
  } else if (category === 'MEMBERSHIP_CARD') {
    const tier = (fields.field_membership_tier ?? '').trim();
    networkCode = 'BARCODE';
    networkLabel = tier ? tier.slice(0, 12).toUpperCase() : 'MEMBER';
  } else if (category === 'IDENTITY') {
    const nat = (fields.field_nationality ?? '').trim();
    networkCode = 'IDENTITY';
    networkLabel = nat ? nat.slice(0, 8).toUpperCase() : 'ID';
  } else if (category === 'OFFICIAL_DOCUMENT') {
    const sub = `${fields.field_document_subtype ?? ''} ${title}`.toLowerCase();
    if (
      sub.includes('passport') ||
      sub.includes('reisepass') ||
      sub.includes('διαβατ') ||
      sub.includes('passaporto')
    ) {
      networkCode = 'PASSPORT';
      networkLabel = 'PASSPORT';
    } else if (
      sub.includes('driver') ||
      sub.includes('führerschein') ||
      sub.includes('patente')
    ) {
      networkCode = 'DRIVERS_LICENSE';
      networkLabel = 'LICENSE';
    } else {
      networkCode = 'NATIONAL_ID';
      networkLabel = 'eID';
    }
  } else {
    networkCode = 'NOTE';
    networkLabel = 'AES-256';
  }

  const inferredDomain =
    cleanInstitutionDomain(explicitDomainField) ||
    matched?.canonicalDomain ||
    inferDomainFromInstitutionName(bankField || insurerField || orgField) ||
    cleanInstitutionDomain(fallbackThemeDomain) ||
    null;

  const autoColor =
    existingTheme?.autoBrandColor !== false
      ? matched?.primaryColorHex ?? existingTheme?.customColorHex ?? null
      : existingTheme?.customColorHex ?? null;

  return {
    institution: matched,
    inferredDomain,
    networkOrTypeCode: networkCode,
    networkDisplayLabel: networkLabel,
    effectiveColorHex: autoColor
  };
}

export function applyAutoBranding(item: VaultItem): VaultItem {
  const resolved = resolveDualBrand(item);
  const prev = item.cardTheme ?? { paletteId: 'obsidian_slate' as const };
  const useAuto = prev.autoBrandColor !== false;
  const nextHex =
    useAuto && resolved.institution
      ? resolved.institution.primaryColorHex
      : prev.customColorHex;
  const nextPalette = nextHex ? 'custom' : prev.paletteId;

  const instName =
    resolved.institution?.displayName ||
    getFieldValue(item, 'field_issuing_bank') ||
    getFieldValue(item, 'field_insurer_name') ||
    getFieldValue(item, 'field_organization') ||
    prev.institutionName;

  return {
    ...item,
    cardTheme: {
      ...prev,
      paletteId: nextPalette,
      customColorHex: nextHex,
      networkBrand: resolved.networkDisplayLabel,
      institutionId: resolved.institution?.id ?? prev.institutionId,
      institutionName: instName || undefined,
      institutionDomain: resolved.inferredDomain ?? prev.institutionDomain,
      autoBrandColor: useAuto
    }
  };
}

function escapeAttr(val: string): string {
  return val
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/**
 * Renders a crisp inline SVG / image emblem for the institution (top-left of card or list row).
 */
export function renderInstitutionLogoHtml(
  item: VaultItem,
  resolved: ResolvedDualBrand = resolveDualBrand(item),
  sizePx = 36
): string {
  const dataUri = item.cardTheme?.institutionLogoDataUri;
  if (dataUri && dataUri.startsWith('data:image/')) {
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#fff;"><img src="${escapeAttr(
      dataUri
    )}" alt="" style="width:100%;height:100%;object-fit:contain;border-radius:7px;" /></span>`;
  }

  const inst = resolved.institution;
  const kind = inst?.logoKind ?? 'MONOGRAM';

  if (kind === 'DB_SLASH') {
    // Deutsche Bank iconic square + diagonal slash
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#ffffff;" aria-hidden="true">
      <svg width="${sizePx - 8}" height="${sizePx - 8}" viewBox="0 0 28 28" fill="none">
        <rect x="2.5" y="2.5" width="23" height="23" stroke="#0018A8" stroke-width="3.6"/>
        <line x1="8.5" y1="21.5" x2="19.5" y2="6.5" stroke="#0018A8" stroke-width="3.8" stroke-linecap="square"/>
      </svg>
    </span>`;
  }

  if (kind === 'SPARKASSE_S') {
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#E2001A;" aria-hidden="true">
      <svg width="${sizePx - 8}" height="${sizePx - 8}" viewBox="0 0 28 28" fill="none">
        <circle cx="14" cy="7" r="3" fill="#ffffff"/>
        <path d="M9 17.5C9 13.5 19 13.5 19 17.5C19 21.5 9 21.5 9 17.5Z" stroke="#ffffff" stroke-width="3.2"/>
      </svg>
    </span>`;
  }

  if (kind === 'COMMERZ_RIBBON') {
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#002E3C;" aria-hidden="true">
      <svg width="${sizePx - 8}" height="${sizePx - 8}" viewBox="0 0 28 28" fill="none">
        <polygon points="14,4 24,22 4,22" stroke="#FFCC00" stroke-width="3" fill="none"/>
      </svg>
    </span>`;
  }

  if (kind === 'ADAC_BADGE') {
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#FFCC00;color:#111;font-weight:900;font-size:9.5px;letter-spacing:-0.02em;" aria-hidden="true">ADAC</span>`;
  }

  if (kind === 'DB_BAHN') {
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#ffffff;border:2.5px solid #EC0016;color:#EC0016;font-weight:900;font-size:13px;" aria-hidden="true">DB</span>`;
  }

  if (kind === 'EU_STARS') {
    const code = escapeAttr(inst?.badgeMonogram ?? 'EU');
    return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:#003399;color:#FFCC00;font-weight:800;font-size:10px;border:1px solid rgba(255,204,0,0.55);" aria-hidden="true">★${code}</span>`;
  }

  const bg = inst?.primaryColorHex ?? item.cardTheme?.customColorHex ?? '#1E3A8A';
  const words = (item.cardTheme?.institutionName || item.title)
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  const fallbackMono =
    words.length >= 2
      ? `${words[0][0]}${words[1][0]}`.toUpperCase()
      : (words[0] ?? 'SP').slice(0, 3).toUpperCase();
  const mono = escapeAttr(inst?.badgeMonogram ?? fallbackMono);
  return `<span class="sp-inst-logo" style="width:${sizePx}px;height:${sizePx}px;background:${escapeAttr(
    bg
  )};color:#fff;font-weight:800;font-size:${mono.length >= 4 ? 9.5 : 11.5}px;border:1px solid rgba(255,255,255,0.28);" aria-hidden="true">${mono}</span>`;
}

/**
 * Renders the Card Network or Category Type vector badge (VISA, Mastercard, AMEX, girocard, SEPA, eGK, Passport, Barcode).
 */
export function renderNetworkOrTypeLogoHtml(
  resolved: ResolvedDualBrand,
  overrideLabel?: string
): string {
  const code = resolved.networkOrTypeCode.toUpperCase();
  const label = escapeAttr(overrideLabel || resolved.networkDisplayLabel);

  if (code === 'VISA') {
    return `<span class="sp-net-logo sp-net-visa" aria-label="VISA">
      <svg width="46" height="16" viewBox="0 0 56 18" fill="none">
        <path d="M2 2.5H9.5L13.2 14.5L9.2 14.5L6.5 5.2L2 2.5Z" fill="#F7B600"/>
        <text x="12" y="14.5" fill="#1A1F71" font-family="Inter,Arial,sans-serif" font-weight="900" font-style="italic" font-size="14.5" letter-spacing="0.6">VISA</text>
      </svg>
    </span>`;
  }

  if (code === 'MASTERCARD') {
    return `<span class="sp-net-logo sp-net-mc" aria-label="Mastercard">
      <svg width="36" height="22" viewBox="0 0 36 22" fill="none">
        <circle cx="13" cy="11" r="9" fill="#EB001B"/>
        <circle cx="23" cy="11" r="9" fill="#F79E1B" fill-opacity="0.9"/>
      </svg>
    </span>`;
  }

  if (code === 'AMEX') {
    return `<span class="sp-net-logo" style="background:#006FCF;color:#fff;border:1px solid #fff;padding:3px 8px;border-radius:6px;font-weight:900;font-size:11px;letter-spacing:0.06em;">AMEX</span>`;
  }

  if (code === 'MAESTRO') {
    return `<span class="sp-net-logo sp-net-mc" aria-label="Maestro">
      <svg width="36" height="22" viewBox="0 0 36 22" fill="none">
        <circle cx="13" cy="11" r="9" fill="#EB001B"/>
        <circle cx="23" cy="11" r="9" fill="#00A2E5" fill-opacity="0.88"/>
      </svg>
    </span>`;
  }

  if (code === 'GIROCARD') {
    return `<span class="sp-net-logo" style="background:#003D7C;color:#fff;border:1px solid rgba(255,255,255,0.65);padding:3px 8px;border-radius:6px;font-weight:700;font-size:11px;display:inline-flex;align-items:center;gap:4px;">
      <span style="width:7px;height:7px;border-radius:50%;background:#E2001A;display:inline-block;"></span>girocard
    </span>`;
  }

  if (code === 'PASSPORT' || code === 'NATIONAL_ID' || code === 'OFFICIAL_DOC') {
    return `<span class="sp-net-logo sp-card-pill" style="display:inline-flex;align-items:center;gap:5px;">
      <svg width="16" height="11" viewBox="0 0 18 12" fill="none">
        <rect x="1" y="1" width="16" height="10" rx="2" stroke="currentColor" stroke-width="1.4"/>
        <line x1="1" y1="6" x2="17" y2="6" stroke="currentColor" stroke-width="1.2"/>
        <circle cx="9" cy="6" r="2.5" fill="currentColor"/>
      </svg>${label}
    </span>`;
  }

  return `<span class="sp-card-pill">${label}</span>`;
}

/**
 * Privacy-preserving optional domain logo & dominant color downloader for ChromeOS / Desktop PWA.
 * Transmits ONLY the public institution domain name over HTTPS; caches inside encrypted `cardTheme`.
 */
export async function fetchDomainBrandLogo(
  rawDomainOrName: string,
  fetcher: typeof fetch = fetch
): Promise<{
  domain: string;
  dataUri: string;
  extractedColorHex?: string;
} | null> {
  const domain =
    cleanInstitutionDomain(rawDomainOrName) ||
    inferDomainFromInstitutionName(rawDomainOrName) ||
    '';
  if (!domain) return null;

  const candidates = [
    `https://icons.duckduckgo.com/ip3/${domain}.ico`,
    `https://${domain}/apple-touch-icon.png`
  ];

  for (const url of candidates) {
    try {
      const res = await fetcher(url);
      if (!res.ok) continue;
      const buf = new Uint8Array(await res.arrayBuffer());
      if (buf.byteLength < 64 || buf.byteLength > 131_072) continue;
      const mime =
        buf[0] === 0x89 && buf[1] === 0x50
          ? 'image/png'
          : buf[0] === 0xff && buf[1] === 0xd8
            ? 'image/jpeg'
            : 'image/x-icon';
      let bin = '';
      for (let i = 0; i < buf.length; i++) bin += String.fromCharCode(buf[i]);
      const dataUri = `data:${mime};base64,${btoa(bin)}`;
      const extractedColorHex = await extractColorFromDataUriBrowser(dataUri);
      return { domain, dataUri, extractedColorHex };
    } catch {
      // Next candidate
    }
  }
  return null;
}

async function extractColorFromDataUriBrowser(
  dataUri: string
): Promise<string | undefined> {
  if (typeof document === 'undefined' || typeof Image === 'undefined') {
    return undefined;
  }
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        canvas.width = 24;
        canvas.height = 24;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          resolve(undefined);
          return;
        }
        ctx.drawImage(img, 0, 0, 24, 24);
        const data = ctx.getImageData(0, 0, 24, 24).data;
        let rSum = 0;
        let gSum = 0;
        let bSum = 0;
        let wSum = 0;
        for (let i = 0; i < data.length; i += 4) {
          const a = data[i + 3];
          if (a < 180) continue;
          const r = data[i];
          const g = data[i + 1];
          const b = data[i + 2];
          const max = Math.max(r, g, b) / 255;
          const min = Math.min(r, g, b) / 255;
          const sat = max === 0 ? 0 : (max - min) / max;
          if (sat > 0.22 && max > 0.14 && max < 0.94) {
            const w = Math.max(1, Math.round(sat * 100));
            rSum += r * w;
            gSum += g * w;
            bSum += b * w;
            wSum += w;
          }
        }
        if (wSum === 0) {
          resolve(undefined);
          return;
        }
        const toHex = (n: number) =>
          Math.max(0, Math.min(255, Math.round(n)))
            .toString(16)
            .padStart(2, '0')
            .toUpperCase();
        resolve(`#${toHex(rSum / wSum)}${toHex(gSum / wSum)}${toHex(bSum / wSum)}`);
      } catch {
        resolve(undefined);
      }
    };
    img.onerror = () => resolve(undefined);
    img.src = dataUri;
  });
}
