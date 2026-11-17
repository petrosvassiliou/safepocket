import {
  bytesToHex,
  constantTimeHexEquals,
  sha256Hex
} from '../crypto/vaultCrypto';
import type { EncryptedBlobEnvelope, EncryptedVaultRow } from '../types';

export interface LiveCloudEndpointConfig {
  firebaseApiKey?: string;
  firebaseProjectId?: string;
  firebaseStorageBucket?: string;
  firebaseAppId?: string;
  firebaseUserId?: string;
  bearerToken?: string;
}

export interface FirebaseAuthResult {
  uid: string;
  email: string;
  idToken: string;
  refreshToken?: string;
  mode: 'live-firebase' | 'embedded-firebase-db';
}

const ALLOWED_ZERO_KNOWLEDGE_KEYS = new Set([
  'entityId',
  'entityType',
  'hlcPacked',
  'versionVector',
  'ivHex',
  'ciphertextHex',
  'aadContext',
  'isTombstone'
]);

/**
 * Derives a domain-separated Cloud Auth credential via HKDF-SHA-256 so the user's raw password
 * (or Master Password) is NEVER transmitted to Firebase Authentication.
 */
export async function deriveZeroKnowledgeCloudAuthPassword(
  email: string,
  syncPassword: string
): Promise<string> {
  const normalizedEmail = email.trim().toLowerCase();
  const seedBytes = new TextEncoder().encode(
    `safepocket-cloud-auth:${normalizedEmail}:${syncPassword}`
  );
  const ikmKey = await crypto.subtle.importKey(
    'raw',
    seedBytes,
    'HKDF',
    false,
    ['deriveBits']
  );
  const saltBytes = new TextEncoder().encode(
    `salt:${normalizedEmail}:safepocket-v0.1`
  );
  const derivedBits = await crypto.subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: saltBytes,
      info: new TextEncoder().encode('safepocket-firebase-auth-verifier-v1')
    },
    ikmKey,
    256
  );
  return `spzk_${bytesToHex(new Uint8Array(derivedBits))}`;
}

/**
 * Firebase Identity Toolkit REST User Creation (`accounts:signUp`).
 */
