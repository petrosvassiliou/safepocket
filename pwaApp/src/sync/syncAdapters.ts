import { constantTimeHexEquals, sha256Hex } from '../crypto/vaultCrypto';
import { SqliteWasmVaultDb } from '../db/sqliteWasmVaultDb';
import type {
  EncryptedBlobEnvelope,
  EncryptedVaultRow,
  VaultStorageMode
} from '../types';
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

export type SyncIndicatorPhase =
  | 'LOCAL_ENCRYPTED'
  | 'CLOUD_SYNCED'
  | 'SYNCING'
  | 'CLOUD_OFFLINE_READY'
  | 'SYNC_ATTENTION';

export interface SyncIndicatorSnapshot {
  storageMode: VaultStorageMode;
  phase: SyncIndicatorPhase;
  isOnline: boolean;
  queuedMutationsCount: number;
  lastSyncedIso: string | null;
  lastError: string | null;
}

const SYNC_ENABLED_STORAGE_KEY = 'safepocket.cloud_sync_enabled';
const STORAGE_MODE_KEY = 'safepocket.storage_mode_v03';
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
 * Coordinates SafePocket's v0.3 Mutually Exclusive Storage & Cloud Sync Policy:
 * - `LOCAL_ONLY` (Default): Device-only encrypted SQLite WASM + OPFS storage; zero cloud traffic.
 * - `CLOUD_SYNC` (Exclusive): Standalone local vault DB (`vault_item`) is purged; Cloud is authoritative
 *   with a session-scoped encrypted offline mirror + mutation outbox that reconciles 100% automatically
 *   on network restoration with zero user input.
 */
export class VaultSyncCoordinator {
  private cloudSyncEnabled = false;
  private preferredService: CloudServiceId = 'FIREBASE';
  private onlineState =
    typeof navigator === 'undefined' ? true : navigator.onLine !== false;
  private syncingInFlight = false;
  private queuedOutboxCount = 0;
  private lastSyncedIso: string | null = null;
  private lastSyncError: string | null = null;
  private statusListeners = new Set<(snap: SyncIndicatorSnapshot) => void>();

  constructor(
    private readonly firebaseDefaultAdapter: CloudSyncProviderAdapter,
    private readonly googleDriveOptionalAdapter: CloudSyncProviderAdapter
  ) {
    if (typeof localStorage !== 'undefined') {
      const savedMode = localStorage.getItem(STORAGE_MODE_KEY);
      if (savedMode === 'CLOUD_SYNC' || savedMode === 'LOCAL_ONLY') {
        this.cloudSyncEnabled = savedMode === 'CLOUD_SYNC';
      } else {
        const savedEnabled = localStorage.getItem(SYNC_ENABLED_STORAGE_KEY);
        if (savedEnabled !== null) {
          this.cloudSyncEnabled = savedEnabled === 'true';
        }
      }
      const savedService = localStorage.getItem(SYNC_SERVICE_STORAGE_KEY);
      if (savedService === 'FIREBASE' || savedService === 'GDRIVE_APPDATA') {
        this.preferredService = savedService;
      }
    }
  }

