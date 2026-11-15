export type SupportedLocale = 'en' | 'de' | 'it' | 'fr' | 'es' | 'el';

export type VaultCategory =
  | 'PAYMENT_CARD'
  | 'IDENTITY'
  | 'OFFICIAL_DOCUMENT'
  | 'SECURE_NOTE';

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

export type CardThemePalette =
  | 'obsidian_slate'
  | 'alpine_emerald'
  | 'aegean_cobalt'
  | 'terracotta_gold';

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

export interface VaultItem {
  schemaVersion: number;
  id: string;
  category: VaultCategory;
  title: string;
  subtitle: string;
  favorite: boolean;
  tags: string[];
  cardTheme?: {
    paletteId: CardThemePalette;
    networkBrand?: string;
  };
  documentSubtype?: DocumentSubtype;
  fields: VaultField[];
  attachments: EncryptedAttachmentMeta[];
  createdAtEpochMs: number;
  updatedAtEpochMs: number;
}

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
  'field_iban',
  'field_tax_id',
  'field_ssn',
  'field_health_insurance',
  'field_document_number'
]);

export const APP_BUILD_INFO = {
  appName: 'SafePocket',
  versionName: '0.1',
  versionCode: 1
} as const;
