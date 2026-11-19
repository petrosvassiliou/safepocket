import { argon2id } from 'hash-wasm';
import { APP_BUILD_INFO, type EncryptedVaultRow, type VaultItem } from '../types';

export interface Argon2idKdfConfig {
  memorySizeKiB: number;
  iterations: number;
  parallelism: number;
  saltHex: string;
}

export interface SafePocketEncryptedContainer {
  magic: 'SAFEPOCKET-CONTAINER-V1';
  appVersion?: string;
  exportedAtIso: string;
  itemCount?: number;
  attachmentCount?: number;
  kdf: Argon2idKdfConfig;
  ivHex: string;
  ciphertextHex: string;
  sha256Hex: string;
}

export interface BackupInspectionSummary {
  exportedAtIso: string;
  appVersion: string;
  itemCount?: number;
  attachmentCount?: number;
  sha256Hex: string;
  kdf: Argon2idKdfConfig;
}

export interface VaultMergeReport {
  merged: VaultItem[];
  addedCount: number;
  updatedCount: number;
  unchangedCount: number;
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function hexToBytes(hex: string): Uint8Array {
  if (hex.length % 2 !== 0) {
    throw new Error('Invalid hex string length');
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

export async function sha256Hex(data: Uint8Array | ArrayBuffer): Promise<string> {
  const buf = data instanceof Uint8Array ? data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) : data;
  const digest = await crypto.subtle.digest('SHA-256', buf as ArrayBuffer);
  return bytesToHex(new Uint8Array(digest));
}

export function constantTimeHexEquals(a: string, b: string): boolean {
  const normA = a.toLowerCase();
  const normB = b.toLowerCase();
  if (normA.length !== normB.length) return false;
  let diff = 0;
  for (let i = 0; i < normA.length; i++) {
    diff |= normA.charCodeAt(i) ^ normB.charCodeAt(i);
  }
  return diff === 0;
}

/**
 * Generates a high-entropy local Secret Key (`SPK-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX`).
 */
export function generateFormattedSecretKey(): string {
  const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  const rand = crypto.getRandomValues(new Uint8Array(25));
  const groups: string[] = [];
  for (let g = 0; g < 5; g++) {
    let chunk = '';
    for (let i = 0; i < 5; i++) {
      chunk += alphabet[rand[g * 5 + i] % alphabet.length];
    }
    groups.push(chunk);
  }
  return `SPK-${groups.join('-')}`;
}

/**
 * Derives a 256-bit AES-GCM CryptoKey from the user's Master Password + Local Secret Key
 * using Argon2id (WASM) combined with HKDF-SHA-256 (Two-Secret Key Derivation).
 */
export async function deriveTwoSecretAes256GcmKey(
  masterPassword: string,
  secretKeyFormatted: string,
  config: Argon2idKdfConfig,
  domainContext = 'safepocket-2skd-v1'
): Promise<CryptoKey> {
  const saltBytes = hexToBytes(config.saltHex);
  const argonHex = await argon2id({
    password: masterPassword,
    salt: saltBytes,
    parallelism: config.parallelism,
    iterations: config.iterations,
    memorySize: config.memorySizeKiB,
    hashLength: 32,
    outputType: 'hex'
  });

  const argonBytes = hexToBytes(argonHex);
  const normalizedSecret = new TextEncoder().encode(
    secretKeyFormatted.trim().toUpperCase()
  );

  // Combine Argon2id(MasterPassword, Salt) with SecretKey via HKDF-SHA-256
  const ikm = new Uint8Array(argonBytes.length + normalizedSecret.length);
  ikm.set(argonBytes, 0);
  ikm.set(normalizedSecret, argonBytes.length);

  const hkdfBase = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, [
    'deriveKey'
  ]);
  argonBytes.fill(0);
  ikm.fill(0);

  return crypto.subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: saltBytes as BufferSource,
      info: new TextEncoder().encode(domainContext)
    },
    hkdfBase,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

/**
 * Zero-Knowledge Client-Side Vault Encryption Engine (AES-256-GCM).
 */
export class ZeroKnowledgeVaultCrypto {
  private vaultKey: CryptoKey | null = null;
  private activeMasterVerifierHash: string | null = null;
  private activeSecretKey = '';
  private kdfConfig: Argon2idKdfConfig;

