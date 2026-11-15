import { bytesToHex } from '../crypto/vaultCrypto';
import type { SqliteWasmVaultDb } from '../db/sqliteWasmVaultDb';
import {
  computeEmbeddedAuthVerifier,
  deriveZeroKnowledgeCloudAuthPassword,
  type FirebaseAuthResult,
  firebaseAuthSignInRest,
  firebaseAuthSignUpRest,
  type LiveCloudEndpointConfig,
  verifyEmbeddedAuthDigest
} from '../sync/liveCloudConnectors';

const FIREBASE_CONFIG_STORAGE_KEY = 'safepocket.firebase_web_config_v1';
const FIREBASE_SESSION_STORAGE_KEY = 'safepocket.firebase_auth_session_v1';

export interface FirebaseWebConfig {
  apiKey: string;
  projectId: string;
  storageBucket: string;
  authDomain?: string;
  appId?: string;
}

/**
 * Provisioned Live Firebase Project (`safepocket-vault-eu`, Cloud Firestore `eur3` EU Multi-Region).
 * Public Firebase Web SDK identifiers; zero-knowledge security is enforced client-side via
 * Argon2id + Secret Key -> AES-256-GCM and server-side via `firebase/firestore.rules`.
 */
export const DEFAULT_PROVISIONED_FIREBASE_CONFIG: Readonly<FirebaseWebConfig> = {
  apiKey: 'AIzaSyBCeyZx121Eq5xxyWZD4k3w1-9vyr0yIsw',
  projectId: 'safepocket-vault-eu',
  storageBucket: 'safepocket-vault-eu.firebasestorage.app',
  authDomain: 'safepocket-vault-eu.firebaseapp.com',
  appId: '1:1028417352953:web:30b5698c6a4fd1db9b1e28'
};

/**
 * Pre-Initialized Firebase Zero-Knowledge Database & User Authentication Service.
 *
 * Present and initialized from v0.1 app boot (even while Cloud Sync is disabled by default).
 * - Zero outbound network calls occur while `cloudSyncEnabled === false`.
 * - Pre-wired to live `safepocket-vault-eu` (`eur3`) in production/browser builds while remaining
 *   100% hermetic in unit tests (`MODE === 'test'`) unless explicitly configured.
 */
export class FirebaseVaultService {
  private initialized = false;
  private config: FirebaseWebConfig;
  private activeSession: FirebaseAuthResult | null = null;

  constructor(
    private readonly sqliteDb: SqliteWasmVaultDb,
    useProvisionedDefaults?: boolean
  ) {
    const envMeta = (import.meta as unknown as { env?: Record<string, string> })
      .env;
    const isTestEnv = envMeta?.MODE === 'test';
    const loadProvisioned = useProvisionedDefaults ?? !isTestEnv;

    const defaultCfg: FirebaseWebConfig = loadProvisioned
      ? {
          apiKey:
            envMeta?.VITE_FIREBASE_API_KEY ||
            DEFAULT_PROVISIONED_FIREBASE_CONFIG.apiKey,
          projectId:
            envMeta?.VITE_FIREBASE_PROJECT_ID ||
            DEFAULT_PROVISIONED_FIREBASE_CONFIG.projectId,
          storageBucket:
            envMeta?.VITE_FIREBASE_STORAGE_BUCKET ||
            DEFAULT_PROVISIONED_FIREBASE_CONFIG.storageBucket,
          authDomain: DEFAULT_PROVISIONED_FIREBASE_CONFIG.authDomain,
          appId:
            envMeta?.VITE_FIREBASE_APP_ID ||
            DEFAULT_PROVISIONED_FIREBASE_CONFIG.appId
        }
      : {
          apiKey: '',
          projectId: 'safepocket-local-zk',
          storageBucket: 'safepocket-local-zk.firebasestorage.app',
          appId: ''
        };

    if (typeof localStorage !== 'undefined') {
      const rawCfg = localStorage.getItem(FIREBASE_CONFIG_STORAGE_KEY);
      if (rawCfg) {
        try {
          const parsed = JSON.parse(rawCfg) as Partial<FirebaseWebConfig>;
          defaultCfg.apiKey = parsed.apiKey ?? defaultCfg.apiKey;
          defaultCfg.projectId = parsed.projectId || defaultCfg.projectId;
          defaultCfg.storageBucket =
            parsed.storageBucket || defaultCfg.storageBucket;
          defaultCfg.appId = parsed.appId ?? defaultCfg.appId;
        } catch {
          // Ignore malformed config
        }
      }
    }
    this.config = defaultCfg;

    if (typeof sessionStorage !== 'undefined') {
      const rawSession = sessionStorage.getItem(FIREBASE_SESSION_STORAGE_KEY);
      if (rawSession) {
        try {
          this.activeSession = JSON.parse(rawSession) as FirebaseAuthResult;
        } catch {
          this.activeSession = null;
        }
      }
    }
  }

  /**
   * Initializes the Firebase local database tables at startup in dormant/standby mode
   * without generating any network traffic.
   */
  async initializeDormant(): Promise<void> {
    await this.sqliteDb.ensureInitialized();
    this.initialized = true;
  }

  get isInitialized(): boolean {
    return this.initialized;
  }

  get isLiveProjectLinked(): boolean {
    return Boolean(
      this.config.apiKey &&
        this.config.projectId &&
        this.config.projectId !== 'safepocket-local-zk'
    );
  }

