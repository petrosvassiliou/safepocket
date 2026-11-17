import type { SafePocketI18n } from '../i18n/l10n';
import {
  validateIsoIban,
  validatePaymentCardPan
} from '../scanner/mrzAndCardValidator';
import type { VaultCategory } from '../types';
import { INSTITUTION_BRAND_CATALOG } from './institutionBrands';

export interface SmartDropdownOption {
  value: string;
  label: string;
}

export interface CountryDialOption {
  countryCode: string;
  dialCode: string;
  flag: string;
  label: string;
}

export const COUNTRY_DIAL_CODES: ReadonlyArray<CountryDialOption> = [
  { countryCode: 'DE', dialCode: '+49', flag: '🇩🇪', label: 'DE +49' },
  { countryCode: 'GR', dialCode: '+30', flag: '🇬🇷', label: 'GR +30' },
  { countryCode: 'IT', dialCode: '+39', flag: '🇮🇹', label: 'IT +39' },
  { countryCode: 'FR', dialCode: '+33', flag: '🇫🇷', label: 'FR +33' },
  { countryCode: 'ES', dialCode: '+34', flag: '🇪🇸', label: 'ES +34' },
  { countryCode: 'AT', dialCode: '+43', flag: '🇦🇹', label: 'AT +43' },
  { countryCode: 'CH', dialCode: '+41', flag: '🇨🇭', label: 'CH +41' },
  { countryCode: 'NL', dialCode: '+31', flag: '🇳🇱', label: 'NL +31' },
  { countryCode: 'BE', dialCode: '+32', flag: '🇧🇪', label: 'BE +32' },
  { countryCode: 'GB', dialCode: '+44', flag: '🇬🇧', label: 'UK +44' },
  { countryCode: 'US', dialCode: '+1', flag: '🇺🇸', label: 'US +1' },
  { countryCode: 'CY', dialCode: '+357', flag: '🇨🇾', label: 'CY +357' },
  { countryCode: 'LU', dialCode: '+352', flag: '🇱🇺', label: 'LU +352' },
  { countryCode: 'PT', dialCode: '+351', flag: '🇵🇹', label: 'PT +351' }
];

export function defaultDialCodeForLocale(locale: string): string {
  switch (locale.toLowerCase()) {
    case 'de':
      return '+49';
    case 'el':
      return '+30';
    case 'it':
      return '+39';
    case 'fr':
      return '+33';
    case 'es':
      return '+34';
    default:
      return '+49';
  }
}

/**
 * Automatically prefixes `https://` on bare domains or upgrades `http://` to `https://`
 * while preserving path/hostname cleanliness.
 */
export function normalizeHttpsUrl(rawInput: string): string {
  const trimmed = rawInput.trim();
  if (!trimmed) return '';
  let cleaned = trimmed.replace(/\s+/g, '');
  if (/^http:\/\//i.test(cleaned)) {
    cleaned = 'https://' + cleaned.slice('http://'.length);
  } else if (!/^https:\/\//i.test(cleaned)) {
    cleaned = 'https://' + cleaned.replace(/^\/+/, '');
  }
  try {
    const parsed = new URL(cleaned);
    const host = parsed.hostname.toLowerCase();
    if (!host) return cleaned;
    const path =
      parsed.pathname && parsed.pathname !== '/' ? parsed.pathname : '';
    return `https://${host}${path}`;
  } catch {
    return cleaned;
  }
}

export function isPlausibleHttpsUrl(url: string): boolean {
  if (!url.startsWith('https://')) return false;
  try {
    const parsed = new URL(url);
    const host = parsed.hostname;
    return (
      host.includes('.') &&
      host.length >= 4 &&
      !host.startsWith('.') &&
      !host.endsWith('.') &&
      /^[a-z0-9.-]+$/i.test(host)
    );
  } catch {
    return false;
  }
}

export interface WebsiteVerificationResult {
  status: 'idle' | 'checking' | 'reachable' | 'unreachable';
  normalizedUrl: string;
  hostname: string;
  detail: string;
}

/**
 * Verifies whether an HTTPS website exists and responds.
 * 1. Normalizes input to `https://<hostname>`.
 * 2. Performs a live `HEAD`/`GET` probe (`mode: 'no-cors'` in browser so opaque 200 responses succeed without CORS false negatives).
 * 3. If direct probe fails or is blocked, falls back to DNS-over-HTTPS A/AAAA existence check (`dns.google`) when reachable.
 */