export async function firebaseAuthSignUpRest(
  apiKey: string,
  email: string,
  syncPassword: string,
  fetcher: typeof fetch = fetch
): Promise<FirebaseAuthResult> {
  const zkAuthToken = await deriveZeroKnowledgeCloudAuthPassword(
    email,
    syncPassword
  );
  const res = await fetcher(
    `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${encodeURIComponent(
      apiKey
    )}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: email.trim().toLowerCase(),
        password: zkAuthToken,
        returnSecureToken: true
      })
    }
  );
  const data = (await res.json()) as Record<string, any>;
  if (!res.ok) {
    throw new Error(
      data?.error?.message || `Firebase Auth signUp failed (HTTP ${res.status})`
    );
  }
  return {
    uid: String(data.localId),
    email: String(data.email),
    idToken: String(data.idToken),
    refreshToken: data.refreshToken ? String(data.refreshToken) : undefined,
    mode: 'live-firebase'
  };
}

/**
 * Firebase Identity Toolkit REST User Sign-In (`accounts:signInWithPassword`).
 */
export async function firebaseAuthSignInRest(
  apiKey: string,
  email: string,
  syncPassword: string,
  fetcher: typeof fetch = fetch
): Promise<FirebaseAuthResult> {
  const zkAuthToken = await deriveZeroKnowledgeCloudAuthPassword(
    email,
    syncPassword
  );
  const res = await fetcher(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${encodeURIComponent(
      apiKey
    )}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: email.trim().toLowerCase(),
        password: zkAuthToken,
        returnSecureToken: true
      })
    }
  );
  const data = (await res.json()) as Record<string, any>;
  if (!res.ok) {
    throw new Error(
      data?.error?.message || `Firebase Auth signIn failed (HTTP ${res.status})`
    );
  }
  return {
    uid: String(data.localId),
    email: String(data.email),
    idToken: String(data.idToken),
    refreshToken: data.refreshToken ? String(data.refreshToken) : undefined,
    mode: 'live-firebase'
  };
}

/**
 * Inspects Base64URL-encoded Firebase JWT `exp` and `aud` claims without requiring external libraries.
 */
export function inspectFirebaseJwtClaims(
  idToken: string
): { exp?: number; aud?: string } | null {
  if (!idToken) return null;
  const parts = idToken.trim().split('.');
  if (parts.length < 2) return null;
  try {
    let base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4 !== 0) {
      base64 += '=';
    }
    const decoded =
      typeof atob === 'function'
        ? atob(base64)
        : Buffer.from(base64, 'base64').toString('utf-8');
    const parsed = JSON.parse(decoded) as Record<string, unknown>;
    return {
      exp: typeof parsed.exp === 'number' ? parsed.exp : undefined,
      aud: typeof parsed.aud === 'string' ? parsed.aud : undefined
    };
  } catch {
    return null;
  }
}

function sanitizeBearerToken(raw: string): string {
  const trimmed = raw.trim();
  return /^bearer\s+/i.test(trimmed)
    ? trimmed.replace(/^bearer\s+/i, '').trim()
    : trimmed;
}

/**
 * Refreshes an expired or near-expiry Firebase `idToken` via `securetoken.googleapis.com/v1/token`
 * using `application/json` so Base64 `+`, `/`, and `=` characters in `refreshToken` are never corrupted.
 */
export async function firebaseRefreshIdTokenRest(
  apiKey: string,
  refreshToken: string,
  fetcher: typeof fetch = fetch
): Promise<{ idToken: string; refreshToken: string; uid?: string }> {
  const cleanKey = apiKey.trim();
  const cleanRefresh = refreshToken.trim();
  if (!cleanKey || !cleanRefresh) {
    throw new Error('Missing Firebase API key or refreshToken');
  }
  const res = await fetcher(
    `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(
      cleanKey
    )}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        grant_type: 'refresh_token',
        refresh_token: cleanRefresh
      })
    }
  );
  const data = (await res.json()) as Record<string, any>;
  if (!res.ok) {
    throw new Error(
      data?.error?.message ||
        `Firebase securetoken refresh failed (HTTP ${res.status})`
    );
  }
  const nextIdToken = String(data.id_token || data.idToken || '').trim();
  if (!nextIdToken) {
    throw new Error('Firebase securetoken response missing id_token');
  }
  return {
    idToken: nextIdToken,
    refreshToken: String(
      data.refresh_token || data.refreshToken || cleanRefresh
    ).trim(),
    uid: data.user_id ? String(data.user_id) : undefined
  };
}

/**
 * Constant-time local auth verifier helper for embedded SQLite Firebase replica.
 */
export async function computeEmbeddedAuthVerifier(
  saltHex: string,
  zkAuthPassword: string
): Promise<string> {
  return sha256Hex(
    new TextEncoder().encode(`embedded-fb-verifier:${saltHex}:${zkAuthPassword}`)
  );
}

export function verifyEmbeddedAuthDigest(a: string, b: string): boolean {
  return constantTimeHexEquals(a, b);
}

/**
 * Client-side Zero-Knowledge Outbound/Inbound Guard mirroring `firebase/firestore.rules`.
 * Guarantees no plaintext title, subtitle, tag, or field value can ever be serialized to a cloud endpoint.
 */
export function assertStrictZeroKnowledgeEnvelope(
  envelope: Record<string, unknown>
): void {
  for (const key of Object.keys(envelope)) {
    if (!ALLOWED_ZERO_KNOWLEDGE_KEYS.has(key)) {
      throw new Error(
        `Zero-knowledge schema violation: forbidden outbound field "${key}"`
      );
    }
  }
  if (
    typeof envelope.ciphertextHex !== 'string' ||
    envelope.ciphertextHex.length < 32 ||
    typeof envelope.ivHex !== 'string' ||
    envelope.ivHex.length !== 24
  ) {
    throw new Error('Zero-knowledge schema violation: malformed AES-GCM envelope');
  }
}

