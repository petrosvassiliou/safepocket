import type { BarcodeFormat } from '../types';

/**
 * ISO/IEC 15417 Code 128 Symbol Width Patterns (values 0..106).
 * Symbols 0..105 each have 6 elements (3 bars, 3 spaces) totaling 11 modules.
 * Stop symbol 106 has 7 elements (4 bars, 3 spaces) totaling 13 modules.
 */
const CODE128_PATTERNS: ReadonlyArray<string> = [
  '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312',
  '132212', '221213', '221312', '231212', '112232', '122132', '122231', '113222',
  '123122', '123221', '223211', '221132', '221231', '213212', '223112', '312131',
  '311222', '321122', '321221', '312212', '322112', '322211', '212123', '212321',
  '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
  '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121',
  '313121', '211331', '231131', '213113', '213311', '213131', '311123', '311321',
  '331121', '312113', '312311', '332111', '314111', '221411', '431111', '111224',
  '111422', '121124', '121421', '141122', '141221', '112214', '112412', '122114',
  '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
  '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112',
  '421211', '212141', '214121', '412121', '111143', '111341', '131141', '114113',
  '114311', '411113', '411311', '113141', '114131', '311141', '411131', '211412',
  '211214', '211232', '2331112'
];

const START_CODE_B = 104;
const STOP_CODE = 106;

// EAN-13 encoding tables (GS1 General Specifications)
const EAN_LEFT_PARITY: ReadonlyArray<string> = [
  'LLLLLL',
  'LLGLGG',
  'LLGGLG',
  'LLGGGL',
  'LGLLGG',
  'LGGLLG',
  'LGGGLL',
  'LGLGLG',
  'LGLGGL',
  'LGGLGL'
];

const EAN_L_CODES: ReadonlyArray<string> = [
  '0001101', '0011001', '0010011', '0111101', '0100011',
  '0110001', '0101111', '0111011', '0110111', '0001011'
];

const EAN_G_CODES: ReadonlyArray<string> = [
  '0100111', '0110011', '0011011', '0100001', '0011101',
  '0111001', '0000101', '0010001', '0001001', '0010111'
];

const EAN_R_CODES: ReadonlyArray<string> = [
  '1110010', '1100110', '1101100', '1000010', '1011100',
  '1001110', '1010000', '1000100', '1001000', '1110100'
];

export interface BarcodeSvgOptions {
  heightPx?: number;
  maxWidthPx?: number;
  inkColor?: string;
  backgroundColor?: string;
}

/**
 * Sanitizes text to printable ASCII 32..126 for ISO/IEC 15417 Code 128B.
 */
export function sanitizeCode128BInput(raw: string): string {
  const trimmed = raw.trim();
  let out = '';
  for (let i = 0; i < trimmed.length; i++) {
    const code = trimmed.charCodeAt(i);
    if (code >= 32 && code <= 126) {
      out += trimmed[i];
    }
  }
  return out || 'SAFEPOCKET';
}

/**
 * Produces the binary module array (`0 | 1`) for an ISO/IEC 15417 Code 128B barcode,
 * including 10-module left/right quiet zones, Start-B (104), weighted Mod-103 check symbol, and Stop (106).
 */
export function encodeCode128BModules(rawValue: string): number[] {
  const clean = sanitizeCode128BInput(rawValue);
  const symbolValues: number[] = [START_CODE_B];
  let weightedSum = START_CODE_B;

  for (let i = 0; i < clean.length; i++) {
    const symVal = clean.charCodeAt(i) - 32;
    symbolValues.push(symVal);
    weightedSum += (i + 1) * symVal;
  }

  const checksum = weightedSum % 103;
  symbolValues.push(checksum);
  symbolValues.push(STOP_CODE);

  const modules: number[] = new Array(10).fill(0); // 10X left quiet zone
  for (const val of symbolValues) {
    const pattern = CODE128_PATTERNS[val];
    for (let idx = 0; idx < pattern.length; idx++) {
      const width = Number(pattern[idx]);
      const isBar = idx % 2 === 0 ? 1 : 0;
      for (let w = 0; w < width; w++) {
        modules.push(isBar);
      }
    }
  }
  for (let q = 0; q < 10; q++) {
    modules.push(0); // 10X right quiet zone
  }
  return modules;
}

