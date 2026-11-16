import type { Argon2idKdfConfig } from '../crypto/vaultCrypto';
import { encodeQrSvg } from '../scanner/barcodeRenderer';

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

export function buildRecoverySecretKeyUri(
  vaultId: string,
  secretKeyFormatted: string
): string {
  const cleanVault = encodeURIComponent(vaultId.trim());
  const cleanSecret = encodeURIComponent(secretKeyFormatted.trim().toUpperCase());
  return `safepocket-recovery://v1?vaultId=${cleanVault}&secretKey=${cleanSecret}`;
}

/**
 * Generates a self-contained, A4/Letter printable HTML SafePocket Emergency Recovery Kit v2
 * containing the Vault ID, Local Secret Key + offline Secret Key QR code (scannable on new devices),
 * optional non-secret Master Password memory hint, blank handwriting boxes, and Argon2id parameters.
 * Master Password itself is NEVER accepted or embedded.
 */
export function buildEmergencyKitHtml(params: {
  vaultId: string;
  createdAtIso: string;
  secretKeyFormatted: string;
  kdf: Argon2idKdfConfig;
  passwordHint?: string;
  cloudAccountEmail?: string;
}): string {
  const esc = (s: string) =>
    s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');

  const recoveryUri = buildRecoverySecretKeyUri(
    params.vaultId,
    params.secretKeyFormatted
  );
  const qrSvg = encodeQrSvg(recoveryUri, {
    sizePx: 148,
    inkColor: '#0f172a',
    backgroundColor: '#ffffff'
  });

  const hintHtml = params.passwordHint?.trim()
    ? `<div style="margin-top:8px;font-size:12.5px;color:#334155;background:#f8fafc;border:1px solid #cbd5e1;border-radius:8px;padding:8px 12px;">
         <strong>Optional Memory Hint:</strong> ${esc(params.passwordHint.trim())}
       </div>`
    : '';

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <title>SafePocket v0.1 Emergency Recovery Kit — ${esc(params.vaultId)}</title>
  <style>
    @page { size: A4 portrait; margin: 14mm; }
    body { font-family: system-ui, -apple-system, 'Segoe UI', sans-serif; color: #0f172a; max-width: 720px; margin: 24px auto; padding: 26px; border: 2px solid #0f172a; border-radius: 14px; background: #ffffff; }
    h1 { margin: 0 0 6px; font-size: 21px; letter-spacing: -0.015em; }
    p { color: #334155; font-size: 13px; line-height: 1.5; margin: 6px 0 16px; }
    .top-grid { display: grid; grid-template-columns: 1fr auto; gap: 18px; align-items: center; background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 12px; padding: 14px 16px; margin-bottom: 18px; }
    .secret-box { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 15.5px; font-weight: 700; letter-spacing: 0.05em; background: #ffffff; border: 1.5px solid #0f172a; border-radius: 9px; padding: 12px 14px; margin-top: 8px; word-break: break-all; }
    .qr-card { text-align: center; background: #ffffff; border: 1px solid #cbd5e1; border-radius: 10px; padding: 8px; }
    .qr-caption { font-size: 10px; font-family: ui-monospace, monospace; color: #475569; margin-top: 4px; }
    .section-label { font-size: 11.5px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; color: #475569; }
    .handwrite-box { height: 54px; border: 2px dashed #64748b; border-radius: 10px; margin: 8px 0 14px; }
    .slots-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 12px 0 16px; }
    .slot { border: 1px solid #cbd5e1; border-radius: 9px; padding: 10px 12px; min-height: 44px; font-size: 12px; color: #334155; }
    .checklist { font-size: 12px; color: #334155; background: #f8fafc; border-radius: 9px; padding: 10px 14px; margin-bottom: 14px; border: 1px solid #e2e8f0; }
    .meta { font-family: ui-monospace, monospace; font-size: 11px; color: #475569; border-top: 1px solid #e2e8f0; padding-top: 12px; line-height: 1.55; }
    @media print {
      body { margin: 0; border-width: 1.5px; }
      .no-print { display: none !important; }
    }
  </style>
</head>
<body>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;">
    <h1>SafePocket v0.1 — Zero-Knowledge Emergency Kit</h1>
    <button class="no-print" onclick="window.print()" style="padding:7px 14px;border-radius:8px;border:1px solid #0f172a;background:#0f172a;color:#fff;font-weight:600;cursor:pointer;">🖨 Print Sheet</button>
  </div>
  <p>Store this recovery sheet in a physical safe. SafePocket encrypts your vault locally using Two-Secret Key Derivation (Master Password + Local Secret Key). Neither SafePocket nor any cloud server ever receives these keys.</p>

  <div class="top-grid">
    <div>
      <div class="section-label">1. Local Secret Key (Scan QR or Enter on New Device)</div>
      <div class="secret-box">${esc(params.secretKeyFormatted)}</div>
      <div style="font-size:11.5px;color:#475569;margin-top:8px;">
        Required alongside your Master Password to unlock or restore encrypted <code>.safepocket</code> backups.
      </div>
    </div>
    <div class="qr-card">
      ${qrSvg}
      <div class="qr-caption">SECRET KEY QR ONLY</div>
    </div>
  </div>

  <div class="section-label">2. Master Password (Write by hand — intentionally never printed)</div>
  <div class="handwrite-box"></div>
  ${hintHtml}

  <div class="slots-grid">
    <div class="slot">
      <div class="section-label" style="margin-bottom:4px;">Offline .safepocket Backup Location</div>
      <div>${params.cloudAccountEmail ? '' : 'e.g. Encrypted USB Drive / Home Safe'}</div>
    </div>
    <div class="slot">
      <div class="section-label" style="margin-bottom:4px;">Optional EU Cloud Sync Account</div>
      <div style="font-family:ui-monospace,monospace;">${esc(params.cloudAccountEmail || 'Local Storage Only (Default)')}</div>
    </div>
  </div>

  <div class="checklist">
    <strong>Offline Recovery Steps:</strong>
    1) Open SafePocket v0.1+ on ChromeOS, Android, or macOS •
    2) Scan the Secret Key QR above (or type the 29-character key) •
    3) Enter your handwritten Master Password to restore your <code>.safepocket</code> archive or EU Cloud replica.
  </div>

  <div class="meta">
    Vault ID: ${esc(params.vaultId)} • Created: ${esc(params.createdAtIso)} • Format: SafePocket v0.1 (Build 1)<br/>
    KDF: Argon2id (${params.kdf.memorySizeKiB} KiB, t=${params.kdf.iterations}, p=${params.kdf.parallelism}, salt=${esc(params.kdf.saltHex)}) → HKDF-SHA256 → AES-256-GCM
  </div>
</body>
</html>`;
}
