import type { VaultHeaderMetadata } from '../types';

const VAULT_HEADER_KEY = 'safepocket.vault_header_v1';

/**
 * Origin Private File System (OPFS) Encrypted Binary Store & Vault Header Persistence.
 *
 * On ChromeOS / Chromium browsers, persists encrypted `.enc` attachment blobs (`AES-256-GCM`)
 * directly inside OPFS (`navigator.storage.getDirectory() -> safepocket_blobs/<id>.enc`),
 * falling back seamlessly to in-memory byte maps in headless / non-OPFS test runtimes.
 */
export class OpfsEncryptedAttachmentStore {
  private memoryFallback = new Map<string, Uint8Array>();

  private async getBlobsDirHandle(): Promise<FileSystemDirectoryHandle | null> {
    if (
      typeof navigator === 'undefined' ||
      !navigator.storage ||
      typeof navigator.storage.getDirectory !== 'function'
    ) {
      return null;
    }
    try {
      const root = await navigator.storage.getDirectory();
      return await root.getDirectoryHandle('safepocket_blobs', { create: true });
    } catch {
      return null;
    }
  }

  async writeEncryptedBlob(
    attachmentId: string,
    encryptedBytes: Uint8Array
  ): Promise<'opfs' | 'memory'> {
    this.memoryFallback.set(attachmentId, encryptedBytes.slice());
    const dir = await this.getBlobsDirHandle();
    if (!dir) return 'memory';
    try {
      const fileHandle = await dir.getFileHandle(`${attachmentId}.enc`, {
        create: true
      });
      const writable = await fileHandle.createWritable();
      await writable.write(encryptedBytes as BufferSource);
      await writable.close();
      return 'opfs';
    } catch {
      return 'memory';
    }
  }

  async readEncryptedBlob(attachmentId: string): Promise<Uint8Array | null> {
    const dir = await this.getBlobsDirHandle();
    if (dir) {
      try {
        const fileHandle = await dir.getFileHandle(`${attachmentId}.enc`);
        const file = await fileHandle.getFile();
        return new Uint8Array(await file.arrayBuffer());
      } catch {
        // Fall through to memory cache
      }
    }
    return this.memoryFallback.get(attachmentId) ?? null;
  }

  async deleteEncryptedBlob(attachmentId: string): Promise<void> {
    this.memoryFallback.delete(attachmentId);
    const dir = await this.getBlobsDirHandle();
    if (!dir) return;
    try {
      await dir.removeEntry(`${attachmentId}.enc`);
    } catch {
      // Ignore if absent
    }
  }
}

export class VaultHeaderStore {
  private memoryHeader: VaultHeaderMetadata | null = null;

  loadHeader(): VaultHeaderMetadata | null {
    if (typeof localStorage !== 'undefined') {
      const raw = localStorage.getItem(VAULT_HEADER_KEY);
      if (raw) {
        try {
          return JSON.parse(raw) as VaultHeaderMetadata;
        } catch {
          return null;
        }
      }
    }
    return this.memoryHeader;
  }

  saveHeader(header: VaultHeaderMetadata): void {
    this.memoryHeader = header;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(VAULT_HEADER_KEY, JSON.stringify(header));
    }
  }

  clearHeader(): void {
    this.memoryHeader = null;
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(VAULT_HEADER_KEY);
    }
  }
}
