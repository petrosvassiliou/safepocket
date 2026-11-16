import {
  type BackupInspectionSummary,
  formatUnencryptedVaultExport,
  generateFormattedSecretKey,
  inspectSafePocketContainerHeader,
  mergeVaultSnapshots,
  sha256Hex,
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
import { FirebaseVaultService } from '../firebase/firebaseVaultService';
import { SafePocketI18n, SUPPORTED_LOCALES } from '../i18n/l10n';
import { renderScannableBarcodeSvg } from '../scanner/barcodeRenderer';
import {
  analyzeMrzRegionFromCanvas,
  type CameraScanMode,
  CameraScannerController,
  parseRecoverySecretKeyPayload,
  type ScannedBarcodeResult
} from '../scanner/cameraScanner';
import {
  buildSampleTd3PassportMrz,
  parseOfflineIcaoMrz,
  validateIsoIban,
  validatePaymentCardPan
} from '../scanner/mrzAndCardValidator';
import {
  computeExpiryStatus,
  extractVaultTags,
  searchVaultItems,
  sortVaultItems,
  summarizeVaultExpiries
} from '../search/searchEngine';
import {
  type AutoLockMinutes,
  IdleAutoLockMonitor
} from '../security/autoLockMonitor';
import {
  ClipboardHygieneController,
  WebAuthnBiometricUnlock
} from '../security/clipboardAndWebAuthn';
import type { LiveCloudEndpointConfig } from '../sync/liveCloudConnectors';
import {
  type CloudServiceId,
  EncryptedLocalVaultDb,
  FirebaseZeroKnowledgeAdapter,
  GoogleDriveAppDataAdapter,
  HybridClock,
  VaultSyncCoordinator
} from '../sync/syncAdapters';
import {
  APP_BUILD_INFO,
  type BarcodeFormat,
  CARD_THEME_PALETTES,
  type CardThemePalette,
  type EncryptedAttachmentMeta,
  EXPIRY_WINDOW_OPTIONS,
  type ExpiryWindowDays,
  type MaterialColorScheme,
  SENSITIVE_FIELD_KEYS,
  type SupportedLocale,
  type ThemeMode,
  VAULT_CATEGORIES,
  type VaultCategory,
  type VaultField,
  type VaultItem,
  type VaultSortMode
} from '../types';
import { PwaUpdateController } from '../update/pwaUpdater';
import { buildCustomCardStyle, normalizeHexColor } from './cardColorUtils';
import {
  buildEmergencyKitHtml,
  estimateMasterPasswordEntropy
} from './onboardingWizard';

const THEME_STORAGE_KEY = 'safepocket.theme_mode';
const MATERIAL_ACCENT_STORAGE_KEY = 'safepocket.material_accent';
const EXPIRY_WINDOW_STORAGE_KEY = 'safepocket.expiry_window_days';
const ALLOW_SCREENSHOTS_STORAGE_KEY = 'safepocket.allow_screenshots';

export function resolveEffectiveTheme(
  mode: ThemeMode,
  prefersDark = false
): 'light' | 'dark' {
  if (mode === 'dark') return 'dark';
  if (mode === 'system') return prefersDark ? 'dark' : 'light';
  return 'light';
}

export function nextThemeModeInCycle(current: ThemeMode): ThemeMode {
  if (current === 'light') return 'dark';
  if (current === 'dark') return 'system';
  return 'light';
}

type NavSelection =
  | 'ALL'
  | 'FAVORITES'
  | 'EXPIRING'
  | VaultCategory
  | 'SETTINGS';

interface DraftCustomField {
  id: string;
  label: string;
  value: string;
  isSensitive: boolean;
}

interface ItemEditorDraft {
  editingItemId: string | null;
  category: VaultCategory;
  title: string;
  subtitle: string;
  paletteId: CardThemePalette;
  customColorHex: string;
  showVisualCard: boolean;
  barcodeFormat: BarcodeFormat;
  tagsCsv: string;
  mrzRawInput?: string;
  standardFieldValues: Record<string, string>;
  customFields: DraftCustomField[];
}

interface EphemeralScanPreview {
  fileName: string;
  mimeType: string;
  side?: 'front' | 'back';
  sha256Hex: string;
  objectUrl: string;
}

const DEMO_DEFAULT_PASSWORD = 'correct-horse-vault-admin';

export class SafePocketAppShell {
  private readonly i18n = new SafePocketI18n();
  private readonly crypto = new ZeroKnowledgeVaultCrypto();
  private readonly sqliteDb = new SqliteWasmVaultDb(true);
  private readonly localDb = new EncryptedLocalVaultDb(this.sqliteDb);
  private readonly opfsStore = new OpfsEncryptedAttachmentStore();
  private readonly vaultHeaderStore = new VaultHeaderStore();
  private readonly hlc = new HybridClock('chromeos-pwa-node');
  private readonly firebaseService = new FirebaseVaultService(this.sqliteDb);
  private readonly firebaseAdapter = new FirebaseZeroKnowledgeAdapter(
    this.sqliteDb
  );
  private readonly gdriveAdapter = new GoogleDriveAppDataAdapter();
  private readonly syncCoordinator = new VaultSyncCoordinator(
    this.firebaseAdapter,
    this.gdriveAdapter
  );
  private readonly updater = new PwaUpdateController('/version.json');
  private readonly clipboard: ClipboardHygieneController;
  private readonly autoLockMonitor: IdleAutoLockMonitor;
  private readonly cameraController = new CameraScannerController();

  private themeMode: ThemeMode = 'light';
  private materialAccent: MaterialColorScheme = 'emerald';
  private expiryWindowDays: ExpiryWindowDays = 90;
  private allowScreenshots = true;
  private sortMode: VaultSortMode = 'pinned_recent';
  private activeTagFilter: string | null = null;
  private items: VaultItem[] = [];
  private selectedNav: NavSelection = 'ALL';
  private selectedItemId: string | null = null;
  private searchQuery = '';
  private revealedFieldIds = new Set<string>();
  private lastSyncedCount = 0;
  private lastPulledCount = 0;
  private clipboardSecondsLeft: number | null = null;
  private clipboardJustCleared = false;
  private editorDraft: ItemEditorDraft | null = null;
  private mobileDetailOpen = false;
  private toastMessage: string | null = null;
  private demoSecretKey = '';
  private activeMasterPassword = DEMO_DEFAULT_PASSWORD;
  private showOnboardingWizard = false;
  private onboardingPassword = DEMO_DEFAULT_PASSWORD;
  private emergencyKitHintDraft = '';
  private wasAutoLocked = false;
  private unlockError: string | null = null;
  private uploadScanSide: 'front' | 'back' = 'front';
  private activePreview: EphemeralScanPreview | null = null;
  private presentationState: {
    itemId: string;
    formatOverride?: BarcodeFormat;
  } | null = null;
  private wakeLockSentinel: { release: () => Promise<void> } | null = null;
  private cameraModalState: {
    mode: CameraScanMode;
    targetContext?: 'lock' | 'restore';
    enhanceCrispDoc: boolean;
    error?: string;
    mrzPreviewUrl?: string;
  } | null = null;
  private restoreModalState: {
    rawJson: string;
    summary: BackupInspectionSummary;
    mode: 'merge' | 'replace';
    masterPassword: string;
    secretKey: string;
    error?: string;
  } | null = null;
  private plainExportModalState: {
    format: 'json' | 'csv';
    password: string;
    error?: string;
  } | null = null;
  private cloudAuthEmailDraft = '';
  private liveCloudConfig: LiveCloudEndpointConfig = {
    firebaseApiKey: '',
    firebaseProjectId: '',
    firebaseStorageBucket: '',
    bearerToken: ''
  };

  private contextMenu: {
    x: number;
    y: number;
    itemId: string;
    label: string;
    value: string;
    isSensitive: boolean;
  } | null = null;

  constructor(private readonly root: HTMLElement) {
    this.themeMode = this.loadSavedThemeMode();
    this.materialAccent = this.loadSavedMaterialAccent();
    this.expiryWindowDays = this.loadSavedExpiryWindowDays();
    this.allowScreenshots = this.loadSavedAllowScreenshots();
    this.applyThemeToDocument();

    this.clipboard = new ClipboardHygieneController((seconds, cleared) => {
      this.clipboardSecondsLeft = seconds;
      this.clipboardJustCleared = cleared;
      this.render();
      if (cleared) {
        setTimeout(() => {
          this.clipboardJustCleared = false;
          this.render();
        }, 3500);
      }
    }, 30);

    this.autoLockMonitor = new IdleAutoLockMonitor(() => {
      if (this.crypto.isUnlocked) {
        this.lockVault(true);
      }
    });
  }

  private loadSavedAllowScreenshots(): boolean {
    if (typeof localStorage === 'undefined') return true;
    const raw = localStorage.getItem(ALLOW_SCREENSHOTS_STORAGE_KEY);
    if (raw === 'false') return false;
    return true;
  }

  setAllowScreenshots(enabled: boolean): void {
    this.allowScreenshots = enabled;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(ALLOW_SCREENSHOTS_STORAGE_KEY, String(enabled));
    }
    this.render();
  }

  private loadSavedExpiryWindowDays(): ExpiryWindowDays {
    if (typeof localStorage === 'undefined') return 90;
    const raw = Number(localStorage.getItem(EXPIRY_WINDOW_STORAGE_KEY));
    if (raw === 30 || raw === 60 || raw === 90 || raw === 180) {
      return raw;
    }
    return 90;
  }

  setExpiryWindowDays(days: ExpiryWindowDays): void {
    this.expiryWindowDays = days;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(EXPIRY_WINDOW_STORAGE_KEY, String(days));
    }
    this.render();
  }

  private loadSavedThemeMode(): ThemeMode {
    if (typeof localStorage === 'undefined') return 'light';
    const raw = localStorage.getItem(THEME_STORAGE_KEY);
    if (raw === 'light' || raw === 'dark' || raw === 'system') {
      return raw;
    }
    return 'light';
  }

  private loadSavedMaterialAccent(): MaterialColorScheme {
    if (typeof localStorage === 'undefined') return 'emerald';
    const raw = localStorage.getItem(MATERIAL_ACCENT_STORAGE_KEY);
    if (
      raw === 'emerald' ||
      raw === 'device' ||
      raw === 'ocean' ||
      raw === 'terracotta' ||
      raw === 'slate'
    ) {
      return raw;
    }
    return 'emerald';
  }

  get currentThemeMode(): ThemeMode {
    return this.themeMode;
  }

  get currentMaterialAccent(): MaterialColorScheme {
    return this.materialAccent;
  }

  get effectiveTheme(): 'light' | 'dark' {
    const prefersDark =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches;
    return resolveEffectiveTheme(this.themeMode, prefersDark);
  }

  setThemeMode(mode: ThemeMode): void {
    this.themeMode = mode;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(THEME_STORAGE_KEY, mode);
    }
    this.applyThemeToDocument();
    this.render();
  }

  setMaterialAccent(accent: MaterialColorScheme): void {
    this.materialAccent = accent;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(MATERIAL_ACCENT_STORAGE_KEY, accent);
    }
    this.applyThemeToDocument();
    this.render();
  }

  private toggleQuickTheme(): void {
    this.setThemeMode(nextThemeModeInCycle(this.themeMode));
  }

  private renderThemeToggleIconSvg(): string {
    if (this.themeMode === 'dark') {
      return `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"></path></svg>`;
    }
    if (this.themeMode === 'system') {
      return `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 3v18"></path><path d="M12 3a9 9 0 0 1 0 18" fill="currentColor" fill-opacity="0.28"></path></svg>`;
    }
    return `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"></path></svg>`;
  }

  private themeToggleTooltip(): string {
    if (this.themeMode === 'light') return this.i18n.t('theme_light');
    if (this.themeMode === 'dark') return this.i18n.t('theme_dark');
    return this.i18n.t('theme_system');
  }

  private applyThemeToDocument(): void {
    if (typeof document === 'undefined') return;
    const effective = this.effectiveTheme;
    document.documentElement.setAttribute('data-theme', effective);
    document.documentElement.setAttribute('data-accent', this.materialAccent);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) {
      meta.setAttribute('content', effective === 'dark' ? '#0E1315' : '#F5F7FA');
    }
  }

  async bootstrap(): Promise<void> {
    this.applyThemeToDocument();
    if (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function'
    ) {
      window
        .matchMedia('(prefers-color-scheme: dark)')
        .addEventListener?.('change', () => {
          if (this.themeMode === 'system') {
            this.applyThemeToDocument();
            this.render();
          }
        });
    }

    this.demoSecretKey =
      localStorage.getItem('safepocket.demo_secret_key') ??
      generateFormattedSecretKey();
    localStorage.setItem('safepocket.demo_secret_key', this.demoSecretKey);

    this.i18n.subscribe(() => this.render());
    this.updater.subscribe(() => this.render());
    this.bindGlobalShortcuts();

    const savedHeader = this.vaultHeaderStore.loadHeader();
    if (!savedHeader) {
      this.showOnboardingWizard = true;
    }

    // Render UI shell immediately (0ms first paint — never blank while WASM compiles)
    this.render();
    void this.updater.registerServiceWorkerAndAutoCheck();

    try {
      // Initialize SQLite WASM + OPFS vault & pre-provisioned Firebase database in standby
      await this.firebaseService.initializeDormant();
      this.liveCloudConfig = this.firebaseService.toEndpointConfig();
      this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
    } catch (err) {
      console.error('SQLite WASM bootstrap warning:', err);
      this.showToast(
        err instanceof Error
          ? `Storage fallback active: ${err.message}`
          : 'Storage fallback active'
      );
    }

    if (!savedHeader) {
      this.render();
      return;
    }

    // Returning session: if demo mode was chosen, unlock seamlessly for instant evaluation;
    // otherwise render Lock Screen using the persisted Argon2id salt & parameters.
    const isDemoVault = localStorage.getItem('safepocket.demo_mode') === 'true';
    if (isDemoVault) {
      await this.unlockAndHydrateVault(
        DEMO_DEFAULT_PASSWORD,
        this.demoSecretKey,
        true
      );
    } else {
      this.render();
    }
  }

  private async initializeVaultFromOnboarding(
    masterPassword: string,
    seedSampleItems: boolean
  ): Promise<void> {
    this.activeMasterPassword = masterPassword;
    await this.crypto.initializeOrUnlock(masterPassword, this.demoSecretKey);

    const kdf = this.crypto.currentKdfConfig;
    const verifierHashHex = await sha256Hex(
      new TextEncoder().encode(
        `verifier:${kdf.saltHex}:${masterPassword}:${this.demoSecretKey}`
      )
    );

    this.vaultHeaderStore.saveHeader({
      vaultId: `vault-${kdf.saltHex.slice(0, 12)}`,
      createdAtIso: new Date().toISOString(),
      saltHex: kdf.saltHex,
      memorySizeKiB: kdf.memorySizeKiB,
      iterations: kdf.iterations,
      parallelism: kdf.parallelism,
      verifierHashHex
    });
    localStorage.setItem('safepocket.demo_mode', String(seedSampleItems));

    await this.localDb.clearAll();
    if (seedSampleItems) {
      this.items = createInitialSampleVaultItems();
      await this.encryptSampleDocumentScans(this.items);
      await this.persistAllItemsEncrypted();
    } else {
      this.items = [];
    }

    this.selectedItemId = this.items[0]?.id ?? null;
    this.showOnboardingWizard = false;
    this.wasAutoLocked = false;
    this.unlockError = null;
    this.autoLockMonitor.start();
    this.render();
  }

  private async unlockAndHydrateVault(
    masterPassword: string,
    secretKey: string,
    reseedIfEmpty = false
  ): Promise<boolean> {
    const header = this.vaultHeaderStore.loadHeader();
    try {
      await this.crypto.initializeOrUnlock(
        masterPassword,
        secretKey,
        header
          ? {
              memorySizeKiB: header.memorySizeKiB,
              iterations: header.iterations,
              parallelism: header.parallelism,
              saltHex: header.saltHex
            }
          : undefined
      );

      if (header) {
        const candidateVerifier = await sha256Hex(
          new TextEncoder().encode(
            `verifier:${header.saltHex}:${masterPassword}:${secretKey
              .trim()
              .toUpperCase()}`
          )
        );
        if (candidateVerifier !== header.verifierHashHex) {
          this.crypto.lock();
          this.unlockError = 'Invalid Master Password or Secret Key';
          this.render();
          return false;
        }
      }

      const existingEncrypted = await this.localDb.loadAllEncryptedRows();
      const loaded: VaultItem[] = [];
      for (const row of existingEncrypted) {
        loaded.push(await this.crypto.decryptVaultRow(row));
      }
      this.items = loaded;

      if (this.items.length === 0 && reseedIfEmpty) {
        this.items = createInitialSampleVaultItems();
        await this.encryptSampleDocumentScans(this.items);
        await this.persistAllItemsEncrypted();
      } else if (this.items.length > 0) {
        // Ensure sample SVG blobs exist in memory/OPFS if user previews sample attachments
        await this.ensureSampleBlobsAvailable(this.items);
      }

      this.activeMasterPassword = masterPassword;
      this.demoSecretKey = secretKey.trim().toUpperCase();
      this.selectedItemId = this.selectedItemId ?? this.items[0]?.id ?? null;
      this.wasAutoLocked = false;
      this.unlockError = null;
      this.autoLockMonitor.start();
      this.render();
      return true;
    } catch {
      this.crypto.lock();
      this.unlockError = 'Decryption failed — verify Master Password & Secret Key';
      this.render();
      return false;
    }
  }

  private async encryptSampleDocumentScans(items: VaultItem[]): Promise<void> {
    for (const item of items) {
      if (item.category !== 'OFFICIAL_DOCUMENT') continue;
      const docNum =
        item.fields.find((f) => f.labelKey === 'field_document_number')?.value ??
        'DOC-001';
      const authority =
        item.fields.find((f) => f.labelKey === 'field_issuing_authority')
          ?.value ?? item.subtitle;

      const updatedAttachments: EncryptedAttachmentMeta[] = [];
      for (const att of item.attachments) {
        const side = att.side ?? 'front';
        const svgBytes = buildSampleSvgScanBytes({
          title: item.title,
          docNumber: docNum,
          authority,
          holder: 'CLARA SOFIA MÜLLER',
          side
        });
        const enc = await this.crypto.encryptBinaryAttachment(
          att.id,
          att.fileName,
          'image/svg+xml',
          svgBytes,
          side
        );
        await this.opfsStore.writeEncryptedBlob(att.id, enc.encryptedBytes);
        await this.sqliteDb.saveLocalEncryptedBlob(
          'default-vault',
          att.id,
          enc.encryptedBytes,
          enc.sha256Hex,
          false
        );
        updatedAttachments.push({
          id: enc.id,
          fileName: enc.fileName,
          mimeType: enc.mimeType,
          side: enc.side,
          sizeBytes: enc.sizeBytes,
          sha256Hex: enc.sha256Hex,
          ivHex: enc.ivHex,
          ciphertextBase64: enc.ciphertextBase64,
          blobSynced: false
        });
      }
      item.attachments = updatedAttachments;
    }
  }

  private async ensureSampleBlobsAvailable(items: VaultItem[]): Promise<void> {
    for (const item of items) {
      for (const att of item.attachments) {
        const existingOpfs = await this.opfsStore.readEncryptedBlob(att.id);
        const existingSql = existingOpfs
          ? null
          : await this.sqliteDb.loadLocalEncryptedBlob(att.id);
        if (
          !existingOpfs &&
          !existingSql &&
          !att.ciphertextBase64 &&
          !att.blobSynced &&
          att.id.startsWith('sample-') &&
          item.category === 'OFFICIAL_DOCUMENT'
        ) {
          await this.encryptSampleDocumentScans([item]);
          await this.persistSingleItemEncrypted(item);
          break;
        }
      }
    }
  }

  private lockVault(triggeredByAutoLock = false): void {
    this.closeScanPreview();
    this.closeCameraModal();
    this.closePresentationMode();
    this.crypto.lock();
    this.autoLockMonitor.stop();
    this.revealedFieldIds.clear();
    this.editorDraft = null;
    this.restoreModalState = null;
    this.plainExportModalState = null;
    this.contextMenu = null;
    this.wasAutoLocked = triggeredByAutoLock;
    this.render();
  }

  private closeScanPreview(): void {
    if (this.activePreview) {
      URL.revokeObjectURL(this.activePreview.objectUrl);
      this.activePreview = null;
    }
  }

  private openPresentationMode(itemId: string): void {
    const target = this.items.find((i) => i.id === itemId);
    if (!target) return;
    target.lastAccessedAtEpochMs = Date.now();
    void this.persistSingleItemEncrypted(target);
    this.presentationState = { itemId };
    this.render();

    const nav = typeof navigator !== 'undefined'
      ? (navigator as Navigator & {
          wakeLock?: {
            request: (
              type: 'screen'
            ) => Promise<{ release: () => Promise<void> }>;
          };
        })
      : undefined;
    if (nav?.wakeLock?.request) {
      void nav.wakeLock
        .request('screen')
        .then((sentinel) => {
          this.wakeLockSentinel = sentinel;
        })
        .catch(() => undefined);
    }
  }

  private closePresentationMode(): void {
    if (this.wakeLockSentinel) {
      void this.wakeLockSentinel.release().catch(() => undefined);
      this.wakeLockSentinel = null;
    }
    this.presentationState = null;
  }

  private openCameraModal(
    mode: CameraScanMode,
    targetContext?: 'lock' | 'restore'
  ): void {
    this.cameraController.stopStream();
    this.cameraModalState = {
      mode,
      targetContext,
      enhanceCrispDoc: true
    };
    this.render();
    this.startActiveCameraStream();
  }

  private startActiveCameraStream(): void {
    if (!this.cameraModalState) return;
    const videoEl =
      this.root.querySelector<HTMLVideoElement>('#sp-camera-video');
    if (!videoEl) return;

    const mode = this.cameraModalState.mode;
    const needsBarcodeLoop = mode === 'barcode' || mode === 'recovery_qr';
    void this.cameraController
      .startStream(
        videoEl,
        needsBarcodeLoop
          ? (res) => this.handleBarcodeDetectedFromCamera(res)
          : undefined
      )
      .catch((err) => {
        if (this.cameraModalState) {
          this.cameraModalState.error =
            err instanceof Error
              ? err.message
              : 'Camera stream unavailable — use image file fallback below';
          this.render();
        }
      });
  }

  private closeCameraModal(): void {
    this.cameraController.stopStream();
    this.cameraModalState = null;
  }

  private handleBarcodeDetectedFromCamera(res: ScannedBarcodeResult): void {
    if (!this.cameraModalState) return;
    const ctx = this.cameraModalState.targetContext;
    const mode = this.cameraModalState.mode;

    if (mode === 'recovery_qr') {
      const parsed = parseRecoverySecretKeyPayload(res.rawValue);
      if (!parsed) {
        this.cameraModalState.error =
          'QR scanned, but no SPK-... Recovery Key found';
        this.render();
        return;
      }
      this.closeCameraModal();
      if (ctx === 'restore' && this.restoreModalState) {
        this.restoreModalState.secretKey = parsed.secretKey;
        this.render();
      } else {
        this.demoSecretKey = parsed.secretKey;
        this.render();
        const skInput =
          this.root.querySelector<HTMLInputElement>('#sp-unlock-secret');
        if (skInput) skInput.value = parsed.secretKey;
      }
      this.showToast(`Scanned Recovery Key: ${parsed.secretKey.slice(0, 9)}…`);
      return;
    }

    if (mode === 'barcode' && this.editorDraft) {
      this.closeCameraModal();
      this.editorDraft.standardFieldValues.field_membership_id = res.rawValue;
      this.editorDraft.barcodeFormat = res.format;
      this.render();
      this.showToast(`Scanned ${res.format}: ${res.rawValue}`);
    }
  }

  private async persistSingleItemEncrypted(item: VaultItem): Promise<void> {
    const hlcPacked = this.hlc.nowPacked();
    const encRow = await this.crypto.encryptVaultItem(item, hlcPacked, {
      'chromeos-pwa-node': 1
    });
    await this.localDb.saveEncryptedRow(encRow);
  }

  private async persistAllItemsEncrypted(): Promise<void> {
    for (const item of this.items) {
      await this.persistSingleItemEncrypted(item);
    }
  }

  private bindGlobalShortcuts(): void {
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        if (this.cameraModalState) {
          this.closeCameraModal();
          this.render();
          return;
        }
        if (this.presentationState) {
          this.closePresentationMode();
          this.render();
          return;
        }
        if (this.activePreview) {
          this.closeScanPreview();
          this.render();
          return;
        }
        if (this.restoreModalState) {
          this.restoreModalState = null;
          this.render();
          return;
        }
        if (this.plainExportModalState) {
          this.plainExportModalState = null;
          this.render();
          return;
        }
        if (this.editorDraft) {
          this.editorDraft = null;
          this.render();
          return;
        }
      }
      const mod = e.ctrlKey || e.metaKey;
      if (!mod) return;
      const key = e.key.toLowerCase();
      if (key === 'f') {
        e.preventDefault();
        const searchEl = this.root.querySelector<HTMLInputElement>(
          '#sp-global-search'
        );
        searchEl?.focus();
        searchEl?.select();
      } else if (key === 'n') {
        e.preventDefault();
        if (this.crypto.isUnlocked) {
          this.openCreateItemModal();
        }
      } else if (key === 'l') {
        e.preventDefault();
        if (this.crypto.isUnlocked) {
          this.lockVault(false);
        }
      }
    });

    window.addEventListener('click', () => {
      if (this.contextMenu) {
        this.contextMenu = null;
        this.render();
      }
    });
  }

  private openCreateItemModal(): void {
    const defaultCat: VaultCategory = VAULT_CATEGORIES.includes(
      this.selectedNav as VaultCategory
    )
      ? (this.selectedNav as VaultCategory)
      : 'PAYMENT_CARD';

    this.editorDraft = {
      editingItemId: null,
      category: defaultCat,
      title: '',
      subtitle: '',
      paletteId: defaultCat === 'IDENTITY' ? 'forest_sage' : 'obsidian_slate',
      customColorHex: '#1E5B53',
      showVisualCard: true,
      barcodeFormat: 'CODE_128',
      tagsCsv: '',
      standardFieldValues: {},
      customFields: []
    };
    this.render();
  }

  private openEditItemModal(item: VaultItem): void {
    const stdKeys = new Set(
      this.defaultTemplateFields(item.category).map((t) => t.key)
    );
    const standardFieldValues: Record<string, string> = {};
    const customFields: DraftCustomField[] = [];

    for (const f of item.fields) {
      if (!f.isCustomLabel && stdKeys.has(f.labelKey)) {
        standardFieldValues[f.labelKey] = f.value;
      } else {
        customFields.push({
          id: f.id,
          label: f.isCustomLabel
            ? f.labelKey
            : this.i18n.resolveFieldLabel(f.labelKey, false, item.category),
          value: f.value,
          isSensitive:
            Boolean(f.isSensitive) || SENSITIVE_FIELD_KEYS.has(f.labelKey)
        });
      }
    }

    this.editorDraft = {
      editingItemId: item.id,
      category: item.category,
      title: item.title,
      subtitle: item.subtitle,
      paletteId: item.cardTheme?.paletteId ?? 'obsidian_slate',
      customColorHex: normalizeHexColor(item.cardTheme?.customColorHex),
      showVisualCard: item.cardTheme?.showVisualCard !== false,
      barcodeFormat: item.cardTheme?.barcodeFormat ?? 'CODE_128',
      tagsCsv: item.tags.join(', '),
      standardFieldValues,
      customFields
    };
    this.render();
  }

  private syncDraftFromDom(): void {
    if (!this.editorDraft) return;
    const titleEl = this.root.querySelector<HTMLInputElement>('#sp-edit-title');
    const subEl = this.root.querySelector<HTMLInputElement>('#sp-edit-subtitle');
    const tagsEl = this.root.querySelector<HTMLInputElement>('#sp-edit-tags');
    const mrzEl =
      this.root.querySelector<HTMLTextAreaElement>('#sp-mrz-input');
    const customHexEl = this.root.querySelector<HTMLInputElement>(
      '#sp-edit-custom-color-hex'
    );
    const showVisEl = this.root.querySelector<HTMLInputElement>(
      '#sp-edit-show-visual-card'
    );
    const barcodeFmtEl = this.root.querySelector<HTMLSelectElement>(
      '#sp-edit-barcode-format'
    );

    if (titleEl) this.editorDraft.title = titleEl.value;
    if (subEl) this.editorDraft.subtitle = subEl.value;
    if (tagsEl) this.editorDraft.tagsCsv = tagsEl.value;
    if (mrzEl) this.editorDraft.mrzRawInput = mrzEl.value;
    if (customHexEl) {
      this.editorDraft.customColorHex = normalizeHexColor(
        customHexEl.value,
        this.editorDraft.customColorHex
      );
    }
    if (showVisEl) {
      this.editorDraft.showVisualCard = showVisEl.checked;
    }
    if (barcodeFmtEl) {
      this.editorDraft.barcodeFormat = barcodeFmtEl.value as BarcodeFormat;
    }

    this.root
      .querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(
        '[data-std-field]'
      )
      .forEach((inp) => {
        const key = inp.dataset.stdField;
        if (key && this.editorDraft) {
          this.editorDraft.standardFieldValues[key] = inp.value;
        }
      });

    this.editorDraft.customFields.forEach((cf, idx) => {
      const lbl = this.root.querySelector<HTMLInputElement>(
        `[data-cf-label="${idx}"]`
      );
      const val = this.root.querySelector<HTMLInputElement>(
        `[data-cf-value="${idx}"]`
      );
      const sens = this.root.querySelector<HTMLInputElement>(
        `[data-cf-sens="${idx}"]`
      );
      if (lbl) cf.label = lbl.value;
      if (val) cf.value = val.value;
      if (sens) cf.isSensitive = sens.checked;
    });
  }

  private async duplicateVaultItem(itemId: string): Promise<void> {
    const source = this.items.find((i) => i.id === itemId);
    if (!source) return;
    const now = Date.now();
    const copy: VaultItem = {
      ...structuredClone(source),
      id: `item-${now}`,
      title: `${source.title} (Copy)`,
      fields: source.fields.map((f, idx) => ({
        ...f,
        id: `f-${now}-${idx}`
      })),
      createdAtEpochMs: now,
      updatedAtEpochMs: now
    };
    this.items.unshift(copy);
    this.selectedItemId = copy.id;
    await this.persistSingleItemEncrypted(copy);
    this.showToast(`${this.i18n.t('context_duplicate')}: ${copy.title}`);
  }

  private async deleteVaultItemWithTombstone(itemId: string): Promise<void> {
    const target = this.items.find((i) => i.id === itemId);
    if (!target) return;
    const confirmed = window.confirm(
      this.i18n.t('confirm_delete_item', { title: target.title })
    );
    if (!confirmed) return;

    const hlcPacked = this.hlc.nowPacked();
    const tombstone = await this.crypto.createTombstoneRow(
      target.id,
      target.category,
      hlcPacked,
      { 'chromeos-pwa-node': 2 }
    );
    await this.localDb.saveEncryptedRow(tombstone);
    for (const att of target.attachments) {
      await this.opfsStore.deleteEncryptedBlob(att.id);
      await this.sqliteDb.deleteLocalEncryptedBlob(att.id);
    }

    this.items = this.items.filter((i) => i.id !== itemId);
    this.selectedItemId = this.items[0]?.id ?? null;
    this.mobileDetailOpen = false;
    this.showToast(`Deleted "${target.title}" (CRDT tombstone recorded)`);
  }

  private async resolveEncryptedAttachmentBytes(
    item: VaultItem,
    att: EncryptedAttachmentMeta
  ): Promise<Uint8Array | undefined> {
    const opfsBytes = await this.opfsStore.readEncryptedBlob(att.id);
    if (opfsBytes) return opfsBytes;

    const sqlHit = await this.sqliteDb.loadLocalEncryptedBlob(att.id);
    if (sqlHit) {
      await this.opfsStore.writeEncryptedBlob(att.id, sqlHit.encryptedBytes);
      return sqlHit.encryptedBytes;
    }

    if (!att.ciphertextBase64 && this.syncCoordinator.isCloudSyncEnabled) {
      this.liveCloudConfig = this.firebaseService.toEndpointConfig(
        this.liveCloudConfig.bearerToken
      );
      this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
      this.gdriveAdapter.setLiveEndpointConfig(this.liveCloudConfig);

      const downloaded =
        await this.syncCoordinator.downloadMissingAttachmentBlobIfEnabled(
          'default-vault',
          att.id,
          att.sha256Hex
        );
      if (downloaded) {
        await this.opfsStore.writeEncryptedBlob(att.id, downloaded);
        await this.sqliteDb.saveLocalEncryptedBlob(
          'default-vault',
          att.id,
          downloaded,
          att.sha256Hex,
          true
        );
        att.blobSynced = true;
        await this.persistSingleItemEncrypted(item);
        return downloaded;
      }
    }
    return undefined;
  }

  private async openDecryptedAttachmentPreview(
    item: VaultItem,
    attachmentId: string
  ): Promise<void> {
    const att = item.attachments.find((a) => a.id === attachmentId);
    if (!att) return;
    try {
      const resolvedCipherBytes = await this.resolveEncryptedAttachmentBytes(
        item,
        att
      );
      const decryptedBytes = await this.crypto.decryptBinaryAttachment({
        attachmentId: att.id,
        ivHex: att.ivHex,
        expectedSha256Hex: att.sha256Hex,
        encryptedBytes: resolvedCipherBytes,
        ciphertextBase64: att.ciphertextBase64
      });
      this.closeScanPreview();
      const blob = new Blob([decryptedBytes as BlobPart], {
        type: att.mimeType || 'application/octet-stream'
      });
      this.activePreview = {
        fileName: att.fileName,
        mimeType: att.mimeType,
        side: att.side,
        sha256Hex: att.sha256Hex,
        objectUrl: URL.createObjectURL(blob)
      };
      this.render();
    } catch (err) {
      this.showToast(
        err instanceof Error ? err.message : 'Attachment decryption failed'
      );
    }
  }

  private buildCurrentEmergencyKitHtml(): string {
    const header = this.vaultHeaderStore.loadHeader();
    const kdf = this.crypto.currentKdfConfig;
    return buildEmergencyKitHtml({
      vaultId: header?.vaultId ?? `vault-${kdf.saltHex.slice(0, 12)}`,
      createdAtIso: header?.createdAtIso ?? new Date().toISOString(),
      secretKeyFormatted: this.demoSecretKey,
      kdf,
      passwordHint: this.emergencyKitHintDraft.trim() || undefined,
      cloudAccountEmail: this.firebaseService.currentUser?.email
    });
  }

  private downloadEmergencyKit(): void {
    const html = this.buildCurrentEmergencyKitHtml();
    this.downloadBlob(
      html,
      'safepocket-emergency-kit.html',
      'text/html;charset=utf-8'
    );
  }

  private printEmergencyKit(): void {
    const html = this.buildCurrentEmergencyKitHtml();
    if (typeof document === 'undefined') return;
    const iframe = document.createElement('iframe');
    iframe.style.position = 'fixed';
    iframe.style.width = '0';
    iframe.style.height = '0';
    iframe.style.border = '0';
    document.body.appendChild(iframe);
    const doc = iframe.contentDocument;
    if (doc) {
      doc.open();
      doc.write(html);
      doc.close();
      setTimeout(() => {
        iframe.contentWindow?.focus();
        iframe.contentWindow?.print();
        setTimeout(() => iframe.remove(), 2500);
      }, 150);
    } else {
      iframe.remove();
      this.downloadEmergencyKit();
    }
  }

  private getFilteredItems(): VaultItem[] {
    const filter: {
      category?: VaultCategory | null;
      favoritesOnly?: boolean;
      expiringOnly?: boolean;
      tag?: string | null;
      warningWindowDays?: ExpiryWindowDays;
    } = {
      tag: this.activeTagFilter,
      warningWindowDays: this.expiryWindowDays
    };
    if (this.selectedNav === 'FAVORITES') filter.favoritesOnly = true;
    else if (this.selectedNav === 'EXPIRING') filter.expiringOnly = true;
    else if (VAULT_CATEGORIES.includes(this.selectedNav as VaultCategory)) {
      filter.category = this.selectedNav as VaultCategory;
    }
    const matched = searchVaultItems(
      this.items,
      this.searchQuery,
      this.i18n,
      filter
    );
    const effectiveSort: VaultSortMode =
      this.selectedNav === 'EXPIRING' && this.sortMode === 'pinned_recent'
        ? 'expiring_soon'
        : this.sortMode;
    return sortVaultItems(
      matched,
      effectiveSort,
      this.i18n.activeLocale,
      Date.now(),
      this.expiryWindowDays
    );
  }

  private showToast(msg: string): void {
    this.toastMessage = msg;
    this.render();
    setTimeout(() => {
      if (this.toastMessage === msg) {
        this.toastMessage = null;
        this.render();
      }
    }, 3200);
  }

  render(): void {
    this.applyThemeToDocument();
    const activeSearchInput =
      document.activeElement?.id === 'sp-global-search'
        ? (document.activeElement as HTMLInputElement)
        : null;
    const caretPos = activeSearchInput?.selectionStart ?? null;

    if (this.showOnboardingWizard) {
      this.root.innerHTML = this.renderOnboardingWizardModal();
      this.wireOnboardingEvents();
      return;
    }

    if (!this.crypto.isUnlocked) {
      this.root.innerHTML = `
        ${this.renderLockScreen()}
        ${this.cameraModalState ? this.renderCameraModal() : ''}
      `;
      this.wireLockScreenEvents();
      return;
    }

    const filtered = this.getFilteredItems();
    const selectedItem =
      filtered.find((i) => i.id === this.selectedItemId) ??
      filtered[0] ??
      null;
    if (selectedItem && selectedItem.id !== this.selectedItemId) {
      this.selectedItemId = selectedItem.id;
    }

    const expirySummary = summarizeVaultExpiries(
      this.items,
      Date.now(),
      this.expiryWindowDays
    );
    const allTags = extractVaultTags(this.items);
    const versionBanner = this.i18n.t('update_version_display', {
      version: APP_BUILD_INFO.versionName,
      build: APP_BUILD_INFO.versionCode
    });
    this.root.innerHTML = `
      ${this.renderClipboardBanner()}
      <div class="sp-shell ${this.mobileDetailOpen ? 'mobile-detail-open' : ''}">
        <aside class="sp-sidebar" aria-label="Vault Navigation">
          <div>
            <div class="sp-brand">
              <div class="sp-brand-left">
                <div class="sp-logo-mark" aria-hidden="true">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1">
                    <rect x="3" y="5" width="18" height="15" rx="3"></rect>
                    <path d="M3 10h18"></path>
                    <circle cx="12" cy="15" r="1.5"></circle>
                  </svg>
                </div>
                <h1 class="sp-brand-title">${this.escape(this.i18n.t('app_name'))}</h1>
              </div>
              <button
                type="button"
                id="sp-quick-theme-toggle"
                class="sp-theme-toggle-btn"
                title="${this.escape(this.themeToggleTooltip())}"
                aria-label="${this.escape(this.i18n.t('settings_theme_title'))}"
              >${this.renderThemeToggleIconSvg()}</button>
            </div>

            <nav class="sp-nav-group">
              ${this.renderNavBtn('ALL', this.i18n.t('nav_all_items'), this.items.length)}
              ${this.renderNavBtn(
                'FAVORITES',
                this.i18n.t('nav_favorites'),
                this.items.filter((i) => i.favorite).length
              )}
              ${this.renderNavBtn(
                'EXPIRING',
                this.i18n.t('nav_expiring'),
                expirySummary.totalAttentionCount
              )}
            </nav>

            <nav class="sp-nav-group">
              ${this.renderNavBtn(
                'PAYMENT_CARD',
                this.i18n.t('nav_payment_cards'),
                this.items.filter((i) => i.category === 'PAYMENT_CARD').length
              )}
              ${this.renderNavBtn(
                'BANK_ACCOUNT',
                this.i18n.t('nav_bank_accounts'),
                this.items.filter((i) => i.category === 'BANK_ACCOUNT').length
              )}
              ${this.renderNavBtn(
                'INSURANCE_CARD',
                this.i18n.t('nav_insurance_cards'),
                this.items.filter((i) => i.category === 'INSURANCE_CARD').length
              )}
              ${this.renderNavBtn(
                'MEMBERSHIP_CARD',
                this.i18n.t('nav_membership_cards'),
                this.items.filter((i) => i.category === 'MEMBERSHIP_CARD').length
              )}
              ${this.renderNavBtn(
                'IDENTITY',
                this.i18n.t('nav_identities'),
                this.items.filter((i) => i.category === 'IDENTITY').length
              )}
              ${this.renderNavBtn(
                'OFFICIAL_DOCUMENT',
                this.i18n.t('nav_official_documents'),
                this.items.filter((i) => i.category === 'OFFICIAL_DOCUMENT').length
              )}
              ${this.renderNavBtn(
                'SECURE_NOTE',
                this.i18n.t('nav_secure_notes'),
                this.items.filter((i) => i.category === 'SECURE_NOTE').length
              )}
            </nav>

            <nav class="sp-nav-group">
              ${this.renderNavBtn('SETTINGS', this.i18n.t('nav_settings'))}
            </nav>
          </div>

          <div class="sp-sidebar-footer">
            <div class="sp-lang-quick" role="group" aria-label="Language Switcher">
              ${SUPPORTED_LOCALES.map(
                (loc) => `
                <button
                  type="button"
                  id="sp-lang-${loc.code}"
                  class="sp-lang-pill ${
                    this.i18n.activeLocale === loc.code ? 'is-active' : ''
                  }"
                  data-locale="${loc.code}"
                  title="${this.escape(loc.nativeName)}"
                >${loc.code.toUpperCase()}</button>
              `
              ).join('')}
            </div>

            <button type="button" id="sp-lock-btn" class="sp-btn" style="width:100%;justify-content:space-between;">
              <span>${this.escape(this.i18n.t('action_lock_vault'))}</span>
              <span style="font-family:var(--font-mono);font-size:10.5px;color:var(--text-muted);">Ctrl+L</span>
            </button>

            <div class="sp-version-subtle" id="sp-sidebar-version">
              <span>${this.escape(versionBanner)}</span>
            </div>
          </div>
        </aside>

        <section class="sp-list-pane" aria-label="Vault Item List">
          <div class="sp-list-header">
            <div class="sp-search-wrap">
              <span class="sp-search-icon" aria-hidden="true">⌕</span>
              <input
                id="sp-global-search"
                class="sp-search-input"
                type="search"
                value="${this.escape(this.searchQuery)}"
                placeholder="${this.escape(this.i18n.t('search_placeholder'))}"
                autocomplete="off"
              />
              <span class="sp-kbd">Ctrl+F</span>
            </div>
            <div class="sp-list-toolbar">
              <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
                <span class="sp-list-meta">${this.escape(
                  this.i18n.plural('items_count', filtered.length)
                )}</span>
                <select
                  id="sp-sort-mode"
                  class="sp-sort-select"
                  aria-label="${this.escape(this.i18n.t('sort_label'))}"
                >
                  <option value="pinned_recent" ${
                    this.sortMode === 'pinned_recent' ? 'selected' : ''
                  }>${this.escape(this.i18n.t('sort_pinned_recent'))}</option>
                  <option value="alphabetical" ${
                    this.sortMode === 'alphabetical' ? 'selected' : ''
                  }>${this.escape(this.i18n.t('sort_alphabetical'))}</option>
                  <option value="expiring_soon" ${
                    this.sortMode === 'expiring_soon' ? 'selected' : ''
                  }>${this.escape(this.i18n.t('sort_expiring_soon'))}</option>
                  <option value="updated_desc" ${
                    this.sortMode === 'updated_desc' ? 'selected' : ''
                  }>${this.escape(this.i18n.t('sort_updated_desc'))}</option>
                </select>
              </div>
              <button type="button" id="sp-new-item-btn" class="sp-btn sp-btn-primary">
                + ${this.escape(this.i18n.t('action_new_item'))}
              </button>
            </div>
            ${
              allTags.length > 0
                ? `<div class="sp-tag-filter-strip" role="group" aria-label="Tag Filter">
                     ${
                       this.activeTagFilter
                         ? `<button type="button" id="sp-clear-tag-filter" class="sp-tag-chip is-active">${this.escape(
                             this.i18n.t('filter_clear_tag', {
                               tag: this.activeTagFilter
                             })
                           )}</button>`
                         : ''
                     }
                     ${allTags
                       .slice(0, 12)
                       .map((entry) => {
                         const active =
                           this.activeTagFilter?.toLowerCase() ===
                           entry.tag.toLowerCase();
                         return `<button type="button" class="sp-tag-chip ${
                           active ? 'is-active' : ''
                         }" data-filter-tag="${this.escape(
                           entry.tag
                         )}">#${this.escape(entry.tag)} (${entry.count})</button>`;
                       })
                       .join('')}
                   </div>`
                : ''
            }
          </div>

          ${
            this.selectedNav === 'EXPIRING' &&
            expirySummary.totalAttentionCount > 0
              ? `<div class="sp-expiry-banner" role="status">
                   <span>⏱ ${this.escape(
                     this.i18n.t('expiry_summary_banner', {
                       expired: expirySummary.expiredCount,
                       critical: expirySummary.criticalCount,
                       warning: expirySummary.warningCount
                     })
                   )}</span>
                 </div>`
              : ''
          }

          <div class="sp-items-scroll">
            ${
              filtered.length === 0
                ? `<div style="padding:28px 16px;text-align:center;color:var(--text-muted);">
                     <div style="font-weight:600;color:var(--text-secondary);margin-bottom:4px;">${this.escape(
                       this.i18n.t('search_empty_title')
                     )}</div>
                     <div style="font-size:12px;">${this.escape(
                       this.i18n.t('search_empty_subtitle')
                     )}</div>
                   </div>`
                : filtered
                    .map((item) =>
                      this.renderItemListRow(item, selectedItem?.id === item.id)
                    )
                    .join('')
            }
          </div>
        </section>

        <main class="sp-inspector" id="sp-inspector-pane">
          <div class="sp-inspector-inner">
            ${
              this.selectedNav === 'SETTINGS'
                ? this.renderSettingsAndAboutView()
                : selectedItem
                  ? this.renderItemInspector(selectedItem)
                  : `<div style="padding:48px 0;text-align:center;color:var(--text-muted);display:flex;flex-direction:column;align-items:center;gap:14px;">
                       <div>${this.escape(this.i18n.t('app_tagline'))}</div>
                       <button type="button" id="sp-empty-new-btn" class="sp-btn sp-btn-primary">
                         + ${this.escape(this.i18n.t('action_new_item'))}
                       </button>
                     </div>`
            }
          </div>
        </main>
      </div>

      ${this.renderMobileBottomNav()}
      ${this.editorDraft ? this.renderItemEditorModal() : ''}
      ${this.activePreview ? this.renderScanPreviewModal() : ''}
      ${this.presentationState ? this.renderPresentationModal() : ''}
      ${this.restoreModalState ? this.renderRestoreBackupModal() : ''}
      ${this.plainExportModalState ? this.renderPlainExportModal() : ''}
      ${this.cameraModalState ? this.renderCameraModal() : ''}
      ${this.contextMenu ? this.renderContextMenu() : ''}
    `;

    this.wireShellEvents();

    if (activeSearchInput) {
      const restored =
        this.root.querySelector<HTMLInputElement>('#sp-global-search');
      if (restored) {
        restored.focus();
        if (caretPos !== null) {
          restored.setSelectionRange(caretPos, caretPos);
        }
      }
    }
  }

  private renderOnboardingWizardModal(): string {
    const entropy = estimateMasterPasswordEntropy(this.onboardingPassword);
    const pct = Math.min(100, Math.max(12, Math.round((entropy.bits / 110) * 100)));

    return `
      <div class="sp-overlay">
        <form class="sp-modal-card" id="sp-onboarding-form" style="max-width:540px;">
          <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:12px;flex-wrap:wrap;">
            <div style="display:flex;align-items:center;gap:10px;min-width:0;">
              <div class="sp-logo-mark">🛡</div>
              <div>
                <h1 style="margin:0;font-size:18px;">${this.escape(
                  this.i18n.t('onboarding_welcome_title')
                )}</h1>
                <div style="font-size:12px;color:var(--text-secondary);">${this.escape(
                  this.i18n.t('app_tagline')
                )}</div>
              </div>
            </div>
            <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
              <button
                type="button"
                id="sp-onboard-theme-toggle"
                class="sp-theme-toggle-btn"
                title="${this.escape(this.themeToggleTooltip())}"
              >${this.renderThemeToggleIconSvg()}</button>
              <div class="sp-lang-quick">
                ${SUPPORTED_LOCALES.map(
                  (loc) => `
                  <button
                    type="button"
                    class="sp-lang-pill ${
                      this.i18n.activeLocale === loc.code ? 'is-active' : ''
                    }"
                    data-onboard-locale="${loc.code}"
                  >${loc.code.toUpperCase()}</button>
                `
                ).join('')}
              </div>
            </div>
          </div>

          <div style="font-size:12.5px;color:var(--text-secondary);background:var(--surface-card);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:10px 12px;">
            ✓ <strong>${this.escape(this.i18n.t('sync_provider_none'))}</strong> — ${this.escape(
              this.i18n.t('sync_enable_cloud_desc')
            )}
          </div>

          <label style="font-size:12px;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('security_master_password'))}
            <input
              id="sp-onboarding-password"
              type="password"
              class="sp-input"
              value="${this.escape(this.onboardingPassword)}"
              required
              minlength="8"
              style="margin-top:4px;"
            />
            <div class="sp-entropy-bar">
              <div class="sp-entropy-fill" style="width:${pct}%"></div>
            </div>
            <div id="sp-onboarding-entropy-label" style="font-size:11px;font-family:var(--font-mono);color:var(--accent-primary);margin-top:4px;">
              ${this.escape(
                this.i18n.t('onboarding_password_strength', {
                  bits: entropy.bits,
                  rating: entropy.rating
                })
              )}
            </div>
          </label>

          <div style="background:var(--bg-canvas);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:11px 12px;display:flex;flex-direction:column;gap:8px;">
            <div style="font-size:11.5px;font-weight:600;color:var(--text-secondary);">
              ${this.escape(this.i18n.t('security_secret_key'))}
            </div>
            <div style="font-family:var(--font-mono);font-size:13px;font-weight:650;color:var(--accent-primary);word-break:break-all;">
              ${this.escape(this.demoSecretKey)}
            </div>
            <input
              id="sp-onboard-kit-hint"
              type="text"
              class="sp-input"
              value="${this.escape(this.emergencyKitHintDraft)}"
              placeholder="${this.escape(
                this.i18n.t('emergency_kit_hint_placeholder')
              )}"
              style="font-size:12px;"
            />
            <div style="display:flex;gap:8px;flex-wrap:wrap;">
              <button type="button" id="sp-onboard-print-kit" class="sp-btn sp-btn-tonal">
                🖨 ${this.escape(this.i18n.t('emergency_kit_print_btn'))}
              </button>
              <button type="button" id="sp-onboard-download-kit" class="sp-btn">
                ⬇ ${this.escape(
                  this.i18n.t('onboarding_download_emergency_kit')
                )}
              </button>
            </div>
          </div>

          <div style="display:flex;flex-direction:column;gap:8px;margin-top:4px;">
            <button type="button" id="sp-onboard-quick-sample" class="sp-btn sp-btn-primary" style="width:100%;min-height:40px;">
              ⚡ ${this.escape(this.i18n.t('onboarding_quick_demo'))}
            </button>
            <button type="submit" class="sp-btn" style="width:100%;">
              ${this.escape(this.i18n.t('onboarding_create_vault'))}
            </button>
          </div>
        </form>
      </div>
    `;
  }

  private wireOnboardingEvents(): void {
    this.root
      .querySelector('#sp-onboard-theme-toggle')
      ?.addEventListener('click', () => {
        this.toggleQuickTheme();
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-onboard-locale]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          this.i18n.setLocaleOverride(
            btn.dataset.onboardLocale as SupportedLocale
          );
        });
      });

    const pwInput = this.root.querySelector<HTMLInputElement>(
      '#sp-onboarding-password'
    );
    pwInput?.addEventListener('input', () => {
      this.onboardingPassword = pwInput.value;
      const entropy = estimateMasterPasswordEntropy(this.onboardingPassword);
      const pct = Math.min(
        100,
        Math.max(12, Math.round((entropy.bits / 110) * 100))
      );
      const fill = this.root.querySelector<HTMLElement>('.sp-entropy-fill');
      const lbl = this.root.querySelector<HTMLElement>(
        '#sp-onboarding-entropy-label'
      );
      if (fill) fill.style.width = `${pct}%`;
      if (lbl) {
        lbl.textContent = this.i18n.t('onboarding_password_strength', {
          bits: entropy.bits,
          rating: entropy.rating
        });
      }
    });

    const hintInput = this.root.querySelector<HTMLInputElement>(
      '#sp-onboard-kit-hint'
    );
    hintInput?.addEventListener('input', () => {
      this.emergencyKitHintDraft = hintInput.value;
    });

    this.root
      .querySelector('#sp-onboard-print-kit')
      ?.addEventListener('click', () => {
        this.printEmergencyKit();
      });

    this.root
      .querySelector('#sp-onboard-download-kit')
      ?.addEventListener('click', () => {
        this.downloadEmergencyKit();
      });

    this.root
      .querySelector('#sp-onboard-quick-sample')
      ?.addEventListener('click', async () => {
        const pw = pwInput?.value.trim() || DEMO_DEFAULT_PASSWORD;
        await this.initializeVaultFromOnboarding(pw, true);
      });

    this.root
      .querySelector<HTMLFormElement>('#sp-onboarding-form')
      ?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const pw = pwInput?.value.trim() ?? '';
        if (!pw) return;
        await this.initializeVaultFromOnboarding(pw, false);
      });
  }

  private renderClipboardBanner(): string {
    if (this.toastMessage) {
      return `<div class="sp-clipboard-banner" role="status"><span>✓ ${this.escape(
        this.toastMessage
      )}</span></div>`;
    }
    if (this.clipboardSecondsLeft !== null) {
      return `
        <div class="sp-clipboard-banner" role="status">
          <span>🛡 ${this.escape(
            this.i18n.t('clipboard_hygiene_active', {
              seconds: this.clipboardSecondsLeft
            })
          )}</span>
          <button type="button" id="sp-scrub-now-btn" class="sp-btn" style="padding:3px 9px;min-height:26px;font-size:11.5px;">
            Clear Now
          </button>
        </div>
      `;
    }
    if (this.clipboardJustCleared) {
      return `
        <div class="sp-clipboard-banner" role="status">
          <span>✓ ${this.escape(this.i18n.t('clipboard_hygiene_cleared'))}</span>
        </div>
      `;
    }
    return '';
  }

  private renderNavBtn(
    id: NavSelection,
    label: string,
    count?: number
  ): string {
    const active = this.selectedNav === id ? 'is-active' : '';
    return `
      <button
        type="button"
        id="sp-nav-${id.toLowerCase()}"
        class="sp-nav-btn ${active}"
        data-nav="${id}"
      >
        <span class="sp-nav-label">${this.escape(label)}</span>
        ${count !== undefined ? `<span class="sp-nav-count">${count}</span>` : ''}
      </button>
    `;
  }

  private renderItemListRow(item: VaultItem, isSelected: boolean): string {
    const exp = computeExpiryStatus(item, Date.now(), this.expiryWindowDays);
    let badgeHtml = '';
    if (exp.severity === 'critical') {
      badgeHtml = `<span class="sp-badge sp-badge-critical">⚡ ${this.escape(
        this.i18n.plural('expiry_critical', exp.daysRemaining, {
          days: exp.daysRemaining
        })
      )}</span>`;
    } else if (exp.severity === 'warning') {
      badgeHtml = `<span class="sp-badge sp-badge-warn">${this.escape(
        this.i18n.plural('expiry_warning', exp.daysRemaining, {
          days: exp.daysRemaining
        })
      )}</span>`;
    } else if (exp.severity === 'expired') {
      badgeHtml = `<span class="sp-badge sp-badge-expired">${this.escape(
        this.i18n.plural('expiry_expired', Math.abs(exp.daysRemaining), {
          days: Math.abs(exp.daysRemaining)
        })
      )}</span>`;
    }

    return `
      <button
        type="button"
        id="sp-item-row-${item.id}"
        class="sp-item-row ${isSelected ? 'is-selected' : ''}"
        data-select-item="${this.escape(item.id)}"
      >
        <div class="sp-item-row-top">
          <span class="sp-item-title">${item.pinned ? '📌 ' : ''}${
            item.favorite ? '★ ' : ''
          }${this.escape(item.title)}</span>
        </div>
        <span class="sp-item-subtitle">${this.escape(
          item.subtitle || this.i18n.resolveCategoryName(item.category)
        )}</span>
        ${badgeHtml ? `<div>${badgeHtml}</div>` : ''}
      </button>
    `;
  }

  private renderItemInspector(item: VaultItem): string {
    const exp = computeExpiryStatus(item, Date.now(), this.expiryWindowDays);
    const expiryField = item.fields.find((f) => f.labelKey === 'field_expiry_date');
    let expiryBadge = '';
    if (exp.severity === 'critical') {
      expiryBadge = `<span class="sp-badge sp-badge-critical">⚡ ${this.escape(
        this.i18n.plural('expiry_critical', exp.daysRemaining, {
          days: exp.daysRemaining
        })
      )}</span>`;
    } else if (exp.severity === 'warning') {
      expiryBadge = `<span class="sp-badge sp-badge-warn">⚠ ${this.escape(
        this.i18n.plural('expiry_warning', exp.daysRemaining, {
          days: exp.daysRemaining
        })
      )}</span>`;
    } else if (exp.severity === 'expired') {
      expiryBadge = `<span class="sp-badge sp-badge-expired">⚠ ${this.escape(
        this.i18n.plural('expiry_expired', Math.abs(exp.daysRemaining), {
          days: Math.abs(exp.daysRemaining)
        })
      )}</span>`;
    } else if (exp.severity === 'valid' && expiryField) {
      expiryBadge = `<span class="sp-badge sp-badge-ok">${this.escape(
        this.i18n.t('expiry_valid', {
          date: this.i18n.formatDate(expiryField.value)
        })
      )}</span>`;
    }

    const showVisualCard =
      item.category === 'PAYMENT_CARD' ||
      item.category === 'BANK_ACCOUNT' ||
      item.category === 'INSURANCE_CARD' ||
      item.category === 'MEMBERSHIP_CARD' ||
      item.category === 'OFFICIAL_DOCUMENT' ||
      (item.category === 'IDENTITY' && item.cardTheme?.showVisualCard !== false);

    return `
      <div>
        <button type="button" id="sp-mobile-back" class="sp-btn" style="margin-bottom:12px;display:none;">
          ← ${this.escape(this.i18n.t('nav_all_items'))}
        </button>
        <div class="sp-inspector-header">
          <div>
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              <span style="font-size:11.5px;font-weight:650;color:var(--accent-primary);text-transform:uppercase;letter-spacing:0.05em;">
                ${this.escape(this.i18n.resolveCategoryName(item.category))}
              </span>
              ${expiryBadge}
            </div>
            <h2 class="sp-inspector-title">${this.escape(item.title)}</h2>
            <div style="font-size:13px;color:var(--text-secondary);">${this.escape(
              item.subtitle
            )}</div>
          </div>

          <div style="display:flex;gap:6px;flex-wrap:wrap;">
            <button type="button" id="sp-toggle-pin" class="sp-btn ${
              item.pinned ? 'sp-btn-tonal' : ''
            }">
              📌 ${this.escape(
                this.i18n.t(item.pinned ? 'action_unpin' : 'action_pin')
              )}
            </button>
            <button type="button" id="sp-toggle-fav" class="sp-btn">
              ${item.favorite ? '★' : '☆'} ${this.escape(this.i18n.t('action_favorite'))}
            </button>
            <button type="button" id="sp-edit-item-btn" class="sp-btn sp-btn-tonal">
              ✎ ${this.escape(this.i18n.t('action_edit'))}
            </button>
            <button type="button" id="sp-duplicate-item-btn" class="sp-btn">
              ⧉ ${this.escape(this.i18n.t('context_duplicate'))}
            </button>
            <button type="button" id="sp-delete-item-btn" class="sp-btn sp-btn-danger">
              ✕ ${this.escape(this.i18n.t('action_delete'))}
            </button>
          </div>
        </div>
      </div>

      ${showVisualCard ? this.renderVisualCardHero(item) : ''}

      <div class="sp-fields-list">
        ${item.fields.map((field) => this.renderFieldRow(item, field)).join('')}
      </div>

      ${
        item.tags.length > 0
          ? `<div style="display:flex;gap:6px;flex-wrap:wrap;">
               ${item.tags
                 .map(
                   (tag) =>
                     `<button type="button" class="sp-tag-chip ${
                       this.activeTagFilter?.toLowerCase() === tag.toLowerCase()
                         ? 'is-active'
                         : ''
                     }" data-filter-tag="${this.escape(tag)}">#${this.escape(
                       tag
                     )}</button>`
                 )
                 .join('')}
             </div>`
          : ''
      }

      ${this.renderAttachmentsSection(item)}
    `;
  }

  private renderVisualCardHero(item: VaultItem, isPresentation = false): string {
    const palette = item.cardTheme?.paletteId ?? 'obsidian_slate';
    const customStyleAttr =
      palette === 'custom'
        ? ` style="${this.escape(
            buildCustomCardStyle(item.cardTheme?.customColorHex).inlineStyleAttr
          )}"`
        : '';
    const presentBtn = isPresentation
      ? ''
      : `<button
           type="button"
           class="sp-btn"
           style="padding:4px 9px;min-height:26px;font-size:11.5px;"
           data-present-card="${this.escape(item.id)}"
         >⛶ ${this.escape(this.i18n.t('action_present_card'))}</button>`;

    if (item.category === 'PAYMENT_CARD') {
      const pan =
        item.fields.find((f) => f.labelKey === 'field_card_number')?.value ?? '';
      const holder =
        item.fields.find((f) => f.labelKey === 'field_cardholder')?.value ?? '';
      const expiry =
        item.fields.find((f) => f.labelKey === 'field_expiry_date')?.value ?? '';
      const bank =
        item.fields.find((f) => f.labelKey === 'field_issuing_bank')?.value ?? '';
      const panFieldId =
        item.fields.find((f) => f.labelKey === 'field_card_number')?.id ?? '';
      const revealed = this.revealedFieldIds.has(panFieldId) || isPresentation;
      const maskedPan = revealed
        ? pan
        : `•••• •••• •••• ${pan.replace(/\s+/g, '').slice(-4)}`;

      return `
        <div class="sp-visual-card" data-palette="${palette}"${customStyleAttr}>
          <div class="sp-card-top-row">
            <div class="sp-emv-chip" aria-hidden="true"></div>
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              <span style="font-weight:650;font-size:13px;opacity:0.9;">${this.escape(
                bank || item.cardTheme?.networkBrand || 'CARD'
              )}</span>
              ${presentBtn}
            </div>
          </div>
          <div class="sp-card-pan">
            <span>${this.escape(maskedPan)}</span>
            <button
              type="button"
              class="sp-btn"
              style="padding:4px 10px;min-height:28px;"
              data-copy-value="${this.escape(pan.replace(/\s+/g, ''))}"
              data-copy-sensitive="true"
            >${this.escape(this.i18n.t('action_copy'))}</button>
          </div>
          <div class="sp-card-bottom-row">
            <div>
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_cardholder')
              )}</div>
              <div style="font-weight:600;letter-spacing:0.04em;">${this.escape(
                holder
              )}</div>
            </div>
            <div style="text-align:right;">
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_expiry_date')
              )}</div>
              <div style="font-family:var(--font-mono);font-weight:600;">${this.escape(
                this.i18n.formatDate(expiry)
              )}</div>
            </div>
          </div>
        </div>
      `;
    }

    if (item.category === 'BANK_ACCOUNT') {
      const ibanField = item.fields.find((f) => f.labelKey === 'field_iban');
      const rawIban = ibanField?.value ?? '';
      const compactIban = rawIban.replace(/\s+/g, '');
      const revealed = ibanField
        ? this.revealedFieldIds.has(ibanField.id) || isPresentation
        : false;
      const maskedIban = revealed
        ? rawIban
        : compactIban.length >= 8
          ? `${compactIban.slice(0, 4)} •••• •••• ${compactIban.slice(-4)}`
          : '•••• ••••';
      const holder =
        item.fields.find((f) => f.labelKey === 'field_account_holder')?.value ??
        '';
      const bic =
        item.fields.find((f) => f.labelKey === 'field_bic')?.value ?? '';
      const bank =
        item.fields.find((f) => f.labelKey === 'field_issuing_bank')?.value ??
        item.title;
      const acctType =
        item.fields.find((f) => f.labelKey === 'field_account_type')?.value ??
        this.i18n.t('category_bank_account');

      return `
        <div class="sp-visual-card" data-palette="${palette}"${customStyleAttr}>
          <div class="sp-card-top-row">
            <div style="display:flex;align-items:center;gap:10px;">
              <div class="sp-card-emblem" aria-hidden="true">🏦</div>
              <span style="font-weight:650;font-size:14px;">${this.escape(
                bank
              )}</span>
            </div>
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              <span class="sp-card-pill">${this.escape(acctType)}</span>
              ${presentBtn}
            </div>
          </div>
          <div class="sp-card-pan" style="font-size:17px;letter-spacing:0.08em;">
            <span>${this.escape(maskedIban)}</span>
            ${
              compactIban
                ? `<button
                     type="button"
                     class="sp-btn"
                     style="padding:4px 10px;min-height:28px;"
                     data-copy-value="${this.escape(compactIban)}"
                     data-copy-sensitive="true"
                   >${this.escape(this.i18n.t('action_copy'))}</button>`
                : ''
            }
          </div>
          <div class="sp-card-bottom-row">
            <div>
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_account_holder')
              )}</div>
              <div style="font-weight:600;letter-spacing:0.03em;">${this.escape(
                holder
              )}</div>
            </div>
            <div style="text-align:right;">
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_bic')
              )}</div>
              <div style="font-family:var(--font-mono);font-weight:600;">${this.escape(
                bic || 'SEPA'
              )}</div>
            </div>
          </div>
        </div>
      `;
    }

    if (item.category === 'INSURANCE_CARD') {
      const numField = item.fields.find(
        (f) => f.labelKey === 'field_insurance_number'
      );
      const rawNum = numField?.value ?? '';
      const revealed = numField
        ? this.revealedFieldIds.has(numField.id) || isPresentation
        : false;
      const maskedNum = revealed
        ? rawNum
        : rawNum.length > 4
          ? `${rawNum.slice(0, 1)}•••••${rawNum.slice(-3)}`
          : '••••••';
      const insurer =
        item.fields.find((f) => f.labelKey === 'field_insurer_name')?.value ??
        item.title;
      const holder =
        item.fields.find((f) => f.labelKey === 'field_full_name')?.value ?? '';
      const ik =
        item.fields.find((f) => f.labelKey === 'field_insurer_id')?.value ?? '';
      const plan =
        item.fields.find((f) => f.labelKey === 'field_coverage_plan')?.value ??
        'eGK / EHIC';
      const expiry =
        item.fields.find((f) => f.labelKey === 'field_expiry_date')?.value ?? '';

      return `
        <div class="sp-visual-card" data-palette="${palette}"${customStyleAttr}>
          <div class="sp-card-top-row">
            <div style="display:flex;align-items:center;gap:10px;">
              <div class="sp-card-emblem" aria-hidden="true">🩺</div>
              <div>
                <div style="font-weight:650;font-size:14px;">${this.escape(
                  insurer
                )}</div>
                ${
                  ik
                    ? `<div style="font-size:10.5px;font-family:var(--font-mono);opacity:0.78;">IK / ID: ${this.escape(
                        ik
                      )}</div>`
                    : ''
                }
              </div>
            </div>
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              <span class="sp-card-pill">${this.escape(plan)}</span>
              ${presentBtn}
            </div>
          </div>
          <div class="sp-card-pan">
            <span>${this.escape(maskedNum)}</span>
            ${
              rawNum
                ? `<button
                     type="button"
                     class="sp-btn"
                     style="padding:4px 10px;min-height:28px;"
                     data-copy-value="${this.escape(rawNum)}"
                     data-copy-sensitive="true"
                   >${this.escape(this.i18n.t('action_copy'))}</button>`
                : ''
            }
          </div>
          <div class="sp-card-bottom-row">
            <div>
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_full_name')
              )}</div>
              <div style="font-weight:600;">${this.escape(holder)}</div>
            </div>
            <div style="text-align:right;">
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_expiry_date')
              )}</div>
              <div style="font-family:var(--font-mono);font-weight:600;">${this.escape(
                expiry ? this.i18n.formatDate(expiry) : '—'
              )}</div>
            </div>
          </div>
        </div>
      `;
    }

    if (item.category === 'MEMBERSHIP_CARD') {
      const org =
        item.fields.find((f) => f.labelKey === 'field_organization')?.value ??
        item.title;
      const memberName =
        item.fields.find((f) => f.labelKey === 'field_member_name')?.value ?? '';
      const memberId =
        item.fields.find((f) => f.labelKey === 'field_membership_id')?.value ??
        '';
      const tier =
        item.fields.find((f) => f.labelKey === 'field_membership_tier')?.value ??
        this.i18n.t('category_membership_card');
      const expiry =
        item.fields.find((f) => f.labelKey === 'field_expiry_date')?.value ?? '';
      const renderedCode = renderScannableBarcodeSvg(
        memberId || item.id,
        item.cardTheme?.barcodeFormat,
        { heightPx: 44, maxWidthPx: 340, qrSizePx: 112 }
      );

      return `
        <div class="sp-visual-card" data-palette="${palette}"${customStyleAttr}>
          <div class="sp-card-top-row">
            <div style="display:flex;align-items:center;gap:10px;">
              <div class="sp-card-emblem" aria-hidden="true">🎫</div>
              <span style="font-weight:650;font-size:14px;">${this.escape(
                org
              )}</span>
            </div>
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              <span class="sp-card-pill">${this.escape(tier)}</span>
              ${presentBtn}
            </div>
          </div>
          <div class="sp-card-pan" style="font-size:17.5px;">
            <span>${this.escape(memberId)}</span>
            ${
              memberId
                ? `<button
                     type="button"
                     class="sp-btn"
                     style="padding:4px 10px;min-height:28px;"
                     data-copy-value="${this.escape(memberId)}"
                     data-copy-sensitive="false"
                   >${this.escape(this.i18n.t('action_copy'))}</button>`
                : ''
            }
          </div>
          <div class="sp-barcode-strip">
            ${renderedCode.svg}
          </div>
          <div class="sp-card-bottom-row">
            <div>
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_member_name')
              )}</div>
              <div style="font-weight:600;">${this.escape(memberName)}</div>
            </div>
            <div style="text-align:right;">
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_expiry_date')
              )}</div>
              <div style="font-family:var(--font-mono);font-weight:600;">${this.escape(
                expiry ? this.i18n.formatDate(expiry) : '—'
              )}</div>
            </div>
          </div>
        </div>
      `;
    }

    if (item.category === 'IDENTITY') {
      const fullName =
        item.fields.find((f) => f.labelKey === 'field_full_name')?.value ??
        item.title;
      const birthdate =
        item.fields.find((f) => f.labelKey === 'field_birthdate')?.value ?? '';
      const idField =
        item.fields.find((f) => f.labelKey === 'field_tax_id') ??
        item.fields.find((f) => f.labelKey === 'field_ssn');
      const rawId = idField?.value ?? '';
      const revealed = idField
        ? this.revealedFieldIds.has(idField.id) || isPresentation
        : false;
      const maskedId = rawId
        ? revealed
          ? rawId
          : rawId.length > 4
            ? `••••• ${rawId.slice(-3)}`
            : '••••'
        : '—';
      const contact =
        item.fields.find((f) => f.labelKey === 'field_email')?.value ??
        item.fields.find((f) => f.labelKey === 'field_phone')?.value ??
        item.subtitle;

      return `
        <div class="sp-visual-card" data-palette="${palette}"${customStyleAttr}>
          <div class="sp-card-top-row">
            <div style="display:flex;align-items:center;gap:10px;">
              <div class="sp-card-emblem" aria-hidden="true">🪪</div>
              <span style="font-weight:650;font-size:14px;">${this.escape(
                fullName
              )}</span>
            </div>
            <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
              <span class="sp-card-pill">${this.escape(
                this.i18n.t('category_identity')
              )}</span>
              ${presentBtn}
            </div>
          </div>
          <div class="sp-card-pan" style="font-size:17px;">
            <span>${this.escape(maskedId)}</span>
            ${
              rawId
                ? `<button
                     type="button"
                     class="sp-btn"
                     style="padding:4px 10px;min-height:28px;"
                     data-copy-value="${this.escape(rawId)}"
                     data-copy-sensitive="true"
                   >${this.escape(this.i18n.t('action_copy'))}</button>`
                : ''
            }
          </div>
          <div class="sp-card-bottom-row">
            <div>
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_email')
              )}</div>
              <div style="font-weight:600;">${this.escape(contact)}</div>
            </div>
            <div style="text-align:right;">
              <div style="font-size:10px;opacity:0.72;text-transform:uppercase;">${this.escape(
                this.i18n.t('field_birthdate')
              )}</div>
              <div style="font-family:var(--font-mono);font-weight:600;">${this.escape(
                birthdate ? this.i18n.formatDate(birthdate) : '—'
              )}</div>
            </div>
          </div>
        </div>
      `;
    }

    const docNumField = item.fields.find(
      (f) => f.labelKey === 'field_document_number'
    );
    const authority =
      item.fields.find((f) => f.labelKey === 'field_issuing_authority')?.value ??
      '';
    const expiry =
      item.fields.find((f) => f.labelKey === 'field_expiry_date')?.value ?? '';
    const isRevealed = docNumField
      ? this.revealedFieldIds.has(docNumField.id) || isPresentation
      : false;
    const docNumText = docNumField
      ? isRevealed
        ? docNumField.value
        : `•••••${docNumField.value.slice(-3)}`
      : '';

    return `
      <div class="sp-visual-card" data-palette="${palette}"${customStyleAttr}>
        <div class="sp-card-top-row">
          <span style="font-size:11.5px;font-family:var(--font-mono);letter-spacing:0.08em;opacity:0.86;">
            ICAO DOC 9303 • ${this.escape(item.documentSubtype ?? 'OFFICIAL_ID')}
          </span>
          <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
            <span style="font-size:12px;opacity:0.9;">${this.escape(authority)}</span>
            ${presentBtn}
          </div>
        </div>
        <div class="sp-card-pan">
          <span>${this.escape(docNumText)}</span>
          ${
            docNumField
              ? `<button
                   type="button"
                   class="sp-btn"
                   style="padding:4px 10px;min-height:28px;"
                   data-copy-value="${this.escape(docNumField.value)}"
                   data-copy-sensitive="true"
                 >${this.escape(this.i18n.t('action_copy'))}</button>`
              : ''
          }
        </div>
        <div class="sp-card-bottom-row">
          <div style="font-family:var(--font-mono);font-size:11.5px;opacity:0.75;">
            P&lt;UTO&lt;&lt;SAFEPOCKET&lt;&lt;ZERO&lt;KNOWLEDGE&lt;&lt;&lt;&lt;&lt;&lt;&lt;
          </div>
          <div>
            <span style="opacity:0.72;font-size:11px;">${this.escape(
              this.i18n.t('field_expiry_date')
            )}: </span>
            <strong style="font-family:var(--font-mono);">${this.escape(
              this.i18n.formatDate(expiry)
            )}</strong>
          </div>
        </div>
      </div>
    `;
  }

  private renderFieldRow(item: VaultItem, field: VaultField): string {
    const label = this.i18n.resolveFieldLabel(
      field.labelKey,
      field.isCustomLabel,
      item.category
    );
    const isSensitive =
      Boolean(field.isSensitive) || SENSITIVE_FIELD_KEYS.has(field.labelKey);

    if (field.valueType === 'MARKDOWN') {
      return this.renderMarkdownNoteField(label, field);
    }

    const isRevealed = this.revealedFieldIds.has(field.id);
    let displayValue = field.value;
    if (field.valueType === 'DATE_ISO') {
      displayValue = this.i18n.formatDate(field.value);
    } else if (isSensitive && !isRevealed) {
      if (field.valueType === 'CARD_NUMBER') {
        displayValue = `•••• •••• •••• ${field.value.replace(/\s+/g, '').slice(-4)}`;
      } else if (field.valueType === 'IBAN') {
        const compact = field.value.replace(/\s+/g, '');
        displayValue =
          compact.length >= 8
            ? `${compact.slice(0, 4)} •••• •••• ${compact.slice(-4)}`
            : '•••• ••••';
      } else if (field.value.length > 4) {
        displayValue = `${'•'.repeat(8)}${field.value.slice(-3)}`;
      } else {
        displayValue = '••••';
      }
    }

    const isMono =
      isSensitive ||
      field.valueType === 'CARD_NUMBER' ||
      field.valueType === 'IBAN';

    return `
      <div
        class="sp-field-card"
        data-context-item="${this.escape(item.id)}"
        data-context-label="${this.escape(label)}"
        data-context-value="${this.escape(field.value)}"
        data-context-sensitive="${String(isSensitive)}"
      >
        <div class="sp-field-meta">
          <span class="sp-field-label">${this.escape(label)}</span>
          <span class="sp-field-value ${isMono ? 'is-mono' : ''}">${this.escape(
            displayValue
          )}</span>
        </div>
        <div class="sp-field-actions">
          ${
            isSensitive
              ? `<button
                   type="button"
                   class="sp-btn"
                   data-toggle-reveal="${this.escape(field.id)}"
                 >${this.escape(
                   isRevealed
                     ? this.i18n.t('action_hide')
                     : this.i18n.t('action_reveal')
                 )}</button>`
              : ''
          }
          <button
            type="button"
            class="sp-btn sp-btn-tonal"
            data-copy-value="${this.escape(field.value)}"
            data-copy-sensitive="${String(isSensitive)}"
          >${this.escape(this.i18n.t('action_copy'))}</button>
        </div>
      </div>
    `;
  }

  private renderMarkdownNoteField(label: string, field: VaultField): string {
    const segments = field.value.split(/```([\s\S]*?)```/g);
    const htmlBlocks = segments
      .map((seg, idx) => {
        if (idx % 2 === 1) {
          const code = seg.trim();
          return `
            <div class="sp-code-block">
              <code>${this.escape(code)}</code>
              <button
                type="button"
                class="sp-btn sp-btn-tonal"
                data-copy-value="${this.escape(code)}"
                data-copy-sensitive="true"
              >${this.escape(this.i18n.t('action_copy'))}</button>
            </div>
          `;
        }
        return seg
          .trim()
          .split('\n')
          .filter(Boolean)
          .map((line) => {
            if (line.startsWith('### ')) {
              return `<h3>${this.escape(line.slice(4))}</h3>`;
            }
            if (line.startsWith('- ')) {
              return `<div style="margin:4px 0;">• ${this.escape(line.slice(2))}</div>`;
            }
            return `<p style="margin:6px 0;color:var(--text-secondary);">${this.escape(
              line
            )}</p>`;
          })
          .join('');
      })
      .join('');

    return `
      <div class="sp-markdown-box">
        <div style="font-size:11.5px;font-weight:600;color:var(--text-secondary);margin-bottom:8px;">
          ${this.escape(label)}
        </div>
        ${htmlBlocks}
      </div>
    `;
  }

  private renderAttachmentsSection(item: VaultItem): string {
    return `
      <div style="display:flex;flex-direction:column;gap:10px;">
        <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;">
          <div style="font-size:12px;font-weight:600;color:var(--text-secondary);">
            ${this.escape(
              this.i18n.plural('attachments_count', item.attachments.length)
            )}
          </div>
          <div style="display:inline-flex;gap:6px;flex-wrap:wrap;align-items:center;">
            <button
              type="button"
              id="sp-camera-capture-doc-btn"
              class="sp-btn sp-btn-primary"
              style="min-height:28px;padding:3px 10px;font-size:11.5px;"
            >📷 ${this.escape(this.i18n.t('camera_capture_photo_btn'))}</button>
            <button
              type="button"
              class="sp-btn ${
                this.uploadScanSide === 'front' ? 'sp-btn-tonal' : ''
              }"
              data-scan-side="front"
              style="min-height:28px;padding:3px 10px;font-size:11.5px;"
            >${this.escape(this.i18n.t('scan_side_front'))}</button>
            <button
              type="button"
              class="sp-btn ${
                this.uploadScanSide === 'back' ? 'sp-btn-tonal' : ''
              }"
              data-scan-side="back"
              style="min-height:28px;padding:3px 10px;font-size:11.5px;"
            >${this.escape(this.i18n.t('scan_side_back'))}</button>
          </div>
        </div>

        ${item.attachments
          .map((att) => {
            const sideBadge =
              att.side === 'back'
                ? this.i18n.t('scan_side_back')
                : this.i18n.t('scan_side_front');
            const syncPill = att.blobSynced
              ? `<span class="sp-badge" style="background:rgba(16,185,129,0.14);color:#059669;">☁️ EU .enc</span>`
              : `<span class="sp-badge" style="background:var(--bg-canvas);border:1px solid var(--border-subtle);color:var(--text-secondary);">🔒 Local .enc</span>`;
            return `
              <div class="sp-field-card" style="padding:10px 14px;">
                <div class="sp-field-meta">
                  <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
                    <span class="sp-field-value" style="font-size:13px;font-weight:560;">📄 ${this.escape(
                      att.fileName
                    )}</span>
                    <span class="sp-badge" style="background:var(--accent-tonal);color:var(--accent-primary);">
                      ${this.escape(sideBadge)}
                    </span>
                    ${syncPill}
                  </div>
                  <span style="font-size:11px;font-family:var(--font-mono);color:var(--text-muted);">
                    ${this.escape(this.i18n.formatBytes(att.sizeBytes))} • OPFS + SQLite AES-256-GCM • SHA-256 ${this.escape(
                      att.sha256Hex.slice(0, 16)
                    )}…
                  </span>
                </div>
                <div class="sp-field-actions">
                  <button
                    type="button"
                    class="sp-btn sp-btn-tonal"
                    data-preview-att="${this.escape(att.id)}"
                  >👁 ${this.escape(this.i18n.t('action_preview_scan'))}</button>
                  <button
                    type="button"
                    class="sp-btn sp-btn-danger"
                    data-delete-att="${this.escape(att.id)}"
                    title="${this.escape(this.i18n.t('action_delete'))}"
                  >✕</button>
                </div>
              </div>
            `;
          })
          .join('')}

        <label class="sp-dropzone" id="sp-scan-dropzone">
          <input type="file" id="sp-scan-file-input" accept="image/*,application/pdf" multiple style="display:none;" />
          <div>📎 ${this.escape(this.i18n.t('dropzone_hint'))}</div>
        </label>
      </div>
    `;
  }

  private renderScanPreviewModal(): string {
    if (!this.activePreview) return '';
    const isPdf = this.activePreview.mimeType === 'application/pdf';
    const sideLabel =
      this.activePreview.side === 'back'
        ? this.i18n.t('scan_side_back')
        : this.i18n.t('scan_side_front');

    return `
      <div class="sp-overlay" id="sp-preview-backdrop">
        <div class="sp-preview-modal-card">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;">
            <div>
              <div style="font-weight:650;font-size:15px;">
                ${this.escape(this.activePreview.fileName)} (${this.escape(sideLabel)})
              </div>
              <div style="font-size:11px;font-family:var(--font-mono);color:var(--accent-primary);">
                ✓ SHA-256 Verified (${this.escape(
                  this.activePreview.sha256Hex.slice(0, 20)
                )}…) • Ephemeral RAM Blob
              </div>
            </div>
            <div style="display:flex;align-items:center;gap:8px;">
              <a
                href="${this.escape(this.activePreview.objectUrl)}"
                download="${this.escape(this.activePreview.fileName)}"
                class="sp-btn sp-btn-tonal"
                style="text-decoration:none;"
              >⬇ Download</a>
              <button type="button" id="sp-close-preview-btn" class="sp-btn sp-btn-primary">
                ✕ ${this.escape(this.i18n.t('action_close_preview'))}
              </button>
            </div>
          </div>
          <div class="sp-preview-frame">
            ${
              isPdf
                ? `<iframe src="${this.escape(
                    this.activePreview.objectUrl
                  )}" title="${this.escape(this.activePreview.fileName)}"></iframe>`
                : `<img src="${this.escape(
                    this.activePreview.objectUrl
                  )}" alt="${this.escape(this.activePreview.fileName)}" />`
            }
          </div>
        </div>
      </div>
    `;
  }

  private renderPresentationModal(): string {
    if (!this.presentationState) return '';
    const item = this.items.find(
      (i) => i.id === this.presentationState?.itemId
    );
    if (!item) return '';

    const memberId =
      item.fields.find((f) => f.labelKey === 'field_membership_id')?.value ??
      item.fields.find((f) => f.labelKey === 'field_insurance_number')?.value ??
      item.fields.find((f) => f.labelKey === 'field_document_number')?.value ??
      '';
    const preferredFormat =
      this.presentationState.formatOverride ?? item.cardTheme?.barcodeFormat;
    const scannablePayload =
      memberId ||
      (item.category === 'BANK_ACCOUNT'
        ? item.fields.find((f) => f.labelKey === 'field_iban')?.value ?? ''
        : '');
    const barcodeRendered = scannablePayload
      ? renderScannableBarcodeSvg(scannablePayload, preferredFormat, {
          heightPx: 84,
          maxWidthPx: 440,
          qrSizePx: 196
        })
      : null;

    return `
      <div class="sp-presentation-overlay" id="sp-presentation-backdrop" role="dialog" aria-modal="true">
        <div class="sp-presentation-inner">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;color:#f8fafc;flex-wrap:wrap;">
            <div>
              <div style="font-weight:700;font-size:17px;">${this.escape(
                item.title
              )}</div>
              <div style="font-size:11.5px;opacity:0.78;">🔆 ${this.escape(
                this.i18n.t('present_wake_lock_active')
              )}</div>
            </div>
            <button type="button" id="sp-close-presentation-btn" class="sp-btn sp-btn-primary">
              ✕ ${this.escape(this.i18n.t('present_close_btn'))}
            </button>
          </div>

          ${this.renderVisualCardHero(item, true)}

          ${
            barcodeRendered
              ? `<div class="sp-scanner-pad">
                   <div style="display:flex;gap:6px;flex-wrap:wrap;justify-content:center;">
                     ${(['CODE_128', 'EAN_13', 'QR_CODE'] as BarcodeFormat[])
                       .map(
                         (fmt) => `<button
                           type="button"
                           class="sp-btn ${
                             barcodeRendered.format === fmt
                               ? 'sp-btn-primary'
                               : ''
                           }"
                           style="min-height:26px;padding:3px 10px;font-size:11px;"
                           data-present-format="${fmt}"
                         >${this.escape(
                           fmt === 'CODE_128'
                             ? this.i18n.t('barcode_format_code128')
                             : fmt === 'EAN_13'
                               ? this.i18n.t('barcode_format_ean13')
                               : this.i18n.t('barcode_format_qr')
                         )}</button>`
                       )
                       .join('')}
                   </div>
                   <div style="width:100%;display:flex;justify-content:center;padding:6px 0;">
                     ${barcodeRendered.svg}
                   </div>
                   <div style="font-family:var(--font-mono);font-size:18px;font-weight:700;letter-spacing:0.1em;color:#0f172a;word-break:break-all;">
                     ${this.escape(scannablePayload)}
                   </div>
                 </div>`
              : ''
          }
        </div>
      </div>
    `;
  }

  private renderCameraModal(): string {
    if (!this.cameraModalState) return '';
    const st = this.cameraModalState;
    const isPhotoOrMrz = st.mode === 'document_photo' || st.mode === 'mrz';

    return `
      <div class="sp-overlay" id="sp-camera-backdrop" style="z-index:960;">
        <div class="sp-camera-modal" role="dialog" aria-modal="true">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
            <h2 style="margin:0;font-size:16.5px;">📷 ${this.escape(
              this.i18n.t('camera_modal_title')
            )}</h2>
            <div style="display:flex;gap:6px;align-items:center;">
              <button type="button" id="sp-camera-flip-btn" class="sp-btn">
                ⇄ ${this.escape(this.i18n.t('camera_flip_btn'))}
              </button>
              <button type="button" id="sp-camera-close-btn" class="sp-btn">
                ✕ ${this.escape(this.i18n.t('action_cancel'))}
              </button>
            </div>
          </div>

          ${
            st.error
              ? `<div class="sp-badge sp-badge-warn" style="padding:7px 10px;border-radius:var(--radius-sm);">
                   ⚠ ${this.escape(st.error)}
                 </div>`
              : ''
          }

          <div class="sp-camera-viewport">
            <video id="sp-camera-video" autoplay muted playsinline></video>
            <div class="sp-camera-guide-box">
              <span style="font-size:11px;font-weight:600;color:#fff;background:rgba(0,0,0,0.55);padding:2px 8px;border-radius:999px;">
                ${
                  st.mode === 'mrz'
                    ? 'ICAO 2×44 / 3×30 MRZ Zone'
                    : st.mode === 'document_photo'
                      ? 'ISO/IEC 7810 ID-1 Card Frame'
                      : 'Code 128 / EAN-13 / QR'
                }
              </span>
            </div>
          </div>

          ${
            st.mrzPreviewUrl
              ? `<div style="background:var(--surface-card);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:8px;display:flex;flex-direction:column;gap:6px;">
                   <div style="font-size:11px;font-weight:600;color:var(--accent-primary);">✓ High-contrast MRZ strip isolated locally — verify or paste lines in editor</div>
                   <img src="${this.escape(
                     st.mrzPreviewUrl
                   )}" alt="MRZ crop" style="width:100%;max-height:84px;object-fit:contain;border-radius:4px;background:#fff;" />
                 </div>`
              : ''
          }

          ${
            isPhotoOrMrz
              ? `<div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
                   <label style="display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--text-secondary);cursor:pointer;">
                     <input
                       type="checkbox"
                       id="sp-camera-crisp-toggle"
                       ${st.enhanceCrispDoc ? 'checked' : ''}
                       style="accent-color:var(--accent-primary);"
                     />
                     <span>${this.escape(
                       this.i18n.t('camera_crisp_doc_toggle')
                     )}</span>
                   </label>
                   <button type="button" id="sp-camera-capture-frame-btn" class="sp-btn sp-btn-primary">
                     📸 ${this.escape(this.i18n.t('camera_capture_now_btn'))}
                   </button>
                 </div>`
              : ''
          }

          <div style="border-top:1px solid var(--border-subtle);padding-top:10px;display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
            <label class="sp-btn sp-btn-tonal" style="cursor:pointer;">
              🖼 ${this.escape(this.i18n.t('camera_upload_image_fallback'))}
              <input type="file" id="sp-camera-image-fallback" accept="image/*" style="display:none;" />
            </label>
            <span style="font-size:11px;color:var(--text-muted);">100% offline • Zero network frames</span>
          </div>
        </div>
      </div>
    `;
  }

  private renderRestoreBackupModal(): string {
    if (!this.restoreModalState) return '';
    const st = this.restoreModalState;
    return `
      <div class="sp-overlay" id="sp-restore-backdrop" style="z-index:920;">
        <form class="sp-modal-card" id="sp-restore-form" style="max-width:520px;">
          <h2 style="margin:0;font-size:17.5px;">${this.escape(
            this.i18n.t('restore_modal_title')
          )}</h2>

          <div style="background:var(--surface-card);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:10px 12px;font-size:12px;font-family:var(--font-mono);display:flex;flex-direction:column;gap:4px;">
            <div style="color:var(--accent-primary);font-weight:650;">✓ SHA-256 Verified (${this.escape(
              st.summary.sha256Hex.slice(0, 20)
            )}…)</div>
            <div> SafePocket v${this.escape(st.summary.appVersion)} • ${this.escape(
              st.summary.exportedAtIso.slice(0, 19).replace('T', ' ')
            )}</div>
            ${
              st.summary.itemCount !== undefined
                ? `<div>Items: ${st.summary.itemCount} • Encrypted scans: ${
                    st.summary.attachmentCount ?? 0
                  }</div>`
                : ''
            }
          </div>

          ${
            st.error
              ? `<div class="sp-badge sp-badge-expired" style="padding:7px 10px;border-radius:var(--radius-sm);">
                   ⚠ ${this.escape(st.error)}
                 </div>`
              : ''
          }

          <div style="display:flex;flex-direction:column;gap:6px;">
            <label style="display:flex;align-items:center;gap:8px;font-size:13px;cursor:pointer;">
              <input type="radio" name="sp-restore-mode" value="merge" ${
                st.mode === 'merge' ? 'checked' : ''
              } style="accent-color:var(--accent-primary);" />
              <span>${this.escape(this.i18n.t('restore_mode_merge'))}</span>
            </label>
            <label style="display:flex;align-items:center;gap:8px;font-size:13px;cursor:pointer;">
              <input type="radio" name="sp-restore-mode" value="replace" ${
                st.mode === 'replace' ? 'checked' : ''
              } style="accent-color:var(--accent-primary);" />
              <span>${this.escape(this.i18n.t('restore_mode_replace'))}</span>
            </label>
          </div>

          <label style="font-size:12px;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('security_master_password'))}
            <input
              id="sp-restore-password"
              type="password"
              class="sp-input"
              required
              value="${this.escape(st.masterPassword)}"
              style="margin-top:4px;"
            />
          </label>

          <label style="font-size:12px;color:var(--text-secondary);">
            <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;">
              <span>${this.escape(this.i18n.t('security_secret_key'))}</span>
              <button type="button" id="sp-restore-scan-qr-btn" class="sp-btn" style="min-height:24px;padding:2px 8px;font-size:11px;">
                📷 ${this.escape(this.i18n.t('camera_scan_recovery_qr_btn'))}
              </button>
            </div>
            <input
              id="sp-restore-secret"
              type="text"
              class="sp-input"
              required
              value="${this.escape(st.secretKey)}"
              style="margin-top:4px;font-family:var(--font-mono);font-size:12px;"
            />
          </label>

          <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:4px;">
            <button type="button" id="sp-cancel-restore-btn" class="sp-btn">
              ${this.escape(this.i18n.t('action_cancel'))}
            </button>
            <button type="submit" class="sp-btn sp-btn-primary">
              ⬆ ${this.escape(this.i18n.t('backup_restore_encrypted'))}
            </button>
          </div>
        </form>
      </div>
    `;
  }

  private renderPlainExportModal(): string {
    if (!this.plainExportModalState) return '';
    const st = this.plainExportModalState;
    return `
      <div class="sp-overlay" id="sp-plain-export-backdrop" style="z-index:920;">
        <form class="sp-modal-card" id="sp-plain-export-form" style="max-width:460px;">
          <h2 style="margin:0;font-size:17px;">${this.escape(
            this.i18n.t('export_plain_modal_title')
          )}</h2>

          ${
            st.error
              ? `<div class="sp-badge sp-badge-expired" style="padding:7px 10px;border-radius:var(--radius-sm);">
                   ⚠ ${this.escape(st.error)}
                 </div>`
              : ''
          }

          <div style="display:flex;gap:8px;">
            <button
              type="button"
              class="sp-btn ${st.format === 'json' ? 'sp-btn-primary' : ''}"
              data-plain-format="json"
              style="flex:1;"
            >JSON (.json)</button>
            <button
              type="button"
              class="sp-btn ${st.format === 'csv' ? 'sp-btn-primary' : ''}"
              data-plain-format="csv"
              style="flex:1;"
            >CSV (.csv)</button>
          </div>

          <label style="font-size:12px;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('security_master_password'))}
            <input
              id="sp-plain-export-password"
              type="password"
              class="sp-input"
              required
              value="${this.escape(st.password)}"
              style="margin-top:4px;"
            />
          </label>

          <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:4px;">
            <button type="button" id="sp-cancel-plain-export-btn" class="sp-btn">
              ${this.escape(this.i18n.t('action_cancel'))}
            </button>
            <button type="submit" class="sp-btn sp-btn-danger">
              ${this.escape(this.i18n.t('backup_export_plain'))}
            </button>
          </div>
        </form>
      </div>
    `;
  }

  private renderSettingsAndAboutView(): string {
    const upd = this.updater.currentStatus;
    const versionDisplay = this.i18n.t('update_version_display', {
      version: APP_BUILD_INFO.versionName,
      build: APP_BUILD_INFO.versionCode
    });

    return `
      <div class="sp-inspector-header">
        <div>
          <h2 class="sp-inspector-title">${this.escape(
            this.i18n.t('nav_settings')
          )}</h2>
          <div style="font-size:13px;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('app_tagline'))}
          </div>
        </div>
        <span class="sp-zk-chip" style="font-size:12px;padding:5px 11px;" id="sp-about-version-badge">
          ${this.escape(versionDisplay)}
        </span>
      </div>

      <!-- 0. Appearance & Material Theme (Light Default / Dark / Use Device Theme + Material Accent) -->
      <section class="sp-settings-section">
        <h2>${this.escape(this.i18n.t('settings_theme_title'))}</h2>
        <div class="sp-theme-segmented" role="group" aria-label="${this.escape(
          this.i18n.t('settings_theme_title')
        )}">
          <button
            type="button"
            id="sp-theme-light-btn"
            class="sp-btn ${
              this.themeMode === 'light' ? 'sp-btn-primary' : ''
            }"
            data-set-theme="light"
          >☀ ${this.escape(this.i18n.t('theme_light'))}</button>
          <button
            type="button"
            id="sp-theme-dark-btn"
            class="sp-btn ${
              this.themeMode === 'dark' ? 'sp-btn-primary' : ''
            }"
            data-set-theme="dark"
          >☾ ${this.escape(this.i18n.t('theme_dark'))}</button>
          <button
            type="button"
            id="sp-theme-system-btn"
            class="sp-btn ${
              this.themeMode === 'system' ? 'sp-btn-primary' : ''
            }"
            data-set-theme="system"
          >◐ ${this.escape(this.i18n.t('theme_system'))}</button>
        </div>

        <div style="display:flex;flex-direction:column;gap:8px;padding-top:4px;border-top:1px solid var(--border-subtle);">
          <div style="font-size:12px;font-weight:600;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('settings_material_color_title'))}
          </div>
          <div class="sp-material-accent-grid" role="group" aria-label="${this.escape(
            this.i18n.t('settings_material_color_title')
          )}">
            ${(
              [
                {
                  id: 'device' as MaterialColorScheme,
                  labelKey: 'material_color_device',
                  dotBg: 'AccentColor'
                },
                {
                  id: 'emerald' as MaterialColorScheme,
                  labelKey: 'material_color_emerald',
                  dotBg: 'hsl(168, 74%, 28%)'
                },
                {
                  id: 'ocean' as MaterialColorScheme,
                  labelKey: 'material_color_ocean',
                  dotBg: 'hsl(208, 78%, 36%)'
                },
                {
                  id: 'terracotta' as MaterialColorScheme,
                  labelKey: 'material_color_terracotta',
                  dotBg: 'hsl(20, 68%, 38%)'
                },
                {
                  id: 'slate' as MaterialColorScheme,
                  labelKey: 'material_color_slate',
                  dotBg: 'hsl(212, 24%, 26%)'
                }
              ]
            )
              .map(
                (acc) => `
                <button
                  type="button"
                  id="sp-accent-${acc.id}-btn"
                  class="sp-btn ${
                    this.materialAccent === acc.id ? 'sp-btn-primary' : ''
                  }"
                  data-set-accent="${acc.id}"
                >
                  <span class="sp-accent-dot" style="background:${acc.dotBg};"></span>
                  <span>${this.escape(this.i18n.t(acc.labelKey))}</span>
                </button>
              `
              )
              .join('')}
          </div>
        </div>
      </section>

      <!-- 1. Self-Update & Version Management -->
      <section class="sp-settings-section">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;">
          <div>
            <h2>${this.escape(this.i18n.t('update_section_title'))}</h2>
            <div style="font-size:12.5px;color:var(--text-secondary);font-family:var(--font-mono);margin-top:2px;">
              ${this.escape(versionDisplay)}
            </div>
          </div>
          <button type="button" id="sp-check-updates-btn" class="sp-btn sp-btn-primary">
            ↻ ${this.escape(this.i18n.t('update_check_btn'))}
          </button>
        </div>

        <label style="display:flex;align-items:flex-start;justify-content:space-between;gap:14px;cursor:pointer;">
          <div>
            <div style="font-weight:600;font-size:13.5px;">${this.escape(
              this.i18n.t('update_auto_toggle')
            )}</div>
            <div style="font-size:12px;color:var(--text-secondary);">${this.escape(
              this.i18n.t('update_auto_desc')
            )}</div>
          </div>
          <input
            type="checkbox"
            id="sp-auto-update-toggle"
            ${this.updater.isAutoUpdateEnabled ? 'checked' : ''}
            style="width:18px;height:18px;accent-color:var(--accent-primary);"
          />
        </label>

        ${this.renderUpdateStatusCard(upd)}
      </section>

      <!-- 2. Localization & Regional Templates -->
      <section class="sp-settings-section">
        <h2>${this.escape(this.i18n.t('settings_language_title'))}</h2>
        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:8px;">
          <button
            type="button"
            class="sp-btn ${this.i18n.isUsingSystemLocale ? 'sp-btn-tonal' : ''}"
            data-set-locale="__system__"
          >${this.escape(
            this.i18n.t('settings_language_system', {
              locale: this.i18n.detectedSystemLocale.toUpperCase()
            })
          )}</button>
          ${SUPPORTED_LOCALES.map(
            (loc) => `
            <button
              type="button"
              class="sp-btn ${
                !this.i18n.isUsingSystemLocale &&
                this.i18n.activeLocale === loc.code
                  ? 'sp-btn-primary'
                  : ''
              }"
              data-set-locale="${loc.code}"
            >${this.escape(loc.nativeName)} (${loc.code.toUpperCase()})</button>
          `
          ).join('')}
        </div>
      </section>

      <!-- 3. Local Storage Default & Optional Cloud Sync (Pre-Initialized Firebase DB) -->
      <section class="sp-settings-section">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
          <h2>${this.escape(this.i18n.t('sync_section_title'))}</h2>
          <span class="sp-badge ${
            this.syncCoordinator.isCloudSyncEnabled ? 'sp-badge-ok' : ''
          }" style="${
            this.syncCoordinator.isCloudSyncEnabled
              ? ''
              : 'background:var(--accent-tonal);color:var(--accent-primary);'
          }">
            ${
              this.syncCoordinator.isCloudSyncEnabled
                ? this.escape(
                    this.i18n.t('sync_status_synced', {
                      provider: this.syncCoordinator.cloudService
                    })
                  )
                : this.escape(this.i18n.t('sync_provider_none'))
            }
          </span>
        </div>

        <div style="font-size:11.5px;font-family:var(--font-mono);padding:8px 11px;border-radius:var(--radius-sm);background:var(--bg-canvas);border:1px solid var(--border-subtle);display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;">
          <span style="color:${
            this.syncCoordinator.isCloudSyncEnabled &&
            this.syncCoordinator.cloudService === 'FIREBASE'
              ? 'var(--accent-primary)'
              : 'var(--text-secondary)'
          };">
            ● ${this.escape(
              this.syncCoordinator.isCloudSyncEnabled &&
                this.syncCoordinator.cloudService === 'FIREBASE'
                ? this.i18n.t('firebase_db_status_active')
                : this.i18n.t('firebase_db_status_dormant')
            )}
          </span>
          <span style="color:var(--text-muted);">
            SQLite WASM (${this.escape(this.sqliteDb.activeBackend.toUpperCase())}) • Project: ${this.escape(
              this.firebaseService.webConfig.projectId
            )}
          </span>
        </div>

        <label style="display:flex;align-items:flex-start;justify-content:space-between;gap:14px;cursor:pointer;">
          <div>
            <div style="font-weight:600;font-size:13.5px;">${this.escape(
              this.i18n.t('sync_enable_cloud_toggle')
            )}</div>
            <div style="font-size:12px;color:var(--text-secondary);">${this.escape(
              this.i18n.t('sync_enable_cloud_desc')
            )}</div>
          </div>
          <input
            type="checkbox"
            id="sp-enable-cloud-sync"
            ${this.syncCoordinator.isCloudSyncEnabled ? 'checked' : ''}
            style="width:18px;height:18px;accent-color:var(--accent-primary);"
          />
        </label>

        <div style="display:flex;gap:8px;flex-wrap:wrap;opacity:${
          this.syncCoordinator.isCloudSyncEnabled ? '1' : '0.68'
        };">
          <button
            type="button"
            class="sp-btn ${
              this.syncCoordinator.cloudService === 'FIREBASE'
                ? 'sp-btn-primary'
                : ''
            }"
            data-cloud-service="FIREBASE"
          >${this.escape(this.i18n.t('sync_provider_firebase'))}</button>
          <button
            type="button"
            class="sp-btn ${
              this.syncCoordinator.cloudService === 'GDRIVE_APPDATA'
                ? 'sp-btn-primary'
                : ''
            }"
            data-cloud-service="GDRIVE_APPDATA"
          >${this.escape(this.i18n.t('sync_provider_gdrive'))}</button>
        </div>

        <!-- Zero-Knowledge Cloud Sync Account (User Creation & Sign-In) -->
        <div style="background:var(--bg-canvas);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:12px;display:flex;flex-direction:column;gap:8px;">
          <div style="font-size:12px;font-weight:600;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('cloud_auth_section_title'))}
          </div>
          ${
            this.firebaseService.currentUser
              ? `<div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;">
                   <span class="sp-badge sp-badge-ok" style="font-family:var(--font-mono);">
                     ✓ ${this.escape(
                       this.i18n.t('cloud_auth_signed_in_as', {
                         email: this.firebaseService.currentUser.email,
                         uid: this.firebaseService.currentUser.uid.slice(0, 14)
                       })
                     )}
                   </span>
                   <button type="button" id="sp-cloud-signout-btn" class="sp-btn">
                     ${this.escape(this.i18n.t('cloud_auth_signout_btn'))}
                   </button>
                 </div>`
              : `<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:8px;">
                   <input
                     id="sp-cloud-auth-email"
                     type="email"
                     class="sp-input"
                     placeholder="${this.escape(this.i18n.t('cloud_auth_email'))}"
                     value="${this.escape(this.cloudAuthEmailDraft)}"
                   />
                   <input
                     id="sp-cloud-auth-password"
                     type="password"
                     class="sp-input"
                     placeholder="${this.escape(this.i18n.t('cloud_auth_password'))}"
                   />
                 </div>
                 <div style="display:flex;gap:8px;flex-wrap:wrap;">
                   <button type="button" id="sp-cloud-signup-btn" class="sp-btn sp-btn-primary">
                     + ${this.escape(this.i18n.t('cloud_auth_create_btn'))}
                   </button>
                   <button type="button" id="sp-cloud-signin-btn" class="sp-btn sp-btn-tonal">
                     ${this.escape(this.i18n.t('cloud_auth_signin_btn'))}
                   </button>
                 </div>`
          }
        </div>

        ${
          this.syncCoordinator.isCloudSyncEnabled
            ? `
            <div style="background:var(--bg-canvas);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:12px;display:flex;flex-direction:column;gap:8px;">
              <div style="font-size:12px;font-weight:600;color:var(--text-secondary);">
                ${this.escape(this.i18n.t('cloud_config_title'))}
              </div>
              ${
                this.syncCoordinator.cloudService === 'FIREBASE'
                  ? `<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;">
                       <input
                         id="sp-cloud-fb-apikey"
                         type="password"
                         class="sp-input"
                         placeholder="${this.escape(
                           this.i18n.t('cloud_firebase_api_key')
                         )}"
                         value="${this.escape(
                           this.firebaseService.webConfig.apiKey
                         )}"
                       />
                       <input
                         id="sp-cloud-fb-project"
                         class="sp-input"
                         placeholder="${this.escape(
                           this.i18n.t('cloud_firebase_project_id')
                         )}"
                         value="${this.escape(
                           this.firebaseService.webConfig.projectId
                         )}"
                       />
                       <input
                         id="sp-cloud-fb-bucket"
                         class="sp-input"
                         placeholder="${this.escape(
                           this.i18n.t('cloud_firebase_bucket')
                         )}"
                         value="${this.escape(
                           this.firebaseService.webConfig.storageBucket
                         )}"
                       />
                     </div>`
                  : `<input
                       id="sp-cloud-bearer-token"
                       type="password"
                       class="sp-input"
                       placeholder="${this.escape(this.i18n.t('cloud_bearer_token'))}"
                       value="${this.escape(this.liveCloudConfig.bearerToken ?? '')}"
                     />`
              }
            </div>`
            : ''
        }

        <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;">
          <span style="font-size:12.5px;color:var(--text-secondary);">
            ${
              !this.syncCoordinator.isCloudSyncEnabled
                ? this.escape(this.i18n.t('sync_status_local'))
                : `${this.escape(
                    this.i18n.t('sync_status_synced', {
                      provider: this.syncCoordinator.cloudService
                    })
                  )} ${
                    this.lastSyncedCount > 0 || this.lastPulledCount > 0
                      ? `(↑${this.lastSyncedCount} pushed • ↓${this.lastPulledCount} pulled)`
                      : ''
                  }`
            }
          </span>
          ${
            this.syncCoordinator.isCloudSyncEnabled
              ? `<button type="button" id="sp-sync-now-btn" class="sp-btn sp-btn-tonal">
                   ⇄ ${this.escape(this.i18n.t('sync_now'))}
                 </button>`
              : ''
          }
        </div>

        <div style="border-top:1px solid var(--border-subtle);padding-top:12px;display:flex;gap:8px;flex-wrap:wrap;">
          <button type="button" id="sp-export-safepocket" class="sp-btn sp-btn-tonal">
            ⬇ ${this.escape(this.i18n.t('backup_export_encrypted'))}
          </button>
          <label class="sp-btn" style="cursor:pointer;">
            ⬆ ${this.escape(this.i18n.t('backup_restore_encrypted'))}
            <input type="file" id="sp-restore-safepocket" accept=".safepocket,application/json" style="display:none;" />
          </label>
          <button type="button" id="sp-export-plain-csv" class="sp-btn">
            ${this.escape(this.i18n.t('backup_export_plain'))}
          </button>
        </div>
      </section>

      <!-- 4. Zero-Knowledge Security, Expiry Window, Auto-Lock & Emergency Kit v2 -->
      <section class="sp-settings-section">
        <h2>${this.escape(this.i18n.t('security_section_title'))}</h2>
        <div style="font-size:12.5px;font-family:var(--font-mono);color:var(--accent-primary);">
          ${this.escape(this.i18n.t('security_kdf_badge'))}
        </div>
        <div style="font-size:12px;color:var(--text-secondary);font-family:var(--font-mono);word-break:break-all;">
          ${this.escape(this.i18n.t('security_secret_key'))}: ${this.escape(
            this.demoSecretKey
          )}
        </div>

        <label style="display:flex;align-items:flex-start;justify-content:space-between;gap:12px;cursor:pointer;padding:8px 0;border-top:1px solid var(--border-subtle);">
          <div style="display:flex;flex-direction:column;gap:2px;">
            <span style="font-size:13px;font-weight:600;color:var(--text-primary);">
              ${this.escape(this.i18n.t('security_allow_screenshots_toggle'))}
            </span>
            <span style="font-size:11.5px;color:var(--text-secondary);">
              ${this.escape(this.i18n.t('security_allow_screenshots_desc'))}
            </span>
          </div>
          <input
            id="sp-toggle-allow-screenshots"
            type="checkbox"
            ${this.allowScreenshots ? 'checked' : ''}
            style="margin-top:4px;width:18px;height:18px;accent-color:var(--accent-primary);"
          />
        </label>

        <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;">
          <span style="font-size:12.5px;font-weight:600;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('expiry_window_setting_title'))}
          </span>
          <div style="display:flex;gap:6px;flex-wrap:wrap;">
            ${EXPIRY_WINDOW_OPTIONS.map(
              (days) => `
              <button
                type="button"
                class="sp-btn ${
                  this.expiryWindowDays === days ? 'sp-btn-primary' : ''
                }"
                data-expiry-window="${days}"
              >${this.escape(
                this.i18n.t('expiry_window_days_option', { days })
              )}</button>
            `
            ).join('')}
          </div>
        </div>

        <div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;">
          <span style="font-size:12.5px;font-weight:600;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('security_auto_lock'))}
          </span>
          <div style="display:flex;gap:6px;flex-wrap:wrap;">
            ${([1, 5, 15] as AutoLockMinutes[])
              .map(
                (min) => `
                <button
                  type="button"
                  class="sp-btn ${
                    this.autoLockMonitor.minutes === min ? 'sp-btn-primary' : ''
                  }"
                  data-autolock-min="${min}"
                >${this.escape(
                  this.i18n.t(`security_auto_lock_${min}m`)
                )}</button>
              `
              )
              .join('')}
          </div>
        </div>

        <div style="border-top:1px solid var(--border-subtle);padding-top:10px;display:flex;flex-direction:column;gap:8px;">
          <input
            id="sp-settings-kit-hint"
            type="text"
            class="sp-input"
            value="${this.escape(this.emergencyKitHintDraft)}"
            placeholder="${this.escape(
              this.i18n.t('emergency_kit_hint_placeholder')
            )}"
            style="font-size:12px;"
          />
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            <button type="button" id="sp-enroll-passkey" class="sp-btn">
              🔑 ${this.escape(this.i18n.t('security_biometrics_toggle'))}
            </button>
            <button type="button" id="sp-settings-print-kit" class="sp-btn sp-btn-primary">
              🖨 ${this.escape(this.i18n.t('emergency_kit_print_btn'))}
            </button>
            <button type="button" id="sp-settings-emergency-kit" class="sp-btn sp-btn-tonal">
              ⬇ ${this.escape(this.i18n.t('onboarding_download_emergency_kit'))}
            </button>
          </div>
        </div>
      </section>
    `;
  }

  private renderUpdateStatusCard(
    upd: ReturnType<
      PwaUpdateController['currentStatus'] extends infer T ? () => T : never
    >
  ): string {
    if (upd.phase === 'idle') return '';
    if (upd.phase === 'checking' || upd.phase === 'verifying') {
      return `
        <div style="display:flex;flex-direction:column;gap:8px;">
          <div style="font-size:12.5px;color:var(--text-secondary);">${this.escape(
            this.i18n.t('update_checking')
          )}</div>
          <div class="sp-progress-track">
            <div class="sp-progress-fill" style="width:${upd.progressPercent}%"></div>
          </div>
        </div>
      `;
    }
    if (upd.phase === 'up_to_date') {
      return `<div class="sp-badge sp-badge-ok">✓ ${this.escape(
        this.i18n.t('update_up_to_date')
      )}</div>`;
    }
    if (upd.phase === 'error') {
      return `<div class="sp-badge sp-badge-expired">⚠ ${this.escape(
        upd.errorMessage ?? 'Update error'
      )}</div>`;
    }
    if (upd.manifest) {
      const notes = this.updater.localizedReleaseNotes(this.i18n.activeLocale);
      const artifact = upd.manifest.artifacts['chromeos-pwa'];
      return `
        <div style="background:var(--bg-canvas);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:14px;display:flex;flex-direction:column;gap:10px;">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;">
            <span class="sp-badge sp-badge-ok">
              ${this.escape(
                this.i18n.t('update_available_badge', {
                  version: upd.manifest.latestVersion,
                  build: upd.manifest.versionCode
                })
              )}
            </span>
            <span style="font-family:var(--font-mono);font-size:11px;color:var(--text-muted);">
              SHA-256: ${this.escape(artifact.sha256.slice(0, 20))}…
            </span>
          </div>

          <div style="font-size:12.5px;white-space:pre-line;color:var(--text-secondary);">
            <strong style="color:var(--text-primary);">${this.escape(
              this.i18n.t('update_release_notes_label')
            )}:</strong>\n${this.escape(notes)}
          </div>

          <div class="sp-progress-track">
            <div class="sp-progress-fill" style="width:${upd.progressPercent}%"></div>
          </div>

          ${
            upd.phase === 'ready_to_reload'
              ? `<div style="display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;">
                   <span class="sp-badge sp-badge-ok">✓ ${this.escape(
                     this.i18n.t('update_sha256_verified')
                   )}</span>
                   <button type="button" id="sp-pwa-reload-btn" class="sp-btn sp-btn-primary">
                     ${this.escape(
                       this.i18n.t('update_pwa_reload_btn', {
                         version: upd.manifest.latestVersion
                       })
                     )}
                   </button>
                 </div>`
              : `<div>
                   <button type="button" id="sp-verify-stage-update-btn" class="sp-btn sp-btn-tonal">
                     ✓ ${this.escape(
                       this.i18n.t('update_install_btn', {
                         version: upd.manifest.latestVersion
                       })
                     )}
                   </button>
                 </div>`
          }
        </div>
      `;
    }
    return '';
  }

  private renderMobileBottomNav(): string {
    const tabs: Array<{ id: NavSelection; icon: string; label: string }> = [
      { id: 'ALL', icon: '▤', label: this.i18n.t('nav_all_items') },
      { id: 'PAYMENT_CARD', icon: '💳', label: this.i18n.t('nav_payment_cards') },
      { id: 'IDENTITY', icon: '🪪', label: this.i18n.t('nav_identities') },
      {
        id: 'OFFICIAL_DOCUMENT',
        icon: '🛂',
        label: this.i18n.t('nav_official_documents')
      },
      { id: 'SETTINGS', icon: '⚙', label: this.i18n.t('nav_settings') }
    ];
    return `
      <nav class="sp-mobile-bottom-nav" aria-label="Compact Navigation">
        ${tabs
          .map(
            (t) => `
          <button
            type="button"
            class="sp-nav-btn ${this.selectedNav === t.id ? 'is-active' : ''}"
            data-nav="${t.id}"
            style="flex-direction:column;justify-content:center;padding:4px;font-size:10.5px;text-align:center;gap:2px;"
          >
            <span>${t.icon}</span>
            <span>${this.escape(t.label)}</span>
          </button>
        `
          )
          .join('')}
      </nav>
    `;
  }

  private renderItemEditorModal(): string {
    if (!this.editorDraft) return '';
    const draft = this.editorDraft;
    const templateFields = this.defaultTemplateFields(draft.category);
    const showPalette =
      draft.category === 'PAYMENT_CARD' ||
      draft.category === 'BANK_ACCOUNT' ||
      draft.category === 'INSURANCE_CARD' ||
      draft.category === 'MEMBERSHIP_CARD' ||
      draft.category === 'IDENTITY' ||
      draft.category === 'OFFICIAL_DOCUMENT';
    const palettes: Array<{ id: CardThemePalette; key: string }> = [
      ...CARD_THEME_PALETTES.map((id) => ({
        id: id as CardThemePalette,
        key: `palette_${id}`
      })),
      { id: 'custom', key: 'palette_custom' }
    ];
    const customSwatchStyle =
      draft.paletteId === 'custom'
        ? ` style="${this.escape(
            buildCustomCardStyle(draft.customColorHex).inlineStyleAttr
          )}"`
        : '';

    return `
      <div class="sp-overlay" id="sp-modal-backdrop">
        <form class="sp-modal-card" id="sp-item-editor-form" style="max-width:540px;">
          <h2 style="margin:0;font-size:18px;">${this.escape(
            draft.editingItemId
              ? this.i18n.t('action_edit')
              : this.i18n.t('action_new_item')
          )}</h2>

          <label style="font-size:12px;color:var(--text-secondary);">
            Category
            <select id="sp-edit-category" class="sp-select" style="margin-top:4px;">
              <option value="PAYMENT_CARD" ${
                draft.category === 'PAYMENT_CARD' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_payment_card'))}</option>
              <option value="BANK_ACCOUNT" ${
                draft.category === 'BANK_ACCOUNT' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_bank_account'))}</option>
              <option value="INSURANCE_CARD" ${
                draft.category === 'INSURANCE_CARD' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_insurance_card'))}</option>
              <option value="MEMBERSHIP_CARD" ${
                draft.category === 'MEMBERSHIP_CARD' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_membership_card'))}</option>
              <option value="IDENTITY" ${
                draft.category === 'IDENTITY' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_identity'))}</option>
              <option value="OFFICIAL_DOCUMENT" ${
                draft.category === 'OFFICIAL_DOCUMENT' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_official_document'))}</option>
              <option value="SECURE_NOTE" ${
                draft.category === 'SECURE_NOTE' ? 'selected' : ''
              }>${this.escape(this.i18n.t('category_secure_note'))}</option>
            </select>
          </label>

          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px;">
            <label style="font-size:12px;color:var(--text-secondary);">
              Title
              <input
                id="sp-edit-title"
                class="sp-input"
                required
                value="${this.escape(draft.title)}"
                placeholder="e.g. ING Girokonto / TK Gesundheitskarte / ADAC"
                style="margin-top:4px;"
              />
            </label>
            <label style="font-size:12px;color:var(--text-secondary);">
              Subtitle
              <input
                id="sp-edit-subtitle"
                class="sp-input"
                value="${this.escape(draft.subtitle)}"
                placeholder="${this.escape(
                  this.i18n.resolveCategoryName(draft.category)
                )}"
                style="margin-top:4px;"
              />
            </label>
          </div>

          ${
            draft.category === 'IDENTITY'
              ? `<label style="display:inline-flex;align-items:center;gap:8px;font-size:12.5px;color:var(--text-primary);cursor:pointer;">
                   <input
                     type="checkbox"
                     id="sp-edit-show-visual-card"
                     ${draft.showVisualCard ? 'checked' : ''}
                     style="accent-color:var(--accent-primary);"
                   />
                   <span>${this.escape(
                     this.i18n.t('identity_show_visual_card')
                   )}</span>
                 </label>`
              : ''
          }

          ${
            showPalette
              ? `<div>
                   <div style="font-size:12px;color:var(--text-secondary);">${this.escape(
                     this.i18n.t('field_card_palette')
                   )}</div>
                   <div class="sp-palette-grid">
                     ${palettes
                       .map(
                         (p) => `
                       <button
                         type="button"
                         class="sp-palette-swatch ${
                           draft.paletteId === p.id ? 'is-selected' : ''
                         }"
                         data-palette="${p.id}"
                         data-select-palette="${p.id}"${
                           p.id === 'custom' ? customSwatchStyle : ''
                         }
                       >
                         <span>${this.escape(this.i18n.t(p.key))}</span>
                         ${draft.paletteId === p.id ? '<span>✓</span>' : ''}
                       </button>
                     `
                       )
                       .join('')}
                   </div>
                   ${
                     draft.paletteId === 'custom'
                       ? `<div class="sp-color-picker-row">
                            <input
                              type="color"
                              id="sp-edit-custom-color-picker"
                              class="sp-color-input"
                              value="${this.escape(draft.customColorHex)}"
                              aria-label="${this.escape(
                                this.i18n.t('palette_custom')
                              )}"
                            />
                            <input
                              type="text"
                              id="sp-edit-custom-color-hex"
                              class="sp-input"
                              value="${this.escape(draft.customColorHex)}"
                              maxlength="7"
                              style="font-family:var(--font-mono);max-width:128px;"
                            />
                            <span style="font-size:11.5px;color:var(--text-secondary);">WCAG Auto-Contrast</span>
                          </div>`
                       : ''
                   }
                 </div>`
              : ''
          }

          ${
            draft.category === 'MEMBERSHIP_CARD'
              ? `<div style="background:var(--bg-canvas);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:10px 12px;display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;">
                   <label style="font-size:12px;color:var(--text-secondary);flex:1;min-width:160px;">
                     ${this.escape(this.i18n.t('barcode_format_label'))}
                     <select id="sp-edit-barcode-format" class="sp-select" style="margin-top:4px;">
                       <option value="CODE_128" ${
                         draft.barcodeFormat === 'CODE_128' ? 'selected' : ''
                       }>${this.escape(this.i18n.t('barcode_format_code128'))}</option>
                       <option value="EAN_13" ${
                         draft.barcodeFormat === 'EAN_13' ? 'selected' : ''
                       }>${this.escape(this.i18n.t('barcode_format_ean13'))}</option>
                       <option value="QR_CODE" ${
                         draft.barcodeFormat === 'QR_CODE' ? 'selected' : ''
                       }>${this.escape(this.i18n.t('barcode_format_qr'))}</option>
                     </select>
                   </label>
                   <button type="button" id="sp-editor-scan-barcode-btn" class="sp-btn sp-btn-primary">
                     📷 ${this.escape(this.i18n.t('camera_scan_barcode_btn'))}
                   </button>
                 </div>`
              : ''
          }

          ${
            draft.category === 'OFFICIAL_DOCUMENT'
              ? `<div style="background:var(--bg-canvas);border:1px solid var(--border-subtle);border-radius:var(--radius-sm);padding:10px 12px;display:flex;flex-direction:column;gap:8px;">
                   <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;">
                     <span style="font-size:12px;font-weight:600;color:var(--accent-primary);">
                       🛂 ${this.escape(this.i18n.t('mrz_scanner_title'))}
                     </span>
                     <div style="display:flex;gap:6px;flex-wrap:wrap;">
                       <button type="button" id="sp-editor-scan-mrz-btn" class="sp-btn sp-btn-tonal" style="min-height:26px;padding:3px 9px;font-size:11px;">
                         📷 ${this.escape(this.i18n.t('camera_scan_mrz_btn'))}
                       </button>
                       <button type="button" id="sp-mrz-sample-btn" class="sp-btn" style="min-height:26px;padding:3px 9px;font-size:11px;">
                         ${this.escape(this.i18n.t('mrz_scanner_sample_btn'))}
                       </button>
                     </div>
                   </div>
                   <textarea
                     id="sp-mrz-input"
                     class="sp-textarea"
                     rows="2"
                     placeholder="P&lt;DEUMUELLER&lt;&lt;CLARA&lt;SOFIA&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&#10;C01X00T470DEU9204191F3106156&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;06"
                     style="font-family:var(--font-mono);font-size:11.5px;letter-spacing:0.03em;"
                   >${this.escape(draft.mrzRawInput ?? '')}</textarea>
                   <div>
                     <button type="button" id="sp-mrz-parse-btn" class="sp-btn sp-btn-tonal" style="min-height:30px;padding:4px 12px;font-size:12px;">
                       ✓ ${this.escape(this.i18n.t('mrz_scanner_parse_btn'))}
                     </button>
                   </div>
                 </div>`
              : ''
          }

          ${templateFields
            .map((tf) => {
              const val = draft.standardFieldValues[tf.key] ?? '';
              let validationHint = '';
              if (tf.key === 'field_card_number' && val.trim().length > 0) {
                const panRes = validatePaymentCardPan(val);
                validationHint = `<span style="font-family:var(--font-mono);font-size:11px;margin-left:8px;color:${
                  panRes.validLuhn ? 'var(--accent-primary)' : '#d9534f'
                };">${this.escape(panRes.networkBrand)} • ${
                  panRes.validLuhn ? '✓ Luhn OK' : '⚠ Check digits'
                }</span>`;
              } else if (tf.key === 'field_iban' && val.trim().length > 0) {
                const ibanOk = validateIsoIban(val);
                validationHint = `<span style="font-family:var(--font-mono);font-size:11px;margin-left:8px;color:${
                  ibanOk ? 'var(--accent-primary)' : '#d9534f'
                };">${ibanOk ? '✓ ISO-13616 Mod-97 OK' : '⚠ Invalid IBAN'}</span>`;
              }
              return `
                <label style="font-size:12px;color:var(--text-secondary);">
                  <span>${this.escape(
                    this.i18n.resolveFieldLabel(tf.key, false, draft.category)
                  )}</span>${validationHint}
                  ${
                    tf.valueType === 'MARKDOWN'
                      ? `<textarea class="sp-textarea" rows="4" data-std-field="${tf.key}" data-std-type="${tf.valueType}" style="margin-top:4px;">${this.escape(
                          val
                        )}</textarea>`
                      : `<input class="sp-input" data-std-field="${tf.key}" data-std-type="${tf.valueType}" value="${this.escape(
                          val
                        )}" placeholder="${this.escape(
                          tf.placeholder
                        )}" style="margin-top:4px;" />`
                  }
                </label>
              `;
            })
            .join('')}

          <label style="font-size:12px;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('field_tags'))}
            <input
              id="sp-edit-tags"
              class="sp-input"
              value="${this.escape(draft.tagsCsv)}"
              placeholder="travel, eu, tax"
              style="margin-top:4px;"
            />
          </label>

          <div style="display:flex;flex-direction:column;gap:8px;">
            ${draft.customFields
              .map(
                (cf, idx) => `
              <div class="sp-custom-field-row">
                <div style="display:grid;grid-template-columns:1fr 1fr auto;gap:8px;align-items:center;">
                  <input
                    class="sp-input"
                    data-cf-label="${idx}"
                    value="${this.escape(cf.label)}"
                    placeholder="${this.escape(this.i18n.t('custom_field_name'))}"
                  />
                  <input
                    class="sp-input"
                    data-cf-value="${idx}"
                    value="${this.escape(cf.value)}"
                    placeholder="Value"
                  />
                  <button
                    type="button"
                    class="sp-btn sp-btn-danger"
                    data-remove-cf="${idx}"
                    style="padding:6px 10px;"
                  >✕</button>
                </div>
                <label style="display:inline-flex;align-items:center;gap:6px;font-size:11.5px;color:var(--text-secondary);cursor:pointer;">
                  <input
                    type="checkbox"
                    data-cf-sens="${idx}"
                    ${cf.isSensitive ? 'checked' : ''}
                    style="accent-color:var(--accent-primary);"
                  />
                  <span>${this.escape(
                    this.i18n.t('custom_field_sensitive')
                  )}</span>
                </label>
              </div>
            `
              )
              .join('')}

            <button type="button" id="sp-add-custom-field-btn" class="sp-btn sp-btn-tonal">
              + ${this.escape(this.i18n.t('action_add_custom_field'))}
            </button>
          </div>

          <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:6px;">
            <button type="button" id="sp-cancel-editor" class="sp-btn">${this.escape(
              this.i18n.t('action_cancel')
            )}</button>
            <button type="submit" class="sp-btn sp-btn-primary">${this.escape(
              this.i18n.t('action_save')
            )}</button>
          </div>
        </form>
      </div>
    `;
  }

  private defaultTemplateFields(category: VaultCategory): Array<{
    key: string;
    valueType: VaultField['valueType'];
    placeholder: string;
  }> {
    switch (category) {
      case 'PAYMENT_CARD':
        return [
          { key: 'field_cardholder', valueType: 'TEXT', placeholder: 'JANE DOE' },
          {
            key: 'field_card_number',
            valueType: 'CARD_NUMBER',
            placeholder: '4532 0192 8834 1109'
          },
          { key: 'field_expiry_date', valueType: 'DATE_ISO', placeholder: '2029-11' },
          { key: 'field_cvv', valueType: 'SENSITIVE_SECRET', placeholder: '742' },
          { key: 'field_pin', valueType: 'SENSITIVE_SECRET', placeholder: '1234' },
          {
            key: 'field_issuing_bank',
            valueType: 'TEXT',
            placeholder: 'Issuing Bank'
          },
          { key: 'field_iban', valueType: 'IBAN', placeholder: 'DE00 0000 ...' },
          { key: 'field_bic', valueType: 'TEXT', placeholder: 'BIC / SWIFT' },
          {
            key: 'field_support_phone',
            valueType: 'PHONE',
            placeholder: '+49 30 ...'
          }
        ];
      case 'BANK_ACCOUNT':
        return [
          {
            key: 'field_account_holder',
            valueType: 'TEXT',
            placeholder: 'CLARA SOFIA MÜLLER'
          },
          {
            key: 'field_iban',
            valueType: 'IBAN',
            placeholder: 'DE89 3704 0044 0532 0130 00'
          },
          {
            key: 'field_bic',
            valueType: 'TEXT',
            placeholder: 'INGDDEFFXXX'
          },
          {
            key: 'field_account_number',
            valueType: 'SENSITIVE_SECRET',
            placeholder: '0532013000'
          },
          {
            key: 'field_routing_code',
            valueType: 'TEXT',
            placeholder: '37040044'
          },
          {
            key: 'field_account_type',
            valueType: 'TEXT',
            placeholder: 'Girokonto / Tagesgeld'
          },
          {
            key: 'field_issuing_bank',
            valueType: 'TEXT',
            placeholder: 'ING-DiBa AG'
          },
          {
            key: 'field_support_phone',
            valueType: 'PHONE',
            placeholder: '+49 69 ...'
          }
        ];
      case 'INSURANCE_CARD':
        return [
          {
            key: 'field_full_name',
            valueType: 'TEXT',
            placeholder: 'Clara Sofia Müller'
          },
          {
            key: 'field_insurer_name',
            valueType: 'TEXT',
            placeholder: 'Techniker Krankenkasse (TK)'
          },
          {
            key: 'field_insurance_number',
            valueType: 'SENSITIVE_SECRET',
            placeholder: 'A109482751'
          },
          {
            key: 'field_insurer_id',
            valueType: 'TEXT',
            placeholder: '101575519'
          },
          {
            key: 'field_coverage_plan',
            valueType: 'TEXT',
            placeholder: 'GKV / EHIC / PKV'
          },
          {
            key: 'field_expiry_date',
            valueType: 'DATE_ISO',
            placeholder: '2029-12-31'
          },
          {
            key: 'field_support_phone',
            valueType: 'PHONE',
            placeholder: '+49 800 ...'
          }
        ];
      case 'MEMBERSHIP_CARD':
        return [
          {
            key: 'field_member_name',
            valueType: 'TEXT',
            placeholder: 'CLARA SOFIA MÜLLER'
          },
          {
            key: 'field_organization',
            valueType: 'TEXT',
            placeholder: 'ADAC / BahnCard / Fitnessclub'
          },
          {
            key: 'field_membership_id',
            valueType: 'TEXT',
            placeholder: '7081 4920 1184'
          },
          {
            key: 'field_membership_tier',
            valueType: 'TEXT',
            placeholder: 'Plus / Gold'
          },
          {
            key: 'field_expiry_date',
            valueType: 'DATE_ISO',
            placeholder: '2027-09-30'
          },
          {
            key: 'field_support_phone',
            valueType: 'PHONE',
            placeholder: '+49 89 ...'
          }
        ];
      case 'IDENTITY':
        return [
          { key: 'field_full_name', valueType: 'TEXT', placeholder: 'Full Name' },
          {
            key: 'field_birthdate',
            valueType: 'DATE_ISO',
            placeholder: '1992-04-19'
          },
          {
            key: 'field_tax_id',
            valueType: 'SENSITIVE_SECRET',
            placeholder: 'Regional Tax Identifier'
          },
          {
            key: 'field_ssn',
            valueType: 'SENSITIVE_SECRET',
            placeholder: 'Social Insurance ID'
          },
          {
            key: 'field_health_insurance',
            valueType: 'SENSITIVE_SECRET',
            placeholder: 'Health Card Number'
          },
          { key: 'field_address', valueType: 'MULTILINE_ADDRESS', placeholder: '' },
          { key: 'field_email', valueType: 'EMAIL', placeholder: 'name@domain.eu' },
          { key: 'field_phone', valueType: 'PHONE', placeholder: '+49 ...' }
        ];
      case 'OFFICIAL_DOCUMENT':
        return [
          {
            key: 'field_document_subtype',
            valueType: 'TEXT',
            placeholder: 'Biometric Passport / National ID'
          },
          {
            key: 'field_document_number',
            valueType: 'SENSITIVE_SECRET',
            placeholder: 'C01X00T47'
          },
          {
            key: 'field_issuing_authority',
            valueType: 'TEXT',
            placeholder: 'Issuing Authority'
          },
          {
            key: 'field_issue_date',
            valueType: 'DATE_ISO',
            placeholder: '2021-06-15'
          },
          {
            key: 'field_expiry_date',
            valueType: 'DATE_ISO',
            placeholder: '2031-06-15'
          },
          {
            key: 'field_nationality',
            valueType: 'TEXT',
            placeholder: 'Nationality'
          }
        ];
      case 'SECURE_NOTE':
        return [
          {
            key: 'field_markdown_note',
            valueType: 'MARKDOWN',
            placeholder: 'Markdown notes...'
          }
        ];
    }
  }

  private renderContextMenu(): string {
    if (!this.contextMenu) return '';
    return `
      <div
        class="sp-context-menu"
        style="left:${Math.min(this.contextMenu.x, window.innerWidth - 240)}px;top:${Math.min(
          this.contextMenu.y,
          window.innerHeight - 140
        )}px;"
      >
        <button type="button" class="sp-context-item" id="sp-ctx-quick-copy">
          📋 ${this.escape(
            this.i18n.t('context_quick_copy', { field: this.contextMenu.label })
          )}
        </button>
        <button type="button" class="sp-context-item" id="sp-ctx-duplicate">
          ⧉ ${this.escape(this.i18n.t('context_duplicate'))}
        </button>
      </div>
    `;
  }

  private renderLockScreen(): string {
    const isDemo = localStorage.getItem('safepocket.demo_mode') === 'true';
    return `
      <div class="sp-overlay">
        <form class="sp-modal-card" id="sp-unlock-form">
          <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;">
            <div style="display:flex;align-items:center;gap:10px;min-width:0;">
              <div class="sp-logo-mark">🔒</div>
              <div>
                <h1 style="margin:0;font-size:18px;">${this.escape(
                  this.i18n.t('app_name')
                )}</h1>
                <div style="font-size:12px;color:var(--text-secondary);">${this.escape(
                  this.i18n.t('app_tagline')
                )}</div>
              </div>
            </div>
            <button
              type="button"
              id="sp-lock-theme-toggle"
              class="sp-theme-toggle-btn"
              title="${this.escape(this.themeToggleTooltip())}"
            >${this.renderThemeToggleIconSvg()}</button>
          </div>

          ${
            this.wasAutoLocked
              ? `<div class="sp-badge sp-badge-warn" style="padding:7px 11px;border-radius:var(--radius-sm);">
                   ⏱ ${this.escape(this.i18n.t('autolock_locked_reason'))}
                 </div>`
              : ''
          }

          ${
            this.unlockError
              ? `<div class="sp-badge sp-badge-expired" style="padding:7px 11px;border-radius:var(--radius-sm);">
                   ⚠ ${this.escape(this.unlockError)}
                 </div>`
              : ''
          }

          <label style="font-size:12px;color:var(--text-secondary);">
            ${this.escape(this.i18n.t('security_master_password'))}
            <input
              id="sp-unlock-password"
              type="password"
              class="sp-input"
              value="${isDemo ? DEMO_DEFAULT_PASSWORD : ''}"
              required
              style="margin-top:4px;"
            />
          </label>

          <label style="font-size:12px;color:var(--text-secondary);">
            <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;">
              <span>${this.escape(this.i18n.t('security_secret_key'))}</span>
              <button
                type="button"
                id="sp-lock-scan-qr-btn"
                class="sp-btn"
                style="min-height:24px;padding:2px 8px;font-size:11px;"
              >📷 ${this.escape(
                this.i18n.t('camera_scan_recovery_qr_btn')
              )}</button>
            </div>
            <input
              id="sp-unlock-secret"
              type="text"
              class="sp-input"
              value="${this.escape(this.demoSecretKey)}"
              required
              style="margin-top:4px;font-family:var(--font-mono);font-size:12px;"
            />
          </label>

          <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:4px;">
            <button type="submit" class="sp-btn sp-btn-primary" style="flex:1;">
              ${this.escape(this.i18n.t('action_unlock_vault'))}
            </button>
            ${
              WebAuthnBiometricUnlock.isSupported()
                ? `<button type="button" id="sp-biometric-unlock-btn" class="sp-btn sp-btn-tonal">
                     ${this.escape(this.i18n.t('action_unlock_biometric'))}
                   </button>`
                : ''
            }
          </div>
        </form>
      </div>
    `;
  }

  private wireLockScreenEvents(): void {
    this.root
      .querySelector('#sp-lock-theme-toggle')
      ?.addEventListener('click', () => {
        this.toggleQuickTheme();
      });

    this.root
      .querySelector('#sp-lock-scan-qr-btn')
      ?.addEventListener('click', () => {
        const sk =
          this.root.querySelector<HTMLInputElement>('#sp-unlock-secret')
            ?.value ?? '';
        if (sk) this.demoSecretKey = sk;
        this.openCameraModal('recovery_qr', 'lock');
      });

    const form = this.root.querySelector<HTMLFormElement>('#sp-unlock-form');
    form?.addEventListener('submit', async (e) => {
      e.preventDefault();
      const pw =
        this.root.querySelector<HTMLInputElement>('#sp-unlock-password')
          ?.value ?? '';
      const sk =
        this.root.querySelector<HTMLInputElement>('#sp-unlock-secret')?.value ??
        '';
      await this.unlockAndHydrateVault(pw, sk, false);
    });

    this.root
      .querySelector('#sp-biometric-unlock-btn')
      ?.addEventListener('click', async () => {
        const ok = await WebAuthnBiometricUnlock.authenticateBiometric().catch(
          () => false
        );
        if (ok) {
          await this.unlockAndHydrateVault(
            this.activeMasterPassword,
            this.demoSecretKey,
            false
          );
        }
      });

    this.wireCameraModalEvents();
  }

  private wireCameraModalEvents(): void {
    if (!this.cameraModalState) return;

    this.root
      .querySelector('#sp-camera-close-btn')
      ?.addEventListener('click', () => {
        this.closeCameraModal();
        this.render();
      });

    this.root
      .querySelector('#sp-camera-backdrop')
      ?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) {
          this.closeCameraModal();
          this.render();
        }
      });

    this.root
      .querySelector('#sp-camera-flip-btn')
      ?.addEventListener('click', () => {
        const videoEl =
          this.root.querySelector<HTMLVideoElement>('#sp-camera-video');
        if (!videoEl || !this.cameraModalState) return;
        const mode = this.cameraModalState.mode;
        const needsBarcode = mode === 'barcode' || mode === 'recovery_qr';
        void this.cameraController
          .flipCamera(
            videoEl,
            needsBarcode
              ? (res) => this.handleBarcodeDetectedFromCamera(res)
              : undefined
          )
          .catch((err) => {
            if (this.cameraModalState) {
              this.cameraModalState.error =
                err instanceof Error ? err.message : 'Camera flip failed';
              this.render();
            }
          });
      });

    this.root
      .querySelector<HTMLInputElement>('#sp-camera-crisp-toggle')
      ?.addEventListener('change', (e) => {
        if (!this.cameraModalState) return;
        this.cameraModalState.enhanceCrispDoc = (
          e.target as HTMLInputElement
        ).checked;
      });

    this.root
      .querySelector('#sp-camera-capture-frame-btn')
      ?.addEventListener('click', async () => {
        const st = this.cameraModalState;
        const videoEl =
          this.root.querySelector<HTMLVideoElement>('#sp-camera-video');
        if (!st || !videoEl) return;
        try {
          const captured =
            await this.cameraController.captureDocumentFrameBytes(
              videoEl,
              st.enhanceCrispDoc
            );
          if (st.mode === 'mrz') {
            const mrz = analyzeMrzRegionFromCanvas(captured.canvas);
            st.mrzPreviewUrl = mrz.mrzCropDataUrl;
            this.render();
            this.startActiveCameraStream();
            this.showToast(
              'MRZ strip isolated locally — verify or paste lines in editor'
            );
            return;
          }
          if (st.mode === 'document_photo') {
            const target = this.items.find((i) => i.id === this.selectedItemId);
            this.closeCameraModal();
            if (target) {
              await this.encryptAndAttachPayloads(target, [
                {
                  fileName: `scan-${this.uploadScanSide}-${Date.now()}.jpg`,
                  mimeType: captured.mimeType,
                  bytes: captured.bytes
                }
              ]);
            } else {
              this.render();
            }
          }
        } catch (err) {
          if (this.cameraModalState) {
            this.cameraModalState.error =
              err instanceof Error ? err.message : 'Frame capture unavailable';
            this.render();
          }
        }
      });

    this.root
      .querySelector<HTMLInputElement>('#sp-camera-image-fallback')
      ?.addEventListener('change', async (e) => {
        const file = (e.target as HTMLInputElement).files?.[0];
        const st = this.cameraModalState;
        if (!file || !st) return;

        if (st.mode === 'barcode' || st.mode === 'recovery_qr') {
          const decoded =
            await this.cameraController.decodeBarcodeFromImageFile(file);
          if (decoded) {
            this.handleBarcodeDetectedFromCamera(decoded);
          } else if (this.cameraModalState) {
            this.cameraModalState.error =
              'No scannable Code 128 / EAN-13 / QR found in selected image';
            this.render();
            this.startActiveCameraStream();
          }
          return;
        }

        if (st.mode === 'document_photo') {
          const target = this.items.find((i) => i.id === this.selectedItemId);
          const raw = new Uint8Array(await file.arrayBuffer());
          this.closeCameraModal();
          if (target) {
            await this.encryptAndAttachPayloads(target, [
              {
                fileName: file.name,
                mimeType: file.type || 'image/jpeg',
                bytes: raw
              }
            ]);
          } else {
            this.render();
          }
          return;
        }

        if (st.mode === 'mrz') {
          const bmpUrl = URL.createObjectURL(file);
          try {
            const img = await new Promise<HTMLImageElement>(
              (resolve, reject) => {
                const el = new Image();
                el.onload = () => resolve(el);
                el.onerror = () => reject(new Error('Image load failed'));
                el.src = bmpUrl;
              }
            );
            const canvas = document.createElement('canvas');
            canvas.width = img.naturalWidth || 800;
            canvas.height = img.naturalHeight || 500;
            const ctx = canvas.getContext('2d');
            if (ctx) {
              ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
              const mrz = analyzeMrzRegionFromCanvas(canvas);
              st.mrzPreviewUrl = mrz.mrzCropDataUrl;
              this.render();
            }
          } finally {
            URL.revokeObjectURL(bmpUrl);
          }
        }
      });
  }

  private wireShellEvents(): void {
    this.wireCameraModalEvents();

    // Quick theme toggle & Settings theme + Material Design accent selectors
    this.root
      .querySelector('#sp-quick-theme-toggle')
      ?.addEventListener('click', () => {
        this.toggleQuickTheme();
      });
    this.root
      .querySelectorAll<HTMLElement>('[data-set-theme]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const mode = btn.dataset.setTheme as ThemeMode;
          if (mode === 'light' || mode === 'dark' || mode === 'system') {
            this.setThemeMode(mode);
          }
        });
      });
    this.root
      .querySelectorAll<HTMLElement>('[data-set-accent]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const accent = btn.dataset.setAccent as MaterialColorScheme;
          if (
            accent === 'emerald' ||
            accent === 'device' ||
            accent === 'ocean' ||
            accent === 'terracotta' ||
            accent === 'slate'
          ) {
            this.setMaterialAccent(accent);
          }
        });
      });

    // Navigation buttons
    this.root.querySelectorAll<HTMLElement>('[data-nav]').forEach((btn) => {
      btn.addEventListener('click', () => {
        this.selectedNav = btn.dataset.nav as NavSelection;
        this.mobileDetailOpen = this.selectedNav === 'SETTINGS';
        this.render();
      });
    });

    this.root.querySelector('#sp-mobile-back')?.addEventListener('click', () => {
      this.mobileDetailOpen = false;
      this.render();
    });

    // Quick language switcher pills & Settings language buttons
    this.root.querySelectorAll<HTMLElement>('[data-locale]').forEach((pill) => {
      pill.addEventListener('click', () => {
        this.i18n.setLocaleOverride(pill.dataset.locale as SupportedLocale);
      });
    });
    this.root
      .querySelectorAll<HTMLElement>('[data-set-locale]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const val = btn.dataset.setLocale;
          this.i18n.setLocaleOverride(
            val === '__system__' ? null : (val as SupportedLocale)
          );
        });
      });

    // Instant Search, Sort Mode & Tag Filter Chips
    const searchEl = this.root.querySelector<HTMLInputElement>(
      '#sp-global-search'
    );
    searchEl?.addEventListener('input', () => {
      this.searchQuery = searchEl.value;
      this.render();
    });

    this.root
      .querySelector<HTMLSelectElement>('#sp-sort-mode')
      ?.addEventListener('change', (e) => {
        this.sortMode = (e.target as HTMLSelectElement).value as VaultSortMode;
        this.render();
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-filter-tag]')
      .forEach((chip) => {
        chip.addEventListener('click', () => {
          const clickedTag = chip.dataset.filterTag ?? '';
          if (!clickedTag) return;
          if (
            this.activeTagFilter &&
            this.activeTagFilter.toLowerCase() === clickedTag.toLowerCase()
          ) {
            this.activeTagFilter = null;
          } else {
            this.activeTagFilter = clickedTag;
            if (this.selectedNav === 'SETTINGS') {
              this.selectedNav = 'ALL';
            }
          }
          this.render();
        });
      });

    this.root
      .querySelector('#sp-clear-tag-filter')
      ?.addEventListener('click', () => {
        this.activeTagFilter = null;
        this.render();
      });

    // Item row selection
    this.root
      .querySelectorAll<HTMLElement>('[data-select-item]')
      .forEach((row) => {
        row.addEventListener('click', () => {
          this.selectedItemId = row.dataset.selectItem ?? null;
          const selected = this.items.find((i) => i.id === this.selectedItemId);
          if (selected) {
            selected.lastAccessedAtEpochMs = Date.now();
          }
          if (this.selectedNav === 'SETTINGS') {
            this.selectedNav = 'ALL';
          }
          this.mobileDetailOpen = true;
          this.render();
        });
      });

    // Copy buttons with 30-second Clipboard Hygiene
    this.root
      .querySelectorAll<HTMLElement>('[data-copy-value]')
      .forEach((btn) => {
        btn.addEventListener('click', async () => {
          const val = btn.dataset.copyValue ?? '';
          const sensitive = btn.dataset.copySensitive === 'true';
          const selected = this.items.find((i) => i.id === this.selectedItemId);
          if (selected) {
            selected.lastAccessedAtEpochMs = Date.now();
          }
          await this.clipboard.copyValue(val, sensitive);
          if (!sensitive) {
            this.showToast(this.i18n.t('action_copied'));
          }
        });
      });

    // Right-click context menu on any field card
    this.root
      .querySelectorAll<HTMLElement>('[data-context-value]')
      .forEach((card) => {
        card.addEventListener('contextmenu', (e) => {
          e.preventDefault();
          this.contextMenu = {
            x: e.clientX,
            y: e.clientY,
            itemId: card.dataset.contextItem ?? '',
            label: card.dataset.contextLabel ?? '',
            value: card.dataset.contextValue ?? '',
            isSensitive: card.dataset.contextSensitive === 'true'
          };
          this.render();
        });
      });

    this.root
      .querySelector('#sp-ctx-quick-copy')
      ?.addEventListener('click', async () => {
        if (!this.contextMenu) return;
        const { value, isSensitive } = this.contextMenu;
        this.contextMenu = null;
        await this.clipboard.copyValue(value, isSensitive);
      });

    this.root
      .querySelector('#sp-ctx-duplicate')
      ?.addEventListener('click', async () => {
        if (!this.contextMenu) return;
        const { itemId } = this.contextMenu;
        this.contextMenu = null;
        await this.duplicateVaultItem(itemId);
      });

    // Reveal / Mask toggle
    this.root
      .querySelectorAll<HTMLElement>('[data-toggle-reveal]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const id = btn.dataset.toggleReveal ?? '';
          if (this.revealedFieldIds.has(id)) this.revealedFieldIds.delete(id);
          else this.revealedFieldIds.add(id);
          this.render();
        });
      });

    // Full-screen Card & Barcode Presentation Mode
    this.root
      .querySelectorAll<HTMLElement>('[data-present-card]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const itemId = btn.dataset.presentCard;
          if (itemId) {
            void this.openPresentationMode(itemId);
          }
        });
      });

    this.root
      .querySelector('#sp-close-presentation-btn')
      ?.addEventListener('click', () => {
        this.closePresentationMode();
        this.render();
      });

    this.root
      .querySelector('#sp-presentation-backdrop')
      ?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) {
          this.closePresentationMode();
          this.render();
        }
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-present-format]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          if (!this.presentationState) return;
          this.presentationState.formatOverride = btn.dataset
            .presentFormat as BarcodeFormat;
          this.render();
        });
      });

    // Inspector CRUD actions: Pin, Favorite, Edit, Duplicate, Delete
    this.root
      .querySelector('#sp-toggle-pin')
      ?.addEventListener('click', async () => {
        const target = this.items.find((i) => i.id === this.selectedItemId);
        if (!target) return;
        target.pinned = !target.pinned;
        target.updatedAtEpochMs = Date.now();
        await this.persistSingleItemEncrypted(target);
        this.render();
      });

    this.root
      .querySelector('#sp-toggle-fav')
      ?.addEventListener('click', async () => {
        const target = this.items.find((i) => i.id === this.selectedItemId);
        if (!target) return;
        target.favorite = !target.favorite;
        target.updatedAtEpochMs = Date.now();
        await this.persistSingleItemEncrypted(target);
        this.render();
      });

    this.root
      .querySelector('#sp-edit-item-btn')
      ?.addEventListener('click', () => {
        const target = this.items.find((i) => i.id === this.selectedItemId);
        if (target) this.openEditItemModal(target);
      });

    this.root
      .querySelector('#sp-duplicate-item-btn')
      ?.addEventListener('click', () => {
        if (this.selectedItemId) {
          void this.duplicateVaultItem(this.selectedItemId);
        }
      });

    this.root
      .querySelector('#sp-delete-item-btn')
      ?.addEventListener('click', () => {
        if (this.selectedItemId) {
          void this.deleteVaultItemWithTombstone(this.selectedItemId);
        }
      });

    // Scrub clipboard immediately
    this.root
      .querySelector('#sp-scrub-now-btn')
      ?.addEventListener('click', () => {
        void this.clipboard.scrubClipboardNow();
      });

    // Lock vault
    this.root.querySelector('#sp-lock-btn')?.addEventListener('click', () => {
      this.lockVault(false);
    });

    // Create / Edit Item Modal events
    this.root
      .querySelector('#sp-new-item-btn')
      ?.addEventListener('click', () => this.openCreateItemModal());
    this.root
      .querySelector('#sp-empty-new-btn')
      ?.addEventListener('click', () => this.openCreateItemModal());

    this.root
      .querySelector('#sp-cancel-editor')
      ?.addEventListener('click', () => {
        this.editorDraft = null;
        this.render();
      });

    this.root
      .querySelector<HTMLSelectElement>('#sp-edit-category')
      ?.addEventListener('change', (e) => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        const nextCat = (e.target as HTMLSelectElement).value as VaultCategory;
        this.editorDraft.category = nextCat;
        if (nextCat === 'IDENTITY' && this.editorDraft.editingItemId === null) {
          this.editorDraft.showVisualCard = true;
        }
        this.render();
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-select-palette]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          if (!this.editorDraft) return;
          this.syncDraftFromDom();
          this.editorDraft.paletteId = btn.dataset
            .selectPalette as CardThemePalette;
          this.render();
        });
      });

    this.root
      .querySelector<HTMLInputElement>('#sp-edit-custom-color-picker')
      ?.addEventListener('change', (e) => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.editorDraft.customColorHex = normalizeHexColor(
          (e.target as HTMLInputElement).value,
          this.editorDraft.customColorHex
        );
        this.render();
      });

    this.root
      .querySelector<HTMLInputElement>('#sp-edit-custom-color-hex')
      ?.addEventListener('change', (e) => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.editorDraft.customColorHex = normalizeHexColor(
          (e.target as HTMLInputElement).value,
          this.editorDraft.customColorHex
        );
        this.render();
      });

    this.root
      .querySelector<HTMLInputElement>('#sp-edit-show-visual-card')
      ?.addEventListener('change', (e) => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.editorDraft.showVisualCard = (
          e.target as HTMLInputElement
        ).checked;
        this.render();
      });

    this.root
      .querySelector<HTMLSelectElement>('#sp-edit-barcode-format')
      ?.addEventListener('change', (e) => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.editorDraft.barcodeFormat = (e.target as HTMLSelectElement)
          .value as BarcodeFormat;
      });

    this.root
      .querySelector('#sp-editor-scan-barcode-btn')
      ?.addEventListener('click', () => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.openCameraModal('barcode');
      });

    this.root
      .querySelector('#sp-editor-scan-mrz-btn')
      ?.addEventListener('click', () => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.openCameraModal('mrz');
      });

    this.root
      .querySelector('#sp-add-custom-field-btn')
      ?.addEventListener('click', () => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.editorDraft.customFields.push({
          id: `cf-${Date.now()}-${this.editorDraft.customFields.length}`,
          label: '',
          value: '',
          isSensitive: false
        });
        this.render();
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-remove-cf]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          if (!this.editorDraft) return;
          this.syncDraftFromDom();
          const idx = Number(btn.dataset.removeCf);
          this.editorDraft.customFields.splice(idx, 1);
          this.render();
        });
      });

    this.root
      .querySelector('#sp-mrz-sample-btn')
      ?.addEventListener('click', () => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        this.editorDraft.mrzRawInput = buildSampleTd3PassportMrz();
        this.render();
      });

    this.root
      .querySelector('#sp-mrz-parse-btn')
      ?.addEventListener('click', () => {
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        try {
          const parsed = parseOfflineIcaoMrz(this.editorDraft.mrzRawInput ?? '');
          const subtypeLabel =
            parsed.documentSubtype === 'PASSPORT'
              ? this.i18n.t('doc_type_passport')
              : this.i18n.t('doc_type_national_id');
          if (!this.editorDraft.title.trim()) {
            this.editorDraft.title = `${parsed.issuingState} ${subtypeLabel} — ${parsed.holderFullName}`;
          }
          if (!this.editorDraft.subtitle.trim()) {
            this.editorDraft.subtitle = `${parsed.holderFullName} (${parsed.nationality})`;
          }
          this.editorDraft.standardFieldValues.field_document_subtype =
            subtypeLabel;
          this.editorDraft.standardFieldValues.field_document_number =
            parsed.documentNumber;
          this.editorDraft.standardFieldValues.field_issuing_authority =
            parsed.issuingState;
          this.editorDraft.standardFieldValues.field_expiry_date =
            parsed.expiryDateIso;
          this.editorDraft.standardFieldValues.field_nationality =
            parsed.nationality;
          this.showToast(
            `ICAO MRZ parsed (${parsed.format}) • Check digits: ${
              parsed.checkDigitsValid ? 'VALID ✓' : 'WARNING ⚠'
            }`
          );
        } catch (err) {
          this.showToast(
            err instanceof Error ? err.message : 'Invalid ICAO MRZ text'
          );
        }
      });

    this.root
      .querySelectorAll<HTMLInputElement>(
        '[data-std-field="field_card_number"], [data-std-field="field_iban"]'
      )
      .forEach((inp) => {
        inp.addEventListener('change', () => {
          this.syncDraftFromDom();
          this.render();
        });
      });

    this.root
      .querySelector<HTMLFormElement>('#sp-item-editor-form')
      ?.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!this.editorDraft) return;
        this.syncDraftFromDom();
        const draft = this.editorDraft;
        const title = draft.title.trim();
        if (!title) return;

        const now = Date.now();
        const fields: VaultField[] = [];
        const templates = this.defaultTemplateFields(draft.category);
        templates.forEach((tf, idx) => {
          const val = (draft.standardFieldValues[tf.key] ?? '').trim();
          if (!val) return;
          fields.push({
            id: `f-${now}-${idx}`,
            labelKey: tf.key,
            value: val,
            valueType: tf.valueType,
            isSensitive: SENSITIVE_FIELD_KEYS.has(tf.key)
          });
        });

        draft.customFields.forEach((cf, idx) => {
          const label = cf.label.trim();
          const val = cf.value.trim();
          if (!label || !val) return;
          fields.push({
            id: cf.id || `cf-${now}-${idx}`,
            labelKey: label,
            isCustomLabel: true,
            value: val,
            valueType: cf.isSensitive ? 'SENSITIVE_SECRET' : 'TEXT',
            isSensitive: cf.isSensitive
          });
        });

        const tags = draft.tagsCsv
          .split(',')
          .map((t) => t.trim())
          .filter(Boolean);

        const detectedNetwork =
          draft.category === 'PAYMENT_CARD' &&
          draft.standardFieldValues.field_card_number
            ? validatePaymentCardPan(
                draft.standardFieldValues.field_card_number
              ).networkBrand
            : undefined;

        const buildCardTheme = (
          prevNetwork?: VaultItem['cardTheme'] extends infer T
            ? T extends { networkBrand?: infer N }
              ? N
              : undefined
            : undefined
        ): VaultItem['cardTheme'] => ({
          paletteId: draft.paletteId,
          customColorHex:
            draft.paletteId === 'custom'
              ? normalizeHexColor(draft.customColorHex)
              : undefined,
          showVisualCard:
            draft.category === 'IDENTITY' ? draft.showVisualCard : undefined,
          barcodeFormat:
            draft.category === 'MEMBERSHIP_CARD'
              ? draft.barcodeFormat
              : undefined,
          networkBrand: detectedNetwork ?? prevNetwork
        });

        if (draft.editingItemId) {
          const existing = this.items.find(
            (i) => i.id === draft.editingItemId
          );
          if (existing) {
            existing.category = draft.category;
            existing.title = title;
            existing.subtitle =
              draft.subtitle.trim() ||
              this.i18n.resolveCategoryName(draft.category);
            existing.cardTheme = buildCardTheme(
              existing.cardTheme?.networkBrand
            );
            existing.tags = tags;
            existing.fields = fields;
            existing.updatedAtEpochMs = now;
            existing.lastAccessedAtEpochMs = now;
            await this.persistSingleItemEncrypted(existing);
            this.selectedItemId = existing.id;
          }
        } else {
          const created: VaultItem = {
            schemaVersion: 1,
            id: `item-${now}`,
            category: draft.category,
            title,
            subtitle:
              draft.subtitle.trim() ||
              this.i18n.resolveCategoryName(draft.category),
            favorite: false,
            tags,
            cardTheme: buildCardTheme(undefined),
            fields,
            attachments: [],
            createdAtEpochMs: now,
            updatedAtEpochMs: now,
            lastAccessedAtEpochMs: now
          };
          this.items.unshift(created);
          this.selectedItemId = created.id;
          await this.persistSingleItemEncrypted(created);
        }

        this.editorDraft = null;
        this.render();
      });

    // Scan side selector, Camera Document Capture & Attachment Preview / Delete / Drag-and-Drop
    this.root
      .querySelector('#sp-camera-capture-doc-btn')
      ?.addEventListener('click', () => {
        this.openCameraModal('document_photo');
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-scan-side]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          this.uploadScanSide = btn.dataset.scanSide as 'front' | 'back';
          this.render();
        });
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-preview-att]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const target = this.items.find((i) => i.id === this.selectedItemId);
          const attId = btn.dataset.previewAtt;
          if (target && attId) {
            void this.openDecryptedAttachmentPreview(target, attId);
          }
        });
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-delete-att]')
      .forEach((btn) => {
        btn.addEventListener('click', async () => {
          const target = this.items.find((i) => i.id === this.selectedItemId);
          const attId = btn.dataset.deleteAtt;
          if (!target || !attId) return;
          await this.opfsStore.deleteEncryptedBlob(attId);
          await this.sqliteDb.deleteLocalEncryptedBlob(attId);
          target.attachments = target.attachments.filter((a) => a.id !== attId);
          target.updatedAtEpochMs = Date.now();
          await this.persistSingleItemEncrypted(target);
          this.render();
        });
      });

    this.root
      .querySelector('#sp-close-preview-btn')
      ?.addEventListener('click', () => {
        this.closeScanPreview();
        this.render();
      });

    this.root
      .querySelector('#sp-preview-backdrop')
      ?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) {
          this.closeScanPreview();
          this.render();
        }
      });

    const dropzone = this.root.querySelector<HTMLElement>('#sp-scan-dropzone');
    const fileInput =
      this.root.querySelector<HTMLInputElement>('#sp-scan-file-input');
    const handleFiles = async (files: FileList | null) => {
      if (!files || files.length === 0) return;
      const target = this.items.find((i) => i.id === this.selectedItemId);
      if (!target) return;

      const selectedFiles = Array.from(files);
      const payloads: Array<{
        fileName: string;
        mimeType: string;
        bytes: Uint8Array;
      }> = [];
      for (const f of selectedFiles) {
        payloads.push({
          fileName: f.name,
          mimeType: f.type || 'application/octet-stream',
          bytes: new Uint8Array(await f.arrayBuffer())
        });
      }
      await this.encryptAndAttachPayloads(target, payloads);
    };
    dropzone?.addEventListener('dragover', (e) => {
      e.preventDefault();
      dropzone.classList.add('is-dragover');
    });
    dropzone?.addEventListener('dragleave', () => {
      dropzone.classList.remove('is-dragover');
    });
    dropzone?.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('is-dragover');
      void handleFiles(e.dataTransfer?.files ?? null);
    });
    fileInput?.addEventListener('change', () => {
      void handleFiles(fileInput.files);
    });

    // Settings: Self-Update controls
    this.root
      .querySelector<HTMLInputElement>('#sp-auto-update-toggle')
      ?.addEventListener('change', (e) => {
        this.updater.setAutoUpdateEnabled(
          (e.target as HTMLInputElement).checked
        );
      });
    this.root
      .querySelector('#sp-check-updates-btn')
      ?.addEventListener('click', () => {
        void this.updater.checkForUpdates();
      });
    this.root
      .querySelector('#sp-verify-stage-update-btn')
      ?.addEventListener('click', () => {
        void this.updater.verifyChecksumAndStageServiceWorker();
      });
    this.root
      .querySelector('#sp-pwa-reload-btn')
      ?.addEventListener('click', () => {
        this.updater.activateAndReload();
      });

    // Settings: Local Storage Default & Optional Cloud Sync controls
    this.root
      .querySelector<HTMLInputElement>('#sp-enable-cloud-sync')
      ?.addEventListener('change', (e) => {
        this.syncCoordinator.setCloudSyncEnabled(
          (e.target as HTMLInputElement).checked
        );
        this.render();
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-cloud-service]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          this.syncCoordinator.setPreferredCloudService(
            btn.dataset.cloudService as CloudServiceId
          );
          this.render();
        });
      });

    const syncLiveConfigInputs = () => {
      const fbApiKey =
        this.root.querySelector<HTMLInputElement>('#sp-cloud-fb-apikey')
          ?.value.trim() ?? this.firebaseService.webConfig.apiKey;
      const fbProj =
        this.root.querySelector<HTMLInputElement>('#sp-cloud-fb-project')
          ?.value.trim() ?? this.firebaseService.webConfig.projectId;
      const fbBucket =
        this.root.querySelector<HTMLInputElement>('#sp-cloud-fb-bucket')
          ?.value.trim() ?? this.firebaseService.webConfig.storageBucket;
      const token =
        this.root.querySelector<HTMLInputElement>('#sp-cloud-bearer-token')
          ?.value.trim() ?? this.liveCloudConfig.bearerToken;

      this.firebaseService.updateWebConfig({
        apiKey: fbApiKey,
        projectId: fbProj,
        storageBucket: fbBucket
      });
      this.liveCloudConfig = this.firebaseService.toEndpointConfig(token);
      this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
      this.gdriveAdapter.setLiveEndpointConfig(this.liveCloudConfig);
    };

    this.root
      .querySelector('#sp-cloud-fb-apikey')
      ?.addEventListener('input', syncLiveConfigInputs);
    this.root
      .querySelector('#sp-cloud-fb-project')
      ?.addEventListener('input', syncLiveConfigInputs);
    this.root
      .querySelector('#sp-cloud-fb-bucket')
      ?.addEventListener('input', syncLiveConfigInputs);
    this.root
      .querySelector('#sp-cloud-bearer-token')
      ?.addEventListener('input', syncLiveConfigInputs);

    this.root
      .querySelector<HTMLInputElement>('#sp-cloud-auth-email')
      ?.addEventListener('input', (e) => {
        this.cloudAuthEmailDraft = (e.target as HTMLInputElement).value;
      });

    this.root
      .querySelector('#sp-cloud-signup-btn')
      ?.addEventListener('click', async () => {
        syncLiveConfigInputs();
        const email =
          this.root
            .querySelector<HTMLInputElement>('#sp-cloud-auth-email')
            ?.value.trim() ?? '';
        const pwd =
          this.root.querySelector<HTMLInputElement>('#sp-cloud-auth-password')
            ?.value ?? '';
        try {
          const user = await this.firebaseService.createCloudUser(email, pwd);
          this.cloudAuthEmailDraft = user.email;
          this.liveCloudConfig = this.firebaseService.toEndpointConfig();
          this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
          this.showToast(
            `Created zero-knowledge Cloud Sync user ${user.email} (${user.mode})`
          );
        } catch (err) {
          this.showToast(
            err instanceof Error ? err.message : 'Cloud user creation failed'
          );
        }
      });

    this.root
      .querySelector('#sp-cloud-signin-btn')
      ?.addEventListener('click', async () => {
        syncLiveConfigInputs();
        const email =
          this.root
            .querySelector<HTMLInputElement>('#sp-cloud-auth-email')
            ?.value.trim() ?? '';
        const pwd =
          this.root.querySelector<HTMLInputElement>('#sp-cloud-auth-password')
            ?.value ?? '';
        try {
          const user = await this.firebaseService.signInCloudUser(email, pwd);
          this.cloudAuthEmailDraft = user.email;
          this.liveCloudConfig = this.firebaseService.toEndpointConfig();
          this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
          this.showToast(`Signed in to Cloud Sync as ${user.email}`);
        } catch (err) {
          this.showToast(
            err instanceof Error ? err.message : 'Cloud sign-in failed'
          );
        }
      });

    this.root
      .querySelector('#sp-cloud-signout-btn')
      ?.addEventListener('click', () => {
        this.firebaseService.signOutCloudUser();
        this.liveCloudConfig = this.firebaseService.toEndpointConfig();
        this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
        this.showToast('Signed out of Cloud Sync');
      });

    this.root
      .querySelector('#sp-sync-now-btn')
      ?.addEventListener('click', async () => {
        syncLiveConfigInputs();
        try {
          const providerKey = `${this.syncCoordinator.cloudService}:${
            this.firebaseService.currentUser?.uid ?? 'local-default-uid'
          }`;
          const sinceCursor = await this.sqliteDb.getSyncCursor(providerKey);
          const pendingBlobs =
            await this.sqliteDb.listPendingUnsyncedBlobs('default-vault');
          const rows = await this.localDb.loadAllRowsIncludingTombstones();
          const res = await this.syncCoordinator.syncBidirectionalIfEnabled(
            'default-vault',
            rows,
            sinceCursor,
            pendingBlobs
          );
          if (!res) return;

          for (const uploadedId of Object.keys(res.uploadedBlobPaths)) {
            await this.sqliteDb.markLocalBlobSynced(uploadedId);
          }

          for (const merged of res.mergedRows) {
            await this.localDb.saveEncryptedRow(merged);
          }
          await this.sqliteDb.setSyncCursor(providerKey, res.cursorHlc);

          const activeHydrated: VaultItem[] = [];
          for (const merged of res.mergedRows) {
            if (merged.isTombstone) continue;
            try {
              activeHydrated.push(await this.crypto.decryptVaultRow(merged));
            } catch {
              // Skip rows encrypted with a different vault key
            }
          }

          let downloadedBlobs = 0;
          for (const item of activeHydrated) {
            let itemMetaUpdated = false;
            for (const att of item.attachments) {
              if (res.uploadedBlobPaths[att.id]) {
                att.blobSynced = true;
                att.storageObjectPath = res.uploadedBlobPaths[att.id];
                itemMetaUpdated = true;
              }
              const localOpfs = await this.opfsStore.readEncryptedBlob(att.id);
              const localSql = localOpfs
                ? null
                : await this.sqliteDb.loadLocalEncryptedBlob(att.id);
              if (!localOpfs && !localSql && !att.ciphertextBase64) {
                const remoteBlob =
                  await this.syncCoordinator.downloadMissingAttachmentBlobIfEnabled(
                    'default-vault',
                    att.id,
                    att.sha256Hex
                  );
                if (remoteBlob) {
                  await this.opfsStore.writeEncryptedBlob(att.id, remoteBlob);
                  await this.sqliteDb.saveLocalEncryptedBlob(
                    'default-vault',
                    att.id,
                    remoteBlob,
                    att.sha256Hex,
                    true
                  );
                  att.blobSynced = true;
                  itemMetaUpdated = true;
                  downloadedBlobs += 1;
                }
              }
            }
            if (itemMetaUpdated) {
              await this.persistSingleItemEncrypted(item);
            }
          }

          if (
            activeHydrated.length > 0 ||
            res.mergedRows.some((r) => r.isTombstone)
          ) {
            this.items = activeHydrated;
            this.selectedItemId = this.selectedItemId ?? this.items[0]?.id ?? null;
          }

          this.lastSyncedCount = res.pushedCount;
          this.lastPulledCount = res.pulledCount;
          this.render();
          this.showToast(
            `Two-Way Sync (${res.service}): ↑${res.pushedCount} items • ↓${res.pulledCount} items • 📎↑${res.uploadedBlobCount}/↓${downloadedBlobs} .enc blobs`
          );
        } catch (err) {
          this.showToast(
            err instanceof Error ? err.message : 'Cloud sync error'
          );
        }
      });

    this.root
      .querySelector('#sp-export-safepocket')
      ?.addEventListener('click', async () => {
        const snapshotItems = structuredClone(this.items);
        for (const item of snapshotItems) {
          for (const att of item.attachments) {
            if (!att.ciphertextBase64) {
              const bytes = await this.resolveEncryptedAttachmentBytes(
                item,
                att
              );
              if (bytes) {
                let bin = '';
                for (let i = 0; i < bytes.length; i++) {
                  bin += String.fromCharCode(bytes[i]);
                }
                att.ciphertextBase64 = btoa(bin);
              }
            }
          }
        }
        const containerJson = await this.crypto.exportSafePocketContainer(
          snapshotItems,
          this.activeMasterPassword,
          this.demoSecretKey
        );
        this.downloadBlob(
          containerJson,
          `vault-backup-${new Date().toISOString().slice(0, 10)}.safepocket`,
          'application/vnd.safepocket.container+json'
        );
        this.showToast('Exported encrypted .safepocket backup (with .enc blobs)');
      });

    // Encrypted .safepocket Backup Inspection & Cross-Vault Merge/Replace Modal
    this.root
      .querySelector<HTMLInputElement>('#sp-restore-safepocket')
      ?.addEventListener('change', async (e) => {
        const inputEl = e.target as HTMLInputElement;
        const file = inputEl.files?.[0];
        if (!file) return;
        const rawText = await file.text();
        inputEl.value = '';
        try {
          const summary = await inspectSafePocketContainerHeader(rawText);
          this.restoreModalState = {
            rawJson: rawText,
            summary,
            mode: 'merge',
            masterPassword: this.activeMasterPassword,
            secretKey: this.demoSecretKey
          };
          this.render();
        } catch (err) {
          this.showToast(
            err instanceof Error
              ? err.message
              : 'Invalid or tampered .safepocket container'
          );
        }
      });

    this.root
      .querySelectorAll<HTMLInputElement>('input[name="sp-restore-mode"]')
      .forEach((radio) => {
        radio.addEventListener('change', () => {
          if (!this.restoreModalState) return;
          this.restoreModalState.mode =
            radio.value === 'replace' ? 'replace' : 'merge';
        });
      });

    this.root
      .querySelector('#sp-restore-scan-qr-btn')
      ?.addEventListener('click', () => {
        if (!this.restoreModalState) return;
        const pwEl = this.root.querySelector<HTMLInputElement>(
          '#sp-restore-password'
        );
        const skEl =
          this.root.querySelector<HTMLInputElement>('#sp-restore-secret');
        if (pwEl) this.restoreModalState.masterPassword = pwEl.value;
        if (skEl) this.restoreModalState.secretKey = skEl.value;
        this.openCameraModal('recovery_qr', 'restore');
      });

    this.root
      .querySelector('#sp-cancel-restore-btn')
      ?.addEventListener('click', () => {
        this.restoreModalState = null;
        this.render();
      });

    this.root
      .querySelector('#sp-restore-backdrop')
      ?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) {
          this.restoreModalState = null;
          this.render();
        }
      });

    this.root
      .querySelector<HTMLFormElement>('#sp-restore-form')
      ?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const st = this.restoreModalState;
        if (!st) return;
        const pw =
          this.root.querySelector<HTMLInputElement>('#sp-restore-password')
            ?.value ?? '';
        const sk =
          this.root.querySelector<HTMLInputElement>('#sp-restore-secret')
            ?.value ?? '';
        st.masterPassword = pw;
        st.secretKey = sk;

        try {
          const incoming = await this.crypto.restoreSafePocketContainer(
            st.rawJson,
            pw,
            sk
          );
          for (const item of incoming) {
            for (const att of item.attachments) {
              if (att.ciphertextBase64) {
                const binStr = atob(att.ciphertextBase64);
                const bytes = new Uint8Array(binStr.length);
                for (let i = 0; i < binStr.length; i++) {
                  bytes[i] = binStr.charCodeAt(i);
                }
                await this.opfsStore.writeEncryptedBlob(att.id, bytes);
                await this.sqliteDb.saveLocalEncryptedBlob(
                  'default-vault',
                  att.id,
                  bytes,
                  att.sha256Hex,
                  false
                );
                if (bytes.byteLength > 16_384) {
                  att.ciphertextBase64 = undefined;
                }
              }
            }
          }

          if (st.mode === 'replace') {
            await this.localDb.clearAll();
            this.items = incoming;
            await this.persistAllItemsEncrypted();
            this.restoreModalState = null;
            this.selectedItemId = this.items[0]?.id ?? null;
            this.render();
            this.showToast(
              `Replaced vault with ${incoming.length} encrypted items`
            );
          } else {
            const report = mergeVaultSnapshots(this.items, incoming);
            this.items = report.merged;
            await this.persistAllItemsEncrypted();
            this.restoreModalState = null;
            this.selectedItemId = this.selectedItemId ?? this.items[0]?.id ?? null;
            this.render();
            this.showToast(
              `Merged backup: +${report.addedCount} added • ${report.updatedCount} updated • ${report.unchangedCount} unchanged`
            );
          }
        } catch (err) {
          if (this.restoreModalState) {
            this.restoreModalState.error =
              err instanceof Error
                ? err.message
                : 'Backup decryption failed — verify Master Password & Secret Key';
            this.render();
          }
        }
      });

    // Master-Password-Gated Plaintext JSON/CSV Export Modal
    this.root
      .querySelector('#sp-export-plain-csv')
      ?.addEventListener('click', () => {
        this.plainExportModalState = {
          format: 'json',
          password: ''
        };
        this.render();
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-plain-format]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          if (!this.plainExportModalState) return;
          const pwEl = this.root.querySelector<HTMLInputElement>(
            '#sp-plain-export-password'
          );
          if (pwEl) this.plainExportModalState.password = pwEl.value;
          this.plainExportModalState.format =
            btn.dataset.plainFormat === 'csv' ? 'csv' : 'json';
          this.render();
        });
      });

    this.root
      .querySelector('#sp-cancel-plain-export-btn')
      ?.addEventListener('click', () => {
        this.plainExportModalState = null;
        this.render();
      });

    this.root
      .querySelector('#sp-plain-export-backdrop')
      ?.addEventListener('click', (e) => {
        if (e.target === e.currentTarget) {
          this.plainExportModalState = null;
          this.render();
        }
      });

    this.root
      .querySelector<HTMLFormElement>('#sp-plain-export-form')
      ?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const st = this.plainExportModalState;
        if (!st) return;
        const candidate =
          this.root.querySelector<HTMLInputElement>('#sp-plain-export-password')
            ?.value ?? '';
        st.password = candidate;
        const verified = await this.crypto.verifyMasterPassword(candidate);
        if (!verified) {
          st.error = 'Master Password verification failed';
          this.render();
          return;
        }
        const exported = formatUnencryptedVaultExport(this.items, st.format);
        this.plainExportModalState = null;
        this.render();
        this.downloadBlob(
          exported.content,
          `safepocket-export.${exported.extension}`,
          exported.mimeType
        );
        this.showToast(
          `Exported unencrypted ${exported.extension.toUpperCase()} (${this.items.length} items)`
        );
      });

    // Settings: Allow Screenshots, Expiry Warning Horizon, Auto-Lock Timeout, Biometrics & Emergency Kit v2
    this.root
      .querySelector<HTMLInputElement>('#sp-toggle-allow-screenshots')
      ?.addEventListener('change', (e) => {
        this.setAllowScreenshots((e.target as HTMLInputElement).checked);
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-expiry-window]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const days = Number(btn.dataset.expiryWindow) as ExpiryWindowDays;
          if (EXPIRY_WINDOW_OPTIONS.includes(days)) {
            this.setExpiryWindowDays(days);
          }
        });
      });

    this.root
      .querySelectorAll<HTMLElement>('[data-autolock-min]')
      .forEach((btn) => {
        btn.addEventListener('click', () => {
          const min = Number(btn.dataset.autolockMin) as AutoLockMinutes;
          this.autoLockMonitor.setMinutes(min);
          this.render();
        });
      });

    this.root
      .querySelector('#sp-enroll-passkey')
      ?.addEventListener('click', async () => {
        const ok = await WebAuthnBiometricUnlock.enrollPlatformBiometric().catch(
          () => false
        );
        this.showToast(
          ok
            ? 'Hardware Passkey enrolled'
            : 'WebAuthn requires interactive top-level browser gesture'
        );
      });

    this.root
      .querySelector<HTMLInputElement>('#sp-settings-kit-hint')
      ?.addEventListener('input', (e) => {
        this.emergencyKitHintDraft = (e.target as HTMLInputElement).value;
      });

    this.root
      .querySelector('#sp-settings-print-kit')
      ?.addEventListener('click', () => {
        this.printEmergencyKit();
      });

    this.root
      .querySelector('#sp-settings-emergency-kit')
      ?.addEventListener('click', () => {
        this.downloadEmergencyKit();
      });
  }

  private async encryptAndAttachPayloads(
    target: VaultItem,
    payloads: Array<{
      fileName: string;
      mimeType: string;
      bytes: Uint8Array;
    }>
  ): Promise<void> {
    if (payloads.length === 0) return;

    const pendingUploadBlobs: Array<{
      attachmentId: string;
      sha256Hex: string;
      encryptedBytes: Uint8Array;
    }> = [];

    for (let idx = 0; idx < payloads.length; idx++) {
      const p = payloads[idx];
      const attId = `att_${Date.now()}_${idx}`;
      const encryptedAtt = await this.crypto.encryptBinaryAttachment(
        attId,
        p.fileName,
        p.mimeType || 'application/octet-stream',
        p.bytes,
        this.uploadScanSide
      );
      await this.opfsStore.writeEncryptedBlob(
        attId,
        encryptedAtt.encryptedBytes
      );
      await this.sqliteDb.saveLocalEncryptedBlob(
        'default-vault',
        attId,
        encryptedAtt.encryptedBytes,
        encryptedAtt.sha256Hex,
        false
      );
      pendingUploadBlobs.push({
        attachmentId: attId,
        sha256Hex: encryptedAtt.sha256Hex,
        encryptedBytes: encryptedAtt.encryptedBytes
      });
      // Omit inline base64 above 16 KiB so Firestore CRDT rows never hit the 1 MiB document cap
      target.attachments.push({
        id: encryptedAtt.id,
        fileName: encryptedAtt.fileName,
        mimeType: encryptedAtt.mimeType,
        side: encryptedAtt.side,
        sizeBytes: encryptedAtt.sizeBytes,
        sha256Hex: encryptedAtt.sha256Hex,
        ivHex: encryptedAtt.ivHex,
        ciphertextBase64:
          encryptedAtt.encryptedBytes.byteLength <= 16_384
            ? encryptedAtt.ciphertextBase64
            : undefined,
        blobSynced: false
      });
    }

    if (this.syncCoordinator.isCloudSyncEnabled) {
      try {
        this.liveCloudConfig = this.firebaseService.toEndpointConfig(
          this.liveCloudConfig.bearerToken
        );
        this.firebaseAdapter.setLiveEndpointConfig(this.liveCloudConfig);
        this.gdriveAdapter.setLiveEndpointConfig(this.liveCloudConfig);

        const uploadRes =
          await this.syncCoordinator.uploadAttachmentBlobsIfEnabled(
            'default-vault',
            pendingUploadBlobs
          );
        if (uploadRes) {
          for (const b of pendingUploadBlobs) {
            await this.sqliteDb.markLocalBlobSynced(b.attachmentId);
            const meta = target.attachments.find((a) => a.id === b.attachmentId);
            if (meta) {
              meta.blobSynced = true;
              meta.storageObjectPath = uploadRes.paths[b.attachmentId];
            }
          }
        }
      } catch (err) {
        this.showToast(
          err instanceof Error
            ? `Saved locally (.enc queued for retry): ${err.message}`
            : 'Saved locally (.enc queued for retry)'
        );
      }
    }

    target.updatedAtEpochMs = Date.now();
    await this.persistSingleItemEncrypted(target);
    if (this.syncCoordinator.isCloudSyncEnabled) {
      try {
        const allRows = await this.localDb.loadAllRowsIncludingTombstones();
        await this.syncCoordinator.syncEncryptedRowsIfEnabled(
          'default-vault',
          allRows.filter((r) => r.id === target.id)
        );
      } catch {
        // Offline-first: CRDT delta remains queued locally
      }
    }
    this.render();
    const syncedAll = pendingUploadBlobs.every(
      (b) => target.attachments.find((a) => a.id === b.attachmentId)?.blobSynced
    );
    this.showToast(
      syncedAll && this.syncCoordinator.isCloudSyncEnabled
        ? `Encrypted & synced ${payloads.length} .enc scan(s) → EU Storage`
        : `Encrypted ${payloads.length} scan(s) → Local OPFS + SQLite (AES-256-GCM)`
    );
  }

  private downloadBlob(
    content: string,
    fileName: string,
    mimeType: string
  ): void {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  }

  private escape(str: string): string {
    return str
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');
  }
}
