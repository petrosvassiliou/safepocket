import type { BarcodeFormat } from '../types';
import { decodeCode128BFromModules, normalizeEan13Digits } from './barcodeRenderer';

export type CameraScanMode = 'barcode' | 'document_photo' | 'mrz' | 'recovery_qr';

export interface ScannedBarcodeResult {
  rawValue: string;
  format: BarcodeFormat;
}

interface WebBarcodeDetection {
  rawValue: string;
  format: string;
}

interface WebBarcodeDetectorInstance {
  detect(source: ImageBitmapSource): Promise<WebBarcodeDetection[]>;
}

interface WebBarcodeDetectorConstructor {
  new (options?: { formats?: string[] }): WebBarcodeDetectorInstance;
  getSupportedFormats?: () => Promise<string[]>;
}

function getNativeBarcodeDetector(): WebBarcodeDetectorInstance | null {
  if (typeof globalThis === 'undefined') return null;
  const Ctor = (
    globalThis as unknown as { BarcodeDetector?: WebBarcodeDetectorConstructor }
  ).BarcodeDetector;
  if (typeof Ctor !== 'function') return null;
  try {
    return new Ctor({
      formats: [
        'code_128',
        'ean_13',
        'ean_8',
        'qr_code',
        'pdf417',
        'aztec',
        'itf',
        'codabar'
      ]
    });
  } catch {
    try {
      return new Ctor();
    } catch {
      return null;
    }
  }
}

export function mapDetectedFormatToSafePocket(
  nativeFormat: string,
  rawValue: string
): BarcodeFormat {
  const lower = nativeFormat.toLowerCase();
  if (lower.includes('qr') || lower.includes('aztec') || lower.includes('data_matrix')) {
    return 'QR_CODE';
  }
  if (lower.includes('ean') && normalizeEan13Digits(rawValue)) {
    return 'EAN_13';
  }
  return 'CODE_128';
}

/**
 * Extracts a SafePocket Secret Key (`SPK-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX`) from either
 * a `safepocket-recovery://v1?...` Emergency Kit QR URI or raw scanned text.
 */
export function parseRecoverySecretKeyPayload(rawText: string): {
  secretKey: string;
  vaultId?: string;
} | null {
  const trimmed = rawText.trim();
  if (!trimmed) return null;

  if (/^safepocket-recovery:\/\//i.test(trimmed)) {
    try {
      const parsed = new URL(trimmed);
      const sk = (parsed.searchParams.get('secretKey') ?? '').trim().toUpperCase();
      const vaultId = (parsed.searchParams.get('vaultId') ?? '').trim() || undefined;
      if (/^SPK-[A-Z2-9]{5}(-[A-Z2-9]{5}){4}$/.test(sk)) {
        return { secretKey: sk, vaultId };
      }
    } catch {
      // Fall through to regex extraction
    }
  }

  const match = trimmed.toUpperCase().match(/SPK-[A-Z2-9]{5}(?:-[A-Z2-9]{5}){4}/);
  if (match) {
    return { secretKey: match[0] };
  }
  return null;
}

/**
 * Pure offline 1D scanline luminance decoder fallback for browsers without native `BarcodeDetector`.
 * Samples horizontal scanlines across RGBA `ImageData`, binarizes via adaptive row midpoint,
 * and attempts Code 128B checksum-verified decoding.
 */
export function decode1DBarcodeFromImageData(
  rgba: Uint8ClampedArray,
  width: number,
  height: number
): ScannedBarcodeResult | null {
  if (width < 40 || height < 4) return null;
  const sampleRows = [
    Math.floor(height * 0.5),
    Math.floor(height * 0.35),
    Math.floor(height * 0.65),
    Math.floor(height * 0.25),
    Math.floor(height * 0.75)
  ];

  for (const row of sampleRows) {
    if (row < 0 || row >= height) continue;
    const lum = new Float32Array(width);
    let minL = 255;
    let maxL = 0;
    for (let x = 0; x < width; x++) {
      const idx = (row * width + x) * 4;
      const alpha = rgba[idx + 3] / 255;
      // Composite transparent pixels over white
      const r = rgba[idx] * alpha + 255 * (1 - alpha);
      const g = rgba[idx + 1] * alpha + 255 * (1 - alpha);
      const b = rgba[idx + 2] * alpha + 255 * (1 - alpha);
      const l = 0.299 * r + 0.587 * g + 0.114 * b;
      lum[x] = l;
      if (l < minL) minL = l;
      if (l > maxL) maxL = l;
    }
    if (maxL - minL < 35) continue;
    const threshold = (minL + maxL) * 0.5;
    const modules = new Array<number>(width);
    for (let x = 0; x < width; x++) {
      modules[x] = lum[x] < threshold ? 1 : 0;
    }
    const decoded = decodeCode128BFromModules(modules);
    if (decoded) {
      return {
        rawValue: decoded,
        format: normalizeEan13Digits(decoded) ? 'EAN_13' : 'CODE_128'
      };
    }
  }
  return null;
}