/**
 * Builds and dispatches a Cloud Firestore REST `:commit` request for Default Cloud Service (`FIREBASE`).
 */
export async function pushRowsToFirestoreRest(
  vaultId: string,
  rows: EncryptedVaultRow[],
  config: Required<Pick<LiveCloudEndpointConfig, 'firebaseProjectId' | 'bearerToken'>> & {
    firebaseUserId?: string;
  },
  fetcher: typeof fetch = fetch
): Promise<{ pushedCount: number; cursorHlc: string | null }> {
  if (rows.length === 0) return { pushedCount: 0, cursorHlc: null };
  const uid = config.firebaseUserId || 'authenticated-user';
  const commitUrl = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(
    config.firebaseProjectId
  )}/databases/(default)/documents:commit`;

  const writes = rows.map((row) => {
    const zkRecord = {
      entityId: row.id,
      entityType: 'ITEM',
      hlcPacked: row.hlcPacked,
      versionVector: JSON.stringify(row.versionVector),
      ivHex: row.ivHex,
      ciphertextHex: row.ciphertextHex,
      aadContext: row.aadContext,
      isTombstone: row.isTombstone
    };
    assertStrictZeroKnowledgeEnvelope(zkRecord);

    return {
      update: {
        name: `projects/${config.firebaseProjectId}/databases/(default)/documents/users/${uid}/vaults/${vaultId}/deltas/${row.id}`,
        fields: {
          entityId: { stringValue: zkRecord.entityId },
          entityType: { stringValue: zkRecord.entityType },
          hlcPacked: { stringValue: zkRecord.hlcPacked },
          versionVector: { stringValue: zkRecord.versionVector },
          ivHex: { stringValue: zkRecord.ivHex },
          ciphertextHex: { stringValue: zkRecord.ciphertextHex },
          aadContext: { stringValue: zkRecord.aadContext },
          isTombstone: { booleanValue: zkRecord.isTombstone }
        }
      }
    };
  });

  const res = await fetcher(commitUrl, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${sanitizeBearerToken(config.bearerToken)}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ writes })
  });
  if (!res.ok) {
    throw new Error(`Firestore commit failed with HTTP ${res.status}`);
  }
  const maxHlc = rows.reduce<string | null>(
    (acc, r) => (!acc || r.hlcPacked > acc ? r.hlcPacked : acc),
    null
  );
  return { pushedCount: rows.length, cursorHlc: maxHlc };
}

/**
 * Pulls remote encrypted CRDT delta documents from Cloud Firestore REST `:runQuery`.
 */
export async function pullRowsFromFirestoreRest(
  vaultId: string,
  sinceCursorHlc: string | null,
  config: Required<Pick<LiveCloudEndpointConfig, 'firebaseProjectId' | 'bearerToken'>> & {
    firebaseUserId?: string;
  },
  fetcher: typeof fetch = fetch
): Promise<EncryptedVaultRow[]> {
  const uid = config.firebaseUserId || 'authenticated-user';
  const parentUrl = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(
    config.firebaseProjectId
  )}/databases/(default)/documents/users/${encodeURIComponent(
    uid
  )}/vaults/${encodeURIComponent(vaultId)}:runQuery`;

  const structuredQuery: Record<string, unknown> = {
    from: [{ collectionId: 'deltas' }],
    orderBy: [{ field: { fieldPath: 'hlcPacked' }, direction: 'ASCENDING' }]
  };
  if (sinceCursorHlc) {
    structuredQuery.where = {
      fieldFilter: {
        field: { fieldPath: 'hlcPacked' },
        op: 'GREATER_THAN',
        value: { stringValue: sinceCursorHlc }
      }
    };
  }

  const res = await fetcher(parentUrl, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${sanitizeBearerToken(config.bearerToken)}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ structuredQuery })
  });
  if (!res.ok) {
    throw new Error(`Firestore pull query failed with HTTP ${res.status}`);
  }

  const entries = (await res.json()) as Array<{
    document?: { fields?: Record<string, any> };
  }>;
  const rows: EncryptedVaultRow[] = [];

  for (const item of entries) {
    const f = item.document?.fields;
    if (!f) continue;
    const aadContext = String(f.aadContext?.stringValue ?? '');
    const zkCheck = {
      entityId: String(f.entityId?.stringValue ?? ''),
      entityType: String(f.entityType?.stringValue ?? 'ITEM'),
      hlcPacked: String(f.hlcPacked?.stringValue ?? ''),
      versionVector: String(f.versionVector?.stringValue ?? '{}'),
      ivHex: String(f.ivHex?.stringValue ?? ''),
      ciphertextHex: String(f.ciphertextHex?.stringValue ?? ''),
      aadContext,
      isTombstone: Boolean(f.isTombstone?.booleanValue ?? false)
    };
    assertStrictZeroKnowledgeEnvelope(zkCheck);
    const cat = (aadContext.split(':')[2] ??
      'SECURE_NOTE') as EncryptedVaultRow['category'];
    rows.push({
      id: zkCheck.entityId,
      category: cat,
      favorite: false,
      ivHex: zkCheck.ivHex,
      ciphertextHex: zkCheck.ciphertextHex,
      aadContext: zkCheck.aadContext,
      hlcPacked: zkCheck.hlcPacked,
      versionVector: JSON.parse(zkCheck.versionVector) as Record<string, number>,
      isTombstone: zkCheck.isTombstone,
      updatedAtEpochMs: Date.now()
    });
  }
  return rows;
}

