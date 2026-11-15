package com.safepocket.shared.sync

import com.safepocket.shared.crypto.CryptoPrimitives
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class CloudSyncUserSession(
    val uid: String,
    val email: String,
    val idToken: String,
    val refreshToken: String? = null
)

@Serializable
private data class IdentityToolkitAuthRequest(
    val email: String,
    val password: String,
    val returnSecureToken: Boolean = true
)

@Serializable
private data class IdentityToolkitAuthResponse(
    val localId: String,
    val email: String,
    val idToken: String,
    val refreshToken: String? = null
)

/**
 * Shared KMP Zero-Knowledge Firebase Auth Client (Default Cloud Sync Account Creation & Sign-In).
 *
 * Pre-initialized in dormant standby mode from v0.1 boot (making zero network calls while
 * `VaultSyncPreferences.cloudSyncEnabled == false`). Derives a domain-separated authentication
 * verifier via HKDF-SHA256 so Firebase Auth never receives the user's Master Password.
 */
class FirebaseZeroKnowledgeAuthService(
    private val httpClient: HttpClient,
    private val primitives: CryptoPrimitives,
    private val firebaseWebApiKey: String
) {
    private var activeSession: CloudSyncUserSession? = null

    val currentUser: CloudSyncUserSession? get() = activeSession

    suspend fun deriveZeroKnowledgeAuthPassword(email: String, syncPassword: String): String {
        val cleanEmail = email.trim().lowercase()
        val ikm = "safepocket-cloud-auth:$cleanEmail:$syncPassword".encodeToByteArray()
        val salt = "salt:$cleanEmail:safepocket-v0.1".encodeToByteArray()
        val derived = primitives.hkdfSha256(
            ikm = ikm,
            salt = salt,
            info = "safepocket-firebase-auth-verifier-v1".encodeToByteArray(),
            outputLengthBytes = 32
        )
        return "spzk_${ZeroKnowledgeEncryptionService.bytesToHex(derived)}"
    }

    suspend fun createCloudAccount(email: String, syncPassword: String): CloudSyncUserSession {
        val zkPassword = deriveZeroKnowledgeAuthPassword(email, syncPassword)
        val response: IdentityToolkitAuthResponse = httpClient.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$firebaseWebApiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(IdentityToolkitAuthRequest(email = email.trim().lowercase(), password = zkPassword))
        }.body()

        return CloudSyncUserSession(
            uid = response.localId,
            email = response.email,
            idToken = response.idToken,
            refreshToken = response.refreshToken
        ).also { activeSession = it }
    }

    suspend fun signInCloudAccount(email: String, syncPassword: String): CloudSyncUserSession {
        val zkPassword = deriveZeroKnowledgeAuthPassword(email, syncPassword)
        val response: IdentityToolkitAuthResponse = httpClient.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$firebaseWebApiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(IdentityToolkitAuthRequest(email = email.trim().lowercase(), password = zkPassword))
        }.body()

        return CloudSyncUserSession(
            uid = response.localId,
            email = response.email,
            idToken = response.idToken,
            refreshToken = response.refreshToken
        ).also { activeSession = it }
    }

    fun signOut() {
        activeSession = null
    }
}