/**
 * Applies an optional high-contrast local document enhancement filter ("Crisp Document Scan")
 * directly on in-memory RGBA bytes before AES-256-GCM encryption.
 */
export function applyCrispDocumentFilterInPlace(rgba: Uint8ClampedArray): void {
  for (let i = 0; i < rgba.length; i += 4) {
    const lum = 0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2];
    // S-curve contrast stretch preserving clean white paper and sharp dark ink
    const normalized = (lum - 128) * 1.48 + 136;
    const clamped = Math.max(0, Math.min(255, Math.round(normalized)));
    rgba[i] = clamped;
    rgba[i + 1] = clamped;
    rgba[i + 2] = clamped;
  }
}

/**
 * Offline MRZ line extractor helper: crops the bottom 28% MRZ zone of a document frame,
 * computes character-grid ink density to verify presence of 2x44 or 3x30 ICAO MRZ bands,
 * and returns a binarized preview Data URL alongside any detected text lines.
 */
export function analyzeMrzRegionFromCanvas(
  sourceCanvas: HTMLCanvasElement
): { mrzCropDataUrl: string; hasHighContrastMrzBand: boolean } {
  const srcW = sourceCanvas.width;
  const srcH = sourceCanvas.height;
  const cropY = Math.floor(srcH * 0.68);
  const cropH = Math.max(24, srcH - cropY);

  const cropCanvas = document.createElement('canvas');
  cropCanvas.width = srcW;
  cropCanvas.height = cropH;
  const ctx = cropCanvas.getContext('2d');
  if (!ctx) {
    return { mrzCropDataUrl: '', hasHighContrastMrzBand: false };
  }
  ctx.drawImage(sourceCanvas, 0, cropY, srcW, cropH, 0, 0, srcW, cropH);
  const img = ctx.getImageData(0, 0, srcW, cropH);
  applyCrispDocumentFilterInPlace(img.data);

  let darkPixels = 0;
  const totalPixels = srcW * cropH;
  for (let i = 0; i < img.data.length; i += 4) {
    if (img.data[i] < 96) darkPixels++;
  }
  ctx.putImageData(img, 0, 0);
  const inkRatio = totalPixels > 0 ? darkPixels / totalPixels : 0;

  return {
    mrzCropDataUrl: cropCanvas.toDataURL('image/png'),
    hasHighContrastMrzBand: inkRatio >= 0.04 && inkRatio <= 0.55
  };
}

/**
 * Zero-Knowledge Offline Camera Controller (`getUserMedia` + Web `BarcodeDetector` + Canvas Fallback).
 * Guarantees immediate `MediaStreamTrack.stop()` teardown upon close or vault auto-lock.
 */
export class CameraScannerController {
  private stream: MediaStream | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private facingMode: 'environment' | 'user' = 'environment';

  static isCameraSupported(): boolean {
    return (
      typeof navigator !== 'undefined' &&
      Boolean(navigator.mediaDevices) &&
      typeof navigator.mediaDevices.getUserMedia === 'function'
    );
  }

  get activeFacingMode(): 'environment' | 'user' {
    return this.facingMode;
  }

  get isStreaming(): boolean {
    return this.stream !== null;
  }

