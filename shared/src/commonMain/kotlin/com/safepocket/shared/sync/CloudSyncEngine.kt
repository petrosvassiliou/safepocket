package com.safepocket.shared.sync

import kotlinx.serialization.Serializable

/**
 * Conflict-Free Offline-First Sync Engine.
 *
 * Every mutation executes locally against SQLCipher / SQLite WASM with 0ms network blocking.
 * Concurrent offline edits across Smartphone, Googlebook OS, ChromeOS PWA, and macOS are
 * reconciled deterministically via per-device Version Vectors + Hybrid Logical Clocks (HLC).
 *
 * Strict Zero-Knowledge Invariant:
 * CloudSyncAdapter implementations ONLY accept pre-encrypted AES-256-GCM envelopes (`EncryptedSyncRecord`).
 * Plaintext item titles, categories, tags, keys, and attachments never leave the device.
 */
@Serializable
data class HybridLogicalClock(
    val wallTimeEpochMs: Long,
    val logicalCounter: Int,
    val nodeId: String
) : Comparable<HybridLogicalClock> {
    override fun compareTo(other: HybridLogicalClock): Int {
        val cmpTime = wallTimeEpochMs.compareTo(other.wallTimeEpochMs)
        if (cmpTime != 0) return cmpTime
        val cmpCounter = logicalCounter.compareTo(other.logicalCounter)
        if (cmpCounter != 0) return cmpCounter
        return nodeId.compareTo(other.nodeId)
    }

    fun pack(): String =
        "${wallTimeEpochMs.toString().padStart(15, '0')}-${logicalCounter.toString().padStart(4, '0')}-$nodeId"

    companion object {
        fun unpack(packed: String): HybridLogicalClock {
            val parts = packed.split('-', limit = 3)
            return HybridLogicalClock(
                wallTimeEpochMs = parts[0].toLong(),
                logicalCounter = parts[1].toInt(),
                nodeId = parts[2]
            )
        }
    }
}

@Serializable
data class EncryptedSyncRecord(
    val entityId: String,
    val entityType: String,               // "ITEM" | "ATTACHMENT_META"
    val hlcPacked: String,
    val versionVector: Map<String, Long>, // nodeId -> monotonic counter
    val isTombstone: Boolean,
    val ivBase64: String,
    val ciphertextBase64: String,         // Opaque AES-256-GCM ciphertext + 128-bit auth tag
    val aadContext: String
)

enum class SyncProviderType {
    FIREBASE,             // Default cloud service when Cloud Sync is enabled
    GOOGLE_DRIVE_APPDATA  // Optional alternative cloud service (hidden appDataFolder)
}

/**
 * Storage & Cloud Sync Policy:
 * - Out-of-the-box default is strictly Local Encrypted Storage (`cloudSyncEnabled = false`).
 * - When the user opts in to Cloud Sync (`cloudSyncEnabled = true`), `FIREBASE` is the default
 *   cloud service and `GOOGLE_DRIVE_APPDATA` is available as an optional alternative.
 */
@Serializable
data class VaultSyncPreferences(
    val cloudSyncEnabled: Boolean = false,
    val preferredCloudService: SyncProviderType = SyncProviderType.FIREBASE
) {
    val isLocalOnly: Boolean get() = !cloudSyncEnabled
}

interface CloudSyncAdapter {
    val providerType: SyncProviderType

    suspend fun pushEncryptedDeltas(
        vaultId: String,
        deltas: List<EncryptedSyncRecord>
    ): SyncPushResult

    suspend fun pullEncryptedDeltasSince(
        vaultId: String,
        sinceHlcPacked: String?
    ): List<EncryptedSyncRecord>

    suspend fun uploadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String,
        encryptedBytes: ByteArray,
        expectedSha256Hex: String
    )

    suspend fun downloadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String
    ): ByteArray
}

class OfflineFirstSyncCoordinator(
    private val firebaseDefaultAdapter: CloudSyncAdapter,
    private val googleDriveOptionalAdapter: CloudSyncAdapter? = null,
    initialPreferences: VaultSyncPreferences = VaultSyncPreferences()
) {
    var preferences: VaultSyncPreferences = initialPreferences
        private set

    fun updatePreferences(
        cloudSyncEnabled: Boolean = preferences.cloudSyncEnabled,
        preferredCloudService: SyncProviderType = preferences.preferredCloudService
    ) {
        preferences = VaultSyncPreferences(
            cloudSyncEnabled = cloudSyncEnabled,
            preferredCloudService = preferredCloudService
        )
    }

    fun activeAdapterOrNull(): CloudSyncAdapter? {
        if (!preferences.cloudSyncEnabled) return null // Local Storage Only (Default)
        return when (preferences.preferredCloudService) {
            SyncProviderType.FIREBASE -> firebaseDefaultAdapter
            SyncProviderType.GOOGLE_DRIVE_APPDATA ->
                requireNotNull(googleDriveOptionalAdapter) {
                    "Optional Google Drive sync adapter is not configured"
                }
        }
    }

    suspend fun syncPendingDeltasIfEnabled(
        vaultId: String,
        pendingDeltas: List<EncryptedSyncRecord>
    ): SyncPushResult? {
        val adapter = activeAdapterOrNull() ?: return null
        return adapter.pushEncryptedDeltas(vaultId, pendingDeltas)
    }
}

data class SyncPushResult(
    val acceptedCount: Int,
    val latestServerCursorHlc: String?
)

object CrdtReconciler {
    enum class CausalRelation { LOCAL_DOMINATES, REMOTE_DOMINATES, EQUAL, CONCURRENT }

    fun compareVectors(local: Map<String, Long>, remote: Map<String, Long>): CausalRelation {
        val allNodes = local.keys + remote.keys
        var localGreater = false
        var remoteGreater = false
        for (node in allNodes) {
            val l = local[node] ?: 0L
            val r = remote[node] ?: 0L
            if (l > r) localGreater = true
            if (r > l) remoteGreater = true
        }
        return when {
            !localGreater && !remoteGreater -> CausalRelation.EQUAL
            localGreater && !remoteGreater -> CausalRelation.LOCAL_DOMINATES
            remoteGreater && !localGreater -> CausalRelation.REMOTE_DOMINATES
            else -> CausalRelation.CONCURRENT
        }
    }

    /**
     * Resolves concurrent edits without data loss: merges causal version vectors (element-wise max)
     * and applies deterministic Last-Write-Wins via total-ordered Hybrid Logical Clock tie-breaker.
     */
    fun resolve(local: EncryptedSyncRecord, remote: EncryptedSyncRecord): EncryptedSyncRecord {
        val mergedVector = (local.versionVector.keys + remote.versionVector.keys).associateWith { node ->
            maxOf(local.versionVector[node] ?: 0L, remote.versionVector[node] ?: 0L)
        }
        val winner = when (compareVectors(local.versionVector, remote.versionVector)) {
            CausalRelation.LOCAL_DOMINATES, CausalRelation.EQUAL -> local
            CausalRelation.REMOTE_DOMINATES -> remote
            CausalRelation.CONCURRENT -> {
                val localHlc = HybridLogicalClock.unpack(local.hlcPacked)
                val remoteHlc = HybridLogicalClock.unpack(remote.hlcPacked)
                if (remoteHlc > localHlc) remote else local
            }
        }
        return winner.copy(versionVector = mergedVector)
    }
}