  constructor(initialKdf?: Partial<Argon2idKdfConfig>) {
    const defaultSalt = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
    this.kdfConfig = {
      memorySizeKiB: initialKdf?.memorySizeKiB ?? 16_384, // 16 MiB fast interactive WASM profile
      iterations: initialKdf?.iterations ?? 3,
      parallelism: initialKdf?.parallelism ?? 2,
      saltHex: initialKdf?.saltHex ?? defaultSalt
    };
  }

  get isUnlocked(): boolean {
    return this.vaultKey !== null;
  }

  get currentKdfConfig(): Argon2idKdfConfig {
    return { ...this.kdfConfig };
  }

  get currentSecretKey(): string {
    return this.activeSecretKey;
  }

  async initializeOrUnlock(
    masterPassword: string,
    secretKeyFormatted: string,
    kdfOverride?: Argon2idKdfConfig
  ): Promise<void> {
    if (kdfOverride) {
      this.kdfConfig = { ...kdfOverride };
    }
    this.vaultKey = await deriveTwoSecretAes256GcmKey(
      masterPassword,
      secretKeyFormatted,
      this.kdfConfig
    );
    this.activeSecretKey = secretKeyFormatted.trim().toUpperCase();
    this.activeMasterVerifierHash = await sha256Hex(
      new TextEncoder().encode(
        `verifier:${this.kdfConfig.saltHex}:${masterPassword}:${this.activeSecretKey}`
      )
    );
  }

  lock(): void {
    this.vaultKey = null;
  }

  async verifyMasterPassword(candidatePassword: string): Promise<boolean> {
    if (!this.activeMasterVerifierHash) return false;
    const candidateHash = await sha256Hex(
      new TextEncoder().encode(
        `verifier:${this.kdfConfig.saltHex}:${candidatePassword}:${this.activeSecretKey}`
      )
    );
    return constantTimeHexEquals(candidateHash, this.activeMasterVerifierHash);
  }

  async encryptVaultItem(
    item: VaultItem,
    hlcPacked: string,
    versionVector: Record<string, number>
  ): Promise<EncryptedVaultRow> {
    if (!this.vaultKey) {
      throw new Error('SafePocket vault is locked');
    }
    const normalizedItem: VaultItem = {
      ...item,
      schemaVersion: item.schemaVersion || 1,
      attachments: item.attachments ?? [],
      createdAtEpochMs: Math.round(item.createdAtEpochMs || Date.now()),
      updatedAtEpochMs: Math.round(item.updatedAtEpochMs || Date.now())
    };
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aadContext = `safepocket:v${normalizedItem.schemaVersion}:${normalizedItem.category}:${normalizedItem.id}`;
    const plaintextBytes = new TextEncoder().encode(JSON.stringify(normalizedItem));

    const cipherBuffer = await crypto.subtle.encrypt(
      {
        name: 'AES-GCM',
        iv,
        additionalData: new TextEncoder().encode(aadContext),
        tagLength: 128
      },
      this.vaultKey,
      plaintextBytes
    );

    return {
      id: normalizedItem.id,
      category: normalizedItem.category,
      favorite: normalizedItem.favorite,
      ivHex: bytesToHex(iv),
      ciphertextHex: bytesToHex(new Uint8Array(cipherBuffer)),
      aadContext,
      hlcPacked,
      versionVector,
      isTombstone: false,
      updatedAtEpochMs: normalizedItem.updatedAtEpochMs
    };
  }

  async decryptVaultRow(row: EncryptedVaultRow): Promise<VaultItem> {
    if (!this.vaultKey) {
      throw new Error('SafePocket vault is locked');
    }
    const iv = hexToBytes(row.ivHex);
    const ciphertext = hexToBytes(row.ciphertextHex);

    const plainBuffer = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: iv as BufferSource,
        additionalData: new TextEncoder().encode(row.aadContext),
        tagLength: 128
      },
      this.vaultKey,
      ciphertext as BufferSource
    );

