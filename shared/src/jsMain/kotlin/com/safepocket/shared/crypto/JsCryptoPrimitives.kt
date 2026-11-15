package com.safepocket.shared.crypto

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * ChromeOS PWA / WebCrypto (`window.crypto.subtle`) CryptoPrimitives implementation for KMP-JS.
 */
class JsCryptoPrimitives(
    private val argon2WasmDeriver: (ByteArray, ByteArray, Int, Int, Int) -> Promise<ByteArray>
) : CryptoPrimitives {

    override suspend fun deriveArgon2idKey(
        passwordUtf8: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): ByteArray = argon2WasmDeriver(passwordUtf8, salt, memoryKiB, iterations, parallelism).await()

    override suspend fun hkdfSha256Combine(
        argonKey: ByteArray,
        secretKeyBytes: ByteArray,
        infoUtf8: ByteArray
    ): ByteArray {
        val ikm = toUint8Array(argonKey + secretKeyBytes)
        val info = toUint8Array(infoUtf8)
        val promise: Promise<ArrayBuffer> = js(
            """
            crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits'])
              .then(function(k) {
                return crypto.subtle.deriveBits(
                  { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(32), info: info },
                  k,
                  256
                );
              })
            """
        )
        return fromArrayBuffer(promise.await())
    }

    override suspend fun aes256GcmEncrypt(
        key256: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val iv = secureRandomBytes(12)
        val keyArr = toUint8Array(key256)
        val ivArr = toUint8Array(iv)
        val ptArr = toUint8Array(plaintext)
        val aadArr = toUint8Array(aad)
        val promise: Promise<ArrayBuffer> = js(
            """
            crypto.subtle.importKey('raw', keyArr, { name: 'AES-GCM' }, false, ['encrypt'])
              .then(function(k) {
                return crypto.subtle.encrypt(
                  { name: 'AES-GCM', iv: ivArr, additionalData: aadArr, tagLength: 128 },
                  k,
                  ptArr
                );
              })
            """
        )
        return iv to fromArrayBuffer(promise.await())
    }

    override suspend fun aes256GcmDecrypt(
        key256: ByteArray,
        iv12Bytes: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val keyArr = toUint8Array(key256)
        val ivArr = toUint8Array(iv12Bytes)
        val ctArr = toUint8Array(ciphertextWithTag)
        val aadArr = toUint8Array(aad)
        val promise: Promise<ArrayBuffer> = js(
            """
            crypto.subtle.importKey('raw', keyArr, { name: 'AES-GCM' }, false, ['decrypt'])
              .then(function(k) {
                return crypto.subtle.decrypt(
                  { name: 'AES-GCM', iv: ivArr, additionalData: aadArr, tagLength: 128 },
                  k,
                  ctArr
                );
              })
            """
        )
        return fromArrayBuffer(promise.await())
    }

    override suspend fun sha256(data: ByteArray): ByteArray {
        val arr = toUint8Array(data)
        val promise: Promise<ArrayBuffer> = js("crypto.subtle.digest('SHA-256', arr)")
        return fromArrayBuffer(promise.await())
    }

    override fun secureRandomBytes(length: Int): ByteArray {
        val arr = Uint8Array(length)
        js("crypto.getRandomValues(arr)")
        return Int8Array(arr.buffer).unsafeCast<ByteArray>()
    }

    private fun toUint8Array(bytes: ByteArray): Uint8Array =
        Uint8Array(bytes.unsafeCast<Int8Array>().buffer)

    private fun fromArrayBuffer(buf: ArrayBuffer): ByteArray =
        Int8Array(buf).unsafeCast<ByteArray>()
}