export async function verifyWebsiteExists(
  rawUrl: string,
  customFetcher: typeof fetch = fetch,
  timeoutMs = 4500
): Promise<WebsiteVerificationResult> {
  const normalizedUrl = normalizeHttpsUrl(rawUrl);
  if (!normalizedUrl) {
    return {
      status: 'idle',
      normalizedUrl: '',
      hostname: '',
      detail: ''
    };
  }
  if (!isPlausibleHttpsUrl(normalizedUrl)) {
    return {
      status: 'unreachable',
      normalizedUrl,
      hostname: normalizedUrl,
      detail: 'Invalid domain syntax'
    };
  }

  const hostname = new URL(normalizedUrl).hostname;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const res = await customFetcher(normalizedUrl, {
      method: 'HEAD',
      mode: 'no-cors',
      cache: 'no-store',
      signal: controller.signal
    });
    clearTimeout(timer);
    if (res.type === 'opaque' || res.ok || (res.status >= 200 && res.status < 500)) {
      return {
        status: 'reachable',
        normalizedUrl,
        hostname,
        detail: `HTTPS ${hostname}`
      };
    }
    return {
      status: 'unreachable',
      normalizedUrl,
      hostname,
      detail: `HTTP ${res.status}`
    };
  } catch {
    clearTimeout(timer);
    // Secondary check via DNS-over-HTTPS JSON resolution if allowed
    try {
      const dnsCtrl = new AbortController();
      const dnsTimer = setTimeout(() => dnsCtrl.abort(), 2800);
      const dnsRes = await customFetcher(
        `https://dns.google/resolve?name=${encodeURIComponent(hostname)}&type=A`,
        { cache: 'no-store', signal: dnsCtrl.signal }
      );
      clearTimeout(dnsTimer);
      if (dnsRes.ok) {
        const body = (await dnsRes.json()) as {
          Status?: number;
          Answer?: Array<{ data?: string }>;
        };
        if (body.Status === 0 && Array.isArray(body.Answer) && body.Answer.length > 0) {
          return {
            status: 'reachable',
            normalizedUrl,
            hostname,
            detail: `DNS+HTTPS ${hostname}`
          };
        }
      }
    } catch {
      // Ignore secondary fallback error
    }
    return {
      status: 'unreachable',
      normalizedUrl,
      hostname,
      detail: `${hostname} did not respond`
    };
  }
}

/**
 * Standard dropdown options per canonical field key & category (localized via ARB keys).
 */
