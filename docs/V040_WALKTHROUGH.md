# SafePocket `v0.4.0` (`Build 15`) — Stability, Google Play Protect Compliance & Cross-Platform Sync v2 Walkthrough

> [!IMPORTANT]
> **Upgrading from `v0.3.x` to `v0.4.0`:**
> 1. **Android Play Protect Cache:** If Google Play Protect previously cached a warning on your device for an older `v0.3.x` APK, uninstall the old APK once before installing `SafePocket-v0.4.0-b15-universal-arm64-x86_64.apk`.
> 2. **Cloud Sync v2 Key Activation:** Sign out and sign back in once (`Settings → Cloud Sync`) with **Email + Cloud Sync Password** on each device so every device derives the shared deterministic `cloudKeyHex`.

---

## 1. Google Play Protect "Harmful App" Flag — Root Cause & Fix

- **Root Cause:** `v0.3.x` declared `android.permission.REQUEST_INSTALL_PACKAGES` and `PackageInstallerStatusReceiver` in `androidApp/src/main/AndroidManifest.xml` while downloading `.apk` binaries over `HttpURLConnection` into `PackageInstaller.Session`. Google Play Protect's ML heuristics classify non-Play-Store vault/financial APKs with `REQUEST_INSTALL_PACKAGES` + self-downloading APK code as **Android Banking Droppers (`T1106`)**.
- **Fix:** Removed `REQUEST_INSTALL_PACKAGES`, `PackageInstallerStatusReceiver`, and all `PackageInstaller` code from `AndroidManifest.xml`, `PlayProtectPackageInstaller.kt`, and `MainActivity.kt`. Manifest Ed25519 verification remains intact in `CrossPlatformUpdateService.kt`, and tapping **Update** opens the verified `https://github.com/petrosvassiliou/safepocket/releases/...` URL in the system browser (`Intent.ACTION_VIEW`).

---

## 2. All 8 Cross-Platform Cloud Sync Root Causes Fixed

Live HTTPS verification against `safepocket-vault-eu` (`eur3`) confirmed Firebase Auth and Firestore security rules were healthy (`HTTP 200 OK`); all sync failures were client-side mismatches across Android, macOS, and PWA:

| # | Root Cause in `v0.3.x` | Fix in `v0.4.0` (`Cross-Platform Sync v2`) |
|---|---|---|
| **1** | **Per-device random Secret Keys & incompatible KDFs** encrypted Firestore rows differently on every device and silently swallowed AES-GCM tag errors. | Deterministic 100,000-round `PBKDF2-HMAC-SHA256` `cloudKeyHex` derived from `(email, syncPassword, uid)` in `Argon2idPure.kt`, `CloudSyncAndPortability.swift`, and `liveCloudConnectors.ts` (pinned vector `e5f20ce7e6b0f14c9175cd10bba9dafb54f4e2014d2d7e89b9edb97684df6001`). |
| **2** | **macOS incompatible JSON wire format** (`MacVaultEntry` used Apple `CFAbsoluteTime` floats, lacked `schemaVersion`/epoch-ms integers, and emitted `URL` enum absent in Kotlin/TS). | Added `MacCanonicalWireVaultItem` in `CloudSyncAndPortability.swift` and added `URL` to `FieldValueType` in `VaultModels.kt` and `pwaApp/src/types.ts`. |
| **3** | **Push-before-Pull** overwrote newer remote edits with stale local snapshots. | Implemented **Pull-First (`sinceHlc = null`) → CRDT Reconcile → Push-Only-Winning-Local-Deltas** across `AndroidVaultRepository.kt`, `SafePocketMacApp.swift`, and `syncAdapters.ts`. |
| **4** | **Local DB cipher mixed with Cloud cipher** on Android & PWA. | Clean cipher translation via `resolveCloudSyncKey32()` on Android and `CloudCipherBridge` on PWA (`appShell.ts` + `syncAdapters.ts`), with automatic self-migration of `v0.3.x` envelopes. |
| **5** | **HLC `-` vs `:` mismatch & `HybridLogicalClock.unpack` crash** + `sinceCursor` clock-skew blindness. | Standardized `:` HLC format (`15-digit wallMs:4-digit counter:nodeId`) with legacy `-` normalization in `CloudSyncEngine.kt`, `SafePocketMacApp.swift`, and `syncAdapters.ts`, plus full delta pulls (`sinceHlc = null`). |
| **6** | **Deleted items resurrected on macOS** (0 tombstones pushed) & **PWA hardcoded `{ 'chromeos-pwa-node': 1 }`**. | Added persistent `MacPendingTombstone` + `knownRemoteTombstones` on macOS, and unique per-browser `pwaNodeId` + monotonic `nextLocalVersionVector` on PWA. |
| **7** | **PWA lost session on tab close (`sessionStorage`) & faked sync in RAM** when unauthenticated. | Migrated session storage to `localStorage` in `firebaseVaultService.ts` and enforced bearer-token check in `FirebaseZeroKnowledgeAdapter`. |
| **8** | **Missing startup/focus/live sync & sample-data pollution**. | Excluded untouched built-in sample items from Cloud pushes and added **instant push on save/delete + startup sync + focus/resume sync + flicker-free 5-second live background sync** on all 3 platforms. |

---

## 3. Earlier Completed UI & macOS Polish Milestones (`v0.3.7` – `v0.3.9`)

- **`v0.3.7` (`Build 12`)**: Centered sidebar footer icons, `+ New` category dropdown button (removing category selector inside the item editor), side-by-side 2-column `Lokale DB` vs `Cloud-Sync` storage cards with non-destructive toggling, elevated modal footers, and top-anchored hover-highlighted right-click context menus.
- **`v0.3.8` (`Build 13`)**: Moved macOS `[ + ⌄ ]` (`macNewItemCategoryMenu`) into the middle pane's top toolbar (`vaultListContent.toolbar`).
- **`v0.3.9` (`Build 14`)**: Upgraded `macosApp/Resources/AppIcon-1024.png` to Apple's exact `862 × 862` G2 continuous squircle proportions with Liquid Glass specular rim and live Dock tile refresh (`NSApplication.shared.applicationIconImage`).
