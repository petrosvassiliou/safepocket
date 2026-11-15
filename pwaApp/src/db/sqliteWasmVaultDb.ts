import initSqlJs, { type Database, type SqlJsStatic } from 'sql.js';
import type { EncryptedBlobEnvelope, EncryptedVaultRow } from '../types';

let cachedSqlRuntime: Promise<SqlJsStatic> | null = null;

async function getSqlWasmRuntime(): Promise<SqlJsStatic> {
  if (!cachedSqlRuntime) {
    const isBrowser = typeof window !== 'undefined';
    cachedSqlRuntime = initSqlJs(
      isBrowser
        ? {
            locateFile: (file: string) => `/${file}`
          }
        : undefined
    );
  }
  return cachedSqlRuntime;
}

const OPFS_SQLITE_FILENAME = 'safepocket_vault_v01.sqlite3';

async function readOpfsBytes(fileName: string): Promise<Uint8Array | null> {
  if (
    typeof navigator === 'undefined' ||
    !navigator.storage ||
    typeof navigator.storage.getDirectory !== 'function'
  ) {
    return null;
  }
  try {
    const root = await navigator.storage.getDirectory();
    const fh = await root.getFileHandle(fileName);
    const file = await fh.getFile();
    const buf = await file.arrayBuffer();
    return buf.byteLength > 0 ? new Uint8Array(buf) : null;
  } catch {
    return null;
  }
}

async function writeOpfsBytes(
  fileName: string,
  bytes: Uint8Array
): Promise<'opfs' | 'memory'> {
  if (
    typeof navigator === 'undefined' ||
    !navigator.storage ||
    typeof navigator.storage.getDirectory !== 'function'
  ) {
    return 'memory';
  }
  try {
    const root = await navigator.storage.getDirectory();
    const fh = await root.getFileHandle(fileName, { create: true });
    const writable = await fh.createWritable();
    await writable.write(bytes as BufferSource);
    await writable.close();
    return 'opfs';
  } catch {
    return 'memory';
  }
}

const DDL_SCHEMA = `
CREATE TABLE IF NOT EXISTS vault_item (
  id TEXT NOT NULL PRIMARY KEY,
  category TEXT NOT NULL,
  favorite INTEGER NOT NULL DEFAULT 0,
  iv_hex TEXT NOT NULL,
  ciphertext_hex TEXT NOT NULL,
  aad_context TEXT NOT NULL,
  hlc_packed TEXT NOT NULL,
  version_vector_json TEXT NOT NULL,
  is_tombstone INTEGER NOT NULL DEFAULT 0,
  updated_at_epoch_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vault_item_hlc ON vault_item(hlc_packed);
CREATE INDEX IF NOT EXISTS idx_vault_item_category ON vault_item(category, is_tombstone);

CREATE TABLE IF NOT EXISTS vault_attachment_blob (
  attachment_id TEXT NOT NULL PRIMARY KEY,
  vault_id TEXT NOT NULL,
  sha256_hex TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  encrypted_blob BLOB NOT NULL,
  cloud_synced INTEGER NOT NULL DEFAULT 0,
  updated_at_epoch_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vault_attachment_pending ON vault_attachment_blob(vault_id, cloud_synced);

CREATE TABLE IF NOT EXISTS sync_checkpoint (
  provider_id TEXT NOT NULL PRIMARY KEY,
  last_cursor_hlc TEXT
);

-- Pre-provisioned Local Firebase Zero-Knowledge Replica Tables
-- Present from v0.1 initial boot even when Cloud Sync is disabled by default.
CREATE TABLE IF NOT EXISTS firebase_auth_users (
  uid TEXT NOT NULL PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  auth_verifier_hex TEXT NOT NULL,
  salt_hex TEXT NOT NULL,
  created_at_iso TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS firebase_firestore_deltas (
  uid TEXT NOT NULL,
  vault_id TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  hlc_packed TEXT NOT NULL,
  version_vector_json TEXT NOT NULL,
  iv_hex TEXT NOT NULL,
  ciphertext_hex TEXT NOT NULL,
  aad_context TEXT NOT NULL,
  is_tombstone INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (uid, vault_id, entity_id)
);

CREATE TABLE IF NOT EXISTS firebase_storage_blobs (
  uid TEXT NOT NULL,
  vault_id TEXT NOT NULL,
  attachment_id TEXT NOT NULL,
  ciphertext_sha256 TEXT NOT NULL,
  content_type TEXT NOT NULL,
  encrypted_blob BLOB NOT NULL,
  updated_at_iso TEXT NOT NULL,
  PRIMARY KEY (uid, vault_id, attachment_id)
);
`;

