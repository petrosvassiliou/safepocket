package com.safepocket.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.safepocket.android.R
import com.safepocket.android.data.BidirectionalSyncReport
import com.safepocket.android.data.CloudSyncStatusSnapshot
import com.safepocket.android.data.LocalEncryptedDbStatus
import com.safepocket.android.data.SyncBadgeStatus
import com.safepocket.android.data.SyncIndicatorSnapshot
import com.safepocket.shared.backup.VaultImportReport
import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.error.DiagnosticEvent
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.i18n.SupportedLocale
import com.safepocket.shared.intelligence.SmartFieldIntelligenceEngine
import com.safepocket.shared.intelligence.WebsiteReachabilityResult
import com.safepocket.shared.model.*
import com.safepocket.shared.search.MultilingualSearchEngine
import com.safepocket.shared.update.AppBuildInfo
import com.safepocket.shared.update.PlatformArtifactDescriptor
import com.safepocket.shared.update.RemoteUpdateManifest
import com.safepocket.shared.update.UpdateCheckState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    syncIndicator: SyncIndicatorSnapshot,
    updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    autoUpdateEnabled: Boolean = true,
    latestBannerError: DiagnosticEvent? = null,
    diagnosticEvents: List<DiagnosticEvent> = emptyList(),
    showSettingsDialog: Boolean = false,
    showEditorDialog: Boolean = false,
    editingItem: VaultItemPayload? = null,
    focusSearchTrigger: Int = 0,
    statusBannerMessage: String? = null,
    onDismissStatusBanner: () -> Unit = {},
    onDismissErrorBanner: () -> Unit = {},
    onClearDiagnosticsLog: () -> Unit = {},
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
    onDeleteAllExampleData: suspend () -> Int = { 0 },
    onSaveEditorItem: (VaultItemPayload) -> Unit = {},
    onFetchBrandLogoPreview: suspend (VaultItemPayload) -> VaultItemPayload = { it },
    onVerifyWebsiteReachability: suspend (String) -> WebsiteReachabilityResult = {
        WebsiteReachabilityResult.Idle
    },
    onFocusSearch: () -> Unit,
    onNewItem: () -> Unit,
    onDismissEditor: () -> Unit = {},
    onLockVault: () -> Unit,
    onUnlockVaultWithPassword: suspend (String) -> Boolean = { true },
    onOpenSettings: () -> Unit,
    onDismissSettings: () -> Unit = {},
    // Exclusive Storage Mode & Cloud Sync callbacks
    onConfigureVaultCredentials: suspend (masterPw: String, secretKeyOrUri: String?) -> Unit = { _, _ -> },
    onSwitchStorageMode: suspend (VaultStorageMode) -> Unit = {},
    onCreateFirebaseAccount: suspend (email: String, password: String) -> Unit = { _, _ -> },
    onSignInFirebaseAccount: suspend (email: String, password: String) -> Unit = { _, _ -> },
    onSignOutFirebaseAccount: suspend () -> Unit = {},
    onSyncNow: suspend () -> BidirectionalSyncReport? = { null },
    // Automated Updates callbacks
    onToggleAutoUpdate: (Boolean) -> Unit = {},
    onCheckForUpdatesNow: suspend () -> Unit = {},
    onInstallVerifiedUpdate: suspend (RemoteUpdateManifest, PlatformArtifactDescriptor) -> String? = { _, _ -> null },
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
    var showPurgeConfirmDialog by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    val exampleItemsCount = remember(items) { items.count { it.isBuiltInSample() } }

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

    val copyItemPrimaryField: (VaultItemPayload) -> Unit = { target ->
        val primary = target.primaryCopyableField()
        if (primary != null) {
            val resolvedLabel = i18n.resolveFieldLabel(primary.labelKey, primary.isCustomLabel, target.category)
            copyWith30sHygiene(resolvedLabel, primary.value, primary.isSensitive)
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Top Global Error Boundary Banner (when any error is surfaced)
                            if (latestBannerError != null) {
                                GlobalErrorBanner(
                                    i18n = i18n,
                                    event = latestBannerError,
                                    onRetry = if (latestBannerError.canRetry && syncIndicator.storageMode == VaultStorageMode.CLOUD_SYNC) {
                                        {
                                            onDismissErrorBanner()
                                            coroutineScope.launch { runCatching { onSyncNow() } }
                                        }
                                    } else null,
                                    onOpenDiagnostics = onOpenSettings,
                                    onDismiss = onDismissErrorBanner
                                )
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                                        .width(276.dp)
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
                                                            // Crisp Studio App Icon + SafePocket Title
                                                            BrandHeaderRow(title = i18n.t("app_name"))
                                                            Spacer(Modifier.height(4.dp))
                                                            CategoryDrawerDestinations(
                                                                i18n = i18n,
                                                                selectedCategory = selectedCategory,
                                                                favoritesOnly = favoritesOnly,
                                                                onSelectCategory = onSelectCategory,
                                                                onSelectFavorites = onSelectFavorites
                                                            )
                                                        }

                                                        // Fixed Non-Truncated Sidebar Footer:
                                                        // Row 1: Full-width Settings button (never clips "Einstellungen"!)
                                                        // Row 2: Bottom-Left Theme Toggle + Version + Lock
                                                        Column(
                                                            modifier = Modifier.padding(top = 8.dp),
                                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            HorizontalDivider()
                                                            SmoothTonalButton(
                                                                onClick = onOpenSettings,
                                                                modifier = Modifier.fillMaxWidth(),
                                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                                                            ) {
                                                                Text(
                                                                    text = "⚙  ${i18n.t("nav_settings")}",
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                // Bottom-Left Corner Theme Switcher Icon
                                                                SmoothOutlinedButton(
                                                                    onClick = cycleTheme,
                                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                                ) {
                                                                    Text(themeGlyph)
                                                                }
                                                                Text(
                                                                    text = "v${AppBuildInfo.CURRENT_VERSION_NAME} (b${AppBuildInfo.CURRENT_VERSION_CODE})",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    maxLines = 1
                                                                )
                                                                SmoothOutlinedButton(
                                                                    onClick = onLockVault,
                                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                                ) {
                                                                    Text("🔒")
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                // Column 2: Search + Delete Example Data Banner + Swipe/Right-Click List
                                                Column(
                                                    modifier = Modifier
                                                        .width(356.dp)
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
                                                    if (exampleItemsCount > 0) {
                                                        DeleteExampleDataInlineCard(
                                                            i18n = i18n,
                                                            exampleCount = exampleItemsCount,
                                                            onRequestDelete = { showPurgeConfirmDialog = true }
                                                        )
                                                    }
                                                    VaultItemListPane(
                                                        i18n = i18n,
                                                        items = filteredItems,
                                                        selectedItem = selectedItem,
                                                        onSelectItem = { onSelectItem(it) },
                                                        onCopyPrimary = copyItemPrimaryField,
                                                        onEditItem = onEditItem,
                                                        onTogglePinItem = onTogglePinItem,
                                                        onToggleFavoriteItem = onToggleFavoriteItem,
                                                        onDuplicateItem = onDuplicateItem,
                                                        onDeleteItem = onDeleteItem,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }

                                                // Column 3: Detail Inspector (with bottom clearance for Sync Status Pill)
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
                                                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 52.dp)
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

                                        // 2. Unfolded Foldable Posture (600..839dp): NavigationRail + 2-Pane
                                        widthDp >= 600 -> {
                                            Row(modifier = Modifier.fillMaxSize()) {
                                                Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxHeight()) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .width(88.dp)
                                                            .padding(vertical = 8.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .verticalScroll(rememberScrollState()),
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Image(
                                                                painter = painterResource(id = R.drawable.ic_safepocket_brand),
                                                                contentDescription = i18n.t("app_name"),
                                                                modifier = Modifier
                                                                    .size(32.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                            )
                                                            SmoothTonalButton(
                                                                onClick = onOpenSettings,
                                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                                            ) {
                                                                Text("⚙")
                                                            }
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
                                                            .weight(0.45f)
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
                                                        if (exampleItemsCount > 0) {
                                                            DeleteExampleDataInlineCard(
                                                                i18n = i18n,
                                                                exampleCount = exampleItemsCount,
                                                                onRequestDelete = { showPurgeConfirmDialog = true }
                                                            )
                                                        }
                                                        VaultItemListPane(
                                                            i18n = i18n,
                                                            items = filteredItems,
                                                            selectedItem = selectedItem,
                                                            onSelectItem = { onSelectItem(it) },
                                                            onCopyPrimary = copyItemPrimaryField,
                                                            onEditItem = onEditItem,
                                                            onTogglePinItem = onTogglePinItem,
                                                            onToggleFavoriteItem = onToggleFavoriteItem,
                                                            onDuplicateItem = onDuplicateItem,
                                                            onDeleteItem = onDeleteItem,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(0.55f)
                                                            .fillMaxHeight()
                                                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 52.dp)
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

                                        // 3. Compact Smartphone (< 600dp): Top Header + Bottom Bar (Bottom-Left Theme + Bottom-Right Sync Pill)
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
                                                                BrandHeaderRow(title = i18n.t("app_name"))
                                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                    SmoothPrimaryButton(
                                                                        onClick = onNewItem,
                                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                                    ) {
                                                                        Text("+")
                                                                    }
                                                                    SmoothTonalButton(
                                                                        onClick = onOpenSettings,
                                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                                    ) {
                                                                        Text("⚙ ${i18n.t("nav_settings")}", maxLines = 1)
                                                                    }
                                                                }
                                                            }
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(top = 8.dp)
                                                                    .horizontalScroll(rememberScrollState()),
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                SmoothFilterPill(
                                                                    selected = selectedCategory == null && !favoritesOnly,
                                                                    onClick = {
                                                                        onSelectCategory(null)
                                                                        onSelectItem(null)
                                                                    },
                                                                    label = i18n.t("nav_all_items")
                                                                )
                                                                SmoothFilterPill(
                                                                    selected = favoritesOnly,
                                                                    onClick = {
                                                                        onSelectFavorites()
                                                                        onSelectItem(null)
                                                                    },
                                                                    label = "★ ${i18n.t("nav_favorites")}"
                                                                )
                                                                VaultCategory.entries.forEach { cat ->
                                                                    SmoothFilterPill(
                                                                        selected = selectedCategory == cat,
                                                                        onClick = {
                                                                            onSelectCategory(cat)
                                                                            onSelectItem(null)
                                                                        },
                                                                        label = "${categoryIcon(cat)} ${categoryLabel(i18n, cat)}"
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
                                                            // Bottom-Left Theme Icon + Lock
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                SmoothOutlinedButton(
                                                                    onClick = cycleTheme,
                                                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                                                                ) {
                                                                    Text(themeGlyph)
                                                                }
                                                                SmoothOutlinedButton(
                                                                    onClick = onLockVault,
                                                                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
                                                                ) {
                                                                    Text("🔒")
                                                                }
                                                            }
                                                            // Bottom-Right Sync Status Pill on Compact Mobile
                                                            BottomRightSyncStatusPill(
                                                                i18n = i18n,
                                                                snapshot = syncIndicator,
                                                                onClick = onOpenSettings
                                                            )
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
                                                        if (exampleItemsCount > 0) {
                                                            DeleteExampleDataInlineCard(
                                                                i18n = i18n,
                                                                exampleCount = exampleItemsCount,
                                                                onRequestDelete = { showPurgeConfirmDialog = true }
                                                            )
                                                        }
                                                        VaultItemListPane(
                                                            i18n = i18n,
                                                            items = filteredItems,
                                                            selectedItem = null,
                                                            onSelectItem = { onSelectItem(it) },
                                                            onCopyPrimary = copyItemPrimaryField,
                                                            onEditItem = onEditItem,
                                                            onTogglePinItem = onTogglePinItem,
                                                            onToggleFavoriteItem = onToggleFavoriteItem,
                                                            onDuplicateItem = onDuplicateItem,
                                                            onDeleteItem = onDeleteItem,
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

                                    // Persistent Bottom-Right Sync Status Pill on Tablet/Foldable/Desktop (`widthDp >= 600`)
                                    if (widthDp >= 600) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(end = 16.dp, bottom = 12.dp)
                                        ) {
                                            BottomRightSyncStatusPill(
                                                i18n = i18n,
                                                snapshot = syncIndicator,
                                                onClick = {
                                                    if (syncIndicator.storageMode == VaultStorageMode.CLOUD_SYNC) {
                                                        coroutineScope.launch {
                                                            runCatching { onSyncNow() }.onFailure { onOpenSettings() }
                                                        }
                                                    } else {
                                                        onOpenSettings()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Delete All Example Data Confirmation Modal
                    if (showPurgeConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showPurgeConfirmDialog = false },
                            title = {
                                Text(i18n.t("action_delete_example_data", mapOf("count" to exampleItemsCount)))
                            },
                            text = {
                                ProvideSeamFreeHoverIndication {
                                    Text(i18n.t("delete_example_data_confirm", mapOf("count" to exampleItemsCount)))
                                }
                            },
                            dismissButton = {
                                ProvideSeamFreeHoverIndication {
                                    SmoothTextButton(onClick = { showPurgeConfirmDialog = false }) {
                                        Text(i18n.t("action_cancel"))
                                    }
                                }
                            },
                            confirmButton = {
                                ProvideSeamFreeHoverIndication {
                                    SmoothPrimaryButton(
                                        onClick = {
                                            showPurgeConfirmDialog = false
                                            coroutineScope.launch {
                                                val removed = onDeleteAllExampleData()
                                                copiedNotice = i18n.t("delete_example_data_done", mapOf("count" to removed))
                                                delay(4_000L)
                                                copiedNotice = null
                                            }
                                        }
                                    ) {
                                        Text("🗑 ${i18n.t("action_delete")}")
                                    }
                                }
                            }
                        )
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
                            exampleItemsCount = exampleItemsCount,
                            updateCheckState = updateCheckState,
                            autoUpdateEnabled = autoUpdateEnabled,
                            diagnosticEvents = diagnosticEvents,
                            onClearDiagnosticsLog = onClearDiagnosticsLog,
                            onCopyToClipboard = copyWith30sHygiene,
                            onSelectThemePreference = onSelectThemePreference,
                            onToggleDeviceDynamicColor = onToggleDeviceDynamicColor,
                            onToggleAllowScreenshots = onToggleAllowScreenshots,
                            onToggleAutoDownloadLogos = onToggleAutoDownloadLogos,
                            onSelectLocaleOverride = onSelectLocaleOverride,
                            onConfigureVaultCredentials = onConfigureVaultCredentials,
                            onSwitchStorageMode = onSwitchStorageMode,
                            onCreateFirebaseAccount = onCreateFirebaseAccount,
                            onSignInFirebaseAccount = onSignInFirebaseAccount,
                            onSignOutFirebaseAccount = onSignOutFirebaseAccount,
                            onSyncNow = onSyncNow,
                            onDeleteAllExampleData = onDeleteAllExampleData,
                            onToggleAutoUpdate = onToggleAutoUpdate,
                            onCheckForUpdatesNow = onCheckForUpdatesNow,
                            onInstallVerifiedUpdate = onInstallVerifiedUpdate,
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
                            onVerifyWebsiteReachability = onVerifyWebsiteReachability,
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
private fun BrandHeaderRow(title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_safepocket_brand),
            contentDescription = title,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GlobalErrorBanner(
    i18n: LocalizationEngine,
    event: DiagnosticEvent,
    onRetry: (() -> Unit)?,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚠ ${event.userMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onRetry != null) {
                    SmoothTextButton(onClick = onRetry) {
                        Text(i18n.t("err_banner_retry"))
                    }
                }
                SmoothTextButton(onClick = onOpenDiagnostics) {
                    Text("⚙")
                }
                SmoothTextButton(onClick = onDismiss) {
                    Text("✕")
                }
            }
        }
    }
}

@Composable
private fun BottomRightSyncStatusPill(
    i18n: LocalizationEngine,
    snapshot: SyncIndicatorSnapshot,
    onClick: () -> Unit
) {
    val (icon, label, dotColor) = when (snapshot.status) {
        SyncBadgeStatus.LOCAL_ONLY -> Triple(
            "💾",
            i18n.t("sync_pill_local_only"),
            Color(0xFF0A8F6A)
        )
        SyncBadgeStatus.CLOUD_SYNCED -> Triple(
            "☁",
            i18n.t("sync_pill_cloud_synced", mapOf("detail" to "Live")),
            Color(0xFF0A8F6A)
        )
        SyncBadgeStatus.SYNCING -> Triple(
            "↻",
            i18n.t("sync_pill_syncing"),
            Color(0xFF2563EB)
        )
        SyncBadgeStatus.CLOUD_OFFLINE_ARMED -> Triple(
            "⚡",
            i18n.t("sync_pill_cloud_offline_armed", mapOf("queued" to snapshot.queuedMutationCount)),
            Color(0xFFD97706)
        )
        SyncBadgeStatus.SYNC_ERROR -> Triple(
            "⚠",
            i18n.t("sync_pill_error"),
            MaterialTheme.colorScheme.error
        )
    }

    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 3.dp,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
    ) {
        SmoothTextButton(
            onClick = onClick,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$icon $label",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeleteExampleDataInlineCard(
    i18n: LocalizationEngine,
    exampleCount: Int,
    onRequestDelete: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = i18n.t("action_delete_example_data", mapOf("count" to exampleCount)),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            SmoothTonalButton(
                onClick = onRequestDelete,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("🗑 ${i18n.t("action_delete")}")
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
    SmoothSidebarItem(
        icon = "📥",
        label = i18n.t("nav_all_items"),
        selected = selectedCategory == null && !favoritesOnly,
        onClick = { onSelectCategory(null) }
    )
    SmoothSidebarItem(
        icon = "★",
        label = i18n.t("nav_favorites"),
        selected = favoritesOnly,
        onClick = onSelectFavorites
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    VaultCategory.entries.forEach { category ->
        SmoothSidebarItem(
            icon = categoryIcon(category),
            label = categoryLabel(i18n, category),
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
    onCopyPrimary: (VaultItemPayload) -> Unit,
    onEditItem: (VaultItemPayload) -> Unit,
    onTogglePinItem: (VaultItemPayload) -> Unit,
    onToggleFavoriteItem: (VaultItemPayload) -> Unit,
    onDuplicateItem: (VaultItemPayload) -> Unit,
    onDeleteItem: (VaultItemPayload) -> Unit,
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
        Text(
            text = i18n.t("swipe_hint_caption"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        items.forEach { item ->
            key(item.id) {
                SwipeAndContextVaultItemRow(
                    i18n = i18n,
                    item = item,
                    isSelected = selectedItem?.id == item.id,
                    onSelect = { onSelectItem(item) },
                    onCopyPrimary = { onCopyPrimary(item) },
                    onEdit = { onEditItem(item) },
                    onTogglePin = { onTogglePinItem(item) },
                    onToggleFavorite = { onToggleFavoriteItem(item) },
                    onDuplicate = { onDuplicateItem(item) },
                    onDelete = { onDeleteItem(item) }
                )
            }
        }
        Spacer(Modifier.height(44.dp))
    }
}

/**
 * Vault entry row supporting:
 * 1. Horizontal Swipe Right -> Pin / Favorite quick toggle
 * 2. Horizontal Swipe Left -> Reveals `Edit`, `Copy Primary`, `Delete` action tray
 * 3. Hardware Right-Click (`PointerButton.Secondary`) & Touch Long-Press Context Menu
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeAndContextVaultItemRow(
    i18n: LocalizationEngine,
    item: VaultItemPayload,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onCopyPrimary: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val actionTrayWidthPx = with(density) { 184.dp.toPx() }
    val pinTriggerPx = with(density) { 88.dp.toPx() }

    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    val animatedOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "swipeRowOffset"
    )

    val resolvedBrand = remember(item) { InstitutionBrandRegistry.resolveForItem(item) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
    ) {
        // Background Swipe Action Trays
        if (animatedOffsetPx > 6f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "📌 ★ ${if (item.pinned) i18n.t("context_unpin") else i18n.t("context_pin")}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else if (animatedOffsetPx < -6f) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmoothTonalButton(
                    onClick = {
                        dragOffsetPx = 0f
                        onEdit()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("✎")
                }
                Spacer(Modifier.width(4.dp))
                SmoothOutlinedButton(
                    onClick = {
                        dragOffsetPx = 0f
                        onCopyPrimary()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("⎘")
                }
                Spacer(Modifier.width(4.dp))
                SmoothOutlinedButton(
                    onClick = {
                        dragOffsetPx = 0f
                        onDelete()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("🗑")
                }
            }
        }

        // Foreground Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = if (isSelected) 4.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                .border(
                    width = 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                    },
                    shape = RoundedCornerShape(14.dp)
                )
                .pointerInput(item.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                val pos = event.changes.firstOrNull()?.position
                                if (pos != null) {
                                    contextMenuOffset = with(density) {
                                        DpOffset(pos.x.toDp(), pos.y.toDp())
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                contextMenuExpanded = true
                            }
                        }
                    }
                }
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dragAmount)
                                .coerceIn(-actionTrayWidthPx, pinTriggerPx * 1.35f)
                        },
                        onDragEnd = {
                            when {
                                dragOffsetPx >= pinTriggerPx -> {
                                    dragOffsetPx = 0f
                                    onTogglePin()
                                }
                                dragOffsetPx <= -(actionTrayWidthPx * 0.45f) -> {
                                    dragOffsetPx = -actionTrayWidthPx
                                }
                                else -> {
                                    dragOffsetPx = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            dragOffsetPx = 0f
                        }
                    )
                }
                .combinedClickable(
                    onClick = {
                        if (dragOffsetPx != 0f) {
                            dragOffsetPx = 0f
                        } else {
                            onSelect()
                        }
                    },
                    onLongClick = {
                        contextMenuExpanded = true
                    }
                )
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

        // Right-Click / Long-Press Context Menu
        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false },
            offset = contextMenuOffset
        ) {
            ProvideSeamFreeHoverIndication {
                Column {
                    if (item.primaryCopyableField() != null) {
                        DropdownMenuItem(
                            text = { Text("⎘  ${i18n.t("context_copy_primary")}") },
                            onClick = {
                                contextMenuExpanded = false
                                onCopyPrimary()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("✎  ${i18n.t("action_edit")}") },
                        onClick = {
                            contextMenuExpanded = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (item.pinned) "📌  ${i18n.t("context_unpin")}"
                                else "📌  ${i18n.t("context_pin")}"
                            )
                        },
                        onClick = {
                            contextMenuExpanded = false
                            onTogglePin()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (item.favorite) "★  ${i18n.t("context_unfavorite")}"
                                else "☆  ${i18n.t("context_favorite")}"
                            )
                        },
                        onClick = {
                            contextMenuExpanded = false
                            onToggleFavorite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("⧉  ${i18n.t("action_duplicate")}") },
                        onClick = {
                            contextMenuExpanded = false
                            onDuplicate()
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "🗑  ${i18n.t("action_delete")}",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            contextMenuExpanded = false
                            onDelete()
                        }
                    )
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
// INTELLIGENT ITEM CREATE & EDIT DIALOG (`v0.3`)
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
    onVerifyWebsiteReachability: suspend (String) -> WebsiteReachabilityResult,
    onSave: (VaultItemPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val activeLocale by i18n.activeLocale.collectAsState()
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
            val rawVal = standardFieldValues[spec.key]?.trim().orEmpty()
            val normalizedVal = if (spec.key == CanonicalFieldKeys.INSTITUTION_DOMAIN && rawVal.isNotEmpty()) {
                SmartFieldIntelligenceEngine.normalizeHttpsUrl(rawVal)
            } else {
                rawVal
            }
            if (normalizedVal.isEmpty()) null
            else VaultField(
                id = "f-std-$idx-${spec.key}",
                labelKey = spec.key,
                isCustomLabel = false,
                value = normalizedVal,
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
            isSampleItem = false,
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
            ProvideSeamFreeHoverIndication {
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
                        val detectedInst = liveBrand.institution
                        if (detectedInst != null) {
                            Text(
                                text = "✓ ${i18n.t("brand_detected_badge", mapOf("institution" to detectedInst.displayName, "network" to liveBrand.networkDisplayLabel))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Category Selector (Zero-Ripple SmoothFilterPills)
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
                            SmoothFilterPill(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = "${categoryIcon(cat)} ${categorySingularLabel(i18n, cat)}"
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
                                        onValueChange = { raw ->
                                            val normalized = SmartFieldIntelligenceEngine.normalizeHexColor(raw)
                                            customColorHex = normalized
                                            if (normalized.length == 7) {
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

                    // Intelligent Category-Specific Structured Fields (Dropdowns, https:// Verify, Phone Prefix, IBAN/PAN Autofill)
                    HorizontalDivider()
                    templateSpecs.forEach { spec ->
                        val curVal = standardFieldValues[spec.key].orEmpty()
                        SmartStructuredFieldInput(
                            spec = spec,
                            category = category,
                            i18n = i18n,
                            activeLocaleCode = activeLocale.code,
                            value = curVal,
                            onValueChange = { standardFieldValues[spec.key] = it },
                            onCrossFieldAutofill = { suggestions ->
                                val appliedNames = mutableListOf<String>()
                                for ((targetKey, suggestedValue) in suggestions) {
                                    if (standardFieldValues[targetKey].isNullOrBlank() && suggestedValue.isNotBlank()) {
                                        standardFieldValues[targetKey] = suggestedValue
                                        appliedNames += i18n.resolveFieldLabel(targetKey, false, category)
                                    }
                                }
                                if (title.isBlank()) {
                                    val bankOrOrg = standardFieldValues[CanonicalFieldKeys.ISSUING_BANK]
                                        ?: standardFieldValues[CanonicalFieldKeys.INSURER_NAME]
                                        ?: standardFieldValues[CanonicalFieldKeys.ORGANIZATION]
                                    if (!bankOrOrg.isNullOrBlank()) {
                                        title = bankOrOrg
                                    }
                                }
                                appliedNames
                            },
                            onVerifyWebsite = onVerifyWebsiteReachability
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
            }
        },
        dismissButton = {
            ProvideSeamFreeHoverIndication {
                SmoothTextButton(onClick = onDismiss) {
                    Text(i18n.t("action_cancel"))
                }
            }
        },
        confirmButton = {
            ProvideSeamFreeHoverIndication {
                SmoothPrimaryButton(
                    enabled = title.isNotBlank() || standardFieldValues.values.any { it.isNotBlank() },
                    onClick = { onSave(buildPreviewPayload()) }
                ) {
                    Text(i18n.t("action_save"))
                }
            }
        }
    )
}

// =============================================================================
// COMPREHENSIVE v0.3 SETTINGS DIALOG:
// EXCLUSIVE STORAGE MODE + AUTOMATED UPDATES + PORTABILITY + DIAGNOSTICS LOG
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
    exampleItemsCount: Int,
    updateCheckState: UpdateCheckState,
    autoUpdateEnabled: Boolean,
    diagnosticEvents: List<DiagnosticEvent>,
    onClearDiagnosticsLog: () -> Unit,
    onCopyToClipboard: (label: String, value: String, isSensitive: Boolean) -> Unit,
    onSelectThemePreference: (ThemePreference) -> Unit,
    onToggleDeviceDynamicColor: (Boolean) -> Unit,
    onToggleAllowScreenshots: (Boolean) -> Unit,
    onToggleAutoDownloadLogos: (Boolean) -> Unit,
    onSelectLocaleOverride: (SupportedLocale?) -> Unit,
    onConfigureVaultCredentials: suspend (masterPw: String, secretKeyOrUri: String?) -> Unit,
    onSwitchStorageMode: suspend (VaultStorageMode) -> Unit,
    onCreateFirebaseAccount: suspend (email: String, password: String) -> Unit,
    onSignInFirebaseAccount: suspend (email: String, password: String) -> Unit,
    onSignOutFirebaseAccount: suspend () -> Unit,
    onSyncNow: suspend () -> BidirectionalSyncReport?,
    onDeleteAllExampleData: suspend () -> Int,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onCheckForUpdatesNow: suspend () -> Unit,
    onInstallVerifiedUpdate: suspend (RemoteUpdateManifest, PlatformArtifactDescriptor) -> String?,
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

    val currentStorageMode = cloudSyncStatus?.storageMode ?: VaultStorageMode.LOCAL_ONLY
    var desiredModeTab by remember(currentStorageMode) { mutableStateOf(currentStorageMode) }

    var statusFeedback by remember { mutableStateOf<String?>(null) }
    var busyOperation by remember { mutableStateOf(false) }

    // Master Password + Secret Key inputs
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
            ProvideSeamFreeHoverIndication {
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
                    // 1. MUTUALLY EXCLUSIVE VAULT STORAGE MODE (`LOCAL_ONLY` XOR `CLOUD_SYNC`)
                    // -----------------------------------------------------------------
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = i18n.t("storage_mode_section_title"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmoothFilterPill(
                                    selected = desiredModeTab == VaultStorageMode.LOCAL_ONLY,
                                    onClick = {
                                        desiredModeTab = VaultStorageMode.LOCAL_ONLY
                                        if (currentStorageMode != VaultStorageMode.LOCAL_ONLY) {
                                            coroutineScope.launch {
                                                busyOperation = true
                                                runCatching {
                                                    onSwitchStorageMode(VaultStorageMode.LOCAL_ONLY)
                                                    statusFeedback = "✓ ${i18n.t("storage_mode_local_only_title")}"
                                                }.onFailure { err ->
                                                    statusFeedback = "⚠ ${err.message}"
                                                }
                                                busyOperation = false
                                            }
                                        }
                                    },
                                    label = "💾 ${i18n.t("storage_mode_local_only_title")}"
                                )
                                SmoothFilterPill(
                                    selected = desiredModeTab == VaultStorageMode.CLOUD_SYNC,
                                    onClick = {
                                        desiredModeTab = VaultStorageMode.CLOUD_SYNC
                                        if (!cloudSyncStatus?.signedInEmail.isNullOrBlank() &&
                                            currentStorageMode != VaultStorageMode.CLOUD_SYNC
                                        ) {
                                            coroutineScope.launch {
                                                busyOperation = true
                                                runCatching {
                                                    onSwitchStorageMode(VaultStorageMode.CLOUD_SYNC)
                                                    statusFeedback = "✓ ${i18n.t("storage_mode_cloud_only_title")}"
                                                }.onFailure { err ->
                                                    statusFeedback = "⚠ ${err.message}"
                                                }
                                                busyOperation = false
                                            }
                                        }
                                    },
                                    label = "☁ ${i18n.t("storage_mode_cloud_only_title")}"
                                )
                            }

                            if (desiredModeTab == VaultStorageMode.LOCAL_ONLY) {
                                Text(
                                    text = i18n.t("storage_mode_local_only_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (localDbStatus != null) {
                                    Text(
                                        text = "${localDbStatus.backendLabel} • ${localDbStatus.databaseFileName} (${localDbStatus.databaseSizeBytes / 1024} KiB) • ${localDbStatus.activeItemCount} items",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                Text(
                                    text = i18n.t("storage_mode_cloud_only_desc"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!cloudSyncStatus?.signedInEmail.isNullOrBlank()) {
                                    Text(
                                        text = i18n.t(
                                            "cloud_auth_signed_in_as",
                                            mapOf(
                                                "email" to (cloudSyncStatus?.signedInEmail ?: ""),
                                                "uid" to (cloudSyncStatus?.signedInUid?.take(10) ?: "")
                                            )
                                        ) + " • Queued Offline: ${cloudSyncStatus?.queuedOfflineMutations ?: 0}",
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
                                                            statusFeedback = "✓ Synced (↑${rep.pushedCount} • ↓${rep.pulledCount})"
                                                        }
                                                    }.onFailure { err ->
                                                        statusFeedback = "⚠ ${err.message}"
                                                    }
                                                    busyOperation = false
                                                }
                                            }
                                        ) {
                                            Text("⇄ ${i18n.t("sync_now")}")
                                        }
                                        SmoothOutlinedButton(
                                            enabled = !busyOperation,
                                            onClick = {
                                                coroutineScope.launch {
                                                    busyOperation = true
                                                    onSignOutFirebaseAccount()
                                                    desiredModeTab = VaultStorageMode.LOCAL_ONLY
                                                    statusFeedback = "Signed out & switched to Local Storage Only"
                                                    busyOperation = false
                                                }
                                            }
                                        ) {
                                            Text(i18n.t("cloud_auth_signout_btn"))
                                        }
                                    }
                                } else {
                                    Text(
                                        text = i18n.t("storage_mode_cloud_auth_required"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
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
                                                        statusFeedback = "✓ Cloud Sync Only active (standalone local DB purged; automatic offline mirror armed)"
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
                                                        statusFeedback = "✓ Signed in to Cloud Sync Only (automatic offline mirror armed)"
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

                            if (exampleItemsCount > 0) {
                                HorizontalDivider()
                                SmoothOutlinedButton(
                                    enabled = !busyOperation,
                                    onClick = {
                                        coroutineScope.launch {
                                            busyOperation = true
                                            val removed = onDeleteAllExampleData()
                                            statusFeedback = "✓ ${i18n.t("delete_example_data_done", mapOf("count" to removed))}"
                                            busyOperation = false
                                        }
                                    }
                                ) {
                                    Text("🗑 ${i18n.t("action_delete_example_data", mapOf("count" to exampleItemsCount))}")
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // 2. ZERO-KNOWLEDGE VAULT CREDENTIALS (MASTER PASSWORD & SECRET KEY)
                    // -----------------------------------------------------------------
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${i18n.t("security_master_password")} & ${i18n.t("security_secret_key")}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (localDbStatus != null) {
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
                                                statusFeedback = "✓ Updated Master Password & re-encrypted vault"
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
                    // 3. GOOGLE PLAY PROTECT COMPLIANT AUTOMATED UPDATES
                    // -----------------------------------------------------------------
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = i18n.t("update_section_title"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = i18n.t("update_auto_toggle"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = autoUpdateEnabled,
                                    onCheckedChange = onToggleAutoUpdate
                                )
                            }

                            val updateSummary = when (val st = updateCheckState) {
                                UpdateCheckState.Idle -> AppBuildInfo.DISPLAY_STRING
                                UpdateCheckState.Checking -> "⏳ Checking signed release manifest…"
                                is UpdateCheckState.UpToDate -> "✓ ${i18n.t("update_up_to_date")} (v${st.currentVersion} b${st.buildNumber})"
                                is UpdateCheckState.UpdateAvailable -> "✨ ${i18n.t("update_available_badge", mapOf("version" to st.manifest.latestVersion))} — ${st.localizedChangelog}"
                                is UpdateCheckState.Downloading -> "⬇ ${i18n.t("update_verifying_checksum")} (${(st.progressFraction * 100).roundToInt()}%)"
                                is UpdateCheckState.VerifyingChecksum -> "🔐 ${i18n.t("update_verifying_checksum")}"
                                is UpdateCheckState.ReadyToInstall -> "✓ ${i18n.t("update_ready_restart")} (SHA-256: ${st.verifiedSha256.take(12)}…)"
                                is UpdateCheckState.Failed -> "⚠ ${st.reason}"
                            }
                            Text(
                                text = updateSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmoothTonalButton(
                                    enabled = updateCheckState !is UpdateCheckState.Checking &&
                                        updateCheckState !is UpdateCheckState.Downloading,
                                    onClick = {
                                        coroutineScope.launch { onCheckForUpdatesNow() }
                                    }
                                ) {
                                    Text("↻ ${i18n.t("update_check_now")}")
                                }
                                if (updateCheckState is UpdateCheckState.UpdateAvailable) {
                                    SmoothPrimaryButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                val note = onInstallVerifiedUpdate(
                                                    updateCheckState.manifest,
                                                    updateCheckState.artifact
                                                )
                                                if (!note.isNullOrBlank()) {
                                                    statusFeedback = note
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            i18n.t(
                                                "update_install_now",
                                                mapOf("version" to updateCheckState.manifest.latestVersion)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // 4. ENCRYPTED BACKUP + CSV & 1PASSWORD IMPORT / EXPORT
                    // -----------------------------------------------------------------
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = i18n.t("portability_section_title"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )

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
                    // 5. SECURITY & AUTOMATIC BRAND LOGO DOWNLOAD
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
                    // 6. APPEARANCE & LANGUAGE (SEAM-FREE HOVER PILLS — NO GRAY LINE!)
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
                                SmoothFilterPill(
                                    selected = themePreference == ThemePreference.LIGHT,
                                    onClick = { onSelectThemePreference(ThemePreference.LIGHT) },
                                    label = i18n.t("theme_light")
                                )
                                SmoothFilterPill(
                                    selected = themePreference == ThemePreference.DARK,
                                    onClick = { onSelectThemePreference(ThemePreference.DARK) },
                                    label = i18n.t("theme_dark")
                                )
                                SmoothFilterPill(
                                    selected = themePreference == ThemePreference.SYSTEM,
                                    onClick = { onSelectThemePreference(ThemePreference.SYSTEM) },
                                    label = i18n.t("theme_system")
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
                                SmoothFilterPill(
                                    selected = manualLocaleOverride == null,
                                    onClick = { onSelectLocaleOverride(null) },
                                    label = i18n.t(
                                        "settings_language_system",
                                        mapOf("locale" to i18n.detectedSystemLocale.nativeDisplayName)
                                    )
                                )
                                SupportedLocale.entries.forEach { locale ->
                                    SmoothFilterPill(
                                        selected = manualLocaleOverride == locale,
                                        onClick = { onSelectLocaleOverride(locale) },
                                        label = locale.nativeDisplayName
                                    )
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // 7. PRIVACY-SCRUBBED DIAGNOSTICS & ERROR LOG
                    // -----------------------------------------------------------------
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = i18n.t("err_diagnostics_title"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (diagnosticEvents.isNotEmpty()) {
                                    SmoothTextButton(onClick = onClearDiagnosticsLog) {
                                        Text(i18n.t("err_clear_log"))
                                    }
                                }
                            }
                            if (diagnosticEvents.isEmpty()) {
                                Text(
                                    text = i18n.t("err_diagnostics_empty"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                diagnosticEvents.take(8).forEach { ev ->
                                    Text(
                                        text = "• [${ev.code}] ${ev.operation}: ${ev.scrubbedDetail}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    // 8. Version & Cryptographic KDF Badge
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
            }
        },
        confirmButton = {
            ProvideSeamFreeHoverIndication {
                SmoothPrimaryButton(onClick = onDismiss) {
                    Text("OK")
                }
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
                Image(
                    painter = painterResource(id = R.drawable.ic_safepocket_brand),
                    contentDescription = i18n.t("app_name"),
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
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
