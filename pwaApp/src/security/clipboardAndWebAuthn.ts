/**
 * 30-Second Sensitive Clipboard Hygiene Controller & WebAuthn Passkey Biometric Unlock.
 */
export class ClipboardHygieneController {
  private countdownTimer: ReturnType<typeof setInterval> | null = null;
  private remainingSeconds = 0;
  private lastCopiedSecret: string | null = null;

  constructor(
    private readonly onTick: (secondsRemaining: number | null, cleared: boolean) => void,
    private readonly ttlSeconds = 30
  ) {}

  async copyValue(value: string, isSensitive: boolean): Promise<void> {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(value);
      } catch {
        this.fallbackCopy(value);
      }
    } else {
      this.fallbackCopy(value);
    }

    if (!isSensitive) {
      return;
    }

    this.clearTimerOnly();
    this.lastCopiedSecret = value;
    this.remainingSeconds = this.ttlSeconds;
    this.onTick(this.remainingSeconds, false);

    this.countdownTimer = setInterval(() => {
      this.remainingSeconds -= 1;
      if (this.remainingSeconds <= 0) {
        void this.scrubClipboardNow();
      } else {
        this.onTick(this.remainingSeconds, false);
      }
    }, 1000);
  }

  async scrubClipboardNow(): Promise<void> {
    this.clearTimerOnly();
    this.lastCopiedSecret = null;
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText('');
      } catch {
        // Ignore focus permission restriction when tab is backgrounded
      }
    }
    this.onTick(null, true);
  }

  private clearTimerOnly(): void {
    if (this.countdownTimer !== null) {
      clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    }
  }

  private fallbackCopy(text: string): void {
    if (typeof document === 'undefined') return;
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
  }
}

/**
 * ChromeOS / WebAuthn Platform Authenticator (Fingerprint / Titan Security Key / Passkey) Unlock.
 */
export class WebAuthnBiometricUnlock {
  private static readonly CRED_STORAGE_KEY = 'safepocket.webauthn_cred_id';

  static isSupported(): boolean {
    return (
      typeof window !== 'undefined' &&
      typeof window.PublicKeyCredential !== 'undefined'
    );
  }

  static hasEnrolledPasskey(): boolean {
    if (typeof localStorage === 'undefined') return false;
    return Boolean(localStorage.getItem(WebAuthnBiometricUnlock.CRED_STORAGE_KEY));
  }

  static unenrollPasskey(): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(WebAuthnBiometricUnlock.CRED_STORAGE_KEY);
    }
  }

  static async enrollPlatformBiometric(): Promise<boolean> {
    if (!WebAuthnBiometricUnlock.isSupported()) return false;
    const challenge = crypto.getRandomValues(new Uint8Array(32));
    const userId = crypto.getRandomValues(new Uint8Array(16));

    const credential = (await navigator.credentials.create({
      publicKey: {
        challenge,
        rp: { name: 'SafePocket Vault' },
        user: {
          id: userId,
          name: 'vault-owner@safepocket.local',
          displayName: 'SafePocket Local Vault Owner'
        },
        pubKeyCredParams: [
          { type: 'public-key', alg: -7 },  // ES256
          { type: 'public-key', alg: -257 } // RS256
        ],
        authenticatorSelection: {
          authenticatorAttachment: 'platform',
          userVerification: 'required'
        },
        timeout: 45_000
      }
    })) as PublicKeyCredential | null;

    if (!credential) return false;
    const rawIdBytes = new Uint8Array(credential.rawId);
    const idB64 = btoa(String.fromCharCode(...rawIdBytes));
    localStorage.setItem(WebAuthnBiometricUnlock.CRED_STORAGE_KEY, idB64);
    return true;
  }

  static async authenticateBiometric(): Promise<boolean> {
    if (!WebAuthnBiometricUnlock.isSupported()) return false;
    const challenge = crypto.getRandomValues(new Uint8Array(32));
    const savedIdB64 =
      typeof localStorage !== 'undefined'
        ? localStorage.getItem(WebAuthnBiometricUnlock.CRED_STORAGE_KEY)
        : null;
    let allowCredentials: PublicKeyCredentialDescriptor[] | undefined;
    if (savedIdB64) {
      try {
        const bin = atob(savedIdB64);
        const rawId = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) {
          rawId[i] = bin.charCodeAt(i);
        }
        allowCredentials = [{ type: 'public-key', id: rawId }];
      } catch {
        allowCredentials = undefined;
      }
    }
    const assertion = await navigator.credentials.get({
      publicKey: {
        challenge,
        allowCredentials,
        userVerification: 'required',
        timeout: 45_000
      }
    });
    return Boolean(assertion);
  }
}
