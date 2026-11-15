import { constantTimeHexEquals, sha256Hex } from '../crypto/vaultCrypto';
import { SqliteWasmVaultDb } from '../db/sqliteWasmVaultDb';
import type { EncryptedBlobEnvelope, EncryptedVaultRow } from '../types';
import {
  assertStrictZeroKnowledgeBlobUpload,
  downloadEncryptedBlobFromFirebaseStorageRest,
  downloadEncryptedBlobFromGoogleDriveAppDataRest,
  type LiveCloudEndpointConfig,
  pullRowsFromFirestoreRest,
  pullRowsFromGoogleDriveAppDataRest,
  pushRowsToFirestoreRest,
  pushRowsToGoogleDriveAppDataRest,
  uploadEncryptedBlobToFirebaseStorageRest,
  uploadEncryptedBlobToGoogleDriveAppDataRest
} from './liveCloudConnectors';

export type CloudServiceId = 'FIREBASE' | 'GDRIVE_APPDATA';
export type CloudProviderId = 'NONE' | CloudServiceId;

const SYNC_ENABLED_STORAGE_KEY = 'safepocket.cloud_sync_enabled';
const SYNC_SERVICE_STORAGE_KEY = 'safepocket.cloud_sync_service';

export class HybridClock {
  private lastWallMs = 0;
  private counter = 0;

  constructor(private readonly nodeId: string) {}

  nowPacked(nowMs = Date.now()): string {
    if (nowMs > this.lastWallMs) {
      this.lastWallMs = nowMs;
      this.counter = 0;
    } else {
      this.counter += 1;
    }
    return `${String(this.lastWallMs).padStart(15, '0')}-${String(
      this.counter
    ).padStart(4, '0')}-${this.nodeId}`;
  }
}

export function reconcileEncryptedRows(
  local: EncryptedVaultRow,
  remote: EncryptedVaultRow
): EncryptedVaultRow {
  const allNodes = new Set([
    ...Object.keys(local.versionVector),
    ...Object.keys(remote.versionVector)
  ]);
  let localGreater = false;
  let remoteGreater = false;
  const mergedVector: Record<string, number> = {};

  for (const node of allNodes) {
    const l = local.versionVector[node] ?? 0;
    const r = remote.versionVector[node] ?? 0;
    if (l > r) localGreater = true;
    if (r > l) remoteGreater = true;
    mergedVector[node] = Math.max(l, r);
  }

  let winner: EncryptedVaultRow;
  if (localGreater && !remoteGreater) {
    winner = local;
  } else if (remoteGreater && !localGreater) {
    winner = remote;
  } else if (!localGreater && !remoteGreater) {
    winner = local;
  } else {
    // Concurrent offline mutation -> deterministic HLC Last-Write-Wins
    winner = remote.hlcPacked > local.hlcPacked ? remote : local;
  }

  return {
    ...winner,
    versionVector: mergedVector
  };
}

export interface CloudSyncProviderAdapter {
  readonly id: CloudServiceId;
  pushEncryptedDeltas(
    vaultId: string,
    rows: EncryptedVaultRow[]
  ): Promise<{ pushedCount: number; cursorHlc: string | null }>;
  pullEncryptedDeltas(
    vaultId: string,
    sinceCursorHlc: string | null
  ): Promise<EncryptedVaultRow[]>;
  uploadEncryptedBlob(
    vaultId: string,
    blob: EncryptedBlobEnvelope
  ): Promise<{ storageObjectPath: string }>;
  downloadEncryptedBlob(
    vaultId: string,
    attachmentId: string,
    expectedSha256Hex: string
  ): Promise<Uint8Array | null>;
}

export interface BidirectionalSyncSummary {
  service: CloudServiceId;
  pushedCount: number;
  pulledCount: number;
  uploadedBlobCount: number;
  uploadedBlobPaths: Record<string, string>;
  mergedRows: EncryptedVaultRow[];
  cursorHlc: string | null;
}

/**
 * Coordinates SafePocket's Storage & Cloud Sync Policy:
 * 1. Default Storage Mode: Local Encrypted Storage Only (`cloudSyncEnabled = false`).
 * 2. Default Cloud Service (when Cloud Sync is enabled): `'FIREBASE'` (pre-initialized from boot).
 * 3. Optional Cloud Service: `'GDRIVE_APPDATA'` (Google Drive hidden appDataFolder).
 */
