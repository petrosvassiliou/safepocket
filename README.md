# SafePocket `v0.1`

**Modern Zero-Knowledge Personal Identity, Payment Card, Official Document & Secure Note Vault (`SafePocket v0.1 • Build 1`)**

SafePocket manages personal life administration — **Payment Cards, Bank Accounts, Insurance Cards, Membership Cards, Identities, Official Documents, and Markdown Secure Notes** — across **Android Smartphones, Foldables & Googlebook OS** (`arm64-v8a` + `x86_64`), **ChromeOS / Web Standalone PWA** (100% offline), and **macOS Native Desktop** (Apple HIG). Website logins, password generators, and browser autofill are intentionally excluded by design.

## Key Capabilities in `v0.1`

- **Light Theme Default, Dark & Device Theme Options + Custom Card Colors**:
  - Launches in a crisp Light Material 3 Expressive theme by default, with Dark, Use Device Theme (`system`), Material Design Device Accent (`AccentColor`), 12 curated anodized card palettes, a **Custom Hex Card Color Picker** with WCAG 2.1 auto-contrast, and **Visual Identity Cards** enabled by default.
- **Offline Camera Scanner, Standards-Compliant Barcodes & Full-Screen Presentation Mode**:
  - Offline `getUserMedia` camera viewfinder (`BarcodeDetector` + pure TypeScript 1D Code-128B scanline fallback + image-file upload fallback), real ISO/IEC 15417 **Code 128B**, GS1 **EAN-13**, and ISO/IEC 18004 **QR Code** SVG encoders, encrypted front/back document photo capture, ICAO Doc 9303 MRZ scanner, and full-screen card/barcode presentation with Screen Wake Lock.
- **Configurable Expiry Monitor, Pin-to-Top, Tag Filter Chips & 4-Mode Sorting**:
  - Configurable local renewal horizon (`30 / 60 / 90 / 180` days; `<= 30` days Critical badge), Pin-to-Top, clickable tag chips, and 4 sort modes.
- **Cross-Vault `.safepocket` Merge/Restore & Printable Emergency Recovery Kit v2**:
  - SHA-256 header inspection, cross-vault **Merge** vs **Replace** restore modal, Master-Password-gated JSON/CSV export modal, and A4/Letter printable Emergency Kit v2 with an offline **Secret Key QR code** (`safepocket-recovery://v1`).
- **Local Encrypted Storage by Default + Optional EU Firebase Sync (`safepocket-vault-eu`)**:
  - Defaults to **Local Encrypted Storage Only** (`SQLCipher` on Android/macOS, **SQLite WASM + OPFS** `safepocket_vault_v01.sqlite3` on PWA) with zero outbound network traffic until Cloud Sync is explicitly enabled.
- **6-Language Runtime Localization (`en`, `de`, `it`, `fr`, `es`, `el`) & Signed Self-Updater (`Ed25519` + `SHA-256`)**:
  - Instant runtime switching across 6 languages and Ed25519 + SHA-256 verified updates starting from `v0.1 (Build 1)`.

## Repository Structure

- `ARCHITECTURE.md` — Zero-Knowledge `Argon2id` + `SecretKey` -> `AES-256-GCM` cryptographic design, EU Firebase Sync, and self-updater design.
- `i18n/` — Strongly-typed localization catalogs in **6 languages** (`en`, `de`, `it`, `fr`, `es`, `el`).
- `manifest/version.json` & `scripts/sign-update-manifest.mjs` — Ed25519-signed release manifest initialized at `v0.1 (Build 1)` with localized changelogs and mandatory `SHA-256` checksums.
- `firebase/` & `scripts/setup-firebase.sh` — Zero-knowledge `firestore.rules`, `storage.rules`, `firestore.indexes.json`, `firebase.json`, and 1-command Firebase Cloud project provisioning script.
- `shared/` — **Kotlin Multiplatform (KMP)** core (`VaultModels.kt`, `SafePocketVault.sq`, `ZeroKnowledgeCrypto.kt`, `LocalizationEngine.kt`, `MultilingualSearchEngine.kt`, `CloudSyncEngine.kt`, `FirebaseAuthService.kt`, `FirebaseSyncAdapter.kt`, `GoogleDriveSyncAdapter.kt`, `MrzAndCardValidator.kt`, `VaultPortabilityService.kt`, `EmergencyKitGenerator.kt`, `CrossPlatformUpdateService.kt`).
- `androidApp/` — **Android & Googlebook OS** native Jetpack Compose Material 3 Expressive target (`versionName = "0.1"`, `versionCode = 1`, universal `arm64-v8a` + `x86_64`).
- `macosApp/` & `scripts/package-macos-dmg.sh` — **Native macOS Desktop** SwiftUI + AppKit universal target (`0.1 Build 1`, vibrancy sidebar, unified toolbar, Touch ID, 30s `NSPasteboard` scrub, and `.dmg` packager).
- `pwaApp/` — **100% Offline-Capable ChromeOS / Web Standalone PWA** (TypeScript + Vite + SQLite WASM + OPFS + WebCrypto `AES-256-GCM` & `Argon2id` WASM + Camera/Barcode/QR Engine + 24-test Vitest suite).
- `.github/workflows/` — Automated CI + EU Firebase Hosting CD (`ci-verify.yml`) and multi-platform Direct Distribution Release (`release-direct-distribution.yml` for PWA + Android APK + macOS DMG on `v*` tags starting at `v0.1`).

## Running & Provisioning

```bash
# 1. Run the 24-test Vitest verification suite & build the ChromeOS/Web PWA
cd pwaApp
npm test
npm run build

# 2. (Optional) Link a live Google Cloud Firebase project in 1 command
../scripts/setup-firebase.sh <your-firebase-project-id>

# 3. Re-sign manifest/version.json (defaults to v0.1 Build 1)
node ../scripts/sign-update-manifest.mjs
```
