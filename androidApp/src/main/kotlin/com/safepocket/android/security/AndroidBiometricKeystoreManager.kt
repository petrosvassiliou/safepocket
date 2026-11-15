package com.safepocket.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-Backed Android Keystore + `BiometricPrompt.CryptoObject` Vault Key Wrapper.
 *
 * Wraps the 256-bit Vault Encryption Key (VEK) inside StrongBox / TEE so biometric unlock
 * on Android 17+ Smartphones, Foldables, and Googlebook OS laptops releases the key only after
 * hardware cryptographic user authentication (`BIOMETRIC_STRONG`).
 */
class AndroidBiometricKeystoreManager {
    private val keyAlias = "safepocket_hardware_vek_wrapper_v1"

    private fun getOrCreateHardwareKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    fun authenticateAndDecryptVaultKey(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        iv12Bytes: ByteArray,
        wrappedVaultKey: ByteArray,
        onUnlocked: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateHardwareKey(), GCMParameterSpec(128, iv12Bytes))

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlockedCipher = result.cryptoObject?.cipher ?: return
                    runCatching { unlockedCipher.doFinal(wrappedVaultKey) }
                        .onSuccess(onUnlocked)
                        .onFailure { onError(it.message ?: "Hardware key unwrap failed") }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use Master Password")
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
