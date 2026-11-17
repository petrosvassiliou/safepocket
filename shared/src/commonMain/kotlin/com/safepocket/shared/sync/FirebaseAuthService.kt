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
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.util.date.getTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class CloudAuthExpiredException(message: String) : IllegalStateException(message)

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

@Serializable
private data class SecureTokenRefreshRequest(
    val grant_type: String = "refresh_token",
    val refresh_token: String
)

internal data class DecodedJwtClaims(
    val expEpochSec: Long?,
    val aud: String?
)

internal object FirebaseJwtInspector {
    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun decodeBase64UrlUtf8(segment: String): String? {
        if (segment.isBlank()) return null
        val normalized = segment.replace('-', '+').replace('_', '/').let { s ->
            val rem = s.length % 4
            if (rem == 0) s else s + "=".repeat(4 - rem)
        }
        val out = ArrayList<Byte>((normalized.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in normalized) {
            if (ch == '=') break
            val idx = B64_ALPHABET.indexOf(ch)
            if (idx < 0) continue
            acc = (acc shl 6) or idx
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray().decodeToString()
    }

    fun inspect(idToken: String, json: Json): DecodedJwtClaims? {
        val parts = idToken.trim().split('.')
        if (parts.size != 3) return null
        val payloadText = runCatching { decodeBase64UrlUtf8(parts[1]) }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(payloadText).jsonObject }.getOrNull() ?: return null
        val exp = obj["exp"]?.jsonPrimitive?.longOrNull
        val aud = obj["aud"]?.jsonPrimitive?.content
        return DecodedJwtClaims(expEpochSec = exp, aud = aud)
    }
}

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
 * ChromeOS PWA and macOS, without transmitting raw passwords to Firebase Auth.
 */
class FirebaseZeroKnowledgeAuthService(
    private val httpClient: HttpClient,
    @Suppress("UNUSED_PARAMETER")
    private val primitives: CryptoPrimitives,
    private val firebaseWebApiKey: String = ProvisionedFirebaseProject.WEB_API_KEY,
    private val apiKeyProvider: (() -> String)? = null,
    private val projectIdProvider: (() -> String)? = null,
    private val onSessionRefreshed: ((CloudSyncUserSession) -> Unit)? = null,
    private val clockEpochSeconds: () -> Long = { getTimeMillis() / 1000L },
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private var activeSession: CloudSyncUserSession? = null
    private var inMemoryZkVerifier: Pair<String, String>? = null

    val currentUser: CloudSyncUserSession? get() = activeSession

    private fun currentApiKey(): String =
        apiKeyProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() } ?: firebaseWebApiKey.trim()

    private fun currentProjectId(): String =
        projectIdProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() } ?: ProvisionedFirebaseProject.PROJECT_ID

    fun restoreSession(session: CloudSyncUserSession?) {
        activeSession = session?.copy(
            uid = session.uid.trim(),
            email = session.email.trim(),
            idToken = session.idToken.trim(),
            refreshToken = session.refreshToken?.trim()
        )
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

    suspend fun refreshIdTokenIfPossible(force: Boolean = false): CloudSyncUserSession? {
        val cur = activeSession ?: return null
        val cleanToken = cur.idToken.trim()
        val claims = FirebaseJwtInspector.inspect(cleanToken, json)
        val nowSec = clockEpochSeconds()
        val expectedProject = currentProjectId()

        val projectMatches = claims?.aud == null || claims.aud == expectedProject
        val tokenStillFresh = claims?.expEpochSec != null && (claims.expEpochSec - nowSec) > 300L
        if (!force && cleanToken.isNotEmpty() && projectMatches && tokenStillFresh) {
            return cur
        }

        val refresh = cur.refreshToken?.trim()?.takeIf { it.isNotEmpty() }
        val apiKey = currentApiKey().encodeURLParameter()
        if (refresh != null && projectMatches) {
            val resp = httpClient.post("https://securetoken.googleapis.com/v1/token?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        SecureTokenRefreshRequest.serializer(),
                        SecureTokenRefreshRequest(refresh_token = refresh)
                    )
                )
            }
            if (resp.status.isSuccess()) {
                val raw: String = resp.body()
                val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                val nextIdToken = obj?.get("id_token")?.jsonPrimitive?.content?.trim()
                val nextRefresh = obj?.get("refresh_token")?.jsonPrimitive?.content?.trim() ?: refresh
                val nextUid = obj?.get("user_id")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() } ?: cur.uid
                if (!nextIdToken.isNullOrEmpty()) {
                    val updated = cur.copy(
                        uid = nextUid,
                        idToken = nextIdToken,
                        refreshToken = nextRefresh
                    )
                    activeSession = updated
                    onSessionRefreshed?.invoke(updated)
                    return updated
                }
            }
        }

        // Fallback: if we have the zero-knowledge auth verifier in memory for this session, transparently re-sign-in
        val memCreds = inMemoryZkVerifier
        if (memCreds != null && memCreds.first.equals(cur.email.trim(), ignoreCase = true)) {
            val reauthed = runCatching {
                authenticateWithDerivedPassword("signInWithPassword", memCreds.first, memCreds.second)
            }.getOrNull()
            if (reauthed != null) {
                onSessionRefreshed?.invoke(reauthed)
                return reauthed
            }
        }

        // If token is still un-expired and matches project despite transient refresh failure, allow one attempt
        val notYetExpired = claims?.expEpochSec != null && claims.expEpochSec > nowSec && projectMatches
        if (!force && notYetExpired) {
            return cur
        }

        // Otherwise never hand a known-expired or wrong-project JWT to Firestore!
        activeSession = null
        throw CloudAuthExpiredException(
            "Cloud Sync session expired or invalid for project '$expectedProject'. Please sign in again."
        )
    }

    private suspend fun authenticate(
        action: String,
        email: String,
        syncPassword: String
    ): CloudSyncUserSession {
        val cleanEmail = email.trim().lowercase()
        val zkPassword = deriveZeroKnowledgeAuthPassword(cleanEmail, syncPassword)
        val session = authenticateWithDerivedPassword(action, cleanEmail, zkPassword)
        inMemoryZkVerifier = cleanEmail to zkPassword
        onSessionRefreshed?.invoke(session)
        return session
    }

    private suspend fun authenticateWithDerivedPassword(
        action: String,
        cleanEmail: String,
        zkPassword: String
    ): CloudSyncUserSession {
        val apiKey = currentApiKey().encodeURLParameter()
        val httpResp = httpClient.post(
            "https://identitytoolkit.googleapis.com/v1/accounts:$action?key=$apiKey"
        ) {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    IdentityToolkitAuthRequest.serializer(),
                    IdentityToolkitAuthRequest(
                        email = cleanEmail,
                        password = zkPassword
                    )
                )
            )
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
            uid = parsed.localId.trim(),
            email = parsed.email.trim(),
            idToken = parsed.idToken.trim(),
            refreshToken = parsed.refreshToken?.trim()
        ).also { activeSession = it }
    }

    fun signOut() {
        activeSession = null
        inMemoryZkVerifier = null
    }
}
