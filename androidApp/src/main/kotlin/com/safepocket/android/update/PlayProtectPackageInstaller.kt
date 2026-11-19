package com.safepocket.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.safepocket.shared.update.PlatformArtifactDescriptor
import com.safepocket.shared.update.PlatformUpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Play Protect Clean Android & Googlebook OS Update Launcher.
 *
 * Why SafePocket intentionally avoids `REQUEST_INSTALL_PACKAGES` and `PackageInstaller.Session`:
 * Google Play Protect's static and ML classifiers flag sideloaded financial/vault APKs that declare
 * `REQUEST_INSTALL_PACKAGES` + download `.apk` binaries over HTTP as Banking Dropper PHAs ("Harmful app").
 *
 * Instead:
 * 1. `CrossPlatformUpdateService` cryptographically verifies `version.json` using RFC 8032 Ed25519
 *    against the pinned SafePocket public key and validates the 64-hex SHA-256 artifact digest.
 * 2. When the user initiates an update, SafePocket opens the verified HTTPS GitHub Release asset URL
 *    in the system browser (`Intent.ACTION_VIEW`), delegating download & installation to Android's
 *    trusted browser + native Google Play Protect verified system installer with zero dropper permissions.
 */
class PlayProtectPackageInstaller(
    private val context: Context
) : PlatformUpdateInstaller {

    override val platformArtifactKey: String = "android-universal"

    override suspend fun downloadVerifyAndStage(
        artifact: PlatformArtifactDescriptor,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val cleanUrl = artifact.url.trim()
            require(cleanUrl.startsWith("https://github.com/petrosvassiliou/safepocket/releases/")) {
                "Untrusted update host rejected: $cleanUrl"
            }
            require(artifact.sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "Invalid SHA-256 digest in update descriptor"
            }
            onProgress(0.5f)
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            onProgress(1.0f)
        }
    }
}
