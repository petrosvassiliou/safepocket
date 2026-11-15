package com.safepocket.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Option B: Zero-Knowledge Google Drive Sync Adapter (`appDataFolder`).
 *
 * Uses the OAuth2 scope `https://www.googleapis.com/auth/drive.appdata` ONLY:
 * -SafePocket has zero access to the user's personal Google Drive files.
 * -Stores encrypted CRDT delta batches (`delta-<hlc>.spdelta.enc`) and compacted encrypted
 *  vault snapshots (`vault-<vaultId>.safepocket`) inside the hidden `appDataFolder` space.
 */
class GoogleDriveSyncAdapter(
    private val httpClient: HttpClient,
    private val oauthAccessTokenProvider: suspend () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : CloudSyncAdapter {

    override val providerType: SyncProviderType = SyncProviderType.GOOGLE_DRIVE_APPDATA

    override suspend fun pushEncryptedDeltas(
        vaultId: String,
        deltas: List<EncryptedSyncRecord>
    ): SyncPushResult {
        if (deltas.isEmpty()) return SyncPushResult(0, null)
        val token = oauthAccessTokenProvider()
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
        val token = oauthAccessTokenProvider()
        val query = buildString {
            append("name contains 'sp-delta-$vaultId-' and trashed = false")
        }
        val listResponse: DriveFileListResponse =
            httpClient.get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("spaces", "appDataFolder")
                parameter("q", query)
                parameter("fields", "files(id,name,modifiedTime)")
                parameter("orderBy", "name")
            }.body()

        return listResponse.files
            .filter { file ->
                val fileHlc = file.name.removePrefix("sp-delta-$vaultId-").removeSuffix(".json.enc")
                sinceHlcPacked == null || fileHlc > sinceHlcPacked
            }
            .flatMap { file ->
                val raw: String =
                    httpClient.get("https://www.googleapis.com/drive/v3/files/${file.id}") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        parameter("alt", "media")
                    }.body()
                json.decodeFromString<List<EncryptedSyncRecord>>(raw)
            }
    }

    override suspend fun uploadEncryptedAttachmentBlob(
        vaultId: String,
        attachmentId: String,
        encryptedBytes: ByteArray,
        expectedSha256Hex: String
    ) {
        val token = oauthAccessTokenProvider()
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
        val token = oauthAccessTokenProvider()
        val targetName = "sp-blob-$vaultId-$attachmentId.bin.enc"
        val listResponse: DriveFileListResponse =
            httpClient.get("https://www.googleapis.com/drive/v3/files") {
                header(HttpHeaders.Authorization, "Bearer $token")
                parameter("spaces", "appDataFolder")
                parameter("q", "name = '$targetName' and trashed = false")
                parameter("fields", "files(id,name)")
            }.body()
        val fileId = listResponse.files.firstOrNull()?.id
            ?: error("Encrypted attachment blob not found in appDataFolder: $attachmentId")
        return httpClient.get("https://www.googleapis.com/drive/v3/files/$fileId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("alt", "media")
        }.body()
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

        httpClient.post("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, "multipart/related; boundary=$boundary")
            setBody(multipartBody)
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