export function dropdownOptionsForField(
  fieldKey: string,
  category: VaultCategory,
  i18n: SafePocketI18n
): SmartDropdownOption[] | null {
  if (fieldKey === 'field_account_type' && category === 'BANK_ACCOUNT') {
    const keys = [
      'acct_type_checking',
      'acct_type_savings',
      'acct_type_fixed_deposit',
      'acct_type_joint',
      'acct_type_business',
      'acct_type_brokerage',
      'acct_type_foreign_currency',
      'acct_type_youth_student'
    ];
    return keys.map((k) => ({ value: i18n.t(k), label: i18n.t(k) }));
  }

  if (fieldKey === 'field_card_network' && category === 'PAYMENT_CARD') {
    return [
      { value: 'VISA', label: 'VISA' },
      { value: 'Mastercard', label: 'Mastercard' },
      { value: 'American Express', label: 'American Express (AMEX)' },
      { value: 'girocard', label: 'girocard / EC' },
      { value: 'Maestro', label: 'Maestro' },
      { value: 'V PAY', label: 'V PAY' },
      { value: 'Discover / Diners', label: 'Discover / Diners Club' },
      { value: 'UnionPay', label: 'UnionPay' }
    ];
  }

  if (fieldKey === 'field_card_type' && category === 'PAYMENT_CARD') {
    const keys = [
      'card_type_credit',
      'card_type_debit',
      'card_type_prepaid',
      'card_type_virtual',
      'card_type_charge',
      'card_type_business'
    ];
    return keys.map((k) => ({ value: i18n.t(k), label: i18n.t(k) }));
  }

  if (fieldKey === 'field_currency') {
    return [
      { value: 'EUR', label: 'EUR (€) — Euro' },
      { value: 'CHF', label: 'CHF — Swiss Franc' },
      { value: 'USD', label: 'USD ($) — US Dollar' },
      { value: 'GBP', label: 'GBP (£) — British Pound' },
      { value: 'SEK', label: 'SEK — Swedish Krona' },
      { value: 'NOK', label: 'NOK — Norwegian Krone' },
      { value: 'DKK', label: 'DKK — Danish Krone' },
      { value: 'PLN', label: 'PLN — Polish Złoty' },
      { value: 'JPY', label: 'JPY (¥) — Japanese Yen' }
    ];
  }

  if (fieldKey === 'field_coverage_plan' && category === 'INSURANCE_CARD') {
    return [
      { value: 'eGK / GKV + EHIC', label: 'Statutory Health + EHIC (eGK / GKV)' },
      { value: 'PKV', label: 'Private Health Insurance (PKV)' },
      { value: 'Dental / Supplementary', label: 'Supplementary / Dental (Zahnzusatz)' },
      { value: 'Travel Medical', label: 'International Travel Health' },
      { value: 'Liability / Home', label: 'Liability / Home / Motor' }
    ];
  }

  if (
    fieldKey === 'field_document_subtype' &&
    category === 'OFFICIAL_DOCUMENT'
  ) {
    return [
      { value: i18n.t('doc_type_passport'), label: i18n.t('doc_type_passport') },
      { value: i18n.t('doc_type_national_id'), label: i18n.t('doc_type_national_id') },
      { value: "Driver's License / Führerschein", label: "Driver's License / Führerschein" },
      { value: 'Residence Permit / Aufenthaltstitel', label: 'Residence Permit / Aufenthaltstitel' }
    ];
  }

  if (fieldKey === 'field_salutation' && category === 'IDENTITY') {
    return [
      { value: 'Ms. / Frau', label: 'Ms. / Frau' },
      { value: 'Mr. / Herr', label: 'Mr. / Herr' },
      { value: 'Mx.', label: 'Mx.' },
      { value: 'Dr.', label: 'Dr.' },
      { value: 'Prof. Dr.', label: 'Prof. Dr.' }
    ];
  }

  if (
    fieldKey === 'field_nationality' ||
    fieldKey === 'field_issuing_country'
  ) {
    return [
      { value: 'Germany (DEU)', label: '🇩🇪 Germany (DEU)' },
      { value: 'Greece (GRC)', label: '🇬🇷 Greece (GRC)' },
      { value: 'Italy (ITA)', label: '🇮🇹 Italy (ITA)' },
      { value: 'France (FRA)', label: '🇫🇷 France (FRA)' },
      { value: 'Spain (ESP)', label: '🇪🇸 Spain (ESP)' },
      { value: 'Austria (AUT)', label: '🇦🇹 Austria (AUT)' },
      { value: 'Switzerland (CHE)', label: '🇨🇭 Switzerland (CHE)' },
      { value: 'Netherlands (NLD)', label: '🇳🇱 Netherlands (NLD)' },
      { value: 'United Kingdom (GBR)', label: '🇬🇧 United Kingdom (GBR)' },
      { value: 'United States (USA)', label: '🇺🇸 United States (USA)' }
    ];
  }

  return null;
}

export interface InstitutionSuggestion {
  id: string;
  displayName: string;
  httpsUrl: string;
  primaryColorHex: string;
  defaultBic?: string;
}

const DEFAULT_BIC_BY_INSTITUTION_ID: Record<string, string> = {
  deutsche_bank: 'DEUTDEFFXXX',
  commerzbank: 'COBADEFFXXX',
  ing: 'INGDDEFFXXX',
  dkb: 'BYLADEM1001',
  sparkasse: 'BELADEBEXXX',
  volksbank: 'GENODEFFXXX',
  n26: 'NTSBDEB1XXX',
  revolut: 'REVOLT21XXX',
  postbank: 'PBNKDEFFXXX',
  hypovereinsbank: 'HYVEDEMMXXX',
  santander: 'SCFBDE33XXX',
  targobank: 'CMCIDEDDXXX',
  consorsbank: 'CSDBDE71XXX',
  trade_republic: 'TRBKDEBBXXX',
  scalable: 'SCABDEMMXXX',
  alpha_bank: 'CRBAGRAAXXX',
  eurobank: 'ERBKGRAAXXX',
  national_bank_greece: 'ETHNGRAAXXX',
  piraeus_bank: 'PIRBGRAAXXX',
  intesa_sanpaolo: 'BCITITMMXXX',
  bnp_paribas: 'BNPAFRPPXXX',
  ubs: 'UBSWCHZH80A',
  chase: 'CHASUS33XXX'
};

