import { describe, expect, it, vi } from 'vitest';
import nodeCrypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {
  formatUnencryptedVaultExport,
  generateFormattedSecretKey,
  inspectSafePocketContainerHeader,
  mergeVaultSnapshots,
  ZeroKnowledgeVaultCrypto
} from '../crypto/vaultCrypto';
import {
  buildSampleSvgScanBytes,
  createInitialSampleVaultItems
} from '../data/sampleVault';
import {
  OpfsEncryptedAttachmentStore,
  VaultHeaderStore
} from '../db/opfsAttachmentStore';
import { SqliteWasmVaultDb } from '../db/sqliteWasmVaultDb';
import {
  DEFAULT_PROVISIONED_FIREBASE_CONFIG,
  FirebaseVaultService
} from '../firebase/firebaseVaultService';
import { SafePocketI18n, SUPPORTED_LOCALES } from '../i18n/l10n';
import {
  computeEan13CheckDigit,
  decodeCode128BFromModules,
  encodeCode128BModules,
  encodeQrSvg,
  renderScannableBarcodeSvg
} from '../scanner/barcodeRenderer';
import {
  applyCrispDocumentFilterInPlace,
  decode1DBarcodeFromImageData,
  parseRecoverySecretKeyPayload
} from '../scanner/cameraScanner';
import {
  buildSampleTd3PassportMrz,
  computeIcaoCheckDigit,
  parseOfflineIcaoMrz,
  validateIsoIban,
  validatePaymentCardPan
} from '../scanner/mrzAndCardValidator';
import {
  computeExpiryStatus,
  extractVaultTags,
  parseExpiryDateUtc,
  searchVaultItems,
  sortVaultItems,
  summarizeVaultExpiries
} from '../search/searchEngine';
import { IdleAutoLockMonitor } from '../security/autoLockMonitor';
import {
  assertStrictZeroKnowledgeBlobUpload,
  assertStrictZeroKnowledgeEnvelope,
  downloadEncryptedBlobFromFirebaseStorageRest,
  pushRowsToFirestoreRest,
  pushRowsToGoogleDriveAppDataRest,
  uploadEncryptedBlobToFirebaseStorageRest
} from '../sync/liveCloudConnectors';
import {
  EncryptedLocalVaultDb,
  FirebaseZeroKnowledgeAdapter,
  GoogleDriveAppDataAdapter,
  HybridClock,
  reconcileEncryptedRows,
  VaultSyncCoordinator
} from '../sync/syncAdapters';
import {
  APP_BUILD_INFO,
  CARD_THEME_PALETTES,
  type RemoteUpdateManifest,
  VAULT_CATEGORIES
} from '../types';
import {
  nextThemeModeInCycle,
  resolveEffectiveTheme
} from '../ui/appShell';
import {
  buildCustomCardStyle,
  normalizeHexColor
} from '../ui/cardColorUtils';
import {
  buildEmergencyKitHtml,
  buildRecoverySecretKeyUri,
  estimateMasterPasswordEntropy
} from '../ui/onboardingWizard';
import {
  buildCanonicalManifestMessage,
  PwaUpdateController,
  verifyManifestEd25519Signature
} from '../update/pwaUpdater';

