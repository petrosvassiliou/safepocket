import enCatalog from '@i18n/en.arb.json';
import deCatalog from '@i18n/de.arb.json';
import itCatalog from '@i18n/it.arb.json';
import frCatalog from '@i18n/fr.arb.json';
import esCatalog from '@i18n/es.arb.json';
import elCatalog from '@i18n/el.arb.json';
import type { SupportedLocale, VaultCategory } from '../types';

export type TranslationKey = keyof typeof enCatalog;
type RawCatalog = Record<string, string>;

const CATALOGS: Record<SupportedLocale, RawCatalog> = {
  en: enCatalog as RawCatalog,
  de: deCatalog as RawCatalog,
  it: itCatalog as RawCatalog,
  fr: frCatalog as RawCatalog,
  es: esCatalog as RawCatalog,
  el: elCatalog as RawCatalog
};

export const SUPPORTED_LOCALES: ReadonlyArray<{
  code: SupportedLocale;
  nativeName: string;
  region: string;
}> = [
  { code: 'en', nativeName: 'English', region: 'US' },
  { code: 'de', nativeName: 'Deutsch', region: 'DE' },
  { code: 'it', nativeName: 'Italiano', region: 'IT' },
  { code: 'fr', nativeName: 'Français', region: 'FR' },
  { code: 'es', nativeName: 'Español', region: 'ES' },
  { code: 'el', nativeName: 'Ελληνικά', region: 'GR' }
];

const STORAGE_KEY = 'safepocket.locale_override';

export class SafePocketI18n {
  private manualOverride: SupportedLocale | null = null;
  private systemLocale: SupportedLocale;
  private listeners = new Set<(locale: SupportedLocale) => void>();

  constructor(systemLanguageTag?: string) {
    const detected =
      systemLanguageTag ??
      (typeof navigator !== 'undefined' ? navigator.language : 'en');
    this.systemLocale = SafePocketI18n.normalizeLocale(detected);

    if (typeof localStorage !== 'undefined') {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved && saved in CATALOGS) {
        this.manualOverride = saved as SupportedLocale;
      }
    }
    this.syncDocumentLang();
  }

  static normalizeLocale(tag: string | null | undefined): SupportedLocale {
    if (!tag) return 'en';
    const base = tag.toLowerCase().split(/[-_]/)[0];
    if (base in CATALOGS) return base as SupportedLocale;
    return 'en';
  }

  get activeLocale(): SupportedLocale {
    return this.manualOverride ?? this.systemLocale;
  }

  get detectedSystemLocale(): SupportedLocale {
    return this.systemLocale;
  }

  get isUsingSystemLocale(): boolean {
    return this.manualOverride === null;
  }

  setLocaleOverride(locale: SupportedLocale | null): void {
    this.manualOverride = locale;
    if (typeof localStorage !== 'undefined') {
      if (locale === null) {
        localStorage.removeItem(STORAGE_KEY);
      } else {
        localStorage.setItem(STORAGE_KEY, locale);
      }
    }
    this.syncDocumentLang();
    for (const listener of this.listeners) {
      listener(this.activeLocale);
    }
  }

  subscribe(listener: (locale: SupportedLocale) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  t(key: string, params?: Record<string, string | number>): string {
    const active = CATALOGS[this.activeLocale];
    const template = active[key] ?? CATALOGS.en[key] ?? key;
    if (!params) return template;
    return Object.entries(params).reduce(
      (acc, [k, v]) => acc.replaceAll(`{${k}}`, String(v)),
      template
    );
  }

  plural(
    baseKey: string,
    count: number,
    extraParams?: Record<string, string | number>
  ): string {
    const locale = this.activeLocale;
    const cat = CATALOGS[locale];
    let suffix: 'zero' | 'one' | 'other';
    if (count === 0 && `${baseKey}_zero` in cat) {
      suffix = 'zero';
    } else if (locale === 'fr' && (count === 0 || count === 1)) {
      suffix = 'one';
    } else if (Math.abs(count) === 1) {
      suffix = 'one';
    } else {
      suffix = 'other';
    }
    return this.t(`${baseKey}_${suffix}`, { count, ...extraParams });
  }

  formatDate(isoDate: string): string {
    if (!isoDate) return '';
    // Handle YYYY-MM card expiry format cleanly as well as YYYY-MM-DD
    if (/^\d{4}-\d{2}$/.test(isoDate)) {
      const [y, m] = isoDate.split('-').map(Number);
      const dt = new Date(Date.UTC(y, m - 1, 1));
      return new Intl.DateTimeFormat(this.activeLocale, {
        month: '2-digit',
        year: 'numeric',
        timeZone: 'UTC'
      }).format(dt);
    }
    const parsed = new Date(`${isoDate}T00:00:00Z`);
    if (Number.isNaN(parsed.getTime())) return isoDate;
    return new Intl.DateTimeFormat(this.activeLocale, {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC'
    }).format(parsed);
  }

  formatBytes(bytes: number): string {
    const nf = new Intl.NumberFormat(this.activeLocale, {
      maximumFractionDigits: 1
    });
    if (bytes < 1024) return `${nf.format(bytes)} B`;
    if (bytes < 1024 * 1024) return `${nf.format(bytes / 1024)} KB`;
    return `${nf.format(bytes / (1024 * 1024))} MB`;
  }

  /**
   * Dynamically resolves language-agnostic canonical field keys (`field_tax_id`, etc.)
   * and applies regional Identity templates (e.g. Steuer-ID in DE, Codice Fiscale in IT,
   * ΑΦΜ (AFM) in EL, NIF / NIE in ES, Numéro fiscal / NIR in FR).
   */
  resolveFieldLabel(
    labelKey: string,
    isCustomLabel: boolean | undefined,
    category: VaultCategory
  ): string {
    if (isCustomLabel) return labelKey;
    if (category === 'BANK_ACCOUNT' && labelKey === 'field_issuing_bank') {
      return this.t('field_bank_name');
    }
    if (category === 'IDENTITY') {
      if (labelKey === 'field_tax_id') return this.t('identity_template_tax_id');
      if (labelKey === 'field_ssn') return this.t('identity_template_ssn');
      if (labelKey === 'field_health_insurance') {
        return this.t('identity_template_health_insurance');
      }
    }
    return this.t(labelKey);
  }

  resolveCategoryName(category: VaultCategory): string {
    switch (category) {
      case 'PAYMENT_CARD':
        return this.t('category_payment_card');
      case 'BANK_ACCOUNT':
        return this.t('category_bank_account');
      case 'INSURANCE_CARD':
        return this.t('category_insurance_card');
      case 'MEMBERSHIP_CARD':
        return this.t('category_membership_card');
      case 'IDENTITY':
        return this.t('category_identity');
      case 'OFFICIAL_DOCUMENT':
        return this.t('category_official_document');
      case 'SECURE_NOTE':
        return this.t('category_secure_note');
    }
  }

  private syncDocumentLang(): void {
    if (typeof document !== 'undefined') {
      document.documentElement.lang = this.activeLocale;
    }
  }
}