    const parsed = JSON.parse(new TextDecoder().decode(plainBuffer)) as VaultItem;
    return {
      ...parsed,
      schemaVersion: parsed.schemaVersion || 1,
      tags: Array.isArray(parsed.tags) ? parsed.tags : [],
      fields: Array.isArray(parsed.fields) ? parsed.fields : [],
      attachments: Array.isArray(parsed.attachments)
        ? parsed.attachments.map((a) => ({ ...a, ivHex: a.ivHex ?? '' }))
        : [],
      createdAtEpochMs: Math.round(parsed.createdAtEpochMs || Date.now()),
      updatedAtEpochMs: Math.round(parsed.updatedAtEpochMs || Date.now())
    };
  }

  private async importAesKeyFromHex(
    keyHex: string,
    usages: KeyUsage[]
  ): Promise<CryptoKey> {
    const clean = keyHex.trim().toLowerCase();
    if (clean.length !== 64) {
      throw new Error('Invalid 256-bit Cloud Sync key hex');
    }
    const rawBytes = hexToBytes(clean);
    return crypto.subtle.importKey(
      'raw',
      rawBytes as BufferSource,
      { name: 'AES-GCM', length: 256 },
      false,
      usages
    );
  }

  async encryptVaultItemWithKeyHex(
    item: VaultItem,
    keyHex: string,
    hlcPacked: string,
    versionVector: Record<string, number>
  ): Promise<EncryptedVaultRow> {
    const aesKey = await this.importAesKeyFromHex(keyHex, ['encrypt']);
    const normalizedItem: VaultItem = {
      ...item,
      schemaVersion: item.schemaVersion || 1,
      attachments: item.attachments ?? [],
      createdAtEpochMs: Math.round(item.createdAtEpochMs || Date.now()),
      updatedAtEpochMs: Math.round(item.updatedAtEpochMs || Date.now())
    };
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aadContext = `safepocket:v${normalizedItem.schemaVersion}:${normalizedItem.category}:${normalizedItem.id}`;
    const plaintextBytes = new TextEncoder().encode(JSON.stringify(normalizedItem));
    const cipherBuffer = await crypto.subtle.encrypt(
      {
        name: 'AES-GCM',
        iv,
        additionalData: new TextEncoder().encode(aadContext),
        tagLength: 128
      },
      aesKey,
      plaintextBytes
    );
    return {
      id: normalizedItem.id,
      category: normalizedItem.category,
      favorite: normalizedItem.favorite,
      ivHex: bytesToHex(iv),
      ciphertextHex: bytesToHex(new Uint8Array(cipherBuffer)),
      aadContext,
      hlcPacked,
      versionVector,
      isTombstone: false,
      updatedAtEpochMs: normalizedItem.updatedAtEpochMs
    };
  }

  async createTombstoneRowWithKeyHex(
    itemId: string,
    category: VaultItem['category'],
    keyHex: string,
    hlcPacked: string,
    versionVector: Record<string, number>
  ): Promise<EncryptedVaultRow> {
    const aesKey = await this.importAesKeyFromHex(keyHex, ['encrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aadContext = `safepocket:v1:${category}:${itemId}`;
    const tombstoneMarker = new TextEncoder().encode('{"deleted":true}');
    const cipherBuf = await crypto.subtle.encrypt(
      {
        name: 'AES-GCM',
        iv,
        additionalData: new TextEncoder().encode(aadContext),
        tagLength: 128
      },
      aesKey,
      tombstoneMarker
    );
    return {
      id: itemId,
      category,
      favorite: false,
      ivHex: bytesToHex(iv),
      ciphertextHex: bytesToHex(new Uint8Array(cipherBuf)),
      aadContext,
      hlcPacked,
      versionVector,
      isTombstone: true,
      updatedAtEpochMs: Date.now()
    };
  }

  async decryptVaultRowWithKeyHex(
    row: EncryptedVaultRow,
    keyHex: string
  ): Promise<VaultItem> {
    const aesKey = await this.importAesKeyFromHex(keyHex, ['decrypt']);
    const iv = hexToBytes(row.ivHex);
    const ciphertext = hexToBytes(row.ciphertextHex);
    const plainBuffer = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: iv as BufferSource,
        additionalData: new TextEncoder().encode(row.aadContext),
        tagLength: 128
      },
      aesKey,
      ciphertext as BufferSource
    );
    const parsed = JSON.parse(new TextDecoder().decode(plainBuffer)) as VaultItem;
    return {
      ...parsed,
      schemaVersion: parsed.schemaVersion || 1,
      tags: Array.isArray(parsed.tags) ? parsed.tags : [],
      fields: Array.isArray(parsed.fields) ? parsed.fields : [],
      attachments: Array.isArray(parsed.attachments)
        ? parsed.attachments.map((a) => ({ ...a, ivHex: a.ivHex ?? '' }))
        : [],
      createdAtEpochMs: Math.round(parsed.createdAtEpochMs || Date.now()),
      updatedAtEpochMs: Math.round(parsed.updatedAtEpochMs || Date.now())
    };
  }

  async encryptBinaryAttachment(
    attachmentId: string,
    fileName: string,
    mimeType: string,
    rawBytes: Uint8Array,
    side?: 'front' | 'back'
  ): Promise<{
    id: string;
    fileName: string;
    mimeType: string;
    side?: 'front' | 'back';
    sizeBytes: number;
    sha256Hex: string;
    ivHex: string;
    ciphertextBase64: string;
    encryptedBytes: Uint8Array;
  }> {
    if (!this.vaultKey) {
      throw new Error('SafePocket vault is locked');
    }
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aad = new TextEncoder().encode(`safepocket:attachment:${attachmentId}`);
    const encrypted = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 },
      this.vaultKey,
      rawBytes as BufferSource
    );
    const encBytes = new Uint8Array(encrypted);
    const digest = await sha256Hex(encBytes);
    let binary = '';
    for (let i = 0; i < encBytes.length; i++) {
      binary += String.fromCharCode(encBytes[i]);
    }
    return {
      id: attachmentId,
      fileName,
      mimeType,
      side,
      sizeBytes: rawBytes.byteLength,
      sha256Hex: digest,
      ivHex: bytesToHex(iv),
      ciphertextBase64: btoa(binary),
      encryptedBytes: encBytes
    };
  }

  /**
   * Verifies the SHA-256 digest of the encrypted attachment blob in constant time and decrypts
   * purely in RAM (`Uint8Array`) so callers can render ephemeral `Blob` URLs with zero disk writes.
   */
  async decryptBinaryAttachment(params: {
    attachmentId: string;
    ivHex: string;
    expectedSha256Hex: string;
    encryptedBytes?: Uint8Array;
    ciphertextBase64?: string;
  }): Promise<Uint8Array> {
    if (!this.vaultKey) {
      throw new Error('SafePocket vault is locked');
    }
    let cipherBytes = params.encryptedBytes;
    if (!cipherBytes && params.ciphertextBase64) {
      const binStr = atob(params.ciphertextBase64);
      cipherBytes = new Uint8Array(binStr.length);
      for (let i = 0; i < binStr.length; i++) {
        cipherBytes[i] = binStr.charCodeAt(i);
      }
    }
    if (!cipherBytes) {
      throw new Error('Encrypted attachment ciphertext unavailable');
    }

    const actualSha = await sha256Hex(cipherBytes);
    if (!constantTimeHexEquals(actualSha, params.expectedSha256Hex)) {
      throw new Error('Attachment SHA-256 integrity check failed — tampering detected');
    }

    const iv = hexToBytes(params.ivHex);
    const aad = new TextEncoder().encode(
      `safepocket:attachment:${params.attachmentId}`
    );
    const decryptedBuffer = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: iv as BufferSource,
        additionalData: aad,
        tagLength: 128
      },
      this.vaultKey,
      cipherBytes as BufferSource
    );
    return new Uint8Array(decryptedBuffer);
  }

  /**
   * Produces a zero-knowledge CRDT tombstone envelope (`isTombstone = true`) wiping plaintext payload
   * while advancing the causal Version Vector and Hybrid Logical Clock so deletions converge across devices.
   */
  async createTombstoneRow(
    itemId: string,
    category: VaultItem['category'],
    hlcPacked: string,
    versionVector: Record<string, number>
  ): Promise<EncryptedVaultRow> {
    if (!this.vaultKey) {
      throw new Error('SafePocket vault is locked');
    }
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aadContext = `safepocket:tombstone:${category}:${itemId}`;
    const tombstoneMarker = new TextEncoder().encode(
      JSON.stringify({ deletedId: itemId, deletedAtHlc: hlcPacked })
    );
    const cipherBuf = await crypto.subtle.encrypt(
      {
        name: 'AES-GCM',
        iv,
        additionalData: new TextEncoder().encode(aadContext),
        tagLength: 128
      },
      this.vaultKey,
      tombstoneMarker
    );
    return {
      id: itemId,
      category,
      favorite: false,
      ivHex: bytesToHex(iv),
      ciphertextHex: bytesToHex(new Uint8Array(cipherBuf)),
      aadContext,
      hlcPacked,
      versionVector,
      isTombstone: true,
      updatedAtEpochMs: Date.now()
    };
  }

  async exportSafePocketContainer(
    items: VaultItem[],
    masterPassword: string,
    secretKeyFormatted: string
  ): Promise<string> {
    const saltHex = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
    const kdf: Argon2idKdfConfig = {
      memorySizeKiB: this.kdfConfig.memorySizeKiB,
      iterations: this.kdfConfig.iterations,
      parallelism: this.kdfConfig.parallelism,
      saltHex
    };
    const key = await deriveTwoSecretAes256GcmKey(
      masterPassword,
      secretKeyFormatted,
      kdf,
      'safepocket-container-backup-v1'
    );
    const exportedAtIso = new Date().toISOString();
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aad = new TextEncoder().encode(`SAFEPOCKET-CONTAINER-V1:${exportedAtIso}`);
    const plaintext = new TextEncoder().encode(JSON.stringify(items));

    const cipherBuf = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 },
      key,
      plaintext
    );
    const cipherBytes = new Uint8Array(cipherBuf);
    const digestHex = await sha256Hex(cipherBytes);
    const attachmentCount = items.reduce(
      (sum, item) => sum + item.attachments.length,
      0
    );

    const container: SafePocketEncryptedContainer = {
      magic: 'SAFEPOCKET-CONTAINER-V1',
      appVersion: APP_BUILD_INFO.versionName,
      exportedAtIso,
      itemCount: items.length,
      attachmentCount,
      kdf,
      ivHex: bytesToHex(iv),
      ciphertextHex: bytesToHex(cipherBytes),
      sha256Hex: digestHex
    };
    return JSON.stringify(container, null, 2);
  }

  async restoreSafePocketContainer(
    rawJson: string,
    masterPassword: string,
    secretKeyFormatted: string
  ): Promise<VaultItem[]> {
    const summary = await inspectSafePocketContainerHeader(rawJson);
    const parsed = JSON.parse(rawJson) as Record<string, unknown>;
    const cipherBytes = hexToBytes(String(parsed.ciphertextHex));
    const iv = hexToBytes(String(parsed.ivHex));
    const aad = new TextEncoder().encode(
      `SAFEPOCKET-CONTAINER-V1:${summary.exportedAtIso}`
    );

    const domains = ['safepocket-container-backup-v1', 'safepocket-backup-v1'];
    let lastErr: unknown = null;
    for (const domain of domains) {
      try {
        const key = await deriveTwoSecretAes256GcmKey(
          masterPassword,
          secretKeyFormatted,
          summary.kdf,
          domain
        );
        const plainBuf = await crypto.subtle.decrypt(
          {
            name: 'AES-GCM',
            iv: iv as BufferSource,
            additionalData: aad,
            tagLength: 128
          },
          key,
          cipherBytes as BufferSource
        );
        return JSON.parse(new TextDecoder().decode(plainBuf)) as VaultItem[];
      } catch (err) {
        lastErr = err;
      }
    }
    throw lastErr instanceof Error
      ? lastErr
      : new Error('Backup decryption failed — verify Master Password & Secret Key');
  }
}

