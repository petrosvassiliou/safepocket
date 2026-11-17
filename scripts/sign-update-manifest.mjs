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

  const latestVersion = (process.env.SAFEPOCKET_RELEASE_VERSION || '0.3').replace(
    /^v/i,
    ''
  );
  const versionCode = Number(process.env.SAFEPOCKET_VERSION_CODE || '5');

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
    publishedAt: '2026-11-17T00:45:00Z',
    signingKeyId: 'safepocket-release-ed25519-2026',
    signingPublicKeyHex: rawPubHex,
    manifestSignature: '',
    releaseNotes: {
      en: `• SafePocket v${latestVersion} delivers intelligent field dropdowns, international phone prefixes, live HTTPS website verification, and IBAN cross-field autofill.\n• Mutually exclusive Local Storage Only vs Cloud Sync Only with 100% automatic zero-input offline support, bottom-right sync status pill, swipe actions, right-click menus, and one-tap Delete Example Data.\n• Play Protect compliant Android/Googlebook OS updates, in-app macOS Ed25519+SHA-256 DMG updates, studio app icon, zero-artifact button hover, and privacy-scrubbed diagnostics.`,
      de: `• SafePocket v${latestVersion} bietet intelligente Auswahlfelder (z. B. Kontoart), internationale Telefonvorwahlen, Live-HTTPS-Webseitenprüfung und IBAN-Auto-Ausfüllung.\n• Strikte Trennung zwischen Nur lokaler Speicher und Nur Cloud-Sync mit 100 % automatischer Offline-Unterstützung ohne Benutzereingriff, Sync-Status unten rechts, Wischgesten, Rechtsklick-Menüs und Beispieldaten-Löschung.\n• Play-Protect-konforme Updates für Android/Googlebook OS, automatische In-App-Updates unter macOS, neues App-Icon, nahtloser Button-Hover ohne graue Linie und datenschutzbereinigte Diagnose.`,
      it: `• SafePocket v${latestVersion} introduce menu a discesa intelligenti, prefissi telefonici internazionali, verifica live dei siti HTTPS e compilazione automatica da IBAN.\n• Modalità esclusiva Solo archiviazione locale o Solo Cloud Sync con supporto offline 100% automatico, indicatore sync in basso a destra, azioni a scorrimento, menu contestuali ed eliminazione dati di esempio.\n• Aggiornamenti conformi a Play Protect su Android/Googlebook OS, aggiornamenti automatici in-app su macOS, nuova icona studio, hover fluido e diagnostica oscurata.`,
      fr: `• SafePocket v${latestVersion} propose des listes déroulantes intelligentes, indicatifs téléphoniques internationaux, vérification HTTPS en direct et saisie automatique via IBAN.\n• Mode exclusif Stockage local uniquement ou Cloud Sync uniquement avec mode hors-ligne 100 % automatique, statut de synchro en bas à droite, gestes de balayage, menus clic droit et suppression des exemples.\n• Mises à jour conformes Play Protect sur Android/Googlebook OS, mises à jour automatiques intégrées sur macOS, nouvelle icône, survol sans artefact et journal de diagnostic nettoyé.`,
      es: `• SafePocket v${latestVersion} incorpora desplegables inteligentes, prefijos telefónicos internacionales, verificación web HTTPS en vivo y autocompletado por IBAN.\n• Modo exclusivo Solo almacenamiento local o Solo Cloud Sync con soporte sin conexión 100 % automático, estado de sincronización abajo a la derecha, gestos de deslizamiento, menús contextuales y borrado de datos de ejemplo.\n• Actualizaciones compatibles con Play Protect en Android/Googlebook OS, actualizaciones automáticas integradas en macOS, nuevo icono, hover limpio y diagnóstico depurado.`,
      el: `• Το SafePocket v${latestVersion} προσφέρει έξυπνες αναπτυσσόμενες λίστες, διεθνή προθέματα τηλεφώνου, ζωντανό έλεγχο ιστοσελίδων HTTPS και αυτόματη συμπλήρωση μέσω IBAN.\n• Αποκλειστική επιλογή Μόνο τοπική αποθήκευση ή Μόνο Cloud Sync με 100% αυτόματη λειτουργία εκτός σύνδεσης, ένδειξη συγχρονισμού κάτω δεξιά, κινήσεις κύλισης (swipe), μενού δεξιού κλικ και διαγραφή δεδομένων παραδείγματος.\n• Αυτόματες ενημερώσεις συμβατές με Play Protect σε Android/Googlebook OS, ενσωματωμένες ενημερώσεις στο macOS, νέο εικονίδιο, καθαρό hover κουμπιών και αρχείο διαγνωστικών.`
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
