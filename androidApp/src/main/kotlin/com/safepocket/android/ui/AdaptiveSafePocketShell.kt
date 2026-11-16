package com.safepocket.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.safepocket.android.data.BidirectionalSyncReport
import com.safepocket.android.data.CloudSyncStatusSnapshot
import com.safepocket.android.data.LocalEncryptedDbStatus
import com.safepocket.shared.backup.VaultImportReport
import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.i18n.SupportedLocale
import com.safepocket.shared.model.*
import com.safepocket.shared.search.MultilingualSearchEngine
import com.safepocket.shared.update.AppBuildInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    autoDownloadLogos: Boolean = true,
    isVaultLocked: Boolean = false,
    hasCustomMasterPassword: Boolean = false,
    localDbStatus: LocalEncryptedDbStatus? = null,
    cloudSyncStatus: CloudSyncStatusSnapshot? = null,
    showSettingsDialog: Boolean = false,
    showEditorDialog: Boolean = false,
    editingItem: VaultItemPayload? = null,
    focusSearchTrigger: Int = 0,
    statusBannerMessage: String? = null,
    onDismissStatusBanner: () -> Unit = {},
    onSelectThemePreference: (ThemePreference) -> Unit = {},
    onToggleDeviceDynamicColor: (Boolean) -> Unit = {},
    onToggleAllowScreenshots: (Boolean) -> Unit = {},
    onToggleAutoDownloadLogos: (Boolean) -> Unit = {},
    onSelectLocaleOverride: (SupportedLocale?) -> Unit = {},
    isFoldableSeparating: Boolean = false,
    onSelectCategory: (VaultCategory?) -> Unit,
    onSelectFavorites: () -> Unit = {},
    onSelectItem: (VaultItemPayload?) -> Unit,
    onToggleFavoriteItem: (VaultItemPayload) -> Unit = {},
    onTogglePinItem: (VaultItemPayload) -> Unit = {},
    onDuplicateItem: (VaultItemPayload) -> Unit = {},
    onEditItem: (VaultItemPayload) -> Unit = {},
    onDeleteItem: (VaultItemPayload) -> Unit = {},
    onSaveEditorItem: (VaultItemPayload) -> Unit = {},
    onFetchBrandLogoPreview: suspend (VaultItemPayload) -> VaultItemPayload = { it },
    onFocusSearch: () -> Unit,
    onNewItem: () -> Unit,
    onDismissEditor: () -> Unit = {},
    onLockVault: () -> Unit,
    onUnlockVaultWithPassword: suspend (String) -> Boolean = { true },
    onOpenSettings: () -> Unit,
    onDismissSettings: () -> Unit = {},
    // Local DB & Cloud Sync callbacks
    onConfigureVaultCredentials: suspend (masterPw: String, secretKeyOrUri: String?) -> Unit = { _, _ -> },
    onCreateFirebaseAccount: suspend (email: String, password: String) -> Unit = { _, _ -> },
    onSignInFirebaseAccount: suspend (email: String, password: String) -> Unit = { _, _ -> },
    onSignOutFirebaseAccount: () -> Unit = {},
    onSetCloudSyncEnabled: (Boolean) -> Unit = {},
    onSyncNow: suspend () -> BidirectionalSyncReport? = { null },
    // Backup, Restore, 1Password & CSV callbacks
    onExportEncryptedBackup: suspend (backupPassword: String) -> ByteArray = { ByteArray(0) },
    onRestoreEncryptedBackup: suspend (rawJson: String, password: String, secretKey: String, replace: Boolean) -> Unit = { _, _, _, _ -> },
    onImport1PasswordOrCsv: suspend (fileName: String, rawBytes: ByteArray, includeLogins: Boolean) -> VaultImportReport? = { _, _, _ -> null },
    onVerifyMasterPassword: suspend (String) -> Boolean = { true },
    onExport1Password1Pux: suspend () -> ByteArray = { ByteArray(0) },
    onExport1PasswordCsv: suspend () -> ByteArray = { ByteArray(0) },
    onExportSafePocketCsv: suspend () -> ByteArray = { ByteArray(0) },
    onExportEmergencyKitHtml: () -> ByteArray = { ByteArray(0) },
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

    val cycleTheme: () -> Unit = {
        val next = when (themePreference) {
            ThemePreference.LIGHT -> ThemePreference.DARK
            ThemePreference.DARK -> ThemePreference.SYSTEM
            ThemePreference.SYSTEM -> ThemePreference.LIGHT
        }
        onSelectThemePreference(next)
    }

    val themeGlyph = when (themePreference) {
        ThemePreference.LIGHT -> "☀"
        ThemePreference.DARK -> "☾"
        ThemePreference.SYSTEM -> "◐"
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
        ProvideSeamFreeHoverIndication {
            key(activeLocale.code) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                                when (event.key) {
                                    Key.F -> { onFocusSearch(); true }
                                    Key.N -> { onNewItem(); true }
                                    Key.E -> { selectedItem?.let { onEditItem(it) }; true }
                                    Key.L -> { onLockVault(); true }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    if (isVaultLocked) {
                        LockedVaultView(
                            i18n = i18n,
                            requiresPassword = hasCustomMasterPassword,
                            onUnlockWithPassword = onUnlockVaultWithPassword
                        )
                    } else {
                        when {
                            // 1. Googlebook OS / ChromeOS & Large Desktop Windows (>= 840dp): 3-Column Layout
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
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                // Clean Top-Left Brand Header (Theme icon moved to bottom-left corner!)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(MaterialTheme.colorScheme.primary),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "🛡",
                                                            style = MaterialTheme.typography.labelMedium
                                                        )
                                                    }
                                                    Text(
                                                        text = i18n.t("app_name"),
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
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

                                            // Bottom-Left Corner Footer: Theme Toggle on Far Bottom-Left + Settings + Lock
                                            Column(
                                                modifier = Modifier.padding(top = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                HorizontalDivider()
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Bottom-Left Corner Theme Switcher Icon
                                                    SmoothOutlinedButton(
                                                        onClick = cycleTheme,
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                    ) {
                                                        Text(themeGlyph)
                                                    }
                                                    SmoothTonalButton(
                                                        onClick = onOpenSettings,
                                                        modifier = Modifier.weight(1f),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                                    ) {
                                                        Text(
                                                            text = "⚙ ${i18n.t("nav_settings")}",
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    SmoothOutlinedButton(
                                                        onClick = onLockVault,
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                    ) {
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

                                    // Column 2: Search + Dual-Branded Vault Item List
                                    Column(
                                        modifier = Modifier
                                            .width(344.dp)
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

                                    // Column 3: Detail Inspector
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
                                            copiedNotice = copiedNotice ?: statusBannerMessage,
                                            onCopyField = copyWith30sHygiene,
                                            onEdit = onEditItem,
                                            onDuplicate = onDuplicateItem,
                                            onTogglePin = onTogglePinItem,
                                            onToggleFavorite = onToggleFavoriteItem,
                                            onDelete = onDeleteItem
                                        )
                                    }
                                }
                            }

                            // 2. Unfolded Foldable Posture (600..839dp): NavigationRail (Theme at Bottom-Left) + 2-Pane
                            widthDp >= 600 -> {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxHeight()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .width(84.dp)
                                                .padding(vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .verticalScroll(rememberScrollState()),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                SmoothOutlinedButton(
                                                    onClick = onOpenSettings,
                                                    contentPadding = PaddingValues(8.dp)
                                                ) {
                                                    Text("⚙")
                                                }
                                                Spacer(Modifier.height(6.dp))
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
                                            // Bottom-Left Theme Toggle Icon
                                            SmoothOutlinedButton(
                                                onClick = cycleTheme,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(themeGlyph)
                                            }
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
                                                copiedNotice = copiedNotice ?: statusBannerMessage,
                                                onCopyField = copyWith30sHygiene,
                                                onEdit = onEditItem,
                                                onDuplicate = onDuplicateItem,
                                                onTogglePin = onTogglePinItem,
                                                onToggleFavorite = onToggleFavoriteItem,
                                                onDelete = onDeleteItem
                                            )
                                        }
                                    }
                                }
                            }

                            // 3. Compact Smartphone (< 600dp): Top Header + Bottom Bar with Bottom-Left Theme Icon
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
                                                        SmoothTonalButton(onClick = onNewItem) {
                                                            Text("+")
                                                        }
                                                        SmoothOutlinedButton(onClick = onOpenSettings) {
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
                                    },
                                    bottomBar = {
                                        Surface(tonalElevation = 2.dp) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Bottom-Left Corner Theme Switcher Icon
                                                SmoothOutlinedButton(
                                                    onClick = cycleTheme,
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Text(themeGlyph)
                                                }
                                                Text(
                                                    text = "v${AppBuildInfo.CURRENT_VERSION_NAME}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                SmoothOutlinedButton(
                                                    onClick = onLockVault,
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Text("🔒")
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
                                            SmoothTextButton(onClick = { onSelectItem(null) }) {
                                                Text("← ${i18n.t("nav_all_items")}")
                                            }
                                            VaultDetailInspectorPane(
                                                i18n = i18n,
                                                selectedItem = selectedItem,
                                                copiedNotice = copiedNotice ?: statusBannerMessage,
                                                onCopyField = copyWith30sHygiene,
                                                onEdit = onEditItem,
                                                onDuplicate = onDuplicateItem,
                                                onTogglePin = onTogglePinItem,
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
                            autoDownloadLogos = autoDownloadLogos,
                            manualLocaleOverride = manualLocaleOverride,
                            localDbStatus = localDbStatus,
                            cloudSyncStatus = cloudSyncStatus,
                            onCopyToClipboard = copyWith30sHygiene,
                            onSelectThemePreference = onSelectThemePreference,
                            onToggleDeviceDynamicColor = onToggleDeviceDynamicColor,
                            onToggleAllowScreenshots = onToggleAllowScreenshots,
                            onToggleAutoDownloadLogos = onToggleAutoDownloadLogos,
                            onSelectLocaleOverride = onSelectLocaleOverride,
                            onConfigureVaultCredentials = onConfigureVaultCredentials,
                            onCreateFirebaseAccount = onCreateFirebaseAccount,
                            onSignInFirebaseAccount = onSignInFirebaseAccount,
                            onSignOutFirebaseAccount = onSignOutFirebaseAccount,
                            onSetCloudSyncEnabled = onSetCloudSyncEnabled,
                            onSyncNow = onSyncNow,
                            onExportEncryptedBackup = onExportEncryptedBackup,
                            onRestoreEncryptedBackup = onRestoreEncryptedBackup,
                            onImport1PasswordOrCsv = onImport1PasswordOrCsv,
                            onVerifyMasterPassword = onVerifyMasterPassword,
                            onExport1Password1Pux = onExport1Password1Pux,
                            onExport1PasswordCsv = onExport1PasswordCsv,
                            onExportSafePocketCsv = onExportSafePocketCsv,
                            onExportEmergencyKitHtml = onExportEmergencyKitHtml,
                            onDismiss = onDismissSettings
                        )
                    }

                    if (showEditorDialog) {
                        VaultItemEditorDialog(
                            i18n = i18n,
                            existingItem = editingItem,
                            initialCategory = selectedCategory ?: VaultCategory.PAYMENT_CARD,
                            onFetchLogoPreview = onFetchBrandLogoPreview,
                            onSave = onSaveEditorItem,
                            onDismiss = onDismissEditor
                        )
                    }
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
        SmoothPrimaryButton(onClick = onNewItem) {
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
            val isSelected = selectedItem?.id == item.id
            val resolvedBrand = remember(item) { InstitutionBrandRegistry.resolveForItem(item) }
            ElevatedCard(
                onClick = { onSelectItem(item) },
                colors = if (isSelected) {
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                } else {
                    CardDefaults.elevatedCardColors()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InstitutionMiniAvatar(item = item, size = 38.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
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
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.pinned) Text("📌", style = MaterialTheme.typography.labelSmall)
                            if (item.favorite) Text("★", color = MaterialTheme.colorScheme.primary)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = item.cardTheme?.networkBrand?.takeIf { it.isNotBlank() }
                                    ?: resolvedBrand.networkDisplayLabel,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultDetailInspectorPane(
    i18n: LocalizationEngine,
    selectedItem: VaultItemPayload?,
    copiedNotice: String?,
    onCopyField: (label: String, value: String, isSensitive: Boolean) -> Unit,
    onEdit: (VaultItemPayload) -> Unit,
    onDuplicate: (VaultItemPayload) -> Unit,
    onTogglePin: (VaultItemPayload) -> Unit,
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
        if (!copiedNotice.isNullOrBlank()) {
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

        // Dual-Logo & Institution Brand Color Visual Card Hero
        if (selectedItem.category != VaultCategory.SECURE_NOTE && selectedItem.cardTheme?.showVisualCard != false) {
            DualBrandVisualCardHero(
                item = selectedItem,
                categoryLabelText = categorySingularLabel(i18n, selectedItem.category)
            )
        } else {
            Text(selectedItem.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (selectedItem.subtitle.isNotBlank()) {
                Text(selectedItem.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Complete Item Action Bar: Edit, Duplicate, Pin, Favorite, Delete
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmoothPrimaryButton(onClick = { onEdit(selectedItem) }) {
                Text("✎ ${i18n.t("action_edit")}")
            }
            SmoothTonalButton(onClick = { onDuplicate(selectedItem) }) {
                Text("⎘ ${i18n.t("action_duplicate")}")
            }
            SmoothOutlinedButton(onClick = { onTogglePin(selectedItem) }) {
                Text(if (selectedItem.pinned) "📌 ${i18n.t("action_unpin")}" else "📌 ${i18n.t("action_pin")}")
            }
            SmoothOutlinedButton(onClick = { onToggleFavorite(selectedItem) }) {
                Text(if (selectedItem.favorite) "★ ${i18n.t("nav_favorites")}" else "☆ ${i18n.t("action_favorite")}")
            }
            SmoothOutlinedButton(onClick = { onDelete(selectedItem) }) {
                Text(i18n.t("action_delete"))
            }
        }

        // Tags Row
        if (selectedItem.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                selectedItem.tags.forEach { tag ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Structured & Custom Fields
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
                            fontFamily = if (field.isSensitive || field.valueType in setOf(FieldValueType.CARD_NUMBER, FieldValueType.IBAN)) {
                                FontFamily.Monospace
                            } else {
                                FontFamily.Default
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (field.isSensitive) {
                            SmoothTextButton(
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
                        SmoothTonalButton(
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

// =============================================================================
// FULL ITEM CREATE & EDIT DIALOG WITH ALL 7 CATEGORY FIELD TEMPLATES + BRANDING
// =============================================================================

private data class CustomFieldDraft(
    val id: String,
    val label: String,
    val value: String,
    val isSensitive: Boolean
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultItemEditorDialog(
    i18n: LocalizationEngine,
    existingItem: VaultItemPayload?,
    initialCategory: VaultCategory,
    onFetchLogoPreview: suspend (VaultItemPayload) -> VaultItemPayload,
    onSave: (VaultItemPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var category by remember { mutableStateOf(existingItem?.category ?: initialCategory) }
    var title by remember { mutableStateOf(existingItem?.title.orEmpty()) }
    var subtitle by remember { mutableStateOf(existingItem?.subtitle.orEmpty()) }
    var tagsText by remember { mutableStateOf(existingItem?.tags?.joinToString(", ").orEmpty()) }
    var autoBrandColor by remember { mutableStateOf(existingItem?.cardTheme?.autoBrandColor ?: true) }
    var selectedPaletteId by remember { mutableStateOf(existingItem?.cardTheme?.paletteId ?: "aegean_cobalt") }
    var customColorHex by remember { mutableStateOf(existingItem?.cardTheme?.customColorHex.orEmpty()) }
    var logoDataUri by remember { mutableStateOf(existingItem?.cardTheme?.institutionLogoDataUri) }
    var fetchingLogo by remember { mutableStateOf(false) }

    // Map of canonical field key -> current string value
    val standardFieldValues = remember {
        mutableStateMapOf<String, String>().apply {
            existingItem?.fields?.filter { !it.isCustomLabel }?.forEach { f ->
                put(f.labelKey, f.value)
            }
        }
    }

    val customFields = remember {
        mutableStateListOf<CustomFieldDraft>().apply {
            existingItem?.fields?.filter { it.isCustomLabel }?.forEach { f ->
                add(CustomFieldDraft(f.id, f.labelKey, f.value, f.isSensitive))
            }
        }
    }

    val templateSpecs = remember(category) { CategoryFieldTemplates.forCategory(category) }

    val buildPreviewPayload: () -> VaultItemPayload = {
        val now = System.currentTimeMillis()
        val stdFields = templateSpecs.mapIndexedNotNull { idx, spec ->
            val v = standardFieldValues[spec.key]?.trim().orEmpty()
            if (v.isEmpty()) null
            else VaultField(
                id = "f-std-$idx-${spec.key}",
                labelKey = spec.key,
                isCustomLabel = false,
                value = v,
                valueType = spec.valueType
            )
        }
        val extraStdFields = (existingItem?.fields.orEmpty())
            .filter { !it.isCustomLabel && templateSpecs.none { s -> s.key == it.labelKey } && it.value.isNotBlank() }
        val custFields = customFields.mapIndexedNotNull { idx, cf ->
            if (cf.label.isBlank() || cf.value.isBlank()) null
            else VaultField(
                id = cf.id.ifBlank { "f-cust-$now-$idx" },
                labelKey = cf.label.trim(),
                isCustomLabel = true,
                value = cf.value.trim(),
                valueType = if (cf.isSensitive) FieldValueType.SENSITIVE_SECRET else FieldValueType.TEXT,
                isSensitive = cf.isSensitive
            )
        }
        val rawItem = VaultItemPayload(
            id = existingItem?.id ?: "item-$now",
            category = category,
            title = title.trim().ifBlank { categorySingularLabel(i18n, category) },
            subtitle = subtitle.trim(),
            favorite = existingItem?.favorite ?: false,
            pinned = existingItem?.pinned ?: false,
            tags = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            cardTheme = CardVisualTheme(
                paletteId = if (!autoBrandColor && customColorHex.isNotBlank()) "custom" else selectedPaletteId,
                customColorHex = customColorHex.trim().takeIf { it.isNotBlank() },
                showVisualCard = category != VaultCategory.SECURE_NOTE,
                institutionLogoDataUri = logoDataUri,
                autoBrandColor = autoBrandColor
            ),
            fields = stdFields + extraStdFields + custFields,
            attachments = existingItem?.attachments.orEmpty(),
            createdAtEpochMs = existingItem?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now
        )
        InstitutionBrandRegistry.applyAutoBranding(rawItem)
    }

    val livePreviewItem = buildPreviewPayload()
    val liveBrand = InstitutionBrandRegistry.resolveForItem(livePreviewItem)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 760.dp)
            .padding(vertical = 16.dp),
        title = {
            Text(
                text = if (existingItem == null) i18n.t("action_new_item") else "${i18n.t("action_edit")} — ${existingItem.title}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Live Dual-Logo Card Preview
                if (category != VaultCategory.SECURE_NOTE) {
                    DualBrandVisualCardHero(
                        item = livePreviewItem,
                        categoryLabelText = categorySingularLabel(i18n, category)
                    )
                    if (liveBrand.institution != null) {
                        Text(
                            text = "✓ ${i18n.t("brand_detected_badge", mapOf("institution" to liveBrand.institution.displayName, "network" to liveBrand.networkDisplayLabel))}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Category Selector
                Text(
                    text = i18n.t("field_category"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
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

                // Title & Subtitle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(i18n.t("field_title")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = subtitle,
                        onValueChange = { subtitle = it },
                        label = { Text(i18n.t("field_subtitle")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Auto-Branding & Card Color Controls
                if (category != VaultCategory.SECURE_NOTE) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = i18n.t("brand_auto_detect_toggle"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = autoBrandColor,
                                    onCheckedChange = { enabled ->
                                        autoBrandColor = enabled
                                        if (enabled) customColorHex = ""
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customColorHex,
                                    onValueChange = {
                                        customColorHex = it
                                        if (it.startsWith("#") && it.length == 7) {
                                            autoBrandColor = false
                                            selectedPaletteId = "custom"
                                        }
                                    },
                                    label = { Text("${i18n.t("palette_custom")} (#RRGGBB)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                SmoothTonalButton(
                                    enabled = !fetchingLogo,
                                    onClick = {
                                        coroutineScope.launch {
                                            fetchingLogo = true
                                            val enriched = onFetchLogoPreview(buildPreviewPayload())
                                            enriched.cardTheme?.institutionLogoDataUri?.let { logoDataUri = it }
                                            enriched.cardTheme?.customColorHex?.let { hex ->
                                                if (autoBrandColor) customColorHex = hex
                                            }
                                            fetchingLogo = false
                                        }
                                    }
                                ) {
                                    Text(if (fetchingLogo) "…" else "🌐 ${i18n.t("brand_fetch_logo_btn")}")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CardPalettes.ALL.forEach { pal ->
                                    val swatch = resolveCardGradientColors(
                                        VaultItemPayload(
                                            id = "sw",
                                            category = VaultCategory.PAYMENT_CARD,
                                            title = "sw",
                                            cardTheme = CardVisualTheme(paletteId = pal, autoBrandColor = false),
                                            createdAtEpochMs = 0L,
                                            updatedAtEpochMs = 0L
                                        ),
                                        liveBrand.copy(effectiveColorHex = null)
                                    ).first
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(swatch)
                                            .border(
                                                width = if (!autoBrandColor && selectedPaletteId == pal) 2.5.dp else 1.dp,
                                                color = if (!autoBrandColor && selectedPaletteId == pal) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                autoBrandColor = false
                                                customColorHex = ""
                                                selectedPaletteId = pal
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                // All Category-Specific Structured Fields
                HorizontalDivider()
                templateSpecs.forEach { spec ->
                    val label = i18n.resolveFieldLabel(spec.key, false, category)
                    val curVal = standardFieldValues[spec.key].orEmpty()
                    val isMultiline = spec.valueType in setOf(
                        FieldValueType.MULTILINE_ADDRESS,
                        FieldValueType.MARKDOWN
                    )
                    OutlinedTextField(
                        value = curVal,
                        onValueChange = { standardFieldValues[spec.key] = it },
                        label = { Text(label) },
                        placeholder = if (spec.placeholder.isNotBlank()) {
                            { Text(spec.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else null,
                        singleLine = !isMultiline,
                        minLines = if (isMultiline) 2 else 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Tags
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text(i18n.t("field_tags")) },
                    placeholder = { Text("banking, travel, visa") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Custom User Fields
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = i18n.t("custom_field_name"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SmoothTonalButton(
                        onClick = {
                            customFields.add(
                                CustomFieldDraft(
                                    id = "f-cust-${System.currentTimeMillis()}-${customFields.size}",
                                    label = "",
                                    value = "",
                                    isSensitive = false
                                )
                            )
                        }
                    ) {
                        Text("+ ${i18n.t("action_add_custom_field")}")
                    }
                }

                customFields.forEachIndexed { index, draft ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = draft.label,
                                    onValueChange = { customFields[index] = draft.copy(label = it) },
                                    label = { Text(i18n.t("custom_field_name")) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = draft.value,
                                    onValueChange = { customFields[index] = draft.copy(value = it) },
                                    label = { Text("Value") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(
                                        checked = draft.isSensitive,
                                        onCheckedChange = { customFields[index] = draft.copy(isSensitive = it) }
                                    )
                                    Text(i18n.t("custom_field_sensitive"), style = MaterialTheme.typography.bodySmall)
                                }
                                SmoothTextButton(onClick = { customFields.removeAt(index) }) {
                                    Text(i18n.t("action_delete"))
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            SmoothTextButton(onClick = onDismiss) {
                Text(i18n.t("action_cancel"))
            }
        },
        confirmButton = {
            SmoothPrimaryButton(
                enabled = title.isNotBlank() || standardFieldValues.values.any { it.isNotBlank() },
                onClick = { onSave(buildPreviewPayload()) }
            ) {
                Text(i18n.t("action_save"))
            }
        }
    )
}

// =============================================================================
// COMPREHENSIVE v0.2 SETTINGS DIALOG:
// LOCAL SQLCIPHER DB + FIREBASE SYNC + .SAFEPOCKET / 1PASSWORD / CSV PORTABILITY
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafePocketSettingsDialog(
    i18n: LocalizationEngine,
    themePreference: ThemePreference,
    useDeviceDynamicColor: Boolean,
    allowScreenshots: Boolean,
    autoDownloadLogos: Boolean,
    manualLocaleOverride: SupportedLocale?,
    localDbStatus: LocalEncryptedDbStatus?,
    cloudSyncStatus: CloudSyncStatusSnapshot?,
    onCopyToClipboard: (label: String, value: String, isSensitive: Boolean) -> Unit,
    onSelectThemePreference: (ThemePreference) -> Unit,
    onToggleDeviceDynamicColor: (Boolean) -> Unit,
    onToggleAllowScreenshots: (Boolean) -> Unit,
    onToggleAutoDownloadLogos: (Boolean) -> Unit,
    onSelectLocaleOverride: (SupportedLocale?) -> Unit,
    onConfigureVaultCredentials: suspend (masterPw: String, secretKeyOrUri: String?) -> Unit,
    onCreateFirebaseAccount: suspend (email: String, password: String) -> Unit,
    onSignInFirebaseAccount: suspend (email: String, password: String) -> Unit,
    onSignOutFirebaseAccount: () -> Unit,
    onSetCloudSyncEnabled: (Boolean) -> Unit,
    onSyncNow: suspend () -> BidirectionalSyncReport?,
    onExportEncryptedBackup: suspend (backupPassword: String) -> ByteArray,
    onRestoreEncryptedBackup: suspend (rawJson: String, password: String, secretKey: String, replace: Boolean) -> Unit,
    onImport1PasswordOrCsv: suspend (fileName: String, rawBytes: ByteArray, includeLogins: Boolean) -> VaultImportReport?,
    onVerifyMasterPassword: suspend (String) -> Boolean,
    onExport1Password1Pux: suspend () -> ByteArray,
    onExport1PasswordCsv: suspend () -> ByteArray,
    onExportSafePocketCsv: suspend () -> ByteArray,
    onExportEmergencyKitHtml: () -> ByteArray,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var statusFeedback by remember { mutableStateOf<String?>(null) }
    var busyOperation by remember { mutableStateOf(false) }

    // Local DB / Master Password + Secret Key inputs
    var newMasterPassword by remember { mutableStateOf("") }
    var pairSecretKeyInput by remember { mutableStateOf("") }

    // Firebase Cloud Sync inputs
    var cloudEmail by remember { mutableStateOf(cloudSyncStatus?.signedInEmail.orEmpty()) }
    var cloudPassword by remember { mutableStateOf("") }

    // Backup & Portability inputs
    var backupPassword by remember { mutableStateOf("") }
    var restoreSecretKey by remember { mutableStateOf("") }
    var restoreReplaceExisting by remember { mutableStateOf(false) }
    var includeLoginsAsNotes by remember { mutableStateOf(false) }
    var exportConfirmMasterPw by remember { mutableStateOf("") }

    // Pending byte payload for SAF CreateDocument
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                statusFeedback = "✓ File exported (${bytes.size} bytes)"
            }.onFailure { err ->
                statusFeedback = "⚠ Export failed: ${err.message}"
            }
        }
    }

    val openRestoreContainerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                busyOperation = true
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("Unable to read selected backup file")
                    onRestoreEncryptedBackup(raw, backupPassword, restoreSecretKey, restoreReplaceExisting)
                    statusFeedback = "✓ Restored encrypted .safepocket backup"
                }.onFailure { err ->
                    statusFeedback = "⚠ Restore failed: ${err.message}"
                }
                busyOperation = false
            }
        }
    }

    val openImport1PasswordOrCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                busyOperation = true
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Unable to read selected file")
                    val pathTail = uri.lastPathSegment ?: "import.csv"
                    val report = onImport1PasswordOrCsv(pathTail, bytes, includeLoginsAsNotes)
                    if (report != null) {
                        statusFeedback = "✓ " + i18n.t(
                            "import_summary_toast",
                            mapOf(
                                "imported" to report.importedItems.size,
                                "skipped" to report.skippedLoginCount
                            )
                        )
                    }
                }.onFailure { err ->
                    statusFeedback = "⚠ Import failed: ${err.message}"
                }
                busyOperation = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 760.dp)
            .padding(vertical = 16.dp),
        title = { Text(i18n.t("nav_settings")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!statusFeedback.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusFeedback!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // -----------------------------------------------------------------
                // 1. LOCAL ENCRYPTED DB (`SQLCipher 4.6`) & ZERO-KNOWLEDGE VAULT KEY
                // -----------------------------------------------------------------
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = i18n.t("db_local_section_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (localDbStatus != null) {
                            Text(
                                text = i18n.t(
                                    "db_local_sqlcipher_status",
                                    mapOf("count" to localDbStatus.activeItemCount)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${localDbStatus.backendLabel} • ${localDbStatus.databaseFileName} (${localDbStatus.databaseSizeBytes / 1024} KiB) • Vault: ${localDbStatus.vaultId}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${i18n.t("security_secret_key")}: ${localDbStatus.secretKeyFormatted}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                SmoothTonalButton(
                                    onClick = {
                                        onCopyToClipboard(
                                            i18n.t("security_secret_key"),
                                            localDbStatus.secretKeyFormatted,
                                            true
                                        )
                                        statusFeedback = "✓ ${i18n.t("action_copied")}: ${i18n.t("security_secret_key")}"
                                    }
                                ) {
                                    Text(i18n.t("action_copy"))
                                }
                            }
                        }

                        HorizontalDivider()
                        OutlinedTextField(
                            value = newMasterPassword,
                            onValueChange = { newMasterPassword = it },
                            label = { Text(i18n.t("security_master_password")) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pairSecretKeyInput,
                            onValueChange = { pairSecretKeyInput = it },
                            label = { Text("${i18n.t("security_secret_key")} (SPK-… / Recovery URI — optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmoothPrimaryButton(
                                enabled = !busyOperation && newMasterPassword.length >= 6,
                                onClick = {
                                    coroutineScope.launch {
                                        busyOperation = true
                                        runCatching {
                                            onConfigureVaultCredentials(
                                                newMasterPassword,
                                                pairSecretKeyInput.takeIf { it.isNotBlank() }
                                            )
                                            newMasterPassword = ""
                                            statusFeedback = "✓ Updated Master Password & re-encrypted SQLCipher DB"
                                        }.onFailure { err ->
                                            statusFeedback = "⚠ ${err.message}"
                                        }
                                        busyOperation = false
                                    }
                                }
                            ) {
                                Text(i18n.t("action_save"))
                            }
                            SmoothOutlinedButton(
                                onClick = {
                                    pendingSaveBytes = onExportEmergencyKitHtml()
                                    saveDocumentLauncher.launch("safepocket-emergency-kit.html")
                                }
                            ) {
                                Text(i18n.t("onboarding_download_emergency_kit"))
                            }
                        }
                    }
                }

                // -----------------------------------------------------------------
                // 2. FIREBASE CLOUD SYNC (ACCOUNT CREATION, SIGN-IN & LIVE SYNC)
                // -----------------------------------------------------------------
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${i18n.t("sync_section_title")} • Firebase (${cloudSyncStatus?.projectId ?: "safepocket-vault-eu"})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = i18n.t("sync_enable_cloud_toggle"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = i18n.t("sync_auto_note"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = cloudSyncStatus?.cloudSyncEnabled ?: false,
                                onCheckedChange = onSetCloudSyncEnabled
                            )
                        }

                        if (!cloudSyncStatus?.signedInEmail.isNullOrBlank()) {
                            Text(
                                text = i18n.t(
                                    "cloud_auth_signed_in_as",
                                    mapOf(
                                        "email" to (cloudSyncStatus?.signedInEmail ?: ""),
                                        "uid" to (cloudSyncStatus?.signedInUid?.take(10) ?: "")
                                    )
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmoothPrimaryButton(
                                    enabled = !busyOperation,
                                    onClick = {
                                        coroutineScope.launch {
                                            busyOperation = true
                                            runCatching {
                                                val rep = onSyncNow()
                                                if (rep != null) {
                                                    statusFeedback = "✓ Synced with Firebase (↑${rep.pushedCount} pushed • ↓${rep.pulledCount} pulled)"
                                                }
                                            }.onFailure { err ->
                                                statusFeedback = "⚠ Sync failed: ${err.message}"
                                            }
                                            busyOperation = false
                                        }
                                    }
                                ) {
                                    Text("⇄ ${i18n.t("sync_now")}")
                                }
                                SmoothOutlinedButton(onClick = {
                                    onSignOutFirebaseAccount()
                                    statusFeedback = "Signed out of Firebase Cloud Sync"
                                }) {
                                    Text(i18n.t("cloud_auth_signout_btn"))
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = cloudEmail,
                                onValueChange = { cloudEmail = it },
                                label = { Text(i18n.t("cloud_auth_email")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = cloudPassword,
                                onValueChange = { cloudPassword = it },
                                label = { Text(i18n.t("cloud_auth_password")) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmoothPrimaryButton(
                                    enabled = !busyOperation && cloudEmail.contains("@") && cloudPassword.length >= 6,
                                    onClick = {
                                        coroutineScope.launch {
                                            busyOperation = true
                                            runCatching {
                                                onCreateFirebaseAccount(cloudEmail.trim(), cloudPassword)
                                                cloudPassword = ""
                                                val rep = onSyncNow()
                                                statusFeedback = "✓ Created Firebase account & synced ↑${rep?.pushedCount ?: 0} items"
                                            }.onFailure { err ->
                                                statusFeedback = "⚠ ${err.message}"
                                            }
                                            busyOperation = false
                                        }
                                    }
                                ) {
                                    Text(i18n.t("cloud_auth_create_btn"))
                                }
                                SmoothTonalButton(
                                    enabled = !busyOperation && cloudEmail.contains("@") && cloudPassword.length >= 6,
                                    onClick = {
                                        coroutineScope.launch {
                                            busyOperation = true
                                            runCatching {
                                                onSignInFirebaseAccount(cloudEmail.trim(), cloudPassword)
                                                cloudPassword = ""
                                                val rep = onSyncNow()
                                                statusFeedback = "✓ Signed in & synced (↑${rep?.pushedCount ?: 0} • ↓${rep?.pulledCount ?: 0})"
                                            }.onFailure { err ->
                                                statusFeedback = "⚠ ${err.message}"
                                            }
                                            busyOperation = false
                                        }
                                    }
                                ) {
                                    Text(i18n.t("cloud_auth_signin_btn"))
                                }
                            }
                        }
                    }
                }

                // -----------------------------------------------------------------
                // 3. ENCRYPTED BACKUP + CSV & 1PASSWORD IMPORT / EXPORT
                // -----------------------------------------------------------------
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = i18n.t("portability_section_title"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // 3A. Encrypted .safepocket Backup & Restore
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text("${i18n.t("backup_export_encrypted")} — Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = restoreSecretKey,
                            onValueChange = { restoreSecretKey = it },
                            label = { Text("${i18n.t("security_secret_key")} (only if restoring from another vault)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmoothPrimaryButton(
                                enabled = !busyOperation && backupPassword.length >= 4,
                                onClick = {
                                    coroutineScope.launch {
                                        busyOperation = true
                                        runCatching {
                                            pendingSaveBytes = onExportEncryptedBackup(backupPassword)
                                            saveDocumentLauncher.launch("safepocket-backup.safepocket")
                                        }.onFailure { err ->
                                            statusFeedback = "⚠ ${err.message}"
                                        }
                                        busyOperation = false
                                    }
                                }
                            ) {
                                Text("🔐 ${i18n.t("backup_export_encrypted")}")
                            }
                            SmoothTonalButton(
                                enabled = !busyOperation && backupPassword.isNotBlank(),
                                onClick = {
                                    restoreReplaceExisting = false
                                    openRestoreContainerLauncher.launch(arrayOf("*/*"))
                                }
                            ) {
                                Text("📥 ${i18n.t("restore_mode_merge")}")
                            }
                            SmoothOutlinedButton(
                                enabled = !busyOperation && backupPassword.isNotBlank(),
                                onClick = {
                                    restoreReplaceExisting = true
                                    openRestoreContainerLauncher.launch(arrayOf("*/*"))
                                }
                            ) {
                                Text("♻ ${i18n.t("restore_mode_replace")}")
                            }
                        }

                        HorizontalDivider()

                        // 3B. Import from 1Password (.1pux / CSV) & Generic CSV
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = i18n.t("import_include_logins_toggle"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = includeLoginsAsNotes,
                                onCheckedChange = { includeLoginsAsNotes = it }
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmoothTonalButton(
                                enabled = !busyOperation,
                                onClick = { openImport1PasswordOrCsvLauncher.launch(arrayOf("*/*")) }
                            ) {
                                Text("🔑 ${i18n.t("import_1password_btn")}")
                            }
                            SmoothOutlinedButton(
                                enabled = !busyOperation,
                                onClick = { openImport1PasswordOrCsvLauncher.launch(arrayOf("text/*", "*/*")) }
                            ) {
                                Text("📄 ${i18n.t("import_csv_btn")}")
                            }
                        }

                        HorizontalDivider()

                        // 3C. Export to 1Password (.1pux / CSV) & SafePocket CSV
                        if (localDbStatus?.hasCustomMasterPassword == true) {
                            OutlinedTextField(
                                value = exportConfirmMasterPw,
                                onValueChange = { exportConfirmMasterPw = it },
                                label = { Text("${i18n.t("export_plain_modal_title")} — ${i18n.t("security_master_password")}") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SmoothTonalButton(
                                enabled = !busyOperation,
                                onClick = {
                                    coroutineScope.launch {
                                        if (!onVerifyMasterPassword(exportConfirmMasterPw)) {
                                            statusFeedback = "⚠ Invalid Master Password"
                                            return@launch
                                        }
                                        pendingSaveBytes = onExport1Password1Pux()
                                        saveDocumentLauncher.launch("safepocket-export.1pux")
                                    }
                                }
                            ) {
                                Text(i18n.t("export_1password_1pux_btn"))
                            }
                            SmoothOutlinedButton(
                                enabled = !busyOperation,
                                onClick = {
                                    coroutineScope.launch {
                                        if (!onVerifyMasterPassword(exportConfirmMasterPw)) {
                                            statusFeedback = "⚠ Invalid Master Password"
                                            return@launch
                                        }
                                        pendingSaveBytes = onExport1PasswordCsv()
                                        saveDocumentLauncher.launch("safepocket-1password.csv")
                                    }
                                }
                            ) {
                                Text(i18n.t("export_1password_csv_btn"))
                            }
                            SmoothOutlinedButton(
                                enabled = !busyOperation,
                                onClick = {
                                    coroutineScope.launch {
                                        if (!onVerifyMasterPassword(exportConfirmMasterPw)) {
                                            statusFeedback = "⚠ Invalid Master Password"
                                            return@launch
                                        }
                                        pendingSaveBytes = onExportSafePocketCsv()
                                        saveDocumentLauncher.launch("safepocket-vault.csv")
                                    }
                                }
                            ) {
                                Text(i18n.t("export_csv_btn"))
                            }
                        }
                    }
                }

                // -----------------------------------------------------------------
                // 4. SECURITY & AUTOMATIC BRAND LOGO DOWNLOAD
                // -----------------------------------------------------------------
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = i18n.t("settings_auto_download_logos_toggle"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = i18n.t("settings_auto_download_logos_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoDownloadLogos,
                                onCheckedChange = onToggleAutoDownloadLogos
                            )
                        }
                    }
                }

                // -----------------------------------------------------------------
                // 5. APPEARANCE & LANGUAGE (ALL 6 LOCALES)
                // -----------------------------------------------------------------
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
                        HorizontalDivider()
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

                // 6. Version & Cryptographic KDF Badge
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
            SmoothPrimaryButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun LockedVaultView(
    i18n: LocalizationEngine,
    requiresPassword: Boolean,
    onUnlockWithPassword: suspend (String) -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf(false) }

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
                if (requiresPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            unlockError = false
                        },
                        label = { Text(i18n.t("security_master_password")) },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = unlockError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(4.dp))
                SmoothPrimaryButton(
                    onClick = {
                        coroutineScope.launch {
                            val ok = onUnlockWithPassword(password)
                            if (!ok) unlockError = true
                            else password = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(i18n.t("action_unlock_vault"))
                }
            }
        }
    }
}
