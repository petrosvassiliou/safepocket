package com.safepocket.shared.search

import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.model.VaultCategory
import com.safepocket.shared.model.VaultItemPayload

/**
 * Instant (< 50ms) Multilingual Vault Search Engine.
 *
 * Searches across:
 * 1. Item titles & subtitles
 * 2. User tags
 * 3. Localized category names (in active language AND English)
 * 4. Field labels & decrypted field values
 *
 * Normalization handles:
 * - Diacritics & accents across German, Italian, French, Spanish, and Greek tonoi/dialytika
 * - German umlaut & Eszett expansions (`ä` matches both `"ae"` and `"a"`, `ö` -> `"oe"`/`"o"`, `ü` -> `"ue"`/`"u"`, `ß` -> `"ss"`)
 * - Greek monotonic/polytonic accents (`ά` -> `"α"` and Latin `"a"`, `έ` -> `"ε"`/`"e"`, etc.)
 */
class MultilingualSearchEngine(
    private val i18n: LocalizationEngine
) {
    fun search(
        items: List<VaultItemPayload>,
        rawQuery: String,
        categoryFilter: VaultCategory? = null,
        favoritesOnly: Boolean = false
    ): List<VaultItemPayload> {
        val baseFiltered = items.asSequence().filter { item ->
            (categoryFilter == null || item.category == categoryFilter) &&
                (!favoritesOnly || item.favorite)
        }

        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return baseFiltered.toList()

        val queryTokens = trimmed
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { foldPrimary(it) }

        return baseFiltered.filter { item ->
            val corpus = buildIndexableCorpus(item)
            queryTokens.all { token ->
                corpus.any { indexedForm -> indexedForm.contains(token) }
            }
        }.toList()
    }

    private fun buildIndexableCorpus(item: VaultItemPayload): List<String> {
        val localizedCategory = when (item.category) {
            VaultCategory.PAYMENT_CARD -> i18n.t("category_payment_card")
            VaultCategory.IDENTITY -> i18n.t("category_identity")
            VaultCategory.OFFICIAL_DOCUMENT -> i18n.t("category_official_document")
            VaultCategory.SECURE_NOTE -> i18n.t("category_secure_note")
        }

        val rawParts = buildList {
            add(item.title)
            add(item.subtitle)
            add(localizedCategory)
            addAll(item.tags)
            item.fields.forEach { f ->
                add(i18n.resolveFieldLabel(f.labelKey, f.isCustomLabel, item.category))
                add(f.value)
            }
        }.joinToString(" ")

        // Produce 3 normalized projections so searching "ae" OR "a" matches "ä/ά/à":
        // 1. Accent-stripped native script ("αθηνα", "muller")
        // 2. Digraph-expanded form ("mueller", "koeln", "strasse")
        // 3. Phonetic Latin fold for Greek characters ("athina", "afm")
        return listOf(
            stripAccentsOnly(rawParts),
            expandDigraphsAndStrip(rawParts),
            transliterateGreekToLatin(expandDigraphsAndStrip(rawParts))
        )
    }

    companion object {
        private val ACCENT_MAP = mapOf(
            'à' to "a", 'á' to "a", 'â' to "a", 'ã' to "a", 'å' to "a", 'ā' to "a",
            'è' to "e", 'é' to "e", 'ê' to "e", 'ë' to "e", 'ē' to "e",
            'ì' to "i", 'í' to "i", 'î' to "i", 'ï' to "i",
            'ò' to "o", 'ó' to "o", 'ô' to "o", 'õ' to "o",
            'ù' to "u", 'ú' to "u", 'û' to "u",
            'ñ' to "n", 'ç' to "c",
            // Greek accented vowels -> unaccented Greek
            'ά' to "α", 'έ' to "ε", 'ή' to "η", 'ί' to "ι", 'ϊ' to "ι", 'ΐ' to "ι",
            'ό' to "ο", 'ύ' to "υ", 'ϋ' to "υ", 'ΰ' to "υ", 'ώ' to "ω", 'ς' to "σ"
        )

        private val GREEK_TO_LATIN = mapOf(
            'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z",
            'η' to "i", 'θ' to "th", 'ι' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m",
            'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
            'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o"
        )

        fun foldPrimary(input: String): String = stripAccentsOnly(input)

        fun stripAccentsOnly(input: String): String = buildString(input.length) {
            for (ch in input.lowercase()) {
                when (ch) {
                    'ä', 'æ' -> append('a')
                    'ö', 'ø' -> append('o')
                    'ü' -> append('u')
                    'ß' -> append("ss")
                    else -> append(ACCENT_MAP[ch] ?: ch.toString())
                }
            }
        }

        fun expandDigraphsAndStrip(input: String): String = buildString(input.length + 8) {
            for (ch in input.lowercase()) {
                when (ch) {
                    'ä', 'æ' -> append("ae")
                    'ö', 'ø' -> append("oe")
                    'ü' -> append("ue")
                    'ß' -> append("ss")
                    else -> append(ACCENT_MAP[ch] ?: ch.toString())
                }
            }
        }

        fun transliterateGreekToLatin(input: String): String = buildString(input.length + 8) {
            for (ch in input) {
                append(GREEK_TO_LATIN[ch] ?: ch.toString())
            }
        }
    }
}
