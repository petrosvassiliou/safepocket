package com.safepocket.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Option A: Zero-Knowledge Firebase Sync Adapter.
 *
 * - Stores ONLY opaque AES-256-GCM envelopes in Cloud Firestore (`users/{uid}/vaults/{vaultId}/deltas/{entityId}`).
 * - Matches `firebase/firestore.rules` and `pwaApp/src/sync/liveCloudConnectors.ts` 100% (`ivHex`, `ciphertextHex`).
 */
class FirebaseSyncAdapter(
    private val httpClient: HttpClient,
    private val projectId: String = ProvisionedFirebaseProject.PROJECT_ID,
    private val storageBucket: String = ProvisionedFirebaseProject.STORAGE_BUCKET,
    private val userIdProvider: suspend () -> String,
    private val idTokenProvider: suspend () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
) : CloudSyncAdapter {

    override val providerType: SyncProviderType = SyncProviderType.FIREBASE

    override suspend fun pushEncryptedDeltas(
        vaultId: String,
        deltas: List<EncryptedSyncRecord>
    ): SyncPushResult {
        if (deltas.isEmpty()) return SyncPushResult(0, null)
        val uid = userIdProvider()
        val token = idTokenProvider()
        val commitUrl =
            "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents:commit"

        val writes = deltas.map { delta ->
            FirestoreWriteEnvelope.fromSyncRecord(projectId, uid, vaultId, delta)
        }
        val resp = httpClient.post(commitUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(FirestoreCommitRequest(writes)))
        }
        if (!resp.status.isSuccess()) {
            val errText: String = runCatching { resp.body<String>() }.getOrDefault("")
            throw IllegalStateException("Firestore sync push failed (${resp.status.value}): ${errText.take(180)}")
        }
        return SyncPushResult(
            acceptedCount = deltas.size,
            latestServerCursorHlc = deltas.maxOfOrNull { it.hlcPacked }
        )
    }

    override suspend fun pullEncryptedDeltasSince(
        vaultId: String,
        sinceHlcPacked: String?
    ): List<EncryptedSyncRecord> {
        val uid = userIdProvider()
        val token = idTokenProvider()
        val queryUrl =
            "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/users/$uid/vaults/$vaultId:runQuery"
        val resp = httpClient.post(queryUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(FirestoreDeltaQuery.since(sinceHlcPacked))
        }
        if (!resp.status.isSuccess()) {
            val errText: String = runCatching { resp.body<String>() }.getOrDefault("")
            throw IllegalStateException("Firestore sync pull failed (${resp.status.value}): ${errText.take(180)}")
        }
        val responseText: String = resp.body()
        return FirestoreDeltaQuery.parseResponse(json, responseText)
    }

    override suspend fun uploadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String,
        encryptedBytes: ByteArray,
        expectedSha256Hex: String
    ) {
        val uid = userIdProvider()
        val token = idTokenProvider()
        val rawPath = "users/$uid/vaults/$vaultId/blobs/$attachmentId.enc"
        val encodedPath = "users%2F$uid%2Fvaults%2F$vaultId%2Fblobs%2F$attachmentId.enc"
        val boundary = "safepocket_zk_storage_blob_v1"
        val metaJson = """{"name":"$rawPath","contentType":"application/octet-stream","metadata":{"ciphertextSha256":"${expectedSha256Hex.lowercase()}"}}"""
        val prefix = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metaJson\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".encodeToByteArray()
        val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
        val bodyBytes = prefix + encryptedBytes + suffix

        val uploadUrl =
            "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o?uploadType=multipart&name=$encodedPath"
        httpClient.post(uploadUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
            setBody(bodyBytes)
        }
    }

    override suspend fun downloadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String
    ): ByteArray {
        val uid = userIdProvider()
        val token = idTokenProvider()
        val encodedPath = "users%2F$uid%2Fvaults%2F$vaultId%2Fblobs%2F$attachmentId.enc"
        val downloadUrl =
            "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o/$encodedPath?alt=media"
        return httpClient.get(downloadUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()
    }
}

