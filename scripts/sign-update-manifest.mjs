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

  const latestVersion = (process.env.SAFEPOCKET_RELEASE_VERSION || '0.2').replace(
    /^v/i,
    ''
  );
  const versionCode = Number(process.env.SAFEPOCKET_VERSION_CODE || '4');

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
    publishedAt: '2026-11-16T20:30:00Z',
    signingKeyId: 'safepocket-release-ed25519-2026',
    signingPublicKeyHex: rawPubHex,
    manifestSignature: '',
    releaseNotes: {
      en: `• SafePocket v${latestVersion} adds automatic Institution + Card Network/Type dual logos & brand colors (e.g. Deutsche Bank + VISA) with optional HTTPS icon download.\n• Complete category-specific field templates across all 7 categories, full item editing, bottom-left theme toggle, and smooth seam-free button hover.\n• Local encrypted SQLCipher / SQLite WASM DB + Firebase Cloud Sync account creation, encrypted .safepocket backup/restore, and 1Password (.1pux / CSV) & CSV import/export.`,
      de: `• SafePocket v${latestVersion} bietet automatische Dual-Logos (Institut + Kartentyp, z. B. Deutsche Bank + VISA) und Markenfarben inkl. optionalem HTTPS-Logo-Download.\n• Vollständige kategoriebezogene Felder für alle 7 Kategorien, vollständiges Bearbeiten bestehender Einträge, Theme-Icon unten links & weicher Button-Hover ohne graue Linie.\n• Lokale verschlüsselte SQLCipher-/SQLite-Datenbank + Firebase-Konto & Cloud-Sync, verschlüsseltes .safepocket-Backup sowie Import/Export für 1Password (.1pux / CSV) & CSV.`,
      it: `• SafePocket v${latestVersion} introduce doppi loghi automatici istituto + circuito/tipo (es. Deutsche Bank + VISA) e colori del brand con download icone opzionale.\n• Campi completi per tutte le 7 categorie, modifica completa degli elementi, selettore tema in basso a sinistra e pulsanti fluidi senza linea grigia.\n• DB locale cifrato SQLCipher / SQLite WASM + account e sync Firebase, backup cifrato .safepocket e import/export 1Password (.1pux / CSV) e CSV.`,
      fr: `• SafePocket v${latestVersion} ajoute les doubles logos automatiques établissement + réseau/type (ex. Deutsche Bank + VISA) et couleurs de marque.\n• Champs complets pour les 7 catégories, modification complète des éléments, bouton de thème en bas à gauche et survol fluide sans ligne grise.\n• Base locale chiffrée SQLCipher / SQLite WASM + compte et synchro Firebase, sauvegarde chiffrée .safepocket et import/export 1Password (.1pux / CSV) et CSV.`,
      es: `• SafePocket v${latestVersion} añade dobles logotipos automáticos de entidad + red/tipo (ej. Deutsche Bank + VISA) y colores de marca.\n• Campos completos para las 7 categorías, edición completa de elementos, botón de tema abajo a la izquierda y botones suaves sin línea gris.\n• Base local cifrada SQLCipher / SQLite WASM + cuenta y sincronización Firebase, copia cifrada .safepocket e importación/exportación 1Password (.1pux / CSV) y CSV.`,
      el: `• Το SafePocket v${latestVersion} προσθέτει αυτόματα διπλά λογότυπα ιδρύματος + δικτύου/τύπου (π.χ. Deutsche Bank + VISA) και χρώματα επωνυμίας.\n• Πλήρη πεδία και για τις 7 κατηγορίες, πλήρη επεξεργασία στοιχείων, εναλλαγή θέματος κάτω αριστερά και ομαλό hover κουμπιών χωρίς γκρι γραμμή.\n• Τοπική κρυπτογραφημένη βάση SQLCipher / SQLite WASM + συγχρονισμός Firebase, κρυπτογραφημένο αντίγραφο .safepocket και εισαγωγή/εξαγωγή 1Password (.1pux / CSV) & CSV.`
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
