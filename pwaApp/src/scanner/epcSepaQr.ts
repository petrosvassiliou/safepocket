/**
 * Offline EPC069-12 v002 SEPA Credit Transfer QR ("GiroCode") Generator & Read-Aloud Formatter.
 *
 * 100% offline — zero network requests, compatible with German/European banking apps
 * (Sparkasse, ING, DKB, N26, Deutsche Bank, UniCredit, BNP Paribas, Santander, Alpha Bank, etc.).
 */

export interface EpcSepaQrInput {
  holderName: string;
  iban: string;
  bic?: string;
  amountEur?: string | number;
  remittanceText?: string;
}

export interface ReadAloudBlock {
  index: number;
  chars: string;
}

export function normalizeSepaIban(rawIban: string): string {
  return rawIban.replace(/[\s-]+/g, '').toUpperCase().trim();
}

export function isPlausibleSepaIban(rawIban: string): boolean {
  const clean = normalizeSepaIban(rawIban);
  return /^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$/.test(clean);
}

export function formatSepaAmountEur(rawAmount?: string | number): string {
  if (rawAmount === undefined || rawAmount === null) return '';
  const normalized = String(rawAmount).trim().replace(',', '.').replace(/[^0-9.]/g, '');
  if (!normalized) return '';
  const parsed = Number(normalized);
  if (!Number.isFinite(parsed) || parsed <= 0 || parsed > 999999999.99) {
    return '';
  }
  return `EUR${parsed.toFixed(2)}`;
}

/**
 * Builds an EPC069-12 Version 002 SEPA Credit Transfer QR payload string.
 * Returns `null` if the IBAN is missing/invalid.
 */
export function buildEpcSepaQrPayload(input: EpcSepaQrInput): string | null {
  const cleanIban = normalizeSepaIban(input.iban);
  if (!isPlausibleSepaIban(cleanIban)) {
    return null;
  }

  const cleanHolder =
    input.holderName
      .replace(/[\r\n]+/g, ' ')
      .trim()
      .slice(0, 70) || 'Account Holder';

  const rawBic = (input.bic ?? '').replace(/\s+/g, '').toUpperCase();
  const cleanBic = /^[A-Z0-9]{8}([A-Z0-9]{3})?$/.test(rawBic) ? rawBic : '';

  const formattedAmount = formatSepaAmountEur(input.amountEur);
  const cleanRemittance = (input.remittanceText ?? '')
    .replace(/[\r\n]+/g, ' ')
    .trim()
    .slice(0, 40);

  const lines = [
    'BCD',
    '002',
    '1',
    'SCT',
    cleanBic,
    cleanHolder,
    cleanIban,
    formattedAmount,
    '',
    '',
    cleanRemittance
  ];

  while (lines.length > 7 && lines[lines.length - 1] === '') {
    lines.pop();
  }

  return lines.join('\n');
}

/**
 * Splits an IBAN, PAN, insurance ID, or document number into indexed 4-character blocks
 * for error-free phone dictation ("Read-Aloud Mode").
 */
export function buildReadAloudBlocks(
  rawValue: string,
  groupSize = 4
): ReadAloudBlock[] {
  const compact = rawValue.replace(/[\s-]+/g, '').trim();
  if (!compact) return [];
  const step = Math.max(2, groupSize);
  const blocks: ReadAloudBlock[] = [];
  let idx = 1;
  for (let i = 0; i < compact.length; i += step) {
    blocks.push({
      index: idx++,
      chars: compact.slice(i, i + step)
    });
  }
  return blocks;
}