export class VaultSyncCoordinator {
  private cloudSyncEnabled = false;
  private preferredService: CloudServiceId = 'FIREBASE';

  constructor(
    private readonly firebaseDefaultAdapter: CloudSyncProviderAdapter,
    private readonly googleDriveOptionalAdapter: CloudSyncProviderAdapter
  ) {
    if (typeof localStorage !== 'undefined') {
      const savedEnabled = localStorage.getItem(SYNC_ENABLED_STORAGE_KEY);
      if (savedEnabled !== null) {
        this.cloudSyncEnabled = savedEnabled === 'true';
      }
      const savedService = localStorage.getItem(SYNC_SERVICE_STORAGE_KEY);
      if (savedService === 'FIREBASE' || savedService === 'GDRIVE_APPDATA') {
        this.preferredService = savedService;
      }
    }
  }

  get isCloudSyncEnabled(): boolean {
    return this.cloudSyncEnabled;
  }

  get cloudService(): CloudServiceId {
    return this.preferredService;
  }

  get effectiveMode(): CloudProviderId {
    return this.cloudSyncEnabled ? this.preferredService : 'NONE';
  }

  setCloudSyncEnabled(enabled: boolean): void {
    this.cloudSyncEnabled = enabled;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(SYNC_ENABLED_STORAGE_KEY, String(enabled));
    }
  }

  setPreferredCloudService(service: CloudServiceId): void {
    this.preferredService = service;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(SYNC_SERVICE_STORAGE_KEY, service);
    }
  }

  private get activeAdapter(): CloudSyncProviderAdapter {
    return this.preferredService === 'FIREBASE'
      ? this.firebaseDefaultAdapter
      : this.googleDriveOptionalAdapter;
  }

  async syncEncryptedRowsIfEnabled(
    vaultId: string,
    rows: EncryptedVaultRow[]
  ): Promise<{ pushedCount: number; cursorHlc: string | null; service: CloudServiceId } | null> {
    if (!this.cloudSyncEnabled) {
      return null;
    }
    const adapter = this.activeAdapter;
    const res = await adapter.pushEncryptedDeltas(vaultId, rows);
    return { ...res, service: adapter.id };
  }

  async uploadAttachmentBlobsIfEnabled(
    vaultId: string,
    blobs: EncryptedBlobEnvelope[]
  ): Promise<{
    service: CloudServiceId;
    uploadedCount: number;
    paths: Record<string, string>;
  } | null> {
    if (!this.cloudSyncEnabled) {
      return null;
    }
    const adapter = this.activeAdapter;
    const paths: Record<string, string> = {};
    for (const blob of blobs) {
      const res = await adapter.uploadEncryptedBlob(vaultId, blob);
      paths[blob.attachmentId] = res.storageObjectPath;
    }
    return {
      service: adapter.id,
      uploadedCount: blobs.length,
      paths
    };
  }

  async downloadMissingAttachmentBlobIfEnabled(
    vaultId: string,
    attachmentId: string,
    expectedSha256Hex: string
  ): Promise<Uint8Array | null> {
    if (!this.cloudSyncEnabled) {
      return null;
    }
    return this.activeAdapter.downloadEncryptedBlob(
      vaultId,
      attachmentId,
      expectedSha256Hex
    );
  }

  /**
   * Full Two-Way Offline-First CRDT Sync (`Upload Pending .enc Blobs` -> `Push` local deltas -> `Pull` remote deltas -> `Reconcile`).
   * Strictly no-op (`null`) when Cloud Sync is disabled (Local Storage Default).
   */
  async syncBidirectionalIfEnabled(
    vaultId: string,
    localRows: EncryptedVaultRow[],
    sinceCursorHlc: string | null = null,
    pendingBlobs: EncryptedBlobEnvelope[] = []
  ): Promise<BidirectionalSyncSummary | null> {
    if (!this.cloudSyncEnabled) {
      return null;
    }
    const adapter = this.activeAdapter;
    const uploadedBlobPaths: Record<string, string> = {};
    for (const blob of pendingBlobs) {
      const uploaded = await adapter.uploadEncryptedBlob(vaultId, blob);
      uploadedBlobPaths[blob.attachmentId] = uploaded.storageObjectPath;
    }

    const pushRes = await adapter.pushEncryptedDeltas(vaultId, localRows);
    const remoteDeltas = await adapter.pullEncryptedDeltas(
      vaultId,
      sinceCursorHlc
    );

    const byId = new Map<string, EncryptedVaultRow>();
    for (const l of localRows) {
      byId.set(l.id, structuredClone(l));
    }
    for (const r of remoteDeltas) {
      const existing = byId.get(r.id);
      byId.set(r.id, existing ? reconcileEncryptedRows(existing, r) : r);
    }

    const mergedRows = Array.from(byId.values());
    const maxCursor = mergedRows.reduce<string | null>(
      (acc, row) => (!acc || row.hlcPacked > acc ? row.hlcPacked : acc),
      pushRes.cursorHlc
    );

    return {
      service: adapter.id,
      pushedCount: pushRes.pushedCount,
      pulledCount: remoteDeltas.length,
      uploadedBlobCount: pendingBlobs.length,
      uploadedBlobPaths,
      mergedRows,
      cursorHlc: maxCursor
    };
  }
}