export interface LocalFirebaseUserRecord {
  uid: string;
  email: string;
  authVerifierHex: string;
  saltHex: string;
  createdAtIso: string;
}

/**
 * SQLite WASM + OPFS Encrypted Local Vault & Pre-Initialized Firebase Database Engine.
 *
 * Achieves 1:1 SQL schema parity with KMP `SafePocketVault.sq` while persisting the binary
 * SQLite database file directly into Origin Private File System (`safepocket_vault_v01.sqlite3`).
 */
export class SqliteWasmVaultDb {
  private db: Database | null = null;
  private initPromise: Promise<Database> | null = null;
  private storageBackend: 'opfs' | 'memory' = 'memory';

  constructor(private readonly persistToOpfs = true) {}

  get activeBackend(): 'opfs' | 'memory' {
    return this.storageBackend;
  }

  async ensureInitialized(): Promise<Database> {
    if (this.db) return this.db;
    if (!this.initPromise) {
      this.initPromise = (async () => {
        const SQL = await getSqlWasmRuntime();
        const existingBytes = this.persistToOpfs
          ? await readOpfsBytes(OPFS_SQLITE_FILENAME)
          : null;
        const database = existingBytes
          ? new SQL.Database(existingBytes)
          : new SQL.Database();
        database.run(DDL_SCHEMA);
        this.db = database;
        if (this.persistToOpfs) {
          this.storageBackend = await writeOpfsBytes(
            OPFS_SQLITE_FILENAME,
            database.export()
          );
        }
        return database;
      })();
    }
    return this.initPromise;
  }

  private async flushToOpfs(): Promise<void> {
    if (!this.db || !this.persistToOpfs) return;
    this.storageBackend = await writeOpfsBytes(
      OPFS_SQLITE_FILENAME,
      this.db.export()
    );
  }

