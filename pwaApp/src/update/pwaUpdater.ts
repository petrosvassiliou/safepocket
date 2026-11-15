import {
  constantTimeHexEquals,
  hexToBytes,
  sha256Hex
} from '../crypto/vaultCrypto';
import {
  APP_BUILD_INFO,
  type RemoteUpdateManifest,
  type SupportedLocale
} from '../types';

export interface PwaUpdateStatus {
  phase:
    | 'idle'
    | 'checking'
    | 'up_to_date'
    | 'available'
    | 'verifying'
    | 'ready_to_reload'
    | 'error';
  progressPercent: number;
  manifest?: RemoteUpdateManifest;
  verifiedSha256?: string;
  ed25519Verified?: boolean;
  errorMessage?: string;
}

const AUTO_UPDATE_STORAGE_KEY = 'safepocket.auto_updates_enabled';

export const PINNED_RELEASE_ED25519_PUBKEY_HEX =
  'b793fdd180549d2d3f226fb13798fa85e2f85901d44c20fa85c2a6bde25420be';

export function buildCanonicalManifestMessage(
  manifest: RemoteUpdateManifest
): string {
  const artifactPairs = Object.keys(manifest.artifacts)
    .sort()
    .map(
      (k) =>
        `${k}:${manifest.artifacts[k].sha256.toLowerCase()}:${manifest.artifacts[k].sizeBytes}`
    )
    .join(';');
  return `safepocket-manifest-v2|${manifest.channel}|${manifest.latestVersion}|${manifest.versionCode}|${manifest.minimumSupportedVersionCode}|${artifactPairs}`;
}

export async function verifyManifestEd25519Signature(
  manifest: RemoteUpdateManifest,
  pinnedPublicKeyHex = PINNED_RELEASE_ED25519_PUBKEY_HEX
): Promise<boolean> {
  if (
    !manifest.manifestSignature ||
    !manifest.manifestSignature.startsWith('ed25519:')
  ) {
    return false;
  }
  const sigHex = manifest.manifestSignature.slice('ed25519:'.length);
  if (sigHex.length !== 128) return false;

  const pubKeyHex = manifest.signingPublicKeyHex || pinnedPublicKeyHex;
  if (!constantTimeHexEquals(pubKeyHex, pinnedPublicKeyHex)) {
    return false;
  }

  try {
    const pubKey = await crypto.subtle.importKey(
      'raw',
      hexToBytes(pinnedPublicKeyHex) as BufferSource,
      { name: 'Ed25519' },
      false,
      ['verify']
    );
    const messageBytes = new TextEncoder().encode(
      buildCanonicalManifestMessage(manifest)
    );
    return await crypto.subtle.verify(
      { name: 'Ed25519' },
      pubKey,
      hexToBytes(sigHex) as BufferSource,
      messageBytes
    );
  } catch {
    return false;
  }
}

/**
 * ChromeOS PWA Self-Update Controller.
 *
 * 1. Fetches signed `version.json` manifest and cryptographically verifies its detached Ed25519 signature + SHA-256 field.
 * 2. Hooks into `ServiceWorkerRegistration.update()` for both automatic startup checks and manual checks.
 * 3. Downloads target Service Worker / bundle payload, computes WebCrypto SHA-256 in constant-time comparison,
 *    posts `SKIP_WAITING` + `REFRESH_OFFLINE_CACHE` to any waiting ServiceWorker, and triggers clean reload.
 */
export class PwaUpdateController {
  private registration: ServiceWorkerRegistration | null = null;
  private status: PwaUpdateStatus = { phase: 'idle', progressPercent: 0 };
  private autoUpdateEnabled = true;
  private listeners = new Set<(status: PwaUpdateStatus) => void>();

  constructor(private readonly manifestUrl = '/version.json') {
    if (typeof localStorage !== 'undefined') {
      const saved = localStorage.getItem(AUTO_UPDATE_STORAGE_KEY);
      if (saved !== null) {
        this.autoUpdateEnabled = saved === 'true';
      }
    }
  }

  get currentStatus(): PwaUpdateStatus {
    return this.status;
  }

  get isAutoUpdateEnabled(): boolean {
    return this.autoUpdateEnabled;
  }