  get storageMode(): VaultStorageMode {
    return this.cloudSyncEnabled ? 'CLOUD_SYNC' : 'LOCAL_ONLY';
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

  get indicatorSnapshot(): SyncIndicatorSnapshot {
    let phase: SyncIndicatorPhase = 'LOCAL_ENCRYPTED';
    if (this.cloudSyncEnabled) {
      if (this.syncingInFlight) {
        phase = 'SYNCING';
      } else if (!this.onlineState) {
        phase = 'CLOUD_OFFLINE_READY';
      } else if (this.lastSyncError) {
        phase = 'SYNC_ATTENTION';
      } else {
        phase = 'CLOUD_SYNCED';
      }
    }
    return {
      storageMode: this.storageMode,
      phase,
      isOnline: this.onlineState,
      queuedMutationsCount: this.queuedOutboxCount,
      lastSyncedIso: this.lastSyncedIso,
      lastError: this.lastSyncError
    };
  }

  subscribeIndicator(
    listener: (snap: SyncIndicatorSnapshot) => void
  ): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  private emitIndicator(): void {
    const snap = this.indicatorSnapshot;
    for (const listener of this.statusListeners) {
      listener(snap);
    }
  }

  setOnlineState(isOnline: boolean): void {
    this.onlineState = isOnline;
    if (isOnline && this.lastSyncError) {
      this.lastSyncError = null;
    }
    this.emitIndicator();
  }

  updateRuntimeSyncTelemetry(params: {
    syncing?: boolean;
    queuedMutationsCount?: number;
    lastSyncedIso?: string | null;
    lastError?: string | null;
  }): void {
    if (params.syncing !== undefined) this.syncingInFlight = params.syncing;
    if (params.queuedMutationsCount !== undefined) {
      this.queuedOutboxCount = params.queuedMutationsCount;
    }
    if (params.lastSyncedIso !== undefined) {
      this.lastSyncedIso = params.lastSyncedIso;
    }
    if (params.lastError !== undefined) {
      this.lastSyncError = params.lastError;
    }
    this.emitIndicator();
  }

  setStorageMode(mode: VaultStorageMode): void {
    this.setCloudSyncEnabled(mode === 'CLOUD_SYNC');
  }

  setCloudSyncEnabled(enabled: boolean): void {
    this.cloudSyncEnabled = enabled;
    if (!enabled) {
      this.syncingInFlight = false;
      this.queuedOutboxCount = 0;
      this.lastSyncError = null;
    }
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(SYNC_ENABLED_STORAGE_KEY, String(enabled));
      localStorage.setItem(
        STORAGE_MODE_KEY,
        enabled ? 'CLOUD_SYNC' : 'LOCAL_ONLY'
      );
    }
    this.emitIndicator();
  }

  setPreferredCloudService(service: CloudServiceId): void {
    this.preferredService = service;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(SYNC_SERVICE_STORAGE_KEY, service);
    }
    this.emitIndicator();
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
  private refreshTokenCallback?: () => Promise<string | undefined>;

  constructor(
    private readonly sqliteReplica?: SqliteWasmVaultDb,
    private readonly fetcher: typeof fetch = fetch
  ) {}

  setRefreshTokenCallback(cb?: () => Promise<string | undefined>): void {
    this.refreshTokenCallback = cb;
  }

  setLiveEndpointConfig(config: LiveCloudEndpointConfig): void {
    this.liveConfig = { ...config };
  }

  get liveEndpointConfig(): LiveCloudEndpointConfig {
    return { ...this.liveConfig };
  }

