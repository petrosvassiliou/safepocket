const AUTO_LOCK_STORAGE_KEY = 'safepocket.auto_lock_minutes';

export type AutoLockMinutes = 1 | 5 | 15;

/**
 * Active Inactivity Auto-Lock Monitor.
 *
 * Defaults to 5 minutes of user inactivity (`pointerdown`, `keydown`, `wheel`, `touchstart`).
 * When triggered, invokes `onLockTimeout()` so the caller immediately wipes the AES-256-GCM
 * key from RAM, clears revealed fields, and revokes any open attachment Blob URLs.
 */
export class IdleAutoLockMonitor {
  private timeoutMinutes: AutoLockMinutes = 5;
  private timerHandle: ReturnType<typeof setTimeout> | null = null;
  private boundReset: () => void;
  private isListening = false;

  constructor(private readonly onLockTimeout: () => void) {
    if (typeof localStorage !== 'undefined') {
      const saved = Number(localStorage.getItem(AUTO_LOCK_STORAGE_KEY));
      if (saved === 1 || saved === 5 || saved === 15) {
        this.timeoutMinutes = saved;
      }
    }
    this.boundReset = () => this.resetIdleTimer();
  }

  get minutes(): AutoLockMinutes {
    return this.timeoutMinutes;
  }

  setMinutes(minutes: AutoLockMinutes): void {
    this.timeoutMinutes = minutes;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(AUTO_LOCK_STORAGE_KEY, String(minutes));
    }
    this.resetIdleTimer();
  }

  start(): void {
    if (typeof window !== 'undefined' && !this.isListening) {
      this.isListening = true;
      for (const evt of ['pointerdown', 'keydown', 'wheel', 'touchstart']) {
        window.addEventListener(evt, this.boundReset, { passive: true });
      }
    }
    this.resetIdleTimer();
  }

  stop(): void {
    if (this.timerHandle !== null) {
      clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    if (typeof window !== 'undefined' && this.isListening) {
      this.isListening = false;
      for (const evt of ['pointerdown', 'keydown', 'wheel', 'touchstart']) {
        window.removeEventListener(evt, this.boundReset);
      }
    }
  }

  resetIdleTimer(overrideMs?: number): void {
    if (this.timerHandle !== null) {
      clearTimeout(this.timerHandle);
    }
    const delayMs = overrideMs ?? this.timeoutMinutes * 60 * 1000;
    this.timerHandle = setTimeout(() => {
      this.timerHandle = null;
      this.onLockTimeout();
    }, delayMs);
  }
}