export async function inspectSafePocketContainerHeader(
  rawJson: string
): Promise<BackupInspectionSummary> {
  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(rawJson) as Record<string, unknown>;
  } catch {
    throw new Error('Invalid .safepocket file: malformed JSON');
  }
  if (parsed.magic !== 'SAFEPOCKET-CONTAINER-V1') {
    throw new Error('Invalid .safepocket container header');
  }
  const cipherHex = String(parsed.ciphertextHex ?? '');
  const expectedSha = String(
    parsed.sha256Hex ?? parsed.containerSha256Hex ?? ''
  );
  if (!cipherHex || !expectedSha) {
    throw new Error('Invalid .safepocket container: missing ciphertext or SHA-256');
  }
  const cipherBytes = hexToBytes(cipherHex);
  const computedSha = await sha256Hex(cipherBytes);
  if (!constantTimeHexEquals(computedSha, expectedSha)) {
    throw new Error('Backup integrity check failed: SHA-256 mismatch');
  }

  const rawKdf = (parsed.kdf ?? parsed.kdfParams ?? {}) as Record<
    string,
    unknown
  >;
  const kdf: Argon2idKdfConfig = {
    memorySizeKiB: Number(rawKdf.memorySizeKiB ?? rawKdf.memoryKiB ?? 16_384),
    iterations: Number(rawKdf.iterations ?? 3),
    parallelism: Number(rawKdf.parallelism ?? 2),
    saltHex: String(rawKdf.saltHex ?? '')
  };
  if (!kdf.saltHex) {
    throw new Error('Invalid .safepocket container: missing Argon2id salt');
  }

  return {
    exportedAtIso: String(parsed.exportedAtIso ?? ''),
    appVersion: String(parsed.appVersion ?? '0.1'),
    itemCount:
      typeof parsed.itemCount === 'number' ? parsed.itemCount : undefined,
    attachmentCount:
      typeof parsed.attachmentCount === 'number'
        ? parsed.attachmentCount
        : undefined,
    sha256Hex: expectedSha,
    kdf
  };
}

