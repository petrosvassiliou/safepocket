export type SafePocketErrorCategory =
  | 'CRYPTO_DECRYPTION'
  | 'DATABASE_STORAGE'
  | 'CLOUD_AUTH'
  | 'CLOUD_SYNC_NETWORK'
  | 'IMPORT_EXPORT'
  | 'AUTO_UPDATE'
  | 'SCANNER_MEDIA'
  | 'UNEXPECTED';

export interface SafePocketAppError {
  id: string;
  category: SafePocketErrorCategory;
  operation: string;
  userTitle: string;
  message: string;
  recoverable: boolean;
  timestampIso: string;
}

export interface DiagnosticLogEntry {
  id: string;
  timestampIso: string;
  level: 'INFO' | 'WARN' | 'ERROR';
  category: SafePocketErrorCategory | 'SYSTEM';
  operation: string;
  scrubbedDetail: string;
}

const MAX_DIAGNOSTIC_EVENTS = 80;

/**
 * Privacy-preserving scrubber: redacts card PANs, IBANs, SPK Secret Keys,
 * Bearer/JWT tokens, and long hex secrets before storing in memory diagnostics.
 */
export function scrubSensitiveDiagnosticText(raw: string): string {
  if (!raw) return '';
  return raw
    .replace(/\bSPK(?:-[A-Z0-9]{4,6}){3,8}\b/gi, 'SPK-[REDACTED]')
    .replace(/\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}(?:\s?[A-Z0-9]{1,4})?\b/g, '[IBAN-REDACTED]')
    .replace(/\b(?:\d[ -]*?){13,19}\b/g, '[PAN-REDACTED]')
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[JWT-REDACTED]')
    .replace(/Bearer\s+[A-Za-z0-9._~+/-]+=*/gi, 'Bearer [TOKEN-REDACTED]')
    .replace(/\b[a-f0-9]{48,}\b/gi, '[HEX-REDACTED]');
}

