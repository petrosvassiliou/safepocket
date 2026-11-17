import fs from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  generateFormattedSecretKey,
  ZeroKnowledgeVaultCrypto
} from '../crypto/vaultCrypto';
import {
  createInitialSampleVaultItems,
  getPrimaryCopyableField,
  isBuiltInSampleItem
} from '../data/sampleVault';
import { SqliteWasmVaultDb } from '../db/sqliteWasmVaultDb';
import {
  classifySafePocketError,
  SafePocketDiagnosticsCenter,
  scrubSensitiveDiagnosticText
} from '../error/errorHandler';
import { SafePocketI18n } from '../i18n/l10n';
import {
  FirebaseZeroKnowledgeAdapter,
  GoogleDriveAppDataAdapter,
  VaultSyncCoordinator
} from '../sync/syncAdapters';
import { APP_BUILD_INFO } from '../types';
import {
  analyzeAndFormatCardNumber,
  analyzeAndFormatIban,
  dropdownOptionsForField,
  extractDialCodeAndLocalPhone,
  formatInternationalPhone,
  normalizeHttpsUrl,
  suggestInstitutions,
  verifyWebsiteExists
} from '../ui/smartFieldIntelligence';

describe('SafePocket v0.3 (Build 5) Feature Suite', () => {
  beforeEach(() => {
    if (typeof localStorage !== 'undefined') {
      localStorage.clear();
    }
  });

  afterEach(() => {
    if (typeof localStorage !== 'undefined') {
      localStorage.clear();
    }
  });

  it('1. 6-Language Localization Catalog Parity (320 keys) & Category-Aware Bank Label Resolution', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const locales = ['en', 'de', 'it', 'fr', 'es', 'el'] as const;
    const catalogs = locales.map((loc) =>
      JSON.parse(
        fs.readFileSync(path.join(repoRoot, `i18n/${loc}.arb.json`), 'utf-8')
      ) as Record<string, string>
    );

    const enKeys = Object.keys(catalogs[0])
      .filter((k) => !k.startsWith('@'))
      .sort();
    expect(enKeys.length).toBeGreaterThanOrEqual(320);

    for (let i = 1; i < catalogs.length; i++) {
      const locKeys = Object.keys(catalogs[i])
        .filter((k) => !k.startsWith('@'))
        .sort();
      expect(locKeys).toEqual(enKeys);
    }

    const i18nDe = new SafePocketI18n('de');
    expect(
      i18nDe.resolveFieldLabel('field_issuing_bank', false, 'BANK_ACCOUNT')
    ).toBe('Bank / Kreditinstitut');
    expect(
      i18nDe.resolveFieldLabel('field_issuing_bank', false, 'PAYMENT_CARD')
    ).toBe('Kartenausgebende Bank');
  });

  it('2. Website Auto-HTTPS Prefixing & Live Existence Probe (Direct + DNS-over-HTTPS Fallback)', async () => {
    expect(normalizeHttpsUrl('ing.de')).toBe('https://ing.de');
    expect(normalizeHttpsUrl('http://www.deutsche-bank.de/')).toBe(
      'https://www.deutsche-bank.de'
    );
    expect(normalizeHttpsUrl('  https://N26.com/login ')).toBe(
      'https://n26.com/login'
    );

    const reachableResult = await verifyWebsiteExists('ing.de', (async () =>
      new Response('', { status: 200 })) as typeof fetch);
    expect(reachableResult.status).toBe('reachable');
    expect(reachableResult.normalizedUrl).toBe('https://ing.de');

    const dnsFallbackResult = await verifyWebsiteExists(
      'commerzbank.de',
      (async (input: RequestInfo | URL) => {
        const urlStr = String(input);
        if (urlStr.includes('dns.google')) {
          return new Response(
            JSON.stringify({ Status: 0, Answer: [{ data: '195.200.34.1' }] }),
            { status: 200 }
          );
        }
        throw new Error('CORS blocked head probe');
      }) as typeof fetch
    );
    expect(dnsFallbackResult.status).toBe('reachable');

    const unreachableResult = await verifyWebsiteExists(
      'nonexistent-bank-domain-example.invalid',
      (async () => {
        throw new Error('ENOTFOUND');
      }) as typeof fetch
    );
    expect(unreachableResult.status).toBe('unreachable');
  });

  it('3. Smart Field Dropdowns, International Phone Prefixing, IBAN Bank Autofill & Luhn PAN Formatting', () => {
    const i18nDe = new SafePocketI18n('de');
    const bankAccountTypes = dropdownOptionsForField(
      'field_account_type',
      'BANK_ACCOUNT',
      i18nDe
    );
    expect(bankAccountTypes).not.toBeNull();
    expect(bankAccountTypes!.length).toBeGreaterThanOrEqual(8);
    expect(bankAccountTypes!.some((o) => o.label.includes('Girokonto'))).toBe(
      true
    );

    expect(
      dropdownOptionsForField('field_card_network', 'PAYMENT_CARD', i18nDe)
        ?.length
    ).toBeGreaterThanOrEqual(6);
    expect(
      dropdownOptionsForField('field_card_type', 'PAYMENT_CARD', i18nDe)?.length
    ).toBeGreaterThanOrEqual(5);
    expect(
      dropdownOptionsForField('field_currency', 'BANK_ACCOUNT', i18nDe)?.length
    ).toBeGreaterThanOrEqual(6);

    // Phone number formatting with international prefix
    expect(formatInternationalPhone('015123456789', '+49')).toBe(
      '+49 151 2345 6789'
    );
    expect(formatInternationalPhone('6971234567', '+30')).toBe(
      '+30 697 1234 567'
    );
    const extracted = extractDialCodeAndLocalPhone('+306971234567', '+49');
    expect(extracted.dialCode).toBe('+30');
    expect(extracted.formattedFull).toBe('+30 697 1234 567');

    // IBAN Mod-97 + German BLZ cross-field autofill (Commerzbank 37040044 & Deutsche Bank 50070010)
    const commerzIban = analyzeAndFormatIban('de89370400440532013000');
    expect(commerzIban.formattedIban).toBe('DE89 3704 0044 0532 0130 00');
    expect(commerzIban.isValidChecksum).toBe(true);
    expect(commerzIban.inferredBankName).toBe('Commerzbank AG');
    expect(commerzIban.inferredBic).toBe('COBADEFFXXX');
    expect(commerzIban.inferredWebsite).toBe('https://www.commerzbank.de');
    expect(commerzIban.inferredCurrency).toBe('EUR');

    const dbIban = analyzeAndFormatIban('DE12500700100532013000');
    expect(dbIban.inferredBankName).toBe('Deutsche Bank AG');
    expect(dbIban.inferredBic).toBe('DEUTDEFFXXX');
    expect(dbIban.inferredWebsite).toBe('https://www.deutsche-bank.de');

    // Institution autocomplete suggestions
    const dbHits = suggestInstitutions('deutsche');
    expect(dbHits.length).toBeGreaterThanOrEqual(1);
    expect(dbHits[0].defaultBic).toBe('DEUTDEFFXXX');

    // Card PAN formatting + Luhn
    const visaAnalysis = analyzeAndFormatCardNumber('4539007512844918');
    expect(visaAnalysis.formattedPan).toBe('4539 0075 1284 4918');
    expect(visaAnalysis.networkBrand.toUpperCase()).toBe('VISA');
  });

  it('4. Built-in Example Data Detection & Primary Copyable Field Selection', () => {
    const samples = createInitialSampleVaultItems();
    expect(samples.length).toBeGreaterThan(0);
    for (const sample of samples) {
      expect(isBuiltInSampleItem(sample)).toBe(true);
      expect(getPrimaryCopyableField(sample)).not.toBeNull();
    }

    const customItem = {
      ...samples[0],
      id: 'item-user-created-999',
      isSampleItem: false
    };
    expect(isBuiltInSampleItem(customItem)).toBe(false);
  });

  it('5. Mutually Exclusive Storage Mode (LOCAL_ONLY XOR CLOUD_SYNC) & Zero-Input Offline Outbox Sync', async () => {
    const sqlite = new SqliteWasmVaultDb(false);
    await sqlite.ensureInitialized();

    const firebaseAdapter = new FirebaseZeroKnowledgeAdapter(sqlite);
    const gdriveAdapter = new GoogleDriveAppDataAdapter();
    const coordinator = new VaultSyncCoordinator(firebaseAdapter, gdriveAdapter);

    expect(coordinator.storageMode).toBe('LOCAL_ONLY');
    expect(coordinator.indicatorSnapshot.phase).toBe('LOCAL_ENCRYPTED');

    // Seed standalone local encrypted row
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 1024,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock(
      'StrongPass!2026',
      generateFormattedSecretKey()
    );
    const sample = createInitialSampleVaultItems()[0];
    const row = await cryptoEngine.encryptVaultItem(
      sample,
      '00001794870000000:0001:node-a',
      { 'node-a': 1 }
    );

    await sqlite.saveEncryptedRow(row);
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);

    // Switch exclusively to CLOUD_SYNC -> standalone local DB must be purged, cloud mirror active
    await sqlite.saveCloudMirrorRow(row);
    await sqlite.purgeStandaloneLocalVault();
    coordinator.setStorageMode('CLOUD_SYNC');

    expect(coordinator.storageMode).toBe('CLOUD_SYNC');
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(0);
    expect((await sqlite.loadActiveCloudMirrorRows()).length).toBe(1);

    // Go offline in CLOUD_SYNC mode -> indicator automatically shows CLOUD_OFFLINE_READY
    coordinator.setOnlineState(false);
    expect(coordinator.indicatorSnapshot.phase).toBe('CLOUD_OFFLINE_READY');

    // Queue an offline mutation while disconnected (zero user input required)
    const offlineMutationRow = {
      ...row,
      hlcPacked: '00001794870005000:0002:node-a',
      versionVector: { 'node-a': 2 }
    };
    await sqlite.saveCloudMirrorRow(offlineMutationRow);
    await sqlite.enqueueCloudMutationOutboxRow(offlineMutationRow);
    const queued = await sqlite.listCloudMutationOutboxRows();
    expect(queued.length).toBe(1);

    // Reconnect online -> automatic reconciliation pushes outbox deltas and clears outbox
    coordinator.setOnlineState(true);
    const syncSummary = await coordinator.syncBidirectionalIfEnabled(
      'vault-exclusive-test',
      await sqlite.loadAllCloudMirrorRowsIncludingTombstones(),
      null
    );
    expect(syncSummary).not.toBeNull();
    await sqlite.removeCloudMutationOutboxRows(queued.map((q) => q.id));
    coordinator.updateRuntimeSyncTelemetry({
      queuedMutationsCount: 0,
      lastSyncedIso: new Date().toISOString()
    });

    expect(syncSummary!.pushedCount).toBe(1);
    expect((await sqlite.listCloudMutationOutboxRows()).length).toBe(0);
    expect(coordinator.indicatorSnapshot.phase).toBe('CLOUD_SYNCED');
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(0);

    // Switch back to LOCAL_ONLY -> cloud mirror & outbox are completely wiped
    await sqlite.purgeCloudSessionMirrorAndOutbox();
    coordinator.setStorageMode('LOCAL_ONLY');
    expect(coordinator.storageMode).toBe('LOCAL_ONLY');
    expect(
      (await sqlite.loadAllCloudMirrorRowsIncludingTombstones()).length
    ).toBe(0);
  });

  it('6. Extensive Error Handling & Privacy-Preserving Secret Scrubbing', () => {
    const rawLeak =
      'Failed to sync DE89 3704 0044 0532 0130 00 with card 4539 0075 1284 4918 and key SPK-ABCD-1234-EFGH-5678-IJKL Bearer secretToken999';
    const scrubbed = scrubSensitiveDiagnosticText(rawLeak);
    expect(scrubbed).not.toContain('DE89 3704');
    expect(scrubbed).not.toContain('4539 0075');
    expect(scrubbed).not.toContain('SPK-ABCD');
    expect(scrubbed).not.toContain('secretToken999');
    expect(scrubbed).toContain('[IBAN-REDACTED]');
    expect(scrubbed).toContain('[PAN-REDACTED]');
    expect(scrubbed).toContain('SPK-[REDACTED]');

    const center = new SafePocketDiagnosticsCenter();
    const classified = center.recordError(
      new Error('Ed25519 signature verification failed for manifest'),
      'autoUpdater.check'
    );
    expect(classified.category).toBe('AUTO_UPDATE');
    expect(center.getEntries().length).toBe(1);
    expect(classifySafePocketError('SQLite quota full', 'db.write').category).toBe(
      'DATABASE_STORAGE'
    );
  });

  it('7. Cross-Platform v0.3.1 Architecture Verification (Android, Googlebook OS, macOS & PWA)', () => {
    expect(APP_BUILD_INFO.versionName).toBe('0.3.1');
    expect(APP_BUILD_INFO.versionCode).toBe(6);

    const repoRoot = path.resolve(__dirname, '../../..');

    // Verify Android ripple elimination, bottom-right sync pill, swipe & right-click context menu, smart fields & Play Protect updater
    const smoothComponents = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/SmoothInteractiveComponents.kt'
      ),
      'utf-8'
    );
    expect(smoothComponents).toContain('LocalRippleConfiguration provides null');

    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    expect(androidShell).toContain('ProvideSeamFreeHoverIndication');
    expect(androidShell).toContain('detectHorizontalDragGestures');
    expect(androidShell).toContain('PointerEventType.Press');
    expect(androidShell).toContain('isSecondaryPressed');
    expect(androidShell).toContain('onDeleteAllExampleData');
    expect(androidShell).toContain('Alignment.BottomEnd');

    const androidSmartFields = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/SmartFieldInputComposable.kt'
      ),
      'utf-8'
    );
    expect(androidSmartFields).toContain('WebsiteReachabilityResult');
    expect(androidSmartFields).toContain('COUNTRY_DIAL_CODES');
    expect(androidSmartFields).toContain('analyzeAndFormatIban');

    const androidRepo = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/data/AndroidVaultRepository.kt'
      ),
      'utf-8'
    );
    expect(androidRepo).toContain('verifyWebsiteExists');
    expect(androidRepo).toContain('VaultStorageMode');

    const sharedUpdater = fs.readFileSync(
      path.join(
        repoRoot,
        'shared/src/commonMain/kotlin/com/safepocket/shared/update/CrossPlatformUpdateService.kt'
      ),
      'utf-8'
    );
    expect(sharedUpdater).toContain('Ed25519VerifierPure.verify');

    const playProtect = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/update/PlayProtectPackageInstaller.kt'
      ),
      'utf-8'
    );
    expect(playProtect).toContain('canInstallPackages');
    expect(playProtect).toContain('verifyApkSigningCertificate');
    expect(playProtect).toContain('INSTALL_REASON_USER');

    // Verify macOS CryptoKit Ed25519 in-app updater, NWPathMonitor exclusive cloud offline sync, swipe & context menus
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    expect(macApp).toContain('SafePocket v0.3.1 (Build 6)');
    expect(macApp).toContain('Curve25519.Signing.PublicKey');
    expect(macApp).toContain('NWPathMonitor');
    expect(macApp).toContain('MacExclusiveStorageMode');
    expect(macApp).toContain('deleteAllExampleData');

    const macSplit = fs.readFileSync(
      path.join(
        repoRoot,
        'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'
      ),
      'utf-8'
    );
    expect(macSplit).toContain('.swipeActions');
    expect(macSplit).toContain('.contextMenu');
    expect(macSplit).toContain('MacSmartItemEditorSheet');

    // Verify studio icon assets exist across PWA, Android, and macOS
    expect(
      fs.existsSync(path.join(repoRoot, 'pwaApp/public/icons/icon-512.png'))
    ).toBe(true);
    expect(
      fs.existsSync(path.join(repoRoot, 'macosApp/Resources/AppIcon-1024.png'))
    ).toBe(true);
    expect(
      fs.existsSync(
        path.join(
          repoRoot,
          'androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png'
        )
      )
    ).toBe(true);
  });

  it('8. 100% Cross-Platform Settings Parity, Clean Radio Buttons & macOS Cloud Sync Login/Config + 6-Language Bundling', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const locales = ['en', 'de', 'it', 'fr', 'es', 'el'] as const;

    // 1) Zero "(One or the Other — Never Both)" or "(Exclusive)" wording in any ARB catalog or platform UI
    for (const loc of locales) {
      const arbText = fs.readFileSync(
        path.join(repoRoot, `i18n/${loc}.arb.json`),
        'utf-8'
      );
      expect(arbText).not.toContain('One or the Other');
      expect(arbText).not.toContain('Never Both');
      expect(arbText).not.toContain('(Exclusive)');
      expect(arbText).not.toContain('(Exklusiv)');
    }

    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macCloud = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/CloudSyncAndPortability.swift'),
      'utf-8'
    );
    const macL10n = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/LocalizationAndModels.swift'),
      'utf-8'
    );
    const macDmgScript = fs.readFileSync(
      path.join(repoRoot, 'scripts/package-macos-dmg.sh'),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );

    expect(macSplit).not.toContain('One or the Other');
    expect(macSplit).not.toContain('Never Both');
    expect(androidShell).not.toContain('One or the Other');
    expect(pwaShell).not.toContain('One or the Other');

    // 2) Explicit Radio Buttons for Storage Mode across macOS, Android, and PWA
    expect(macSplit).toContain('.pickerStyle(.radioGroup)');
    expect(androidShell).toContain('RadioButton(');
    expect(pwaShell).toContain('role="radio"');

    // 3) Full Cloud Sync Login, Sign-Up, Provider Selection & Endpoint Config on macOS & Android
    expect(macCloud).toContain('safepocket-firebase-auth-verifier-v1');
    expect(macCloud).toContain('identitytoolkit.googleapis.com/v1/accounts:');
    expect(macCloud).toContain('documents:commit');
    expect(macSplit).toContain('signInFirebaseAccount');
    expect(macSplit).toContain('createFirebaseAccount');
    expect(macSplit).toContain('cloud_config_title');
    expect(macSplit).toContain('cloud_firebase_project_id');
    expect(macSplit).toContain('cloud_firebase_api_key');
    expect(androidShell).toContain('cloud_config_title');
    expect(androidShell).toContain('cloud_firebase_project_id');

    // 4) 6-Language Selector & ARB Bundling on macOS + Full Settings Parity (Accent, Portability, Screenshots, Expiry, Auto-Lock)
    expect(macL10n).toContain('MacLocalizationEngine');
    expect(macSplit).toContain('settings_language_title');
    expect(macSplit).toContain('MacSupportedLocale.allCases');
    expect(macDmgScript).toContain('Contents/Resources/i18n');
    expect(macApp).toContain('sharingType');
    expect(macSplit).toContain('security_allow_screenshots_toggle');
    expect(macSplit).toContain('expiry_window_setting_title');
    expect(macSplit).toContain('security_auto_lock');
    expect(macSplit).toContain('MacMaterialAccent.allCases');
    expect(macSplit).toContain('backup_export_encrypted');
    expect(macSplit).toContain('import_1password_btn');
    expect(macSplit).toContain('export_1password_1pux_btn');
    expect(androidShell).toContain('material_color_emerald');
    expect(androidShell).toContain('expiry_window_setting_title');
    expect(androidShell).toContain('security_auto_lock');
    expect(androidShell).toContain('export_json_btn');
  });
});