/**
 * Decodes a 1D binary module sequence (`0 | 1`) or normalized bar/space width stream back into
 * a verified Code 128B ASCII string (verifying Start-B 104, Mod-103 check digit, and Stop 106).
 */
export function decodeCode128BFromModules(modules: ReadonlyArray<number>): string | null {
  // Collapse contiguous runs of 1s and 0s starting at first bar
  const runs: number[] = [];
  let i = 0;
  while (i < modules.length && modules[i] === 0) i++;
  while (i < modules.length) {
    const bit = modules[i];
    let len = 0;
    while (i < modules.length && modules[i] === bit) {
      len++;
      i++;
    }
    runs.push(len);
  }
  // Remove trailing quiet zone space run if even count
  if (runs.length % 2 === 0) {
    runs.pop();
  }
  // Minimum: Start (6) + 1 char (6) + Checksum (6) + Stop (7) = 25 runs
  if (runs.length < 25 || (runs.length - 7) % 6 !== 0) {
    return null;
  }

  // Estimate unit module width from first 6 runs (11 modules)
  const first11Sum = runs.slice(0, 6).reduce((a, b) => a + b, 0);
  if (first11Sum <= 0) return null;
  const unit = first11Sum / 11;

  const patternToVal = new Map<string, number>();
  CODE128_PATTERNS.forEach((pat, idx) => patternToVal.set(pat, idx));

  const normalizeGroup = (slice: number[], targetSum: number): string => {
    const scaled = slice.map((r) => Math.max(1, Math.min(4, Math.round(r / unit))));
    let diff = targetSum - scaled.reduce((a, b) => a + b, 0);
    while (diff !== 0) {
      let bestIdx = 0;
      let bestErr = -Infinity;
      for (let k = 0; k < slice.length; k++) {
        const exact = slice[k] / unit;
        const err = diff > 0 ? exact - scaled[k] : scaled[k] - exact;
        if (err > bestErr && (diff > 0 ? scaled[k] < 4 : scaled[k] > 1)) {
          bestErr = err;
          bestIdx = k;
        }
      }
      if (bestErr === -Infinity) break;
      scaled[bestIdx] += diff > 0 ? 1 : -1;
      diff += diff > 0 ? -1 : 1;
    }
    return scaled.join('');
  };

  const numSymbols = (runs.length - 7) / 6;
  const decodedVals: number[] = [];
  for (let s = 0; s < numSymbols; s++) {
    const pat = normalizeGroup(runs.slice(s * 6, s * 6 + 6), 11);
    const val = patternToVal.get(pat);
    if (val === undefined) return null;
    decodedVals.push(val);
  }

  const stopPat = normalizeGroup(runs.slice(numSymbols * 6, numSymbols * 6 + 7), 13);
  if (stopPat !== CODE128_PATTERNS[STOP_CODE]) return null;
  if (decodedVals[0] !== START_CODE_B || decodedVals.length < 3) return null;

  const expectedChecksum = decodedVals[decodedVals.length - 1];
  const dataVals = decodedVals.slice(1, -1);
  let weighted = START_CODE_B;
  let text = '';
  for (let idx = 0; idx < dataVals.length; idx++) {
    const v = dataVals[idx];
    if (v > 94) return null;
    weighted += (idx + 1) * v;
    text += String.fromCharCode(v + 32);
  }
  if (weighted % 103 !== expectedChecksum) return null;
  return text;
}

export function computeEan13CheckDigit(twelveDigits: string): number {
  if (!/^\d{12}$/.test(twelveDigits)) {
    throw new Error('EAN-13 check digit requires exactly 12 digits');
  }
  let sum = 0;
  for (let i = 0; i < 12; i++) {
    const d = twelveDigits.charCodeAt(i) - 48;
    sum += i % 2 === 0 ? d : d * 3;
  }
  return (10 - (sum % 10)) % 10;
}

export function normalizeEan13Digits(raw: string): string | null {
  const digits = raw.replace(/\D+/g, '');
  if (digits.length === 12) {
    return `${digits}${computeEan13CheckDigit(digits)}`;
  }
  if (digits.length === 13) {
    const expected = computeEan13CheckDigit(digits.slice(0, 12));
    if (Number(digits[12]) === expected) {
      return digits;
    }
  }
  return null;
}