/**
 * Builds and dispatches a Google Drive v3 `spaces=appDataFolder` multipart delta upload
 * for Optional Cloud Service (`GDRIVE_APPDATA`).
 */
export async function pushRowsToGoogleDriveAppDataRest(
  vaultId: string,
  rows: EncryptedVaultRow[],
  bearerToken: string,
  fetcher: typeof fetch = fetch
): Promise<{ pushedCount: number; cursorHlc: string | null }> {
  if (rows.length === 0) return { pushedCount: 0, cursorHlc: null };

  const sanitizedEnvelopes = rows.map((row) => {
    const zkRecord = {
      entityId: row.id,
      entityType: 'ITEM',
      hlcPacked: row.hlcPacked,
      versionVector: row.versionVector,
      ivHex: row.ivHex,
      ciphertextHex: row.ciphertextHex,
      aadContext: row.aadContext,
      isTombstone: row.isTombstone
    };
    assertStrictZeroKnowledgeEnvelope(zkRecord);
    return zkRecord;
  });

  const maxHlc = rows.reduce<string>(
    (acc, r) => (r.hlcPacked > acc ? r.hlcPacked : acc),
    rows[0].hlcPacked
  );
  const boundary = 'safepocket_appdata_v1_boundary';
  const metadata = JSON.stringify({
    name: `sp-delta-${vaultId}-${maxHlc}.json.enc`,
    parents: ['appDataFolder']
  });
  const payloadJson = JSON.stringify(sanitizedEnvelopes);
  const multipartBody = [
    `--${boundary}`,
    'Content-Type: application/json; charset=UTF-8',
    '',
    metadata,
    `--${boundary}`,
    'Content-Type: application/vnd.safepocket.delta+json',
    '',
    payloadJson,
    `--${boundary}--`
  ].join('\r\n');

  const res = await fetcher(
    'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${bearerToken}`,
        'Content-Type': `multipart/related; boundary=${boundary}`
      },
      body: multipartBody
    }
  );
  if (!res.ok) {
    throw new Error(`Google Drive appDataFolder upload HTTP ${res.status}`);
  }
  return { pushedCount: rows.length, cursorHlc: maxHlc };
}