export function mergeVaultSnapshots(
  existing: ReadonlyArray<VaultItem>,
  incoming: ReadonlyArray<VaultItem>
): VaultMergeReport {
  const byId = new Map<string, VaultItem>();
  for (const item of existing) {
    byId.set(item.id, structuredClone(item));
  }

  let addedCount = 0;
  let updatedCount = 0;
  let unchangedCount = 0;

  for (const inc of incoming) {
    const current = byId.get(inc.id);
    if (!current) {
      byId.set(inc.id, structuredClone(inc));
      addedCount++;
    } else if (inc.updatedAtEpochMs > current.updatedAtEpochMs) {
      byId.set(inc.id, structuredClone(inc));
      updatedCount++;
    } else {
      unchangedCount++;
    }
  }

  const merged = Array.from(byId.values()).sort(
    (a, b) => b.updatedAtEpochMs - a.updatedAtEpochMs
  );
  return { merged, addedCount, updatedCount, unchangedCount };
}

export function formatUnencryptedVaultExport(
  items: ReadonlyArray<VaultItem>,
  format: 'json' | 'csv'
): { content: string; mimeType: string; extension: 'json' | 'csv' } {
  if (format === 'json') {
    const sanitized = items.map((item) => ({
      id: item.id,
      category: item.category,
      title: item.title,
      subtitle: item.subtitle,
      favorite: item.favorite,
      pinned: Boolean(item.pinned),
      tags: item.tags,
      fields: item.fields.map((f) => ({
        labelKey: f.labelKey,
        isCustomLabel: Boolean(f.isCustomLabel),
        value: f.value,
        valueType: f.valueType
      })),
      updatedAtIso: new Date(item.updatedAtEpochMs).toISOString()
    }));
    return {
      content: JSON.stringify(
        {
          exportedFrom: `SafePocket v${APP_BUILD_INFO.versionName}`,
          exportedAtIso: new Date().toISOString(),
          warning: 'UNENCRYPTED PLAINTEXT EXPORT — HANDLE WITH EXTREME CARE',
          items: sanitized
        },
        null,
        2
      ),
      mimeType: 'application/json;charset=utf-8',
      extension: 'json'
    };
  }

  const escCsv = (val: string) => `"${val.replaceAll('"', '""')}"`;
  const lines = [
    'id,category,title,subtitle,favorite,pinned,tags,fields_summary',
    ...items.map((item) => {
      const fieldsSummary = item.fields
        .map((f) => `${f.labelKey}=${f.value}`)
        .join('; ');
      return [
        escCsv(item.id),
        escCsv(item.category),
        escCsv(item.title),
        escCsv(item.subtitle),
        String(item.favorite),
        String(Boolean(item.pinned)),
        escCsv(item.tags.join('|')),
        escCsv(fieldsSummary)
      ].join(',');
    })
  ];
  return {
    content: lines.join('\n'),
    mimeType: 'text/csv;charset=utf-8',
    extension: 'csv'
  };
}