export function encodeEan13Modules(rawDigits: string): {
  digits13: string;
  modules: number[];
  guardPositions: Set<number>;
} {
  const digits = rawDigits.replace(/\D+/g, '');
  const twelve =
    digits.length >= 12 ? digits.slice(0, 12) : digits.padStart(12, '0');
  const full13 =
    digits.length === 13 &&
    Number(digits[12]) === computeEan13CheckDigit(twelve)
      ? digits
      : `${twelve}${computeEan13CheckDigit(twelve)}`;

  const firstDigit = Number(full13[0]);
  const parity = EAN_LEFT_PARITY[firstDigit];
  const modules: number[] = [];
  const guardPositions = new Set<number>();

  const pushBits = (bits: string, isGuard = false) => {
    for (let i = 0; i < bits.length; i++) {
      if (isGuard) guardPositions.add(modules.length);
      modules.push(bits[i] === '1' ? 1 : 0);
    }
  };

  pushBits('00000000000'); // 11X left quiet zone
  pushBits('101', true); // Start guard
  for (let i = 0; i < 6; i++) {
    const d = Number(full13[i + 1]);
    pushBits(parity[i] === 'L' ? EAN_L_CODES[d] : EAN_G_CODES[d]);
  }
  pushBits('01010', true); // Center guard
  for (let i = 0; i < 6; i++) {
    const d = Number(full13[i + 7]);
    pushBits(EAN_R_CODES[d]);
  }
  pushBits('101', true); // End guard
  pushBits('0000000'); // 7X right quiet zone

  return { digits13: full13, modules, guardPositions };
}

