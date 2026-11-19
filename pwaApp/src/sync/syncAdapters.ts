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

export function normalizeHlcPacked(raw: string): string {
  const trimmed = raw.trim();
  if (trimmed.length >= 21 && trimmed[15] === '-' && trimmed[20] === '-') {
    const wall = trimmed.slice(0, 15);
    const cnt = trimmed.slice(16, 20);
    if (/^\d{15}$/.test(wall) && /^\d{4}$/.test(cnt)) {
      return `${wall}:${cnt}:${trimmed.slice(21)}`;
    }
  }
  return trimmed;
}

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
    return `${String(this.lastWallMs).padStart(15, '0')}:${String(
      this.counter
    ).padStart(4, '0')}:${this.nodeId}`;
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

  const normLocalHlc = normalizeHlcPacked(local.hlcPacked);
  const normRemoteHlc = normalizeHlcPacked(remote.hlcPacked);

  let winner: EncryptedVaultRow;
  if (localGreater && !remoteGreater) {
    winner = local;
  } else if (remoteGreater && !localGreater) {
    winner = remote;
  } else {
    // Equal or concurrent offline mutation -> deterministic HLC Last-Write-Wins
    winner = normRemoteHlc > normLocalHlc ? remote : local;
  }

  return {
    ...winner,
    hlcPacked: normalizeHlcPacked(winner.hlcPacked),
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
   * Full Two-Way Offline-First CRDT Sync (`Upload Pending .enc Blobs` -> `Pull` remote deltas -> `Reconcile` -> `Push` only winning local deltas).
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

    // 1. PULL FIRST so remote edits are never overwritten by stale local snapshots
    const remoteDeltas = await adapter.pullEncryptedDeltas(
      vaultId,
      sinceCursorHlc
    );

    const localById = new Map<string, EncryptedVaultRow>();
    for (const rawLocal of localRows) {
      const l: EncryptedVaultRow = {
        ...structuredClone(rawLocal),
        hlcPacked: normalizeHlcPacked(rawLocal.hlcPacked)
      };
      const prev = localById.get(l.id);
      localById.set(l.id, prev ? reconcileEncryptedRows(prev, l) : l);
    }

    const remoteById = new Map<string, EncryptedVaultRow>();
    for (const rawRemote of remoteDeltas) {
      const r: EncryptedVaultRow = {
        ...structuredClone(rawRemote),
        hlcPacked: normalizeHlcPacked(rawRemote.hlcPacked)
      };
      const prev = remoteById.get(r.id);
      remoteById.set(r.id, prev ? reconcileEncryptedRows(prev, r) : r);
    }

    // 2. CRDT Reconcile & collect ONLY local rows that are absent on remote or strictly beat remote
    const mergedById = new Map<string, EncryptedVaultRow>();
    const rowsToPush: EncryptedVaultRow[] = [];
    const allIds = new Set([...localById.keys(), ...remoteById.keys()]);

    for (const id of allIds) {
      const l = localById.get(id);
      const r = remoteById.get(id);
      if (l && !r) {
        mergedById.set(id, l);
        rowsToPush.push(l);
      } else if (!l && r) {
        mergedById.set(id, r);
      } else if (l && r) {
        const winner = reconcileEncryptedRows(l, r);
        mergedById.set(id, winner);

        const nodes = new Set([
          ...Object.keys(l.versionVector),
          ...Object.keys(r.versionVector)
        ]);
        let localGreater = false;
        let remoteGreater = false;
        for (const node of nodes) {
          const lv = l.versionVector[node] ?? 0;
          const rv = r.versionVector[node] ?? 0;
          if (lv > rv) localGreater = true;
          if (rv > lv) remoteGreater = true;
        }
        const localDominates = localGreater && !remoteGreater;
        const remoteDominates = remoteGreater && !localGreater;
        const normL = normalizeHlcPacked(l.hlcPacked);
        const normR = normalizeHlcPacked(r.hlcPacked);
        const localWon =
          localDominates ||
          (!remoteDominates &&
            (normL > normR ||
              (normL === normR && l.isTombstone && !r.isTombstone)));
        if (localWon) {
          rowsToPush.push(winner);
        }
      }
    }

    // 3. PUSH ONLY WINNING LOCAL DELTAS (0 writes when already in sync!)
    const pushRes =
      rowsToPush.length > 0
        ? await adapter.pushEncryptedDeltas(vaultId, rowsToPush)
        : { pushedCount: 0, cursorHlc: null };

    const mergedRows = Array.from(mergedById.values());
    const maxCursor = mergedRows.reduce<string | null>(
      (acc, row) => (!acc || row.hlcPacked > acc ? row.hlcPacked : acc),
      pushRes.cursorHlc ? normalizeHlcPacked(pushRes.cursorHlc) : null
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
    if (
      this.liveConfig.firebaseProjectId &&
      this.liveConfig.firebaseProjectId !== 'safepocket-local-zk' &&
      !this.liveConfig.bearerToken
    ) {
      throw new Error(
        'Cloud Sync authentication required — please sign in in Settings.'
      );
    }

    let maxHlc: string | null = null;
    const uid = this.liveConfig.firebaseUserId || 'local-default-uid';

    for (const row of rows) {
      if (!row.ciphertextHex || !row.ivHex) {
        throw new Error('Zero-knowledge violation: unencrypted sync payload rejected');
      }
      const normalizedRow: EncryptedVaultRow = {
        ...structuredClone(row),
        hlcPacked: normalizeHlcPacked(row.hlcPacked)
      };
      const existing = this.simulatedCloudStore.get(`${uid}:${normalizedRow.id}`);
      const reconciled = existing
        ? reconcileEncryptedRows(existing, normalizedRow)
        : normalizedRow;
      this.simulatedCloudStore.set(`${uid}:${normalizedRow.id}`, reconciled);

      if (this.sqliteReplica) {
        await this.sqliteReplica.upsertLocalFirestoreDelta(
          uid,
          vaultId,
          reconciled
        );
      }
      if (!maxHlc || normalizedRow.hlcPacked > maxHlc) {
        maxHlc = normalizedRow.hlcPacked;
      }
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
    if (
      this.liveConfig.firebaseProjectId &&
      this.liveConfig.firebaseProjectId !== 'safepocket-local-zk' &&
      !this.liveConfig.bearerToken
    ) {
      throw new Error(
        'Cloud Sync authentication required — please sign in in Settings.'
      );
    }

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

export interface CloudCipherBridge {
  isActive(): boolean;
  toCloudRow(localRow: EncryptedVaultRow): Promise<EncryptedVaultRow | null>;
  fromCloudRow(cloudRow: EncryptedVaultRow): Promise<{
    localRow: EncryptedVaultRow;
    reencryptForCloud?: EncryptedVaultRow;
  } | null>;
}

/**
 * Encrypted Vault Store backed by SQLite WASM + OPFS (`SqliteWasmVaultDb`).
 *
 * SafePocket v0.4.0 Local-First + Cross-Platform Zero-Knowledge Cloud Sync v2 Architecture:
 * - `LOCAL_ONLY` (Lokale DB): 100% offline on-device encrypted SQLite (`vault_item`); zero cloud traffic.
 * - `CLOUD_SYNC` (Cloud-Sync): Local encrypted SQLite (`vault_item`) + real-time zero-knowledge cloud sync
 *   across Android, macOS, and ChromeOS/Web PWA via `cloud_offline_mirror` + `cloud_mutation_outbox` + `CloudCipherBridge`.
 */
export class EncryptedLocalVaultDb {
  readonly sqliteEngine: SqliteWasmVaultDb;
  private boundCoordinator: VaultSyncCoordinator | null = null;
  private cloudCipherBridge: CloudCipherBridge | null = null;

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

  bindCloudCipherBridge(bridge: CloudCipherBridge | null): void {
    this.cloudCipherBridge = bridge;
  }

  get activeStorageMode(): VaultStorageMode {
    return this.boundCoordinator?.storageMode ?? 'LOCAL_ONLY';
  }

  async saveEncryptedRow(row: EncryptedVaultRow): Promise<void> {
    const normalized: EncryptedVaultRow = {
      ...row,
      hlcPacked: normalizeHlcPacked(row.hlcPacked)
    };
    await this.sqliteEngine.saveEncryptedRow(normalized);
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      await this.sqliteEngine.saveCloudMirrorRow(normalized);
      await this.sqliteEngine.enqueueCloudMutationOutboxRow(normalized);
      const pending = await this.sqliteEngine.listCloudMutationOutboxRows();
      this.boundCoordinator?.updateRuntimeSyncTelemetry({
        queuedMutationsCount: pending.length
      });
    }
  }

  async saveMergedCloudRowToMirrorOnly(row: EncryptedVaultRow): Promise<void> {
    const normalized: EncryptedVaultRow = {
      ...row,
      hlcPacked: normalizeHlcPacked(row.hlcPacked)
    };
    await this.sqliteEngine.saveEncryptedRow(normalized);
    await this.sqliteEngine.saveCloudMirrorRow(normalized);
  }

  async loadAllRowsIncludingTombstones(): Promise<EncryptedVaultRow[]> {
    const localRows = await this.sqliteEngine.loadAllRowsIncludingTombstones();
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      const mirrorRows =
        await this.sqliteEngine.loadAllCloudMirrorRowsIncludingTombstones();
      if (mirrorRows.length === 0) {
        return localRows;
      }
      const merged = new Map<string, EncryptedVaultRow>();
      for (const l of localRows) merged.set(l.id, l);
      for (const m of mirrorRows) {
        const existing = merged.get(m.id);
        merged.set(m.id, existing ? reconcileEncryptedRows(existing, m) : m);
      }
      return Array.from(merged.values());
    }
    return localRows;
  }

  async loadAllEncryptedRows(): Promise<EncryptedVaultRow[]> {
    const all = await this.loadAllRowsIncludingTombstones();
    return all.filter((r) => !r.isTombstone);
  }

  async clearAll(): Promise<void> {
    await this.sqliteEngine.clearAll();
    if (this.activeStorageMode === 'CLOUD_SYNC') {
      await this.sqliteEngine.purgeCloudSessionMirrorAndOutbox();
      this.boundCoordinator?.updateRuntimeSyncTelemetry({
        queuedMutationsCount: 0
      });
    }
  }

  /**
   * Seamlessly switches between `LOCAL_ONLY` (100% offline local DB) and `CLOUD_SYNC`
   * (Local DB + real-time Cloud Sync) while keeping local SQLite rows intact.
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
      // 1. Ensure local SQLite DB (`vault_item`) and cloud mirror/outbox both hold current snapshot
      for (const rawRow of activeSnapshotRows) {
        const row: EncryptedVaultRow = {
          ...rawRow,
          hlcPacked: normalizeHlcPacked(rawRow.hlcPacked)
        };
        await this.sqliteEngine.saveEncryptedRow(row);
        await this.sqliteEngine.saveCloudMirrorRow(row);
        await this.sqliteEngine.enqueueCloudMutationOutboxRow(row);
      }
      coord.setStorageMode('CLOUD_SYNC');
      const outbox = await this.sqliteEngine.listCloudMutationOutboxRows();
      coord.updateRuntimeSyncTelemetry({ queuedMutationsCount: outbox.length });

      // 2. Immediately reconcile if online; if offline, automatic outbox holds items seamlessly
      if (coord.indicatorSnapshot.isOnline) {
        return this.reconcileCloudSessionAutomatically(vaultId, providerKey);
      }
      return null;
    }

    // Switching `CLOUD_SYNC` -> `LOCAL_ONLY`:
    // Preserve local encrypted SQLite (`vault_item`) and pause Cloud Sync without destroying local or cloud state
    for (const rawRow of activeSnapshotRows) {
      await this.sqliteEngine.saveEncryptedRow({
        ...rawRow,
        hlcPacked: normalizeHlcPacked(rawRow.hlcPacked)
      });
    }
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
      const localRows = await this.sqliteEngine.loadAllRowsIncludingTombstones();
      const outbox = await this.sqliteEngine.listCloudMutationOutboxRows();
      const mirror =
        await this.sqliteEngine.loadAllCloudMirrorRowsIncludingTombstones();
      const byId = new Map<string, EncryptedVaultRow>();
      for (const l of localRows) byId.set(l.id, l);
      for (const m of mirror) {
        const existing = byId.get(m.id);
        byId.set(m.id, existing ? reconcileEncryptedRows(existing, m) : m);
      }
      for (const o of outbox) {
        const existing = byId.get(o.id);
        byId.set(o.id, existing ? reconcileEncryptedRows(existing, o) : o);
      }

      const pendingBlobs =
        await this.sqliteEngine.listPendingUnsyncedBlobs(vaultId);

      const bridge =
        this.cloudCipherBridge && this.cloudCipherBridge.isActive()
          ? this.cloudCipherBridge
          : null;

      let candidateRowsForCloud: EncryptedVaultRow[];
      if (bridge) {
        candidateRowsForCloud = [];
        for (const localRow of byId.values()) {
          const translated = await bridge.toCloudRow(localRow);
          if (translated) {
            candidateRowsForCloud.push(translated);
          }
        }
      } else {
        candidateRowsForCloud = Array.from(byId.values());
      }

      // Full delta pull (`sinceCursorHlc = null`) so device clock skew or out-of-order offline flushes never hide remote edits
      const res = await coord.syncBidirectionalIfEnabled(
        vaultId,
        candidateRowsForCloud,
        null,
        pendingBlobs
      );
      if (!res) {
        coord.updateRuntimeSyncTelemetry({ syncing: false });
        return null;
      }

      for (const uploadedId of Object.keys(res.uploadedBlobPaths)) {
        await this.sqliteEngine.markLocalBlobSynced(uploadedId);
      }

      let finalLocalMergedRows: EncryptedVaultRow[];
      if (bridge) {
        finalLocalMergedRows = [];
        const migrationCloudRows: EncryptedVaultRow[] = [];
        for (const cloudRow of res.mergedRows) {
          const converted = await bridge.fromCloudRow(cloudRow);
          if (converted) {
            finalLocalMergedRows.push(converted.localRow);
            if (converted.reencryptForCloud) {
              migrationCloudRows.push(converted.reencryptForCloud);
            }
          }
        }
        if (migrationCloudRows.length > 0) {
          await coord.syncEncryptedRowsIfEnabled(vaultId, migrationCloudRows);
        }
      } else {
        finalLocalMergedRows = res.mergedRows;
      }

      for (const merged of finalLocalMergedRows) {
        await this.sqliteEngine.saveEncryptedRow(merged);
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
      return {
        ...res,
        mergedRows: finalLocalMergedRows
      };
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