const GERMAN_BLZ_DIRECTORY: Record<
  string,
  {
    bankName: string;
    bic: string;
    httpsUrl: string;
    brandHex: string;
  }
> = {
  '50070010': {
    bankName: 'Deutsche Bank AG',
    bic: 'DEUTDEFFXXX',
    httpsUrl: 'https://www.deutsche-bank.de',
    brandHex: '#0018A8'
  },
  '10070000': {
    bankName: 'Deutsche Bank AG',
    bic: 'DEUTDEBBXXX',
    httpsUrl: 'https://www.deutsche-bank.de',
    brandHex: '#0018A8'
  },
  '37040044': {
    bankName: 'Commerzbank AG',
    bic: 'COBADEFFXXX',
    httpsUrl: 'https://www.commerzbank.de',
    brandHex: '#00414B'
  },
  '50010517': {
    bankName: 'ING-DiBa AG',
    bic: 'INGDDEFFXXX',
    httpsUrl: 'https://www.ing.de',
    brandHex: '#FF6200'
  },
  '12030000': {
    bankName: 'Deutsche Kreditbank AG (DKB)',
    bic: 'BYLADEM1001',
    httpsUrl: 'https://www.dkb.de',
    brandHex: '#148DEA'
  },
  '10011001': {
    bankName: 'N26 Bank SE',
    bic: 'NTSBDEB1XXX',
    httpsUrl: 'https://n26.com',
    brandHex: '#1A7F72'
  },
  '10050000': {
    bankName: 'Berliner Sparkasse',
    bic: 'BELADEBEXXX',
    httpsUrl: 'https://www.sparkasse.de',
    brandHex: '#EE0000'
  },
  '70020270': {
    bankName: 'HypoVereinsbank (UniCredit)',
    bic: 'HYVEDEMMXXX',
    httpsUrl: 'https://www.hypovereinsbank.de',
    brandHex: '#007A91'
  },
  '10010010': {
    bankName: 'Postbank',
    bic: 'PBNKDEFFXXX',
    httpsUrl: 'https://www.postbank.de',
    brandHex: '#003366'
  },
  '76030080': {
    bankName: 'Consorsbank',
    bic: 'CSDBDE71XXX',
    httpsUrl: 'https://www.consorsbank.de',
    brandHex: '#0080A6'
  }
};

export function suggestInstitutions(query: string): InstitutionSuggestion[] {
  const q = query.trim().toLowerCase();
  const list = INSTITUTION_BRAND_CATALOG.map((inst) => ({
    id: inst.id,
    displayName: inst.displayName,
    httpsUrl: normalizeHttpsUrl(inst.canonicalDomain),
    primaryColorHex: inst.primaryColorHex,
    defaultBic: DEFAULT_BIC_BY_INSTITUTION_ID[inst.id]
  }));
  if (!q) return list.slice(0, 12);
  return list
    .filter(
      (i) =>
        i.displayName.toLowerCase().includes(q) ||
        i.httpsUrl.toLowerCase().includes(q) ||
        i.id.includes(q)
    )
    .slice(0, 10);
}

/**
 * Formats phone numbers with international prefix (`+49`, `+30`, etc.) and readable grouping.
 */