  async saveEncryptedRow(row: EncryptedVaultRow): Promise<void> {
    const db = await this.ensureInitialized();
    db.run(
      `INSERT OR REPLACE INTO vault_item (
        id, category, favorite, iv_hex, ciphertext_hex, aad_context,
        hlc_packed, version_vector_json, is_tombstone, updated_at_epoch_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        row.id,
        row.category,
        row.favorite ? 1 : 0,
        row.ivHex,
        row.ciphertextHex,
        row.aadContext,
        row.hlcPacked,
        JSON.stringify(row.versionVector),
        row.isTombstone ? 1 : 0,
        row.updatedAtEpochMs
      ]
    );
    await this.flushToOpfs();
  }

  async loadAllRowsIncludingTombstones(): Promise<EncryptedVaultRow[]> {
    const db = await this.ensureInitialized();
    const stmt = db.prepare(
      `SELECT id, category, favorite, iv_hex, ciphertext_hex, aad_context,
              hlc_packed, version_vector_json, is_tombstone, updated_at_epoch_ms
       FROM vault_item
       ORDER BY updated_at_epoch_ms DESC`
    );
    const rows: EncryptedVaultRow[] = [];
    while (stmt.step()) {
      const r = stmt.getAsObject() as Record<string, unknown>;
      rows.push({
        id: String(r.id),
        category: r.category as EncryptedVaultRow['category'],
        favorite: Number(r.favorite) === 1,
        ivHex: String(r.iv_hex),
        ciphertextHex: String(r.ciphertext_hex),
        aadContext: String(r.aad_context),
        hlcPacked: String(r.hlc_packed),
        versionVector: JSON.parse(String(r.version_vector_json)) as Record<
          string,
          number
        >,
        isTombstone: Number(r.is_tombstone) === 1,
        updatedAtEpochMs: Number(r.updated_at_epoch_ms)
      });
    }
    stmt.free();
    return rows;
  }

  async loadAllEncryptedRows(): Promise<EncryptedVaultRow[]> {
    const all = await this.loadAllRowsIncludingTombstones();
    return all.filter((r) => !r.isTombstone);
  }

  async clearAll(): Promise<void> {
    const db = await this.ensureInitialized();
    db.run('DELETE FROM vault_item;');
    await this.flushToOpfs();
  }

  async getSyncCursor(providerId: string): Promise<string | null> {
    const db = await this.ensureInitialized();
    const stmt = db.prepare(
      'SELECT last_cursor_hlc FROM sync_checkpoint WHERE provider_id = ?'
    );
    stmt.bind([providerId]);
    let cursor: string | null = null;
    if (stmt.step()) {
      const row = stmt.getAsObject();
      cursor = row.last_cursor_hlc ? String(row.last_cursor_hlc) : null;
    }
    stmt.free();
    return cursor;
  }

  async setSyncCursor(providerId: string, cursorHlc: string | null): Promise<void> {
    const db = await this.ensureInitialized();
    db.run(
      'INSERT OR REPLACE INTO sync_checkpoint (provider_id, last_cursor_hlc) VALUES (?, ?)',
      [providerId, cursorHlc]
    );
    await this.flushToOpfs();
  }

  // --- Pre-initialized Local Firebase Auth & Firestore Replica Operations ---

  async createLocalFirebaseUser(record: LocalFirebaseUserRecord): Promise<void> {
    const db = await this.ensureInitialized();
    const check = db.prepare(
      'SELECT uid FROM firebase_auth_users WHERE LOWER(email) = LOWER(?)'
    );
    check.bind([record.email]);
    const exists = check.step();
    check.free();
    if (exists) {
      throw new Error('EMAIL_EXISTS: A Firebase sync user with this email already exists');
    }
    db.run(
      `INSERT INTO firebase_auth_users (uid, email, auth_verifier_hex, salt_hex, created_at_iso)
       VALUES (?, ?, ?, ?, ?)`,
      [
        record.uid,
        record.email.toLowerCase(),
        record.authVerifierHex,
        record.saltHex,
        record.createdAtIso
      ]
    );
    await this.flushToOpfs();
  }

  async findLocalFirebaseUserByEmail(
    email: string
  ): Promise<LocalFirebaseUserRecord | null> {
    const db = await this.ensureInitialized();
    const stmt = db.prepare(
      `SELECT uid, email, auth_verifier_hex, salt_hex, created_at_iso
       FROM firebase_auth_users WHERE LOWER(email) = LOWER(?)`
    );
    stmt.bind([email]);
    let found: LocalFirebaseUserRecord | null = null;
    if (stmt.step()) {
      const r = stmt.getAsObject();
      found = {
        uid: String(r.uid),
        email: String(r.email),
        authVerifierHex: String(r.auth_verifier_hex),
        saltHex: String(r.salt_hex),
        createdAtIso: String(r.created_at_iso)
      };
    }
    stmt.free();
    return found;
  }

  async upsertLocalFirestoreDelta(
    uid: string,
    vaultId: string,
    row: EncryptedVaultRow
  ): Promise<void> {
    const db = await this.ensureInitialized();
    db.run(
      `INSERT OR REPLACE INTO firebase_firestore_deltas (
        uid, vault_id, entity_id, entity_type, hlc_packed,
        version_vector_json, iv_hex, ciphertext_hex, aad_context, is_tombstone
      ) VALUES (?, ?, ?, 'ITEM', ?, ?, ?, ?, ?, ?)`,
      [
        uid,
        vaultId,
        row.id,
        row.hlcPacked,
        JSON.stringify(row.versionVector),
        row.ivHex,
        row.ciphertextHex,
        row.aadContext,
        row.isTombstone ? 1 : 0
      ]
    );
    await this.flushToOpfs();
  }

  async queryLocalFirestoreDeltas(
    uid: string,
    vaultId: string,
    sinceCursorHlc: string | null
  ): Promise<EncryptedVaultRow[]> {
    const db = await this.ensureInitialized();
    const stmt = sinceCursorHlc
      ? db.prepare(
          `SELECT entity_id, hlc_packed, version_vector_json, iv_hex, ciphertext_hex, aad_context, is_tombstone
           FROM firebase_firestore_deltas
           WHERE uid = ? AND vault_id = ? AND hlc_packed > ?
           ORDER BY hlc_packed ASC`
        )
      : db.prepare(
          `SELECT entity_id, hlc_packed, version_vector_json, iv_hex, ciphertext_hex, aad_context, is_tombstone
           FROM firebase_firestore_deltas
           WHERE uid = ? AND vault_id = ?
           ORDER BY hlc_packed ASC`
        );

    stmt.bind(sinceCursorHlc ? [uid, vaultId, sinceCursorHlc] : [uid, vaultId]);
    const out: EncryptedVaultRow[] = [];
    while (stmt.step()) {
      const r = stmt.getAsObject();
      const aad = String(r.aad_context);
      // Extract category from aadContext `safepocket:v1:<CATEGORY>:<ID>` or `safepocket:tombstone:<CATEGORY>:<ID>`
      const parts = aad.split(':');
      const cat = (parts[2] ?? 'SECURE_NOTE') as EncryptedVaultRow['category'];
      out.push({
        id: String(r.entity_id),
        category: cat,
        favorite: false,
        ivHex: String(r.iv_hex),
        ciphertextHex: String(r.ciphertext_hex),
        aadContext: aad,
        hlcPacked: String(r.hlc_packed),
        versionVector: JSON.parse(String(r.version_vector_json)) as Record<
          string,
          number
        >,
        isTombstone: Number(r.is_tombstone) === 1,
        updatedAtEpochMs: Date.now()
      });
    }
    stmt.free();
    return out;
  }

  // --- Encrypted Attachment Blob Cache, Sync Queue & Firebase Storage Replica ---

  async saveLocalEncryptedBlob(
    vaultId: string,
    attachmentId: string,
    encryptedBytes: Uint8Array,
    sha256Hex: string,
    cloudSynced = false
  ): Promise<void> {
    const db = await this.ensureInitialized();
    db.run(
      `INSERT OR REPLACE INTO vault_attachment_blob (
        attachment_id, vault_id, sha256_hex, size_bytes, encrypted_blob, cloud_synced, updated_at_epoch_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [
        attachmentId,
        vaultId,
        sha256Hex.toLowerCase(),
        encryptedBytes.byteLength,
        encryptedBytes,
        cloudSynced ? 1 : 0,
        Date.now()
      ]
    );
    await this.flushToOpfs();
  }

  async loadLocalEncryptedBlob(attachmentId: string): Promise<{
    encryptedBytes: Uint8Array;
    sha256Hex: string;
    cloudSynced: boolean;
  } | null> {
    const db = await this.ensureInitialized();
    const stmt = db.prepare(
      `SELECT sha256_hex, encrypted_blob, cloud_synced
       FROM vault_attachment_blob
       WHERE attachment_id = ?`
    );
    stmt.bind([attachmentId]);
    let found: {
      encryptedBytes: Uint8Array;
      sha256Hex: string;
      cloudSynced: boolean;
    } | null = null;
    if (stmt.step()) {
      const r = stmt.getAsObject();
      const rawBlob = r.encrypted_blob;
      const bytes =
        rawBlob instanceof Uint8Array ? rawBlob.slice() : new Uint8Array(0);
      found = {
        encryptedBytes: bytes,
        sha256Hex: String(r.sha256_hex),
        cloudSynced: Number(r.cloud_synced) === 1
      };
    }
    stmt.free();
    return found;
  }

  async markLocalBlobSynced(attachmentId: string): Promise<void> {
    const db = await this.ensureInitialized();
    db.run(
      `UPDATE vault_attachment_blob
       SET cloud_synced = 1, updated_at_epoch_ms = ?
       WHERE attachment_id = ?`,
      [Date.now(), attachmentId]
    );
    await this.flushToOpfs();
  }

  async listPendingUnsyncedBlobs(
    vaultId: string
  ): Promise<EncryptedBlobEnvelope[]> {
    const db = await this.ensureInitialized();
    const stmt = db.prepare(
      `SELECT attachment_id, sha256_hex, encrypted_blob
       FROM vault_attachment_blob
       WHERE vault_id = ? AND cloud_synced = 0
       ORDER BY updated_at_epoch_ms ASC`
    );
    stmt.bind([vaultId]);
    const out: EncryptedBlobEnvelope[] = [];
    while (stmt.step()) {
      const r = stmt.getAsObject();
      const rawBlob = r.encrypted_blob;
      out.push({
        attachmentId: String(r.attachment_id),
        sha256Hex: String(r.sha256_hex),
        encryptedBytes:
          rawBlob instanceof Uint8Array ? rawBlob.slice() : new Uint8Array(0)
      });
    }
    stmt.free();
    return out;
  }

  async deleteLocalEncryptedBlob(attachmentId: string): Promise<void> {
    const db = await this.ensureInitialized();
    db.run('DELETE FROM vault_attachment_blob WHERE attachment_id = ?', [
      attachmentId
    ]);
    await this.flushToOpfs();
  }

  async upsertLocalStorageReplicaBlob(
    uid: string,
    vaultId: string,
    attachmentId: string,
    encryptedBytes: Uint8Array,
    ciphertextSha256: string
  ): Promise<void> {
    const db = await this.ensureInitialized();
    db.run(
      `INSERT OR REPLACE INTO firebase_storage_blobs (
        uid, vault_id, attachment_id, ciphertext_sha256, content_type, encrypted_blob, updated_at_iso
      ) VALUES (?, ?, ?, ?, 'application/octet-stream', ?, ?)`,
      [
        uid,
        vaultId,
        attachmentId,
        ciphertextSha256.toLowerCase(),
        encryptedBytes,
        new Date().toISOString()
      ]
    );
    await this.flushToOpfs();
  }

  async getLocalStorageReplicaBlob(
    uid: string,
    vaultId: string,
    attachmentId: string
  ): Promise<{ encryptedBytes: Uint8Array; ciphertextSha256: string } | null> {
    const db = await this.ensureInitialized();
    const stmt = db.prepare(
      `SELECT ciphertext_sha256, encrypted_blob
       FROM firebase_storage_blobs
       WHERE uid = ? AND vault_id = ? AND attachment_id = ?`
    );
    stmt.bind([uid, vaultId, attachmentId]);
    let found: { encryptedBytes: Uint8Array; ciphertextSha256: string } | null =
      null;
    if (stmt.step()) {
      const r = stmt.getAsObject();
      const rawBlob = r.encrypted_blob;
      found = {
        ciphertextSha256: String(r.ciphertext_sha256),
        encryptedBytes:
          rawBlob instanceof Uint8Array ? rawBlob.slice() : new Uint8Array(0)
      };
    }
    stmt.free();
    return found;
  }
}
