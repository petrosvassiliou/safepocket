package com.safepocket.shared.backup

import com.safepocket.shared.crypto.Argon2idParameters
import com.safepocket.shared.crypto.CryptoPrimitives
import com.safepocket.shared.crypto.HkdfSha256Pure
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import com.safepocket.shared.model.VaultItemPayload
import com.safepocket.shared.update.AppBuildInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CrossPlatformKdfConfig(
    val memorySizeKiB: Int = 16_384,
    val iterations: Int = 3,
    val parallelism: Int = 2,
    val saltHex: String
)

/**
 * Cross-platform `.safepocket` encrypted container schema (100% compatible with ChromeOS/Web PWA).
 */
@Serializable
data class SafePocketContainerFile(
    val magic: String = "SAFEPOCKET-CONTAINER-V1",
    val appVersion: String = AppBuildInfo.CURRENT_VERSION_NAME,
    val exportedAtIso: String,
    val itemCount: Int? = null,
    val attachmentCount: Int? = null,
    val kdf: CrossPlatformKdfConfig? = null,
    val kdfParams: Argon2idParameters? = null,
    val ivHex: String,
    val ciphertextHex: String,
    val sha256Hex: String? = null,
    val containerSha256Hex: String? = null
)

data class ContainerInspectionSummary(
    val exportedAtIso: String,
    val appVersion: String,
    val itemCount: Int?,
    val attachmentCount: Int?,
    val sha256Hex: String,
    val kdf: CrossPlatformKdfConfig
)

class VaultPortabilityService(
    private val primitives: CryptoPrimitives,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    private val compactJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Exports a cross-platform `.safepocket` archive encrypted with `(masterPassword, secretKeyFormatted)`.
     * Byte-for-byte compatible with `ZeroKnowledgeVaultCrypto.exportSafePocketContainer` in `pwaApp`.
     */
    suspend fun exportCrossPlatformContainer(
        items: List<VaultItemPayload>,
        masterPassword: String,
        secretKeyFormatted: String,
        exportedAtIso: String,
        memorySizeKiB: Int = 16_384,
        iterations: Int = 3,
        parallelism: Int = 2
    ): String {
        val salt = primitives.secureRandomBytes(16)
        val saltHex = ZeroKnowledgeEncryptionService.bytesToHex(salt)
        val kdf = CrossPlatformKdfConfig(
            memorySizeKiB = memorySizeKiB,
            iterations = iterations,
            parallelism = parallelism,
            saltHex = saltHex
        )
        val backupKey = HkdfSha256Pure.deriveTwoSecretKey(
            masterPassword = masterPassword,
            secretKeyFormatted = secretKeyFormatted,
            saltHex = saltHex,
            memorySizeKiB = memorySizeKiB,
            iterations = iterations,
            parallelism = parallelism,
            domainContext = "safepocket-container-backup-v1"
        )
        return try {
            val plaintext = compactJson.encodeToString(items).encodeToByteArray()
            val aad = "SAFEPOCKET-CONTAINER-V1:$exportedAtIso".encodeToByteArray()
            val (iv, ciphertext) = primitives.aes256GcmEncrypt(backupKey, plaintext, aad)
            val shaHex = ZeroKnowledgeEncryptionService.bytesToHex(primitives.sha256(ciphertext))
            val attachmentCount = items.sumOf { it.attachments.size }

            val container = SafePocketContainerFile(
                appVersion = AppBuildInfo.CURRENT_VERSION_NAME,
                exportedAtIso = exportedAtIso,
                itemCount = items.size,
                attachmentCount = attachmentCount,
                kdf = kdf,
                ivHex = ZeroKnowledgeEncryptionService.bytesToHex(iv),
                ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(ciphertext),
                sha256Hex = shaHex
            )
            json.encodeToString(container)
        } finally {
            backupKey.fill(0)
        }
    }

    suspend fun inspectContainer(containerJson: String): ContainerInspectionSummary {
        val container = json.decodeFromString<SafePocketContainerFile>(containerJson)
        require(container.magic == "SAFEPOCKET-CONTAINER-V1") { "Invalid .safepocket container header" }
        val expectedSha = container.sha256Hex ?: container.containerSha256Hex
            ?: error("Missing SHA-256 digest in .safepocket container")
        val ciphertext = ZeroKnowledgeEncryptionService.hexToBytes(container.ciphertextHex)
        val computedSha = ZeroKnowledgeEncryptionService.bytesToHex(primitives.sha256(ciphertext))
        require(computedSha.equals(expectedSha, ignoreCase = true)) {
            "Backup integrity check failed: SHA-256 mismatch"
        }
        val resolvedKdf = container.kdf ?: container.kdfParams?.let {
            CrossPlatformKdfConfig(
                memorySizeKiB = it.memoryKiB,
                iterations = it.iterations,
                parallelism = it.parallelism,
                saltHex = it.saltHex
            )
        } ?: error("Missing Argon2id KDF parameters in .safepocket container")

        return ContainerInspectionSummary(
            exportedAtIso = container.exportedAtIso,
            appVersion = container.appVersion,
            itemCount = container.itemCount,
            attachmentCount = container.attachmentCount,
            sha256Hex = expectedSha,
            kdf = resolvedKdf
        )
    }

    suspend fun restoreCrossPlatformContainer(
        containerJson: String,
        masterPassword: String,
        secretKeyFormatted: String
    ): List<VaultItemPayload> {
        val summary = inspectContainer(containerJson)
        val container = json.decodeFromString<SafePocketContainerFile>(containerJson)
        val ciphertext = ZeroKnowledgeEncryptionService.hexToBytes(container.ciphertextHex)
        val iv = ZeroKnowledgeEncryptionService.hexToBytes(container.ivHex)
        val aad = "SAFEPOCKET-CONTAINER-V1:${summary.exportedAtIso}".encodeToByteArray()

        val domains = listOf("safepocket-container-backup-v1", "safepocket-backup-v1")
        var lastError: Throwable? = null
        for (domain in domains) {
            val key = HkdfSha256Pure.deriveTwoSecretKey(
                masterPassword = masterPassword,
                secretKeyFormatted = secretKeyFormatted,
                saltHex = summary.kdf.saltHex,
                memorySizeKiB = summary.kdf.memorySizeKiB,
                iterations = summary.kdf.iterations,
                parallelism = summary.kdf.parallelism,
                domainContext = domain
            )
            try {
                val decrypted = primitives.aes256GcmDecrypt(key, iv, ciphertext, aad)
                return compactJson.decodeFromString(decrypted.decodeToString())
            } catch (t: Throwable) {
                lastError = t
            } finally {
                key.fill(0)
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "Decryption failed — verify Master Password & Secret Key"
        )
    }

    fun mergeSnapshots(
        existing: List<VaultItemPayload>,
        incoming: List<VaultItemPayload>
    ): List<VaultItemPayload> {
        val map = existing.associateBy { it.id }.toMutableMap()
        for (inc in incoming) {
            val cur = map[inc.id]
            if (cur == null || inc.updatedAtEpochMs > cur.updatedAtEpochMs) {
                map[inc.id] = inc
            }
        }
        return map.values.sortedByDescending { it.updatedAtEpochMs }
    }
}