export function classifySafePocketError(
  err: unknown,
  operation: string,
  preferredCategory?: SafePocketErrorCategory
): SafePocketAppError {
  const rawMessage =
    err instanceof Error
      ? err.message
      : typeof err === 'string'
        ? err
        : 'Unexpected operation error';
  const scrubbed = scrubSensitiveDiagnosticText(rawMessage);
  const lower = scrubbed.toLowerCase();

  let category: SafePocketErrorCategory = preferredCategory ?? 'UNEXPECTED';
  if (!preferredCategory) {
    if (
      lower.includes('decrypt') ||
      lower.includes('gcm') ||
      lower.includes('argon2') ||
      lower.includes('verifier') ||
      lower.includes('secret key') ||
      lower.includes('master password')
    ) {
      category = 'CRYPTO_DECRYPTION';
    } else if (
      lower.includes('sqlite') ||
      lower.includes('opfs') ||
      lower.includes('quota') ||
      lower.includes('database')
    ) {
      category = 'DATABASE_STORAGE';
    } else if (
      lower.includes('email_exists') ||
      lower.includes('invalid_login') ||
      lower.includes('invalid_password') ||
      lower.includes('sign-in') ||
      lower.includes('auth') ||
      lower.includes('401') ||
      lower.includes('403')
    ) {
      category = 'CLOUD_AUTH';
    } else if (
      lower.includes('sync') ||
      lower.includes('firestore') ||
      lower.includes('network') ||
      lower.includes('offline') ||
      lower.includes('fetch')
    ) {
      category = 'CLOUD_SYNC_NETWORK';
    } else if (
      lower.includes('1pux') ||
      lower.includes('csv') ||
      lower.includes('import') ||
      lower.includes('export') ||
      lower.includes('safepocket')
    ) {
      category = 'IMPORT_EXPORT';
    } else if (
      lower.includes('ed25519') ||
      lower.includes('manifest') ||
      lower.includes('update') ||
      lower.includes('sha-256')
    ) {
      category = 'AUTO_UPDATE';
    } else if (
      lower.includes('camera') ||
      lower.includes('mrz') ||
      lower.includes('barcode')
    ) {
      category = 'SCANNER_MEDIA';
    }
  }

  const userTitleByCategory: Record<SafePocketErrorCategory, string> = {
    CRYPTO_DECRYPTION: 'Vault Encryption & Key Check',
    DATABASE_STORAGE: 'Encrypted Storage Notice',
    CLOUD_AUTH: 'Cloud Account Authentication',
    CLOUD_SYNC_NETWORK: 'Cloud Sync & Connectivity',
    IMPORT_EXPORT: 'Backup / Import / Export',
    AUTO_UPDATE: 'Cryptographic Update Verifier',
    SCANNER_MEDIA: 'Document & Camera Scanner',
    UNEXPECTED: 'SafePocket Error Guard'
  };

  return {
    id: `err-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    category,
    operation,
    userTitle: userTitleByCategory[category],
    message: scrubbed,
    recoverable: category !== 'UNEXPECTED',
    timestampIso: new Date().toISOString()
  };
}

export class SafePocketDiagnosticsCenter {
  private readonly entries: DiagnosticLogEntry[] = [];
  private listeners = new Set<() => void>();
  private globalInstalled = false;

  recordInfo(operation: string, detail: string): void {
    this.push({
      id: `diag-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      timestampIso: new Date().toISOString(),
      level: 'INFO',
      category: 'SYSTEM',
      operation,
      scrubbedDetail: scrubSensitiveDiagnosticText(detail)
    });
  }

  recordWarning(
    operation: string,
    detail: string,
    category: SafePocketErrorCategory | 'SYSTEM' = 'SYSTEM'
  ): void {
    this.push({
      id: `diag-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      timestampIso: new Date().toISOString(),
      level: 'WARN',
      category,
      operation,
      scrubbedDetail: scrubSensitiveDiagnosticText(detail)
    });
  }

  recordError(
    err: unknown,
    operation: string,
    preferredCategory?: SafePocketErrorCategory
  ): SafePocketAppError {
    const classified = classifySafePocketError(
      err,
      operation,
      preferredCategory
    );
    this.push({
      id: classified.id,
      timestampIso: classified.timestampIso,
      level: 'ERROR',
      category: classified.category,
      operation,
      scrubbedDetail: classified.message
    });
    return classified;
  }

  getEntries(): ReadonlyArray<DiagnosticLogEntry> {
    return this.entries;
  }

  clear(): void {
    this.entries.length = 0;
    this.notify();
  }

  formatReport(): string {
    if (this.entries.length === 0) {
      return 'SafePocket Diagnostics Log (0 events)';
    }
    return this.entries
      .map(
        (e) =>
          `[${e.timestampIso}] [${e.level}] [${e.category}] ${e.operation}: ${e.scrubbedDetail}`
      )
      .join('\n');
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  installGlobalErrorBoundaries(
    onUnhandledAppError?: (appError: SafePocketAppError) => void
  ): void {
    if (this.globalInstalled || typeof window === 'undefined') return;
    this.globalInstalled = true;

    window.addEventListener('error', (event) => {
      const appErr = this.recordError(
        event.error ?? event.message ?? 'Window error',
        'window.onerror'
      );
      onUnhandledAppError?.(appErr);
    });

    window.addEventListener('unhandledrejection', (event) => {
      const appErr = this.recordError(
        event.reason ?? 'Unhandled Promise rejection',
        'window.unhandledrejection'
      );
      onUnhandledAppError?.(appErr);
    });
  }

  private push(entry: DiagnosticLogEntry): void {
    this.entries.unshift(entry);
    if (this.entries.length > MAX_DIAGNOSTIC_EVENTS) {
      this.entries.length = MAX_DIAGNOSTIC_EVENTS;
    }
    this.notify();
  }

  private notify(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }
}
