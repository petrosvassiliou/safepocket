import type { SafePocketI18n } from '../i18n/l10n';
import type {
  ExpiryWindowDays,
  VaultCategory,
  VaultItem,
  VaultSortMode
} from '../types';

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
  severity: 'none' | 'valid' | 'warning' | 'critical' | 'expired';
  daysRemaining: number;
}

export interface VaultExpirySummary {
  expiredCount: number;
  criticalCount: number;
  warningCount: number;
  totalAttentionCount: number;
}

export function parseExpiryDateUtc(rawDate: string): Date | null {
  const raw = rawDate.trim();
  if (!raw) return null;
  if (/^\d{4}-\d{2}$/.test(raw)) {
    const [y, m] = raw.split('-').map(Number);
    const d = new Date(Date.UTC(y, m, 0, 23, 59, 59));
    return Number.isNaN(d.getTime()) ? null : d;
  }
  if (/^(0[1-9]|1[0-2])\/\d{2}$/.test(raw)) {
    const [m, yy] = raw.split('/').map(Number);
    const d = new Date(Date.UTC(2000 + yy, m, 0, 23, 59, 59));
    return Number.isNaN(d.getTime()) ? null : d;
  }
  const iso = new Date(`${raw}T23:59:59Z`);
  return Number.isNaN(iso.getTime()) ? null : iso;
}

export function computeExpiryStatus(
  item: VaultItem,
  referenceEpochMs = Date.now(),
  warningWindowDays: ExpiryWindowDays = 90
): ExpiryComputation {
  const expiryField = item.fields.find((f) => f.labelKey === 'field_expiry_date');
  if (!expiryField || !expiryField.value.trim()) {
    return { severity: 'none', daysRemaining: 0 };
  }
  const targetDate = parseExpiryDateUtc(expiryField.value);
  if (!targetDate) {
    return { severity: 'none', daysRemaining: 0 };
  }
  const diffDays = Math.ceil(
    (targetDate.getTime() - referenceEpochMs) / (1000 * 60 * 60 * 24)
  );
  if (diffDays < 0) {
    return { severity: 'expired', daysRemaining: diffDays };
  }
  if (diffDays <= 30) {
    return { severity: 'critical', daysRemaining: diffDays };
  }
  if (diffDays <= warningWindowDays) {
    return { severity: 'warning', daysRemaining: diffDays };
  }
  return { severity: 'valid', daysRemaining: diffDays };
}

export function summarizeVaultExpiries(
  items: ReadonlyArray<VaultItem>,
  referenceEpochMs = Date.now(),
  warningWindowDays: ExpiryWindowDays = 90
): VaultExpirySummary {
  let expiredCount = 0;
  let criticalCount = 0;
  let warningCount = 0;
  for (const item of items) {
    const res = computeExpiryStatus(item, referenceEpochMs, warningWindowDays);
    if (res.severity === 'expired') expiredCount++;
    else if (res.severity === 'critical') criticalCount++;
    else if (res.severity === 'warning') warningCount++;
  }
  return {
    expiredCount,
    criticalCount,
    warningCount,
    totalAttentionCount: expiredCount + criticalCount + warningCount
  };
}

export function extractVaultTags(
  items: ReadonlyArray<VaultItem>
): Array<{ tag: string; count: number }> {
  const counts = new Map<string, { display: string; count: number }>();
  for (const item of items) {
    const seenInItem = new Set<string>();
    for (const rawTag of item.tags) {
      const cleaned = rawTag.trim();
      if (!cleaned) continue;
      const key = stripAccents(cleaned);
      if (seenInItem.has(key)) continue;
      seenInItem.add(key);
      const existing = counts.get(key);
      if (existing) {
        existing.count += 1;
      } else {
        counts.set(key, { display: cleaned, count: 1 });
      }
    }
  }
  return Array.from(counts.values())
    .map((entry) => ({ tag: entry.display, count: entry.count }))
    .sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
}

export function sortVaultItems(
  items: ReadonlyArray<VaultItem>,
  mode: VaultSortMode,
  locale = 'en',
  referenceEpochMs = Date.now(),
  warningWindowDays: ExpiryWindowDays = 90
): VaultItem[] {
  const copy = [...items];
  const collator = new Intl.Collator(locale, {
    sensitivity: 'base',
    numeric: true
  });

  copy.sort((a, b) => {
    if (mode === 'pinned_recent') {
      const pinDiff = Number(Boolean(b.pinned)) - Number(Boolean(a.pinned));
      if (pinDiff !== 0) return pinDiff;
      const favDiff = Number(Boolean(b.favorite)) - Number(Boolean(a.favorite));
      if (favDiff !== 0) return favDiff;
      const aRec = a.lastAccessedAtEpochMs ?? a.updatedAtEpochMs;
      const bRec = b.lastAccessedAtEpochMs ?? b.updatedAtEpochMs;
      return bRec - aRec;
    }

    if (mode === 'alphabetical') {
      const pinDiff = Number(Boolean(b.pinned)) - Number(Boolean(a.pinned));
      if (pinDiff !== 0) return pinDiff;
      return collator.compare(a.title, b.title);
    }

    if (mode === 'expiring_soon') {
      const expA = computeExpiryStatus(a, referenceEpochMs, warningWindowDays);
      const expB = computeExpiryStatus(b, referenceEpochMs, warningWindowDays);
      const hasA = expA.severity !== 'none';
      const hasB = expB.severity !== 'none';
      if (hasA !== hasB) return hasA ? -1 : 1;
      if (hasA && hasB && expA.daysRemaining !== expB.daysRemaining) {
        return expA.daysRemaining - expB.daysRemaining;
      }
      return collator.compare(a.title, b.title);
    }

    // updated_desc
    return b.updatedAtEpochMs - a.updatedAtEpochMs;
  });

  return copy;
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
    tag?: string | null;
    warningWindowDays?: ExpiryWindowDays;
    referenceEpochMs?: number;
  } = {}
): VaultItem[] {
  const normalizedTagFilter = filter.tag ? stripAccents(filter.tag.trim()) : null;
  const windowDays = filter.warningWindowDays ?? 90;
  const refMs = filter.referenceEpochMs ?? Date.now();

  const subset = items.filter((item) => {
    if (filter.category && item.category !== filter.category) return false;
    if (filter.favoritesOnly && !item.favorite) return false;
    if (normalizedTagFilter) {
      const hasTag = item.tags.some(
        (t) => stripAccents(t.trim()) === normalizedTagFilter
      );
      if (!hasTag) return false;
    }
    if (filter.expiringOnly) {
      const exp = computeExpiryStatus(item, refMs, windowDays);
      if (
        exp.severity !== 'warning' &&
        exp.severity !== 'critical' &&
        exp.severity !== 'expired'
      ) {
        return false;
      }
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
