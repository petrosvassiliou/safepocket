# SafePocket `v0.4.0` (`Build 15`)

**Cross-Platform Zero-Knowledge Personal Identity, Payment Card, Official Document & Secure Note Vault**

SafePocket manages personal life administration — **Payment Cards, Bank Accounts, Insurance Cards, Membership Cards, Identities, Official Documents, and Markdown Secure Notes** — across:
- **macOS Native Desktop** (`Apple Silicon` + `Intel` universal SwiftUI/AppKit app)
- **Android Smartphones, Foldables & Googlebook OS** (`arm64-v8a` + `x86_64` Jetpack Compose Material 3 Adaptive app, Google Play Protect compliant)
- **ChromeOS & Web Standalone PWA** (100% offline-capable TypeScript + SQLite WASM + OPFS app, hosted at `https://safepocket-vault-eu.web.app`)

Website logins, password generators, and browser autofill are excluded by design.

---

## Opening SafePocket as a Local Antigravity Workspace

1. **Clone the repository onto your machine:**
   ```bash
   git clone https://github.com/petrosvassiliou/safepocket.git
   cd safepocket
   ```
2. **Open in Antigravity Desktop:**
   - Launch **Antigravity** on your Mac/Linux/Windows machine.
   - Choose **File → Open Folder…** (or select **Local** instead of Cloud VM in the project picker) and open the cloned `safepocket` directory.
   - Local agents will automatically read [`AGENTS.md`](./AGENTS.md), [`ARCHITECTURE.md`](./ARCHITECTURE.md), and [`docs/V040_WALKTHROUGH.md`](./docs/V040_WALKTHROUGH.md) for full architectural and release history.

---

## Local Prerequisites & Build / Run Commands

### 1. ChromeOS / Web PWA (`pwaApp/`)
- **Prerequisites:** Node.js `20+` (Node `24 LTS` recommended) and `npm`.
- **Commands:**
  ```bash
  # Install dependencies
  npm --prefix pwaApp ci

  # Run the 45-test cross-platform verification suite (Vitest)
  npm --prefix pwaApp test

  # Start local Vite dev server (http://127.0.0.1:4173)
  npm --prefix pwaApp run dev

  # Type-check & build production offline bundle (pwaApp/dist)
  npm --prefix pwaApp run build
  ```

### 2. Native macOS App (`macosApp/`)
- **Prerequisites:** macOS `14.0+` (Sonoma / Sequoia / Tahoe) with Xcode `16+` or Xcode Command Line Tools (`swift --version`).
- **Commands:**
  ```bash
  # Build & launch debug app directly via Swift Package Manager
  swift run --package-path macosApp SafePocketMac

  # Package universal (arm64 + x86_64) SafePocket.app + UDZO .dmg installer
  ./scripts/package-macos-dmg.sh
  ```

### 3. Native Android & Googlebook OS App (`androidApp/` + `shared/`)
- **Prerequisites:** JDK `17` (Temurin 17 recommended), Android SDK (`compileSdk = 35`, `minSdk = 29`), and Gradle `8.11.1+` (or Android Studio Ladybug / Meerkat+).
- **Commands:**
  ```bash
  # Option A: Open the repository root in Android Studio and run the `androidApp` configuration.

  # Option B: Build from terminal with Gradle 8.11.1+
  gradle :androidApp:assembleDebug

  # Install debug APK onto a connected Android device / emulator
  adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk

  # Build release universal APK (uses debug fallback signing locally unless SAFEPOCKET_KEYSTORE_PATH is set)
  gradle :androidApp:assembleRelease --no-daemon
  ```

### 4. Release Manifest Signing & Firebase Cloud Provisioning (`scripts/`)
```bash
# Re-compute SHA-256 digests and sign manifest/version.json + pwaApp/public/version.json
node scripts/sign-update-manifest.mjs

# (Optional) Deploy Firestore & Storage zero-knowledge rules to a Firebase project
./scripts/setup-firebase.sh safepocket-vault-eu
```

---

## Repository Structure

- [`AGENTS.md`](./AGENTS.md) — Workspace instructions, cryptographic/sync invariants, and version-bump checklist for local AI agents.
- [`ARCHITECTURE.md`](./ARCHITECTURE.md) — Zero-Knowledge local & Cross-Platform Cloud Sync v2 specification, CRDT reconciliation, and Ed25519 update architecture.
- [`docs/V040_WALKTHROUGH.md`](./docs/V040_WALKTHROUGH.md) — Detailed root-cause walkthrough of `v0.4.0` (`Build 15`) Play Protect compliance and Cross-Platform Cloud Sync v2.
- [`i18n/`](./i18n) — Strongly-typed localization catalogs across **6 languages** (`en`, `de`, `it`, `fr`, `es`, `el` — 320 keys each).
- [`shared/`](./shared) — **Kotlin Multiplatform (KMP)** core (`VaultModels.kt`, `SafePocketVault.sq`, `Argon2idPure.kt`, `ZeroKnowledgeCrypto.kt`, `CloudSyncEngine.kt`, `FirebaseAuthService.kt`, `FirebaseSyncAdapter.kt`, `GoogleDriveSyncAdapter.kt`, `CrossPlatformUpdateService.kt`).
- [`androidApp/`](./androidApp) — **Android & Googlebook OS** native Jetpack Compose Material 3 Adaptive app.
- [`macosApp/`](./macosApp) — **macOS Native Desktop** SwiftUI + AppKit universal app (`NavigationSplitView`, Keychain + Touch ID, `NSPasteboard` 30s auto-scrub, in-app DMG updater).
- [`pwaApp/`](./pwaApp) — **Offline-Capable ChromeOS / Web PWA** (TypeScript, Vite, `sql.js` SQLite WASM + OPFS, WebCrypto + `hash-wasm`, 45-test Vitest suite).
- [`firebase/`](./firebase) — Zero-knowledge `firestore.rules`, `storage.rules`, and `firestore.indexes.json` deployed to `safepocket-vault-eu` (`eur3`).
- [`.github/workflows/`](./.github/workflows) — Automated CI + EU Firebase Hosting deploy (`ci-verify.yml`) and multi-platform Direct Distribution Release (`release-direct-distribution.yml` triggered on `v*` git tags).
