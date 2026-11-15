export interface ParsedMrzResult {
  format: 'TD3_PASSPORT' | 'TD1_NATIONAL_ID';
  documentSubtype: 'PASSPORT' | 'NATIONAL_ID';
  issuingState: string;
  nationality: string;
  documentNumber: string;
  holderFullName: string;
  birthDateIso: string;
  expiryDateIso: string;
  sex: string;
  checkDigitsValid: boolean;
}

const MRZ_WEIGHTS = [7, 3, 1] as const;

export function computeIcaoCheckDigit(field: string): number {
  let sum = 0;
  const upper = field.toUpperCase();
  for (let i = 0; i < upper.length; i++) {
    const ch = upper.charCodeAt(i);
    let val = 0;
    if (ch >= 48 && ch <= 57) {
      val = ch - 48;
    } else if (ch >= 65 && ch <= 90) {
      val = ch - 65 + 10;
    } else {
      val = 0; // '<' filler
    }
    sum += val * MRZ_WEIGHTS[i % 3];
  }
  return sum % 10;
}

function expandMrzDateToIso(yyMmDd: string, isExpiry: boolean): string {
  if (!/^\d{6}$/.test(yyMmDd)) return '';
  const yy = Number(yyMmDd.slice(0, 2));
  const mm = yyMmDd.slice(2, 4);
  const dd = yyMmDd.slice(4, 6);
  const currentTwoDigit = new Date().getUTCFullYear() % 100;
  const century = isExpiry
    ? 2000
    : yy <= currentTwoDigit
      ? 2000
      : 1900;
  return `${century + yy}-${mm}-${dd}`;
}

function parseMrzHolderName(rawNameSegment: string): string {
  const [surnamePart = '', givenPart = ''] = rawNameSegment.split('<<');
  const surname = surnamePart.replace(/<+/g, ' ').trim();
  const given = givenPart.replace(/<+/g, ' ').trim();
  return [given, surname].filter(Boolean).join(' ');
}

/**
 * 100% Client-Side Offline ICAO Doc 9303 MRZ Parser (TD3 Passports & TD1 National IDs).
 * Zero cloud OCR or outbound API calls.
 */
export function parseOfflineIcaoMrz(rawMrzText: string): ParsedMrzResult {
  const lines = rawMrzText
    .split(/\r?\n/)
    .map((l) => l.trim().toUpperCase().replace(/\s+/g, ''))
    .filter(Boolean);

  // TD3 Biometric Passport: 2 lines x 44 chars
  if (lines.length === 2 && lines[0].length === 44 && lines[1].length === 44) {
    const [l1, l2] = lines;
    const issuingState = l1.slice(2, 5).replace(/<+/g, '');
    const holderFullName = parseMrzHolderName(l1.slice(5));

    const docField = l2.slice(0, 9);
    const docCheck = Number(l2[9]);
    const nationality = l2.slice(10, 13).replace(/<+/g, '');
    const dobField = l2.slice(13, 19);
    const dobCheck = Number(l2[19]);
    const sex = l2[20] === '<' ? 'X' : l2[20];
    const expField = l2.slice(21, 27);
    const expCheck = Number(l2[27]);

    const checkDigitsValid =
      computeIcaoCheckDigit(docField) === docCheck &&
      computeIcaoCheckDigit(dobField) === dobCheck &&
      computeIcaoCheckDigit(expField) === expCheck;

    return {
      format: 'TD3_PASSPORT',
      documentSubtype: 'PASSPORT',
      issuingState,
      nationality,
      documentNumber: docField.replace(/<+/g, ''),
      holderFullName,
      birthDateIso: expandMrzDateToIso(dobField, false),
      expiryDateIso: expandMrzDateToIso(expField, true),
      sex,
      checkDigitsValid
    };
  }

  // TD1 National ID Card (CIE / Personalausweis): 3 lines x 30 chars
  if (
    lines.length === 3 &&
    lines[0].length === 30 &&
    lines[1].length === 30 &&
    lines[2].length === 30
  ) {
    const [l1, l2, l3] = lines;
    const issuingState = l1.slice(2, 5).replace(/<+/g, '');
    const docField = l1.slice(5, 14);
    const docCheck = Number(l1[14]);

    const dobField = l2.slice(0, 6);
    const dobCheck = Number(l2[6]);
    const sex = l2[7] === '<' ? 'X' : l2[7];
    const expField = l2.slice(8, 14);
    const expCheck = Number(l2[14]);
    const nationality = l2.slice(15, 18).replace(/<+/g, '');
    const holderFullName = parseMrzHolderName(l3);

    const checkDigitsValid =
      computeIcaoCheckDigit(docField) === docCheck &&
      computeIcaoCheckDigit(dobField) === dobCheck &&
      computeIcaoCheckDigit(expField) === expCheck;

    return {
      format: 'TD1_NATIONAL_ID',
      documentSubtype: 'NATIONAL_ID',
      issuingState,
      nationality,
      documentNumber: docField.replace(/<+/g, ''),
      holderFullName,
      birthDateIso: expandMrzDateToIso(dobField, false),
      expiryDateIso: expandMrzDateToIso(expField, true),
      sex,
      checkDigitsValid
    };
  }

  throw new Error(
    'Unrecognized MRZ geometry: expected TD3 (2×44 chars) or TD1 (3×30 chars)'
  );
}

