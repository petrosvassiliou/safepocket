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

describe('SafePocket v0.3.3 (Build 8) Feature Suite', () => {
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

  it('7. Cross-Platform v0.3.5 Architecture Verification (Android, Googlebook OS, macOS & PWA)', () => {
    expect(APP_BUILD_INFO.versionName).toBe('0.3.5');
    expect(APP_BUILD_INFO.versionCode).toBe(10);

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
    expect(macApp).toContain('SafePocket v0.3.5 (Build 10)');
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
});
