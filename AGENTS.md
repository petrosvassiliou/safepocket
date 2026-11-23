# SafePocket — Local Agent & Developer Guide (`v0.4.0` / `Build 15`)

This repository contains the complete multi-platform **SafePocket** codebase (`Android / Googlebook OS`, `macOS Native`, and `ChromeOS / Web PWA`) connected to the live EU Firebase project `safepocket-vault-eu` (`eur3`, hosted at `https://safepocket-vault-eu.web.app`).

Read [`ARCHITECTURE.md`](./ARCHITECTURE.md) and [`docs/V040_WALKTHROUGH.md`](./docs/V040_WALKTHROUGH.md) before modifying encryption, sync, or release packaging.

---

## 1. Critical Architectural & Security Invariants (Do Not Break)

1. **Google Play Protect Compliance (`androidApp/`)**:
   - **NEVER** add `android.permission.REQUEST_INSTALL_PACKAGES`, Accessibility, Overlay, SMS, or `android.content.pm.PackageInstaller` sessions back into `androidApp/src/main/AndroidManifest.xml` or `PlayProtectPackageInstaller.kt`. Those trigger Google Play Protect's Android Banking Dropper (`T1106`) false-positive on sideloaded APKs.
2. **Cross-Platform Zero-Knowledge Sync v2 (`cloudKeyHex`)**:
   - Local vaults encrypt on-device databases using the device's `Master Password + Secret Key (SPK-...)`.
   - Cloud Sync encrypts Firestore deltas under `cloudKeyHex` derived deterministically via `100,000` rounds of `PBKDF2-HMAC-SHA256`:
     - Password UTF-8: `"safepocket-zk-cloud-sync-kek-v2:<lowerEmail>:<syncPassword>"`
     - Salt UTF-8: `"safepocket-zk-cloud-salt-v2:<uid>:<lowerEmail>"`
     - Pinned vector (`clara@safepocket.eu` / `MyStrongCloudPass!2026` / `uid_eu_test_12345`): `e5f20ce7e6b0f14c9175cd10bba9dafb54f4e2014d2d7e89b9edb97684df6001`.
   - All three implementations (`shared/.../Argon2idPure.kt`, `macosApp/.../CloudSyncAndPortability.swift`, `pwaApp/src/sync/liveCloudConnectors.ts`) must remain byte-for-byte identical.
3. **Canonical Wire Schema & Pull-First CRDT Sync**:
   - All synced item ciphertexts use bound AAD `safepocket:v1:<CATEGORY>:<ITEM_ID>` and canonical JSON (`VaultItemPayload` / `MacCanonicalWireVaultItem` / `VaultItem`).
   - Hybrid Logical Clocks pack with colon delimiters (`15-digit wallMs:4-digit counter:nodeId`) and normalize legacy hyphens.
   - Sync must remain **Pull-First (`sinceHlc = null`) → CRDT Reconcile → Push-Only-Winning-Local-Deltas**, excluding untouched built-in sample items (`SampleVaultCatalogIds`).
4. **6-Language Localization Parity (`i18n/*.arb.json`)**:
   - Every UI string added or changed must be updated across all 6 catalogs (`en`, `de`, `it`, `fr`, `es`, `el`) so Test 1 & Test 10 in `pwaApp/src/__tests__/v03Features.test.ts` pass.

---

## 2. Version Bump Checklist (Keep All Targets Aligned)

Whenever bumping semantic version / monotonic build code (currently `0.4.0` / `15`), update all of the following together:
1. `pwaApp/src/types.ts` (`APP_BUILD_INFO`)
2. `pwaApp/package.json` (`version`)
3. `pwaApp/public/sw.js` (`CACHE_VERSION`)
4. `shared/src/commonMain/kotlin/com/safepocket/shared/update/CrossPlatformUpdateService.kt` (`SHIPPED_VERSION_NAME`, `SHIPPED_VERSION_CODE`)
5. `androidApp/build.gradle.kts` (`defaultConfig` fallback `versionCode` / `versionName`)
6. `macosApp/SafePocketMac/SafePocketMacApp.swift` (`currentVersion`, `currentVersionCode`, settings version label)
7. `scripts/package-macos-dmg.sh` & `scripts/sign-update-manifest.mjs` + `.github/workflows/release-direct-distribution.yml`
8. Run `node scripts/sign-update-manifest.mjs` and update version assertions in `pwaApp/src/__tests__/safepocket.test.ts` & `pwaApp/src/__tests__/v03Features.test.ts`.

---

## 3. Quick Verification Commands

```bash
# Verify localization parity, crypto vectors, CRDT sync & TypeScript build
npm --prefix pwaApp test && npm --prefix pwaApp run build

# Build & run native macOS app locally (on macOS)
swift run --package-path macosApp SafePocketMac

# Assemble Android debug APK locally (JDK 17 + Gradle 8.11.1+)
gradle :androidApp:assembleDebug
```

---

## 4. Roadmap Context from Cloud Handoff

- **Completed in `v0.4.0` (`Build 15`)**: Play Protect compliance + robust, instant Cross-Platform Cloud Sync v2 across Android, macOS, and PWA.
- **Next Planned Focus**: Once `v0.4.0` stability & sync are confirmed across devices, proceed with **UI/UX redesign & new feature discussions** (the user noted the current UI needs a thorough visual/UX overhaul next).
