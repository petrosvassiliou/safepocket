package com.safepocket.shared.sync

import com.safepocket.shared.crypto.CryptoPrimitives
import com.safepocket.shared.crypto.HkdfSha256Pure
import com.safepocket.shared.crypto.ZeroKnowledgeEncryptionService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

object ProvisionedFirebaseProject {
    const val PROJECT_ID = "safepocket-vault-eu"
    const val FIRESTORE_REGION = "eur3"
    const val STORAGE_BUCKET = "safepocket-vault-eu.firebasestorage.app"
    const val WEB_API_KEY = "AIzaSyBCeyZx121Eq5xxyWZD4k3w1-9vyr0yIsw"
    const val ANDROID_APP_ID = "1:1028417352953:android:68a6559ae1686faf9b1e28"
    const val WEB_APP_ID = "1:1028417352953:web:30b5698c6a4fd1db9b1e28"
}

/**
 * Shared KMP Zero-Knowledge Firebase Auth Client (Default Cloud Sync Account Creation & Sign-In).
 *
 * Uses exact RFC 5869 HKDF-SHA256 parameters matching `pwaApp/src/sync/liveCloudConnectors.ts`
 * (`deriveZeroKnowledgeCloudAuthPassword`) so accounts created on Android sign in seamlessly on
 * ChromeOS PWA and vice versa, without transmitting raw passwords to Firebase Auth.
 */
class FirebaseZeroKnowledgeAuthService(
    private val httpClient: HttpClient,
    @Suppress("UNUSED_PARAMETER")
    private val primitives: CryptoPrimitives,
    private val firebaseWebApiKey: String = ProvisionedFirebaseProject.WEB_API_KEY,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private var activeSession: CloudSyncUserSession? = null

    val currentUser: CloudSyncUserSession? get() = activeSession

    fun restoreSession(session: CloudSyncUserSession?) {
        activeSession = session
    }

    fun deriveZeroKnowledgeAuthPassword(email: String, syncPassword: String): String {
        val cleanEmail = email.trim().lowercase()
        val ikm = "safepocket-cloud-auth:$cleanEmail:$syncPassword".encodeToByteArray()
        val salt = "salt:$cleanEmail:safepocket-v0.1".encodeToByteArray()
        val derived = HkdfSha256Pure.derive(
            ikm = ikm,
            salt = salt,
            info = "safepocket-firebase-auth-verifier-v1".encodeToByteArray(),
            length = 32
        )
        return "spzk_${ZeroKnowledgeEncryptionService.bytesToHex(derived)}"
    }

    suspend fun createCloudAccount(email: String, syncPassword: String): CloudSyncUserSession =
        authenticate("signUp", email, syncPassword)

    suspend fun signInCloudAccount(email: String, syncPassword: String): CloudSyncUserSession =
        authenticate("signInWithPassword", email, syncPassword)

    suspend fun refreshIdTokenIfPossible(): CloudSyncUserSession? {
        val cur = activeSession ?: return null
        val refresh = cur.refreshToken?.takeIf { it.isNotBlank() } ?: return cur
        val resp = httpClient.post("https://securetoken.googleapis.com/v1/token?key=$firebaseWebApiKey") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("grant_type=refresh_token&refresh_token=$refresh")
        }
        if (!resp.status.isSuccess()) return cur
        val raw: String = resp.body()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return cur
        val nextIdToken = obj["id_token"]?.jsonPrimitive?.content ?: return cur
        val nextRefresh = obj["refresh_token"]?.jsonPrimitive?.content ?: refresh
        return cur.copy(idToken = nextIdToken, refreshToken = nextRefresh).also { activeSession = it }
    }

    private suspend fun authenticate(
        action: String,
        email: String,
        syncPassword: String
    ): CloudSyncUserSession {
        val zkPassword = deriveZeroKnowledgeAuthPassword(email, syncPassword)
        val httpResp = httpClient.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:$action?key=$firebaseWebApiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(IdentityToolkitAuthRequest.serializer(), IdentityToolkitAuthRequest(
                email = email.trim().lowercase(),
                password = zkPassword
            )))
        }
        val bodyText: String = httpResp.body()
        if (!httpResp.status.isSuccess()) {
            val fbErr = runCatching {
                json.parseToJsonElement(bodyText).jsonObject["error"]
                    ?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
            throw IllegalStateException(fbErr ?: "Firebase Auth $action failed (${httpResp.status.value})")
        }
        val parsed = json.decodeFromString(IdentityToolkitAuthResponse.serializer(), bodyText)
        return CloudSyncUserSession(
            uid = parsed.localId,
            email = parsed.email,
            idToken = parsed.idToken,
            refreshToken = parsed.refreshToken
        ).also { activeSession = it }
    }

    fun signOut() {
        activeSession = null
    }
}
