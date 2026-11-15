# SafePocket `v0.1`

**Modern Zero-Knowledge Personal Identity, Payment Card, Official Document & Secure Note Vault (`SafePocket v0.1 • Build 1`)**

SafePocket manages personal life administration, physical wallet cards, official IDs/passports, and Markdown secure notes across **Android 17+ Smartphones, Foldables & Googlebook OS** (`arm64-v8a` + `x86_64`), **ChromeOS Standalone PWA** (100% offline), and **macOS Native Desktop** (Apple HIG). Website logins, password generators, and browser autofill are intentionally excluded by design.

## Key Capabilities in `v0.1`

- **Local Encrypted Storage by Default + Pre-Initialized Firebase Sync**:
  - Defaults to **Local Encrypted Storage Only** (`SQLCipher` on Android/macOS, **SQLite WASM + OPFS** `safepocket_vault_v01.sqlite3` on ChromeOS PWA) with zero outbound network traffic.
  - The **Firebase Zero-Knowledge Database & Auth Engine** (`FirebaseVaultService`) is present and initialized in standby from `v0.1` boot even while Cloud Sync is disabled.
  - Enabling Cloud Sync defaults to **Firebase** (supporting **Zero-Knowledge User Creation**, **Sign-In**, and **Two-Way CRDT Push/Pull Sync**) with **Google Drive (`appDataFolder`)** available as an optional alternative.
- **6-Language Runtime Localization (`en`, `de`, `it`, `fr`, `es`, `el`)**:
  - Instant runtime language switching without restart, 35% text expansion resilience, diacritic/Greek-insensitive client-side search (`< 50ms`), and regional Identity Tax ID templates (*Steuer-ID*, *Codice Fiscale*, *ΑΦΜ (AFM)*, *NIF / NIE*, *Numéro fiscal / NIR*).
- **Offline ICAO Doc 9303 MRZ Document Scanner & Card Validators**:
  - 100% client-side offline MRZ parser for **TD3 Passports (`2×44`)** and **TD1 National IDs (`3×30`)** with 7-3-1 check-digit verification, plus offline **Luhn PAN** and **ISO-13616 Mod-97 IBAN** checksum validators.
- **Google Play Protect Compliant Self-Updater (`Ed25519` + `SHA-256`)**:
  - Real WebCrypto / native **Ed25519** detached manifest signature verification (`manifest/version.json`) + mandatory **SHA-256** artifact verification across Android `PackageInstaller.Session`, ChromeOS Service Worker `skipWaiting()`, and macOS Sparkle.

## Repository Structure

- `ARCHITECTURE.md` — Flutter vs. KMP evaluation, Zero-Knowledge `Argon2id` + `SecretKey` -> `AES-256-GCM` cryptographic design, pre-initialized Firebase Auth/Sync, and self-updater design.
- `i18n/` — Strongly-typed localization catalogs in **6 languages** (`en`, `de`, `it`, `fr`, `es`, `el`).
- `manifest/version.json` & `scripts/sign-update-manifest.mjs` — Ed25519-signed remote update manifest targeting `v0.1.1 (Build 2)` with localized changelogs and mandatory `SHA-256` checksums.
- `firebase/` & `scripts/setup-firebase.sh` — Zero-knowledge `firestore.rules`, `storage.rules`, `firestore.indexes.json`, `firebase.json`, and 1-command Firebase Cloud project provisioning script.
- `shared/` — **Kotlin Multiplatform (KMP)** core (`VaultModels.kt`, `SafePocketVault.sq`, `ZeroKnowledgeCrypto.kt`, `LocalizationEngine.kt`, `MultilingualSearchEngine.kt`, `CloudSyncEngine.kt`, `FirebaseAuthService.kt`, `FirebaseSyncAdapter.kt`, `GoogleDriveSyncAdapter.kt`, `MrzAndCardValidator.kt`, `VaultPortabilityService.kt`, `CrossPlatformUpdateService.kt`).
- `androidApp/` — **Android 17+ & Googlebook OS** native Jetpack Compose Material 3 Expressive target (`versionName = "0.1"`, `versionCode = 1`, `resizeableActivity="true"`, `PlayProtectPackageInstaller.kt`, `AdaptiveSafePocketShell.kt`).
- `macosApp/` — **Native macOS Desktop** SwiftUI + AppKit target (`SafePocketMacApp.swift`, `MacVaultSplitView.swift` with vibrancy sidebar, unified toolbar, Touch ID, 30s `NSPasteboard` scrub, and Sparkle update integration).
- `pwaApp/` — **100% Offline-Capable ChromeOS Standalone PWA** (TypeScript + Vite + SQLite WASM + OPFS + WebCrypto `AES-256-GCM` & `Argon2id` WASM + Firebase Auth/Sync + MRZ Scanner + 14-test Vitest suite).
- `.github/workflows/` — Automated CI (`ci-verify.yml`) and Ed25519-signed Direct Distribution Release (`release-direct-distribution.yml`) pipelines.

## Running & Provisioning

```bash
# 1. Run the 14-test Vitest verification suite & build the ChromeOS PWA
cd pwaApp
npm test
npm run build

# 2. (Optional) Link a live Google Cloud Firebase project in 1 command
../scripts/setup-firebase.sh <your-firebase-project-id>

# 3. Re-sign manifest/version.json after updating release artifacts
npm run sign:manifest
```