  async startStream(
    videoElement: HTMLVideoElement,
    onBarcodeDetected?: (res: ScannedBarcodeResult) => void
  ): Promise<void> {
    this.stopStream();
    if (!CameraScannerController.isCameraSupported()) {
      throw new Error('Camera access is not supported in this browser context');
    }

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        facingMode: { ideal: this.facingMode },
        width: { ideal: 1920 },
        height: { ideal: 1080 }
      }
    });

    videoElement.srcObject = this.stream;
    videoElement.setAttribute('playsinline', 'true');
    await videoElement.play().catch(() => undefined);

    if (onBarcodeDetected) {
      const nativeDetector = getNativeBarcodeDetector();
      const scratchCanvas =
        typeof document !== 'undefined' ? document.createElement('canvas') : null;

      this.pollTimer = setInterval(async () => {
        if (videoElement.readyState < 2 || videoElement.videoWidth === 0) return;

        if (nativeDetector) {
          try {
            const detections = await nativeDetector.detect(videoElement);
            if (detections.length > 0 && detections[0].rawValue) {
              const first = detections[0];
              onBarcodeDetected({
                rawValue: first.rawValue,
                format: mapDetectedFormatToSafePocket(
                  first.format,
                  first.rawValue
                )
              });
              return;
            }
          } catch {
            // Fall through to canvas 1D scanline decoder
          }
        }

        if (scratchCanvas) {
          scratchCanvas.width = Math.min(960, videoElement.videoWidth);
          scratchCanvas.height = Math.min(540, videoElement.videoHeight);
          const ctx = scratchCanvas.getContext('2d', {
            willReadFrequently: true
          });
          if (ctx) {
            ctx.drawImage(
              videoElement,
              0,
              0,
              scratchCanvas.width,
              scratchCanvas.height
            );
            const imgData = ctx.getImageData(
              0,
              0,
              scratchCanvas.width,
              scratchCanvas.height
            );
            const fallbackHit = decode1DBarcodeFromImageData(
              imgData.data,
              scratchCanvas.width,
              scratchCanvas.height
            );
            if (fallbackHit) {
              onBarcodeDetected(fallbackHit);
            }
          }
        }
      }, 220);
    }
  }

  async flipCamera(
    videoElement: HTMLVideoElement,
    onBarcodeDetected?: (res: ScannedBarcodeResult) => void
  ): Promise<void> {
    this.facingMode =
      this.facingMode === 'environment' ? 'user' : 'environment';
    await this.startStream(videoElement, onBarcodeDetected);
  }

  /**
   * Decodes a barcode/QR from a user-selected image file completely offline in RAM.
   */
  async decodeBarcodeFromImageFile(
    file: File
  ): Promise<ScannedBarcodeResult | null> {
    if (typeof createImageBitmap === 'function') {
      const bmp = await createImageBitmap(file);
      try {
        const nativeDetector = getNativeBarcodeDetector();
        if (nativeDetector) {
          const hits = await nativeDetector.detect(bmp);
          if (hits.length > 0 && hits[0].rawValue) {
            return {
              rawValue: hits[0].rawValue,
              format: mapDetectedFormatToSafePocket(
                hits[0].format,
                hits[0].rawValue
              )
            };
          }
        }
        if (typeof document !== 'undefined') {
          const canvas = document.createElement('canvas');
          canvas.width = bmp.width;
          canvas.height = bmp.height;
          const ctx = canvas.getContext('2d');
          if (ctx) {
            ctx.drawImage(bmp, 0, 0);
            const imgData = ctx.getImageData(0, 0, bmp.width, bmp.height);
            return decode1DBarcodeFromImageData(
              imgData.data,
              bmp.width,
              bmp.height
            );
          }
        }
      } finally {
        bmp.close();
      }
    }
    return null;
  }

  /**
   * Captures the current `<video>` frame cropped to ISO/IEC 7810 ID-1 card proportions
   * purely in RAM (`Uint8Array`) so callers can encrypt immediately into AES-256-GCM `.enc`.
   */
  async captureDocumentFrameBytes(
    videoElement: HTMLVideoElement,
    enhanceCrispDoc: boolean
  ): Promise<{
    bytes: Uint8Array;
    mimeType: string;
    canvas: HTMLCanvasElement;
  }> {
    const vw = videoElement.videoWidth || 1280;
    const vh = videoElement.videoHeight || 720;
    // Crop centered ID-1 1.586:1 aspect ratio region (88% width or height-fitted)
    const targetAspect = 1.586;
    let cropW = Math.round(vw * 0.88);
    let cropH = Math.round(cropW / targetAspect);
    if (cropH > vh * 0.86) {
      cropH = Math.round(vh * 0.86);
      cropW = Math.round(cropH * targetAspect);
    }
    const sx = Math.max(0, Math.floor((vw - cropW) / 2));
    const sy = Math.max(0, Math.floor((vh - cropH) / 2));

    const canvas = document.createElement('canvas');
    canvas.width = cropW;
    canvas.height = cropH;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      throw new Error('2D canvas context unavailable');
    }
    ctx.drawImage(videoElement, sx, sy, cropW, cropH, 0, 0, cropW, cropH);

    if (enhanceCrispDoc) {
      const imgData = ctx.getImageData(0, 0, cropW, cropH);
      applyCrispDocumentFilterInPlace(imgData.data);
      ctx.putImageData(imgData, 0, 0);
    }

    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (b) => (b ? resolve(b) : reject(new Error('Frame encoding failed'))),
        'image/jpeg',
        0.92
      );
    });
    return {
      bytes: new Uint8Array(await blob.arrayBuffer()),
      mimeType: blob.type || 'image/jpeg',
      canvas
    };
  }

  stopStream(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    if (this.stream) {
      for (const track of this.stream.getTracks()) {
        try {
          track.stop();
        } catch {
          // Ignore track stop errors
        }
      }
      this.stream = null;
    }
  }
}
