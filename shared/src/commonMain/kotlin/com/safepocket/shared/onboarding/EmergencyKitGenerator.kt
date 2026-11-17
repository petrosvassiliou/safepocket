package com.safepocket.shared.onboarding

import com.safepocket.shared.crypto.Argon2idParameters
import kotlin.math.log2

/**
 * Generates printable Zero-Knowledge SafePocket Emergency Kits and evaluates Master Password
 * Shannon/charset entropy locally with zero network requests.
 */
object EmergencyKitGenerator {

    data class PasswordEntropyReport(
        val estimatedBits: Int,
        val rating: String // "Weak" | "Moderate" | "Strong" | "Vault-Grade"
    )

    fun estimatePasswordEntropy(password: String): PasswordEntropyReport {
        if (password.isEmpty()) return PasswordEntropyReport(0, "Weak")
        var pool = 0
        if (password.any { it in 'a'..'z' }) pool += 26
        if (password.any { it in 'A'..'Z' }) pool += 26
        if (password.any { it in '0'..'9' }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        if (password.any { it.code > 127 }) pool += 64

        val bits = (password.length * log2(pool.coerceAtLeast(2).toDouble())).toInt()
        val rating = when {
            bits >= 95 -> "Vault-Grade"
            bits >= 72 -> "Strong"
            bits >= 48 -> "Moderate"
            else -> "Weak"
        }
        return PasswordEntropyReport(bits, rating)
    }

    fun buildRecoverySecretKeyUri(vaultId: String, secretKeyFormatted: String): String =
        "safepocket-recovery://v1?vaultId=${vaultId.trim()}&secretKey=${secretKeyFormatted.trim().uppercase()}"

    fun buildPrintableEmergencyKitHtml(
        vaultId: String,
        createdAtIso: String,
        secretKeyFormatted: String,
        kdfParams: Argon2idParameters,
        passwordHint: String? = null
    ): String {
        val hintSection = if (!passwordHint.isNullOrBlank()) {
            "<div style=\"margin-top:8px;font-size:12.5px;color:#334155;\"><strong>Memory Hint:</strong> $passwordHint</div>"
        } else ""
        val recoveryUri = buildRecoverySecretKeyUri(vaultId, secretKeyFormatted)
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <title>SafePocket v0.3.1 Emergency Kit — $vaultId</title>
          <style>
            @page { size: A4 portrait; margin: 14mm; }
            body { font-family: system-ui, -apple-system, sans-serif; color: #111827; max-width: 700px; margin: 28px auto; padding: 24px; border: 2px solid #111827; border-radius: 14px; }
            h1 { margin: 0 0 6px; font-size: 21px; }
            .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 16px; letter-spacing: 0.06em; background: #f3f4f6; padding: 14px; border-radius: 8px; border: 1px solid #d1d5db; }
            .write-box { height: 54px; border: 2px dashed #6b7280; border-radius: 8px; margin-top: 8px; }
            .meta { font-size: 11.5px; font-family: ui-monospace, monospace; color: #4b5563; margin-top: 18px; }
          </style>
        </head>
        <body>
          <h1>SafePocket v0.3.1 — Zero-Knowledge Emergency Kit</h1>
          <p>Store this sheet in a physical safe. SafePocket operates with strict zero-knowledge local encryption; neither SafePocket nor any cloud provider can recover your Secret Key or Master Password.</p>
          <h3>1. Local Secret Key (Required on New Devices)</h3>
          <div class="mono">$secretKeyFormatted</div>
          <div style="font-size:11px;color:#6b7280;margin-top:4px;font-family:ui-monospace,monospace;">Recovery URI: $recoveryUri</div>
          <h3>2. Master Password (Write by Hand — Never Stored Here)</h3>
          <div class="write-box"></div>
          $hintSection
          <div class="meta">
            Vault ID: $vaultId • Created: $createdAtIso • SafePocket v0.3.2 (Build 7)<br/>
            KDF: Argon2id (${kdfParams.memoryKiB} KiB, t=${kdfParams.iterations}, p=${kdfParams.parallelism}) → HKDF-SHA256 → AES-256-GCM
          </div>
        </body>
        </html>
        """.trimIndent()
    }
}