export function extractDialCodeAndLocalPhone(
  rawValue: string,
  fallbackDialCode = '+49'
): { dialCode: string; formattedFull: string } {
  const trimmed = rawValue.trim();
  if (!trimmed) {
    return { dialCode: fallbackDialCode, formattedFull: '' };
  }
  const normalizedPlus = trimmed.startsWith('00')
    ? '+' + trimmed.slice(2)
    : trimmed;

  if (normalizedPlus.startsWith('+')) {
    const sorted = [...COUNTRY_DIAL_CODES].sort(
      (a, b) => b.dialCode.length - a.dialCode.length
    );
    const match = sorted.find((c) => normalizedPlus.startsWith(c.dialCode));
    const dial = match?.dialCode ?? fallbackDialCode;
    return {
      dialCode: dial,
      formattedFull: formatInternationalPhone(normalizedPlus, dial)
    };
  }
  return {
    dialCode: fallbackDialCode,
    formattedFull: formatInternationalPhone(trimmed, fallbackDialCode)
  };
}

export function formatInternationalPhone(
  rawInput: string,
  selectedDialCode = '+49'
): string {
  const trimmed = rawInput.trim();
  if (!trimmed) return '';

  let normalized = trimmed.replace(/[^\d+]/g, '');
  if (normalized.startsWith('00')) {
    normalized = '+' + normalized.slice(2);
  }

  let activeDial = selectedDialCode;
  let subscriberDigits = '';

  if (normalized.startsWith('+')) {
    const sorted = [...COUNTRY_DIAL_CODES].sort(
      (a, b) => b.dialCode.length - a.dialCode.length
    );
    const matched = sorted.find((c) => normalized.startsWith(c.dialCode));
    if (matched) {
      activeDial = matched.dialCode;
      subscriberDigits = normalized
        .slice(matched.dialCode.length)
        .replace(/\D/g, '');
    } else {
      const m = normalized.match(/^(\+\d{1,3})(\d*)$/);
      if (m) {
        activeDial = m[1];
        subscriberDigits = m[2];
      } else {
        return normalized;
      }
    }
  } else {
    subscriberDigits = normalized.replace(/\D/g, '');
    if (subscriberDigits.startsWith('0') && subscriberDigits.length > 1) {
      subscriberDigits = subscriberDigits.slice(1);
    }
  }

  if (!subscriberDigits) return activeDial;

  // Group subscriber digits cleanly in 3-4-4 or 3-3-4 blocks
  const groups: string[] = [];
  let cursor = 0;
  const firstLen = subscriberDigits.length >= 9 ? 3 : Math.min(3, subscriberDigits.length);
  groups.push(subscriberDigits.slice(0, firstLen));
  cursor = firstLen;
  while (cursor < subscriberDigits.length) {
    const rem = subscriberDigits.length - cursor;
    const step = rem > 4 ? 4 : rem;
    groups.push(subscriberDigits.slice(cursor, cursor + step));
    cursor += step;
  }

  return `${activeDial} ${groups.join(' ')}`.trim();
}

export interface IbanIntelligenceResult {
  formattedIban: string;
  countryCode: string;
  isValidChecksum: boolean;
  isCompleteLength: boolean;
  routingCode?: string;
  accountNumber?: string;
  inferredCurrency?: string;
  inferredBankName?: string;
  inferredBic?: string;
  inferredWebsite?: string;
  inferredBrandHex?: string;
}

const IBAN_COUNTRY_CURRENCY: Record<string, string> = {
  DE: 'EUR',
  AT: 'EUR',
  FR: 'EUR',
  IT: 'EUR',
  ES: 'EUR',
  GR: 'EUR',
  NL: 'EUR',
  BE: 'EUR',
  LU: 'EUR',
  PT: 'EUR',
  IE: 'EUR',
  FI: 'EUR',
  CY: 'EUR',
  CH: 'CHF',
  LI: 'CHF',
  GB: 'GBP',
  SE: 'SEK',
  NO: 'NOK',
  DK: 'DKK',
  PL: 'PLN'
};

