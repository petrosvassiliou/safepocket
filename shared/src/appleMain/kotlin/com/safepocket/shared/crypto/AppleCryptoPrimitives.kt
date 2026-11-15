package com.safepocket.shared.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * macOS Native (`macosArm64` + `macosX64`) CryptoPrimitives binding Apple Security CSPRNG,
 * CryptoKit AES.GCM bridge, and libsodium Argon2id into `SafePocketShared.xcframework`.
 */
class AppleCryptoPrimitives(
    private val swiftCryptoKitDelegate: SwiftCryptoKitBridge
) : CryptoPrimitives {

    interface SwiftCryptoKitBridge {
        fun argon2id(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int): ByteArray
        fun hkdfSha256(ikm: ByteArray, info: ByteArray): ByteArray
        fun aesGcmSeal(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
        fun aesGcmOpen(key: ByteArray, nonce12: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray
        fun sha256(data: ByteArray): ByteArray
    }

    override suspend fun deriveArgon2idKey(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray = swiftCryptoKitDelegate.argon2id(passwordUtf8, salt, memoryKiB, iterations, parallelism)

    override suspend fun hkdfSha256Combine(
        argonKey: ByteArray,
        secretKeyBytes: ByteArray,
        infoUtf8: ByteArray
    ): ByteArray {
        val ikm = argonKey + secretKeyBytes
        return try {
            swiftCryptoKitDelegate.hkdfSha256(ikm, infoUtf8)
        } finally {
            ikm.fill(0)
        }
    }

    override suspend fun aes256GcmEncrypt(
        key256: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val iv = secureRandomBytes(12)
        return iv to swiftCryptoKitDelegate.aesGcmSeal(key256, iv, plaintext, aad)
    }

    override suspend fun aes256GcmDecrypt(
        key256: ByteArray,
        iv12Bytes: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray = swiftCryptoKitDelegate.aesGcmOpen(key256, iv12Bytes, ciphertextWithTag, aad)

    override suspend fun sha256(data: ByteArray): ByteArray =
        swiftCryptoKitDelegate.sha256(data)

    @OptIn(ExperimentalForeignApi::class)
    override fun secureRandomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        if (length > 0) {
            out.usePinned { pinned ->
                SecRandomCopyBytes(kSecRandomDefault, length.toULong(), pinned.addressOf(0))
            }
        }
        return out
    }
}
