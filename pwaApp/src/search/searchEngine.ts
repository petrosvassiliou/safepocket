import type { SafePocketI18n } from '../i18n/l10n';
import type { VaultCategory, VaultItem } from '../types';

const GREEK_ACCENT_MAP: Record<string, string> = {
  ά: 'α',
  έ: 'ε',
  ή: 'η',
  ί: 'ι',
  ϊ: 'ι',
  ΐ: 'ι',
  ό: 'ο',
  ύ: 'υ',
  ϋ: 'υ',
  ΰ: 'υ',
  ώ: 'ω',
  ς: 'σ'
};

const GREEK_TO_LATIN_MAP: Record<string, string> = {
  α: 'a',
  β: 'v',
  γ: 'g',
  δ: 'd',
  ε: 'e',
  ζ: 'z',
  η: 'i',
  θ: 'th',
  ι: 'i',
  κ: 'k',
  λ: 'l',
  μ: 'm',
  ν: 'n',
  ξ: 'x',
  ο: 'o',
  π: 'p',
  ρ: 'r',
  σ: 's',
  τ: 't',
  υ: 'y',
  φ: 'f',
  χ: 'ch',
  ψ: 'ps',
  ω: 'o'
};

export function stripAccents(input: string): string {
  const lower = input.toLowerCase();
  let mapped = '';
  for (const ch of lower) {
    if (ch === 'ß') mapped += 'ss';
    else if (ch === 'æ') mapped += 'a';
    else if (ch === 'ø') mapped += 'o';
    else if (ch in GREEK_ACCENT_MAP) mapped += GREEK_ACCENT_MAP[ch];
    else mapped += ch;
  }
  return mapped.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

export function expandDigraphsAndStrip(input: string): string {
  const lower = input.toLowerCase();
  let mapped = '';
  for (const ch of lower) {
    if (ch === 'ä' || ch === 'æ') mapped += 'ae';
    else if (ch === 'ö' || ch === 'ø') mapped += 'oe';
    else if (ch === 'ü') mapped += 'ue';
    else if (ch === 'ß') mapped += 'ss';
    else if (ch in GREEK_ACCENT_MAP) mapped += GREEK_ACCENT_MAP[ch];
    else mapped += ch;
  }
  return mapped.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

export function foldGreekToLatin(input: string): string {
  const base = expandDigraphsAndStrip(input);
  let out = '';
  for (const ch of base) {
    out += GREEK_TO_LATIN_MAP[ch] ?? ch;
  }
  return out;
}

export interface ExpiryComputation {
  severity: 'none' | 'valid' | 'warning' | 'expired';
  daysRemaining: number;
}

export function computeExpiryStatus(
  item: VaultItem,
  referenceEpochMs = Date.now()
): ExpiryComputation {
  const expiryField = item.fields.find((f) => f.labelKey === 'field_expiry_date');
  if (!expiryField || !expiryField.value.trim()) {
    return { severity: 'none', daysRemaining: 0 };
  }
  const raw = expiryField.value.trim();
  let targetDate: Date;
  if (/^\d{4}-\d{2}$/.test(raw)) {
    const [y, m] = raw.split('-').map(Number);
    // Last day of expiry month
    targetDate = new Date(Date.UTC(y, m, 0, 23, 59, 59));
  } else {
    targetDate = new Date(`${raw}T23:59:59Z`);
  }
  if (Number.isNaN(targetDate.getTime())) {
    return { severity: 'none', daysRemaining: 0 };
  }
  const diffDays = Math.ceil(
    (targetDate.getTime() - referenceEpochMs) / (1000 * 60 * 60 * 24)
  );
  if (diffDays < 0) {
    return { severity: 'expired', daysRemaining: diffDays };
  }
  if (diffDays <= 90) {
    return { severity: 'warning', daysRemaining: diffDays };
  }
  return { severity: 'valid', daysRemaining: diffDays };
}

/**
 * Instant (< 50ms) client-side search across titles, tags, localized category names,
 * localized field labels, and decrypted field values, with full accent/umlaut/Greek folding.
 */
export function searchVaultItems(
  items: VaultItem[],
  query: string,
  i18n: SafePocketI18n,
  filter: {
    category?: VaultCategory | null;
    favoritesOnly?: boolean;
    expiringOnly?: boolean;
  } = {}
): VaultItem[] {
  const subset = items.filter((item) => {
    if (filter.category && item.category !== filter.category) return false;
    if (filter.favoritesOnly && !item.favorite) return false;
    if (filter.expiringOnly) {
      const exp = computeExpiryStatus(item);
      if (exp.severity !== 'warning' && exp.severity !== 'expired') return false;
    }
    return true;
  });

  const trimmed = query.trim();
  if (!trimmed) return subset;

  const queryTokens = trimmed
    .split(/\s+/)
    .filter(Boolean)
    .map((tok) => stripAccents(tok));

  return subset.filter((item) => {
    const rawCorpus = [
      item.title,
      item.subtitle,
      i18n.resolveCategoryName(item.category),
      ...item.tags,
      ...item.fields.flatMap((f) => [
        i18n.resolveFieldLabel(f.labelKey, f.isCustomLabel, item.category),
        f.value
      ])
    ].join(' ');

    const projections = [
      stripAccents(rawCorpus),
      expandDigraphsAndStrip(rawCorpus),
      foldGreekToLatin(rawCorpus)
    ];

    return queryTokens.every((token) =>
      projections.some((proj) => proj.includes(token))
    );
  });
}