/**
 * Pulls encrypted delta files from Google Drive v3 hidden `spaces=appDataFolder`.
 */
export async function pullRowsFromGoogleDriveAppDataRest(
  vaultId: string,
  sinceCursorHlc: string | null,
  bearerToken: string,
  fetcher: typeof fetch = fetch
): Promise<EncryptedVaultRow[]> {
  const q = encodeURIComponent(
    `name contains 'sp-delta-${vaultId}-' and trashed = false`
  );
  const listRes = await fetcher(
    `https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=${q}&fields=files(id,name)`,
    {
      headers: { Authorization: `Bearer ${bearerToken}` }
    }
  );
  if (!listRes.ok) {
    throw new Error(`Google Drive appDataFolder list HTTP ${listRes.status}`);
  }
  const listData = (await listRes.json()) as {
    files?: Array<{ id: string; name: string }>;
  };
  const out: EncryptedVaultRow[] = [];

  for (const file of listData.files ?? []) {
    const dlRes = await fetcher(
      `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(
        file.id
      )}?alt=media`,
      {
        headers: { Authorization: `Bearer ${bearerToken}` }
      }
    );
    if (!dlRes.ok) continue;
    const records = (await dlRes.json()) as Array<Record<string, any>>;
    for (const rec of records) {
      assertStrictZeroKnowledgeEnvelope(rec);
      const hlc = String(rec.hlcPacked);
      if (sinceCursorHlc && hlc <= sinceCursorHlc) continue;
      const aad = String(rec.aadContext);
      const cat = (aad.split(':')[2] ??
        'SECURE_NOTE') as EncryptedVaultRow['category'];
      out.push({
        id: String(rec.entityId),
        category: cat,
        favorite: false,
        ivHex: String(rec.ivHex),
        ciphertextHex: String(rec.ciphertextHex),
        aadContext: aad,
        hlcPacked: hlc,
        versionVector: (typeof rec.versionVector === 'string'
          ? JSON.parse(rec.versionVector)
          : rec.versionVector) as Record<string, number>,
        isTombstone: Boolean(rec.isTombstone),
        updatedAtEpochMs: Date.now()
      });
    }
  }
  return out;
}

const MAX_ATTACHMENT_BLOB_BYTES = 25 * 1024 * 1024; // Matches firebase/storage.rules (< 25 MiB)
const SAFE_ATTACHMENT_ID_REGEX = /^[a-zA-Z0-9_-]+$/;
const SHA256_HEX_REGEX = /^[a-fA-F0-9]{64}$/;

function concatByteChunks(...chunks: Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, c) => sum + c.byteLength, 0);
  const combined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return combined;
}

/**
 * Client-side Zero-Knowledge Outbound Guard mirroring `firebase/storage.rules`:
 * - Enforces alphanumeric/dash/underscore attachment ID (`<id>.enc`) so original filenames NEVER leak.
 * - Enforces `< 25 MiB` ciphertext size and 64-char hex `ciphertextSha256` matching the exact payload bytes.
 */
export async function assertStrictZeroKnowledgeBlobUpload(
  blob: EncryptedBlobEnvelope
): Promise<void> {
  if (!SAFE_ATTACHMENT_ID_REGEX.test(blob.attachmentId)) {
    throw new Error(
      `Zero-knowledge blob violation: invalid attachmentId "${blob.attachmentId}"`
    );
  }
  if (
    blob.encryptedBytes.byteLength === 0 ||
    blob.encryptedBytes.byteLength >= MAX_ATTACHMENT_BLOB_BYTES
  ) {
    throw new Error(
      `Zero-knowledge blob violation: encrypted blob size (${blob.encryptedBytes.byteLength} B) exceeds 25 MiB limit`
    );
  }
  if (!SHA256_HEX_REGEX.test(blob.sha256Hex)) {
    throw new Error(
      'Zero-knowledge blob violation: invalid SHA-256 hex digest format'
    );
  }
  const actualSha = await sha256Hex(blob.encryptedBytes);
  if (!constantTimeHexEquals(actualSha, blob.sha256Hex)) {
    throw new Error(
      'Zero-knowledge blob violation: pre-upload SHA-256 digest mismatch'
    );
  }
}

