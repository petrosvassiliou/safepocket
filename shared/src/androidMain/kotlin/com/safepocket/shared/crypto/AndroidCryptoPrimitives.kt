package com.safepocket.shared.crypto

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.safepocket.db.SafePocketDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 10+ / Android 17+ & Googlebook OS (`arm64-v8a` + `x86_64`) CryptoPrimitives,
 * SQLCipher 4.6 Encrypted Database Driver Factory, and `.1pux` ZIP Codec.
 */
class AndroidCryptoPrimitives(
    private val argon2idBridge: suspend (
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ) -> ByteArray = { pw, salt, mem, iter, par ->
        Argon2idPure.hash(
            password = pw,
            salt = salt,
            memoryKiB = mem,
            iterations = iter,
            parallelism = par,
            hashLength = 32
        )
    }
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
        val ikm = argonKey + secretKeyBytes
        try {
            HkdfSha256Pure.derive(
                ikm = ikm,
                salt = ByteArray(32),
                info = infoUtf8,
                length = 32
            )
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
    fun createEncryptedDriver(
        context: Context,
        rawDatabaseKey32: ByteArray,
        databaseFileName: String = "safepocket_vault.db"
    ): SqlDriver {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(rawDatabaseKey32.copyOf(), null, true)
        return AndroidSqliteDriver(
            schema = SafePocketDatabase.Schema,
            context = context,
            name = databaseFileName,
            factory = factory
        )
    }
}

object AndroidZipCodec {
    fun extractEntries(zipBytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var n = zis.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        n = zis.read(buf)
                    }
                    entries[entry.name] = out.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    fun createZip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