  private async executeWith401Retry<T>(
    op: (bearerToken: string) => Promise<T>
  ): Promise<T> {
    const initialToken = this.liveConfig.bearerToken || '';
    try {
      return await op(initialToken);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      if (msg.includes('HTTP 401') && this.refreshTokenCallback) {
        const refreshed = await this.refreshTokenCallback();
        if (refreshed && refreshed !== initialToken) {
          this.liveConfig = { ...this.liveConfig, bearerToken: refreshed };
          return op(refreshed);
        }
      }
      throw err;
    }
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
      const projectId = this.liveConfig.firebaseProjectId;
      return this.executeWith401Retry((bearerToken) =>
        pushRowsToFirestoreRest(
          vaultId,
          rows,
          {
            firebaseProjectId: projectId,
            bearerToken,
            firebaseUserId: this.liveConfig.firebaseUserId
          },
          this.fetcher
        )
      );
    }
    return { pushedCount: rows.length, cursorHlc: maxHlc };
  }

  async pullEncryptedDeltas(
    vaultId: string,
    sinceCursorHlc: string | null
  ): Promise<EncryptedVaultRow[]> {
    if (this.liveConfig.firebaseProjectId && this.liveConfig.bearerToken) {
      const projectId = this.liveConfig.firebaseProjectId;
      return this.executeWith401Retry((bearerToken) =>
        pullRowsFromFirestoreRest(
          vaultId,
          sinceCursorHlc,
          {
            firebaseProjectId: projectId,
            bearerToken,
            firebaseUserId: this.liveConfig.firebaseUserId
          },
          this.fetcher
        )
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
      const bucket = this.liveConfig.firebaseStorageBucket;
      return this.executeWith401Retry((bearerToken) =>
        uploadEncryptedBlobToFirebaseStorageRest(
          vaultId,
          blob,
          {
            firebaseStorageBucket: bucket,
            bearerToken,
            firebaseUserId: uid
          },
          this.fetcher
        )
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
      const bucket = this.liveConfig.firebaseStorageBucket;
      return this.executeWith401Retry((bearerToken) =>
        downloadEncryptedBlobFromFirebaseStorageRest(
          vaultId,
          attachmentId,
          expectedSha256Hex,
          {
            firebaseStorageBucket: bucket,
            bearerToken,
            firebaseUserId: uid
          },
          this.fetcher
        )
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
 * Encrypted Vault Store backed by SQLite WASM + OPFS (`SqliteWasmVaultDb`).
 *
 * Enforces SafePocket v0.3 Mutually Exclusive Storage Mode (`LOCAL_ONLY` XOR `CLOUD_SYNC`):
 * - In `LOCAL_ONLY`: persists AES-256-GCM rows strictly to `vault_item`; `cloud_offline_mirror` & `cloud_mutation_outbox` are empty.
 * - In `CLOUD_SYNC`: `vault_item` (standalone local DB) is purged; writes go to the session-scoped
 *   `cloud_offline_mirror` + `cloud_mutation_outbox` and sync immediately when online or automatically
 *   upon network reconnection with zero user input.
 */
export class EncryptedLocalVaultDb {
  readonly sqliteEngine: SqliteWasmVaultDb;
  private boundCoordinator: VaultSyncCoordinator | null = null;

  constructor(
    sqliteEngine?: SqliteWasmVaultDb,
    coordinator?: VaultSyncCoordinator
  ) {
    this.sqliteEngine = sqliteEngine ?? new SqliteWasmVaultDb(true);
    this.boundCoordinator = coordinator ?? null;
  }

  bindSyncCoordinator(coordinator: VaultSyncCoordinator): void {
    this.boundCoordinator = coordinator;
  }

  get activeStorageMode(): VaultStorageMode {
    return this.boundCoordinator?.storageMode ?? 'LOCAL_ONLY';
  }

  async saveEncryptedRow(row: EncryptedVaultRow): Promise<void> {
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      await this.sqliteEngine.saveCloudMirrorRow(row);
      await this.sqliteEngine.enqueueCloudMutationOutboxRow(row);
      const pending = await this.sqliteEngine.listCloudMutationOutboxRows();
      this.boundCoordinator?.updateRuntimeSyncTelemetry({
        queuedMutationsCount: pending.length
      });
      return;
    }
    await this.sqliteEngine.saveEncryptedRow(row);
  }

  async saveMergedCloudRowToMirrorOnly(row: EncryptedVaultRow): Promise<void> {
    await this.sqliteEngine.saveCloudMirrorRow(row);
  }

  async loadAllRowsIncludingTombstones(): Promise<EncryptedVaultRow[]> {
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      return this.sqliteEngine.loadAllCloudMirrorRowsIncludingTombstones();
    }
    return this.sqliteEngine.loadAllRowsIncludingTombstones();
  }

  async loadAllEncryptedRows(): Promise<EncryptedVaultRow[]> {
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      return this.sqliteEngine.loadActiveCloudMirrorRows();
    }
    return this.sqliteEngine.loadAllEncryptedRows();
  }

  async clearAll(): Promise<void> {
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      await this.sqliteEngine.purgeCloudSessionMirrorAndOutbox();
      this.boundCoordinator?.updateRuntimeSyncTelemetry({
        queuedMutationsCount: 0
      });
      return;
    }
    await this.sqliteEngine.clearAll();
  }

  /**
   * Transitions exclusively between `LOCAL_ONLY` and `CLOUD_SYNC` (never both simultaneously).
   */
  async switchExclusiveStorageMode(
    targetMode: VaultStorageMode,
    activeSnapshotRows: EncryptedVaultRow[],
    vaultId = 'default-vault',
    providerKey = 'FIREBASE:local-default-uid'
  ): Promise<BidirectionalSyncSummary | null> {
    const coord = this.boundCoordinator;
    if (!coord) {
      throw new Error('Sync coordinator not bound to EncryptedLocalVaultDb');
    }

    if (targetMode === 'CLOUD_SYNC') {
      // 1. Seed session-scoped cloud mirror & outbox from current unlocked snapshot
      for (const row of activeSnapshotRows) {
        await this.sqliteEngine.saveCloudMirrorRow(row);
        await this.sqliteEngine.enqueueCloudMutationOutboxRow(row);
      }
      // 2. Purge standalone local vault (`vault_item`) so Local DB is strictly zeroed out
      await this.sqliteEngine.purgeStandaloneLocalVault();
      coord.setStorageMode('CLOUD_SYNC');
      const outbox = await this.sqliteEngine.listCloudMutationOutboxRows();
      coord.updateRuntimeSyncTelemetry({ queuedMutationsCount: outbox.length });

      // 3. Immediately reconcile if online; if offline, automatic outbox holds items seamlessly
      if (coord.indicatorSnapshot.isOnline) {
        return this.reconcileCloudSessionAutomatically(vaultId, providerKey);
      }
      return null;
    }

    // Switching `CLOUD_SYNC` -> `LOCAL_ONLY`:
    // 1. Persist active snapshot to standalone `vault_item`
    await this.sqliteEngine.purgeStandaloneLocalVault();
    for (const row of activeSnapshotRows) {
      await this.sqliteEngine.saveEncryptedRow(row);
    }
    // 2. Immediately wipe session-scoped cloud offline mirror, outbox & cursors
    await this.sqliteEngine.purgeCloudSessionMirrorAndOutbox();
    coord.setStorageMode('LOCAL_ONLY');
    return null;
  }

  /**
   * Automatically flushes queued offline mutations and pulls remote CRDT deltas
   * with zero user input whenever Cloud Sync is active and connectivity is available.
   */
  async reconcileCloudSessionAutomatically(
    vaultId = 'default-vault',
    providerKey = 'FIREBASE:local-default-uid'
  ): Promise<BidirectionalSyncSummary | null> {
    const coord = this.boundCoordinator;
    if (!coord || coord.storageMode !== 'CLOUD_SYNC') {
      return null;
    }
    if (!coord.indicatorSnapshot.isOnline) {
      const queued = await this.sqliteEngine.listCloudMutationOutboxRows();
      coord.updateRuntimeSyncTelemetry({
        syncing: false,
        queuedMutationsCount: queued.length
      });
      return null;
    }

    coord.updateRuntimeSyncTelemetry({ syncing: true, lastError: null });
    try {
      const outbox = await this.sqliteEngine.listCloudMutationOutboxRows();
      const mirror =
        await this.sqliteEngine.loadAllCloudMirrorRowsIncludingTombstones();
      const byId = new Map<string, EncryptedVaultRow>();
      for (const m of mirror) byId.set(m.id, m);
      for (const o of outbox) byId.set(o.id, o);

      const sinceCursor = await this.sqliteEngine.getSyncCursor(providerKey);
      const pendingBlobs =
        await this.sqliteEngine.listPendingUnsyncedBlobs(vaultId);

      const res = await coord.syncBidirectionalIfEnabled(
        vaultId,
        Array.from(byId.values()),
        sinceCursor,
        pendingBlobs
      );
      if (!res) {
        coord.updateRuntimeSyncTelemetry({ syncing: false });
        return null;
      }

      for (const uploadedId of Object.keys(res.uploadedBlobPaths)) {
        await this.sqliteEngine.markLocalBlobSynced(uploadedId);
      }
      for (const merged of res.mergedRows) {
        await this.sqliteEngine.saveCloudMirrorRow(merged);
      }
      if (outbox.length > 0) {
        await this.sqliteEngine.removeCloudMutationOutboxRows(
          outbox.map((r) => r.id)
        );
      }
      await this.sqliteEngine.setSyncCursor(providerKey, res.cursorHlc);

      const remaining = await this.sqliteEngine.listCloudMutationOutboxRows();
      coord.updateRuntimeSyncTelemetry({
        syncing: false,
        queuedMutationsCount: remaining.length,
        lastSyncedIso: new Date().toISOString(),
        lastError: null
      });
      return res;
    } catch (err) {
      const remaining = await this.sqliteEngine.listCloudMutationOutboxRows();
      coord.updateRuntimeSyncTelemetry({
        syncing: false,
        queuedMutationsCount: remaining.length,
        lastError:
          err instanceof Error ? err.message : 'Cloud sync reconciliation delayed'
      });
      throw err;
    }
  }
}