/**
 * Uploads an AES-256-GCM `.enc` binary attachment to EU Firebase Cloud Storage
 * (`users/{uid}/vaults/{vaultId}/blobs/{attachmentId}.enc`) via `multipart/related`
 * with `contentType: 'application/octet-stream'` and `metadata.ciphertextSha256`,
 * matching `firebase/storage.rules` 1:1 with zero base64 inflation and zero filename/MIME leakage.
 */
export async function uploadEncryptedBlobToFirebaseStorageRest(
  vaultId: string,
  blob: EncryptedBlobEnvelope,
  config: Required<
    Pick<LiveCloudEndpointConfig, 'firebaseStorageBucket' | 'bearerToken'>
  > & {
    firebaseUserId?: string;
  },
  fetcher: typeof fetch = fetch
): Promise<{ storageObjectPath: string }> {
  await assertStrictZeroKnowledgeBlobUpload(blob);
  const uid = config.firebaseUserId || 'authenticated-user';
  const objectPath = `users/${uid}/vaults/${vaultId}/blobs/${blob.attachmentId}.enc`;
  const boundary = 'safepocket_zk_storage_blob_v1';
  const enc = new TextEncoder();

  const metaJson = JSON.stringify({
    name: objectPath,
    contentType: 'application/octet-stream',
    metadata: {
      ciphertextSha256: blob.sha256Hex.toLowerCase()
    }
  });

  const prefixBytes = enc.encode(
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metaJson}\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`
  );
  const suffixBytes = enc.encode(`\r\n--${boundary}--\r\n`);
  const multipartBytes = concatByteChunks(
    prefixBytes,
    blob.encryptedBytes,
    suffixBytes
  );

  const uploadUrl = `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(
    config.firebaseStorageBucket
  )}/o?uploadType=multipart&name=${encodeURIComponent(objectPath)}`;

  const res = await fetcher(uploadUrl, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${config.bearerToken}`,
      'Content-Type': `multipart/related; boundary=${boundary}`
    },
    body: multipartBytes as BodyInit
  });

  if (!res.ok) {
    throw new Error(`Firebase Storage .enc upload failed with HTTP ${res.status}`);
  }
  return { storageObjectPath: objectPath };
}

/**
 * Downloads an AES-256-GCM `.enc` binary attachment from EU Firebase Cloud Storage
 * (`?alt=media`) and verifies its `SHA-256` digest in constant time before returning.
 */
export async function downloadEncryptedBlobFromFirebaseStorageRest(
  vaultId: string,
  attachmentId: string,
  expectedSha256Hex: string,
  config: Required<
    Pick<LiveCloudEndpointConfig, 'firebaseStorageBucket' | 'bearerToken'>
  > & {
    firebaseUserId?: string;
  },
  fetcher: typeof fetch = fetch
): Promise<Uint8Array | null> {
  if (!SAFE_ATTACHMENT_ID_REGEX.test(attachmentId)) {
    throw new Error(`Invalid attachmentId "${attachmentId}"`);
  }
  const uid = config.firebaseUserId || 'authenticated-user';
  const objectPath = `users/${uid}/vaults/${vaultId}/blobs/${attachmentId}.enc`;
  const downloadUrl = `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(
    config.firebaseStorageBucket
  )}/o/${encodeURIComponent(objectPath)}?alt=media`;

  const res = await fetcher(downloadUrl, {
    headers: {
      Authorization: `Bearer ${config.bearerToken}`
    }
  });
  if (res.status === 404) {
    return null;
  }
  if (!res.ok) {
    throw new Error(
      `Firebase Storage .enc download failed with HTTP ${res.status}`
    );
  }
  const bytes = new Uint8Array(await res.arrayBuffer());
  const actualSha = await sha256Hex(bytes);
  if (!constantTimeHexEquals(actualSha, expectedSha256Hex)) {
    throw new Error(
      'Firebase Storage .enc SHA-256 integrity check failed — tampering detected'
    );
  }
  return bytes;
}