export function buildSampleTd3PassportMrz(): string {
  const line1 = 'P<DEUMUELLER<<CLARA<SOFIA<<<<<<<<<<<<<<<<<<<';
  const docNum = 'C01X00T47';
  const docChk = computeIcaoCheckDigit(docNum);
  const dob = '920419';
  const dobChk = computeIcaoCheckDigit(dob);
  const exp = '310615';
  const expChk = computeIcaoCheckDigit(exp);
  const optional = '<<<<<<<<<<<<<<';
  const optChk = computeIcaoCheckDigit(optional);
  const compositeInput = `${docNum}${docChk}${dob}${dobChk}${exp}${expChk}${optional}${optChk}`;
  const finalChk = computeIcaoCheckDigit(compositeInput);
  const line2 = `${docNum}${docChk}DEU${dob}${dobChk}F${exp}${expChk}${optional}${optChk}${finalChk}`;
  return `${line1}\n${line2}`;
}

export interface CardValidationResult {
  validLuhn: boolean;
  networkBrand: 'Visa' | 'Mastercard' | 'Amex' | 'Discover' | 'Card';
  digitsLength: number;
}

/**
 * Offline Luhn PAN Checksum & Card Network Detector.
 */
export function validatePaymentCardPan(rawPan: string): CardValidationResult {
  const digits = rawPan.replace(/\D+/g, '');
  let networkBrand: CardValidationResult['networkBrand'] = 'Card';
  if (/^4/.test(digits)) networkBrand = 'Visa';
  else if (/^(5[1-5]|2[2-7])/.test(digits)) networkBrand = 'Mastercard';
  else if (/^3[47]/.test(digits)) networkBrand = 'Amex';
  else if (/^6(?:011|5)/.test(digits)) networkBrand = 'Discover';

  if (digits.length < 13 || digits.length > 19) {
    return { validLuhn: false, networkBrand, digitsLength: digits.length };
  }

  let sum = 0;
  let shouldDouble = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48;
    if (shouldDouble) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    shouldDouble = !shouldDouble;
  }

  return {
    validLuhn: sum % 10 === 0,
    networkBrand,
    digitsLength: digits.length
  };
}

/**
 * Offline ISO-13616 Mod-97 IBAN Checksum Validator.
 */
export function validateIsoIban(rawIban: string): boolean {
  const cleaned = rawIban.replace(/\s+/g, '').toUpperCase();
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(cleaned)) {
    return false;
  }
  const rearranged = cleaned.slice(4) + cleaned.slice(0, 4);
  let remainder = 0;
  for (let i = 0; i < rearranged.length; i++) {
    const code = rearranged.charCodeAt(i);
    const expanded =
      code >= 65 && code <= 90 ? String(code - 55) : rearranged[i];
    for (let j = 0; j < expanded.length; j++) {
      remainder = (remainder * 10 + (expanded.charCodeAt(j) - 48)) % 97;
    }
  }
  return remainder === 1;
}