/**
 * Default Cloud Service: Zero-Knowledge Firebase (Firestore Encrypted Payloads + EU Cloud Storage `.enc` Blobs).
 * Supports both persistent SQLite WASM Firebase replica (hermetic offline/tests) and live REST endpoints.
 */
export class FirebaseZeroKnowledgeAdapter implements CloudSyncProviderAdapter {
  readonly id: CloudServiceId = 'FIREBASE';
  private simulatedCloudStore = new Map<string, EncryptedVaultRow>();
  private simulatedBlobStore = new Map<
    string,
    { encryptedBytes: Uint8Array; sha256Hex: string }
  >();
  private liveConfig: LiveCloudEndpointConfig = {};

  constructor(
    private readonly sqliteReplica?: SqliteWasmVaultDb,
    private readonly fetcher: typeof fetch = fetch
  ) {}

  setLiveEndpointConfig(config: LiveCloudEndpointConfig): void {
    this.liveConfig = { ...config };
  }

  get liveEndpointConfig(): LiveCloudEndpointConfig {
    return { ...this.liveConfig };
  }

  async pushEncryptedDeltas(
    vaultId: string,
    rows: EncryptedVaultRow[]
  ): Promise<{ pushedCount: number; cursorHlc: string | null }> {
    let maxHlc: string | null = null;
    const uid = this.liveConfig.firebaseUserId || 'local-default-uid';

    for (const row of rows) {
      if (!row.ciphertextHex || !row.ivHex) {
        throw new Error('Zero-knowledge violation: unencrypted sync payload rejected');
      }
      const existing = this.simulatedCloudStore.get(`${uid}:${row.id}`);
      const reconciled = existing
        ? reconcileEncryptedRows(existing, row)
        : structuredClone(row);
      this.simulatedCloudStore.set(`${uid}:${row.id}`, reconciled);

      if (this.sqliteReplica) {
        await this.sqliteReplica.upsertLocalFirestoreDelta(
          uid,
          vaultId,
          reconciled
        );
      }
      if (!maxHlc || row.hlcPacked > maxHlc) maxHlc = row.hlcPacked;
    }

    if (this.liveConfig.firebaseProjectId && this.liveConfig.bearerToken) {
      return pushRowsToFirestoreRest(
        vaultId,
        rows,
        {
          firebaseProjectId: this.liveConfig.firebaseProjectId,
          bearerToken: this.liveConfig.bearerToken,
          firebaseUserId: this.liveConfig.firebaseUserId
        },
        this.fetcher
      );
    }
    return { pushedCount: rows.length, cursorHlc: maxHlc };
  }

  async pullEncryptedDeltas(
    vaultId: string,
    sinceCursorHlc: string | null
  ): Promise<EncryptedVaultRow[]> {
    if (this.liveConfig.firebaseProjectId && this.liveConfig.bearerToken) {
      return pullRowsFromFirestoreRest(
        vaultId,
        sinceCursorHlc,
        {
          firebaseProjectId: this.liveConfig.firebaseProjectId,
          bearerToken: this.liveConfig.bearerToken,
          firebaseUserId: this.liveConfig.firebaseUserId
        },
        this.fetcher
      );
    }

    const uid = this.liveConfig.firebaseUserId || 'local-default-uid';
    if (this.sqliteReplica) {
      return this.sqliteReplica.queryLocalFirestoreDeltas(
        uid,
        vaultId,
        sinceCursorHlc
      );
    }

    return Array.from(this.simulatedCloudStore.entries())
      .filter(([key]) => key.startsWith(`${uid}:`))
      .map(([, val]) => val)
      .filter((r) => !sinceCursorHlc || r.hlcPacked > sinceCursorHlc);
  }

