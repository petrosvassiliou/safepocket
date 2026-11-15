package com.safepocket.shared.i18n

import com.safepocket.shared.model.CanonicalFieldKeys
import com.safepocket.shared.model.VaultCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SupportedLocale(
    val code: String,
    val nativeDisplayName: String,
    val defaultRegion: String
) {
    EN("en", "English", "US"),
    DE("de", "Deutsch", "DE"),
    IT("it", "Italiano", "IT"),
    FR("fr", "Français", "FR"),
    ES("es", "Español", "ES"),
    EL("el", "Ελληνικά", "GR");

    companion object {
        fun fromLanguageTag(tag: String?): SupportedLocale {
            if (tag.isNullOrBlank()) return EN
            val prefix = tag.lowercase().substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it.code == prefix } ?: EN
        }
    }
}

/**
 * Reactive Localization Engine supporting:
 * - Automatic OS locale sync + manual runtime override without app restart (`StateFlow`)
 * - Strongly-typed key lookup with English (`en`) fallback
 * - Pluralization (`zero` / `one` / `other`) across Germanic, Romance, and Greek (`el`) rules
 * - Locale-aware regional Identity field labels (Steuer-ID, Codice Fiscale, ΑΦΜ, NIF/NIE, Numéro fiscal / NIR)
 */
class LocalizationEngine(
    initialSystemLanguageTag: String,
    catalogsByCode: Map<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val parsedCatalogs: Map<SupportedLocale, Map<String, String>> =
        SupportedLocale.entries.associateWith { locale ->
            val raw = catalogsByCode[locale.code] ?: "{}"
            json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }

    private var systemLocale: SupportedLocale = SupportedLocale.fromLanguageTag(initialSystemLanguageTag)
    private val _manualOverride = MutableStateFlow<SupportedLocale?>(null)
    private val _activeLocale = MutableStateFlow(systemLocale)

    val activeLocale: StateFlow<SupportedLocale> = _activeLocale.asStateFlow()
    val isFollowingSystem: Boolean get() = _manualOverride.value == null

    fun setManualLocaleOverride(locale: SupportedLocale?) {
        _manualOverride.value = locale
        _activeLocale.value = locale ?: systemLocale
    }

    fun onSystemLocaleChanged(newSystemLanguageTag: String) {
        systemLocale = SupportedLocale.fromLanguageTag(newSystemLanguageTag)
        if (_manualOverride.value == null) {
            _activeLocale.value = systemLocale
        }
    }

    fun t(key: String, params: Map<String, Any> = emptyMap()): String {
        val locale = _activeLocale.value
        val template = parsedCatalogs[locale]?.get(key)
            ?: parsedCatalogs[SupportedLocale.EN]?.get(key)
            ?: key
        return params.entries.fold(template) { acc, (k, v) ->
            acc.replace("{$k}", v.toString())
        }
    }

    fun plural(baseKey: String, count: Long, extraParams: Map<String, Any> = emptyMap()): String {
        val locale = _activeLocale.value
        val suffix = when {
            count == 0L && parsedCatalogs[locale]?.containsKey("${baseKey}_zero") == true -> "zero"
            // In French, both 0 and 1 take singular; in EN/DE/IT/ES/EL, 1 takes singular
            locale == SupportedLocale.FR && (count == 0L || count == 1L) -> "one"
            count == 1L -> "one"
            else -> "other"
        }
        return t("${baseKey}_$suffix", extraParams + ("count" to count))
    }

    /**
     * Resolves a stored language-agnostic field key (e.g. `field_tax_id`) into its localized label,
     * upgrading Identity items to regional statutory terminology (*Steuer-ID*, *Codice Fiscale*, *ΑΦΜ (AFM)*, etc.).
     */
    fun resolveFieldLabel(
        labelKey: String,
        isCustomLabel: Boolean,
        category: VaultCategory
    ): String {
        if (isCustomLabel) return labelKey
        if (category == VaultCategory.IDENTITY) {
            when (labelKey) {
                CanonicalFieldKeys.TAX_ID -> return t("identity_template_tax_id")
                CanonicalFieldKeys.SSN -> return t("identity_template_ssn")
                CanonicalFieldKeys.HEALTH_INSURANCE -> return t("identity_template_health_insurance")
            }
        }
        return t(labelKey)
    }
}