@Serializable
private data class FirestoreCommitRequest(val writes: List<FirestoreWriteEnvelope>)

@Serializable
private data class FirestoreWriteEnvelope(val update: FirestoreDocument) {
    companion object {
        fun fromSyncRecord(
            projectId: String,
            uid: String,
            vaultId: String,
            record: EncryptedSyncRecord
        ): FirestoreWriteEnvelope {
            val docPath =
                "projects/$projectId/databases/(default)/documents/users/$uid/vaults/$vaultId/deltas/${record.entityId}"
            return FirestoreWriteEnvelope(
                update = FirestoreDocument(
                    name = docPath,
                    fields = mapOf(
                        "entityId" to FirestoreFieldValue(stringValue = record.entityId),
                        "entityType" to FirestoreFieldValue(stringValue = record.entityType),
                        "hlcPacked" to FirestoreFieldValue(stringValue = record.hlcPacked),
                        "versionVector" to FirestoreFieldValue(
                            stringValue = record.versionVector.entries.joinToString(
                                prefix = "{",
                                postfix = "}"
                            ) { "\"${it.key}\":${it.value}" }
                        ),
                        "ivHex" to FirestoreFieldValue(stringValue = record.ivHex),
                        "ciphertextHex" to FirestoreFieldValue(stringValue = record.ciphertextHex),
                        "aadContext" to FirestoreFieldValue(stringValue = record.aadContext),
                        "isTombstone" to FirestoreFieldValue(booleanValue = record.isTombstone)
                    )
                )
            )
        }
    }
}

@Serializable
private data class FirestoreDocument(
    val name: String,
    val fields: Map<String, FirestoreFieldValue>
)

@Serializable
private data class FirestoreFieldValue(
    val stringValue: String? = null,
    val booleanValue: Boolean? = null
)

object FirestoreDeltaQuery {
    fun since(hlcPacked: String?): String = if (hlcPacked.isNullOrBlank()) {
        """{"structuredQuery":{"from":[{"collectionId":"deltas"}],"orderBy":[{"field":{"fieldPath":"hlcPacked"},"direction":"ASCENDING"}]}}"""
    } else {
        """{"structuredQuery":{"from":[{"collectionId":"deltas"}],"where":{"fieldFilter":{"field":{"fieldPath":"hlcPacked"},"op":"GREATER_THAN","value":{"stringValue":"$hlcPacked"}}},"orderBy":[{"field":{"fieldPath":"hlcPacked"},"direction":"ASCENDING"}]}}"""
    }

    fun parseResponse(json: Json, raw: String): List<EncryptedSyncRecord> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        val out = mutableListOf<EncryptedSyncRecord>()
        for (elem in arr) {
            val doc = elem.jsonObject["document"]?.jsonObject ?: continue
            val f = doc["fields"]?.jsonObject ?: continue
            val entityId = f["entityId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: continue
            val entityType = f["entityType"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "ITEM"
            val hlcPacked = f["hlcPacked"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: continue
            val vvRaw = f["versionVector"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "{}"
            val ivHex = f["ivHex"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content.orEmpty()
            val ciphertextHex = f["ciphertextHex"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content.orEmpty()
            val aadContext = f["aadContext"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content.orEmpty()
            val isTombstone = f["isTombstone"]?.jsonObject?.get("booleanValue")?.jsonPrimitive?.booleanOrNull ?: false

            val versionVector = runCatching {
                json.parseToJsonElement(vvRaw).jsonObject.mapNotNull { (k, v) ->
                    v.jsonPrimitive.longOrNull?.let { k to it }
                }.toMap()
            }.getOrDefault(emptyMap())

            if (ivHex.isNotBlank() && ciphertextHex.isNotBlank()) {
                out += EncryptedSyncRecord(
                    entityId = entityId,
                    entityType = entityType,
                    hlcPacked = hlcPacked,
                    versionVector = versionVector,
                    isTombstone = isTombstone,
                    ivHex = ivHex,
                    ciphertextHex = ciphertextHex,
                    aadContext = aadContext
                )
            }
        }
        return out
    }
}