export function encodeCode128BSvg(
  value: string,
  options: BarcodeSvgOptions = {}
): string {
  const modules = encodeCode128BModules(value);
  const height = options.heightPx ?? 48;
  const maxWidth = options.maxWidthPx ?? 360;
  const ink = options.inkColor ?? '#0f172a';
  const bg = options.backgroundColor;

  const rects: string[] = [];
  if (bg) {
    rects.push(
      `<rect x="0" y="0" width="${modules.length}" height="${height}" fill="${bg}" rx="3" />`
    );
  }
  let idx = 0;
  while (idx < modules.length) {
    if (modules[idx] === 1) {
      const start = idx;
      while (idx < modules.length && modules[idx] === 1) idx++;
      rects.push(
        `<rect x="${start}" y="2" width="${idx - start}" height="${height - 4}" fill="${ink}" shape-rendering="crispEdges" />`
      );
    } else {
      idx++;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${modules.length} ${height}" width="100%" height="${height}" style="max-width:${maxWidth}px;display:block;" role="img" aria-label="Code 128 Barcode">${rects.join('')}</svg>`;
}

export function encodeEan13Svg(
  value: string,
  options: BarcodeSvgOptions = {}
): string {
  const { modules, guardPositions } = encodeEan13Modules(value);
  const height = options.heightPx ?? 52;
  const maxWidth = options.maxWidthPx ?? 340;
  const ink = options.inkColor ?? '#0f172a';
  const bg = options.backgroundColor;

  const rects: string[] = [];
  if (bg) {
    rects.push(
      `<rect x="0" y="0" width="${modules.length}" height="${height}" fill="${bg}" rx="3" />`
    );
  }
  for (let i = 0; i < modules.length; i++) {
    if (modules[i] === 1) {
      const barHeight = guardPositions.has(i) ? height - 4 : height - 10;
      rects.push(
        `<rect x="${i}" y="2" width="1" height="${barHeight}" fill="${ink}" shape-rendering="crispEdges" />`
      );
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${modules.length} ${height}" width="100%" height="${height}" style="max-width:${maxWidth}px;display:block;" role="img" aria-label="EAN-13 Barcode">${rects.join('')}</svg>`;
}

// --- ISO/IEC 18004 QR Code Generator (Versions 1..6, Byte Mode UTF-8, ECC Level L) ---

interface QrVersionSpec {
  version: number;
  size: number;
  totalDataCodewords: number;
  ecCodewordsPerBlock: number;
  numBlocks: number;
  alignmentCenters: number[];
}

const QR_LEVEL_L_SPECS: ReadonlyArray<QrVersionSpec> = [
  { version: 1, size: 21, totalDataCodewords: 19, ecCodewordsPerBlock: 7, numBlocks: 1, alignmentCenters: [] },
  { version: 2, size: 25, totalDataCodewords: 34, ecCodewordsPerBlock: 10, numBlocks: 1, alignmentCenters: [6, 18] },
  { version: 3, size: 29, totalDataCodewords: 55, ecCodewordsPerBlock: 15, numBlocks: 1, alignmentCenters: [6, 22] },
  { version: 4, size: 33, totalDataCodewords: 80, ecCodewordsPerBlock: 20, numBlocks: 1, alignmentCenters: [6, 26] },
  { version: 5, size: 37, totalDataCodewords: 108, ecCodewordsPerBlock: 26, numBlocks: 1, alignmentCenters: [6, 30] },
  { version: 6, size: 41, totalDataCodewords: 136, ecCodewordsPerBlock: 18, numBlocks: 2, alignmentCenters: [6, 34] }
];

function gf256Mul(x: number, y: number): number {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z;
}

function reedSolomonComputeRemainder(
  data: Uint8Array,
  degree: number
): Uint8Array {
  const gen = new Uint8Array(degree);
  gen[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      gen[j] = gf256Mul(gen[j], root);
      if (j + 1 < degree) {
        gen[j] ^= gen[j + 1];
      }
    }
    root = gf256Mul(root, 0x02);
  }

  const result = new Uint8Array(degree);
  for (let i = 0; i < data.length; i++) {
    const factor = data[i] ^ result[0];
    result.copyWithin(0, 1);
    result[degree - 1] = 0;
    for (let j = 0; j < degree; j++) {
      result[j] ^= gf256Mul(gen[j], factor);
    }
  }
  return result;
}

export function encodeQrMatrix(payload: string): boolean[][] {
  const utf8 = new TextEncoder().encode(payload || 'SAFEPOCKET');
  // Byte mode overhead in Versions 1..9 is 4 bits mode (0100) + 8 bits length = 12 bits -> max payload = totalDataCodewords - 2
  const spec =
    QR_LEVEL_L_SPECS.find((s) => utf8.length <= s.totalDataCodewords - 2) ??
    QR_LEVEL_L_SPECS[QR_LEVEL_L_SPECS.length - 1];
  const bytes =
    utf8.length <= spec.totalDataCodewords - 2
      ? utf8
      : utf8.subarray(0, spec.totalDataCodewords - 2);

  // Assemble bit stream: 0100 (Byte mode) + 8-bit byte count + payload + terminator + pad bytes (0xEC, 0x11)
  const bits: number[] = [];
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) {
      bits.push((val >>> i) & 1);
    }
  };
  pushBits(0b0100, 4);
  pushBits(bytes.length, 8);
  for (const b of bytes) pushBits(b, 8);

  const capacityBits = spec.totalDataCodewords * 8;
  const termLen = Math.min(4, capacityBits - bits.length);
  pushBits(0, termLen);
  while (bits.length % 8 !== 0) bits.push(0);

  const dataCodewords = new Uint8Array(spec.totalDataCodewords);
  for (let i = 0; i < bits.length / 8; i++) {
    let byte = 0;
    for (let b = 0; b < 8; b++) {
      byte = (byte << 1) | bits[i * 8 + b];
    }
    dataCodewords[i] = byte;
  }
  let padToggle = true;
  for (let i = bits.length / 8; i < spec.totalDataCodewords; i++) {
    dataCodewords[i] = padToggle ? 0xec : 0x11;
    padToggle = !padToggle;
  }

  // Split into RS blocks & interleave
  const blockDataLen = spec.totalDataCodewords / spec.numBlocks;
  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  for (let b = 0; b < spec.numBlocks; b++) {
    const slice = dataCodewords.subarray(
      b * blockDataLen,
      (b + 1) * blockDataLen
    );
    dataBlocks.push(slice);
    ecBlocks.push(reedSolomonComputeRemainder(slice, spec.ecCodewordsPerBlock));
  }

  const finalCodewords: number[] = [];
  for (let i = 0; i < blockDataLen; i++) {
    for (let b = 0; b < spec.numBlocks; b++) {
      finalCodewords.push(dataBlocks[b][i]);
    }
  }
  for (let i = 0; i < spec.ecCodewordsPerBlock; i++) {
    for (let b = 0; b < spec.numBlocks; b++) {
      finalCodewords.push(ecBlocks[b][i]);
    }
  }

  const n = spec.size;
  const modules: boolean[][] = Array.from({ length: n }, () =>
    new Array<boolean>(n).fill(false)
  );
  const isFunction: boolean[][] = Array.from({ length: n }, () =>
    new Array<boolean>(n).fill(false)
  );

  const setFunc = (r: number, c: number, dark: boolean) => {
    if (r < 0 || r >= n || c < 0 || c >= n) return;
    modules[r][c] = dark;
    isFunction[r][c] = true;
  };

  // Finder patterns + separators (top-left, top-right, bottom-left)
  const drawFinder = (topR: number, leftC: number) => {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const dist = Math.max(Math.abs(dr - 3), Math.abs(dc - 3));
        const dark = dist !== 2 && dist !== 4;
        setFunc(topR + dr, leftC + dc, dark);
      }
    }
  };
  drawFinder(0, 0);
  drawFinder(0, n - 7);
  drawFinder(n - 7, 0);

  // Timing patterns
  for (let i = 8; i < n - 8; i++) {
    setFunc(6, i, i % 2 === 0);
    setFunc(i, 6, i % 2 === 0);
  }

  // Alignment patterns (Versions 2..6)
  const centers = spec.alignmentCenters;
  for (let i = 0; i < centers.length; i++) {
    for (let j = 0; j < centers.length; j++) {
      if (
        (i === 0 && j === 0) ||
        (i === 0 && j === centers.length - 1) ||
        (i === centers.length - 1 && j === 0)
      ) {
        continue;
      }
      const cr = centers[i];
      const cc = centers[j];
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          setFunc(cr + dr, cc + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
        }
      }
    }
  }

  // Reserve format info coordinates + dark module
  const writeFormatBits = (mask: number, target: boolean[][]) => {
    // ECC Level L = 01
    const data5 = (0b01 << 3) | mask;
    let rem = data5;
    for (let i = 0; i < 10; i++) {
      rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    }
    const bits15 = ((data5 << 10) | rem) ^ 0x5412;
    const getBit = (idx: number) => ((bits15 >>> idx) & 1) === 1;

    // Top-left copy
    for (let i = 0; i <= 5; i++) target[8][i] = getBit(14 - i);
    target[8][7] = getBit(8);
    target[8][8] = getBit(7);
    target[7][8] = getBit(6);
    for (let i = 9; i < 15; i++) target[14 - i][8] = getBit(14 - i);

    // Split copy (bottom-left & top-right)
    for (let i = 0; i < 7; i++) target[n - 1 - i][8] = getBit(14 - i);
    for (let i = 7; i < 15; i++) target[8][n - 15 + i] = getBit(14 - i);

    // Always-dark module
    target[n - 8][8] = true;
  };

  for (let i = 0; i <= 8; i++) {
    if (i !== 6) {
      isFunction[8][i] = true;
      isFunction[i][8] = true;
    }
  }
  for (let i = 0; i < 8; i++) {
    isFunction[8][n - 1 - i] = true;
    if (i < 7) isFunction[n - 1 - i][8] = true;
  }
  setFunc(n - 8, 8, true);

  // Place data & EC bits in upward/downward 2-column zig-zag
  let bitIdx = 0;
  const totalBits = finalCodewords.length * 8;
  for (let right = n - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < n; vert++) {
      const r = ((right + 1) & 2) === 0 ? n - 1 - vert : vert;
      for (let j = 0; j < 2; j++) {
        const c = right - j;
        if (!isFunction[r][c]) {
          let dark = false;
          if (bitIdx < totalBits) {
            const cw = finalCodewords[bitIdx >>> 3];
            dark = ((cw >>> (7 - (bitIdx & 7))) & 1) === 1;
            bitIdx++;
          }
          modules[r][c] = dark;
        }
      }
    }
  }

  // Evaluate masks 0..7 and select lowest run-length penalty
  const maskTest = (m: number, r: number, c: number): boolean => {
    switch (m) {
      case 0:
        return (r + c) % 2 === 0;
      case 1:
        return r % 2 === 0;
      case 2:
        return c % 3 === 0;
      case 3:
        return (r + c) % 3 === 0;
      case 4:
        return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
      case 5:
        return ((r * c) % 2) + ((r * c) % 3) === 0;
      case 6:
        return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
      default:
        return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
    }
  };

  let bestMask = 0;
  let bestPenalty = Infinity;
  let bestGrid = modules;

  for (let m = 0; m < 8; m++) {
    const candidate = modules.map((row, r) =>
      row.map((val, c) => (!isFunction[r][c] && maskTest(m, r, c) ? !val : val))
    );
    writeFormatBits(m, candidate);

    let penalty = 0;
    for (let r = 0; r < n; r++) {
      let runLen = 1;
      for (let c = 1; c < n; c++) {
        if (candidate[r][c] === candidate[r][c - 1]) {
          runLen++;
          if (runLen === 5) penalty += 3;
          else if (runLen > 5) penalty += 1;
        } else {
          runLen = 1;
        }
      }
    }
    for (let c = 0; c < n; c++) {
      let runLen = 1;
      for (let r = 1; r < n; r++) {
        if (candidate[r][c] === candidate[r - 1][c]) {
          runLen++;
          if (runLen === 5) penalty += 3;
          else if (runLen > 5) penalty += 1;
        } else {
          runLen = 1;
        }
      }
    }
    if (penalty < bestPenalty) {
      bestPenalty = penalty;
      bestMask = m;
      bestGrid = candidate;
    }
  }
  void bestMask;
  return bestGrid;
}

