package com.safepocket.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.model.ThemePreference
import com.safepocket.shared.model.VaultCategory
import com.safepocket.shared.model.VaultItemPayload
import com.safepocket.shared.update.AppBuildInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adaptive Material Design 3 Expressive Shell for:
 * 1. Compact Smartphones (< 600dp): Bottom NavigationBar + single pane
 * 2. Unfolded Foldables (600dp..839dp): NavigationRail + Hinge-aware 2-pane List-Detail
 * 3. Googlebook OS Desktop (>= 840dp): 3-Column Freeform Desktop Layout
 *    (Navigation Sidebar -> Item List -> Detail Inspector) with hardware keyboard shortcuts
 *    (`Ctrl+F`, `Ctrl+N`, `Ctrl+L`), hover states, right-click quick copy, drag-and-drop scans,
 *    Light Theme default + Dark Theme option, and subtle version badge (`SafePocket v0.1 (Build 1)`).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveSafePocketShell(
    i18n: LocalizationEngine,
    items: List<VaultItemPayload>,
    selectedCategory: VaultCategory?,
    selectedItem: VaultItemPayload?,
    themePreference: ThemePreference = ThemePreference.LIGHT,
    useDeviceDynamicColor: Boolean = true,
    onSelectThemePreference: (ThemePreference) -> Unit = {},
    onToggleDeviceDynamicColor: (Boolean) -> Unit = {},
    isFoldableSeparating: Boolean = false,
    onSelectCategory: (VaultCategory?) -> Unit,
    onSelectItem: (VaultItemPayload) -> Unit,
    onFocusSearch: () -> Unit,
    onNewItem: () -> Unit,
    onLockVault: () -> Unit,
    onOpenSettings: () -> Unit,
    onDropDocumentAttachment: (DragAndDropEvent) -> Boolean
) {
    val activeLocale by i18n.activeLocale.collectAsState()
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var clipboardJob by remember { mutableStateOf<Job?>(null) }

    // Material 3 Expressive: Light Theme default, Dark or Use Device Theme (SYSTEM) + Dynamic Device Color
    val useDarkTheme = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        useDeviceDynamicColor && useDarkTheme -> dynamicDarkColorScheme(context)
        useDeviceDynamicColor && !useDarkTheme -> dynamicLightColorScheme(context)
        useDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    val copyWith30sHygiene: (String, String, Boolean) -> Unit = { label, secretValue, isSensitive ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, secretValue).apply {
            if (isSensitive) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        if (isSensitive) {
            clipboardJob?.cancel()
            clipboardJob = coroutineScope.launch {
                delay(30_000L)
                clipboard.clearPrimaryClip()
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Googlebook OS Desktop Keyboard Shortcuts: Ctrl+F, Ctrl+N, Ctrl+L
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                        when (event.key) {
                            Key.F -> { onFocusSearch(); true }
                            Key.N -> { onNewItem(); true }
                            Key.L -> { onLockVault(); true }
                            else -> false
                        }
                    } else false
                }
        ) {
            when {
                // Googlebook OS & Large Desktop Windows: 3-Column Desktop Layout (when not split by a foldable hinge)
                widthDp >= 840 && !isFoldableSeparating -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        PermanentDrawerSheet(
                            modifier = Modifier
                                .width(272.dp)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = i18n.t("app_name"),
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        TextButton(
                                            onClick = {
                                                val next = when (themePreference) {
                                                    ThemePreference.LIGHT -> ThemePreference.DARK
                                                    ThemePreference.DARK -> ThemePreference.SYSTEM
                                                    ThemePreference.SYSTEM -> ThemePreference.LIGHT
                                                }
                                                onSelectThemePreference(next)
                                            }
                                        ) {
                                            Text(
                                                when (themePreference) {
                                                    ThemePreference.LIGHT -> "☀"
                                                    ThemePreference.DARK -> "☾"
                                                    ThemePreference.SYSTEM -> "◐"
                                                }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    CategoryDrawerDestinations(
                                        i18n = i18n,
                                        localeKey = activeLocale.code,
                                        selectedCategory = selectedCategory,
                                        onSelectCategory = onSelectCategory
                                    )
                                }

                                // Subtle version footer in desktop sidebar + Settings & Lock actions
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = onOpenSettings,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(i18n.t("nav_settings"))
                                    }
                                    HorizontalDivider()
                                    Text(
                                        text = i18n.t(
                                            "update_version_display",
                                            mapOf(
                                                "version" to AppBuildInfo.CURRENT_VERSION_NAME,
                                                "build" to AppBuildInfo.CURRENT_VERSION_CODE
                                            )
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Column 2: Vault Item List
                        Column(
                            modifier = Modifier
                                .width(340.dp)
                                .fillMaxHeight()
                                .padding(12.dp)
                        ) {
                            VaultItemListPane(items, selectedItem, onSelectItem)
                        }

                        // Column 3: Detail Inspector with Drag-and-Drop scan target
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .dragAndDropTarget(
                                    shouldStartDragAndDrop = { true },
                                    target = remember {
                                        object : DragAndDropTarget {
                                            override fun onDrop(event: DragAndDropEvent): Boolean =
                                                onDropDocumentAttachment(event)
                                        }
                                    }
                                )
                                .padding(20.dp)
                        ) {
                            VaultDetailInspectorPane(i18n, selectedItem, copyWith30sHygiene)
                        }
                    }
                }

                // Unfolded Foldable Posture: NavigationRail + 2-Pane List-Detail
                widthDp >= 600 -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail {
                            VaultCategory.entries.forEach { cat ->
                                NavigationRailItem(
                                    selected = selectedCategory == cat,
                                    onClick = { onSelectCategory(cat) },
                                    icon = { Text(cat.name.take(2)) },
                                    label = { Text(categoryLabel(i18n, cat)) }
                                )
                            }
                        }
                        Row(modifier = Modifier.weight(1f)) {
                            Box(Modifier.weight(0.42f).padding(12.dp)) {
                                VaultItemListPane(items, selectedItem, onSelectItem)
                            }
                            Box(Modifier.weight(0.58f).padding(16.dp)) {
                                VaultDetailInspectorPane(i18n, selectedItem, copyWith30sHygiene)
                            }
                        }
                    }
                }

                // Smartphone Compact: Bottom NavigationBar (Top 5 categories; full list in drawer/filter)
                else -> {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                VaultCategory.entries.take(5).forEach { cat ->
                                    NavigationBarItem(
                                        selected = selectedCategory == cat,
                                        onClick = { onSelectCategory(cat) },
                                        icon = { Text(cat.name.take(2)) },
                                        label = { Text(categoryLabel(i18n, cat), maxLines = 2) }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(Modifier.padding(innerPadding).padding(12.dp)) {
                            if (selectedItem == null) {
                                VaultItemListPane(items, null, onSelectItem)
                            } else {
                                VaultDetailInspectorPane(i18n, selectedItem, copyWith30sHygiene)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDrawerDestinations(
    i18n: LocalizationEngine,
    localeKey: String,
    selectedCategory: VaultCategory?,
    onSelectCategory: (VaultCategory?) -> Unit
) {
    key(localeKey) {
        NavigationDrawerItem(
            label = { Text(i18n.t("nav_all_items")) },
            selected = selectedCategory == null,
            onClick = { onSelectCategory(null) }
        )
        VaultCategory.entries.forEach { category ->
            NavigationDrawerItem(
                label = { Text(categoryLabel(i18n, category)) },
                selected = selectedCategory == category,
                onClick = { onSelectCategory(category) }
            )
        }
    }
}

private fun categoryLabel(i18n: LocalizationEngine, category: VaultCategory): String =
    when (category) {
        VaultCategory.PAYMENT_CARD -> i18n.t("nav_payment_cards")
        VaultCategory.BANK_ACCOUNT -> i18n.t("nav_bank_accounts")
        VaultCategory.INSURANCE_CARD -> i18n.t("nav_insurance_cards")
        VaultCategory.MEMBERSHIP_CARD -> i18n.t("nav_membership_cards")
        VaultCategory.IDENTITY -> i18n.t("nav_identities")
        VaultCategory.OFFICIAL_DOCUMENT -> i18n.t("nav_official_documents")
        VaultCategory.SECURE_NOTE -> i18n.t("nav_secure_notes")
    }

@Composable
private fun VaultItemListPane(
    items: List<VaultItemPayload>,
    selectedItem: VaultItemPayload?,
    onSelectItem: (VaultItemPayload) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val interactionSource = remember { MutableInteractionSource() }
            ElevatedCard(
                onClick = { onSelectItem(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .hoverable(interactionSource)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultDetailInspectorPane(
    i18n: LocalizationEngine,
    selectedItem: VaultItemPayload?,
    onCopyField: (label: String, value: String, isSensitive: Boolean) -> Unit
) {
    if (selectedItem == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(i18n.t("app_tagline"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(selectedItem.title, style = MaterialTheme.typography.headlineSmall)
        selectedItem.fields.forEach { field ->
            val resolvedLabel = i18n.resolveFieldLabel(
                field.labelKey,
                field.isCustomLabel,
                selectedItem.category
            )
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(resolvedLabel, style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (field.isSensitive) "••••••••" else field.value,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    FilledTonalButton(
                        onClick = { onCopyField(resolvedLabel, field.value, field.isSensitive) }
                    ) {
                        Text(i18n.t("action_copy"))
                    }
                }
            }
        }
    }
}
