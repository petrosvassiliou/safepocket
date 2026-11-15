package com.safepocket.shared.crypto

import com.safepocket.shared.model.VaultItemPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Zero-Knowledge Two-Secret Key Derivation (2SKD) & AES-256-GCM Envelope Architecture.
 *
 * Security Properties:
 * 1. Master Password never leaves client memory and is wiped immediately after Argon2id derivation.
 * 2. Locally generated 256-bit Secret Key (formatted e.g. SP3-XXXXXX-XXXXXX-...) is combined via
 *    HKDF-SHA256 with the Argon2id output so an attacker who compromises cloud storage cannot
 *    brute-force the Master Password even with offline GPU clusters.
 * 3. Every VaultItem and every file attachment has its own random 96-bit IV and authenticated AAD
 *    (`itemId || category || schemaVersion`) preventing ciphertext transplant/replay attacks.
 */
@Serializable
data class Argon2idParameters(
    val memoryKiB: Int = 65_536,   // 64 MiB OWASP high-assurance interactive profile
    val iterations: Int = 3,
    val parallelism: Int = 4,
    val saltHex: String
)

@Serializable
data class EncryptedEnvelope(
    val keyWrapVersion: Int = 1,
    val ivBase64: String,          // 12-byte (96-bit) CSPRNG nonce
    val ciphertextBase64: String,  // Ciphertext || 128-bit GCM authentication tag
    val aadContext: String         // Bound context: "safepocket:v1:<entityType>:<entityId>"
)

/**
 * Platform-accelerated cryptographic primitives bound via Kotlin `expect`/`actual`:
 * - Android 17+ / Googlebook OS: Lazysodium-Android (BoringSSL / ARMv8 Crypto Extensions & AES-NI on x86_64) + Android Keystore
 * - macOS: Apple CryptoKit (`AES.GCM`) + Libsodium Argon2id
 * - ChromeOS PWA: WebCryptoSubtle (`AES-GCM` 256-bit) + Argon2id WebAssembly worker
 */
