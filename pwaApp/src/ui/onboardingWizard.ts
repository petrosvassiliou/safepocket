import type { Argon2idKdfConfig } from '../crypto/vaultCrypto';

export interface PasswordEntropyAssessment {
  bits: number;
  rating: 'Weak' | 'Moderate' | 'Strong' | 'Vault-Grade';
}

/**
 * Pure client-side Master Password entropy estimator (zero network calls).
 */
export function estimateMasterPasswordEntropy(
  password: string
): PasswordEntropyAssessment {
  if (!password) return { bits: 0, rating: 'Weak' };
  let pool = 0;
  if (/[a-z]/.test(password)) pool += 26;
  if (/[A-Z]/.test(password)) pool += 26;
  if (/[0-9]/.test(password)) pool += 10;
  if (/[^a-zA-Z0-9\s]/.test(password)) pool += 33;
  if (/\s/.test(password)) pool += 1;
  if (/[^\x00-\x7F]/.test(password)) pool += 64;

  const bits = Math.round(password.length * Math.log2(Math.max(pool, 2)));
  if (bits >= 95) return { bits, rating: 'Vault-Grade' };
  if (bits >= 72) return { bits, rating: 'Strong' };
  if (bits >= 48) return { bits, rating: 'Moderate' };
  return { bits, rating: 'Weak' };
}

/**
 * Generates a self-contained, printable HTML SafePocket Emergency Kit containing
 * the Vault ID, Local Secret Key, Argon2id parameters, and a blank box to write the Master Password by hand.
 */
export function buildEmergencyKitHtml(params: {
  vaultId: string;
  createdAtIso: string;
  secretKeyFormatted: string;
  kdf: Argon2idKdfConfig;
}): string {
  const esc = (s: string) =>
    s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>SafePocket Emergency Kit — ${esc(params.vaultId)}</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; color: #0f172a; max-width: 680px; margin: 36px auto; padding: 28px; border: 2px solid #0f172a; border-radius: 14px; }
    h1 { margin: 0 0 8px; font-size: 22px; }
    p { color: #334155; font-size: 13.5px; line-height: 1.5; }
    .secret-box { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 16px; font-weight: 700; letter-spacing: 0.06em; background: #f1f5f9; border: 1px solid #cbd5e1; border-radius: 10px; padding: 14px 16px; margin: 8px 0 18px; }
    .handwrite-box { height: 58px; border: 2px dashed #64748b; border-radius: 10px; margin: 8px 0 18px; }
    .meta { font-family: ui-monospace, monospace; font-size: 11.5px; color: #475569; border-top: 1px solid #e2e8f0; padding-top: 14px; }
  </style>
</head>
<body>
  <h1>SafePocket — Zero-Knowledge Emergency Kit</h1>
  <p>Print this sheet or store it on offline encrypted media. SafePocket uses Local Encrypted Storage by default and Two-Secret Key Derivation (Master Password + Secret Key). Neither SafePocket nor any cloud service ever receives these keys.</p>
  <div style="font-size:12px;font-weight:700;text-transform:uppercase;color:#475569;">1. Local Secret Key (Required to unlock on new devices)</div>
  <div class="secret-box">${esc(params.secretKeyFormatted)}</div>
  <div style="font-size:12px;font-weight:700;text-transform:uppercase;color:#475569;">2. Master Password (Write by hand — intentionally never printed)</div>
  <div class="handwrite-box"></div>
  <div class="meta">
    Vault ID: ${esc(params.vaultId)} • Created: ${esc(params.createdAtIso)}<br/>
    KDF: Argon2id (${params.kdf.memorySizeKiB} KiB, t=${params.kdf.iterations}, p=${params.kdf.parallelism}, salt=${esc(params.kdf.saltHex)}) → HKDF-SHA256 → AES-256-GCM
  </div>
</body>
</html>`;
}
