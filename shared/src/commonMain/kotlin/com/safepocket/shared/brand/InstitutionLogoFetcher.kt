package com.safepocket.shared.brand

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class DownloadedBrandAsset(
    val domain: String,
    val dataUri: String,
    val extractedColorHex: String?
)

/**
 * Privacy-Preserving Online Institution Icon Downloader.
 *
 * - Never transmits card numbers, account numbers, names, or vault fields.
 * - Requests ONLY the public domain favicon/touch-icon via HTTPS when `autoDownloadLogos == true`.
 * - Caches the resulting compact `data:image/...;base64,...` URI directly inside the item's
 *   AES-256-GCM encrypted `CardVisualTheme` so it renders 100% offline across all synced devices.
 */
object InstitutionLogoFetcher {

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun fetchDomainLogo(
        httpClient: HttpClient,
        rawDomainOrName: String,
        extractColorFromBytes: ((ByteArray) -> String?)? = null
    ): DownloadedBrandAsset? {
        val domain = InstitutionBrandRegistry.cleanDomain(rawDomainOrName)
            .ifEmpty { InstitutionBrandRegistry.inferDomainFromName(rawDomainOrName).orEmpty() }
        if (domain.isBlank()) return null

        val candidateUrls = listOf(
            "https://$domain/apple-touch-icon.png",
            "https://icons.duckduckgo.com/ip3/$domain.ico",
            "https://$domain/favicon.ico"
        )

        for (url in candidateUrls) {
            val fetched = runCatching {
                val resp = httpClient.get(url)
                if (!resp.status.isSuccess()) return@runCatching null
                val bytes: ByteArray = resp.body()
                if (bytes.size !in 64..131_072) return@runCatching null
                val mime = detectImageMime(bytes) ?: return@runCatching null
                val b64 = Base64.encode(bytes)
                val color = extractColorFromBytes?.invoke(bytes)
                DownloadedBrandAsset(
                    domain = domain,
                    dataUri = "data:$mime;base64,$b64",
                    extractedColorHex = color
                )
            }.getOrNull()
            if (fetched != null) return fetched
        }
        return null
    }

    private fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() &&
            bytes[3] == 0x00.toByte()
        ) {
            return "image/x-icon"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        val head = bytes.take(96).toByteArray().decodeToString()
        if ("<svg" in head.lowercase()) {
            return "image/svg+xml"
        }
        return null
    }
}