  async uploadEncryptedBlob(
    vaultId: string,
    blob: EncryptedBlobEnvelope
  ): Promise<{ storageObjectPath: string }> {
    await assertStrictZeroKnowledgeBlobUpload(blob);
    const uid = this.liveConfig.firebaseUserId || 'local-default-uid';
    const objectPath = `users/${uid}/vaults/${vaultId}/blobs/${blob.attachmentId}.enc`;

    if (
      this.liveConfig.firebaseProjectId &&
      this.liveConfig.firebaseStorageBucket &&
      this.liveConfig.bearerToken
    ) {
      return uploadEncryptedBlobToFirebaseStorageRest(
        vaultId,
        blob,
        {
          firebaseStorageBucket: this.liveConfig.firebaseStorageBucket,
          bearerToken: this.liveConfig.bearerToken,
          firebaseUserId: uid
        },
        this.fetcher
      );
    }

    this.simulatedBlobStore.set(`${uid}:${vaultId}:${blob.attachmentId}`, {
      encryptedBytes: blob.encryptedBytes.slice(),
      sha256Hex: blob.sha256Hex.toLowerCase()
    });
    if (this.sqliteReplica) {
      await this.sqliteReplica.upsertLocalStorageReplicaBlob(
        uid,
        vaultId,
        blob.attachmentId,
        blob.encryptedBytes,
        blob.sha256Hex
      );
    }
    return { storageObjectPath: objectPath };
  }

  async downloadEncryptedBlob(
    vaultId: string,
    attachmentId: string,
    expectedSha256Hex: string
  ): Promise<Uint8Array | null> {
    const uid = this.liveConfig.firebaseUserId || 'local-default-uid';

    if (
      this.liveConfig.firebaseProjectId &&
      this.liveConfig.firebaseStorageBucket &&
      this.liveConfig.bearerToken
    ) {
      return downloadEncryptedBlobFromFirebaseStorageRest(
        vaultId,
        attachmentId,
        expectedSha256Hex,
        {
          firebaseStorageBucket: this.liveConfig.firebaseStorageBucket,
          bearerToken: this.liveConfig.bearerToken,
          firebaseUserId: uid
        },
        this.fetcher
      );
    }

    let candidate: { encryptedBytes: Uint8Array; sha256Hex: string } | null =
      null;
    if (this.sqliteReplica) {
      const dbHit = await this.sqliteReplica.getLocalStorageReplicaBlob(
        uid,
        vaultId,
        attachmentId
      );
      if (dbHit) {
        candidate = {
          encryptedBytes: dbHit.encryptedBytes,
          sha256Hex: dbHit.ciphertextSha256
        };
      }
    }
    if (!candidate) {
      candidate =
        this.simulatedBlobStore.get(`${uid}:${vaultId}:${attachmentId}`) ??
        null;
    }
    if (!candidate) return null;

    const actualSha = await sha256Hex(candidate.encryptedBytes);
    if (!constantTimeHexEquals(actualSha, expectedSha256Hex)) {
      throw new Error(
        'Firebase Storage replica SHA-256 integrity check failed'
      );
    }
    return candidate.encryptedBytes.slice();
  }
}

/**
 * Optional Cloud Service: Zero-Knowledge Google Drive Adapter (Hidden `spaces=appDataFolder` Delta Log & `.enc` Blobs)
 */
export class GoogleDriveAppDataAdapter implements CloudSyncProviderAdapter {
  readonly id: CloudServiceId = 'GDRIVE_APPDATA';
  private appDataDeltaLog: EncryptedVaultRow[] = [];
  private appDataBlobStore = new Map<string, Uint8Array>();
  private liveConfig: LiveCloudEndpointConfig = {};

  constructor(private readonly fetcher: typeof fetch = fetch) {}

