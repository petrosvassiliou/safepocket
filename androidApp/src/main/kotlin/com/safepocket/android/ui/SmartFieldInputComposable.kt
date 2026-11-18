package com.safepocket.android.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.intelligence.SmartFieldIntelligenceEngine
import com.safepocket.shared.intelligence.WebsiteReachabilityResult
import com.safepocket.shared.model.CanonicalFieldKeys
import com.safepocket.shared.model.FieldTemplateSpec
import com.safepocket.shared.model.FieldValueType
import com.safepocket.shared.model.VaultCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * SafePocket v0.3 Intelligent Structured Field Input:
 * - Standard localized dropdowns (Bank Account Type, Card Network/Type, Currency, Plan, Subtype, Country)
 * - Automatic `https://` prefixing + asynchronous live HTTPS website existence check
 * - International phone dial-code selector (`+49`, `+30`, `+39`, `+33`, `+34`, `+41`, `+43`, `+44`, `+1`) + E.164 grouping
 * - IBAN 4-block formatting + ISO Mod-97 verification + automatic Bank / BIC / BLZ / Account / Website autofill
 * - Card PAN 4-4-4-4 / Amex 4-6-5 formatting + Luhn check + Card Network autofill
 * - Institution autocomplete chips (`Deutsche Bank`, `ING`, `Sparkasse`, `TK`, `ADAC`, …)
 * - Smart Date (`MM/YY`, `DD.MM.YYYY` -> ISO) + Calendar picker button
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartStructuredFieldInput(
    spec: FieldTemplateSpec,
    category: VaultCategory,
    i18n: LocalizationEngine,
    activeLocaleCode: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCrossFieldAutofill: (Map<String, String>) -> List<String>,
    onVerifyWebsite: suspend (String) -> WebsiteReachabilityResult
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val label = i18n.resolveFieldLabel(spec.key, false, category)
    val dropdownPresets = remember(spec.key, category, activeLocaleCode) {
        SmartFieldIntelligenceEngine.dropdownOptionsForField(spec.key, category, i18n)
    }

    var autofillNotice by remember(spec.key) { mutableStateOf<String?>(null) }
    var secretRevealed by remember(spec.key) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when {
            // 1. Standard Dropdown Selector (Bank Account Type, Card Network, Card Type, Currency, etc.)
            dropdownPresets.isNotEmpty() -> {
                var menuExpanded by remember { mutableStateOf(false) }
                val matchesPreset = dropdownPresets.any {
                    it.storedValue.equals(value, ignoreCase = true) ||
                        it.displayLabel.equals(value, ignoreCase = true)
                }
                var customMode by remember(spec.key) {
                    mutableStateOf(value.isNotBlank() && !matchesPreset)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedCard(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = value.ifBlank { spec.placeholder.ifBlank { "—" } },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (value.isBlank()) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text("▾", style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            ProvideSeamFreeHoverIndication {
                                Column {
                                    dropdownPresets.forEach { option ->
                                        SmoothContextMenuItem(
                                            text = option.displayLabel,
                                            onClick = {
                                                customMode = false
                                                onValueChange(option.storedValue)
                                                menuExpanded = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    SmoothContextMenuItem(
                                        text = "✎ ${i18n.t("option_custom_value")}",
                                        accentHighlight = true,
                                        onClick = {
                                            customMode = true
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (customMode) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = { Text("$label (${i18n.t("option_custom_value")})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 2. Website / Domain Field with Automatic `https://` Prefix + Live Website Existence Probe
            spec.key == CanonicalFieldKeys.INSTITUTION_DOMAIN -> {
                var reachability by remember { mutableStateOf<WebsiteReachabilityResult>(WebsiteReachabilityResult.Idle) }
                var probeTrigger by remember { mutableIntStateOf(0) }

                LaunchedEffect(value, probeTrigger) {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) {
                        reachability = WebsiteReachabilityResult.Idle
                        return@LaunchedEffect
                    }
                    delay(600L)
                    val normalized = SmartFieldIntelligenceEngine.normalizeHttpsUrl(trimmed)
                    if (normalized != trimmed && SmartFieldIntelligenceEngine.isPlausibleHttpsUrl(normalized)) {
                        onValueChange(normalized)
                    }
                    if (SmartFieldIntelligenceEngine.isPlausibleHttpsUrl(normalized)) {
                        reachability = WebsiteReachabilityResult.Checking(normalized)
                        val probeResult = onVerifyWebsite(normalized)
                        reachability = probeResult
                        if (probeResult is WebsiteReachabilityResult.Reachable &&
                            probeResult.normalizedUrl != value
                        ) {
                            onValueChange(probeResult.normalizedUrl)
                        }
                    } else {
                        reachability = WebsiteReachabilityResult.Idle
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { next ->
                            onValueChange(next)
                        },
                        label = { Text("$label (https://)") },
                        placeholder = { Text("https://www.deutsche-bank.de", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && value.isNotBlank()) {
                                    val normalized = SmartFieldIntelligenceEngine.normalizeHttpsUrl(value)
                                    if (normalized != value) {
                                        onValueChange(normalized)
                                    }
                                }
                            }
                    )
                    SmoothOutlinedButton(
                        enabled = value.isNotBlank(),
                        onClick = {
                            val normalized = SmartFieldIntelligenceEngine.normalizeHttpsUrl(value)
                            if (normalized != value) onValueChange(normalized)
                            probeTrigger++
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text("🌐")
                    }
                }

                when (val r = reachability) {
                    is WebsiteReachabilityResult.Checking -> {
                        Text(
                            text = "⏳ ${i18n.t("url_check_verifying")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    is WebsiteReachabilityResult.Reachable -> {
                        Text(
                            text = "✓ ${i18n.t("url_check_reachable", mapOf("url" to r.normalizedUrl))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    is WebsiteReachabilityResult.Unreachable -> {
                        Text(
                            text = "⚠ ${i18n.t("url_check_unreachable", mapOf("url" to r.normalizedUrl))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    WebsiteReachabilityResult.Idle -> Unit
                }
            }

            // 3. International Phone Number with Country Prefix Selector (`+49`, `+30`, `+39`, …)
            spec.valueType == FieldValueType.PHONE || spec.key in setOf(
                CanonicalFieldKeys.PHONE,
                CanonicalFieldKeys.SUPPORT_PHONE,
                CanonicalFieldKeys.EMERGENCY_PHONE
            ) -> {
                var selectedDial by remember(activeLocaleCode) {
                    mutableStateOf(SmartFieldIntelligenceEngine.defaultDialCodeForLocale(activeLocaleCode))
                }
                var dialMenuOpen by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        SmoothOutlinedButton(
                            onClick = { dialMenuOpen = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 13.dp)
                        ) {
                            val flag = SmartFieldIntelligenceEngine.COUNTRY_DIAL_CODES
                                .firstOrNull { it.dialCode == selectedDial }?.flagEmoji ?: "🌍"
                            Text("$flag $selectedDial ▾")
                        }
                        DropdownMenu(
                            expanded = dialMenuOpen,
                            onDismissRequest = { dialMenuOpen = false }
                        ) {
                            ProvideSeamFreeHoverIndication {
                                Column {
                                    SmartFieldIntelligenceEngine.COUNTRY_DIAL_CODES.forEach { dial ->
                                        SmoothContextMenuItem(
                                            text = "${dial.flagEmoji} ${dial.label}",
                                            onClick = {
                                                selectedDial = dial.dialCode
                                                dialMenuOpen = false
                                                if (value.isNotBlank()) {
                                                    val stripped = if (value.startsWith("+")) {
                                                        val oldCc = SmartFieldIntelligenceEngine.COUNTRY_DIAL_CODES
                                                            .map { it.dialCode }
                                                            .sortedByDescending { it.length }
                                                            .firstOrNull { value.startsWith(it) }
                                                        if (oldCc != null) value.removePrefix(oldCc).trim() else value
                                                    } else value
                                                    onValueChange(
                                                        SmartFieldIntelligenceEngine.formatInternationalPhone(
                                                            stripped,
                                                            dial.dialCode
                                                        )
                                                    )
                                                } else {
                                                    onValueChange("${dial.dialCode} ")
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = value,
                        onValueChange = { raw ->
                            val formatted = SmartFieldIntelligenceEngine.autoFormatOnChange(
                                spec.key,
                                FieldValueType.PHONE,
                                raw,
                                selectedDial
                            )
                            onValueChange(formatted)
                        },
                        label = { Text(label) },
                        placeholder = { Text("$selectedDial 170 1234 567", maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && value.isNotBlank()) {
                                    onValueChange(
                                        SmartFieldIntelligenceEngine.formatInternationalPhone(value, selectedDial)
                                    )
                                }
                            }
                    )
                }
            }

            // 4. IBAN with Live 4-Char Formatting, ISO Mod-97 Check & Cross-Field Bank/BIC/Website Autofill
            spec.key == CanonicalFieldKeys.IBAN || spec.valueType == FieldValueType.IBAN -> {
                val analysis = remember(value) { SmartFieldIntelligenceEngine.analyzeAndFormatIban(value) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        val res = SmartFieldIntelligenceEngine.analyzeAndFormatIban(raw)
                        onValueChange(res.formattedIban)
                        if (res.matchedInstitution != null || res.extractedRoutingOrBlz != null) {
                            val suggestions = buildMap {
                                val inst = res.matchedInstitution
                                if (inst != null) {
                                    put(CanonicalFieldKeys.ISSUING_BANK, inst.displayName)
                                }
                                res.suggestedBic?.let { put(CanonicalFieldKeys.BIC, it) }
                                res.extractedRoutingOrBlz?.let { put(CanonicalFieldKeys.ROUTING_CODE, it) }
                                if (res.isMod97Valid) {
                                    res.extractedAccountNumber?.let { put(CanonicalFieldKeys.ACCOUNT_NUMBER, it) }
                                }
                                res.suggestedWebsiteHttps?.let { put(CanonicalFieldKeys.INSTITUTION_DOMAIN, it) }
                                res.suggestedCurrency?.let { put(CanonicalFieldKeys.CURRENCY, it) }
                            }
                            val applied = onCrossFieldAutofill(suggestions)
                            if (applied.isNotEmpty()) {
                                autofillNotice = i18n.t(
                                    "autofill_applied_badge",
                                    mapOf("fields" to applied.joinToString(", "))
                                )
                            }
                        }
                    },
                    label = { Text(label) },
                    placeholder = { Text(spec.placeholder.ifBlank { "DE89 3704 0044 0532 0130 00" }, maxLines = 1) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.replace(" ", "").length >= 15) {
                    Text(
                        text = if (analysis.isMod97Valid) {
                            "✓ ${i18n.t("validation_iban_valid")}"
                        } else if (analysis.isCompleteLength) {
                            "⚠ ${i18n.t("validation_iban_invalid")}"
                        } else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (analysis.isMod97Valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            // 5. Card Number (PAN) with Live 4-4-4-4 / Amex 4-6-5 Formatting, Luhn Check & Network Autofill
            spec.key == CanonicalFieldKeys.CARD_NUMBER || spec.valueType == FieldValueType.CARD_NUMBER -> {
                val panAnalysis = remember(value) { SmartFieldIntelligenceEngine.analyzeAndFormatCardNumber(value) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        val res = SmartFieldIntelligenceEngine.analyzeAndFormatCardNumber(raw)
                        onValueChange(res.formattedPan)
                        if (res.detectedNetworkCode != "CARD" && res.digitsCount >= 4) {
                            val applied = onCrossFieldAutofill(
                                mapOf(CanonicalFieldKeys.CARD_NETWORK to res.detectedNetworkLabel)
                            )
                            if (applied.isNotEmpty()) {
                                autofillNotice = i18n.t(
                                    "autofill_applied_badge",
                                    mapOf("fields" to applied.joinToString(", "))
                                )
                            }
                        }
                    },
                    label = { Text(label) },
                    placeholder = { Text("4532 0192 8834 1109", maxLines = 1) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                if (panAnalysis.digitsCount >= 13) {
                    Text(
                        text = if (panAnalysis.isLuhnValid) {
                            "✓ ${i18n.t("validation_luhn_valid")} • ${panAnalysis.detectedNetworkLabel}"
                        } else {
                            "⚠ ${i18n.t("validation_luhn_invalid")}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (panAnalysis.isLuhnValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }

            // 6. Institution Autocomplete (`ISSUING_BANK`, `INSURER_NAME`, `ORGANIZATION`)
            spec.key in setOf(
                CanonicalFieldKeys.ISSUING_BANK,
                CanonicalFieldKeys.INSURER_NAME,
                CanonicalFieldKeys.ORGANIZATION
            ) -> {
                var isFocused by remember { mutableStateOf(false) }
                val suggestions = remember(value) {
                    SmartFieldIntelligenceEngine.suggestInstitutions(value, limit = 5)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label) },
                    placeholder = if (spec.placeholder.isNotBlank()) {
                        { Text(spec.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                )
                if (isFocused && suggestions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        suggestions.forEach { brand ->
                            SmoothFilterPill(
                                selected = value.equals(brand.displayName, ignoreCase = true),
                                onClick = {
                                    onValueChange(brand.displayName)
                                    val autoMap = buildMap {
                                        put(CanonicalFieldKeys.INSTITUTION_DOMAIN, "https://www.${brand.canonicalDomain}")
                                        val bic = brand.bicPrefixes.firstOrNull()
                                        if (bic != null && category in setOf(VaultCategory.BANK_ACCOUNT, VaultCategory.PAYMENT_CARD)) {
                                            put(CanonicalFieldKeys.BIC, if (bic.length == 8) "${bic}XXX" else bic)
                                        }
                                    }
                                    val applied = onCrossFieldAutofill(autoMap)
                                    if (applied.isNotEmpty()) {
                                        autofillNotice = i18n.t(
                                            "autofill_applied_badge",
                                            mapOf("fields" to applied.joinToString(", "))
                                        )
                                    }
                                },
                                label = brand.displayName
                            )
                        }
                    }
                }
            }

            // 7. ISO Date Fields with Smart `MM/YY` / `DD.MM.YYYY` Normalization + Calendar Picker
            spec.valueType == FieldValueType.DATE_ISO -> {
                val yearMonthPreferred = spec.key in setOf(
                    CanonicalFieldKeys.VALID_FROM,
                    CanonicalFieldKeys.MEMBER_SINCE
                ) || (spec.key == CanonicalFieldKeys.EXPIRY_DATE && category == VaultCategory.PAYMENT_CARD)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        label = { Text(label) },
                        placeholder = {
                            Text(
                                if (yearMonthPreferred) "YYYY-MM / MM/YY" else "YYYY-MM-DD",
                                maxLines = 1
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && value.isNotBlank()) {
                                    onValueChange(
                                        SmartFieldIntelligenceEngine.formatDateIsoInput(value, yearMonthPreferred)
                                    )
                                }
                            }
                    )
                    SmoothOutlinedButton(
                        onClick = {
                            val cal = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val mm = (m + 1).toString().padStart(2, '0')
                                    val dd = d.toString().padStart(2, '0')
                                    onValueChange(if (yearMonthPreferred) "$y-$mm" else "$y-$mm-$dd")
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 13.dp)
                    ) {
                        Text("📅")
                    }
                }
            }

            // 8. Generic / Multiline / Sensitive Secret / BIC / Email Fields
            else -> {
                val isMultiline = spec.valueType in setOf(
                    FieldValueType.MULTILINE_ADDRESS,
                    FieldValueType.MARKDOWN
                )
                val isSecret = spec.valueType == FieldValueType.SENSITIVE_SECRET

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { raw ->
                            onValueChange(
                                SmartFieldIntelligenceEngine.autoFormatOnChange(
                                    spec.key,
                                    spec.valueType,
                                    raw
                                )
                            )
                        },
                        label = { Text(label) },
                        placeholder = if (spec.placeholder.isNotBlank()) {
                            { Text(spec.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else null,
                        visualTransformation = if (isSecret && !secretRevealed) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        singleLine = !isMultiline,
                        minLines = if (isMultiline) 2 else 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSecret) {
                        SmoothTextButton(onClick = { secretRevealed = !secretRevealed }) {
                            Text(if (secretRevealed) i18n.t("action_hide") else i18n.t("action_reveal"))
                        }
                    }
                }
            }
        }

        val currentAutofill = autofillNotice
        if (!currentAutofill.isNullOrBlank()) {
            Text(
                text = "✨ $currentAutofill",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
