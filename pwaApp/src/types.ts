export type SupportedLocale = 'en' | 'de' | 'it' | 'fr' | 'es' | 'el';

export type ThemeMode = 'light' | 'dark' | 'system';

export type MaterialColorScheme =
  | 'emerald'
  | 'device'
  | 'ocean'
  | 'terracotta'
  | 'slate';

export type VaultCategory =
  | 'PAYMENT_CARD'
  | 'BANK_ACCOUNT'
  | 'INSURANCE_CARD'
  | 'MEMBERSHIP_CARD'
  | 'IDENTITY'
  | 'OFFICIAL_DOCUMENT'
  | 'SECURE_NOTE';

export const VAULT_CATEGORIES: ReadonlyArray<VaultCategory> = [
  'PAYMENT_CARD',
  'BANK_ACCOUNT',
  'INSURANCE_CARD',
  'MEMBERSHIP_CARD',
  'IDENTITY',
  'OFFICIAL_DOCUMENT',
  'SECURE_NOTE'
];

export type DocumentSubtype =
  | 'PASSPORT'
  | 'NATIONAL_ID'
  | 'DRIVERS_LICENSE'
  | 'RESIDENCE_PERMIT_VISA';

export type FieldValueType =
  | 'TEXT'
  | 'SENSITIVE_SECRET'
  | 'CARD_NUMBER'
  | 'IBAN'
  | 'DATE_ISO'
  | 'PHONE'
  | 'EMAIL'
  | 'MULTILINE_ADDRESS'
  | 'MARKDOWN';

export type BarcodeFormat = 'CODE_128' | 'EAN_13' | 'QR_CODE';

export type VaultSortMode =
  | 'pinned_recent'
  | 'alphabetical'
  | 'expiring_soon'
  | 'updated_desc';

export type ExpiryWindowDays = 30 | 60 | 90 | 180;

export const EXPIRY_WINDOW_OPTIONS: ReadonlyArray<ExpiryWindowDays> = [
  30,
  60,
  90,
  180
];

export type PresetCardThemePalette =
  | 'obsidian_slate'
  | 'alpine_emerald'
  | 'aegean_cobalt'
  | 'terracotta_gold'
  | 'crimson_ruby'
  | 'midnight_navy'
  | 'arctic_cyan'
  | 'forest_sage'
  | 'sunset_coral'
  | 'espresso_bronze'
  | 'titanium_silver'
  | 'pearl_ivory';

export type CardThemePalette = PresetCardThemePalette | 'custom';

export const CARD_THEME_PALETTES: ReadonlyArray<PresetCardThemePalette> = [
  'obsidian_slate',
  'alpine_emerald',
  'aegean_cobalt',
  'terracotta_gold',
  'crimson_ruby',
  'midnight_navy',
  'arctic_cyan',
  'forest_sage',
  'sunset_coral',
  'espresso_bronze',
  'titanium_silver',
  'pearl_ivory'
];

export interface VaultField {
  id: string;
  labelKey: string;
  isCustomLabel?: boolean;
  value: string;
  valueType: FieldValueType;
  isSensitive?: boolean;
}

export interface EncryptedAttachmentMeta {
  id: string;
  fileName: string;
  mimeType: string;
  side?: 'front' | 'back';
  sizeBytes: number;
  sha256Hex: string;
  ivHex: string;
  ciphertextBase64?: string;
  blobSynced?: boolean;
  storageObjectPath?: string;
}

export interface EncryptedBlobEnvelope {
  attachmentId: string;
  sha256Hex: string;
  encryptedBytes: Uint8Array;
}

export interface VaultHeaderMetadata {
  vaultId: string;
  createdAtIso: string;
  saltHex: string;
  memorySizeKiB: number;
  iterations: number;
  parallelism: number;
  verifierHashHex: string;
}

export type VaultStorageMode = 'LOCAL_ONLY' | 'CLOUD_SYNC';

export interface VaultItem {
  schemaVersion: number;
  id: string;
  category: VaultCategory;
  title: string;
  subtitle: string;
  favorite: boolean;
  pinned?: boolean;
  isSampleItem?: boolean;
  lastAccessedAtEpochMs?: number;
  tags: string[];
  cardTheme?: {
    paletteId: CardThemePalette;
    customColorHex?: string;
    showVisualCard?: boolean;
    barcodeFormat?: BarcodeFormat;
    networkBrand?: string;
    institutionId?: string;
    institutionName?: string;
    institutionDomain?: string;
    institutionLogoDataUri?: string;
    autoBrandColor?: boolean;
  };
  documentSubtype?: DocumentSubtype;
  fields: VaultField[];
  attachments: EncryptedAttachmentMeta[];
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
}

export const SAMPLE_VAULT_ITEM_IDS = new Set<string>([
  'item-card-db-visa',
  'item-card-n26-metal',
  'item-card-alpine-metal',
  'item-bank-ing-giro',
  'item-bank-sepa-main',
  'item-ins-tk-egk',
  'item-ins-ehic-eu',
  'item-mem-bahncard',
  'item-mem-adac-plus',
  'item-identity-primary',
  'item-id-clara',
  'item-id-eu-citizen',
  'item-doc-hellenic-passport',
  'item-doc-cie-italy',
  'item-doc-passport-de',
  'item-doc-passport-eu',
  'item-note-home-safe',
  'item-note-emergency',
  'item-note-emergency-kit',
  'item-note-vault-codes'
]);

export interface EncryptedVaultRow {
  id: string;
  category: VaultCategory;
  favorite: boolean;
  ivHex: string;
  ciphertextHex: string;
  aadContext: string;
  hlcPacked: string;
  versionVector: Record<string, number>;
  isTombstone: boolean;
  updatedAtEpochMs: number;
}

export interface RemoteUpdateManifest {
  schemaVersion: number;
  channel: string;
  latestVersion: string;
  versionCode: number;
  minimumSupportedVersionCode: number;
  publishedAt: string;
  signingKeyId: string;
  signingPublicKeyHex?: string;
  manifestSignature: string;
  releaseNotes: Record<string, string>;
  artifacts: Record<
    string,
    {
      url: string;
      sha256: string;
      sizeBytes: number;
      signingCertSha256?: string;
    }
  >;
}

export const SENSITIVE_FIELD_KEYS = new Set<string>([
  'field_card_number',
  'field_cvv',
  'field_pin',
  'field_online_passcode',
  'field_iban',
  'field_account_number',
  'field_insurance_number',
  'field_tax_id',
  'field_ssn',
  'field_health_insurance',
  'field_document_number'
]);

export const APP_BUILD_INFO = {
  appName: 'SafePocket',
  versionName: '0.3.6',
  versionCode: 11
} as const;
