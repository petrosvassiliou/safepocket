const AUTO_LOCK_STORAGE_KEY = 'safepocket.auto_lock_minutes';
const LOCK_ON_STANDBY_STORAGE_KEY = 'safepocket.lock_on_standby';

export type AutoLockMinutes = 0 | 1 | 5 | 15;

/**
 * Active Inactivity & System Standby Auto-Lock Monitor.
 *
 * - Defaults to 5 minutes of user inactivity (`pointerdown`, `keydown`, `wheel`, `touchstart`),
 *   or `0` (`Never`) when the user disables idle timeout.
 * - Supports `lockOnStandby` (default `true`), locking immediately on system sleep/freeze/hide
 *   (`freeze`, `pagehide`, and `visibilitychange` -> `hidden`).
 * - When triggered, invokes `onLockTimeout()` so the caller immediately wipes the AES-256-GCM
 *   key from RAM, clears revealed fields, and revokes any open attachment Blob URLs.
 */
export class IdleAutoLockMonitor {
  private timeoutMinutes: AutoLockMinutes = 5;
  private standbyLockEnabled = true;
  private timerHandle: ReturnType<typeof setTimeout> | null = null;
  private boundReset: () => void;
  private boundStandbyHandler: () => void;
  private boundVisibilityHandler: () => void;
  private isListening = false;

  constructor(private readonly onLockTimeout: () => void) {
    if (typeof localStorage !== 'undefined') {
      const rawSaved = localStorage.getItem(AUTO_LOCK_STORAGE_KEY);
      if (rawSaved !== null) {
        const saved = Number(rawSaved);
        if (saved === 0 || saved === 1 || saved === 5 || saved === 15) {
          this.timeoutMinutes = saved;
        }
      }
      const rawStandby = localStorage.getItem(LOCK_ON_STANDBY_STORAGE_KEY);
      if (rawStandby !== null) {
        this.standbyLockEnabled = rawStandby !== 'false';
      }
    }
    this.boundReset = () => this.resetIdleTimer();
    this.boundStandbyHandler = () => {
      this.handleSystemStandbyEvent();
    };
    this.boundVisibilityHandler = () => {
      if (
        typeof document !== 'undefined' &&
        document.visibilityState === 'hidden'
      ) {
        this.handleSystemStandbyEvent();
      }
    };
  }

  get minutes(): AutoLockMinutes {
    return this.timeoutMinutes;
  }

  get lockOnStandby(): boolean {
    return this.standbyLockEnabled;
  }

  get hasPendingIdleTimer(): boolean {
    return this.timerHandle !== null;
  }

  setMinutes(minutes: AutoLockMinutes): void {
    this.timeoutMinutes = minutes;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(AUTO_LOCK_STORAGE_KEY, String(minutes));
    }
    this.resetIdleTimer();
  }

  setLockOnStandby(enabled: boolean): void {
    this.standbyLockEnabled = enabled;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(LOCK_ON_STANDBY_STORAGE_KEY, String(enabled));
    }
  }

  handleSystemStandbyEvent(): boolean {
    if (!this.standbyLockEnabled) {
      return false;
    }
    this.triggerImmediateLock();
    return true;
  }

  triggerImmediateLock(): void {
    if (this.timerHandle !== null) {
      clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    this.onLockTimeout();
  }

  start(): void {
    if (typeof window !== 'undefined' && !this.isListening) {
      this.isListening = true;
      for (const evt of ['pointerdown', 'keydown', 'wheel', 'touchstart']) {
        window.addEventListener(evt, this.boundReset, { passive: true });
      }
      window.addEventListener('pagehide', this.boundStandbyHandler);
    }
    if (typeof document !== 'undefined' && this.isListening) {
      document.addEventListener('freeze', this.boundStandbyHandler);
      document.addEventListener('visibilitychange', this.boundVisibilityHandler);
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
      window.removeEventListener('pagehide', this.boundStandbyHandler);
      if (typeof document !== 'undefined') {
        document.removeEventListener('freeze', this.boundStandbyHandler);
        document.removeEventListener('visibilitychange', this.boundVisibilityHandler);
      }
    }
  }

  resetIdleTimer(overrideMs?: number): void {
    if (this.timerHandle !== null) {
      clearTimeout(this.timerHandle);
      this.timerHandle = null;
    }
    if (overrideMs === undefined && this.timeoutMinutes === 0) {
      return;
    }
    const delayMs = overrideMs ?? this.timeoutMinutes * 60 * 1000;
    this.timerHandle = setTimeout(() => {
      this.timerHandle = null;
      this.onLockTimeout();
    }, delayMs);
  }
}