export function analyzeAndFormatIban(rawInput: string): IbanIntelligenceResult {
  const compact = rawInput.replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
  const formattedIban = compact.replace(/(.{4})(?=.)/g, '$1 ').trim();
  const countryCode = compact.slice(0, 2);
  const isValidChecksum = compact.length >= 15 ? validateIsoIban(compact) : false;

  let routingCode: string | undefined;
  let accountNumber: string | undefined;
  let inferredBankName: string | undefined;
  let inferredBic: string | undefined;
  let inferredWebsite: string | undefined;
  let inferredBrandHex: string | undefined;

  if (countryCode === 'DE' && compact.length >= 12) {
    routingCode = compact.slice(4, 12);
    if (compact.length === 22) {
      accountNumber = compact.slice(12, 22);
    }
    const hit = GERMAN_BLZ_DIRECTORY[routingCode];
    if (hit) {
      inferredBankName = hit.bankName;
      inferredBic = hit.bic;
      inferredWebsite = hit.httpsUrl;
      inferredBrandHex = hit.brandHex;
    }
  } else if (countryCode === 'AT' && compact.length >= 9) {
    routingCode = compact.slice(4, 9);
    if (compact.length === 20) accountNumber = compact.slice(9, 20);
  } else if (countryCode === 'CH' && compact.length >= 9) {
    routingCode = compact.slice(4, 9);
    if (compact.length === 21) accountNumber = compact.slice(9, 21);
  } else if (countryCode === 'GR' && compact.length >= 11) {
    routingCode = compact.slice(4, 11);
    if (compact.length === 27) accountNumber = compact.slice(11, 27);
  }

  return {
    formattedIban,
    countryCode,
    isValidChecksum,
    isCompleteLength: compact.length >= 15,
    routingCode,
    accountNumber,
    inferredCurrency: IBAN_COUNTRY_CURRENCY[countryCode],
    inferredBankName,
    inferredBic,
    inferredWebsite,
    inferredBrandHex
  };
}

export function analyzeAndFormatCardNumber(rawInput: string): {
  formattedPan: string;
  networkBrand: string;
  validLuhn: boolean;
} {
  const digits = rawInput.replace(/\D/g, '').slice(0, 19);
  const isAmex = digits.startsWith('34') || digits.startsWith('37');
  let formattedPan = '';
  if (isAmex) {
    const p1 = digits.slice(0, 4);
    const p2 = digits.slice(4, 10);
    const p3 = digits.slice(10, 15);
    formattedPan = [p1, p2, p3].filter(Boolean).join(' ');
  } else {
    formattedPan = digits.replace(/(\d{4})(?=\d)/g, '$1 ').trim();
  }
  const validation = validatePaymentCardPan(digits);
  return {
    formattedPan,
    networkBrand: validation.networkBrand,
    validLuhn: validation.validLuhn
  };
}

export function formatBicSwift(rawInput: string): {
  formattedBic: string;
  isValidFormat: boolean;
} {
  const cleaned = rawInput.replace(/[^a-zA-Z0-9]/g, '').toUpperCase().slice(0, 11);
  const isValidFormat = /^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$/.test(
    cleaned
  );
  return { formattedBic: cleaned, isValidFormat };
}

export function formatIsoDateSmart(
  rawInput: string,
  allowYearMonth = false
): string {
  const trimmed = rawInput.trim();
  if (!trimmed) return '';

  // Support MM/YY or MM/YYYY for card expiry/valid-from
  if (allowYearMonth) {
    const mmyy = trimmed.match(/^(\d{1,2})[/. -](\d{2}|\d{4})$/);
    if (mmyy) {
      const mm = mmyy[1].padStart(2, '0');
      const yyyy = mmyy[2].length === 2 ? `20${mmyy[2]}` : mmyy[2];
      const monthNum = Number(mm);
      if (monthNum >= 1 && monthNum <= 12) {
        return `${yyyy}-${mm}`;
      }
    }
    if (/^\d{4}-\d{2}$/.test(trimmed)) return trimmed;
  }

  // Support DD.MM.YYYY or DD/MM/YYYY -> YYYY-MM-DD
  const dmy = trimmed.match(/^(\d{1,2})[./](\d{1,2})[./](\d{4})$/);
  if (dmy) {
    const dd = dmy[1].padStart(2, '0');
    const mm = dmy[2].padStart(2, '0');
    const yyyy = dmy[3];
    return `${yyyy}-${mm}-${dd}`;
  }

  // Auto-hyphenate pure digits YYYYMMDD or YYYYMM
  const digits = trimmed.replace(/\D/g, '');
  if (allowYearMonth && digits.length === 6 && digits.startsWith('20')) {
    return `${digits.slice(0, 4)}-${digits.slice(4, 6)}`;
  }
  if (digits.length === 8 && (digits.startsWith('19') || digits.startsWith('20'))) {
    return `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6, 8)}`;
  }

  return trimmed;
}