/**
 * Uploads an encrypted `.enc` attachment blob into Google Drive hidden `appDataFolder`
 * (`sp-blob-${vaultId}-${attachmentId}.enc`) for Optional Cloud Service (`GDRIVE_APPDATA`).
 */
export async function uploadEncryptedBlobToGoogleDriveAppDataRest(
  vaultId: string,
  blob: EncryptedBlobEnvelope,
  bearerToken: string,
  fetcher: typeof fetch = fetch
): Promise<{ storageObjectPath: string }> {
  await assertStrictZeroKnowledgeBlobUpload(blob);
  const fileName = `sp-blob-${vaultId}-${blob.attachmentId}.enc`;
  const boundary = 'safepocket_appdata_blob_v1';
  const enc = new TextEncoder();
  const metadata = JSON.stringify({
    name: fileName,
    parents: ['appDataFolder'],
    appProperties: {
      ciphertextSha256: blob.sha256Hex.toLowerCase()
    }
  });
  const bodyBytes = concatByteChunks(
    enc.encode(
      `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`
    ),
    blob.encryptedBytes,
    enc.encode(`\r\n--${boundary}--\r\n`)
  );

  const res = await fetcher(
    'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart',
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${bearerToken}`,
        'Content-Type': `multipart/related; boundary=${boundary}`
      },
      body: bodyBytes as BodyInit
    }
  );
  if (!res.ok) {
    throw new Error(
      `Google Drive appDataFolder .enc upload failed with HTTP ${res.status}`
    );
  }
  return { storageObjectPath: `appDataFolder/${fileName}` };
}

/**
 * Downloads and SHA-256-verifies an encrypted `.enc` attachment blob from Google Drive `appDataFolder`.
 */
export async function downloadEncryptedBlobFromGoogleDriveAppDataRest(
  vaultId: string,
  attachmentId: string,
  expectedSha256Hex: string,
  bearerToken: string,
  fetcher: typeof fetch = fetch
): Promise<Uint8Array | null> {
  const fileName = `sp-blob-${vaultId}-${attachmentId}.enc`;
  const q = encodeURIComponent(`name = '${fileName}' and trashed = false`);
  const listRes = await fetcher(
    `https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=${q}&fields=files(id,name)`,
    {
      headers: { Authorization: `Bearer ${bearerToken}` }
    }
  );
  if (!listRes.ok) {
    throw new Error(
      `Google Drive appDataFolder blob list failed with HTTP ${listRes.status}`
    );
  }
  const listData = (await listRes.json()) as {
    files?: Array<{ id: string; name: string }>;
  };
  const latest = listData.files?.[0];
  if (!latest) return null;

  const dlRes = await fetcher(
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(
      latest.id
    )}?alt=media`,
    {
      headers: { Authorization: `Bearer ${bearerToken}` }
    }
  );
  if (!dlRes.ok) {
    throw new Error(
      `Google Drive appDataFolder blob download failed with HTTP ${dlRes.status}`
    );
  }
  const bytes = new Uint8Array(await dlRes.arrayBuffer());
  const actualSha = await sha256Hex(bytes);
  if (!constantTimeHexEquals(actualSha, expectedSha256Hex)) {
    throw new Error(
      'Google Drive appDataFolder .enc SHA-256 integrity check failed'
    );
  }
  return bytes;
}
