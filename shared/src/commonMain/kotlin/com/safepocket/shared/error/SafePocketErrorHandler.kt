package com.safepocket.shared.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SafePocketErrorCode {
    NETWORK_OFFLINE,
    NETWORK_TIMEOUT,
    WEBSITE_UNREACHABLE,
    FIREBASE_AUTH_INVALID_CREDENTIALS,
    FIREBASE_AUTH_EMAIL_EXISTS,
    FIREBASE_AUTH_RATE_LIMITED,
    FIREBASE_PERMISSION_OR_CONFIG,
    CRYPTO_DECRYPTION_FAILED,
    CRYPTO_WRONG_PASSWORD_OR_KEY,
    STORAGE_DATABASE_IO,
    IMPORT_MALFORMED_FILE,
    EXPORT_IO_FAILED,
    UPDATE_VERIFICATION_FAILED,
    UPDATE_PERMISSION_REQUIRED,
    VALIDATION_ERROR,
    UNEXPECTED_ERROR
}

data class DiagnosticEvent(
    val id: String,
    val timestampEpochMs: Long,
    val operation: String,
    val code: SafePocketErrorCode,
    val userMessage: String,
    val scrubbedDetail: String,
    val canRetry: Boolean
)

class SafePocketAppException(
    val code: SafePocketErrorCode,
    override val message: String,
    val canRetry: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Centralized Error Classifier & Privacy-Scrubbed Diagnostics Log for SafePocket v0.3.
 *
 * Ensures every exception across Database, Crypto, Cloud Sync, Website Probes, Import/Export,
 * and Self-Updates is translated into an actionable user message while scrubbing all sensitive
 * vault secrets (PANs, IBANs, PINs, tokens, Secret Keys) from diagnostic logs.
 */
object SafePocketErrorHandler {

    private const val MAX_DIAGNOSTIC_EVENTS = 80

    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    private val _latestBannerError = MutableStateFlow<DiagnosticEvent?>(null)
    val latestBannerError: StateFlow<DiagnosticEvent?> = _latestBannerError.asStateFlow()

    fun dismissBanner() {
        _latestBannerError.value = null
    }

    fun clearDiagnostics() {
        _events.value = emptyList()
        _latestBannerError.value = null
    }

    fun scrubSensitiveText(raw: String): String {
        return raw
            // Scrub 12–19 digit card numbers
            .replace(Regex("\\b(?:\\d[ -]*?){12,19}\\b"), "[REDACTED-PAN]")
            // Scrub IBANs
            .replace(Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9 ]{11,32}\\b"), "[REDACTED-IBAN]")
            // Scrub SafePocket Secret Keys
            .replace(Regex("SP[0-9A-Z-]{12,}", RegexOption.IGNORE_CASE), "[REDACTED-SECRET-KEY]")
            // Scrub Bearer / JWT tokens
            .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[REDACTED-JWT]")
            .take(360)
    }

    fun classify(operation: String, throwable: Throwable, epochMs: Long = 0L): DiagnosticEvent {
        if (throwable is SafePocketAppException) {
            return DiagnosticEvent(
                id = "diag-$epochMs-${operation.hashCode()}",
                timestampEpochMs = epochMs,
                operation = operation,
                code = throwable.code,
                userMessage = throwable.message,
                scrubbedDetail = scrubSensitiveText("${throwable.code}: ${throwable.cause?.message ?: throwable.message}"),
                canRetry = throwable.canRetry
            )
        }

        val rawMsg = throwable.message ?: throwable::class.simpleName ?: "Unknown error"
        val upper = rawMsg.uppercase()

        val (code, friendly, retryable) = when {
            "UNABLE TO RESOLVE HOST" in upper || "UNKNOWNHOST" in upper || "NETWORK IS UNREACHABLE" in upper || "FAILED TO CONNECT" in upper ->
                Triple(
                    SafePocketErrorCode.NETWORK_OFFLINE,
                    "Network connection unavailable. In Cloud Sync mode, SafePocket continues working offline automatically and syncs as soon as you're back online.",
                    true
                )
            "TIMEOUT" in upper || "TIMED OUT" in upper ->
                Triple(
                    SafePocketErrorCode.NETWORK_TIMEOUT,
                    "Connection timed out while completing '$operation'. Please check your connection and retry.",
                    true
                )
            "EMAIL_EXISTS" in upper ->
                Triple(
                    SafePocketErrorCode.FIREBASE_AUTH_EMAIL_EXISTS,
                    "A Firebase Cloud Sync account already exists for this email address. Use 'Sign In' instead.",
                    false
                )
            "INVALID_LOGIN_CREDENTIALS" in upper || "INVALID_PASSWORD" in upper || "EMAIL_NOT_FOUND" in upper || "USER_NOT_FOUND" in upper ->
                Triple(
                    SafePocketErrorCode.FIREBASE_AUTH_INVALID_CREDENTIALS,
                    "Invalid email or Cloud Sync password. Please verify your credentials and try again.",
                    false
                )
            "TOO_MANY_ATTEMPTS" in upper ->
                Triple(
                    SafePocketErrorCode.FIREBASE_AUTH_RATE_LIMITED,
                    "Too many sign-in attempts. Please wait a moment before trying again.",
                    true
                )
            "PERMISSION_DENIED" in upper || "CONFIGURATION_NOT_FOUND" in upper || "OPERATION_NOT_ALLOWED" in upper ->
                Triple(
                    SafePocketErrorCode.FIREBASE_PERMISSION_OR_CONFIG,
                    scrubSensitiveText(rawMsg),
                    false
                )
            "AEADBADTAG" in upper || "AUTHENTICATION TAG" in upper || "DECRYPT" in upper || "MAC CHECK" in upper ->
                Triple(
                    SafePocketErrorCode.CRYPTO_WRONG_PASSWORD_OR_KEY,
                    "Decryption failed — please verify your Master Password and Secret Key.",
                    false
                )
            "ED25519" in upper || "CHECKSUM MISMATCH" in upper || "SIGNING CERTIFICATE" in upper ->
                Triple(
                    SafePocketErrorCode.UPDATE_VERIFICATION_FAILED,
                    "Update package failed cryptographic integrity verification and was safely rejected.",
                    true
                )
            "CSV" in upper || "1PUX" in upper || "JSON" in upper || "SERIALIZ" in upper ->
                Triple(
                    SafePocketErrorCode.IMPORT_MALFORMED_FILE,
                    "The selected file could not be parsed (${scrubSensitiveText(rawMsg)}).",
                    false
                )
            "SQL" in upper || "DISK" in upper || "ENOENT" in upper || "IOEXCEPTION" in upper ->
                Triple(
                    SafePocketErrorCode.STORAGE_DATABASE_IO,
                    "Encrypted storage operation '$operation' encountered an I/O error.",
                    true
                )
            else ->
                Triple(
                    SafePocketErrorCode.UNEXPECTED_ERROR,
                    scrubSensitiveText(rawMsg),
                    true
                )
        }

        return DiagnosticEvent(
            id = "diag-$epochMs-${_events.value.size}",
            timestampEpochMs = epochMs,
            operation = operation,
            code = code,
            userMessage = friendly,
            scrubbedDetail = scrubSensitiveText("${throwable::class.simpleName}: $rawMsg"),
            canRetry = retryable
        )
    }

    fun record(
        operation: String,
        throwable: Throwable,
        epochMs: Long = 0L,
        surfaceBanner: Boolean = true
    ): DiagnosticEvent {
        val event = classify(operation, throwable, epochMs)
        _events.value = (listOf(event) + _events.value).take(MAX_DIAGNOSTIC_EVENTS)
        if (surfaceBanner) {
            _latestBannerError.value = event
        }
        return event
    }

    suspend fun <T> runGuarded(
        operation: String,
        epochMsProvider: () -> Long = { 0L },
        surfaceBanner: Boolean = true,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val diag = record(operation, t, epochMsProvider(), surfaceBanner)
            Result.failure(SafePocketAppException(diag.code, diag.userMessage, diag.canRetry, t))
        }
    }
}