  setLiveEndpointConfig(config: LiveCloudEndpointConfig): void {
    this.liveConfig = { ...config };
  }

  get liveEndpointConfig(): LiveCloudEndpointConfig {
    return { ...this.liveConfig };
  }

  async pushEncryptedDeltas(
    vaultId: string,
    rows: EncryptedVaultRow[]
  ): Promise<{ pushedCount: number; cursorHlc: string | null }> {
    let maxHlc: string | null = null;
    for (const row of rows) {
      if (!row.ciphertextHex || !row.ivHex) {
        throw new Error('Zero-knowledge violation: unencrypted Drive appData payload rejected');
      }
      this.appDataDeltaLog.push(structuredClone(row));
      if (!maxHlc || row.hlcPacked > maxHlc) maxHlc = row.hlcPacked;
    }
    if (this.liveConfig.bearerToken) {
      return pushRowsToGoogleDriveAppDataRest(
        vaultId,
        rows,
        this.liveConfig.bearerToken,
        this.fetcher
      );
    }
    return { pushedCount: rows.length, cursorHlc: maxHlc };
  }

  async pullEncryptedDeltas(
    vaultId: string,
    sinceCursorHlc: string | null
  ): Promise<EncryptedVaultRow[]> {
    if (this.liveConfig.bearerToken) {
      return pullRowsFromGoogleDriveAppDataRest(
        vaultId,
        sinceCursorHlc,
        this.liveConfig.bearerToken,
        this.fetcher
      );
    }
    return this.appDataDeltaLog.filter(
      (r) => !sinceCursorHlc || r.hlcPacked > sinceCursorHlc
    );
  }

  async uploadEncryptedBlob(
    vaultId: string,
    blob: EncryptedBlobEnvelope
  ): Promise<{ storageObjectPath: string }> {
    await assertStrictZeroKnowledgeBlobUpload(blob);
    if (this.liveConfig.bearerToken) {
      return uploadEncryptedBlobToGoogleDriveAppDataRest(
        vaultId,
        blob,
        this.liveConfig.bearerToken,
        this.fetcher
      );
    }
    this.appDataBlobStore.set(
      `${vaultId}:${blob.attachmentId}`,
      blob.encryptedBytes.slice()
    );
    return {
      storageObjectPath: `appDataFolder/sp-blob-${vaultId}-${blob.attachmentId}.enc`
    };
  }

  async downloadEncryptedBlob(
    vaultId: string,
    attachmentId: string,
    expectedSha256Hex: string
  ): Promise<Uint8Array | null> {
    if (this.liveConfig.bearerToken) {
      return downloadEncryptedBlobFromGoogleDriveAppDataRest(
        vaultId,
        attachmentId,
        expectedSha256Hex,
        this.liveConfig.bearerToken,
        this.fetcher
      );
    }
    const bytes = this.appDataBlobStore.get(`${vaultId}:${attachmentId}`);
    if (!bytes) return null;
    const actualSha = await sha256Hex(bytes);
    if (!constantTimeHexEquals(actualSha, expectedSha256Hex)) {
      throw new Error(
        'Google Drive appDataFolder .enc SHA-256 integrity check failed'
      );
    }
    return bytes.slice();
  }
}

/**
 * Encrypted Local Vault Store backed by SQLite WASM + OPFS (`SqliteWasmVaultDb`).
 * Persists ONLY AES-256-GCM encrypted rows (`EncryptedVaultRow`).
 */
export class EncryptedLocalVaultDb {
  readonly sqliteEngine: SqliteWasmVaultDb;

  constructor(sqliteEngine?: SqliteWasmVaultDb) {
    this.sqliteEngine = sqliteEngine ?? new SqliteWasmVaultDb(true);
  }

  async saveEncryptedRow(row: EncryptedVaultRow): Promise<void> {
    await this.sqliteEngine.saveEncryptedRow(row);
  }

  async loadAllRowsIncludingTombstones(): Promise<EncryptedVaultRow[]> {
    return this.sqliteEngine.loadAllRowsIncludingTombstones();
  }

  async loadAllEncryptedRows(): Promise<EncryptedVaultRow[]> {
    return this.sqliteEngine.loadAllEncryptedRows();
  }

  async clearAll(): Promise<void> {
    await this.sqliteEngine.clearAll();
  }
}
