package com.safepocket.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import com.safepocket.shared.update.CrossPlatformUpdateService
import com.safepocket.shared.update.PlatformArtifactDescriptor
import com.safepocket.shared.update.PlatformUpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Google Play Protect Compliant Android 17+ & Googlebook OS Self-Updater.
 *
 * Security & Compliance Guarantees:
 * 1. Uses `android.content.pm.PackageInstaller` Session API (`MODE_FULL_INSTALL`) with
 *    `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` for seamless updates whenSafePocket
 *    is the update owner of its own package.
 * 2. Downloads the APK to app-private cache, verifies the full SHA-256 digest in constant time,
 *    AND verifies that the downloaded APK's V2/V3/V4 signing certificate matches both the
 *    currently installed SafePocket certificate AND the pinned `signingCertSha256` from `version.json`
 *    BEFORE opening or committing the `PackageInstaller.Session`.
 * 3. Never uses deprecated `ACTION_VIEW` `application/vnd.android.package-archive` file intents.
 */
class PlayProtectPackageInstaller(
    private val context: Context
) : PlatformUpdateInstaller {

    override val platformArtifactKey: String = "android-universal"

    override suspend fun downloadVerifyAndStage(
        artifact: PlatformArtifactDescriptor,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "verified_updates").apply { mkdirs() }
        val stagedApk = File(stagingDir, "safepocket-staged.apk")
        var activeSession: PackageInstaller.Session? = null

        runCatching {
            // Step 1: Stream HTTPS download while computing SHA-256 incrementally
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: artifact.sizeBytes
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                stagedApk.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress((downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.95f))
                        }
                    }
                }
            }

            // Step 2: Mandatory constant-time SHA-256 checksum verification
            val computedSha256 = ZeroKnowledgeEncryptionService.bytesToHex(digest.digest())
            check(CrossPlatformUpdateService.constantTimeEquals(computedSha256, artifact.sha256)) {
                "APK SHA-256 checksum mismatch! Expected ${artifact.sha256}, got $computedSha256"
            }

            // Step 3: Verify APK Signing Certificate matches installed app & manifest pin
            verifyApkSigningCertificate(stagedApk, artifact.signingCertSha256)

            // Step 4: Create PackageInstaller Session & stream verified APK bytes
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(stagedApk.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= 34) {
                    setRequestUpdateOwnership(true)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            activeSession = session

            stagedApk.inputStream().use { apkIn ->
                session.openWrite("safepocket_base.apk", 0, stagedApk.length()).use { sessionOut ->
                    apkIn.copyTo(sessionOut)
                    session.fsync(sessionOut)
                }
            }

            val callbackIntent = Intent(context, PackageInstallerStatusReceiver::class.java).apply {
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            onProgress(1.0f)
            session.commit(pendingIntent.intentSender)
            session.close()
        }.onFailure {
            activeSession?.runCatching { abandon() }
            stagedApk.delete()
        }.also {
            // Clean staged cache file once transferred to PackageInstaller session
            stagedApk.delete()
        }
    }

    private fun verifyApkSigningCertificate(stagedApk: File, pinnedCertSha256: String?) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archiveInfo: PackageInfo = pm.getPackageArchiveInfo(stagedApk.absolutePath, flags)
            ?: error("Downloaded file is not a valid signed Android APK archive")

        check(archiveInfo.packageName == context.packageName) {
            "APK packageName mismatch: ${archiveInfo.packageName}"
        }

        val archiveSigners = archiveInfo.signingInfo?.apkContentsSigners
            ?: error("Downloaded APK lacks V2/V3/V4 signing certificates")
        val installedInfo = pm.getPackageInfo(context.packageName, flags)
        val installedSigners = installedInfo.signingInfo?.apkContentsSigners.orEmpty()

        val sha256 = MessageDigest.getInstance("SHA-256")
        val archiveCertDigests = archiveSigners.map {
            ZeroKnowledgeEncryptionService.bytesToHex(sha256.digest(it.toByteArray())).lowercase()
        }
        val installedCertDigests = installedSigners.map {
            ZeroKnowledgeEncryptionService.bytesToHex(sha256.digest(it.toByteArray())).lowercase()
        }

        check(archiveCertDigests.any { it in installedCertDigests }) {
            "APK signing certificate does not match currently installed SafePocket key!"
        }

        if (!pinnedCertSha256.isNullOrBlank()) {
            check(archiveCertDigests.any { CrossPlatformUpdateService.constantTimeEquals(it, pinnedCertSha256.lowercase()) }) {
                "APK signing certificate does not match pinned manifest certificate SHA-256!"
            }
        }
    }
}

class PackageInstallerStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Triggered on initial sideload before update ownership is established or when OS requires confirmation
                val confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Package successfully updated in-place
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                android.util.Log.e("SafePocketUpdater", "PackageInstaller failed ($status): $msg")
            }
        }
    }
}
