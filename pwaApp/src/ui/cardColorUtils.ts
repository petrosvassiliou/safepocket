export interface CustomCardComputedStyle {
  normalizedHex: string;
  relativeLuminance: number;
  isLightSurface: boolean;
  gradientCss: string;
  inkColor: string;
  borderColor: string;
  pillBackground: string;
  pillBorder: string;
  inlineStyleAttr: string;
}

/**
 * Validates and normalizes `#RGB` or `#RRGGBB` input into canonical uppercase `#RRGGBB`.
 */
export function normalizeHexColor(
  raw: string | undefined,
  fallback = '#1E5B53'
): string {
  if (!raw) return fallback;
  const cleaned = raw.trim();
  if (/^#[0-9a-fA-F]{6}$/.test(cleaned)) {
    return cleaned.toUpperCase();
  }
  if (/^#[0-9a-fA-F]{3}$/.test(cleaned)) {
    const r = cleaned[1];
    const g = cleaned[2];
    const b = cleaned[3];
    return `#${r}${r}${g}${g}${b}${b}`.toUpperCase();
  }
  return fallback;
}

function parseRgbChannels(hex6: string): [number, number, number] {
  const norm = normalizeHexColor(hex6);
  return [
    parseInt(norm.slice(1, 3), 16),
    parseInt(norm.slice(3, 5), 16),
    parseInt(norm.slice(5, 7), 16)
  ];
}

function srgbChannelToLinear(channel255: number): number {
  const c = channel255 / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/**
 * Computes WCAG 2.1 relative luminance (`0.0` black .. `1.0` white).
 */
export function computeRelativeLuminance(hexColor: string): number {
  const [r, g, b] = parseRgbChannels(hexColor);
  return (
    0.2126 * srgbChannelToLinear(r) +
    0.7152 * srgbChannelToLinear(g) +
    0.0722 * srgbChannelToLinear(b)
  );
}

function shiftRgbHex(
  [r, g, b]: [number, number, number],
  factor: number,
  lift = 0
): string {
  const clamp = (v: number) =>
    Math.max(0, Math.min(255, Math.round(v * factor + lift)))
      .toString(16)
      .padStart(2, '0');
  return `#${clamp(r)}${clamp(g)}${clamp(b)}`.toUpperCase();
}

/**
 * Builds an anodized two-stop card gradient and WCAG-compliant foreground/pill tokens
 * from any user-picked `#RRGGBB` color.
 */
export function buildCustomCardStyle(
  rawHex: string | undefined
): CustomCardComputedStyle {
  const normalizedHex = normalizeHexColor(rawHex);
  const rgb = parseRgbChannels(normalizedHex);
  const relativeLuminance = computeRelativeLuminance(normalizedHex);
  const isLightSurface = relativeLuminance > 0.4;

  const stopStart = isLightSurface
    ? shiftRgbHex(rgb, 1.03, 6)
    : shiftRgbHex(rgb, 1.12, 10);
  const stopEnd = isLightSurface
    ? shiftRgbHex(rgb, 0.86, 0)
    : shiftRgbHex(rgb, 0.68, 0);

  const gradientCss = `linear-gradient(135deg, ${stopStart}, ${stopEnd})`;
  const inkColor = isLightSurface ? '#0f172a' : '#f8fafc';
  const borderColor = isLightSurface
    ? 'rgba(15, 23, 42, 0.18)'
    : 'rgba(255, 255, 255, 0.16)';
  const pillBackground = isLightSurface
    ? 'rgba(15, 23, 42, 0.08)'
    : 'rgba(255, 255, 255, 0.14)';
  const pillBorder = isLightSurface
    ? 'rgba(15, 23, 42, 0.16)'
    : 'rgba(255, 255, 255, 0.22)';

  const inlineStyleAttr = `background:${gradientCss};color:${inkColor};border-color:${borderColor};--sp-card-pill-bg:${pillBackground};--sp-card-pill-border:${pillBorder};--sp-card-ink:${inkColor};`;

  return {
    normalizedHex,
    relativeLuminance,
    isLightSurface,
    gradientCss,
    inkColor,
    borderColor,
    pillBackground,
    pillBorder,
    inlineStyleAttr
  };
}