  setAutoUpdateEnabled(enabled: boolean): void {
    this.autoUpdateEnabled = enabled;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(AUTO_UPDATE_STORAGE_KEY, String(enabled));
    }
  }

  subscribe(listener: (status: PwaUpdateStatus) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async registerServiceWorkerAndAutoCheck(): Promise<void> {
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator) {
      try {
        this.registration = await navigator.serviceWorker.register('/sw.js', {
          scope: '/'
        });
      } catch {
        // Non-fatal in sandboxed iframe/test contexts
      }
    }
    if (this.autoUpdateEnabled) {
      await this.checkForUpdates();
    }
  }

  async checkForUpdates(
    customFetcher: typeof fetch = fetch
  ): Promise<PwaUpdateStatus> {
    this.emit({ phase: 'checking', progressPercent: 15 });
    try {
      if (this.registration) {
        await this.registration.update();
      }
      const res = await customFetcher(this.manifestUrl, { cache: 'no-store' });
      if (!res.ok) {
        throw new Error(`Manifest HTTP ${res.status}`);
      }
      const manifest = (await res.json()) as RemoteUpdateManifest;

      const sigValid = await verifyManifestEd25519Signature(manifest);
      if (!sigValid) {
        throw new Error(
          'Ed25519 manifest signature verification failed — untrusted update rejected'
        );
      }

      const artifact = manifest.artifacts['chromeos-pwa'];
      if (!artifact || !/^[a-fA-F0-9]{64}$/.test(artifact.sha256)) {
        throw new Error('Missing mandatory 64-hex SHA-256 in version.json');
      }

      if (manifest.versionCode > APP_BUILD_INFO.versionCode) {
        this.emit({
          phase: 'available',
          progressPercent: 40,
          manifest,
          ed25519Verified: true
        });
      } else {
        this.emit({
          phase: 'up_to_date',
          progressPercent: 100,
          manifest,
          ed25519Verified: true
        });
      }
      return this.status;
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Update check failed';
      this.emit({ phase: 'error', progressPercent: 0, errorMessage: msg });
      return this.status;
    }
  }

  async verifyChecksumAndStageServiceWorker(
    customFetcher: typeof fetch = fetch
  ): Promise<PwaUpdateStatus> {
    const manifest = this.status.manifest;
    if (!manifest) {
      throw new Error('Check for updates before staging');
    }
    const artifact = manifest.artifacts['chromeos-pwa'];
    this.emit({
      phase: 'verifying',
      progressPercent: 65,
      manifest,
      ed25519Verified: true
    });

    try {
      const res = await customFetcher(artifact.url, { cache: 'no-store' });
      if (!res.ok) {
        throw new Error(`Artifact download HTTP ${res.status}`);
      }
      const bytes = new Uint8Array(await res.arrayBuffer());
      const computedSha256 = await sha256Hex(bytes);

      if (!constantTimeHexEquals(computedSha256, artifact.sha256)) {
        throw new Error(
          `SHA-256 tampering detected! Expected ${artifact.sha256}, got ${computedSha256}`
        );
      }

      if (this.registration?.waiting) {
        this.registration.waiting.postMessage({ type: 'SKIP_WAITING' });
        this.registration.waiting.postMessage({ type: 'REFRESH_OFFLINE_CACHE' });
      } else if (this.registration?.active) {
        this.registration.active.postMessage({ type: 'REFRESH_OFFLINE_CACHE' });
      }

      this.emit({
        phase: 'ready_to_reload',
        progressPercent: 100,
        manifest,
        verifiedSha256: computedSha256,
        ed25519Verified: true
      });
      return this.status;
    } catch (err) {
      const msg =
        err instanceof Error ? err.message : 'SHA-256 verification failed';
      this.emit({
        phase: 'error',
        progressPercent: 0,
        manifest,
        errorMessage: msg
      });
      return this.status;
    }
  }

  localizedReleaseNotes(locale: SupportedLocale): string {
    const notes = this.status.manifest?.releaseNotes;
    if (!notes) return '';
    return notes[locale] ?? notes.en ?? '';
  }

  activateAndReload(): void {
    if (this.registration?.waiting) {
      this.registration.waiting.postMessage({ type: 'SKIP_WAITING' });
    }
    if (typeof window !== 'undefined') {
      window.location.reload();
    }
  }

  private emit(next: PwaUpdateStatus): void {
    this.status = next;
    for (const listener of this.listeners) {
      listener(next);
    }
  }
}
