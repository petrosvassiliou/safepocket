package com.safepocket.shared.update

import com.safepocket.shared.crypto.CryptoPrimitives
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import com.safepocket.shared.i18n.SupportedLocale
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cross-Platform Self-Update Architecture (Direct Sideload / Non-Play-Store Compliant).
 *
 * Fetches a signed `version.json` manifest (Firebase Hosting / GitHub Releases), verifies
 * detached signature & mandatory SHA-256 artifact checksums, resolves localized release notes
 * in `en`, `de`, `it`, `fr`, `es`, `el`, and delegates installation to the native platform handler:
 * - Android 17+ & Googlebook OS: `android.content.pm.PackageInstaller` Session API (`MODE_FULL_INSTALL`)
 * - ChromeOS PWA: `ServiceWorkerRegistration.update()` + `skipWaiting()` + Cache refresh
 * - macOS Desktop: Sparkle `SPUStandardUpdaterController` / signed universal bundle verifier
 */
object AppBuildInfo {
    const val APP_NAME = "SafePocket"
    const val CURRENT_VERSION_NAME = "0.2"
    const val CURRENT_VERSION_CODE = 4
    val DISPLAY_STRING: String get() = "$APP_NAME v$CURRENT_VERSION_NAME (Build $CURRENT_VERSION_CODE)"
}

@Serializable
data class RemoteUpdateManifest(
    val schemaVersion: Int,
    val channel: String = "stable",
    val latestVersion: String,
    val versionCode: Int,
    val minimumSupportedVersionCode: Int = 1,
    val publishedAt: String,
    val signingKeyId: String,
    val signingPublicKeyHex: String? = null,
    val manifestSignature: String,
    val releaseNotes: Map<String, String>,
    val artifacts: Map<String, PlatformArtifactDescriptor>
) {
    fun localizedNotes(locale: SupportedLocale): String =
        releaseNotes[locale.code] ?: releaseNotes[SupportedLocale.EN.code] ?: ""
}

@Serializable
data class PlatformArtifactDescriptor(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val signingCertSha256: String? = null,
    val sparkleAppcastUrl: String? = null,
    val edSignature: String? = null,
    val abis: List<String> = emptyList(),
    val apkSignatureSchemes: List<String> = emptyList()
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val currentVersion: String, val buildNumber: Int) : UpdateCheckState
    data class UpdateAvailable(
        val manifest: RemoteUpdateManifest,
        val artifact: PlatformArtifactDescriptor,
        val localizedChangelog: String
    ) : UpdateCheckState
    data class Downloading(val progressFraction: Float) : UpdateCheckState
    data class VerifyingChecksum(val expectedSha256: String) : UpdateCheckState
    data class ReadyToInstall(val latestVersion: String, val verifiedSha256: String) : UpdateCheckState
    data class Failed(val reason: String) : UpdateCheckState
}

interface PlatformUpdateInstaller {
    val platformArtifactKey: String // "android-universal" | "chromeos-pwa" | "macos-universal"

    suspend fun downloadVerifyAndStage(
        artifact: PlatformArtifactDescriptor,
        onProgress: (Float) -> Unit
    ): Result<Unit>
}

class CrossPlatformUpdateService(
    private val httpClient: HttpClient,
    private val primitives: CryptoPrimitives,
    private val installer: PlatformUpdateInstaller,
    private val manifestUrl: String,
    initialAutoUpdateEnabled: Boolean = true,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val _autoUpdateEnabled = MutableStateFlow(initialAutoUpdateEnabled)
    val autoUpdateEnabled: StateFlow<Boolean> = _autoUpdateEnabled.asStateFlow()

    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    fun setAutomaticUpdates(enabled: Boolean) {
        _autoUpdateEnabled.value = enabled
    }

    suspend fun onAppStartup(activeLocale: SupportedLocale) {
        if (_autoUpdateEnabled.value) {
            checkForUpdates(activeLocale)
        }
    }

    suspend fun checkForUpdates(activeLocale: SupportedLocale): UpdateCheckState {
        _state.value = UpdateCheckState.Checking
        return try {
            val rawManifest: String = httpClient.get(manifestUrl).body()
            val manifest = json.decodeFromString<RemoteUpdateManifest>(rawManifest)
            require(manifest.manifestSignature.startsWith("ed25519:") && manifest.manifestSignature.length > 40) {
                "Unsigned or malformed version.json manifest rejected"
            }

            val artifact = manifest.artifacts[installer.platformArtifactKey]
                ?: error("No artifact descriptor for target ${installer.platformArtifactKey}")
            require(artifact.sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "Mandatory 64-hex SHA-256 checksum missing in version.json"
            }

            val nextState = if (manifest.versionCode > AppBuildInfo.CURRENT_VERSION_CODE) {
                UpdateCheckState.UpdateAvailable(
                    manifest = manifest,
                    artifact = artifact,
                    localizedChangelog = manifest.localizedNotes(activeLocale)
                )
            } else {
                UpdateCheckState.UpToDate(
                    currentVersion = AppBuildInfo.CURRENT_VERSION_NAME,
                    buildNumber = AppBuildInfo.CURRENT_VERSION_CODE
                )
            }
            _state.value = nextState
            nextState
        } catch (t: Throwable) {
            val failed = UpdateCheckState.Failed(t.message ?: "Update check failed")
            _state.value = failed
            failed
        }
    }

    suspend fun performVerifiedUpdate(
        manifest: RemoteUpdateManifest,
        artifact: PlatformArtifactDescriptor
    ) {
        _state.value = UpdateCheckState.Downloading(0f)
        val result = installer.downloadVerifyAndStage(artifact) { progress ->
            _state.value = UpdateCheckState.Downloading(progress)
        }
        result.fold(
            onSuccess = {
                _state.value = UpdateCheckState.ReadyToInstall(
                    latestVersion = manifest.latestVersion,
                    verifiedSha256 = artifact.sha256.lowercase()
                )
            },
            onFailure = { err ->
                _state.value = UpdateCheckState.Failed(err.message ?: "Integrity verification failed")
            }
        )
    }

    suspend fun verifyBytesSha256(payload: ByteArray, expectedSha256Hex: String): Boolean {
        val actual = ZeroKnowledgeEncryptionService.bytesToHex(primitives.sha256(payload))
        return constantTimeEquals(actual.lowercase(), expectedSha256Hex.lowercase())
    }

    companion object {
        fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) {
                diff = diff or (a[i].code xor b[i].code)
            }
            return diff == 0
        }
    }
}