  get webConfig(): FirebaseWebConfig {
    return { ...this.config };
  }

  get currentUser(): FirebaseAuthResult | null {
    return this.activeSession ? { ...this.activeSession } : null;
  }

  updateWebConfig(next: Partial<FirebaseWebConfig>): void {
    this.config = {
      apiKey: (next.apiKey ?? this.config.apiKey).trim(),
      projectId:
        (next.projectId ?? this.config.projectId).trim() ||
        'safepocket-local-zk',
      storageBucket:
        (next.storageBucket ?? this.config.storageBucket).trim() ||
        'safepocket-local-zk.firebasestorage.app',
      appId: (next.appId ?? this.config.appId ?? '').trim()
    };
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(
        FIREBASE_CONFIG_STORAGE_KEY,
        JSON.stringify(this.config)
      );
    }
  }

  toEndpointConfig(overrideBearerToken?: string): LiveCloudEndpointConfig {
    return {
      firebaseApiKey: this.config.apiKey || undefined,
      firebaseProjectId: this.isLiveProjectLinked
        ? this.config.projectId
        : undefined,
      firebaseStorageBucket: this.config.storageBucket || undefined,
      firebaseUserId: this.activeSession?.uid,
      bearerToken: overrideBearerToken || this.activeSession?.idToken
    };
  }

  async createCloudUser(
    email: string,
    syncPassword: string,
    fetcher: typeof fetch = fetch
  ): Promise<FirebaseAuthResult> {
    await this.initializeDormant();
    const cleanEmail = email.trim().toLowerCase();
    if (!cleanEmail.includes('@') || syncPassword.length < 6) {
      throw new Error(
        'Valid email and password (min 6 chars) required for Cloud Sync account'
      );
    }

    if (this.isLiveProjectLinked) {
      try {
        const liveUser = await firebaseAuthSignUpRest(
          this.config.apiKey,
          cleanEmail,
          syncPassword,
          fetcher
        );
        this.persistSession(liveUser);
        return liveUser;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        if (
          msg.includes('CONFIGURATION_NOT_FOUND') ||
          msg.includes('OPERATION_NOT_ALLOWED')
        ) {
          throw new Error(
            `Enable Email/Password Sign-In once in Firebase Console (Authentication → Sign-in method) for ${this.config.projectId}`
          );
        }
        throw err;
      }
    }

    const zkAuthPwd = await deriveZeroKnowledgeCloudAuthPassword(
      cleanEmail,
      syncPassword
    );
    const saltHex = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
    const verifierHex = await computeEmbeddedAuthVerifier(saltHex, zkAuthPwd);
    const uid = `fb-uid-${bytesToHex(crypto.getRandomValues(new Uint8Array(8)))}`;

    await this.sqliteDb.createLocalFirebaseUser({
      uid,
      email: cleanEmail,
      authVerifierHex: verifierHex,
      saltHex,
      createdAtIso: new Date().toISOString()
    });

    const embeddedSession: FirebaseAuthResult = {
      uid,
      email: cleanEmail,
      idToken: `local-fb-jwt-${uid}`,
      mode: 'embedded-firebase-db'
    };
    this.persistSession(embeddedSession);
    return embeddedSession;
  }

  async signInCloudUser(
    email: string,
    syncPassword: string,
    fetcher: typeof fetch = fetch
  ): Promise<FirebaseAuthResult> {
    await this.initializeDormant();
    const cleanEmail = email.trim().toLowerCase();

    if (this.isLiveProjectLinked) {
      try {
        const liveUser = await firebaseAuthSignInRest(
          this.config.apiKey,
          cleanEmail,
          syncPassword,
          fetcher
        );
        this.persistSession(liveUser);
        return liveUser;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        if (
          msg.includes('CONFIGURATION_NOT_FOUND') ||
          msg.includes('OPERATION_NOT_ALLOWED')
        ) {
          throw new Error(
            `Enable Email/Password Sign-In once in Firebase Console (Authentication → Sign-in method) for ${this.config.projectId}`
          );
        }
        throw err;
      }
    }

    const existing = await this.sqliteDb.findLocalFirebaseUserByEmail(
      cleanEmail
    );
    if (!existing) {
      throw new Error('USER_NOT_FOUND: No Cloud Sync account found for email');
    }

    const zkAuthPwd = await deriveZeroKnowledgeCloudAuthPassword(
      cleanEmail,
      syncPassword
    );
    const candidateVerifier = await computeEmbeddedAuthVerifier(
      existing.saltHex,
      zkAuthPwd
    );
    if (!verifyEmbeddedAuthDigest(candidateVerifier, existing.authVerifierHex)) {
      throw new Error('INVALID_PASSWORD: Cloud Sync authentication failed');
    }

    const session: FirebaseAuthResult = {
      uid: existing.uid,
      email: existing.email,
      idToken: `local-fb-jwt-${existing.uid}`,
      mode: 'embedded-firebase-db'
    };
    this.persistSession(session);
    return session;
  }

  signOutCloudUser(): void {
    this.activeSession = null;
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem(FIREBASE_SESSION_STORAGE_KEY);
    }
  }

  private persistSession(session: FirebaseAuthResult): void {
    this.activeSession = session;
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(
        FIREBASE_SESSION_STORAGE_KEY,
        JSON.stringify(session)
      );
    }
  }
}