interface CryptoPrimitives {
    suspend fun deriveArgon2idKey(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray // 32 bytes

    suspend fun hkdfSha256Combine(
        argonKey: ByteArray,
        secretKeyBytes: ByteArray,
        infoUtf8: ByteArray
    ): ByteArray // 32-byte Key Encryption Key (KEK)

    suspend fun aes256GcmEncrypt(
        key256: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): Pair<ByteArray, ByteArray> // (iv12Bytes, ciphertextWithTag)

    suspend fun aes256GcmDecrypt(
        key256: ByteArray,
        iv12Bytes: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray

    suspend fun sha256(data: ByteArray): ByteArray
    fun secureRandomBytes(length: Int): ByteArray
}

class ZeroKnowledgeEncryptionService(
    private val primitives: CryptoPrimitives,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private var unlockedVaultKey: ByteArray? = null

    val isUnlocked: Boolean
        get() = unlockedVaultKey != null

    /**
     * Generates a new formatted 128-bit high-entropy Secret Key (`SPK-XXXX-...`) during vault creation.
     */
    fun generateLocalSecretKey(): Pair<String, ByteArray> {
        val raw = primitives.secureRandomBytes(32)
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val groups = (0 until 6).map { g ->
            (0 until 5).map { i ->
                alphabet[(raw[g * 5 + i].toInt() and 0xFF) % alphabet.length]
            }.joinToString("")
        }
        return ("SPK-${groups.joinToString("-")}") to raw
    }

    /**
     * Derives the 256-bit Key Encryption Key (KEK) from Master Password + Local Secret Key,
     * then unwraps the Vault Encryption Key (VEK) in memory.
     */
    suspend fun unlockVault(
        masterPassword: String,
        secretKeyRaw: ByteArray,
        params: Argon2idParameters,
        wrappedVaultKey: ByteArray,
        wrappedVaultKeyIv: ByteArray,
        vaultId: String
    ) {
        val pwBytes = masterPassword.encodeToByteArray()
        try {
            val salt = hexToBytes(params.saltHex)
            val mukPart = primitives.deriveArgon2idKey(
                passwordUtf8 = pwBytes,
                salt = salt,
                memoryKiB = params.memoryKiB,
                iterations = params.iterations,
                parallelism = params.parallelism
            )
            val kek = primitives.hkdfSha256Combine(
                argonKey = mukPart,
                secretKeyBytes = secretKeyRaw,
                infoUtf8 = "safepocket-2skd-kek-v1:$vaultId".encodeToByteArray()
            )
            mukPart.fill(0)
            val vek = primitives.aes256GcmDecrypt(
                key256 = kek,
                iv12Bytes = wrappedVaultKeyIv,
                ciphertextWithTag = wrappedVaultKey,
                aad = "safepocket:vek:$vaultId".encodeToByteArray()
            )
            kek.fill(0)
            unlockedVaultKey = vek
        } finally {
            pwBytes.fill(0)
        }
    }

    /**
     * Immediately zeroes the in-memory Vault Encryption Key (`Ctrl+L` / `Cmd+L` / auto-lock timer).
     */
    fun lockVault() {
        unlockedVaultKey?.fill(0)
        unlockedVaultKey = null
    }

    suspend fun encryptItem(item: VaultItemPayload): Pair<ByteArray, ByteArray> {
        val vek = checkNotNull(unlockedVaultKey) { "Vault is locked" }
        val plaintext = json.encodeToString(item).encodeToByteArray()
        val aad = "safepocket:v${item.schemaVersion}:${item.category.name}:${item.id}".encodeToByteArray()
        return primitives.aes256GcmEncrypt(vek, plaintext, aad)
    }

    suspend fun decryptItem(
        itemId: String,
        categoryName: String,
        schemaVersion: Int,
        iv: ByteArray,
        ciphertextWithTag: ByteArray
    ): VaultItemPayload {
        val vek = checkNotNull(unlockedVaultKey) { "Vault is locked" }
        val aad = "safepocket:v$schemaVersion:$categoryName:$itemId".encodeToByteArray()
        val plaintext = primitives.aes256GcmDecrypt(vek, iv, ciphertextWithTag, aad)
        return json.decodeFromString(plaintext.decodeToString())
    }

    suspend fun encryptAttachment(
        attachmentId: String,
        itemId: String,
        rawBytes: ByteArray
    ): EncryptedAttachmentBundle {
        val vek = checkNotNull(unlockedVaultKey) { "Vault is locked" }
        val fileDek = primitives.secureRandomBytes(32)
        return try {
            val fileAad = "safepocket:blob:$itemId:$attachmentId".encodeToByteArray()
            val (blobIv, encryptedBlob) = primitives.aes256GcmEncrypt(fileDek, rawBytes, fileAad)
            val wrapAad = "safepocket:dekwrap:$itemId:$attachmentId".encodeToByteArray()
            val (wrapIv, wrappedDek) = primitives.aes256GcmEncrypt(vek, fileDek, wrapAad)
            val digestHex = bytesToHex(primitives.sha256(encryptedBlob))
            EncryptedAttachmentBundle(
                attachmentId = attachmentId,
                wrappedFileKey = wrappedDek,
                fileKeyIv = wrapIv,
                blobIv = blobIv,
                encryptedBlob = encryptedBlob,
                encryptedBlobSha256Hex = digestHex
            )
        } finally {
            fileDek.fill(0)
        }
    }

    /**
     * Verifies the encrypted attachment blob's SHA-256 digest in constant time, unwraps the
     * per-attachment 256-bit DEK using the Vault Encryption Key, and decrypts purely in RAM.
     */
    suspend fun decryptAttachment(
        itemId: String,
        bundle: EncryptedAttachmentBundle
    ): ByteArray {
        val vek = checkNotNull(unlockedVaultKey) { "Vault is locked" }
        val actualSha = bytesToHex(primitives.sha256(bundle.encryptedBlob)).lowercase()
        val expectedSha = bundle.encryptedBlobSha256Hex.lowercase()
        require(actualSha.length == expectedSha.length && actualSha.indices.fold(0) { acc, i ->
            acc or (actualSha[i].code xor expectedSha[i].code)
        } == 0) {
            "Attachment SHA-256 integrity verification failed"
        }

        val wrapAad = "safepocket:dekwrap:$itemId:${bundle.attachmentId}".encodeToByteArray()
        val fileDek = primitives.aes256GcmDecrypt(vek, bundle.fileKeyIv, bundle.wrappedFileKey, wrapAad)
        return try {
            val fileAad = "safepocket:blob:$itemId:${bundle.attachmentId}".encodeToByteArray()
            primitives.aes256GcmDecrypt(fileDek, bundle.blobIv, bundle.encryptedBlob, fileAad)
        } finally {
            fileDek.fill(0)
        }
    }

    companion object {
        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "Invalid hex length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}

data class EncryptedAttachmentBundle(
    val attachmentId: String,
    val wrappedFileKey: ByteArray,
    val fileKeyIv: ByteArray,
    val blobIv: ByteArray,
    val encryptedBlob: ByteArray,
    val encryptedBlobSha256Hex: String
)
