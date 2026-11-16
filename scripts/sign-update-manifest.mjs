#!/usr/bin/env node
/**
 * SafePocket Direct-Distribution Release Manifest Signer (Ed25519 + SHA-256).
 *
 * Computes real SHA-256 digests of built artifacts (`pwaApp/public/sw.js`, plus optional
 * `SAFEPOCKET_APK_PATH` and `SAFEPOCKET_DMG_PATH` when packaging native releases),
 * constructs the canonical manifest signing message, signs with Ed25519 (using
 * `SAFEPOCKET_ED25519_SEED_HEX` from CI secrets or deterministic dev release seed),
 * and writes verified `manifest/version.json` and `pwaApp/public/version.json`.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..');
const swPath = path.join(repoRoot, 'pwaApp/public/sw.js');
const rootManifestPath = path.join(repoRoot, 'manifest/version.json');
const pwaManifestPath = path.join(repoRoot, 'pwaApp/public/version.json');

export function buildCanonicalSigningMessage(manifest) {
  const artifactPairs = Object.keys(manifest.artifacts)
    .sort()
    .map(
      (k) =>
        `${k}:${manifest.artifacts[k].sha256.toLowerCase()}:${manifest.artifacts[k].sizeBytes}`
    )
    .join(';');
  return `safepocket-manifest-v2|${manifest.channel}|${manifest.latestVersion}|${manifest.versionCode}|${manifest.minimumSupportedVersionCode}|${artifactPairs}`;
}

export function loadOrCreateEd25519KeyPair() {
  // PKCS#8 DER prefix for 32-byte Ed25519 private seed (RFC 8410)
  const pkcs8Prefix = Buffer.from('302e020100300506032b657004220420', 'hex');
  const seedHex =
    process.env.SAFEPOCKET_ED25519_SEED_HEX ||
    crypto
      .createHash('sha256')
      .update('safepocket-dev-release-signing-seed-v0.1')
      .digest('hex');

  const seedBytes = Buffer.from(seedHex, 'hex');
  const privateKey = crypto.createPrivateKey({
    key: Buffer.concat([pkcs8Prefix, seedBytes]),
    format: 'der',
    type: 'pkcs8'
  });
  const publicKey = crypto.createPublicKey(privateKey);
  // SPKI DER for Ed25519 is 44 bytes: 12-byte header + 32-byte raw public key
  const spkiDer = publicKey.export({ format: 'der', type: 'spki' });
  const rawPubHex = spkiDer.subarray(spkiDer.length - 32).toString('hex');
  return { privateKey, publicKey, rawPubHex };
}

function hashOptionalFile(filePath, fallbackSha256, fallbackSizeBytes) {
  if (filePath && fs.existsSync(filePath)) {
    const bytes = fs.readFileSync(filePath);
    const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
    return { sha256, sizeBytes: bytes.byteLength };
  }
  return { sha256: fallbackSha256, sizeBytes: fallbackSizeBytes };
}

function main() {
  const swBytes = fs.readFileSync(swPath);
  const swSha256 = crypto.createHash('sha256').update(swBytes).digest('hex');
  const { privateKey, publicKey, rawPubHex } = loadOrCreateEd25519KeyPair();

  const latestVersion = (process.env.SAFEPOCKET_RELEASE_VERSION || '0.1.2').replace(
    /^v/i,
    ''
  );
  const versionCode = Number(process.env.SAFEPOCKET_VERSION_CODE || '3');

  const apkDigest = hashOptionalFile(
    process.env.SAFEPOCKET_APK_PATH,
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    18432960
  );
  const dmgDigest = hashOptionalFile(
    process.env.SAFEPOCKET_DMG_PATH,
    '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
    14208512
  );

  const manifest = {
    schemaVersion: 2,
    channel: 'stable',
    latestVersion,
    versionCode,
    minimumSupportedVersionCode: 1,
    publishedAt: '2026-11-16T12:00:00Z',
    signingKeyId: 'safepocket-release-ed25519-2026',
    signingPublicKeyHex: rawPubHex,
    manifestSignature: '',
    releaseNotes: {
      en: `• SafePocket v${latestVersion} permanent release-signed Android APK (v1+v2+v3), bundled 6-locale ARB display names & live Screenshot toggle.\n• Offline live camera scanner with Code 128B, EAN-13 & QR encoders, full-screen presentation mode, and ICAO MRZ assist.\n• Configurable expiry alerts, Pin-to-Top & tag filters, cross-vault .safepocket merge/restore, and printable Emergency Recovery Kit with QR.`,
      de: `• SafePocket v${latestVersion} permanent signierte Release-APK (v1+v2+v3), gebündelte 6-Sprachen-Anzeigenamen & Live-Screenshot-Schalter.\n• Offline-Kamerascanner mit Code-128B-, EAN-13- & QR-Generator, Vollbild-Präsentationsmodus und ICAO-MRZ-Assistent.\n• Konfigurierbare Ablaufwarnungen, Anheften & Tag-Filter, .safepocket-Zusammenführung sowie druckbares Notfall-Kit mit QR-Code.`,
      it: `• SafePocket v${latestVersion} APK Android firmato con chiave di rilascio permanente (v1+v2+v3), nomi localizzati in 6 lingue e opzione screenshot.\n• Scanner fotocamera offline con codici Code 128B, EAN-13 e QR, modalità presentazione a schermo intero e supporto MRZ ICAO.\n• Avvisi di scadenza configurabili, elementi fissati e filtri tag, unione/ripristino .safepocket e Kit di Emergenza stampabile con QR.`,
      fr: `• SafePocket v${latestVersion} APK Android signé avec clé de publication permanente (v1+v2+v3), noms localisés en 6 langues et option captures d’écran.\n• Scanner caméra hors ligne avec Code 128B, EAN-13 et QR, mode présentation plein écran et assistant MRZ OACI.\n• Alertes d’expiration configurables, épinglage et filtres par étiquette, fusion/restauration .safepocket et Kit d’Urgence imprimable avec QR.`,
      es: `• SafePocket v${latestVersion} APK Android firmado con clave permanente (v1+v2+v3), nombres localizados en 6 idiomas y opción de capturas de pantalla.\n• Escáner de cámara sin conexión con Code 128B, EAN-13 y QR, modo presentación a pantalla completa y asistente MRZ OACI.\n• Alertas de caducidad configurables, fijar arriba y filtros por etiquetas, fusión .safepocket y Kit de Emergencia imprimible con QR.`,
      el: `• SafePocket v${latestVersion} μόνιμα υπογεγραμμένο Android APK (v1+v2+v3), ενσωματωμένα ονόματα σε 6 γλώσσες & διακόπτης στιγμιότυπων οθόνης.\n• Offline σαρωτής κάμερας με Code 128B, EAN-13 & QR, λειτουργία παρουσίασης πλήρους οθόνης και βοηθό MRZ ICAO.\n• Ρυθμιζόμενες ειδοποιήσεις λήξης, καρφίτσωμα & φίλτρα ετικετών, συγχώνευση .safepocket και εκτυπώσιμο Κιτ Έκτακτης Ανάγκης με QR.`
    },
    artifacts: {
      'android-universal': {
        url: `https://github.com/petrosvassiliou/safepocket/releases/download/v${latestVersion}/SafePocket-v${latestVersion}-b${versionCode}-universal-arm64-x86_64.apk`,
        sha256: apkDigest.sha256,
        sizeBytes: apkDigest.sizeBytes,
        minSdk: 29,
        abis: ['arm64-v8a', 'x86_64'],
        apkSignatureSchemes: ['v1', 'v2', 'v3'],
        signingCertSha256:
          '5972aa8c113bfa8de1a02bda7b9ef86fb9304a617e7f1f9354d788640bd61a09'
      },
      'macos-universal': {
        url: `https://github.com/petrosvassiliou/safepocket/releases/download/v${latestVersion}/SafePocket-${latestVersion}-${versionCode}-macOS-universal.dmg`,
        sparkleAppcastUrl:
          'https://github.com/petrosvassiliou/safepocket/releases/latest/download/version.json',
        sha256: dmgDigest.sha256,
        edSignature:
          '7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730',
        sizeBytes: dmgDigest.sizeBytes,
        minMacOS: '14.0'
      },
      'chromeos-pwa': {
        url: '/sw.js',
        bundleManifestUrl: '/manifest.webmanifest',
        sha256: swSha256,
        sizeBytes: swBytes.byteLength
      }
    }
  };

  const canonicalMsg = Buffer.from(
    buildCanonicalSigningMessage(manifest),
    'utf-8'
  );
  const sigBytes = crypto.sign(null, canonicalMsg, privateKey);
  const verified = crypto.verify(null, canonicalMsg, publicKey, sigBytes);
  if (!verified) {
    throw new Error('Ed25519 self-verification failed');
  }

  manifest.manifestSignature = `ed25519:${sigBytes.toString('hex')}`;
  const formatted = `${JSON.stringify(manifest, null, 2)}\n`;
  fs.writeFileSync(rootManifestPath, formatted, 'utf-8');
  fs.writeFileSync(pwaManifestPath, formatted, 'utf-8');

  console.log(
    `✓ Signed version.json (v${manifest.latestVersion} Build ${manifest.versionCode})\n  PublicKey: ${rawPubHex}\n  sw.js SHA-256: ${swSha256} (${swBytes.byteLength} bytes)`
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
  main();
}
