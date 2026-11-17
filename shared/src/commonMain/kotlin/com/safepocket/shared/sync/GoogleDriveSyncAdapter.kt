package com.safepocket.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Option B: Zero-Knowledge Google Drive Sync Adapter (`appDataFolder`).
 *
 * Uses the OAuth2 scope `https://www.googleapis.com/auth/drive.appdata` ONLY:
 * - SafePocket has zero access to the user's personal Google Drive files.
 * - Stores encrypted CRDT delta batches (`delta-<hlc>.spdelta.enc`) and compacted encrypted
 *   vault snapshots (`vault-<vaultId>.safepocket`) inside the hidden `appDataFolder` space.
 */
class GoogleDriveSyncAdapter(
    private val httpClient: HttpClient,
    private val oauthAccessTokenProvider: suspend () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : CloudSyncAdapter {

    override val providerType: SyncProviderType = SyncProviderType.GOOGLE_DRIVE_APPDATA

    private fun sanitizeToken(raw: String): String {
        val clean = raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        check(clean.isNotEmpty()) {
            "Google Drive OAuth 2.0 Bearer token required (`drive.appdata` scope) — configure it in Settings → Storage & Cloud Sync."
        }
        return clean
    }

    override suspend fun pushEncryptedDeltas(
        vaultId: String,
        deltas: List<EncryptedSyncRecord>
    ): SyncPushResult {
        if (deltas.isEmpty()) return SyncPushResult(0, null)
        val token = sanitizeToken(oauthAccessTokenProvider())
        val maxHlc = deltas.maxOf { it.hlcPacked }
        val fileName = "sp-delta-$vaultId-$maxHlc.json.enc"
        val payloadBytes = json.encodeToString(deltas).encodeToByteArray()

        uploadToAppDataFolder(
            token = token,
            fileName = fileName,
            mimeType = "application/vnd.safepocket.delta+json",
            bytes = payloadBytes
        )
        return SyncPushResult(acceptedCount = deltas.size, latestServerCursorHlc = maxHlc)
    }

    override suspend fun pullEncryptedDeltasSince(
        vaultId: String,
        sinceHlcPacked: String?
    ): List<EncryptedSyncRecord> {
        val token = sanitizeToken(oauthAccessTokenProvider())
        val query = buildString {
            append("name contains 'sp-delta-$vaultId-' and trashed = false")
        }
        val listHttp = httpClient.get("https://www.googleapis.com/drive/v3/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("spaces", "appDataFolder")
            parameter("q", query)
            parameter("fields", "files(id,name,modifiedTime)")
            parameter("orderBy", "name")
        }
        if (listHttp.status.value !in 200..299) {
            val errText: String = runCatching { listHttp.body<String>() }.getOrDefault("")
            throw IllegalStateException("Google Drive appDataFolder pull failed (${listHttp.status.value}): ${errText.take(160)}")
        }
        val listResponse: DriveFileListResponse = listHttp.body()

        return listResponse.files
            .filter { file ->
                val fileHlc = file.name.removePrefix("sp-delta-$vaultId-").removeSuffix(".json.enc")
                sinceHlcPacked == null || fileHlc > sinceHlcPacked
            }
            .flatMap { file ->
                val getHttp = httpClient.get("https://www.googleapis.com/drive/v3/files/${file.id}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("alt", "media")
                }
                if (getHttp.status.value !in 200..299) {
                    throw IllegalStateException("Google Drive delta read failed (${getHttp.status.value})")
                }
                val raw: String = getHttp.body()
                json.decodeFromString<List<EncryptedSyncRecord>>(raw)
            }
    }

    override suspend fun uploadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String,
        encryptedBytes: ByteArray,
        expectedSha256Hex: String
    ) {
        val token = sanitizeToken(oauthAccessTokenProvider())
        uploadToAppDataFolder(
            token = token,
            fileName = "sp-blob-$vaultId-$attachmentId.bin.enc",
            mimeType = "application/octet-stream",
            bytes = encryptedBytes
        )
    }

    override suspend fun downloadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String
    ): ByteArray {
        val token = sanitizeToken(oauthAccessTokenProvider())
        val targetName = "sp-blob-$vaultId-$attachmentId.bin.enc"
        val listHttp = httpClient.get("https://www.googleapis.com/drive/v3/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("spaces", "appDataFolder")
            parameter("q", "name = '$targetName' and trashed = false")
            parameter("fields", "files(id,name)")
        }
        if (listHttp.status.value !in 200..299) {
            throw IllegalStateException("Google Drive blob lookup failed (${listHttp.status.value})")
        }
        val listResponse: DriveFileListResponse = listHttp.body()
        val fileId = listResponse.files.firstOrNull()?.id
            ?: error("Encrypted attachment blob not found in appDataFolder: $attachmentId")
        val dlHttp = httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
        }
        if (dlHttp.status.value !in 200..299) {
            throw IllegalStateException("Google Drive blob download failed (${dlHttp.status.value})")
        }
        return dlHttp.body()
    }

    private suspend fun uploadToAppDataFolder(
        token: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        val boundary = "safepocket_appdata_boundary"
        val metadataJson = """{"name":"$fileName","parents":["appDataFolder"]}"""
        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadataJson)
            append("\r\n--$boundary\r\n")
            append("Content-Type: $mimeType\r\n\r\n")
        }.encodeToByteArray() + bytes + "\r\n--$boundary--".encodeToByteArray()

        val upHttp = httpClient.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
            setBody(multipartBody)
        }
        if (upHttp.status.value !in 200..299) {
            val errText: String = runCatching { upHttp.body<String>() }.getOrDefault("")
            throw IllegalStateException("Google Drive appDataFolder push failed (${upHttp.status.value}): ${errText.take(160)}")
        }
    }
}

@Serializable
private data class DriveFileListResponse(val files: List<DriveFileEntry> = emptyList())

@Serializable
private data class DriveFileEntry(
    val id: String,
    val name: String,
    val modifiedTime: String? = null
)
