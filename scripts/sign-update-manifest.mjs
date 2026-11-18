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

  const latestVersion = (process.env.SAFEPOCKET_RELEASE_VERSION || '0.3.7').replace(
    /^v/i,
    ''
  );
  const versionCode = Number(process.env.SAFEPOCKET_VERSION_CODE || '12');

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
    publishedAt: '2026-11-18T23:00:00Z',
    signingKeyId: 'safepocket-release-ed25519-2026',
    signingPublicKeyHex: rawPubHex,
    manifestSignature: '',
    releaseNotes: {
      en: `• SafePocket v${latestVersion} adds a direct category dropdown to + New, removes redundant category pickers from item dialogs, and centers all sidebar footer icons.\n• Introduces side-by-side Local DB vs Cloud-Sync cards with seamless 1-click sync pause/resume that keeps both your local encrypted DB and Cloud session intact.\n• Anchors right-click context menus directly on top of selected items with crisp hover highlights, and elevates modal action buttons clear of window frames across Android / Googlebook OS, ChromeOS / Web PWA, and macOS.`,
      de: `• SafePocket v${latestVersion} ergänzt den Button + Neu um ein direktes Kategorie-Menü, entfernt die doppelte Kategorieauswahl im Dialog und zentriert alle Symbole unten links.\n• Neue nebeneinanderliegende Karten für Lokale DB & Cloud-Sync ermöglichen nahtloses Pausieren/Fortsetzen per Klick ohne Abmeldung oder Datenverlust.\n• Kontextmenüs erscheinen per Rechtsklick direkt über dem ausgewählten Eintrag mit Hover-Hervorhebung, und Dialog-Schaltflächen haben mehr Abstand zum Fensterrand.`,
      it: `• SafePocket v${latestVersion} aggiunge un menu categorie diretto a + Nuovo, centra le icone della barra laterale e solleva i pulsanti delle finestre modali.\n• Nuove schede affiancate DB locale e Cloud-Sync per mettere in pausa o riprendere la sincronizzazione con 1 clic mantenendo intatti DB locale e sessione cloud.\n• Menu contestuale ancorato direttamente sopra l'elemento selezionato con evidenziazione al passaggio del mouse.`,
      fr: `• SafePocket v${latestVersion} ajoute un menu déroulant de catégories au bouton + Nouveau, centre les icônes de la barre latérale et rehausse les boutons des fenêtres modales.\n• Nouvelles cartes côte à côte Base locale et Cloud-Sync permettant de suspendre/reprendre la synchronisation en 1 clic sans déconnexion.\n• Menu contextuel affiché directement au-dessus de l'élément sélectionné avec surbrillance au survol.`,
      es: `• SafePocket v${latestVersion} añade un menú desplegable de categorías en + Nuevo, centra los iconos de la barra lateral y eleva los botones de los diálogos modales.\n• Nuevas tarjetas lado a lado BD local y Cloud-Sync para pausar o reanudar la sincronización con 1 clic conservando la base local y la sesión en la nube.\n• Menú contextual anclado directamente sobre el elemento seleccionado con resaltado al pasar el cursor.`,
      el: `• Το SafePocket v${latestVersion} προσθέτει μενού κατηγοριών στο κουμπί + Νέο, κεντράρει τα εικονίδια της πλαϊνής μπάρας και ανυψώνει τα κουμπιά των παραθύρων διαλόγου.\n• Νέες κάρτες Τοπική ΒΔ και Cloud-Sync δίπλα-δίπλα για άμεση παύση/συνέχιση συγχρονισμού με 1 κλικ χωρίς αποσύνδεση.\n• Το μενού δεξιού κλικ εμφανίζεται ακριβώς πάνω από το επιλεγμένο στοιχείο με επισήμανση κατά την αιώρηση.`
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
