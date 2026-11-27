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
import { SafePocketI18n, SUPPORTED_LOCALES } from '../i18n/l10n';
import { IdleAutoLockMonitor } from '../security/autoLockMonitor';
import {
  deriveZeroKnowledgeCloudSyncKeyHex,
  firebaseRefreshIdTokenRest,
  inspectFirebaseJwtClaims
} from '../sync/liveCloudConnectors';
import {
  type CloudCipherBridge,
  EncryptedLocalVaultDb,
  FirebaseZeroKnowledgeAdapter,
  GoogleDriveAppDataAdapter,
  HybridClock,
  normalizeHlcPacked,
  VaultSyncCoordinator
} from '../sync/syncAdapters';
import { APP_BUILD_INFO, type VaultItem } from '../types';
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

describe('SafePocket v0.6.0-beta.1 (Build 21) Feature Suite', () => {
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

  it('5. Seamless Lokale DB + Cloud-Sync Toggle (Preserves Local DB & Session) & Zero-Input Offline Outbox Sync', async () => {
    const sqlite = new SqliteWasmVaultDb(false);
    await sqlite.ensureInitialized();

    const firebaseAdapter = new FirebaseZeroKnowledgeAdapter(sqlite);
    const gdriveAdapter = new GoogleDriveAppDataAdapter();
    const coordinator = new VaultSyncCoordinator(firebaseAdapter, gdriveAdapter);
    const db = new EncryptedLocalVaultDb(sqlite);
    db.bindSyncCoordinator(coordinator);

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

    await db.saveEncryptedRow(row);
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);

    // Switch to CLOUD_SYNC while offline -> Local DB remains intact AND cloud mirror/outbox receive snapshot
    coordinator.setOnlineState(false);
    await db.switchExclusiveStorageMode(
      'CLOUD_SYNC',
      [row],
      'vault-exclusive-test',
      'FIREBASE:test-uid'
    );

    expect(coordinator.storageMode).toBe('CLOUD_SYNC');
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);
    expect((await sqlite.loadActiveCloudMirrorRows()).length).toBe(1);
    expect(coordinator.indicatorSnapshot.phase).toBe('CLOUD_OFFLINE_READY');

    // Queue an offline mutation while disconnected (updates both local DB and cloud mirror/outbox)
    const offlineMutationRow = {
      ...row,
      hlcPacked: '00001794870005000:0002:node-a',
      versionVector: { 'node-a': 2 }
    };
    await db.saveEncryptedRow(offlineMutationRow);
    const queued = await sqlite.listCloudMutationOutboxRows();
    expect(queued.length).toBe(1);
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);

    // Reconnect online -> automatic reconciliation pushes outbox deltas and clears outbox
    coordinator.setOnlineState(true);
    const syncSummary = await db.reconcileCloudSessionAutomatically(
      'vault-exclusive-test',
      'FIREBASE:test-uid'
    );
    expect(syncSummary).not.toBeNull();
    expect(syncSummary!.pushedCount).toBe(1);
    expect((await sqlite.listCloudMutationOutboxRows()).length).toBe(0);
    expect(coordinator.indicatorSnapshot.phase).toBe('CLOUD_SYNCED');
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);

    // Pause Cloud-Sync by toggling back to LOCAL_ONLY -> local DB & cloud mirror stay intact!
    await db.switchExclusiveStorageMode(
      'LOCAL_ONLY',
      [offlineMutationRow],
      'vault-exclusive-test',
      'FIREBASE:test-uid'
    );
    expect(coordinator.storageMode).toBe('LOCAL_ONLY');
    expect(coordinator.indicatorSnapshot.phase).toBe('LOCAL_ENCRYPTED');
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);
    expect((await sqlite.loadActiveCloudMirrorRows()).length).toBe(1);

    // Only explicit sign-out purges cloud mirror & outbox while keeping local DB intact
    await sqlite.purgeCloudSessionMirrorAndOutbox();
    expect(
      (await sqlite.loadAllCloudMirrorRowsIncludingTombstones()).length
    ).toBe(0);
    expect(await sqlite.countStandaloneLocalVaultRows()).toBe(1);
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

  it('7. Cross-Platform v0.6.0-beta.1 Architecture Verification (Android, Googlebook OS, macOS & PWA)', () => {
    expect(APP_BUILD_INFO.versionName).toBe('0.6.0-beta.1');
    expect(APP_BUILD_INFO.versionCode).toBe(21);

    const repoRoot = path.resolve(__dirname, '../../..');

    // Verify Android ripple elimination, bottom-right sync pill, swipe & right-click context menu, smart fields & Play Protect compliance
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
    expect(androidShell).toContain('BottomRightSyncStatusPill');
    expect(androidShell).toContain('Arrangement.End');

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

    const androidManifest = fs.readFileSync(
      path.join(repoRoot, 'androidApp/src/main/AndroidManifest.xml'),
      'utf-8'
    );
    expect(androidManifest).not.toContain('REQUEST_INSTALL_PACKAGES');
    expect(androidManifest).not.toContain('PackageInstallerStatusReceiver');

    const playProtect = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/update/PlayProtectPackageInstaller.kt'
      ),
      'utf-8'
    );
    expect(playProtect).toContain('Intent.ACTION_VIEW');
    expect(playProtect).toContain(
      'https://github.com/petrosvassiliou/safepocket/releases/'
    );
    expect(playProtect).not.toContain('android.content.pm.PackageInstaller');

    // Verify macOS CryptoKit Ed25519 in-app updater, NWPathMonitor exclusive cloud offline sync, live Dock icon refresh, swipe & context menus
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    expect(macApp).toContain('SafePocket v0.6.0-beta.1 (Build 21)');
    expect(macApp).toContain('NSApplication.shared.applicationIconImage = appIcon');
    expect(macApp).toContain('noteFileSystemChanged(destApp.path)');
    expect(macApp).toContain('Curve25519.Signing.PublicKey');
    expect(macApp).toContain('NWPathMonitor');
    expect(macApp).toContain('MacExclusiveStorageMode');
    expect(macApp).toContain('deleteAllExampleData');

    // Verify macOS AppIcon-1024.png exists with exact 1024x1024 IHDR header
    const macIconBytes = fs.readFileSync(
      path.join(repoRoot, 'macosApp/Resources/AppIcon-1024.png')
    );
    expect(macIconBytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(macIconBytes.readUInt32BE(16)).toBe(1024);
    expect(macIconBytes.readUInt32BE(20)).toBe(1024);

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

  it('9. macOS Foreground App Activation, Bordered Text Inputs Everywhere & Active Non-Grayed-Out Buttons', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
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

    // 1) SwiftPM executable foreground GUI activation & Info.plist NSPrincipalClass
    expect(macApp).toContain('SafePocketMacAppDelegate');
    expect(macApp).toContain('setActivationPolicy(.regular)');
    expect(macApp).toContain('makeKeyAndOrderFront');
    expect(macDmgScript).toContain('<key>NSPrincipalClass</key>');
    expect(macDmgScript).toContain('<string>NSApplication</string>');

    // 2) Zero .formStyle(.grouped) anywhere in macOS views; explicit .textFieldStyle(.roundedBorder) everywhere
    expect(macSplit).not.toContain('.formStyle(.grouped)');
    expect(macSplit).toContain('MacLabeledTextField');
    expect(macSplit).toContain('MacLabeledSecureField');
    expect(macSplit).toContain('.textFieldStyle(.roundedBorder)');

    // 3) Login, Create Account, Backup & Security buttons never pre-disabled on empty input strings
    expect(macSplit).not.toContain('!cloudEmailInput.contains("@")');
    expect(macSplit).not.toContain('cloudPasswordInput.count < 6');
    expect(androidShell).not.toContain('&& cloudEmail.contains("@")');
  });

  it('10. v0.3.3 Display-String Hygiene & Cross-Repo ARB Completeness (Zero Raw Enum Subtitles or Unlocalized Keys)', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const locales = ['en', 'de', 'it', 'fr', 'es', 'el'] as const;
    const catalogs = locales.map(
      (loc) =>
        JSON.parse(
          fs.readFileSync(path.join(repoRoot, `i18n/${loc}.arb.json`), 'utf-8')
        ) as Record<string, string>
    );

    const enKeySet = new Set(
      Object.keys(catalogs[0]).filter((k) => !k.startsWith('@'))
    );
    expect(enKeySet.size).toBeGreaterThanOrEqual(367);
    for (const cat of catalogs) {
      const locKeys = Object.keys(cat).filter((k) => !k.startsWith('@'));
      expect(locKeys.length).toBe(enKeySet.size);
      for (const k of enKeySet) {
        expect(typeof cat[k]).toBe('string');
        expect(cat[k].trim().length).toBeGreaterThan(0);
      }
    }

    // Collect every literal localization key referenced via .t('...') / .t("...") or labelKey across Swift, Kotlin, and TypeScript
    const walkFiles = (dir: string, exts: string[]): string[] => {
      if (!fs.existsSync(dir)) return [];
      const out: string[] = [];
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name === 'node_modules' || entry.name === 'dist' || entry.name === '__tests__') {
            continue;
          }
          out.push(...walkFiles(full, exts));
        } else if (exts.some((ext) => entry.name.endsWith(ext))) {
          out.push(full);
        }
      }
      return out;
    };

    const sourceFiles = [
      ...walkFiles(path.join(repoRoot, 'macosApp'), ['.swift']),
      ...walkFiles(path.join(repoRoot, 'androidApp/src'), ['.kt']),
      ...walkFiles(path.join(repoRoot, 'shared/src'), ['.kt']),
      ...walkFiles(path.join(repoRoot, 'pwaApp/src'), ['.ts'])
    ];

    const missingKeys: string[] = [];
    const literalTRegex = /\.t\(\s*['"]([a-z0-9_]+)['"]/g;
    const literalPluralRegex = /\.plural\(\s*['"]([a-z0-9_]+)['"]/g;
    const fieldKeyRegex = /['"](field_[a-z0-9_]+)['"]/g;
    for (const file of sourceFiles) {
      const content = fs.readFileSync(file, 'utf-8');
      for (const match of content.matchAll(literalTRegex)) {
        const key = match[1];
        if (!enKeySet.has(key)) {
          missingKeys.push(`${path.relative(repoRoot, file)} -> ${key}`);
        }
      }
      for (const match of content.matchAll(literalPluralRegex)) {
        const base = match[1];
        if (!enKeySet.has(`${base}_one`) || !enKeySet.has(`${base}_other`)) {
          missingKeys.push(`${path.relative(repoRoot, file)} -> plural:${base}`);
        }
      }
      for (const match of content.matchAll(fieldKeyRegex)) {
        const key = match[1];
        if (!enKeySet.has(key)) {
          missingKeys.push(`${path.relative(repoRoot, file)} -> ${key}`);
        }
      }
    }
    expect(missingKeys).toEqual([]);

    // Verify raw internal enum names (e.g. BANK_ACCOUNT) resolve to localized display strings
    const i18n = new SafePocketI18n();
    i18n.setLocaleOverride('en');
    expect(i18n.resolveDisplaySubtitle('BANK_ACCOUNT', 'BANK_ACCOUNT')).toBe(
      'Bank Account'
    );
    expect(i18n.resolveDisplaySubtitle('', 'OFFICIAL_DOCUMENT')).toBe(
      'Official Document'
    );
    expect(i18n.resolveDisplaySubtitle('Personal Checking', 'BANK_ACCOUNT')).toBe(
      'Personal Checking'
    );

    i18n.setLocaleOverride('de');
    expect(i18n.resolveDisplaySubtitle('BANK_ACCOUNT', 'BANK_ACCOUNT')).toBe(
      'Bankkonto'
    );
    expect(i18n.resolveCategorySingularName('PAYMENT_CARD')).toBe(
      'Zahlungskarte'
    );
  });

  it('11. v0.3.3 Modal Settings, Solid Red Swipe-Delete, Flag Language Selector & Standby/Never Lock Parity', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );

    // 1) Modal Settings Window from Top Bar (no redundant sidebar Settings item) across PWA, macOS, and Android
    expect(pwaShell).toContain('id="sp-settings-modal-dialog"');
    expect(pwaShell).toContain('this.settingsModalOpen = true');
    expect(pwaShell).not.toContain("this.renderNavBtn('SETTINGS'");
    expect(macSplit).toContain('.sheet(isPresented: $vaultState.presentSettingsSheet)');
    expect(macSplit).toContain('MacSettingsModalWindow');
    expect(macSplit).toContain('Label(vaultState.t("nav_settings"), systemImage: "gearshape")');
    expect(macSplit).not.toContain('Label(vaultState.t("nav_settings"), systemImage: "gearshape.fill")');
    expect(androidShell).toContain('SafePocketSettingsDialog(');
    expect(androidShell).not.toContain('⚙  ${i18n.t("nav_settings")}');

    // 2) Solid Red Swipe-to-Delete across PWA, macOS, and Android
    expect(pwaCss).toContain('#dc2626');
    expect(pwaShell).toContain("this.i18n.t('action_delete')");
    expect(macSplit).toContain('.tint(.red)');
    expect(androidShell).toContain('Color(0xFFDC2626)');

    // 3) Country Flags in Main-App Language Selector across all 6 languages
    const expectedFlags = ['🇬🇧', '🇩🇪', '🇮🇹', '🇫🇷', '🇪🇸', '🇬🇷'];
    expect(SUPPORTED_LOCALES.map((l) => l.flag)).toEqual(expectedFlags);
    expect(pwaShell).toContain('id="sp-main-lang-select"');
    expect(macSplit).toContain('MacFlagLanguageMenu');
    expect(androidShell).toContain('MainAppFlagLanguageSelector');

    // 4) Standby / System Sleep Lock + Never Lock (0m) across PWA, macOS, and Android
    expect(macApp).toContain('NSWorkspace.willSleepNotification');
    expect(macSplit).toContain('security_auto_lock_never');
    expect(androidShell).toContain('Intent.ACTION_SCREEN_OFF');
    expect(androidShell).toContain('security_auto_lock_never');
    expect(pwaShell).toContain('id="sp-toggle-lock-standby"');
    expect(pwaShell).toContain("this.i18n.t('security_auto_lock_never')");

    // Verify runtime IdleAutoLockMonitor behavior for Never (0) + Standby Lock
    let lockCount = 0;
    const monitor = new IdleAutoLockMonitor(() => {
      lockCount += 1;
    });

    monitor.setMinutes(5);
    expect(monitor.hasPendingIdleTimer).toBe(true);

    // Setting minutes to 0 (Never) clears any pending idle timeout timer
    monitor.setMinutes(0);
    expect(monitor.minutes).toBe(0);
    expect(monitor.hasPendingIdleTimer).toBe(false);
    expect(lockCount).toBe(0);

    // Standby lock enabled by default -> simulated standby locks immediately even when idle timer is Never (0)
    expect(monitor.lockOnStandby).toBe(true);
    expect(monitor.handleSystemStandbyEvent()).toBe(true);
    expect(lockCount).toBe(1);

    // Disabling standby lock prevents sleep/standby from locking vault
    monitor.setLockOnStandby(false);
    expect(monitor.lockOnStandby).toBe(false);
    expect(monitor.handleSystemStandbyEvent()).toBe(false);
    expect(lockCount).toBe(1);
    monitor.stop();
  });

  it('12. v0.3.5 Crisp 3-Column UI Alignment & Compact Non-Overlapping Bottom Cloud-Sync Status Bar', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );

    // 1) macOS unified compact toolbar + native root List sidebar/list + safeAreaInset non-overlapping status bar
    expect(macApp).toContain('.windowToolbarStyle(.unifiedCompact(showsTitle: true))');
    expect(macApp).toContain('var selectedCategoryTitle: String');
    expect(macApp).toContain('var cloudProviderDisplayName: String');
    expect(macSplit).not.toContain('ZStack(alignment: .bottomTrailing)');
    expect(macSplit).not.toContain('Section(vaultState.t("app_name"))');
    expect(macSplit).toContain('.navigationTitle(vaultState.selectedCategoryTitle)');
    expect(macSplit).toContain('macBottomStatusFooter');
    expect(macSplit).toContain('.font(.system(size: 10.5, weight: .medium))');

    // 2) PWA dedicated non-overlapping bottom status bar + compact 10.5px pill + aligned 34px toolbar controls
    expect(pwaShell).toContain('class="sp-bottom-status-bar"');
    expect(pwaShell).toContain("? 'Google Drive'");
    expect(pwaCss).toContain('.sp-bottom-status-bar');
    expect(pwaCss).toContain('position: static;');
    expect(pwaCss).toContain('font-size: 10.5px;');
    expect(pwaCss).toContain('min-height: 34px;');

    // 3) Android dedicated non-overlapping bottom status bar + compact 10.5.sp zero-shadow status pill
    expect(androidShell).not.toContain('.align(Alignment.BottomEnd)');
    expect(androidShell).toContain('fontSize = 10.5.sp');
    expect(androidShell).toContain('shadowElevation = 0.dp');
  });

  it('13. v0.3.6 Flush Bottom-Left Sidebar Controls & Firebase JSON RefreshToken + HTTP 401 Auto-Retry Recovery', async () => {
    const repoRoot = path.resolve(__dirname, '../../..');

    // 1) Base64URL JWT claim inspection + JSON refreshToken preservation of +, /, and = characters
    const fakeHeader = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url');
    const fakePayload = Buffer.from(
      JSON.stringify({ aud: 'safepocket-vault-eu', exp: 1999999999 })
    ).toString('base64url');
    const fakeJwt = `${fakeHeader}.${fakePayload}.sig`;
    const claims = inspectFirebaseJwtClaims(fakeJwt);
    expect(claims?.aud).toBe('safepocket-vault-eu');
    expect(claims?.exp).toBe(1999999999);

    const trickyRefreshToken = 'AEu4IL2+abc/def==+xyz/99=';
    let capturedContentType = '';
    let capturedBodyText = '';
    const refreshed = await firebaseRefreshIdTokenRest(
      'AIzaTestKey',
      trickyRefreshToken,
      (async (_url, init) => {
        capturedContentType = String(
          (init?.headers as Record<string, string>)?.['Content-Type'] ?? ''
        );
        capturedBodyText = String(init?.body ?? '');
        return new Response(
          JSON.stringify({
            id_token: fakeJwt,
            refresh_token: trickyRefreshToken,
            user_id: 'uid-googlebook-1'
          }),
          { status: 200 }
        );
      }) as typeof fetch
    );
    expect(capturedContentType).toBe('application/json');
    expect(JSON.parse(capturedBodyText).refresh_token).toBe(trickyRefreshToken);
    expect(refreshed.idToken).toBe(fakeJwt);

    // 2) FirebaseZeroKnowledgeAdapter automatically refreshes once on HTTP 401 and succeeds on retry
    const cryptoEngine = new ZeroKnowledgeVaultCrypto({
      memorySizeKiB: 2048,
      iterations: 1,
      parallelism: 1
    });
    await cryptoEngine.initializeOrUnlock('retry-pw', generateFormattedSecretKey());
    const sample = createInitialSampleVaultItems()[0];
    const encRow = await cryptoEngine.encryptVaultItem(
      sample,
      '001763300001000-0000-googlebook',
      { googlebook: 1 }
    );

    const seenAuthHeaders: string[] = [];
    const adapter = new FirebaseZeroKnowledgeAdapter(
      undefined,
      (async (_url, init) => {
        const auth = String(
          (init?.headers as Record<string, string>)?.Authorization ?? ''
        );
        seenAuthHeaders.push(auth);
        if (auth === 'Bearer expired-jwt') {
          return new Response(
            JSON.stringify({
              error: { code: 401, message: 'Request had invalid authentication credentials.' }
            }),
            { status: 401 }
          );
        }
        return new Response(JSON.stringify({ writeResults: [{}] }), { status: 200 });
      }) as typeof fetch
    );
    adapter.setLiveEndpointConfig({
      firebaseProjectId: 'safepocket-vault-eu',
      firebaseUserId: 'uid-googlebook-1',
      bearerToken: 'expired-jwt'
    });
    adapter.setRefreshTokenCallback(async () => 'fresh-renewed-jwt');

    const pushRes = await adapter.pushEncryptedDeltas('default-vault', [encRow]);
    expect(pushRes.pushedCount).toBe(1);
    expect(seenAuthHeaders).toEqual(['Bearer expired-jwt', 'Bearer fresh-renewed-jwt']);

    // 3) Cross-platform flush bottom-left sidebar controls & JSON securetoken refresh in Kotlin + Swift
    const ktAuth = fs.readFileSync(
      path.join(
        repoRoot,
        'shared/src/commonMain/kotlin/com/safepocket/shared/sync/FirebaseAuthService.kt'
      ),
      'utf-8'
    );
    const ktSync = fs.readFileSync(
      path.join(
        repoRoot,
        'shared/src/commonMain/kotlin/com/safepocket/shared/sync/FirebaseSyncAdapter.kt'
      ),
      'utf-8'
    );
    const macCloud = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/CloudSyncAndPortability.swift'),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );

    expect(ktAuth).not.toContain('FormUrlEncoded');
    expect(ktAuth).toContain('SecureTokenRefreshRequest');
    expect(ktAuth).toContain('CloudAuthExpiredException');
    expect(ktSync).toContain('resp.status.value == 401 && forceRefreshTokenProvider != null');
    expect(macCloud).not.toContain('application/x-www-form-urlencoded');
    expect(androidShell).toContain('windowInsets = WindowInsets(0, 0, 0, 0)');
    expect(androidShell).toContain('Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)');
    expect(androidShell).not.toContain('if (!isVaultLocked && widthDp >= 600)');
    expect(macSplit).toContain('.frame(maxWidth: .infinity, alignment: .center)');
    expect(pwaCss).toContain('padding: 14px 10px 6px 8px;');
    expect(pwaCss).toContain('justify-content: center;');
  });

  it('14. v0.3.7 Five-Point UX & Cross-Platform Parity (Centered Icons, +New Category Menu, 2-Column Storage Cards, Elevated Modal Footers & Top-Anchored Hover Context Menus)', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const smoothComponents = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/SmoothInteractiveComponents.kt'
      ),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    const androidRepo = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/data/AndroidVaultRepository.kt'
      ),
      'utf-8'
    );
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const deArb = JSON.parse(
      fs.readFileSync(path.join(repoRoot, 'i18n/de.arb.json'), 'utf-8')
    ) as Record<string, string>;

    // 1) Centered sidebar footer icons relative to divider line
    expect(smoothComponents).toContain('CenteredThemeContrastIcon');
    expect(smoothComponents).toContain('CenteredLockOutlineIcon');
    expect(smoothComponents).toContain('withCenteredMetrics');
    expect(androidShell).toContain(
      'horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)'
    );
    expect(pwaCss).toContain('width: 34px;');
    expect(pwaCss).toContain(
      '.sp-sidebar-bottom-bar {\n  display: flex;\n  align-items: center;\n  justify-content: center;'
    );

    // 2) + New category dropdown in middle pane top bar & category-free item dialog
    expect(androidShell).toContain('NewItemCategoryDropdownButton');
    expect(pwaShell).toContain('data-new-item-category');
    expect(pwaShell).not.toContain('id="sp-edit-category"');
    expect(macSplit).toContain('macNewItemCategoryMenu');
    expect(macSplit).toContain(
      '.navigationTitle(vaultState.selectedCategoryTitle)\n        .toolbar {\n            ToolbarItem(placement: .automatic) {\n                macNewItemCategoryMenu'
    );
    expect(macSplit).not.toContain(
      'ToolbarItemGroup(placement: .primaryAction) {\n                macNewItemCategoryMenu'
    );
    expect(macApp).toContain('openCreateEditor(category:');

    // 3) Side-by-side 2-column Lokale DB vs Cloud-Sync cards & non-destructive toggle
    expect(deArb.storage_mode_section_title).toBe('Datenbank & Synchronisierung');
    expect(deArb.storage_mode_local_only_title).toBe('Lokale DB');
    expect(deArb.storage_mode_cloud_only_title).toBe('Cloud-Sync');
    expect(androidShell).toContain('StorageModeSelectionCard');
    expect(smoothComponents).toContain('CloudOffCardIcon');
    expect(smoothComponents).toContain('CloudDoneCardIcon');
    expect(androidRepo).not.toContain('remove(KEY_FIREBASE_SESSION) // on mode toggle');
    expect(pwaCss).toContain('.sp-storage-mode-grid');
    expect(pwaCss).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))');
    expect(macSplit).toContain('MacStorageModeSelectionCard');

    // 4) Modal action buttons clear of window frame / bottom edge
    expect(androidShell).toContain('usePlatformDefaultWidth = false');
    expect(androidShell).toContain('vertical = 28.dp');
    expect(pwaCss).toContain('.sp-modal-footer');
    expect(macSplit).toContain('.padding(.bottom, 22)');

    // 5) Right-click context menu on top of selected item + hover highlight
    expect(smoothComponents).toContain('SmoothContextMenuItem');
    expect(androidShell).toContain('(pos.y - size.height).toDp()');
    expect(pwaShell).toContain('entryEl.getBoundingClientRect()');
    expect(pwaCss).toContain('.sp-context-item:hover');
    expect(pwaCss).toContain('.sp-context-item.is-danger:hover');
  });

  it('15. Cross-Platform Zero-Knowledge Sync v2: PBKDF2-100k Test Vector, Distinct-Secret-Key Multi-Device Sync, Pull-First CRDT, Tombstones & macOS Wire Compatibility', async () => {
    // 1) Verify pinned RFC 8018 PBKDF2-HMAC-SHA256 (100,000 rounds) test vector shared across Kotlin, Swift & WebCrypto
    const cloudKeyHex = await deriveZeroKnowledgeCloudSyncKeyHex(
      'Clara@SafePocket.EU ',
      'MyStrongCloudPass!2026',
      'uid_eu_test_12345'
    );
    expect(cloudKeyHex).toBe(
      'e5f20ce7e6b0f14c9175cd10bba9dafb54f4e2014d2d7e89b9edb97684df6001'
    );

    // 2) Simulate two independent devices with completely DIFFERENT local Secret Keys & Argon2id salts
    const sharedCloudReplica = new SqliteWasmVaultDb(false);
    await sharedCloudReplica.ensureInitialized();

    const makeDevice = async (nodeId: string, masterPass: string) => {
      const localSqlite = new SqliteWasmVaultDb(false);
      await localSqlite.ensureInitialized();
      const localCrypto = new ZeroKnowledgeVaultCrypto({
        memorySizeKiB: 1024,
        iterations: 1,
        parallelism: 1
      });
      await localCrypto.initializeOrUnlock(
        masterPass,
        generateFormattedSecretKey()
      );
      const fbAdapter = new FirebaseZeroKnowledgeAdapter(sharedCloudReplica);
      const gdAdapter = new GoogleDriveAppDataAdapter();
      const coord = new VaultSyncCoordinator(fbAdapter, gdAdapter);
      coord.setStorageMode('CLOUD_SYNC');
      const db = new EncryptedLocalVaultDb(localSqlite, coord);
      const bridge: CloudCipherBridge = {
        isActive: () => true,
        toCloudRow: async (localRow) => {
          const normHlc = normalizeHlcPacked(localRow.hlcPacked);
          if (localRow.isTombstone) {
            return localCrypto.createTombstoneRowWithKeyHex(
              localRow.id,
              localRow.category,
              cloudKeyHex,
              normHlc,
              localRow.versionVector
            );
          }
          const item = await localCrypto.decryptVaultRow(localRow);
          if (isBuiltInSampleItem(item)) return null;
          return localCrypto.encryptVaultItemWithKeyHex(
            item,
            cloudKeyHex,
            normHlc,
            localRow.versionVector
          );
        },
        fromCloudRow: async (cloudRow) => {
          const normHlc = normalizeHlcPacked(cloudRow.hlcPacked);
          if (cloudRow.isTombstone) {
            return {
              localRow: await localCrypto.createTombstoneRow(
                cloudRow.id,
                cloudRow.category,
                normHlc,
                cloudRow.versionVector
              )
            };
          }
          const item = await localCrypto.decryptVaultRowWithKeyHex(
            cloudRow,
            cloudKeyHex
          );
          return {
            localRow: await localCrypto.encryptVaultItem(
              item,
              normHlc,
              cloudRow.versionVector
            )
          };
        }
      };
      db.bindCloudCipherBridge(bridge);
      return {
        localSqlite,
        localCrypto,
        coord,
        db,
        hlc: new HybridClock(nodeId),
        nodeId
      };
    };

    const deviceA = await makeDevice('android-pixel9', 'DeviceAMaster!1');
    const deviceB = await makeDevice('macos-m4pro', 'CompletelyDifferentMaster!2');

    // Seed untouched built-in sample item on Device B -> must NEVER pollute Cloud!
    const sampleB = createInitialSampleVaultItems()[0];
    await deviceB.db.saveEncryptedRow(
      await deviceB.localCrypto.encryptVaultItem(
        sampleB,
        deviceB.hlc.nowPacked(1_700_000_001_000),
        { [deviceB.nodeId]: 1_700_000_001_000 }
      )
    );

    // Create real user item on Device A and sync
    const createdItem: VaultItem = {
      schemaVersion: 1,
      id: 'item-cross-platform-visa-1',
      category: 'PAYMENT_CARD',
      title: 'Alpha Bank Gold Visa',
      subtitle: 'Primary Travel Card',
      favorite: true,
      pinned: true,
      tags: ['travel', 'visa'],
      fields: [
        {
          id: 'f-pan',
          labelKey: 'field_card_number',
          value: '4539 0075 1284 4918',
          valueType: 'NUMBER',
          isSensitive: true
        }
      ],
      attachments: [],
      createdAtEpochMs: 1_700_000_010_000,
      updatedAtEpochMs: 1_700_000_010_000,
      isSampleItem: false
    };
    await deviceA.db.saveEncryptedRow(
      await deviceA.localCrypto.encryptVaultItem(
        createdItem,
        deviceA.hlc.nowPacked(1_700_000_010_000),
        { [deviceA.nodeId]: 1_700_000_010_000 }
      )
    );

    const syncA1 = await deviceA.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    expect(syncA1?.pushedCount).toBe(1);

    // Idle 5-second background sync when nothing changed -> 0 redundant writes!
    const syncAIdle = await deviceA.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    expect(syncAIdle?.pushedCount).toBe(0);

    // Sync Device B: sample item is NOT pushed (pushedCount === 0), and Device A's item decrypts 100% cleanly on Device B!
    const syncB1 = await deviceB.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    expect(syncB1?.pushedCount).toBe(0);
    expect(syncB1?.pulledCount).toBe(1);
    const bRowsAfterPull = (await deviceB.db.loadAllEncryptedRows()).filter(
      (r) => r.id === createdItem.id
    );
    expect(bRowsAfterPull.length).toBe(1);
    const decryptedOnB = await deviceB.localCrypto.decryptVaultRow(
      bRowsAfterPull[0]
    );
    expect(decryptedOnB.title).toBe('Alpha Bank Gold Visa');
    expect(decryptedOnB.fields[0].value).toBe('4539 0075 1284 4918');

    // 3) Pull-First Guarantee: Device A makes a newer edit, then Device B syncs with stale v1 local state -> MUST NOT overwrite Device A's newer edit!
    const editedOnA: VaultItem = {
      ...createdItem,
      title: 'Alpha Bank Platinum Visa (Edited on Android)',
      updatedAtEpochMs: 1_700_000_050_000
    };
    await deviceA.db.saveEncryptedRow(
      await deviceA.localCrypto.encryptVaultItem(
        editedOnA,
        deviceA.hlc.nowPacked(1_700_000_050_000),
        { [deviceA.nodeId]: 1_700_000_050_000 }
      )
    );
    const syncA2 = await deviceA.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    expect(syncA2?.pushedCount).toBe(1);

    const syncB2 = await deviceB.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    expect(syncB2?.pushedCount).toBe(0);
    const bUpdatedRow = (await deviceB.db.loadAllEncryptedRows()).find(
      (r) => r.id === createdItem.id
    )!;
    expect((await deviceB.localCrypto.decryptVaultRow(bUpdatedRow)).title).toBe(
      'Alpha Bank Platinum Visa (Edited on Android)'
    );

    // 4) Cross-Device Tombstone Propagation without Resurrection: Device B deletes item -> Device A deletes it cleanly on sync!
    await deviceB.db.saveEncryptedRow(
      await deviceB.localCrypto.createTombstoneRow(
        createdItem.id,
        createdItem.category,
        deviceB.hlc.nowPacked(1_700_000_090_000),
        {
          [deviceA.nodeId]: 1_700_000_050_000,
          [deviceB.nodeId]: 1_700_000_090_000
        }
      )
    );
    const syncBDelete = await deviceB.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    expect(syncBDelete?.pushedCount).toBe(1);

    await deviceA.db.reconcileCloudSessionAutomatically(
      'default-vault',
      'FIREBASE:uid_eu_test_12345'
    );
    const activeOnAAfterDelete = await deviceA.db.loadAllEncryptedRows();
    expect(
      activeOnAAfterDelete.some((r) => r.id === createdItem.id)
    ).toBe(false);

    // 5) Verify macOS Canonical Wire JSON (URL field enum + integer epochMs) decrypts cleanly
    const macWireItem: VaultItem = {
      schemaVersion: 1,
      id: 'mac-wire-item-1',
      category: 'BANK_ACCOUNT',
      title: 'DKB Girokonto',
      subtitle: 'DE89 3704 0044 0532 0130 00',
      favorite: false,
      pinned: false,
      tags: ['sepa'],
      cardTheme: {
        paletteId: 'nordic_ocean',
        autoBrandColor: true,
        institutionDomain: 'dkb.de'
      },
      fields: [
        {
          id: 'f-url',
          labelKey: 'field_institution_domain',
          value: 'https://www.dkb.de',
          valueType: 'URL'
        }
      ],
      attachments: [],
      createdAtEpochMs: 1_700_000_100_000,
      updatedAtEpochMs: 1_700_000_100_000
    };
    const macCloudRow = await deviceA.localCrypto.encryptVaultItemWithKeyHex(
      macWireItem,
      cloudKeyHex,
      '001700000100000:0000:macos-node',
      { 'macos-node': 1_700_000_100_000 }
    );
    const decodedMacWire = await deviceB.localCrypto.decryptVaultRowWithKeyHex(
      macCloudRow,
      cloudKeyHex
    );
    expect(decodedMacWire.fields[0].valueType).toBe('URL');
    expect(decodedMacWire.title).toBe('DKB Girokonto');

    // 6) Verify Kotlin & Swift Cross-Platform Sync v2 source invariants
    const repoRoot = path.resolve(__dirname, '../../..');
    const ktCrypto = fs.readFileSync(
      path.join(
        repoRoot,
        'shared/src/commonMain/kotlin/com/safepocket/shared/crypto/Argon2idPure.kt'
      ),
      'utf-8'
    );
    const macCloudSwift = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/CloudSyncAndPortability.swift'),
      'utf-8'
    );
    const macAppSwift = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    expect(ktCrypto).toContain('safepocket-zk-cloud-sync-kek-v2:');
    expect(ktCrypto).toContain('iterations = 100_000');
    expect(macCloudSwift).toContain('safepocket-zk-cloud-sync-kek-v2:');
    expect(macCloudSwift).toContain('MacCanonicalWireVaultItem');
    expect(macCloudSwift).toContain('MacPendingTombstone');
    expect(macAppSwift).toContain('startLiveCloudSyncLoop');
  });

  it('16. Phase 1 UI/UX Overhaul: Vector Icon System, ISO ID-1 3D Flippable Cards, 5-Tab Settings & Live-Preview Progressive Editor', async () => {
    const { categoryIconName, renderIconSvg } = await import(
      '../ui/vectorIcons'
    );
    const { VAULT_CATEGORIES } = await import('../types');

    // 1) Cohesive stroke SVG icons for all 7 vault categories
    for (const cat of VAULT_CATEGORIES) {
      const svg = renderIconSvg(categoryIconName(cat), 16);
      expect(svg).toContain('<svg');
      expect(svg).toContain('class="sp-svg-icon"');
      expect(svg).toContain('stroke-width="1.85"');
    }

    const repoRoot = path.resolve(__dirname, '../../..');
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    const androidBrandCard = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/BrandCardComposable.kt'
      ),
      'utf-8'
    );

    // 2) ISO ID-1 (1.586:1) 3D Front/Back Flippable Cards across PWA, macOS & Android
    expect(pwaCss).toContain('.sp-card-3d-flipper.is-flipped');
    expect(pwaCss).toContain('aspect-ratio: 1.586 / 1;');
    expect(pwaShell).toContain('renderVisualCardBackFace');
    expect(pwaShell).toContain('data-flip-card=');
    expect(macSplit).toContain('MacFlippableVisualCard');
    expect(macSplit).toContain('.aspectRatio(1.586, contentMode: .fit)');
    expect(macSplit).toContain('.rotation3DEffect');
    expect(androidBrandCard).toContain('Modifier.aspectRatio(1.586f)');
    expect(androidBrandCard).toContain('rotationY = cardFaceRotationDeg');
    expect(pwaShell).toContain('data-toggle-card-secret=');
    expect(macSplit).toContain('isCvvRevealed');
    expect(androidBrandCard).toContain('isCvvRevealed');

    // 3) 5-Tab Settings Modal across PWA, macOS & Android
    for (const tabKey of [
      'settings_tab_appearance',
      'settings_tab_security',
      'settings_tab_sync',
      'settings_tab_data',
      'settings_tab_updates'
    ]) {
      expect(pwaShell).toContain(tabKey);
      expect(macSplit).toContain(tabKey);
      expect(androidShell).toContain(tabKey);
    }

    // 4) Live-Preview Item Editor + Progressive Disclosure + 3-Step Onboarding / Vault Shield Lock
    expect(pwaShell).toContain('id="sp-editor-live-card-slot"');
    expect(pwaShell).toContain('id="sp-toggle-editor-more"');
    expect(pwaShell).toContain('data-onboard-step=');
    expect(pwaShell).toContain('sp-vault-shield-hero');
    expect(macSplit).toContain('editor_live_preview_label');
    expect(macSplit).toContain('editor_more_fields_toggle');
    expect(androidShell).toContain('editor_live_preview_label');
    expect(androidShell).toContain('editor_more_fields_toggle');

    const macCloud = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/CloudSyncAndPortability.swift'),
      'utf-8'
    );
    expect(macCloud).toContain('safepocket_keybag_v2.enc');
    expect(macCloud).toContain('hardwarePlatformUUID');
  });

  it('17. Phase 2 Signature Wallet UX: EPC069-12 SEPA GiroCode, Read-Aloud Dictation Blocks, 30s Radial Clipboard Ring & Pinned Wallet Deck across PWA, macOS & Android', async () => {
    const { buildEpcSepaQrPayload, buildReadAloudBlocks } = await import(
      '../scanner/epcSepaQr'
    );
    const { encodeQrSvg } = await import('../scanner/barcodeRenderer');

    // 1) EPC069-12 v002 SEPA GiroCode payload & offline QR SVG generation
    const basicEpc = buildEpcSepaQrPayload({
      holderName: 'Clara Sofia Müller',
      iban: 'DE89 3704 0044 0532 0130 00',
      bic: 'DEUTDEFFXXX'
    });
    expect(basicEpc).toBe(
      'BCD\n002\n1\nSCT\nDEUTDEFFXXX\nClara Sofia Müller\nDE89370400440532013000'
    );

    const withAmountAndPurpose = buildEpcSepaQrPayload({
      holderName: 'Clara Sofia Müller',
      iban: 'DE89 3704 0044 0532 0130 00',
      bic: 'INGDDEFFXXX',
      amountEur: '25,5',
      remittanceText: 'Dinner Split'
    });
    expect(withAmountAndPurpose).toBe(
      'BCD\n002\n1\nSCT\nINGDDEFFXXX\nClara Sofia Müller\nDE89370400440532013000\nEUR25.50\n\n\nDinner Split'
    );
    expect(encodeQrSvg(withAmountAndPurpose!)).toContain('<svg');
    expect(
      buildEpcSepaQrPayload({ holderName: 'Test', iban: 'INVALID' })
    ).toBeNull();

    // 2) Read-Aloud 4-character dictation chunking
    const blocks = buildReadAloudBlocks('DE89 3704 0044 0532 0130 00', 4);
    expect(blocks).toEqual([
      { index: 1, chars: 'DE89' },
      { index: 2, chars: '3704' },
      { index: 3, chars: '0044' },
      { index: 4, chars: '0532' },
      { index: 5, chars: '0130' },
      { index: 6, chars: '00' }
    ]);

    // 3) Cross-platform Phase 2 UI wiring across PWA, macOS & Android/Shared
    const repoRoot = path.resolve(__dirname, '../../..');
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const sharedSepaKt = fs.readFileSync(
      path.join(
        repoRoot,
        'shared/src/commonMain/kotlin/com/safepocket/shared/scanner/EpcSepaAndQrEngine.kt'
      ),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );

    expect(pwaCss).toContain('.sp-clipboard-ring-svg');
    expect(pwaCss).toContain('.sp-wallet-deck');
    expect(pwaCss).toContain('.sp-sepa-qr-card');
    expect(pwaCss).toContain('.sp-read-aloud-grid');
    expect(pwaShell).toContain('renderPinnedWalletDeck');
    expect(pwaShell).toContain('renderSepaQrSection');
    expect(pwaShell).toContain('data-present-tab=');

    expect(macApp).toContain('clipboardCountdownSeconds');
    expect(macApp).toContain('func scrubClipboardNow()');
    expect(macSplit).toContain('macPinnedWalletDeck');
    expect(macSplit).toContain('MacSepaQrSectionCard');
    expect(macSplit).toContain('MacPassPresentationSheet');

    expect(sharedSepaKt).toContain('object EpcSepaAndQrEngine');
    expect(sharedSepaKt).toContain('fun buildEpcSepaQrPayload');
    expect(sharedSepaKt).toContain('fun encodeQrMatrix');
    expect(androidShell).toContain('PinnedWalletDeckStrip');
    expect(androidShell).toContain('ClipboardCountdownToastPill');
    expect(androidShell).toContain('SepaQrSectionCard');
    expect(androidShell).toContain('PassPresentationDialog');
  });

  it('18. Phase 3 Power & Mobile Ergonomics: Cmd+K Command Palette, 6-Month Passport Travel Readiness Radar & Quick-Copy Swipe across PWA, macOS & Android', async () => {
    const { computeDocumentReadiness, searchVaultItems } = await import(
      '../search/searchEngine'
    );
    const i18n = new SafePocketI18n('en');
    const refMs = Date.UTC(2026, 10, 26, 12, 0, 0); // 2026-11-26

    // 1) Passport with ~125 days remaining (< 6 months / <= 180 days) & missing scan triggers Travel Readiness warnings
    const passportFiveMonths = {
      schemaVersion: 1 as const,
      id: 'doc-passport-5m',
      category: 'OFFICIAL_DOCUMENT' as const,
      title: 'EU Reisepass',
      subtitle: 'Passport',
      favorite: false,
      pinned: false,
      tags: ['travel'],
      fields: [
        {
          id: 'f1',
          labelKey: 'field_document_number',
          isCustomLabel: false,
          value: 'C01X99887',
          valueType: 'SENSITIVE_SECRET' as const,
          isSensitive: true
        },
        {
          id: 'f2',
          labelKey: 'field_expiry_date',
          isCustomLabel: false,
          value: '2027-03-31',
          valueType: 'DATE_ISO' as const,
          isSensitive: false
        }
      ],
      attachments: [],
      createdAtEpochMs: refMs,
      updatedAtEpochMs: refMs
    };

    const readiness5m = computeDocumentReadiness(passportFiveMonths, refMs, 90);
    expect(readiness5m.travelSixMonthWarning).toBe(true);
    expect(readiness5m.missingBackupScan).toBe(true);
    expect(readiness5m.timelineBucket).toBe('upcoming');
    expect(readiness5m.needsReadinessAttention).toBe(true);

    // 2) Payment card with 400+ days remaining is healthy and excluded from expiringOnly
    const healthyCard = {
      schemaVersion: 1 as const,
      id: 'card-healthy-2029',
      category: 'PAYMENT_CARD' as const,
      title: 'Healthy Visa',
      subtitle: 'Credit',
      favorite: false,
      pinned: false,
      tags: [],
      fields: [
        {
          id: 'c1',
          labelKey: 'field_expiry_date',
          isCustomLabel: false,
          value: '2029-12',
          valueType: 'DATE_ISO' as const,
          isSensitive: false
        }
      ],
      attachments: [],
      createdAtEpochMs: refMs,
      updatedAtEpochMs: refMs
    };
    const readinessCard = computeDocumentReadiness(healthyCard, refMs, 90);
    expect(readinessCard.travelSixMonthWarning).toBe(false);
    expect(readinessCard.missingBackupScan).toBe(false);
    expect(readinessCard.timelineBucket).toBe('healthy');
    expect(readinessCard.needsReadinessAttention).toBe(false);

    // expiringOnly search surfaces 5-month passport even when warningWindowDays is 90
    const attentionItems = searchVaultItems(
      [passportFiveMonths, healthyCard],
      '',
      i18n,
      { expiringOnly: true, warningWindowDays: 90, referenceEpochMs: refMs }
    );
    expect(attentionItems.map((it) => it.id)).toEqual(['doc-passport-5m']);

    // 3) Cross-platform Phase 3 wiring across PWA, macOS, Shared Kotlin & Android
    const repoRoot = path.resolve(__dirname, '../../..');
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const pwaCss = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/styles/m3-expressive.css'),
      'utf-8'
    );
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const sharedSearchKt = fs.readFileSync(
      path.join(
        repoRoot,
        'shared/src/commonMain/kotlin/com/safepocket/shared/search/MultilingualSearchEngine.kt'
      ),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );

    expect(pwaCss).toContain('.sp-cmd-palette-card');
    expect(pwaCss).toContain('.sp-badge-travel');
    expect(pwaShell).toContain('renderCommandPaletteModal');
    expect(pwaShell).toContain('renderExpiringReadinessGroups');
    expect(pwaShell).toContain('readiness_passport_6m_badge');

    expect(macApp).toContain('presentCommandPalette');
    expect(macApp).toContain('isTravelSixMonthWarning');
    expect(macSplit).toContain('MacCommandPaletteSheet');
    expect(macSplit).toContain('readiness_passport_6m_badge');
    expect(macSplit).toContain('swipe_quick_copy_hint');

    expect(sharedSearchKt).toContain('fun computeDocumentReadiness');
    expect(androidShell).toContain('CommandPaletteDialog');
    expect(androidShell).toContain('readiness_passport_6m_badge');
    expect(androidShell).toContain('swipe_quick_copy_hint');
  });

  it('19. v0.6.0-beta.1 Phase 1: Cross-Platform Per-Blob Envelope Encryption (fileKeyHex), EU Cloud Storage Sync & In-Memory Image/PDF Lightbox across PWA, macOS & Android', async () => {
    const {
      ZeroKnowledgeVaultCrypto,
      generateFormattedSecretKey
    } = await import('../crypto/vaultCrypto');
    const { deriveZeroKnowledgeCloudSyncKeyHex } = await import(
      '../sync/liveCloudConnectors'
    );
    const { computeDocumentReadiness } = await import('../search/searchEngine');

    // 1) Independent local vaults (Device A vs Device B) decrypt synced .enc attachment via per-blob fileKeyHex + cloudKeyHex!
    const cryptoDeviceA = new ZeroKnowledgeVaultCrypto();
    await cryptoDeviceA.initializeOrUnlock(
      'MacLocalMasterPass!A',
      generateFormattedSecretKey()
    );

    const cryptoDeviceB = new ZeroKnowledgeVaultCrypto();
    await cryptoDeviceB.initializeOrUnlock(
      'AndroidLocalMasterPass!B',
      generateFormattedSecretKey()
    );

    const cloudKeyHex = await deriveZeroKnowledgeCloudSyncKeyHex(
      'clara@safepocket.eu',
      'MyStrongCloudPass!2026',
      'uid_eu_test_12345'
    );

    const rawPdfScan = new TextEncoder().encode(
      '%PDF-1.7\nSafePocket Biometric Passport Backup Scan\n%%EOF'
    );
    const fileKeyHex = cryptoDeviceA.generateAttachmentKeyHex();
    expect(fileKeyHex).toHaveLength(64);

    const encBlob = await cryptoDeviceA.encryptBinaryAttachment(
      'att_passport_v06_01',
      'Passport_Clara_2026.pdf',
      'application/pdf',
      rawPdfScan,
      'front',
      fileKeyHex
    );
    expect(encBlob.fileKeyHex).toBe(fileKeyHex);

    const nowMs = Date.UTC(2026, 10, 27, 1, 0, 0);
    const passportItem = {
      schemaVersion: 1 as const,
      id: 'doc-v06-passport',
      category: 'OFFICIAL_DOCUMENT' as const,
      title: 'Reisepass (DEU)',
      subtitle: 'Passport',
      favorite: false,
      pinned: true,
      tags: ['travel'],
      fields: [
        {
          id: 'd1',
          labelKey: 'field_document_number',
          isCustomLabel: false,
          value: 'C01X00T47',
          valueType: 'SENSITIVE_SECRET' as const,
          isSensitive: true
        },
        {
          id: 'd2',
          labelKey: 'field_expiry_date',
          isCustomLabel: false,
          value: '2030-06-15',
          valueType: 'DATE_ISO' as const,
          isSensitive: false
        }
      ],
      attachments: [
        {
          id: encBlob.id,
          fileName: encBlob.fileName,
          mimeType: encBlob.mimeType,
          side: 'front' as const,
          sizeBytes: encBlob.sizeBytes,
          sha256Hex: encBlob.sha256Hex,
          ivHex: encBlob.ivHex,
          fileKeyHex: encBlob.fileKeyHex,
          blobSynced: true
        }
      ],
      createdAtEpochMs: nowMs,
      updatedAtEpochMs: nowMs
    };

    // Attaching scan clears "missingBackupScan" in Travel Readiness Radar!
    const readiness = computeDocumentReadiness(passportItem, nowMs, 90);
    expect(readiness.missingBackupScan).toBe(false);
    expect(readiness.needsReadinessAttention).toBe(false);

    // Sync item JSON under cloudKeyHex -> Device B decrypts item AND decrypts .enc blob in RAM despite having a different local vault key!
    const cloudRow = await cryptoDeviceA.encryptVaultItemWithKeyHex(
      passportItem,
      cloudKeyHex,
      '001764205200000:0001:mac-a',
      { 'mac-a': 1 }
    );
    const hydratedOnDeviceB = await cryptoDeviceB.decryptVaultRowWithKeyHex(
      cloudRow,
      cloudKeyHex
    );
    expect(hydratedOnDeviceB.attachments).toHaveLength(1);
    expect(hydratedOnDeviceB.attachments[0].fileKeyHex).toBe(fileKeyHex);

    const decryptedOnDeviceB = await cryptoDeviceB.decryptBinaryAttachment({
      attachmentId: hydratedOnDeviceB.attachments[0].id,
      ivHex: hydratedOnDeviceB.attachments[0].ivHex,
      expectedSha256Hex: hydratedOnDeviceB.attachments[0].sha256Hex,
      fileKeyHex: hydratedOnDeviceB.attachments[0].fileKeyHex,
      encryptedBytes: encBlob.encryptedBytes
    });
    expect(new TextDecoder().decode(decryptedOnDeviceB)).toBe(
      '%PDF-1.7\nSafePocket Biometric Passport Backup Scan\n%%EOF'
    );

    // 2) Cross-platform source verification across PWA, macOS & Android
    const repoRoot = path.resolve(__dirname, '../../..');
    const macCloud = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/CloudSyncAndPortability.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const androidRepo = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/data/AndroidVaultRepository.kt'
      ),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );

    expect(macCloud).toContain('enum MacEncryptedBlobStore');
    expect(macCloud).toContain('func uploadEncryptedBlobToFirebaseStorage');
    expect(macCloud).toContain('func decryptAttachmentBlob');
    expect(macSplit).toContain('struct MacDocumentScansSectionCard');
    expect(macSplit).toContain('struct MacAttachmentLightboxSheet');
    expect(macSplit).toContain('struct MacInMemoryPdfView');

    expect(androidRepo).toContain('fun attachScanBytesToItem');
    expect(androidRepo).toContain('fun decryptAttachmentBytesInRam');
    expect(androidShell).toContain('fun EncryptedDocumentScansSectionCard');
    expect(androidShell).toContain('fun AttachmentLightboxDialog');
    expect(androidShell).toContain('PdfRenderer');
  });

  it('20. v0.6.0-beta.1 Phase 2: Instant Biometric / Passkey Unlock & macOS Menu Bar Quick-Vault across PWA, macOS & Android', async () => {
    const { WebAuthnBiometricUnlock } = await import(
      '../security/clipboardAndWebAuthn'
    );

    if (typeof localStorage !== 'undefined' && typeof localStorage.setItem === 'function') {
      WebAuthnBiometricUnlock.unenrollPasskey();
      expect(WebAuthnBiometricUnlock.hasEnrolledPasskey()).toBe(false);
      localStorage.setItem('safepocket.webauthn_cred_id', btoa('mock-cred-123'));
      expect(WebAuthnBiometricUnlock.hasEnrolledPasskey()).toBe(true);
      WebAuthnBiometricUnlock.unenrollPasskey();
      expect(WebAuthnBiometricUnlock.hasEnrolledPasskey()).toBe(false);
    }

    const repoRoot = path.resolve(__dirname, '../../..');
    const pwaShell = fs.readFileSync(
      path.join(repoRoot, 'pwaApp/src/ui/appShell.ts'),
      'utf-8'
    );
    const macApp = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/SafePocketMacApp.swift'),
      'utf-8'
    );
    const macSplit = fs.readFileSync(
      path.join(repoRoot, 'macosApp/SafePocketMac/Views/MacVaultSplitView.swift'),
      'utf-8'
    );
    const androidBio = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/security/AndroidBiometricKeystoreManager.kt'
      ),
      'utf-8'
    );
    const androidMain = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/MainActivity.kt'
      ),
      'utf-8'
    );
    const androidShell = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );

    // PWA WebAuthn Passkey enrollment & removal
    expect(pwaShell).toContain('security_biometrics_enrolled_badge');
    expect(pwaShell).toContain('id="sp-remove-passkey"');

    // macOS Menu Bar Quick-Vault & Touch ID toggle
    expect(macApp).toContain('MenuBarExtra(');
    expect(macApp).toContain('MacMenuBarQuickVaultView');
    expect(macSplit).toContain('struct MacMenuBarQuickVaultView');
    expect(macSplit).toContain('security_menubar_toggle');

    // Android BiometricPrompt Vault Shield integration
    expect(androidBio).toContain('fun authenticateVaultShield');
    expect(androidBio).toContain('fun canAuthenticateBiometrics');
    expect(androidMain).toContain('class MainActivity : FragmentActivity()');
    expect(androidMain).toContain('authenticateVaultShield');
    expect(androidShell).toContain('showBiometricButton');
    expect(androidShell).toContain('security_biometrics_toggle');
  });
});

