package com.safepocket.shared.crypto

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.safepocket.db.SafePocketDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 17+ & Googlebook OS (`arm64-v8a` + `x86_64`) Hardware-Accelerated CryptoPrimitives
 * & SQLCipher 4.6 Encrypted Database Driver Factory.
 */
class AndroidCryptoPrimitives(
    private val argon2idBridge: suspend (
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ) -> ByteArray
) : CryptoPrimitives {

    private val csprng = SecureRandom()

    override suspend fun deriveArgon2idKey(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray = withContext(Dispatchers.Default) {
        argon2idBridge(passwordUtf8, salt, memoryKiB, iterations, parallelism)
    }

    override suspend fun hkdfSha256Combine(
        argonKey: ByteArray,
        secretKeyBytes: ByteArray,
        infoUtf8: ByteArray
    ): ByteArray = withContext(Dispatchers.Default) {
        // RFC 5869 HKDF-Extract + HKDF-Expand (HmacSHA256)
        val ikm = argonKey + secretKeyBytes
        try {
            val extractMac = Mac.getInstance("HmacSHA256")
            extractMac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = extractMac.doFinal(ikm)

            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(infoUtf8)
            expandMac.update(0x01.toByte())
            expandMac.doFinal()
        } finally {
            ikm.fill(0)
        }
    }

    override suspend fun aes256GcmEncrypt(
        key256: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): Pair<ByteArray, ByteArray> = withContext(Dispatchers.Default) {
        val iv = secureRandomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key256, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        iv to cipher.doFinal(plaintext)
    }

    override suspend fun aes256GcmDecrypt(
        key256: ByteArray,
        iv12Bytes: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray = withContext(Dispatchers.Default) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key256, "AES"),
            GCMParameterSpec(128, iv12Bytes)
        )
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertextWithTag)
    }

    override suspend fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun secureRandomBytes(length: Int): ByteArray =
        ByteArray(length).also(csprng::nextBytes)
}

object AndroidSqlCipherDriverFactory {
    fun createEncryptedDriver(context: Context, rawDatabaseKey32: ByteArray): SqlDriver {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(rawDatabaseKey32.copyOf(), null, true)
        return AndroidSqliteDriver(
            schema = SafePocketDatabase.Schema,
            context = context,
            name = "safepocket_vault.db",
            factory = factory
        )
    }
}
