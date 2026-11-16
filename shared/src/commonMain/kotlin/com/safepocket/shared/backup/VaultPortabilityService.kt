package com.safepocket.shared.backup

import com.safepocket.shared.crypto.Argon2idParameters
import com.safepocket.shared.crypto.CryptoPrimitives
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import com.safepocket.shared.model.VaultItemPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Backup, Restore & Portability Service.
 *
 * 1. Encrypted `.safepocket` Container:
 *    Self-contained zero-knowledge archive protected by Argon2id + Secret Key + AES-256-GCM.
 *    Safe for one-click cross-platform restore across Android, Googlebook OS, ChromeOS PWA, and macOS.
 * 2. Unencrypted JSON / CSV Export & Import:
 *    Strictly gated behind fresh Master Password re-authentication (`reauthenticateMasterPassword`).
 */
@Serializable
data class SafePocketContainerFile(
    val magic: String = "SAFEPOCKET-CONTAINER-V1",
    val exportedAtIso: String,
    val kdfParams: Argon2idParameters,
    val ivHex: String,
    val ciphertextHex: String,
    val containerSha256Hex: String
)

class VaultPortabilityService(
    private val primitives: CryptoPrimitives,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    suspend fun exportEncryptedContainer(
        items: List<VaultItemPayload>,
        masterPassword: String,
        secretKeyRaw: ByteArray,
        exportedAtIso: String
    ): String {
        val salt = primitives.secureRandomBytes(16)
        val params = Argon2idParameters(saltHex = ZeroKnowledgeEncryptionService.bytesToHex(salt))
        val pwBytes = masterPassword.encodeToByteArray()
        try {
            val argon = primitives.deriveArgon2idKey(
                pwBytes, salt, params.memoryKiB, params.iterations, params.parallelism
            )
            val backupKey = primitives.hkdfSha256Combine(
                argon, secretKeyRaw, "safepocket-container-backup-v1".encodeToByteArray()
            )
            argon.fill(0)
            val plaintext = json.encodeToString(items).encodeToByteArray()
            val aad = "SAFEPOCKET-CONTAINER-V1:$exportedAtIso".encodeToByteArray()
            val (iv, ciphertext) = primitives.aes256GcmEncrypt(backupKey, plaintext, aad)
            backupKey.fill(0)

            val shaHex = ZeroKnowledgeEncryptionService.bytesToHex(primitives.sha256(ciphertext))
            val container = SafePocketContainerFile(
                exportedAtIso = exportedAtIso,
                kdfParams = params,
                ivHex = ZeroKnowledgeEncryptionService.bytesToHex(iv),
                ciphertextHex = ZeroKnowledgeEncryptionService.bytesToHex(ciphertext),
                containerSha256Hex = shaHex
            )
            return json.encodeToString(container)
        } finally {
            pwBytes.fill(0)
        }
    }

    suspend fun restoreEncryptedContainer(
        containerJson: String,
        masterPassword: String,
        secretKeyRaw: ByteArray
    ): List<VaultItemPayload> {
        val container = json.decodeFromString<SafePocketContainerFile>(containerJson)
        require(container.magic == "SAFEPOCKET-CONTAINER-V1") { "Invalid .safepocket container header" }
        val ciphertext = ZeroKnowledgeEncryptionService.hexToBytes(container.ciphertextHex)
        val computedSha = ZeroKnowledgeEncryptionService.bytesToHex(primitives.sha256(ciphertext))
        require(computedSha.equals(container.containerSha256Hex, ignoreCase = true)) {
            "Backup integrity check failed: SHA-256 mismatch"
        }

        val salt = ZeroKnowledgeEncryptionService.hexToBytes(container.kdfParams.saltHex)
        val pwBytes = masterPassword.encodeToByteArray()
        try {
            val argon = primitives.deriveArgon2idKey(
                pwBytes,
                salt,
                container.kdfParams.memoryKiB,
                container.kdfParams.iterations,
                container.kdfParams.parallelism
            )
            val backupKey = primitives.hkdfSha256Combine(
                argon, secretKeyRaw, "safepocket-container-backup-v1".encodeToByteArray()
            )
            argon.fill(0)
            val aad = "SAFEPOCKET-CONTAINER-V1:${container.exportedAtIso}".encodeToByteArray()
            val iv = ZeroKnowledgeEncryptionService.hexToBytes(container.ivHex)
            val decrypted = primitives.aes256GcmDecrypt(backupKey, iv, ciphertext, aad)
            backupKey.fill(0)
            return json.decodeFromString(decrypted.decodeToString())
        } finally {
            pwBytes.fill(0)
        }
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

    /**
     * Plaintext CSV export strictly gated behind explicit Master Password verification callback.
     */
    suspend fun exportUnencryptedCsvGated(
        items: List<VaultItemPayload>,
        candidateMasterPassword: String,
        verifyMasterPassword: suspend (String) -> Boolean
    ): String {
        check(verifyMasterPassword(candidateMasterPassword)) {
            "Master Password re-authentication failed — plaintext export denied"
        }
        val header = "id,category,title,subtitle,favorite,tags,fields_json"
        val rows = items.map { item ->
            val fieldsEscaped = json.encodeToString(item.fields).replace("\"", "\"\"")
            "\"${item.id}\",\"${item.category.name}\",\"${item.title.replace("\"", "\"\"")}\"," +
                "\"${item.subtitle.replace("\"", "\"\"")}\",${item.favorite}," +
                "\"${item.tags.joinToString("|")}\",\"$fieldsEscaped\""
        }
        return (listOf(header) + rows).joinToString("\n")
    }
}
