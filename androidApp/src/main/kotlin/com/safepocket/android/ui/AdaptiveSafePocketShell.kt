package com.safepocket.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.i18n.SupportedLocale
import com.safepocket.shared.model.*
import com.safepocket.shared.search.MultilingualSearchEngine
import com.safepocket.shared.update.AppBuildInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adaptive Material Design 3 Expressive Shell for:
 * 1. Compact Smartphones (< 600dp): Top header + scrollable category chips + list/inspector
 * 2. Unfolded Foldables (600dp..839dp): Scrollable NavigationRail + 2-pane List-Detail
 * 3. Googlebook OS / ChromeOS Desktop (>= 840dp): 3-Column Freeform Desktop Layout
 *    (Navigation Sidebar -> Search & Item List -> Detail Inspector) with hardware keyboard shortcuts
 *    (`Ctrl+F`, `Ctrl+N`, `Ctrl+L`), localized 6-language labels, and live Screenshot toggle in Settings.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSafePocketShell(
    i18n: LocalizationEngine,
    items: List<VaultItemPayload>,
    selectedCategory: VaultCategory?,
    favoritesOnly: Boolean = false,
    selectedItem: VaultItemPayload?,
    themePreference: ThemePreference = ThemePreference.LIGHT,
    useDeviceDynamicColor: Boolean = true,
    allowScreenshots: Boolean = true,
    isVaultLocked: Boolean = false,
    showSettingsDialog: Boolean = false,
    showNewItemDialog: Boolean = false,
    focusSearchTrigger: Int = 0,
    onSelectThemePreference: (ThemePreference) -> Unit = {},
    onToggleDeviceDynamicColor: (Boolean) -> Unit = {},
    onToggleAllowScreenshots: (Boolean) -> Unit = {},
    onSelectLocaleOverride: (SupportedLocale?) -> Unit = {},
    isFoldableSeparating: Boolean = false,
    onSelectCategory: (VaultCategory?) -> Unit,
    onSelectFavorites: () -> Unit = {},
    onSelectItem: (VaultItemPayload?) -> Unit,
    onToggleFavoriteItem: (VaultItemPayload) -> Unit = {},
    onDeleteItem: (VaultItemPayload) -> Unit = {},
    onCreateItem: (VaultItemPayload) -> Unit = {},
    onFocusSearch: () -> Unit,
    onNewItem: () -> Unit,
    onDismissNewItem: () -> Unit = {},
    onLockVault: () -> Unit,
    onUnlockVault: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onDismissSettings: () -> Unit = {},
    onDropDocumentAttachment: (DragAndDropEvent) -> Boolean
) {
    val activeLocale by i18n.activeLocale.collectAsState()
    val manualLocaleOverride by i18n.manualOverride.collectAsState()
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var clipboardJob by remember { mutableStateOf<Job?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusSearchTrigger) {
        if (focusSearchTrigger > 0) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val searchEngine = remember(i18n, activeLocale) { MultilingualSearchEngine(i18n) }
    val filteredItems = remember(items, searchQuery, selectedCategory, favoritesOnly, activeLocale) {
        val searched = searchEngine.search(
            items = items,
            rawQuery = searchQuery,
            categoryFilter = selectedCategory,
            favoritesOnly = favoritesOnly
        )
        searchEngine.sortItems(searched, VaultSortMode.PINNED_RECENT)
    }

    val useDarkTheme = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val supportsDynamicColor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val colorScheme = when {
        useDeviceDynamicColor && supportsDynamicColor && useDarkTheme -> dynamicDarkColorScheme(context)
        useDeviceDynamicColor && supportsDynamicColor && !useDarkTheme -> dynamicLightColorScheme(context)
        useDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    val copyWith30sHygiene: (String, String, Boolean) -> Unit = { label, secretValue, isSensitive ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, secretValue).apply {
            if (isSensitive) {
                description.extras = PersistableBundle().apply {
                    val key = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        ClipDescription.EXTRA_IS_SENSITIVE
                    } else {
                        "android.content.extra.IS_SENSITIVE"
                    }
                    putBoolean(key, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        clipboardJob?.cancel()
        clipboardJob = coroutineScope.launch {
            copiedNotice = if (isSensitive) {
                i18n.t("clipboard_hygiene_active", mapOf("seconds" to 30))
            } else {
                "${i18n.t("action_copied")}: $label"
            }
            delay(if (isSensitive) 30_000L else 3_500L)
            if (isSensitive) {
                clipboard.clearPrimaryClip()
            }
            copiedNotice = null
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        key(activeLocale.code) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
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
                if (isVaultLocked) {
                    LockedVaultView(i18n = i18n, onUnlock = onUnlockVault)
                } else {
                    when {
                        // Googlebook OS / ChromeOS & Large Desktop Windows: 3-Column Desktop Layout
                        widthDp >= 840 && !isFoldableSeparating -> {
                            Row(modifier = Modifier.fillMaxSize()) {
                                PermanentDrawerSheet(
                                    modifier = Modifier
                                        .width(268.dp)
                                        .fillMaxHeight()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = i18n.t("app_name"),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
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
                                            Spacer(Modifier.height(4.dp))
                                            CategoryDrawerDestinations(
                                                i18n = i18n,
                                                selectedCategory = selectedCategory,
                                                favoritesOnly = favoritesOnly,
                                                onSelectCategory = onSelectCategory,
                                                onSelectFavorites = onSelectFavorites
                                            )
                                        }

                                        Column(
                                            modifier = Modifier.padding(top = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            HorizontalDivider()
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                FilledTonalButton(
                                                    onClick = onOpenSettings,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(
                                                        text = "⚙ ${i18n.t("nav_settings")}",
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                OutlinedButton(onClick = onLockVault) {
                                                    Text("🔒")
                                                }
                                            }
                                            Text(
                                                text = i18n.t(
                                                    "update_version_display",
                                                    mapOf(
                                                        "version" to AppBuildInfo.CURRENT_VERSION_NAME,
                                                        "build" to AppBuildInfo.CURRENT_VERSION_CODE
                                                    )
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }

                                // Column 2: Search + Vault Item List
                                Column(
                                    modifier = Modifier
                                        .width(336.dp)
                                        .fillMaxHeight()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    SearchAndCreateHeader(
                                        i18n = i18n,
                                        query = searchQuery,
                                        onQueryChange = { searchQuery = it },
                                        onNewItem = onNewItem,
                                        searchFocusRequester = searchFocusRequester
                                    )
                                    VaultItemListPane(
                                        i18n = i18n,
                                        items = filteredItems,
                                        selectedItem = selectedItem,
                                        onSelectItem = { onSelectItem(it) },
                                        modifier = Modifier.weight(1f)
                                    )
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
                                    VaultDetailInspectorPane(
                                        i18n = i18n,
                                        selectedItem = selectedItem,
                                        copiedNotice = copiedNotice,
                                        onCopyField = copyWith30sHygiene,
                                        onToggleFavorite = onToggleFavoriteItem,
                                        onDelete = onDeleteItem
                                    )
                                }
                            }
                        }

                        // Unfolded Foldable Posture: NavigationRail + 2-Pane List-Detail
                        widthDp >= 600 -> {
                            Row(modifier = Modifier.fillMaxSize()) {
                                NavigationRail(
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                    header = {
                                        IconButton(onClick = onOpenSettings) {
                                            Text("⚙")
                                        }
                                    }
                                ) {
                                    NavigationRailItem(
                                        selected = selectedCategory == null && !favoritesOnly,
                                        onClick = { onSelectCategory(null) },
                                        icon = { Text("📥") },
                                        label = { Text(i18n.t("nav_all_items"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                    VaultCategory.entries.forEach { cat ->
                                        NavigationRailItem(
                                            selected = selectedCategory == cat,
                                            onClick = { onSelectCategory(cat) },
                                            icon = { Text(categoryIcon(cat)) },
                                            label = { Text(categoryLabel(i18n, cat), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        )
                                    }
                                }
                                Row(modifier = Modifier.weight(1f)) {
                                    Column(
                                        modifier = Modifier
                                            .weight(0.44f)
                                            .fillMaxHeight()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        SearchAndCreateHeader(
                                            i18n = i18n,
                                            query = searchQuery,
                                            onQueryChange = { searchQuery = it },
                                            onNewItem = onNewItem,
                                            searchFocusRequester = searchFocusRequester
                                        )
                                        VaultItemListPane(
                                            i18n = i18n,
                                            items = filteredItems,
                                            selectedItem = selectedItem,
                                            onSelectItem = { onSelectItem(it) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(0.56f)
                                            .fillMaxHeight()
                                            .padding(16.dp)
                                    ) {
                                        VaultDetailInspectorPane(
                                            i18n = i18n,
                                            selectedItem = selectedItem,
                                            copiedNotice = copiedNotice,
                                            onCopyField = copyWith30sHygiene,
                                            onToggleFavorite = onToggleFavoriteItem,
                                            onDelete = onDeleteItem
                                        )
                                    }
                                }
                            }
                        }

                        // Smartphone Compact: Top Header + All 7 Category Chips + List / Detail
                        else -> {
                            Scaffold(
                                topBar = {
                                    Surface(tonalElevation = 2.dp) {
                                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = i18n.t("app_name"),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    FilledTonalButton(onClick = onNewItem) {
                                                        Text("+")
                                                    }
                                                    OutlinedButton(onClick = onOpenSettings) {
                                                        Text("⚙")
                                                    }
                                                }
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 6.dp)
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                FilterChip(
                                                    selected = selectedCategory == null && !favoritesOnly,
                                                    onClick = {
                                                        onSelectCategory(null)
                                                        onSelectItem(null)
                                                    },
                                                    label = { Text(i18n.t("nav_all_items")) }
                                                )
                                                FilterChip(
                                                    selected = favoritesOnly,
                                                    onClick = {
                                                        onSelectFavorites()
                                                        onSelectItem(null)
                                                    },
                                                    label = { Text("★ ${i18n.t("nav_favorites")}") }
                                                )
                                                VaultCategory.entries.forEach { cat ->
                                                    FilterChip(
                                                        selected = selectedCategory == cat,
                                                        onClick = {
                                                            onSelectCategory(cat)
                                                            onSelectItem(null)
                                                        },
                                                        label = { Text("${categoryIcon(cat)} ${categoryLabel(i18n, cat)}") }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                Column(
                                    modifier = Modifier
                                        .padding(innerPadding)
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (selectedItem == null) {
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            placeholder = { Text(i18n.t("search_placeholder"), maxLines = 1) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        VaultItemListPane(
                                            i18n = i18n,
                                            items = filteredItems,
                                            selectedItem = null,
                                            onSelectItem = { onSelectItem(it) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        TextButton(onClick = { onSelectItem(null) }) {
                                            Text("← ${i18n.t("nav_all_items")}")
                                        }
                                        VaultDetailInspectorPane(
                                            i18n = i18n,
                                            selectedItem = selectedItem,
                                            copiedNotice = copiedNotice,
                                            onCopyField = copyWith30sHygiene,
                                            onToggleFavorite = onToggleFavoriteItem,
                                            onDelete = {
                                                onDeleteItem(it)
                                                onSelectItem(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showSettingsDialog) {
                    SafePocketSettingsDialog(
                        i18n = i18n,
                        themePreference = themePreference,
                        useDeviceDynamicColor = useDeviceDynamicColor,
                        allowScreenshots = allowScreenshots,
                        manualLocaleOverride = manualLocaleOverride,
                        onSelectThemePreference = onSelectThemePreference,
                        onToggleDeviceDynamicColor = onToggleDeviceDynamicColor,
                        onToggleAllowScreenshots = onToggleAllowScreenshots,
                        onSelectLocaleOverride = onSelectLocaleOverride,
                        onDismiss = onDismissSettings
                    )
                }

                if (showNewItemDialog) {
                    NewVaultItemDialog(
                        i18n = i18n,
                        initialCategory = selectedCategory ?: VaultCategory.PAYMENT_CARD,
                        onCreate = onCreateItem,
                        onDismiss = onDismissNewItem
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryDrawerDestinations(
    i18n: LocalizationEngine,
    selectedCategory: VaultCategory?,
    favoritesOnly: Boolean,
    onSelectCategory: (VaultCategory?) -> Unit,
    onSelectFavorites: () -> Unit
) {
    NavigationDrawerItem(
        icon = { Text("📥") },
        label = { Text(i18n.t("nav_all_items"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selectedCategory == null && !favoritesOnly,
        onClick = { onSelectCategory(null) }
    )
    NavigationDrawerItem(
        icon = { Text("★") },
        label = { Text(i18n.t("nav_favorites"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = favoritesOnly,
        onClick = onSelectFavorites
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    VaultCategory.entries.forEach { category ->
        NavigationDrawerItem(
            icon = { Text(categoryIcon(category)) },
            label = { Text(categoryLabel(i18n, category), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            selected = selectedCategory == category && !favoritesOnly,
            onClick = { onSelectCategory(category) }
        )
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

private fun categorySingularLabel(i18n: LocalizationEngine, category: VaultCategory): String =
    when (category) {
        VaultCategory.PAYMENT_CARD -> i18n.t("category_payment_card")
        VaultCategory.BANK_ACCOUNT -> i18n.t("category_bank_account")
        VaultCategory.INSURANCE_CARD -> i18n.t("category_insurance_card")
        VaultCategory.MEMBERSHIP_CARD -> i18n.t("category_membership_card")
        VaultCategory.IDENTITY -> i18n.t("category_identity")
        VaultCategory.OFFICIAL_DOCUMENT -> i18n.t("category_official_document")
        VaultCategory.SECURE_NOTE -> i18n.t("category_secure_note")
    }

private fun categoryIcon(category: VaultCategory): String =
    when (category) {
        VaultCategory.PAYMENT_CARD -> "💳"
        VaultCategory.BANK_ACCOUNT -> "🏦"
        VaultCategory.INSURANCE_CARD -> "🏥"
        VaultCategory.MEMBERSHIP_CARD -> "🎫"
        VaultCategory.IDENTITY -> "🪪"
        VaultCategory.OFFICIAL_DOCUMENT -> "🛂"
        VaultCategory.SECURE_NOTE -> "🔒"
    }

private fun paletteGradient(paletteId: String?): List<Color> =
    when (paletteId) {
        "alpine_emerald" -> listOf(Color(0xFF0F4C3A), Color(0xFF1B7A5E))
        "aegean_cobalt" -> listOf(Color(0xFF103766), Color(0xFF1E5AA8))
        "terracotta_gold" -> listOf(Color(0xFF6E351A), Color(0xFFA8572A))
        "crimson_ruby" -> listOf(Color(0xFF641826), Color(0xFF9E2A3F))
        "arctic_cyan" -> listOf(Color(0xFF0E4D5C), Color(0xFF1B7B8E))
        else -> listOf(Color(0xFF1E293B), Color(0xFF334155))
    }

@Composable
private fun SearchAndCreateHeader(
    i18n: LocalizationEngine,
    query: String,
    onQueryChange: (String) -> Unit,
    onNewItem: () -> Unit,
    searchFocusRequester: FocusRequester
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = i18n.t("search_placeholder"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester)
        )
        Button(onClick = onNewItem) {
            Text("+ ${i18n.t("shortcut_hint_new")}")
        }
    }
}

@Composable
private fun VaultItemListPane(
    i18n: LocalizationEngine,
    items: List<VaultItemPayload>,
    selectedItem: VaultItemPayload?,
    onSelectItem: (VaultItemPayload) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(i18n.t("search_empty_title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    i18n.t("search_empty_subtitle"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val interactionSource = remember { MutableInteractionSource() }
            val isSelected = selectedItem?.id == item.id
            ElevatedCard(
                onClick = { onSelectItem(item) },
                colors = if (isSelected) {
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                } else {
                    CardDefaults.elevatedCardColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .hoverable(interactionSource)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${categoryIcon(item.category)} ${item.title}",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = item.subtitle.ifBlank { categorySingularLabel(i18n, item.category) }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.favorite) {
                        Text("★", color = MaterialTheme.colorScheme.primary)
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
    copiedNotice: String?,
    onCopyField: (label: String, value: String, isSensitive: Boolean) -> Unit,
    onToggleFavorite: (VaultItemPayload) -> Unit,
    onDelete: (VaultItemPayload) -> Unit
) {
    if (selectedItem == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(i18n.t("app_tagline"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var revealedFieldIds by remember(selectedItem.id) { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (copiedNotice != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = copiedNotice,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        // Visual Card Hero for Cards, Identities & Official Documents
        if (selectedItem.category != VaultCategory.SECURE_NOTE && selectedItem.cardTheme?.showVisualCard != false) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(paletteGradient(selectedItem.cardTheme?.paletteId)))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = categorySingularLabel(i18n, selectedItem.category),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                        selectedItem.cardTheme?.networkBrand?.let { brand ->
                            Text(
                                text = brand,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Text(
                        text = selectedItem.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (selectedItem.subtitle.isNotBlank()) {
                        Text(
                            text = selectedItem.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        } else {
            Text(selectedItem.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onToggleFavorite(selectedItem) }) {
                Text(if (selectedItem.favorite) "★ ${i18n.t("nav_favorites")}" else "☆ ${i18n.t("action_favorite")}")
            }
            OutlinedButton(onClick = { onDelete(selectedItem) }) {
                Text(i18n.t("action_delete"))
            }
        }

        selectedItem.fields.forEach { field ->
            val resolvedLabel = i18n.resolveFieldLabel(
                field.labelKey,
                field.isCustomLabel,
                selectedItem.category
            )
            val isRevealed = field.id in revealedFieldIds
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = resolvedLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (field.isSensitive && !isRevealed) "••••••••••••" else field.value,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = if (field.isSensitive) FontFamily.Monospace else FontFamily.Default
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (field.isSensitive) {
                            TextButton(
                                onClick = {
                                    revealedFieldIds = if (isRevealed) {
                                        revealedFieldIds - field.id
                                    } else {
                                        revealedFieldIds + field.id
                                    }
                                }
                            ) {
                                Text(if (isRevealed) i18n.t("action_hide") else i18n.t("action_reveal"))
                            }
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafePocketSettingsDialog(
    i18n: LocalizationEngine,
    themePreference: ThemePreference,
    useDeviceDynamicColor: Boolean,
    allowScreenshots: Boolean,
    manualLocaleOverride: SupportedLocale?,
    onSelectThemePreference: (ThemePreference) -> Unit,
    onToggleDeviceDynamicColor: (Boolean) -> Unit,
    onToggleAllowScreenshots: (Boolean) -> Unit,
    onSelectLocaleOverride: (SupportedLocale?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n.t("nav_settings")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Screenshot & Screen Recording Permission Toggle (Enabled by Default)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = i18n.t("security_section_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = i18n.t("security_allow_screenshots_toggle"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = i18n.t("security_allow_screenshots_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = allowScreenshots,
                                onCheckedChange = onToggleAllowScreenshots
                            )
                        }
                    }
                }

                // 2. Appearance & Material Theme
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = i18n.t("settings_theme_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = themePreference == ThemePreference.LIGHT,
                                onClick = { onSelectThemePreference(ThemePreference.LIGHT) },
                                label = { Text(i18n.t("theme_light")) }
                            )
                            FilterChip(
                                selected = themePreference == ThemePreference.DARK,
                                onClick = { onSelectThemePreference(ThemePreference.DARK) },
                                label = { Text(i18n.t("theme_dark")) }
                            )
                            FilterChip(
                                selected = themePreference == ThemePreference.SYSTEM,
                                onClick = { onSelectThemePreference(ThemePreference.SYSTEM) },
                                label = { Text(i18n.t("theme_system")) }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = i18n.t("material_color_device"),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = useDeviceDynamicColor,
                                onCheckedChange = onToggleDeviceDynamicColor
                            )
                        }
                    }
                }

                // 3. Interface Language (All 6 Bundled Locales + System Default)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = i18n.t("settings_language_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = manualLocaleOverride == null,
                                onClick = { onSelectLocaleOverride(null) },
                                label = {
                                    Text(
                                        i18n.t(
                                            "settings_language_system",
                                            mapOf("locale" to i18n.detectedSystemLocale.nativeDisplayName)
                                        )
                                    )
                                }
                            )
                            SupportedLocale.entries.forEach { locale ->
                                FilterChip(
                                    selected = manualLocaleOverride == locale,
                                    onClick = { onSelectLocaleOverride(locale) },
                                    label = { Text(locale.nativeDisplayName) }
                                )
                            }
                        }
                    }
                }

                // 4. Version & Encryption Summary
                Text(
                    text = "${
                        i18n.t(
                            "update_version_display",
                            mapOf(
                                "version" to AppBuildInfo.CURRENT_VERSION_NAME,
                                "build" to AppBuildInfo.CURRENT_VERSION_CODE
                            )
                        )
                    } • ${i18n.t("security_kdf_badge")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewVaultItemDialog(
    i18n: LocalizationEngine,
    initialCategory: VaultCategory,
    onCreate: (VaultItemPayload) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(initialCategory) }
    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var primaryValue by remember { mutableStateOf("") }

    val primaryFieldKey = when (category) {
        VaultCategory.PAYMENT_CARD -> CanonicalFieldKeys.CARD_NUMBER
        VaultCategory.BANK_ACCOUNT -> CanonicalFieldKeys.IBAN
        VaultCategory.INSURANCE_CARD -> CanonicalFieldKeys.INSURANCE_NUMBER
        VaultCategory.MEMBERSHIP_CARD -> CanonicalFieldKeys.MEMBERSHIP_ID
        VaultCategory.IDENTITY -> CanonicalFieldKeys.FULL_NAME
        VaultCategory.OFFICIAL_DOCUMENT -> CanonicalFieldKeys.DOCUMENT_NUMBER
        VaultCategory.SECURE_NOTE -> CanonicalFieldKeys.MARKDOWN_NOTE
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(i18n.t("action_new_item")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VaultCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text("${categoryIcon(cat)} ${categorySingularLabel(i18n, cat)}") }
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(categorySingularLabel(i18n, category)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = { Text("Subtitle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = primaryValue,
                    onValueChange = { primaryValue = it },
                    label = { Text(i18n.resolveFieldLabel(primaryFieldKey, false, category)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(i18n.t("action_cancel"))
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onCreate(
                        VaultItemPayload(
                            id = "item-$now",
                            category = category,
                            title = title.trim(),
                            subtitle = subtitle.trim(),
                            cardTheme = CardVisualTheme(paletteId = "aegean_cobalt"),
                            fields = if (primaryValue.isNotBlank()) {
                                listOf(
                                    VaultField(
                                        id = "f-$now",
                                        labelKey = primaryFieldKey,
                                        value = primaryValue.trim()
                                    )
                                )
                            } else emptyList(),
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                }
            ) {
                Text(i18n.t("action_save"))
            }
        }
    )
}

@Composable
private fun LockedVaultView(
    i18n: LocalizationEngine,
    onUnlock: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(modifier = Modifier.widthIn(max = 420.dp).padding(24.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("🔒", style = MaterialTheme.typography.displaySmall)
                Text(i18n.t("app_name"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    i18n.t("app_tagline"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                    Text(i18n.t("action_unlock_vault"))
                }
            }
        }
    }
}