export function encodeQrSvg(
  payload: string,
  options: { sizePx?: number; inkColor?: string; backgroundColor?: string } = {}
): string {
  const matrix = encodeQrMatrix(payload);
  const quiet = 4;
  const total = matrix.length + quiet * 2;
  const sizePx = options.sizePx ?? 168;
  const ink = options.inkColor ?? '#0f172a';
  const bg = options.backgroundColor ?? '#ffffff';

  const paths: string[] = [];
  for (let r = 0; r < matrix.length; r++) {
    for (let c = 0; c < matrix.length; c++) {
      if (matrix[r][c]) {
        paths.push(`M${c + quiet},${r + quiet}h1v1h-1z`);
      }
    }
  }

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" width="${sizePx}" height="${sizePx}" shape-rendering="crispEdges" role="img" aria-label="QR Code"><rect width="${total}" height="${total}" fill="${bg}" rx="1.5"/><path d="${paths.join('')}" fill="${ink}"/></svg>`;
}

export function inferBestBarcodeFormat(
  rawValue: string,
  preferred?: BarcodeFormat
): BarcodeFormat {
  if (preferred === 'QR_CODE' || preferred === 'CODE_128') {
    return preferred;
  }
  if (preferred === 'EAN_13') {
    return normalizeEan13Digits(rawValue) ? 'EAN_13' : 'CODE_128';
  }
  if (/^(https?:\/\/|safepocket-recovery:\/\/)/i.test(rawValue.trim())) {
    return 'QR_CODE';
  }
  if (normalizeEan13Digits(rawValue)) {
    return 'EAN_13';
  }
  return 'CODE_128';
}

export function renderScannableBarcodeSvg(
  rawValue: string,
  preferredFormat?: BarcodeFormat,
  options: BarcodeSvgOptions & { qrSizePx?: number } = {}
): { format: BarcodeFormat; svg: string } {
  const format = inferBestBarcodeFormat(rawValue, preferredFormat);
  if (format === 'QR_CODE') {
    return {
      format,
      svg: encodeQrSvg(rawValue, {
        sizePx: options.qrSizePx ?? 136,
        inkColor: options.inkColor ?? '#0f172a',
        backgroundColor: options.backgroundColor ?? '#ffffff'
      })
    };
  }
  if (format === 'EAN_13') {
    return {
      format,
      svg: encodeEan13Svg(rawValue, options)
    };
  }
  return {
    format: 'CODE_128',
    svg: encodeCode128BSvg(rawValue, options)
  };
}