describe('SafePocket v0.1 Cross-Platform Core & Milestones #1–#8 Verification Suite', () => {
  it('1. Localization (i18n/l10n): translates all 6 languages & applies regional Identity Tax ID templates dynamically', () => {
    const i18n = new SafePocketI18n('en-US');
    expect(SUPPORTED_LOCALES.map((l) => l.code)).toEqual([
      'en',
      'de',
      'it',
      'fr',
      'es',
      'el'
    ]);

    const expectedRegionalTaxLabels: Record<string, string> = {
      en: 'Tax ID (TIN / SSN)',
      de: 'Steuer-ID (IdNr.)',
      it: 'Codice Fiscale',
      fr: 'Numéro fiscal / NIR (SPI)',
      es: 'NIF / NIE',
      el: 'ΑΦΜ (AFM)'
    };

    for (const [localeCode, expectedLabel] of Object.entries(
      expectedRegionalTaxLabels
    )) {
      i18n.setLocaleOverride(localeCode as any);
      expect(
        i18n.resolveFieldLabel('field_tax_id', false, 'IDENTITY')
      ).toBe(expectedLabel);
      expect(i18n.plural('items_count', 1)).toContain('1');
      expect(i18n.plural('items_count', 5)).toContain('5');
      expect(i18n.t('onboarding_welcome_title').length).toBeGreaterThan(5);
    }
  });

  it('2. Zero-Knowledge Encryption: Argon2id + Secret Key -> AES-256-GCM item & custom field encryption, AAD tamper rejection & .safepocket restore', async () => {
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 4096,
      iterations: 2,
      parallelism: 1
    });
    const secretKey = generateFormattedSecretKey();
    expect(secretKey).toMatch(/^SPK-[A-Z2-9]{5}(-[A-Z2-9]{5}){4}$/);

    await cryptoEngine.initializeOrUnlock('S3cure-Master-Pass!', secretKey);
    const items = createInitialSampleVaultItems();
    items[0].fields.push({
      id: 'cf-concierge-code',
      labelKey: 'Lounge Access PIN',
      isCustomLabel: true,
      value: '9918-VIP',
      valueType: 'SENSITIVE_SECRET',
      isSensitive: true
    });

    const hlc = new HybridClock('test-node').nowPacked();
    const encryptedRow = await cryptoEngine.encryptVaultItem(items[0], hlc, {
      'test-node': 1
    });

    const panUtf8Hex = Buffer.from('5355 8910 4421 7804', 'utf-8').toString('hex');
    const customUtf8Hex = Buffer.from('9918-VIP', 'utf-8').toString('hex');
    expect(encryptedRow.ciphertextHex).not.toContain('N26');
    expect(encryptedRow.ciphertextHex).not.toContain(panUtf8Hex);
    expect(encryptedRow.ciphertextHex).not.toContain(customUtf8Hex);

    const decrypted = await cryptoEngine.decryptVaultRow(encryptedRow);
    expect(decrypted.title).toBe(items[0].title);
    expect(
      decrypted.fields.find((f) => f.id === 'cf-concierge-code')?.value
    ).toBe('9918-VIP');

    // AAD tampering must fail GCM authentication
    await expect(
      cryptoEngine.decryptVaultRow({
        ...encryptedRow,
        aadContext: 'safepocket:v1:IDENTITY:tampered-id'
      })
    ).rejects.toThrow();

    // .safepocket encrypted backup export & restore
    const containerJson = await cryptoEngine.exportSafePocketContainer(
      items,
      'S3cure-Master-Pass!',
      secretKey
    );
    const restored = await cryptoEngine.restoreSafePocketContainer(
      containerJson,
      'S3cure-Master-Pass!',
      secretKey
    );
    expect(restored).toHaveLength(items.length);
    expect(restored[1].title).toContain('Ελληνικό Διαβατήριο');
  });

  it('3. Encrypted Binary Document Scans & OPFS Store: lossless front/back AES-256-GCM round-trip & SHA-256 tamper rejection', async () => {
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock('scan-pw', generateFormattedSecretKey());
    const opfs = new OpfsEncryptedAttachmentStore();

    const originalSvgBytes = buildSampleSvgScanBytes({
      title: 'Ελληνικό Διαβατήριο',
      docNumber: 'ΑΤ8492017',
      authority: 'Αρχηγείο Ελληνικής Αστυνομίας',
      holder: 'CLARA SOFIA MÜLLER',
      side: 'front'
    });

    const enc = await cryptoEngine.encryptBinaryAttachment(
      'att-passport-front-v2',
      'passport_front.svg',
      'image/svg+xml',
      originalSvgBytes,
      'front'
    );
    expect(enc.side).toBe('front');
    await opfs.writeEncryptedBlob(enc.id, enc.encryptedBytes);

    const storedCipherBytes = await opfs.readEncryptedBlob(enc.id);
    expect(storedCipherBytes).not.toBeNull();

    const decryptedBytes = await cryptoEngine.decryptBinaryAttachment({
      attachmentId: enc.id,
      ivHex: enc.ivHex,
      expectedSha256Hex: enc.sha256Hex,
      encryptedBytes: storedCipherBytes!
    });
    expect(Array.from(decryptedBytes)).toEqual(Array.from(originalSvgBytes));

    // Mutate 1 byte in encrypted blob -> constant-time SHA-256 check must reject before decrypt
    const corrupted = storedCipherBytes!.slice();
    corrupted[0] ^= 0xff;
    await expect(
      cryptoEngine.decryptBinaryAttachment({
        attachmentId: enc.id,
        ivHex: enc.ivHex,
        expectedSha256Hex: enc.sha256Hex,
        encryptedBytes: corrupted
      })
    ).rejects.toThrow(/SHA-256 integrity check failed/);
  });

  it('4. CRDT Tombstone Deletion & Local Encrypted DB: filters deleted items from UI while converging tombstones across peers', async () => {
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock('crdt-pw', generateFormattedSecretKey());
    const localDb = new EncryptedLocalVaultDb();
    const item = createInitialSampleVaultItems()[0];

    const v1Row = await cryptoEngine.encryptVaultItem(
      item,
      '001763200000100-0000-nodeA',
      { nodeA: 1 }
    );
    await localDb.saveEncryptedRow(v1Row);
    expect(await localDb.loadAllEncryptedRows()).toHaveLength(1);

    // Delete item with causal CRDT tombstone (nodeA: 2)
    const tombstone = await cryptoEngine.createTombstoneRow(
      item.id,
      item.category,
      '001763200000500-0000-nodeA',
      { nodeA: 2 }
    );
    expect(tombstone.isTombstone).toBe(true);
    await localDb.saveEncryptedRow(tombstone);

    // Active UI query excludes tombstones; delta sync query retains tombstones
    expect(await localDb.loadAllEncryptedRows()).toHaveLength(0);
    const allIncludingTombstones = await localDb.loadAllRowsIncludingTombstones();
    expect(allIncludingTombstones).toHaveLength(1);
    expect(allIncludingTombstones[0].isTombstone).toBe(true);

    // Reconciling stale v1Row with causal tombstone must converge to tombstone
    const converged = reconcileEncryptedRows(v1Row, tombstone);
    expect(converged.isTombstone).toBe(true);
    expect(converged.versionVector).toEqual({ nodeA: 2 });
  });

  it('5. Instant Multilingual Search (< 50ms) & Expiry Warnings: German umlauts, Greek tonoi/Latin folding, and custom fields', () => {
    const i18n = new SafePocketI18n('de-DE');
    const items = createInitialSampleVaultItems();
    items[2].fields.push({
      id: 'cf-1',
      labelKey: 'Bürgeramt Aktenzeichen',
      isCustomLabel: true,
      value: 'MUC-ZÜRICH-774',
      valueType: 'TEXT'
    });

    searchVaultItems(items, 'warmup', i18n);
    const start = performance.now();
    const byDigraph = searchVaultItems(items, 'mueller', i18n);
    const byStripped = searchVaultItems(items, 'muller', i18n);
    const byGreekUnaccented = searchVaultItems(items, 'αθηνα', i18n);
    const byGreekLatin = searchVaultItems(items, 'athina', i18n);
    const byCustomDigraph = searchVaultItems(items, 'zuerich', i18n);
    const byLocalizedCategory = searchVaultItems(items, 'amtliches', i18n);
    const elapsedMs = performance.now() - start;

    expect(elapsedMs).toBeLessThan(50);
    expect(byDigraph.length).toBeGreaterThanOrEqual(2);
    expect(byStripped.length).toBeGreaterThanOrEqual(2);
    expect(byGreekUnaccented.map((i) => i.id)).toContain(
      'item-doc-hellenic-passport'
    );
    expect(byGreekLatin.map((i) => i.id)).toContain(
      'item-doc-hellenic-passport'
    );
    expect(byCustomDigraph.map((i) => i.id)).toContain('item-identity-primary');
    expect(byLocalizedCategory).toHaveLength(2);

    const exp = computeExpiryStatus(items[1]);
    expect(exp.severity).toBe('warning');
  });

  it('6. Storage & Cloud Sync Policy: defaults to Local Storage Only, uses Firebase as default cloud service when enabled, and Google Drive optionally', async () => {
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock('pw', generateFormattedSecretKey());
    const sample = createInitialSampleVaultItems()[0];

    const rowPhone = await cryptoEngine.encryptVaultItem(
      { ...sample, title: 'Edited on Android 17 Foldable' },
      '001763200000100-0001-android',
      { android: 2, pwa: 1 }
    );
    const rowPwa = await cryptoEngine.encryptVaultItem(
      { ...sample, title: 'Edited Offline on ChromeOS PWA' },
      '001763200000250-0001-pwa',
      { android: 1, pwa: 2 }
    );

    const merged = reconcileEncryptedRows(rowPhone, rowPwa);
    expect(merged.versionVector).toEqual({ android: 2, pwa: 2 });

    const fb = new FirebaseZeroKnowledgeAdapter();
    const gd = new GoogleDriveAppDataAdapter();
    const coordinator = new VaultSyncCoordinator(fb, gd);

    // 1) Strictly Local Storage Only by default
    expect(coordinator.isCloudSyncEnabled).toBe(false);
    expect(coordinator.effectiveMode).toBe('NONE');
    expect(coordinator.cloudService).toBe('FIREBASE');
    expect(await coordinator.syncEncryptedRowsIfEnabled('v1', [merged])).toBeNull();

    // 2) Firebase is the default cloud service once enabled
    coordinator.setCloudSyncEnabled(true);
    expect(coordinator.effectiveMode).toBe('FIREBASE');
    const fbResult = await coordinator.syncEncryptedRowsIfEnabled('v1', [merged]);
    expect(fbResult?.service).toBe('FIREBASE');
    expect(fbResult?.pushedCount).toBe(1);

    // 3) Google Drive (appDataFolder) works as an optional override
    coordinator.setPreferredCloudService('GDRIVE_APPDATA');
    expect(coordinator.effectiveMode).toBe('GDRIVE_APPDATA');
    const gdResult = await coordinator.syncEncryptedRowsIfEnabled('v1', [merged]);
    expect(gdResult?.service).toBe('GDRIVE_APPDATA');
    expect(gdResult?.pushedCount).toBe(1);
  });

  it('7. Live Cloud REST Connectors & Zero-Knowledge Outbound Guard: validates Firestore :commit & Google Drive appDataFolder requests and rejects plaintext leaks', async () => {
    // Verify client-side zero-knowledge guard blocks any unexpected plaintext key
    expect(() =>
      assertStrictZeroKnowledgeEnvelope({
        entityId: 'item-1',
        entityType: 'ITEM',
        hlcPacked: '001',
        versionVector: '{}',
        ivHex: '00112233445566778899aabb',
        ciphertextHex: 'a'.repeat(64),
        aadContext: 'ctx',
        isTombstone: false,
        title: 'LEAKED PLAINTEXT'
      })
    ).toThrow(/forbidden outbound field "title"/);

    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock('rest-pw', generateFormattedSecretKey());
    const encRow = await cryptoEngine.encryptVaultItem(
      createInitialSampleVaultItems()[0],
      '001763200000999-0000-pwa',
      { pwa: 1 }
    );

    const capturedRequests: Array<{ url: string; init?: RequestInit }> = [];
    const mockFetch = (async (url: string | URL | Request, init?: RequestInit) => {
      capturedRequests.push({ url: String(url), init });
      return new Response(JSON.stringify({ writeResults: [{}] }), { status: 200 });
    }) as typeof fetch;

    // 1) Firestore REST :commit (Default Cloud Service)
    const fbRes = await pushRowsToFirestoreRest(
      'vault-eu-1',
      [encRow],
      {
        firebaseProjectId: 'safepocket-prod',
        bearerToken: 'firebase-id-token-xyz',
        firebaseUserId: 'uid-clara'
      },
      mockFetch
    );
    expect(fbRes.pushedCount).toBe(1);
    expect(capturedRequests[0].url).toBe(
      'https://firestore.googleapis.com/v1/projects/safepocket-prod/databases/(default)/documents:commit'
    );
    expect(String(capturedRequests[0].init?.body)).not.toContain('N26 Metal');

    // 2) Google Drive v3 appDataFolder multipart upload (Optional Cloud Service)
    const gdRes = await pushRowsToGoogleDriveAppDataRest(
      'vault-eu-1',
      [encRow],
      'gdrive-oauth-token-abc',
      mockFetch
    );
    expect(gdRes.pushedCount).toBe(1);
    expect(capturedRequests[1].url).toContain(
      'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart'
    );
    expect(String(capturedRequests[1].init?.body)).toContain('"appDataFolder"');
    expect(String(capturedRequests[1].init?.body)).not.toContain('CLARA MÜLLER');
  });

  it('8. First-Run Onboarding & Emergency Kit: computes Master Password entropy and prints Secret Key without exposing Master Password', () => {
    const weak = estimateMasterPasswordEntropy('12345');
    const strong = estimateMasterPasswordEntropy('Correct-Horse-Vault-2026!Ä');
    expect(weak.rating).toBe('Weak');
    expect(strong.bits).toBeGreaterThanOrEqual(95);
    expect(strong.rating).toBe('Vault-Grade');

    const secretKey = generateFormattedSecretKey();
    const kitHtml = buildEmergencyKitHtml({
      vaultId: 'vault-test-01',
      createdAtIso: '2026-11-15T12:00:00Z',
      secretKeyFormatted: secretKey,
      kdf: {
        memorySizeKiB: 16384,
        iterations: 3,
        parallelism: 2,
        saltHex: '00112233445566778899aabbccddeeff'
      }
    });
    expect(kitHtml).toContain(secretKey);
    expect(kitHtml).toContain('Argon2id');
    expect(kitHtml).not.toContain('Correct-Horse-Vault-2026');

    const headerStore = new VaultHeaderStore();
    expect(headerStore.loadHeader()).toBeNull();
    headerStore.saveHeader({
      vaultId: 'vault-test-01',
      createdAtIso: '2026-11-15T12:00:00Z',
      saltHex: '00112233445566778899aabbccddeeff',
      memorySizeKiB: 16384,
      iterations: 3,
      parallelism: 2,
      verifierHashHex: 'deadbeef'
    });
    expect(headerStore.loadHeader()?.vaultId).toBe('vault-test-01');
  });

  it('9. Active Inactivity Auto-Lock Monitor: defaults to 5 minutes and fires deterministic RAM key-wipe callback upon idle timeout', () => {
    vi.useFakeTimers();
    try {
      let lockedCount = 0;
      const monitor = new IdleAutoLockMonitor(() => {
        lockedCount += 1;
      });
      expect(monitor.minutes).toBe(5);

      monitor.start();
      vi.advanceTimersByTime(4 * 60 * 1000);
      expect(lockedCount).toBe(0);

      // Advance past 5-minute threshold -> auto-lock triggers
      vi.advanceTimersByTime(61 * 1000);
      expect(lockedCount).toBe(1);

      // Switch to 1-minute profile
      monitor.setMinutes(1);
      expect(monitor.minutes).toBe(1);
      vi.advanceTimersByTime(61 * 1000);
      expect(lockedCount).toBe(2);
      monitor.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('10. Self-Updating Service: reports up_to_date on shipped v0.1 (Build 1) manifest, stages signed v0.2 updates, and rejects SHA-256 tampering', async () => {
    const publicDir = path.resolve(__dirname, '../../public');
    const shippedManifestJson = fs.readFileSync(
      path.join(publicDir, 'version.json'),
      'utf-8'
    );
    const swBytes = fs.readFileSync(path.join(publicDir, 'sw.js'));

    // 1) Shipped v0.1 (Build 1) manifest matches APP_BUILD_INFO (versionCode: 1) -> up_to_date
    const baselineUpdater = new PwaUpdateController('/version.json');
    const baselineStatus = await baselineUpdater.checkForUpdates((async (
      url: string
    ) => {
      if (url.endsWith('/version.json')) {
        return new Response(shippedManifestJson, { status: 200 });
      }
      return new Response(swBytes, { status: 200 });
    }) as typeof fetch);
    expect(baselineStatus.phase).toBe('up_to_date');
    expect(baselineStatus.ed25519Verified).toBe(true);

    // 2) Dynamically sign a v0.4 (next build) manifest fixture to verify staged update & SHA-256 tamper rejection
    const nextManifest: RemoteUpdateManifest = {
      ...(JSON.parse(shippedManifestJson) as RemoteUpdateManifest),
      latestVersion: '0.4',
      versionCode: APP_BUILD_INFO.versionCode + 1
    };
    const pkcs8Prefix = Buffer.from('302e020100300506032b657004220420', 'hex');
    const seedBytes = nodeCrypto
      .createHash('sha256')
      .update('safepocket-dev-release-signing-seed-v0.1')
      .digest();
    const privateKey = nodeCrypto.createPrivateKey({
      key: Buffer.concat([pkcs8Prefix, seedBytes]),
      format: 'der',
      type: 'pkcs8'
    });
    const sigHex = nodeCrypto
      .sign(
        null,
        Buffer.from(buildCanonicalManifestMessage(nextManifest), 'utf-8'),
        privateKey
      )
      .toString('hex');
    nextManifest.manifestSignature = `ed25519:${sigHex}`;
    const nextManifestJson = JSON.stringify(nextManifest);

    const mockFetcher = (async (url: string) => {
      if (url.endsWith('/version.json')) {
        return new Response(nextManifestJson, { status: 200 });
      }
      return new Response(swBytes, { status: 200 });
    }) as typeof fetch;

    const updater = new PwaUpdateController('/version.json');
    const checkStatus = await updater.checkForUpdates(mockFetcher);
    expect(checkStatus.phase).toBe('available');
    expect(updater.localizedReleaseNotes('el')).toContain('SafePocket');

    const stageStatus =
      await updater.verifyChecksumAndStageServiceWorker(mockFetcher);
    expect(stageStatus.phase).toBe('ready_to_reload');
    expect(stageStatus.verifiedSha256).toHaveLength(64);

    const tamperedFetcher = (async (url: string) => {
      if (url.endsWith('/version.json')) {
        return new Response(nextManifestJson, { status: 200 });
      }
      return new Response(new Uint8Array([1, 2, 3, 4]), { status: 200 });
    }) as typeof fetch;

    await updater.checkForUpdates(tamperedFetcher);
    const rejected =
      await updater.verifyChecksumAndStageServiceWorker(tamperedFetcher);
    expect(rejected.phase).toBe('error');
    expect(rejected.errorMessage).toContain('SHA-256 tampering detected');
  });

  it('11. Version v0.5.0-beta.3 (Build 18) & Real Ed25519 Manifest Signing: verifies v0.5.0-beta.3 baseline and rejects forged/mutated version.json signatures', async () => {
    expect(APP_BUILD_INFO.versionName).toBe('0.5.0-beta.3');
    expect(APP_BUILD_INFO.versionCode).toBe(18);

    const publicDir = path.resolve(__dirname, '../../public');
    const signedManifest = JSON.parse(
      fs.readFileSync(path.join(publicDir, 'version.json'), 'utf-8')
    ) as RemoteUpdateManifest;

    expect(signedManifest.latestVersion).toBe('0.5.0-beta.3');
    expect(signedManifest.versionCode).toBe(18);
    expect(await verifyManifestEd25519Signature(signedManifest)).toBe(true);

    // Mutate latestVersion without re-signing -> Ed25519 verification must fail
    const mutatedManifest: RemoteUpdateManifest = {
      ...signedManifest,
      latestVersion: '9.9.9'
    };
    expect(await verifyManifestEd25519Signature(mutatedManifest)).toBe(false);

    // Updater must immediately reject tampered manifest signature
    const updater = new PwaUpdateController('/version.json');
    const badStatus = await updater.checkForUpdates((async () =>
      new Response(JSON.stringify(mutatedManifest), { status: 200 })) as typeof fetch);
    expect(badStatus.phase).toBe('error');
    expect(badStatus.errorMessage).toContain(
      'Ed25519 manifest signature verification failed'
    );
  });

  it('12. SQLite WASM Encrypted Database Engine: persists encrypted rows, filters tombstones, and tracks provider sync cursors', async () => {
    const sqlite = new SqliteWasmVaultDb(false);
    await sqlite.ensureInitialized();

    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock('sqlite-pw', generateFormattedSecretKey());

    const items = createInitialSampleVaultItems();
    const enc0 = await cryptoEngine.encryptVaultItem(
      items[0],
      '001763200001000-0000-pwa',
      { pwa: 1 }
    );
    const enc1 = await cryptoEngine.encryptVaultItem(
      items[1],
      '001763200002000-0000-pwa',
      { pwa: 1 }
    );

    await sqlite.saveEncryptedRow(enc0);
    await sqlite.saveEncryptedRow(enc1);
    expect(await sqlite.loadAllEncryptedRows()).toHaveLength(2);

    await sqlite.setSyncCursor('FIREBASE:uid-1', '001763200002000-0000-pwa');
    expect(await sqlite.getSyncCursor('FIREBASE:uid-1')).toBe(
      '001763200002000-0000-pwa'
    );
  });

  it('13. Pre-Initialized Firebase Database, Zero-Knowledge User Creation & Two-Way CRDT Sync: creates cloud user and syncs encrypted items across two devices', async () => {
    // Shared SQLite WASM instance simulating the pre-initialized Firebase DB
    const sharedFirebaseSqlite = new SqliteWasmVaultDb(false);
    const firebaseAuth = new FirebaseVaultService(sharedFirebaseSqlite);

    // 1) Dormant initialization at boot (no network calls, local storage remains default)
    await firebaseAuth.initializeDormant();
    expect(firebaseAuth.isInitialized).toBe(true);
    expect(firebaseAuth.currentUser).toBeNull();

    // 2) User Creation (Sign-Up) with HKDF zero-knowledge derived auth verifier
    const createdUser = await firebaseAuth.createCloudUser(
      'petros@safepocket.eu',
      'CloudSyncSecret#2026'
    );
    expect(createdUser.email).toBe('petros@safepocket.eu');
    expect(createdUser.uid).toMatch(/^fb-uid-/);

    // Duplicate email creation must be rejected
    await expect(
      firebaseAuth.createCloudUser('petros@safepocket.eu', 'AnotherPass123')
    ).rejects.toThrow(/EMAIL_EXISTS/);

    // Sign-Out + Sign-In verification + Wrong password rejection
    firebaseAuth.signOutCloudUser();
    expect(firebaseAuth.currentUser).toBeNull();

    await expect(
      firebaseAuth.signInCloudUser('petros@safepocket.eu', 'WrongPassword999')
    ).rejects.toThrow(/INVALID_PASSWORD/);

    const signedIn = await firebaseAuth.signInCloudUser(
      'petros@safepocket.eu',
      'CloudSyncSecret#2026'
    );
    expect(signedIn.uid).toBe(createdUser.uid);

    // 3) Two-Way CRDT Sync across Device A (pushes encrypted item) and Device B (pulls & merges)
    const sharedVaultCrypto = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    const sharedSecret = generateFormattedSecretKey();
    await sharedVaultCrypto.initializeOrUnlock('shared-vault-master', sharedSecret);

    const endpointCfg = firebaseAuth.toEndpointConfig();
    const deviceAAdapter = new FirebaseZeroKnowledgeAdapter(sharedFirebaseSqlite);
    deviceAAdapter.setLiveEndpointConfig(endpointCfg);
    const deviceACoordinator = new VaultSyncCoordinator(
      deviceAAdapter,
      new GoogleDriveAppDataAdapter()
    );
    deviceACoordinator.setCloudSyncEnabled(true);

    const sampleCard = createInitialSampleVaultItems()[0];
    const encCardA = await sharedVaultCrypto.encryptVaultItem(
      sampleCard,
      '001763200010000-0000-deviceA',
      { deviceA: 1 }
    );

    const syncA = await deviceACoordinator.syncBidirectionalIfEnabled(
      'vault-shared-01',
      [encCardA],
      null
    );
    expect(syncA?.pushedCount).toBe(1);

    // Device B starts with empty local rows, signs into same Firebase UID, and runs bidirectional sync
    const deviceBAdapter = new FirebaseZeroKnowledgeAdapter(sharedFirebaseSqlite);
    deviceBAdapter.setLiveEndpointConfig(endpointCfg);
    const deviceBCoordinator = new VaultSyncCoordinator(
      deviceBAdapter,
      new GoogleDriveAppDataAdapter()
    );
    deviceBCoordinator.setCloudSyncEnabled(true);

    const syncB = await deviceBCoordinator.syncBidirectionalIfEnabled(
      'vault-shared-01',
      [],
      null
    );
    expect(syncB?.pulledCount).toBe(1);
    expect(syncB?.mergedRows).toHaveLength(1);

    const decryptedOnDeviceB = await sharedVaultCrypto.decryptVaultRow(
      syncB!.mergedRows[0]
    );
    expect(decryptedOnDeviceB.title).toBe(sampleCard.title);
  });

  it('14. Offline ICAO Doc 9303 MRZ Document Scanner & PAN / ISO-13616 IBAN Validators: parses TD3 Passports & TD1 IDs and validates checksums', () => {
    // 1) TD3 Passport (2x44)
    const sampleTd3 = buildSampleTd3PassportMrz();
    const parsedTd3 = parseOfflineIcaoMrz(sampleTd3);
    expect(parsedTd3.format).toBe('TD3_PASSPORT');
    expect(parsedTd3.documentSubtype).toBe('PASSPORT');
    expect(parsedTd3.issuingState).toBe('DEU');
    expect(parsedTd3.documentNumber).toBe('C01X00T47');
    expect(parsedTd3.holderFullName).toBe('CLARA SOFIA MUELLER');
    expect(parsedTd3.birthDateIso).toBe('1992-04-19');
    expect(parsedTd3.expiryDateIso).toBe('2031-06-15');
    expect(parsedTd3.checkDigitsValid).toBe(true);

    // 2) TD1 National ID Card (3x30)
    const idDoc = 'L01X00T47';
    const idDocChk = computeIcaoCheckDigit(idDoc);
    const idDob = '920419';
    const idDobChk = computeIcaoCheckDigit(idDob);
    const idExp = '320801';
    const idExpChk = computeIcaoCheckDigit(idExp);
    const td1Line1 = `IDITA${idDoc}${idDocChk}<<<<<<<<<<<<<<<`;
    const td1Line2 = `${idDob}${idDobChk}F${idExp}${idExpChk}ITA<<<<<<<<<<<0`;
    const td1Line3 = 'ROSSI<<MARCO<ANTONIO<<<<<<<<<<';
    const parsedTd1 = parseOfflineIcaoMrz(
      `${td1Line1}\n${td1Line2}\n${td1Line3}`
    );
    expect(parsedTd1.format).toBe('TD1_NATIONAL_ID');
    expect(parsedTd1.documentSubtype).toBe('NATIONAL_ID');
    expect(parsedTd1.nationality).toBe('ITA');
    expect(parsedTd1.holderFullName).toBe('MARCO ANTONIO ROSSI');
    expect(parsedTd1.checkDigitsValid).toBe(true);

    // 3) Luhn PAN & ISO-13616 IBAN checksums
    const mastercardCheck = validatePaymentCardPan('5355 8910 4421 7805');
    const visaValid = validatePaymentCardPan('4532 0151 1283 0366');
    expect(visaValid.networkBrand).toBe('Visa');
    expect(visaValid.validLuhn).toBe(true);
    expect(mastercardCheck.networkBrand).toBe('Mastercard');

    expect(validateIsoIban('DE89 3704 0044 0532 0130 00')).toBe(true);
    expect(validateIsoIban('DE00 3704 0044 0532 0130 00')).toBe(false);
  });

  it('15. Provisioned Live Firebase Project (safepocket-vault-eu • eur3): pre-loads Web SDK config & sends HKDF-derived password to Identity Toolkit', async () => {
    const sqlite = new SqliteWasmVaultDb(false);
    const liveService = new FirebaseVaultService(sqlite, true);
    await liveService.initializeDormant();

    expect(liveService.isLiveProjectLinked).toBe(true);
    expect(liveService.webConfig.projectId).toBe('safepocket-vault-eu');
    expect(liveService.webConfig.apiKey).toBe(
      DEFAULT_PROVISIONED_FIREBASE_CONFIG.apiKey
    );

    let capturedBody = '';
    const mockLiveAuthFetch = (async (_url: string | URL | Request, init?: RequestInit) => {
      capturedBody = String(init?.body ?? '');
      return new Response(
        JSON.stringify({
          localId: 'live-uid-eur3-001',
          email: 'petros@pvamail.de',
          idToken: 'eyJhbGciOiJSUzI1NiIsImtpZCI6ImxpdmUifQ.test',
          refreshToken: 'refresh-eur3'
        }),
        { status: 200 }
      );
    }) as typeof fetch;

    const session = await liveService.createCloudUser(
      'petros@pvamail.de',
      'UserChosenSyncPass!99',
      mockLiveAuthFetch
    );
    expect(session.mode).toBe('live-firebase');
    expect(session.uid).toBe('live-uid-eur3-001');
    // Verify raw user password was NEVER sent — only spzk_<64-hex> HKDF verifier
    expect(capturedBody).not.toContain('UserChosenSyncPass!99');
    expect(capturedBody).toContain('"spzk_');

    const endpoint = liveService.toEndpointConfig();
    expect(endpoint.firebaseProjectId).toBe('safepocket-vault-eu');
    expect(endpoint.firebaseUserId).toBe('live-uid-eur3-001');
  });

  it('16. EU Firebase Storage .enc Multipart Sync: enforces storage.rules schema, zero filename/MIME leak & SHA-256 tamper rejection', async () => {
    const cryptoEngine = new ZeroKnowledgeVaultCrypto();
    await cryptoEngine.initializeOrUnlock(
      'MasterPass#2026!',
      generateFormattedSecretKey()
    );

    const rawScan = new TextEncoder().encode(
      'HIGH-RES-PASSPORT-FRONT-JPEG-BYTES-1234567890'
    );
    const encrypted = await cryptoEngine.encryptBinaryAttachment(
      'att_passport_front_01',
      'Reisepass_Max_Mustermann.jpg',
      'image/jpeg',
      rawScan,
      'front'
    );

    // Reject path-traversal or filename leaks in attachmentId
    await expect(
      assertStrictZeroKnowledgeBlobUpload({
        attachmentId: '../Reisepass.jpg',
        sha256Hex: encrypted.sha256Hex,
        encryptedBytes: encrypted.encryptedBytes
      })
    ).rejects.toThrow(/invalid attachmentId/);

    let capturedUrl = '';
    let capturedContentType = '';
    let capturedBodyBytes = new Uint8Array(0);
    const storageMemory = new Map<string, Uint8Array>();

    const mockStorageFetch = (async (
      url: string | URL | Request,
      init?: RequestInit
    ) => {
      capturedUrl = String(url);
      if (init?.method === 'POST') {
        capturedContentType = String(
          (init.headers as Record<string, string>)['Content-Type'] ?? ''
        );
        capturedBodyBytes = init.body as Uint8Array;
        storageMemory.set(
          'users/uid_eu_1/vaults/default-vault/blobs/att_passport_front_01.enc',
          encrypted.encryptedBytes.slice()
        );
        return new Response(JSON.stringify({ name: 'ok' }), { status: 200 });
      }
      const stored = storageMemory.get(
        'users/uid_eu_1/vaults/default-vault/blobs/att_passport_front_01.enc'
      );
      if (!stored) return new Response('Not found', { status: 404 });
      return new Response(stored as BodyInit, { status: 200 });
    }) as typeof fetch;

    const uploadRes = await uploadEncryptedBlobToFirebaseStorageRest(
      'default-vault',
      {
        attachmentId: encrypted.id,
        sha256Hex: encrypted.sha256Hex,
        encryptedBytes: encrypted.encryptedBytes
      },
      {
        firebaseStorageBucket: 'safepocket-vault-eu.firebasestorage.app',
        bearerToken: 'jwt-eu-token',
        firebaseUserId: 'uid_eu_1'
      },
      mockStorageFetch
    );

    expect(uploadRes.storageObjectPath).toBe(
      'users/uid_eu_1/vaults/default-vault/blobs/att_passport_front_01.enc'
    );
    expect(capturedUrl).toContain(
      'https://firebasestorage.googleapis.com/v0/b/safepocket-vault-eu.firebasestorage.app/o?uploadType=multipart'
    );
    expect(capturedContentType).toContain('multipart/related; boundary=');

    const multipartPreview = new TextDecoder().decode(capturedBodyBytes);
    expect(multipartPreview).toContain('"contentType":"application/octet-stream"');
    expect(multipartPreview).toContain(
      `"ciphertextSha256":"${encrypted.sha256Hex}"`
    );
    // Original filename & MIME type MUST NEVER appear in Storage multipart envelope
    expect(multipartPreview).not.toContain('Reisepass_Max_Mustermann.jpg');
    expect(multipartPreview).not.toContain('image/jpeg');

    const downloaded = await downloadEncryptedBlobFromFirebaseStorageRest(
      'default-vault',
      'att_passport_front_01',
      encrypted.sha256Hex,
      {
        firebaseStorageBucket: 'safepocket-vault-eu.firebasestorage.app',
        bearerToken: 'jwt-eu-token',
        firebaseUserId: 'uid_eu_1'
      },
      mockStorageFetch
    );
    expect(downloaded).not.toBeNull();
    const decrypted = await cryptoEngine.decryptBinaryAttachment({
      attachmentId: encrypted.id,
      ivHex: encrypted.ivHex,
      expectedSha256Hex: encrypted.sha256Hex,
      encryptedBytes: downloaded!
    });
    expect(new TextDecoder().decode(decrypted)).toBe(
      'HIGH-RES-PASSPORT-FRONT-JPEG-BYTES-1234567890'
    );

    // Flip 1 bit in cloud storage -> SHA-256 constant-time integrity guard MUST reject
    const corrupted = encrypted.encryptedBytes.slice();
    corrupted[0] ^= 0xff;
    storageMemory.set(
      'users/uid_eu_1/vaults/default-vault/blobs/att_passport_front_01.enc',
      corrupted
    );
    await expect(
      downloadEncryptedBlobFromFirebaseStorageRest(
        'default-vault',
        'att_passport_front_01',
        encrypted.sha256Hex,
        {
          firebaseStorageBucket: 'safepocket-vault-eu.firebasestorage.app',
          bearerToken: 'jwt-eu-token',
          firebaseUserId: 'uid_eu_1'
        },
        mockStorageFetch
      )
    ).rejects.toThrow(/SHA-256 integrity check failed/);
  });

  it('17. Two-Device End-to-End Attachment Sync & Offline SQLite Queue: syncs large scan without Firestore base64 bloat and caches offline on Device B', async () => {
    const sharedSecretKey = generateFormattedSecretKey();
    const sharedCloudReplica = new SqliteWasmVaultDb(false);

    // Device A: encrypts vault item + 32 KB binary scan locally while offline
    const cryptoA = new ZeroKnowledgeVaultCrypto();
    await cryptoA.initializeOrUnlock('SharedMaster!2026', sharedSecretKey);
    const kdfConfig = cryptoA.currentKdfConfig;

    const dbA = new SqliteWasmVaultDb(false);
    const fbAdapterA = new FirebaseZeroKnowledgeAdapter(sharedCloudReplica);
    fbAdapterA.setLiveEndpointConfig({ firebaseUserId: 'uid-shared-user' });
    const syncA = new VaultSyncCoordinator(
      fbAdapterA,
      new GoogleDriveAppDataAdapter()
    );

    const largePdfBytes = new Uint8Array(24_576);
    for (let i = 0; i < largePdfBytes.length; i++) {
      largePdfBytes[i] = (i * 31 + 7) & 0xff;
    }

    const encBlob = await cryptoA.encryptBinaryAttachment(
      'att_large_pdf_01',
      'Residence_Permit_2026.pdf',
      'application/pdf',
      largePdfBytes,
      'front'
    );
    await dbA.saveLocalEncryptedBlob(
      'default-vault',
      encBlob.id,
      encBlob.encryptedBytes,
      encBlob.sha256Hex,
      false
    );

    const pendingBefore = await dbA.listPendingUnsyncedBlobs('default-vault');
    expect(pendingBefore).toHaveLength(1);

    const docItem = createInitialSampleVaultItems()[2];
    docItem.id = 'doc-sync-pdf-1';
    docItem.attachments = [
      {
        id: encBlob.id,
        fileName: encBlob.fileName,
        mimeType: encBlob.mimeType,
        side: 'front',
        sizeBytes: encBlob.sizeBytes,
        sha256Hex: encBlob.sha256Hex,
        ivHex: encBlob.ivHex,
        // Notice: NO inline ciphertextBase64 in Firestore CRDT item!
        ciphertextBase64: undefined,
        blobSynced: false
      }
    ];

    const hlcA = new HybridClock('device-a').nowPacked();
    const encRowA = await cryptoA.encryptVaultItem(docItem, hlcA, {
      'device-a': 1
    });
    await dbA.saveEncryptedRow(encRowA);

    // Enable Cloud Sync on Device A -> pushes CRDT delta + pending .enc blob
    syncA.setCloudSyncEnabled(true);
    const syncResA = await syncA.syncBidirectionalIfEnabled(
      'default-vault',
      await dbA.loadAllRowsIncludingTombstones(),
      null,
      pendingBefore
    );
    expect(syncResA?.uploadedBlobCount).toBe(1);
    await dbA.markLocalBlobSynced(encBlob.id);
    expect(await dbA.listPendingUnsyncedBlobs('default-vault')).toHaveLength(0);

    // Device B: fresh SQLite vault unlocked with same Master Password + Secret Key + Salt
    const cryptoB = new ZeroKnowledgeVaultCrypto(kdfConfig);
    await cryptoB.initializeOrUnlock(
      'SharedMaster!2026',
      sharedSecretKey,
      kdfConfig
    );
    const dbB = new SqliteWasmVaultDb(false);
    const fbAdapterB = new FirebaseZeroKnowledgeAdapter(sharedCloudReplica);
    fbAdapterB.setLiveEndpointConfig({ firebaseUserId: 'uid-shared-user' });
    const syncB = new VaultSyncCoordinator(
      fbAdapterB,
      new GoogleDriveAppDataAdapter()
    );
    syncB.setCloudSyncEnabled(true);

    const syncResB = await syncB.syncBidirectionalIfEnabled(
      'default-vault',
      [],
      null
    );
    expect(syncResB?.pulledCount).toBe(1);

    const hydratedOnB = await cryptoB.decryptVaultRow(syncResB!.mergedRows[0]);
    expect(hydratedOnB.attachments[0].fileName).toBe(
      'Residence_Permit_2026.pdf'
    );
    expect(hydratedOnB.attachments[0].ciphertextBase64).toBeUndefined();

    // Device B fetches .enc blob from Firebase Storage and caches it offline in SQLite WASM
    const fetchedEncBytes =
      await syncB.downloadMissingAttachmentBlobIfEnabled(
        'default-vault',
        hydratedOnB.attachments[0].id,
        hydratedOnB.attachments[0].sha256Hex
      );
    expect(fetchedEncBytes).not.toBeNull();
    await dbB.saveLocalEncryptedBlob(
      'default-vault',
      hydratedOnB.attachments[0].id,
      fetchedEncBytes!,
      hydratedOnB.attachments[0].sha256Hex,
      true
    );

    // Go offline on Device B -> still decrypts exact 24 KiB PDF from local SQLite WASM cache!
    syncB.setCloudSyncEnabled(false);
    const offlineCached = await dbB.loadLocalEncryptedBlob(
      hydratedOnB.attachments[0].id
    );
    expect(offlineCached?.cloudSynced).toBe(true);
    const decryptedPdfOnB = await cryptoB.decryptBinaryAttachment({
      attachmentId: hydratedOnB.attachments[0].id,
      ivHex: hydratedOnB.attachments[0].ivHex,
      expectedSha256Hex: hydratedOnB.attachments[0].sha256Hex,
      encryptedBytes: offlineCached!.encryptedBytes
    });
    expect(decryptedPdfOnB).toEqual(largePdfBytes);
  });

  it('18. Light Theme Default + Dark Option & Bank Accounts / Insurance Cards / Membership Cards across all 6 ARB locales', async () => {
    // 1) Light Theme is strictly the default out-of-the-box, even if OS prefers dark
    expect(resolveEffectiveTheme('light', false)).toBe('light');
    expect(resolveEffectiveTheme('light', true)).toBe('light');
    // Dark Theme option & System Theme option
    expect(resolveEffectiveTheme('dark', false)).toBe('dark');
    expect(resolveEffectiveTheme('dark', true)).toBe('dark');
    expect(resolveEffectiveTheme('system', false)).toBe('light');
    expect(resolveEffectiveTheme('system', true)).toBe('dark');

    const indexHtml = fs.readFileSync(
      path.resolve(__dirname, '../../index.html'),
      'utf-8'
    );
    expect(indexHtml).toContain('data-theme="light"');
    expect(indexHtml).toContain('content="#F5F7FA"');

    // 2) All 7 first-class categories are present in VAULT_CATEGORIES & sample vault
    expect(VAULT_CATEGORIES).toEqual([
      'PAYMENT_CARD',
      'BANK_ACCOUNT',
      'INSURANCE_CARD',
      'MEMBERSHIP_CARD',
      'IDENTITY',
      'OFFICIAL_DOCUMENT',
      'SECURE_NOTE'
    ]);

    const items = createInitialSampleVaultItems();
    const bankItem = items.find((i) => i.category === 'BANK_ACCOUNT');
    const insuranceItem = items.find((i) => i.category === 'INSURANCE_CARD');
    const membershipItem = items.find((i) => i.category === 'MEMBERSHIP_CARD');
    expect(bankItem).toBeDefined();
    expect(insuranceItem).toBeDefined();
    expect(membershipItem).toBeDefined();

    // Validate SEPA IBAN on sample Bank Account
    const bankIban = bankItem!.fields.find((f) => f.labelKey === 'field_iban')?.value ?? '';
    expect(validateIsoIban(bankIban)).toBe(true);

    // Encrypt & decrypt all 3 new categories via ZeroKnowledgeVaultCrypto with category-bound AAD
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock(
      'ThemeAndCardsPass#2026',
      generateFormattedSecretKey()
    );
    const hlc = new HybridClock('test-node-18').nowPacked();

    for (const newItem of [bankItem!, insuranceItem!, membershipItem!]) {
      const encRow = await cryptoEngine.encryptVaultItem(newItem, hlc, {
        'test-node-18': 1
      });
      expect(encRow.aadContext).toContain(newItem.category);
      expect(encRow.ciphertextHex).not.toContain(
        Buffer.from(newItem.title, 'utf-8').toString('hex')
      );
      const decItem = await cryptoEngine.decryptVaultRow(encRow);
      expect(decItem.category).toBe(newItem.category);
      expect(decItem.title).toBe(newItem.title);
      expect(decItem.fields).toEqual(newItem.fields);
    }

    // 3) Instant multilingual search across German, Italian, and Greek category & field names
    const i18nDe = new SafePocketI18n('de-DE');
    expect(searchVaultItems(items, 'Bankkonto', i18nDe).map((i) => i.id)).toContain(
      'item-bank-ing-giro'
    );
    expect(
      searchVaultItems(items, 'Versichertenkarte', i18nDe).map((i) => i.id)
    ).toContain('item-ins-tk-egk');
    expect(
      searchVaultItems(items, 'Mitgliedschaftskarte', i18nDe).map((i) => i.id)
    ).toContain('item-mem-adac-plus');

    const i18nIt = new SafePocketI18n('it-IT');
    expect(
      searchVaultItems(items, 'Conto bancario', i18nIt).map((i) => i.id)
    ).toContain('item-bank-ing-giro');
    expect(
      searchVaultItems(items, 'Tessera sanitaria', i18nIt).map((i) => i.id)
    ).toContain('item-ins-tk-egk');

    const i18nEl = new SafePocketI18n('el-GR');
    expect(
      searchVaultItems(items, 'τραπεζικος λογαριασμος', i18nEl).map((i) => i.id)
    ).toContain('item-bank-ing-giro');
    expect(
      searchVaultItems(items, 'καρτα ασφαλισης', i18nEl).map((i) => i.id)
    ).toContain('item-ins-tk-egk');
    expect(
      searchVaultItems(items, 'καρτα μελους', i18nEl).map((i) => i.id)
    ).toContain('item-mem-adac-plus');

    // 4) Enforce 100% ARB key parity across all 6 locales (en, de, it, fr, es, el)
    const i18nDir = path.resolve(__dirname, '../../../i18n');
    const enKeys = Object.keys(
      JSON.parse(fs.readFileSync(path.join(i18nDir, 'en.arb.json'), 'utf-8'))
    )
      .filter((k) => !k.startsWith('@'))
      .sort();

    for (const locale of ['de', 'it', 'fr', 'es', 'el']) {
      const localeKeys = Object.keys(
        JSON.parse(
          fs.readFileSync(path.join(i18nDir, `${locale}.arb.json`), 'utf-8')
        )
      )
        .filter((k) => !k.startsWith('@'))
        .sort();
      expect(localeKeys).toEqual(enKeys);
    }
  });

  it('19. 12 Curated Card Color Options, Material Design Device Theme / Dynamic Accent & Clean Brand Header', () => {
    // 1) 12 card color options available and localized across all 6 languages
    expect(CARD_THEME_PALETTES).toHaveLength(12);
    const i18n = new SafePocketI18n('en-US');
    for (const locale of SUPPORTED_LOCALES) {
      i18n.setLocaleOverride(locale.code);
      for (const paletteId of CARD_THEME_PALETTES) {
        const key = `palette_${paletteId}`;
        const label = i18n.t(key);
        expect(label).not.toBe(key);
        expect(label.length).toBeGreaterThan(2);
      }
      expect(i18n.t('material_color_device')).not.toBe('material_color_device');
      expect(i18n.t('theme_system')).not.toBe('theme_system');
    }

    // 2) Quick theme switcher cycles Light -> Dark -> Device Theme ('system') -> Light
    expect(nextThemeModeInCycle('light')).toBe('dark');
    expect(nextThemeModeInCycle('dark')).toBe('system');
    expect(nextThemeModeInCycle('system')).toBe('light');

    // 3) CSS defines all 12 card palettes + Material Design Device Accent + non-overlapping .sp-brand grid
    const css = fs.readFileSync(
      path.resolve(__dirname, '../styles/m3-expressive.css'),
      'utf-8'
    );
    for (const paletteId of CARD_THEME_PALETTES) {
      expect(css).toContain(`.sp-visual-card[data-palette='${paletteId}']`);
      expect(css).toContain(`.sp-palette-swatch[data-palette='${paletteId}']`);
    }
    expect(css).toContain("html[data-accent='device']");
    expect(css).toContain('AccentColor');
    expect(css).toContain('grid-template-columns: minmax(0, 1fr) auto');

    // 4) Sidebar brand header must NOT display AES-256 encryption badge
    const appShellSource = fs.readFileSync(
      path.resolve(__dirname, '../ui/appShell.ts'),
      'utf-8'
    );
    expect(appShellSource).not.toContain('<span class="sp-zk-chip">AES-256</span>');
  });

  it('20. Standards-Compliant Barcodes (Code 128B, EAN-13, QR) & Offline Camera Scanline Decoder: round-trips module rasters and recovery QR URIs', () => {
    // 1) ISO/IEC 15417 Code 128B encode -> module decode + RGBA scanline decode round-trip
    const codePayload = 'ADAC-70814920';
    const modules = encodeCode128BModules(codePayload);
    expect(decodeCode128BFromModules(modules)).toBe(codePayload);

    // Render synthetic 4x-scaled RGBA scanline image (white quiet zone + black bars)
    const scale = 4;
    const width = modules.length * scale;
    const height = 24;
    const rgba = new Uint8ClampedArray(width * height * 4);
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const isBar = modules[Math.floor(x / scale)] === 1;
        const c = isBar ? 12 : 248;
        const offset = (y * width + x) * 4;
        rgba[offset] = c;
        rgba[offset + 1] = c;
        rgba[offset + 2] = c;
        rgba[offset + 3] = 255;
      }
    }
    const scanned1D = decode1DBarcodeFromImageData(rgba, width, height);
    expect(scanned1D).toEqual({ rawValue: codePayload, format: 'CODE_128' });

    // 2) GS1 EAN-13 check digit & SVG renderer
    expect(computeEan13CheckDigit('400638133393')).toBe(1);
    const eanRendered = renderScannableBarcodeSvg('4006381333931', 'EAN_13');
    expect(eanRendered.format).toBe('EAN_13');
    expect(eanRendered.svg).toContain('<svg');
    expect(eanRendered.svg).toContain('aria-label="EAN-13 Barcode"');

    // 3) ISO/IEC 18004 QR Code SVG encoder & Crisp Document contrast stretch
    const qrSvg = encodeQrSvg('safepocket-recovery://v1?vaultId=v1&secretKey=SPK-23456-789AB-CDEFG-HJKLM-NPQRS');
    expect(qrSvg).toContain('<svg');
    expect(qrSvg).toContain('aria-label="QR Code"');

    const docPixels = new Uint8ClampedArray([40, 40, 40, 255, 220, 220, 220, 255]);
    applyCrispDocumentFilterInPlace(docPixels);
    expect(docPixels[0]).toBeLessThan(35);
    expect(docPixels[4]).toBeGreaterThan(240);

    // 4) Recovery QR payload extraction
    const sk = generateFormattedSecretKey();
    const uri = buildRecoverySecretKeyUri('vault-eu-01', sk);
    expect(parseRecoverySecretKeyPayload(uri)).toEqual({
      secretKey: sk,
      vaultId: 'vault-eu-01'
    });
  });

  it('21. Configurable Expiry Monitor, Pin-to-Top, Tag Filter Chips & 4-Mode Vault Sorting', () => {
    const nowMs = Date.UTC(2026, 10, 16, 12, 0, 0); // 2026-11-16 UTC
    expect(parseExpiryDateUtc('12/28')?.toISOString()).toBe(
      '2028-12-31T23:59:59.000Z'
    );
    expect(parseExpiryDateUtc('2027-02')?.toISOString()).toBe(
      '2027-02-28T23:59:59.000Z'
    );

    const items = createInitialSampleVaultItems();
    // Create controlled items for critical (<=30d), warning (31..90d), and expired (<0d)
    const criticalDoc = structuredClone(items[1]);
    criticalDoc.id = 'doc-crit-15d';
    criticalDoc.title = 'Zeta Critical ID';
    criticalDoc.pinned = false;
    criticalDoc.fields = [
      {
        id: 'f-exp-crit',
        labelKey: 'field_expiry_date',
        value: '2026-12-01',
        valueType: 'DATE_ISO'
      }
    ];

    const warningDoc = structuredClone(items[1]);
    warningDoc.id = 'doc-warn-75d';
    warningDoc.title = 'Alpha Renewal Card';
    warningDoc.pinned = false;
    warningDoc.fields = [
      {
        id: 'f-exp-warn',
        labelKey: 'field_expiry_date',
        value: '2027-01-30',
        valueType: 'DATE_ISO'
      }
    ];

    const expiredDoc = structuredClone(items[1]);
    expiredDoc.id = 'doc-expired-5d';
    expiredDoc.title = 'Beta Expired Pass';
    expiredDoc.pinned = false;
    expiredDoc.fields = [
      {
        id: 'f-exp-past',
        labelKey: 'field_expiry_date',
        value: '2026-11-10',
        valueType: 'DATE_ISO'
      }
    ];

    expect(computeExpiryStatus(criticalDoc, nowMs, 90).severity).toBe('critical');
    expect(computeExpiryStatus(warningDoc, nowMs, 90).severity).toBe('warning');
    // Narrowing horizon to 30 days marks 75-day item as 'valid' while 15-day item stays 'critical'
    expect(computeExpiryStatus(warningDoc, nowMs, 30).severity).toBe('valid');
    expect(computeExpiryStatus(criticalDoc, nowMs, 30).severity).toBe('critical');
    expect(computeExpiryStatus(expiredDoc, nowMs, 30).severity).toBe('expired');

    const summary90 = summarizeVaultExpiries(
      [criticalDoc, warningDoc, expiredDoc],
      nowMs,
      90
    );
    expect(summary90).toEqual({
      expiredCount: 1,
      criticalCount: 1,
      warningCount: 1,
      totalAttentionCount: 3
    });

    // Tag extraction & tag filtering
    const tags = extractVaultTags(items);
    expect(tags.length).toBeGreaterThan(0);
    const i18n = new SafePocketI18n('en-US');
    const targetTag = items[0].tags[0];
    const tagFiltered = searchVaultItems(items, '', i18n, { tag: targetTag });
    expect(tagFiltered.every((it) => it.tags.includes(targetTag))).toBe(true);

    // 4-mode sorting: pinned_recent keeps pinned items on top; alphabetical & expiring_soon sort deterministically
    const pinnedCard = {
      ...structuredClone(items[0]),
      id: 'pinned-card',
      title: 'Omega Pinned Card',
      pinned: true
    };
    const byPinned = sortVaultItems(
      [warningDoc, pinnedCard, criticalDoc],
      'pinned_recent',
      'en',
      nowMs,
      90
    );
    expect(byPinned[0].id).toBe('pinned-card');

    const byAlpha = sortVaultItems(
      [criticalDoc, warningDoc, expiredDoc],
      'alphabetical',
      'en',
      nowMs,
      90
    );
    expect(byAlpha.map((i) => i.title)).toEqual([
      'Alpha Renewal Card',
      'Beta Expired Pass',
      'Zeta Critical ID'
    ]);

    const byExpiring = sortVaultItems(
      [warningDoc, criticalDoc, expiredDoc],
      'expiring_soon',
      'en',
      nowMs,
      90
    );
    expect(byExpiring.map((i) => i.id)).toEqual([
      'doc-expired-5d',
      'doc-crit-15d',
      'doc-warn-75d'
    ]);
  });

  it('22. Encrypted .safepocket Header Inspection, Cross-Vault Merge/Restore, Plain Export & Printable Emergency Kit v2 QR', async () => {
    // Vault A creates backup with its own Master Password + Secret Key
    const cryptoA = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    const secretA = generateFormattedSecretKey();
    await cryptoA.initializeOrUnlock('VaultAPass#2026', secretA);
    const sample = createInitialSampleVaultItems();
    const backupItems = [
      { ...structuredClone(sample[0]), updatedAtEpochMs: 5_000, title: 'Updated N26 Metal' },
      { ...structuredClone(sample[1]), id: 'new-foreign-passport', title: 'New Passport From Backup' }
    ];
    const containerJson = await cryptoA.exportSafePocketContainer(
      backupItems,
      'VaultAPass#2026',
      secretA
    );

    const headerSummary = await inspectSafePocketContainerHeader(containerJson);
    expect(headerSummary.appVersion).toBe(APP_BUILD_INFO.versionName);
    expect(headerSummary.itemCount).toBe(2);
    expect(headerSummary.sha256Hex).toHaveLength(64);

    // Vault B (unlocked with different credentials!) restores Vault A's container using Vault A's credentials & merges
    const cryptoB = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoB.initializeOrUnlock('VaultBPass#9999', generateFormattedSecretKey());
    const decryptedIncoming = await cryptoB.restoreSafePocketContainer(
      containerJson,
      'VaultAPass#2026',
      secretA
    );
    const localBefore = [
      { ...structuredClone(sample[0]), updatedAtEpochMs: 1_000, title: 'Older N26 Metal' },
      { ...structuredClone(sample[2]), updatedAtEpochMs: 9_000 }
    ];
    const mergeReport = mergeVaultSnapshots(localBefore, decryptedIncoming);
    expect(mergeReport.addedCount).toBe(1);
    expect(mergeReport.updatedCount).toBe(1);
    expect(mergeReport.unchangedCount).toBe(0);
    expect(mergeReport.merged).toHaveLength(3);
    expect(
      mergeReport.merged.find((i) => i.id === sample[0].id)?.title
    ).toBe('Updated N26 Metal');

    // Plaintext JSON & CSV export formatter
    const jsonExport = formatUnencryptedVaultExport(mergeReport.merged, 'json');
    expect(jsonExport.extension).toBe('json');
    expect(JSON.parse(jsonExport.content).exportedFrom).toBe(
      `SafePocket v${APP_BUILD_INFO.versionName}`
    );
    const csvExport = formatUnencryptedVaultExport(mergeReport.merged, 'csv');
    expect(csvExport.extension).toBe('csv');
    expect(csvExport.content).toContain('Updated N26 Metal');

    // Printable Emergency Kit v2 embeds QR SVG + Secret Key + optional hint, never Master Password
    const kitHtml = buildEmergencyKitHtml({
      vaultId: 'vault-v2-print',
      createdAtIso: '2026-11-16T12:00:00Z',
      secretKeyFormatted: secretA,
      kdf: cryptoA.currentKdfConfig,
      passwordHint: 'Childhood street in Munich'
    });
    expect(kitHtml).toContain('<svg');
    expect(kitHtml).toContain('aria-label="QR Code"');
    expect(kitHtml).toContain(secretA);
    expect(kitHtml).toContain('Childhood street in Munich');
    expect(kitHtml).not.toContain('VaultAPass#2026');
  });

  it('23. Custom Hex Card Color Picker (WCAG Luminance Contrast), Default Identity Visual Cards & Presentation Mode', () => {
    expect(normalizeHexColor('#abc')).toBe('#AABBCC');
    expect(normalizeHexColor('invalid', '#1E5B53')).toBe('#1E5B53');

    const lightCard = buildCustomCardStyle('#F8FAFC');
    expect(lightCard.isLightSurface).toBe(true);
    expect(lightCard.inkColor).toBe('#0f172a');
    expect(lightCard.inlineStyleAttr).toContain('background:linear-gradient(');
    expect(lightCard.inlineStyleAttr).toContain('--sp-card-ink:#0f172a;');

    const darkCard = buildCustomCardStyle('#101828');
    expect(darkCard.isLightSurface).toBe(false);
    expect(darkCard.inkColor).toBe('#f8fafc');
    expect(darkCard.inlineStyleAttr).toContain('--sp-card-ink:#f8fafc;');

    // Sample Identity item renders visual card by default (showVisualCard !== false)
    const sampleIdentity = createInitialSampleVaultItems().find(
      (i) => i.category === 'IDENTITY'
    );
    expect(sampleIdentity?.cardTheme?.showVisualCard).not.toBe(false);

    const css = fs.readFileSync(
      path.resolve(__dirname, '../styles/m3-expressive.css'),
      'utf-8'
    );
    expect(css).toContain(".sp-visual-card[data-palette='custom']");
    expect(css).toContain('.sp-presentation-overlay');
    expect(css).toContain('.sp-scanner-pad');
  });

  it('24. Cross-Platform Version 0.5.0-beta.3 (Build 18) Alignment, Permanent Release Keystore & Modal-Only UX', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const rootManifest = JSON.parse(
      fs.readFileSync(path.join(repoRoot, 'manifest/version.json'), 'utf-8')
    ) as RemoteUpdateManifest;
    const pwaManifest = JSON.parse(
      fs.readFileSync(path.join(repoRoot, 'pwaApp/public/version.json'), 'utf-8')
    ) as RemoteUpdateManifest;

    expect(APP_BUILD_INFO.versionName).toBe('0.5.0-beta.3');
    expect(APP_BUILD_INFO.versionCode).toBe(18);
    expect(rootManifest.latestVersion).toBe('0.5.0-beta.3');
    expect(rootManifest.versionCode).toBe(18);
    expect(pwaManifest.latestVersion).toBe('0.5.0-beta.3');
    expect(pwaManifest.versionCode).toBe(18);
    expect(rootManifest.artifacts['android-universal'].signingCertSha256).toBe(
      '5972aa8c113bfa8de1a02bda7b9ef86fb9304a617e7f1f9354d788640bd61a09'
    );

    const androidGradle = fs.readFileSync(
      path.join(repoRoot, 'androidApp/build.gradle.kts'),
      'utf-8'
    );
    expect(androidGradle).toContain('?: "0.5.0-beta.3"');
    expect(androidGradle).toContain('storeType = "PKCS12"');
    expect(androidGradle).toContain('enableV1Signing = true');
    expect(androidGradle).toContain('enableV2Signing = true');
    expect(androidGradle).toContain('enableV3Signing = true');

    const macAppSwift = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    expect(macAppSwift).toContain('SafePocket v0.5.0-beta.3 (Build 18)');

    // Verify legacy browser prompt dialog was eliminated in favor of Master-Password modal
    const appShellSource = fs.readFileSync(
      path.resolve(__dirname, '../ui/appShell.ts'),
      'utf-8'
    );
    expect(appShellSource).not.toContain('window.prompt');
  });

  it('25. Android ARB Localization Bundling & Default-Enabled Screenshot Toggle (Live FLAG_SECURE Option)', () => {
    const repoRoot = path.resolve(__dirname, '../../..');

    // 1) Verify Android Gradle bundles ../i18n ARB catalogs into APK assets
    const androidGradle = fs.readFileSync(
      path.join(repoRoot, 'androidApp/build.gradle.kts'),
      'utf-8'
    );
    expect(androidGradle).toContain('assets.srcDirs("src/main/assets", "../i18n")');

    // 2) Verify MainActivity loads bundled .arb.json catalogs (not emptyMap) and enables screenshots by default
    const mainActivityKt = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/MainActivity.kt'
      ),
      'utf-8'
    );
    expect(mainActivityKt).not.toContain('catalogsByCode = emptyMap()');
    expect(mainActivityKt).toContain('loadBundledArbCatalogs()');
    expect(mainActivityKt).toContain('${locale.code}.arb.json');
    expect(mainActivityKt).toContain('prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, true)');
    expect(mainActivityKt).toContain('window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)');
    expect(mainActivityKt).toContain('WindowManager.LayoutParams.FLAG_SECURE');

    // 3) Verify AdaptiveSafePocketShell renders Settings dialog with Allow Screenshots Switch & no raw 2-letter category codes
    const shellKt = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    expect(shellKt).toContain('security_allow_screenshots_toggle');
    expect(shellKt).toContain('security_allow_screenshots_desc');
    expect(shellKt).toContain('onToggleAllowScreenshots');
    expect(shellKt).not.toContain('cat.name.take(2)');

    // 4) Verify all 6 locales translate the screenshot setting
    const i18n = new SafePocketI18n('en-US');
    for (const locale of SUPPORTED_LOCALES) {
      i18n.setLocaleOverride(locale.code);
      expect(i18n.t('security_allow_screenshots_toggle')).not.toBe(
        'security_allow_screenshots_toggle'
      );
      expect(i18n.t('security_allow_screenshots_desc')).not.toBe(
        'security_allow_screenshots_desc'
      );
    }
  });
});





