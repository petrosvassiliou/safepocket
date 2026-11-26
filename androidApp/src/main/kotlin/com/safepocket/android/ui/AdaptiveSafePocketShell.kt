package com.safepocket.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.safepocket.shared.scanner.EpcSepaAndQrEngine
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import com.safepocket.shared.search.DocumentReadiness
import com.safepocket.shared.search.MultilingualSearchEngine
import com.safepocket.shared.search.ReadinessTimelineBucket
import com.safepocket.shared.update.AppBuildInfo
import com.safepocket.shared.update.PlatformArtifactDescriptor
import com.safepocket.shared.update.RemoteUpdateManifest
import com.safepocket.shared.update.UpdateCheckState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    initialCreateCategory: VaultCategory = VaultCategory.PAYMENT_CARD,
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
    onNewItemWithCategory: (VaultCategory) -> Unit = { onNewItem() },
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
    var clipboardCountdownSecs by remember { mutableStateOf<Int?>(null) }
    var presentingPassItem by remember { mutableStateOf<VaultItemPayload?>(null) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var expiringOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf<String?>(null) }
    var showPurgeConfirmDialog by remember { mutableStateOf(false) }
    var openNewMenuTrigger by remember { mutableIntStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }

    val exampleItemsCount = remember(items) { items.count { it.isBuiltInSample() } }

    LaunchedEffect(focusSearchTrigger) {
        if (focusSearchTrigger > 0) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val prefs = remember(context) {
        context.getSharedPreferences("safepocket_android_prefs", Context.MODE_PRIVATE)
    }
    var materialAccentId by remember {
        mutableStateOf(prefs.getString("material_accent_id", "emerald") ?: "emerald")
    }
    var expiryWindowDays by remember {
        val saved = prefs.getInt("expiry_window_days", 90)
        mutableIntStateOf(if (saved in listOf(30, 60, 90, 180)) saved else 90)
    }
    val referenceEpochMs = remember(items, expiryWindowDays) { System.currentTimeMillis() }
    val readinessAttentionCount = remember(items, expiryWindowDays, referenceEpochMs) {
        items.count {
            MultilingualSearchEngine.computeDocumentReadiness(it, referenceEpochMs, expiryWindowDays).needsReadinessAttention
        }
    }

    val searchEngine = remember(i18n, activeLocale) { MultilingualSearchEngine(i18n) }
    val filteredItems = remember(
        items,
        searchQuery,
        selectedCategory,
        favoritesOnly,
        expiringOnly,
        expiryWindowDays,
        referenceEpochMs,
        activeLocale
    ) {
        val searched = searchEngine.search(
            items = items,
            rawQuery = searchQuery,
            categoryFilter = if (expiringOnly) null else selectedCategory,
            favoritesOnly = if (expiringOnly) false else favoritesOnly
        )
        if (expiringOnly) {
            val attention = searched.filter {
                MultilingualSearchEngine.computeDocumentReadiness(it, referenceEpochMs, expiryWindowDays).needsReadinessAttention
            }
            searchEngine.sortItems(attention, VaultSortMode.EXPIRING_SOON)
        } else {
            searchEngine.sortItems(searched, VaultSortMode.PINNED_RECENT)
        }
    }
    var autoLockMinutes by remember {
        val saved = prefs.getInt("auto_lock_minutes", 5)
        mutableIntStateOf(if (saved in listOf(0, 1, 5, 15)) saved else 5)
    }
    var lockOnStandby by remember {
        mutableStateOf(prefs.getBoolean("lock_on_standby", true))
    }

    DisposableEffect(context, lockOnStandby) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF && lockOnStandby) {
                    onLockVault()
                }
            }
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        runCatching { context.registerReceiver(receiver, filter) }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(autoLockMinutes, isVaultLocked, items, selectedItem, searchQuery, showSettingsDialog, showEditorDialog) {
        if (!isVaultLocked && autoLockMinutes > 0) {
            delay(autoLockMinutes * 60_000L)
            onLockVault()
        }
    }

    val useDarkTheme = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val accentPrimaryLight = when (materialAccentId) {
        "ocean" -> Color(0xFF1D6EC7)
        "terracotta" -> Color(0xFFC25730)
        "slate" -> Color(0xFF455A70)
        else -> Color(0xFF148F73) // emerald
    }
    val accentPrimaryDark = when (materialAccentId) {
        "ocean" -> Color(0xFF7CB8FF)
        "terracotta" -> Color(0xFFFFB59C)
        "slate" -> Color(0xFFAEC6DF)
        else -> Color(0xFF5EEAD4) // emerald
    }
    val supportsDynamicColor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val colorScheme = when {
        useDeviceDynamicColor && supportsDynamicColor && useDarkTheme -> dynamicDarkColorScheme(context)
        useDeviceDynamicColor && supportsDynamicColor && !useDarkTheme -> dynamicLightColorScheme(context)
        useDarkTheme -> darkColorScheme(primary = accentPrimaryDark)
        else -> lightColorScheme(primary = accentPrimaryLight)
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
            if (isSensitive) {
                copiedNotice = null
                for (rem in 30 downTo 1) {
                    clipboardCountdownSecs = rem
                    delay(1_000L)
                }
                clipboard.clearPrimaryClip()
                clipboardCountdownSecs = null
                copiedNotice = i18n.t("clipboard_hygiene_cleared")
                delay(2_500L)
                copiedNotice = null
            } else {
                clipboardCountdownSecs = null
                copiedNotice = "${i18n.t("action_copied")}: $label"
                delay(2_800L)
                copiedNotice = null
            }
        }
    }

    val scrubClipboardNow: () -> Unit = {
        clipboardJob?.cancel()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        clipboardCountdownSecs = null
        coroutineScope.launch {
            copiedNotice = i18n.t("clipboard_hygiene_cleared")
            delay(2_500L)
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

    val copyItemSecondaryField: (VaultItemPayload) -> Unit = { target ->
        val secondaryKeys = listOf(
            CanonicalFieldKeys.CVV,
            CanonicalFieldKeys.PIN,
            CanonicalFieldKeys.BIC,
            CanonicalFieldKeys.ROUTING_CODE,
            CanonicalFieldKeys.ONLINE_PASSCODE
        )
        val secondary = secondaryKeys.firstNotNullOfOrNull { key ->
            target.fields.firstOrNull { !it.isCustomLabel && it.labelKey == key && it.value.isNotBlank() }
        }
        if (secondary != null) {
            val resolvedLabel = i18n.resolveFieldLabel(secondary.labelKey, secondary.isCustomLabel, target.category)
            copyWith30sHygiene(resolvedLabel, secondary.value, true)
        } else {
            copyItemPrimaryField(target)
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        ProvideSeamFreeHoverIndication {
            key(activeLocale.code) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed)) {
                                when (event.key) {
                                    Key.K -> {
                                        if (!isVaultLocked) showCommandPalette = !showCommandPalette
                                        true
                                    }
                                    Key.P -> {
                                        if (!isVaultLocked && selectedItem != null && selectedItem.category != VaultCategory.SECURE_NOTE) {
                                            presentingPassItem = selectedItem
                                        }
                                        true
                                    }
                                    Key.F -> { onFocusSearch(); true }
                                    Key.N -> { openNewMenuTrigger++; true }
                                    Key.E -> { selectedItem?.let { onEditItem(it) }; true }
                                    Key.L -> { onLockVault(); true }
                                    Key.Comma -> { onOpenSettings(); true }
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
                                        activeLocale = activeLocale,
                                        manualLocaleOverride = manualLocaleOverride,
                                        onSelectLocaleOverride = onSelectLocaleOverride,
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
                                                        .fillMaxHeight(),
                                                    windowInsets = WindowInsets(0, 0, 0, 0)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.fillMaxSize(),
                                                        verticalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .padding(start = 10.dp, end = 12.dp, top = 14.dp)
                                                                .verticalScroll(rememberScrollState()),
                                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            // Crisp Studio App Icon + SafePocket Title
                                                            BrandHeaderRow(title = i18n.t("app_name"))
                                                            Spacer(Modifier.height(6.dp))
                                                            CategoryDrawerDestinations(
                                                                i18n = i18n,
                                                                selectedCategory = selectedCategory,
                                                                favoritesOnly = favoritesOnly,
                                                                expiringOnly = expiringOnly,
                                                                readinessAttentionCount = readinessAttentionCount,
                                                                onSelectCategory = { cat ->
                                                                    expiringOnly = false
                                                                    onSelectCategory(cat)
                                                                },
                                                                onSelectFavorites = {
                                                                    expiringOnly = false
                                                                    onSelectFavorites()
                                                                },
                                                                onSelectExpiring = {
                                                                    onSelectCategory(null)
                                                                    expiringOnly = true
                                                                }
                                                            )
                                                        }

                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                                            horizontalAlignment = Alignment.CenterHorizontally
                                                        ) {
                                                            HorizontalDivider()
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                SmoothOutlinedIconButton(
                                                                    onClick = cycleTheme,
                                                                    size = 36.dp
                                                                ) {
                                                                    CenteredThemeContrastIcon(themePreference.name)
                                                                }
                                                                MainAppFlagLanguageSelector(
                                                                    i18n = i18n,
                                                                    activeLocale = activeLocale,
                                                                    manualLocaleOverride = manualLocaleOverride,
                                                                    onSelectLocaleOverride = onSelectLocaleOverride,
                                                                    modifier = Modifier.height(36.dp)
                                                                )
                                                                SmoothOutlinedIconButton(
                                                                    onClick = onLockVault,
                                                                    size = 36.dp
                                                                ) {
                                                                    CenteredLockOutlineIcon()
                                                                }
                                                            }
                                                            Text(
                                                                text = "v${AppBuildInfo.CURRENT_VERSION_NAME} (b${AppBuildInfo.CURRENT_VERSION_CODE})",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1
                                                            )
                                                        }
                                                    }
                                                }

                                                // Column 2: Search + Delete Example Data Banner + Swipe/Right-Click List
                                                Column(
                                                    modifier = Modifier
                                                        .width(356.dp)
                                                        .fillMaxHeight()
                                                        .padding(horizontal = 14.dp, vertical = 16.dp),
                                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    SearchAndCreateHeader(
                                                        i18n = i18n,
                                                        query = searchQuery,
                                                        onQueryChange = { searchQuery = it },
                                                        onOpenCommandPalette = { showCommandPalette = true },
                                                        onNewItemWithCategory = onNewItemWithCategory,
                                                        openNewMenuTrigger = openNewMenuTrigger,
                                                        onOpenSettings = onOpenSettings,
                                                        searchFocusRequester = searchFocusRequester
                                                    )
                                                    if (exampleItemsCount > 0) {
                                                        DeleteExampleDataInlineCard(
                                                            i18n = i18n,
                                                            exampleCount = exampleItemsCount,
                                                            onRequestDelete = { showPurgeConfirmDialog = true }
                                                        )
                                                    }
                                                    if (selectedCategory == null && !favoritesOnly && !expiringOnly && searchQuery.isBlank()) {
                                                        PinnedWalletDeckStrip(
                                                            i18n = i18n,
                                                            items = items,
                                                            selectedItemId = selectedItem?.id,
                                                            onSelectItem = { onSelectItem(it) }
                                                        )
                                                    }
                                                    VaultItemListPane(
                                                        i18n = i18n,
                                                        items = filteredItems,
                                                        selectedItem = selectedItem,
                                                        expiringOnly = expiringOnly,
                                                        expiryWindowDays = expiryWindowDays,
                                                        referenceEpochMs = referenceEpochMs,
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

                                                // Column 3: Detail Inspector + Right-Aligned Bottom Status Bar
                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxWidth()
                                                            .dragAndDropTarget(
                                                                shouldStartDragAndDrop = { true },
                                                                target = remember {
                                                                    object : DragAndDropTarget {
                                                                        override fun onDrop(event: DragAndDropEvent): Boolean =
                                                                            onDropDocumentAttachment(event)
                                                                    }
                                                                }
                                                            )
                                                            .padding(horizontal = 24.dp, vertical = 20.dp)
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
                                                            onDelete = onDeleteItem,
                                                            onPresentPass = { presentingPassItem = it }
                                                        )
                                                    }
                                                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                                    Surface(color = MaterialTheme.colorScheme.surface) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 16.dp, vertical = 4.dp),
                                                            horizontalArrangement = Arrangement.End,
                                                            verticalAlignment = Alignment.CenterVertically
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

                                        // 2. Unfolded Foldable Posture (600..839dp): NavigationRail + 2-Pane
                                        widthDp >= 600 -> {
                                            Row(modifier = Modifier.fillMaxSize()) {
                                                Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxHeight()) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .width(88.dp)
                                                            .padding(top = 8.dp, bottom = 6.dp),
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
                                                            NavigationRailItem(
                                                                selected = selectedCategory == null && !favoritesOnly && !expiringOnly,
                                                                onClick = {
                                                                    expiringOnly = false
                                                                    onSelectCategory(null)
                                                                },
                                                                icon = { Text("📥") },
                                                                label = { Text(i18n.t("nav_all_items"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                            )
                                                            NavigationRailItem(
                                                                selected = expiringOnly,
                                                                onClick = {
                                                                    onSelectCategory(null)
                                                                    expiringOnly = true
                                                                },
                                                                icon = { Text("⏳") },
                                                                label = { Text(i18n.t("nav_expiring"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                            )
                                                            VaultCategory.entries.forEach { cat ->
                                                                NavigationRailItem(
                                                                    selected = selectedCategory == cat && !expiringOnly,
                                                                    onClick = {
                                                                        expiringOnly = false
                                                                        onSelectCategory(cat)
                                                                    },
                                                                    icon = { Text(categoryIcon(cat)) },
                                                                    label = { Text(categoryLabel(i18n, cat), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                                                )
                                                            }
                                                        }
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.spacedBy(5.dp)
                                                        ) {
                                                            MainAppFlagLanguageSelector(
                                                                i18n = i18n,
                                                                activeLocale = activeLocale,
                                                                manualLocaleOverride = manualLocaleOverride,
                                                                onSelectLocaleOverride = onSelectLocaleOverride,
                                                                compact = true
                                                            )
                                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                SmoothOutlinedIconButton(
                                                                    onClick = cycleTheme,
                                                                    size = 34.dp
                                                                ) {
                                                                    CenteredThemeContrastIcon(themePreference.name)
                                                                }
                                                                SmoothOutlinedIconButton(
                                                                    onClick = onLockVault,
                                                                    size = 34.dp
                                                                ) {
                                                                    CenteredLockOutlineIcon()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxWidth()
                                                    ) {
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
                                                                onOpenCommandPalette = { showCommandPalette = true },
                                                                onNewItemWithCategory = onNewItemWithCategory,
                                                                openNewMenuTrigger = openNewMenuTrigger,
                                                                onOpenSettings = onOpenSettings,
                                                                searchFocusRequester = searchFocusRequester
                                                            )
                                                            if (exampleItemsCount > 0) {
                                                                DeleteExampleDataInlineCard(
                                                                    i18n = i18n,
                                                                    exampleCount = exampleItemsCount,
                                                                    onRequestDelete = { showPurgeConfirmDialog = true }
                                                                )
                                                            }
                                                            if (selectedCategory == null && !favoritesOnly && !expiringOnly && searchQuery.isBlank()) {
                                                                PinnedWalletDeckStrip(
                                                                    i18n = i18n,
                                                                    items = items,
                                                                    selectedItemId = selectedItem?.id,
                                                                    onSelectItem = { onSelectItem(it) }
                                                                )
                                                            }
                                                            VaultItemListPane(
                                                                i18n = i18n,
                                                                items = filteredItems,
                                                                selectedItem = selectedItem,
                                                                expiringOnly = expiringOnly,
                                                                expiryWindowDays = expiryWindowDays,
                                                                referenceEpochMs = referenceEpochMs,
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
                                                                onDelete = onDeleteItem,
                                                                onPresentPass = { presentingPassItem = it }
                                                            )
                                                        }
                                                    }
                                                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                                    Surface(color = MaterialTheme.colorScheme.surface) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 16.dp, vertical = 4.dp),
                                                            horizontalArrangement = Arrangement.End,
                                                            verticalAlignment = Alignment.CenterVertically
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
                                                                Row(
                                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    MainAppFlagLanguageSelector(
                                                                        i18n = i18n,
                                                                        activeLocale = activeLocale,
                                                                        manualLocaleOverride = manualLocaleOverride,
                                                                        onSelectLocaleOverride = onSelectLocaleOverride,
                                                                        compact = true
                                                                    )
                                                                    NewItemCategoryDropdownButton(
                                                                        i18n = i18n,
                                                                        compact = true,
                                                                        openMenuTrigger = openNewMenuTrigger,
                                                                        onSelectCategoryToCreate = onNewItemWithCategory
                                                                    )
                                                                    SmoothTonalButton(
                                                                        onClick = onOpenSettings,
                                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                                                    ) {
                                                                        Text("⚙")
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
                                                                    selected = selectedCategory == null && !favoritesOnly && !expiringOnly,
                                                                    onClick = {
                                                                        expiringOnly = false
                                                                        onSelectCategory(null)
                                                                        onSelectItem(null)
                                                                    },
                                                                    label = i18n.t("nav_all_items")
                                                                )
                                                                SmoothFilterPill(
                                                                    selected = favoritesOnly && !expiringOnly,
                                                                    onClick = {
                                                                        expiringOnly = false
                                                                        onSelectFavorites()
                                                                        onSelectItem(null)
                                                                    },
                                                                    label = "★ ${i18n.t("nav_favorites")}"
                                                                )
                                                                SmoothFilterPill(
                                                                    selected = expiringOnly,
                                                                    onClick = {
                                                                        onSelectCategory(null)
                                                                        expiringOnly = true
                                                                        onSelectItem(null)
                                                                    },
                                                                    label = if (readinessAttentionCount > 0) {
                                                                        "⏳ ${i18n.t("nav_expiring")} ($readinessAttentionCount)"
                                                                    } else {
                                                                        "⏳ ${i18n.t("nav_expiring")}"
                                                                    }
                                                                )
                                                                VaultCategory.entries.forEach { cat ->
                                                                    SmoothFilterPill(
                                                                        selected = selectedCategory == cat && !expiringOnly,
                                                                        onClick = {
                                                                            expiringOnly = false
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
                                                                .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            // Flush Bottom-Left Theme Icon + Lock
                                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                SmoothOutlinedIconButton(
                                                                    onClick = cycleTheme,
                                                                    size = 36.dp
                                                                ) {
                                                                    CenteredThemeContrastIcon(themePreference.name)
                                                                }
                                                                SmoothOutlinedIconButton(
                                                                    onClick = onLockVault,
                                                                    size = 36.dp
                                                                ) {
                                                                    CenteredLockOutlineIcon()
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
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            OutlinedTextField(
                                                                value = searchQuery,
                                                                onValueChange = { searchQuery = it },
                                                                placeholder = { Text(i18n.t("search_placeholder"), maxLines = 1) },
                                                                singleLine = true,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            SmoothTonalButton(
                                                                onClick = { showCommandPalette = true },
                                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                                                            ) {
                                                                Text("⌘K")
                                                            }
                                                        }
                                                        if (exampleItemsCount > 0) {
                                                            DeleteExampleDataInlineCard(
                                                                i18n = i18n,
                                                                exampleCount = exampleItemsCount,
                                                                onRequestDelete = { showPurgeConfirmDialog = true }
                                                            )
                                                        }
                                                        if (selectedCategory == null && !favoritesOnly && !expiringOnly && searchQuery.isBlank()) {
                                                            PinnedWalletDeckStrip(
                                                                i18n = i18n,
                                                                items = items,
                                                                selectedItemId = null,
                                                                onSelectItem = { onSelectItem(it) }
                                                            )
                                                        }
                                                        VaultItemListPane(
                                                            i18n = i18n,
                                                            items = filteredItems,
                                                            selectedItem = null,
                                                            expiringOnly = expiringOnly,
                                                            expiryWindowDays = expiryWindowDays,
                                                            referenceEpochMs = referenceEpochMs,
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
                                                        Surface(
                                                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                                            tonalElevation = 2.dp,
                                                            modifier = Modifier.fillMaxSize()
                                                        ) {
                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxSize()
                                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .align(Alignment.CenterHorizontally)
                                                                        .width(38.dp)
                                                                        .height(4.dp)
                                                                        .clip(CircleShape)
                                                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                                                )
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
                                                                    },
                                                                    onPresentPass = { presentingPassItem = it }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (clipboardCountdownSecs != null) {
                                        ClipboardCountdownToastPill(
                                            i18n = i18n,
                                            secondsLeft = clipboardCountdownSecs ?: 0,
                                            onClearNow = scrubClipboardNow,
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showCommandPalette && !isVaultLocked) {
                        CommandPaletteDialog(
                            i18n = i18n,
                            searchEngine = searchEngine,
                            items = items,
                            onSelectAndClose = { target ->
                                onSelectItem(target)
                                showCommandPalette = false
                            },
                            onCopyPrimaryAndClose = { target ->
                                onSelectItem(target)
                                copyItemPrimaryField(target)
                                showCommandPalette = false
                            },
                            onCopySecondaryAndClose = { target ->
                                onSelectItem(target)
                                copyItemSecondaryField(target)
                                showCommandPalette = false
                            },
                            onPresentPassAndClose = { target ->
                                onSelectItem(target)
                                showCommandPalette = false
                                if (target.category != VaultCategory.SECURE_NOTE) {
                                    presentingPassItem = target
                                }
                            },
                            onDismiss = { showCommandPalette = false }
                        )
                    }

                    presentingPassItem?.let { passTarget ->
                        PassPresentationDialog(
                            i18n = i18n,
                            item = passTarget,
                            onDismiss = { presentingPassItem = null }
                        )
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
                            items = items,
                            themePreference = themePreference,
                            useDeviceDynamicColor = useDeviceDynamicColor,
                            materialAccentId = materialAccentId,
                            onSelectMaterialAccent = { nextAccent ->
                                materialAccentId = nextAccent
                                prefs.edit().putString("material_accent_id", nextAccent).apply()
                            },
                            allowScreenshots = allowScreenshots,
                            autoDownloadLogos = autoDownloadLogos,
                            expiryWindowDays = expiryWindowDays,
                            onSelectExpiryWindowDays = { days ->
                                expiryWindowDays = days
                                prefs.edit().putInt("expiry_window_days", days).apply()
                            },
                            autoLockMinutes = autoLockMinutes,
                            onSelectAutoLockMinutes = { mins ->
                                autoLockMinutes = mins
                                prefs.edit().putInt("auto_lock_minutes", mins).apply()
                            },
                            lockOnStandby = lockOnStandby,
                            onToggleLockOnStandby = { enabled ->
                                lockOnStandby = enabled
                                prefs.edit().putBoolean("lock_on_standby", enabled).apply()
                            },
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
                            initialCategory = initialCreateCategory,
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
    val (label, dotColor) = when (snapshot.status) {
        SyncBadgeStatus.LOCAL_ONLY -> Pair(
            i18n.t("sync_pill_local_only"),
            Color(0xFF0A8F6A)
        )
        SyncBadgeStatus.CLOUD_SYNCED -> Pair(
            i18n.t("sync_pill_cloud_synced", mapOf("detail" to "Firebase")),
            Color(0xFF0A8F6A)
        )
        SyncBadgeStatus.SYNCING -> Pair(
            i18n.t("sync_pill_syncing"),
            Color(0xFF2563EB)
        )
        SyncBadgeStatus.CLOUD_OFFLINE_ARMED -> Pair(
            i18n.t("sync_pill_cloud_offline_armed", mapOf("queued" to snapshot.queuedMutationCount)),
            Color(0xFFD97706)
        )
        SyncBadgeStatus.SYNC_ERROR -> Pair(
            i18n.t("sync_pill_error"),
            MaterialTheme.colorScheme.error
        )
    }

    Surface(
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        SmoothTextButton(
            onClick = onClick,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
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
    expiringOnly: Boolean = false,
    readinessAttentionCount: Int = 0,
    onSelectCategory: (VaultCategory?) -> Unit,
    onSelectFavorites: () -> Unit,
    onSelectExpiring: () -> Unit = {}
) {
    SmoothSidebarItem(
        icon = "📥",
        label = i18n.t("nav_all_items"),
        selected = selectedCategory == null && !favoritesOnly && !expiringOnly,
        onClick = { onSelectCategory(null) }
    )
    SmoothSidebarItem(
        icon = "★",
        label = i18n.t("nav_favorites"),
        selected = favoritesOnly && !expiringOnly,
        onClick = onSelectFavorites
    )
    SmoothSidebarItem(
        icon = "⏳",
        label = i18n.t("nav_expiring"),
        selected = expiringOnly,
        onClick = onSelectExpiring,
        badgeCount = readinessAttentionCount.takeIf { it > 0 }
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    VaultCategory.entries.forEach { category ->
        SmoothSidebarItem(
            icon = categoryIcon(category),
            label = categoryLabel(i18n, category),
            selected = selectedCategory == category && !favoritesOnly && !expiringOnly,
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
    i18n.resolveCategorySingularName(category)

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
private fun NewItemCategoryDropdownButton(
    i18n: LocalizationEngine,
    compact: Boolean = false,
    openMenuTrigger: Int = 0,
    onSelectCategoryToCreate: (VaultCategory) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(openMenuTrigger) {
        if (openMenuTrigger > 0) {
            menuExpanded = true
        }
    }

    Box {
        SmoothPrimaryButton(
            onClick = { menuExpanded = true },
            contentPadding = if (compact) {
                PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            } else {
                PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            }
        ) {
            Text(
                text = if (compact) "+ ▾" else "+ ${i18n.t("shortcut_hint_new")} ▾",
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            ProvideSeamFreeHoverIndication {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    VaultCategory.entries.forEach { cat ->
                        SmoothContextMenuItem(
                            text = "${categoryIcon(cat)}  ${categorySingularLabel(i18n, cat)}",
                            onClick = {
                                menuExpanded = false
                                onSelectCategoryToCreate(cat)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndCreateHeader(
    i18n: LocalizationEngine,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenCommandPalette: () -> Unit = {},
    onNewItemWithCategory: (VaultCategory) -> Unit,
    openNewMenuTrigger: Int = 0,
    onOpenSettings: () -> Unit,
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
            trailingIcon = {
                SmoothTextButton(
                    onClick = onOpenCommandPalette,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = i18n.t("cmd_palette_open_btn"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester)
        )
        NewItemCategoryDropdownButton(
            i18n = i18n,
            compact = false,
            openMenuTrigger = openNewMenuTrigger,
            onSelectCategoryToCreate = onNewItemWithCategory
        )
        SmoothTonalButton(
            onClick = onOpenSettings,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("⚙")
        }
    }
}

@Composable
private fun VaultItemListPane(
    i18n: LocalizationEngine,
    items: List<VaultItemPayload>,
    selectedItem: VaultItemPayload?,
    expiringOnly: Boolean = false,
    expiryWindowDays: Int = 90,
    referenceEpochMs: Long = System.currentTimeMillis(),
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

    val readinessMap = remember(items, expiryWindowDays, referenceEpochMs) {
        items.associate { it.id to MultilingualSearchEngine.computeDocumentReadiness(it, referenceEpochMs, expiryWindowDays) }
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

        if (expiringOnly) {
            Text(
                text = "🛂 ${i18n.t("readiness_radar_title")}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            val buckets = listOf(
                Triple(ReadinessTimelineBucket.EXPIRED, i18n.t("readiness_group_expired"), Color(0xFFDC2626)),
                Triple(ReadinessTimelineBucket.URGENT, i18n.t("readiness_group_urgent"), Color(0xFFD97706)),
                Triple(
                    ReadinessTimelineBucket.UPCOMING,
                    i18n.t("readiness_group_upcoming", mapOf("days" to expiryWindowDays)),
                    MaterialTheme.colorScheme.primary
                )
            )
            buckets.forEach { (targetBucket, headerLabel, tint) ->
                val bucketItems = items.filter { item ->
                    val r = readinessMap[item.id]
                    if (targetBucket == ReadinessTimelineBucket.UPCOMING) {
                        r?.timelineBucket == ReadinessTimelineBucket.UPCOMING ||
                            (r?.timelineBucket == ReadinessTimelineBucket.HEALTHY && r.missingBackupScan)
                    } else {
                        r?.timelineBucket == targetBucket
                    }
                }
                if (bucketItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = tint
                        )
                        Text(
                            text = bucketItems.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = tint
                        )
                    }
                    bucketItems.forEach { item ->
                        key(item.id) {
                            SwipeAndContextVaultItemRow(
                                i18n = i18n,
                                item = item,
                                readiness = readinessMap[item.id],
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
                }
            }
        } else {
            items.forEach { item ->
                key(item.id) {
                    SwipeAndContextVaultItemRow(
                        i18n = i18n,
                        item = item,
                        readiness = readinessMap[item.id],
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
        }
        Spacer(Modifier.height(44.dp))
    }
}

/**
 * Vault entry row supporting:
 * 1. Horizontal Swipe Right -> Quick Copy Primary / Pin action tray (full swipe triggers instant Quick Copy)
 * 2. Horizontal Swipe Left -> Reveals `Edit`, `Copy Primary`, `Delete` action tray
 * 3. Hardware Right-Click (`PointerButton.Secondary`) & Touch Long-Press Context Menu
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeAndContextVaultItemRow(
    i18n: LocalizationEngine,
    item: VaultItemPayload,
    readiness: DocumentReadiness? = null,
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
    val actionTrayWidthPx = with(density) { 216.dp.toPx() }
    val rightTrayWidthPx = with(density) { 156.dp.toPx() }
    val quickCopyTriggerPx = with(density) { 118.dp.toPx() }

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
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmoothPrimaryButton(
                    onClick = {
                        dragOffsetPx = 0f
                        onCopyPrimary()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text("⎘ ${i18n.t("swipe_quick_copy_hint")}", style = MaterialTheme.typography.labelSmall)
                }
                SmoothOutlinedButton(
                    onClick = {
                        dragOffsetPx = 0f
                        onTogglePin()
                    },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    Text("📌", style = MaterialTheme.typography.labelSmall)
                }
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
                Button(
                    onClick = {
                        dragOffsetPx = 0f
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "🗑 ${i18n.t("action_delete")}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
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
                                onSelect()
                                if (pos != null) {
                                    contextMenuOffset = with(density) {
                                        DpOffset(
                                            x = pos.x.toDp().coerceAtLeast(8.dp),
                                            y = (pos.y - size.height).toDp()
                                        )
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
                                .coerceIn(-actionTrayWidthPx, rightTrayWidthPx)
                        },
                        onDragEnd = {
                            when {
                                dragOffsetPx >= quickCopyTriggerPx -> {
                                    dragOffsetPx = 0f
                                    if (item.primaryCopyableField() != null) {
                                        onCopyPrimary()
                                    } else {
                                        onTogglePin()
                                    }
                                }
                                dragOffsetPx >= rightTrayWidthPx * 0.42f -> {
                                    dragOffsetPx = rightTrayWidthPx
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
                        onSelect()
                        contextMenuOffset = DpOffset(16.dp, (-44).dp)
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
                    val subtitle = i18n.resolveDisplaySubtitle(item.subtitle, item.category)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (readiness != null && (readiness.travelSixMonthWarning || readiness.missingBackupScan)) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (readiness.travelSixMonthWarning) {
                                Surface(
                                    color = Color(0xFFFEF3C7),
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = "🛂 ${i18n.t("readiness_passport_6m_badge")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF92400E),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                            if (readiness.missingBackupScan) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = i18n.t("readiness_missing_scan_badge"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
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

        // Right-Click / Long-Press Context Menu (anchored directly on top of the selected item)
        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false },
            offset = contextMenuOffset
        ) {
            ProvideSeamFreeHoverIndication {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    if (item.primaryCopyableField() != null) {
                        SmoothContextMenuItem(
                            text = "⎘  ${i18n.t("context_copy_primary")}",
                            onClick = {
                                contextMenuExpanded = false
                                onCopyPrimary()
                            }
                        )
                    }
                    SmoothContextMenuItem(
                        text = "✎  ${i18n.t("action_edit")}",
                        onClick = {
                            contextMenuExpanded = false
                            onEdit()
                        }
                    )
                    SmoothContextMenuItem(
                        text = if (item.pinned) "📌  ${i18n.t("context_unpin")}"
                        else "📌  ${i18n.t("context_pin")}",
                        onClick = {
                            contextMenuExpanded = false
                            onTogglePin()
                        }
                    )
                    SmoothContextMenuItem(
                        text = if (item.favorite) "★  ${i18n.t("context_unfavorite")}"
                        else "☆  ${i18n.t("context_favorite")}",
                        onClick = {
                            contextMenuExpanded = false
                            onToggleFavorite()
                        }
                    )
                    SmoothContextMenuItem(
                        text = "⧉  ${i18n.t("action_duplicate")}",
                        onClick = {
                            contextMenuExpanded = false
                            onDuplicate()
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                    SmoothContextMenuItem(
                        text = "🗑  ${i18n.t("action_delete")}",
                        isDestructive = true,
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
    onDelete: (VaultItemPayload) -> Unit,
    onPresentPass: ((VaultItemPayload) -> Unit)? = null
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

        // Dual-Logo & Institution Brand Color Visual Card Hero (3D Front/Back Flippable)
        if (selectedItem.category != VaultCategory.SECURE_NOTE && selectedItem.cardTheme?.showVisualCard != false) {
            DualBrandVisualCardHero(
                item = selectedItem,
                categoryLabelText = categorySingularLabel(i18n, selectedItem.category),
                displaySubtitleText = i18n.resolveDisplaySubtitle(selectedItem.subtitle, selectedItem.category),
                flipLabelFront = i18n.t("card_flip_to_front"),
                flipLabelBack = i18n.t("card_flip_to_back"),
                reverseBadgeLabel = i18n.t("card_back_badge"),
                reverseEmptyHint = i18n.t("card_back_empty_hint"),
                cvvLabelText = i18n.t("field_cvv"),
                onEditClick = { onEdit(selectedItem) }
            )
        } else {
            Text(selectedItem.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (selectedItem.subtitle.isNotBlank()) {
                Text(
                    i18n.resolveDisplaySubtitle(selectedItem.subtitle, selectedItem.category),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (selectedItem.category == VaultCategory.BANK_ACCOUNT) {
            SepaQrSectionCard(i18n = i18n, item = selectedItem)
        }

        // Complete Item Action Bar: Edit, Present, Duplicate, Pin, Favorite, Delete
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmoothPrimaryButton(onClick = { onEdit(selectedItem) }) {
                Text("✎ ${i18n.t("action_edit")}")
            }
            if (onPresentPass != null && selectedItem.category != VaultCategory.SECURE_NOTE) {
                SmoothTonalButton(onClick = { onPresentPass(selectedItem) }) {
                    Text(i18n.t("action_present_card"))
                }
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
    val category = existingItem?.category ?: initialCategory
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
    val primarySpecs = remember(templateSpecs) { templateSpecs.take(6) }
    val secondarySpecs = remember(templateSpecs) { templateSpecs.drop(6) }
    var moreFieldsExpanded by remember(existingItem?.id, category) {
        mutableStateOf(
            secondarySpecs.any { spec -> !standardFieldValues[spec.key].isNullOrBlank() }
        )
    }

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
        val cleanedSubtitle = subtitle.trim()
            .takeUnless { raw -> VaultCategory.entries.any { it.name.equals(raw, ignoreCase = true) } }
            .orEmpty()
        val rawItem = VaultItemPayload(
            id = existingItem?.id ?: "item-$now",
            category = category,
            title = title.trim().ifBlank { categorySingularLabel(i18n, category) },
            subtitle = cleanedSubtitle,
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
    val maxModalHeightDp = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(320).dp
    val headerTitle = if (existingItem == null) {
        "${categoryIcon(category)} ${i18n.t("action_new_item")} — ${categorySingularLabel(i18n, category)}"
    } else {
        "${categoryIcon(category)} ${i18n.t("action_edit")} — ${existingItem.title}"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 740.dp)
                    .heightIn(max = maxModalHeightDp)
            ) {
                ProvideSeamFreeHoverIndication {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = headerTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            SmoothTextButton(onClick = onDismiss) {
                                Text("✕")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Live Dual-Logo Card Preview
                            if (category != VaultCategory.SECURE_NOTE) {
                                Text(
                                    text = i18n.t("editor_live_preview_label").uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                DualBrandVisualCardHero(
                                    item = livePreviewItem,
                                    categoryLabelText = categorySingularLabel(i18n, category),
                                    displaySubtitleText = i18n.resolveDisplaySubtitle(livePreviewItem.subtitle, category),
                                    isCompactPreview = true
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

                            // Intelligent Category-Specific Structured Fields (Essential + Progressive Disclosure)
                            HorizontalDivider()
                            Text(
                                text = i18n.t("editor_essential_fields_title"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val applyCrossFieldAutofill: (Map<String, String>) -> List<String> = { suggestions ->
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
                            }
                            primarySpecs.forEach { spec ->
                                val curVal = standardFieldValues[spec.key].orEmpty()
                                SmartStructuredFieldInput(
                                    spec = spec,
                                    category = category,
                                    i18n = i18n,
                                    activeLocaleCode = activeLocale.code,
                                    value = curVal,
                                    onValueChange = { standardFieldValues[spec.key] = it },
                                    onCrossFieldAutofill = applyCrossFieldAutofill,
                                    onVerifyWebsite = onVerifyWebsiteReachability
                                )
                            }
                            if (secondarySpecs.isNotEmpty()) {
                                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { moreFieldsExpanded = !moreFieldsExpanded }
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${i18n.t("editor_more_fields_toggle")} (${secondarySpecs.size})",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = if (moreFieldsExpanded) "▾" else "▸",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (moreFieldsExpanded) {
                                            HorizontalDivider()
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                secondarySpecs.forEach { spec ->
                                                    val curVal = standardFieldValues[spec.key].orEmpty()
                                                    SmartStructuredFieldInput(
                                                        spec = spec,
                                                        category = category,
                                                        i18n = i18n,
                                                        activeLocaleCode = activeLocale.code,
                                                        value = curVal,
                                                        onValueChange = { standardFieldValues[spec.key] = it },
                                                        onCrossFieldAutofill = applyCrossFieldAutofill,
                                                        onVerifyWebsite = onVerifyWebsiteReachability
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
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
                                                label = { Text(i18n.t("editor_field_value_placeholder")) },
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

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmoothTextButton(onClick = onDismiss) {
                                Text(i18n.t("action_cancel"))
                            }
                            SmoothPrimaryButton(
                                enabled = title.isNotBlank() || standardFieldValues.values.any { it.isNotBlank() },
                                onClick = { onSave(buildPreviewPayload()) }
                            ) {
                                Text(i18n.t("action_save"))
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// COMPREHENSIVE v0.3 SETTINGS DIALOG:
// EXCLUSIVE STORAGE MODE + AUTOMATED UPDATES + PORTABILITY + DIAGNOSTICS LOG
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SafePocketSettingsDialog(
    i18n: LocalizationEngine,
    items: List<VaultItemPayload> = emptyList(),
    themePreference: ThemePreference,
    useDeviceDynamicColor: Boolean,
    materialAccentId: String = "emerald",
    onSelectMaterialAccent: (String) -> Unit = {},
    allowScreenshots: Boolean,
    autoDownloadLogos: Boolean,
    expiryWindowDays: Int = 90,
    onSelectExpiryWindowDays: (Int) -> Unit = {},
    autoLockMinutes: Int = 5,
    onSelectAutoLockMinutes: (Int) -> Unit = {},
    lockOnStandby: Boolean = true,
    onToggleLockOnStandby: (Boolean) -> Unit = {},
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
    val settingsPrefs = remember(context) {
        context.getSharedPreferences("safepocket_android_prefs", Context.MODE_PRIVATE)
    }

    val currentStorageMode = cloudSyncStatus?.storageMode ?: VaultStorageMode.LOCAL_ONLY
    var desiredModeTab by remember(currentStorageMode) { mutableStateOf(currentStorageMode) }
    var activeSettingsTab by remember { mutableStateOf("appearance") }

    var statusFeedback by remember { mutableStateOf<String?>(null) }
    var busyOperation by remember { mutableStateOf(false) }

    // Master Password + Secret Key + Hint inputs
    var newMasterPassword by remember { mutableStateOf("") }
    var pairSecretKeyInput by remember { mutableStateOf("") }
    var masterPasswordHint by remember {
        mutableStateOf(settingsPrefs.getString("master_pw_hint", "") ?: "")
    }

    // Cloud Provider + Firebase / Google Drive + Endpoint Config inputs
    var selectedCloudProvider by remember {
        mutableStateOf(settingsPrefs.getString("cloud_provider_id", "FIREBASE") ?: "FIREBASE")
    }
    var cloudEmail by remember { mutableStateOf(cloudSyncStatus?.signedInEmail.orEmpty()) }
    var cloudPassword by remember { mutableStateOf("") }
    var showCloudEndpointConfig by remember { mutableStateOf(false) }
    var firebaseProjectId by remember {
        mutableStateOf(settingsPrefs.getString("cfg_fb_project_id", cloudSyncStatus?.projectId ?: "safepocket-vault-eu") ?: "safepocket-vault-eu")
    }
    var firebaseApiKey by remember {
        mutableStateOf(settingsPrefs.getString("cfg_fb_api_key", "AIzaSyBCeyZx121Eq5xxyWZD4k3w1-9vyr0yIsw") ?: "")
    }
    var firebaseBucket by remember {
        mutableStateOf(settingsPrefs.getString("cfg_fb_bucket", "safepocket-vault-eu.firebasestorage.app") ?: "safepocket-vault-eu.firebasestorage.app")
    }
    var gdriveBearerToken by remember {
        mutableStateOf(settingsPrefs.getString("cfg_gdrive_token", "") ?: "")
    }

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

    val maxModalHeightDp = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(320).dp
    val hasSavedCloudSession = (selectedCloudProvider == "GDRIVE_APPDATA" && gdriveBearerToken.isNotBlank()) ||
        !cloudSyncStatus?.signedInEmail.isNullOrBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .heightIn(max = maxModalHeightDp)
            ) {
                ProvideSeamFreeHoverIndication {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = i18n.t("nav_settings"),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            SmoothTextButton(onClick = onDismiss) {
                                Text("✕")
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "appearance" to i18n.t("settings_tab_appearance"),
                                "security" to i18n.t("settings_tab_security"),
                                "sync" to i18n.t("settings_tab_sync"),
                                "data" to i18n.t("settings_tab_data"),
                                "updates" to i18n.t("settings_tab_updates")
                            ).forEach { (tabId, tabTitle) ->
                                SmoothFilterPill(
                                    selected = activeSettingsTab == tabId,
                                    onClick = { activeSettingsTab = tabId },
                                    label = tabTitle
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 16.dp),
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
                            // 1. DATABASE & SYNC (`Lokale DB` vs `Cloud-Sync` 2-Column Cards)
                            // -----------------------------------------------------------------
                            if (activeSettingsTab == "sync") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        DatabaseSyncSectionIcon(color = MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            text = i18n.t("storage_mode_section_title"),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        StorageModeSelectionCard(
                                            selected = desiredModeTab == VaultStorageMode.LOCAL_ONLY,
                                            title = i18n.t("storage_mode_local_only_title"),
                                            subtitle = i18n.t("storage_mode_local_only_desc"),
                                            enabled = !busyOperation,
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
                                            modifier = Modifier.weight(1f),
                                            icon = { tint -> CloudOffCardIcon(color = tint) }
                                        )
                                        StorageModeSelectionCard(
                                            selected = desiredModeTab == VaultStorageMode.CLOUD_SYNC,
                                            title = i18n.t("storage_mode_cloud_only_title"),
                                            subtitle = i18n.t("storage_mode_cloud_only_desc"),
                                            enabled = !busyOperation,
                                            onClick = {
                                                desiredModeTab = VaultStorageMode.CLOUD_SYNC
                                                if (hasSavedCloudSession && currentStorageMode != VaultStorageMode.CLOUD_SYNC) {
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
                                            modifier = Modifier.weight(1f),
                                            icon = { tint -> CloudDoneCardIcon(color = tint) }
                                        )
                                    }

                                    if (desiredModeTab == VaultStorageMode.LOCAL_ONLY) {
                                        if (localDbStatus != null) {
                                            Text(
                                                text = "${localDbStatus.backendLabel} • ${localDbStatus.databaseFileName} (${localDbStatus.databaseSizeBytes / 1024} KiB) • ${localDbStatus.activeItemCount} items",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        // Cloud Service Radio Selection (Firebase vs Google Drive appDataFolder)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        "FIREBASE" to i18n.t("sync_provider_firebase"),
                                        "GDRIVE_APPDATA" to i18n.t("sync_provider_gdrive")
                                    ).forEach { (pid, label) ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    selectedCloudProvider = pid
                                                    settingsPrefs.edit().putString("cloud_provider_id", pid).apply()
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            RadioButton(selected = selectedCloudProvider == pid, onClick = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text(label, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }

                                if (selectedCloudProvider == "GDRIVE_APPDATA") {
                                    OutlinedTextField(
                                        value = gdriveBearerToken,
                                        onValueChange = {
                                            gdriveBearerToken = it
                                            settingsPrefs.edit().putString("cfg_gdrive_token", it).apply()
                                        },
                                        label = { Text(i18n.t("cloud_bearer_token")) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    SmoothPrimaryButton(
                                        enabled = !busyOperation && gdriveBearerToken.isNotBlank(),
                                        onClick = {
                                            coroutineScope.launch {
                                                busyOperation = true
                                                runCatching {
                                                    if (currentStorageMode != VaultStorageMode.CLOUD_SYNC) {
                                                        onSwitchStorageMode(VaultStorageMode.CLOUD_SYNC)
                                                    }
                                                    val rep = onSyncNow()
                                                    statusFeedback = if (rep != null) {
                                                        "✓ Synced via Google Drive (↑${rep.pushedCount} • ↓${rep.pulledCount})"
                                                    } else {
                                                        "✓ ${i18n.t("storage_mode_cloud_only_title")}"
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
                                } else if (!cloudSyncStatus?.signedInEmail.isNullOrBlank()) {
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
                                            enabled = !busyOperation,
                                            onClick = {
                                                if (!cloudEmail.trim().contains("@") || cloudPassword.length < 6) {
                                                    statusFeedback = "⚠ ${i18n.t("cloud_auth_email")} & ${i18n.t("cloud_auth_password")} (min. 6 chars) required"
                                                    return@SmoothPrimaryButton
                                                }
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
                                            enabled = !busyOperation,
                                            onClick = {
                                                if (!cloudEmail.trim().contains("@") || cloudPassword.length < 6) {
                                                    statusFeedback = "⚠ ${i18n.t("cloud_auth_email")} & ${i18n.t("cloud_auth_password")} (min. 6 chars) required"
                                                    return@SmoothTonalButton
                                                }
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

                                // Collapsible Cloud Endpoint Configuration (Parity with macOS & ChromeOS PWA)
                                SmoothTextButton(
                                    onClick = { showCloudEndpointConfig = !showCloudEndpointConfig }
                                ) {
                                    Text("${if (showCloudEndpointConfig) "▾" else "▸"} ${i18n.t("cloud_config_title")}")
                                }
                                if (showCloudEndpointConfig) {
                                    OutlinedTextField(
                                        value = firebaseProjectId,
                                        onValueChange = {
                                            firebaseProjectId = it
                                            settingsPrefs.edit().putString("cfg_fb_project_id", it).apply()
                                        },
                                        label = { Text(i18n.t("cloud_firebase_project_id")) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = firebaseApiKey,
                                        onValueChange = {
                                            firebaseApiKey = it
                                            settingsPrefs.edit().putString("cfg_fb_api_key", it).apply()
                                        },
                                        label = { Text(i18n.t("cloud_firebase_api_key")) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = firebaseBucket,
                                        onValueChange = {
                                            firebaseBucket = it
                                            settingsPrefs.edit().putString("cfg_fb_bucket", it).apply()
                                        },
                                        label = { Text(i18n.t("cloud_firebase_bucket")) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
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
                    if (activeSettingsTab == "security") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                            OutlinedTextField(
                                value = masterPasswordHint,
                                onValueChange = {
                                    masterPasswordHint = it
                                    settingsPrefs.edit().putString("master_pw_hint", it).apply()
                                },
                                label = { Text(i18n.t("emergency_kit_hint_placeholder")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SmoothPrimaryButton(
                                    enabled = !busyOperation,
                                    onClick = {
                                        if (newMasterPassword.trim().length < 6) {
                                            statusFeedback = "⚠ ${i18n.t("security_master_password")} (min. 6 chars) required"
                                            return@SmoothPrimaryButton
                                        }
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
                    if (activeSettingsTab == "updates") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                    if (activeSettingsTab == "data") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                                    enabled = !busyOperation,
                                    onClick = {
                                        if (backupPassword.length < 4) {
                                            statusFeedback = "⚠ Backup password (min. 4 chars) required"
                                            return@SmoothPrimaryButton
                                        }
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
                                    enabled = !busyOperation,
                                    onClick = {
                                        if (backupPassword.isBlank()) {
                                            statusFeedback = "⚠ Enter backup password above before restoring"
                                            return@SmoothTonalButton
                                        }
                                        restoreReplaceExisting = false
                                        openRestoreContainerLauncher.launch(arrayOf("*/*"))
                                    }
                                ) {
                                    Text("📥 ${i18n.t("restore_mode_merge")}")
                                }
                                SmoothOutlinedButton(
                                    enabled = !busyOperation,
                                    onClick = {
                                        if (backupPassword.isBlank()) {
                                            statusFeedback = "⚠ Enter backup password above before restoring"
                                            return@SmoothOutlinedButton
                                        }
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
                                SmoothOutlinedButton(
                                    enabled = !busyOperation,
                                    onClick = {
                                        coroutineScope.launch {
                                            if (!onVerifyMasterPassword(exportConfirmMasterPw)) {
                                                statusFeedback = "⚠ Invalid Master Password"
                                                return@launch
                                            }
                                            val prettyJson = Json { prettyPrint = true }.encodeToString(items)
                                            pendingSaveBytes = prettyJson.encodeToByteArray()
                                            saveDocumentLauncher.launch("safepocket-vault.json")
                                        }
                                    }
                                ) {
                                    Text(i18n.t("export_json_btn"))
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // 5. SECURITY, EXPIRY WINDOW, AUTO-LOCK & BRAND LOGO DOWNLOAD
                    // -----------------------------------------------------------------
                    if (activeSettingsTab == "security") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                            HorizontalDivider()
                            Text(
                                text = i18n.t("expiry_window_setting_title"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(30, 60, 90, 180).forEach { days ->
                                    SmoothFilterPill(
                                        selected = expiryWindowDays == days,
                                        onClick = { onSelectExpiryWindowDays(days) },
                                        label = i18n.t("expiry_window_days_option", mapOf("days" to days))
                                    )
                                }
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = i18n.t("security_lock_on_standby_toggle"),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = i18n.t("security_lock_on_standby_desc"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = lockOnStandby,
                                    onCheckedChange = onToggleLockOnStandby
                                )
                            }
                            HorizontalDivider()
                            Text(
                                text = i18n.t("security_auto_lock"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    1 to i18n.t("security_auto_lock_1m"),
                                    5 to i18n.t("security_auto_lock_5m"),
                                    15 to i18n.t("security_auto_lock_15m"),
                                    0 to i18n.t("security_auto_lock_never")
                                ).forEach { (mins, label) ->
                                    SmoothFilterPill(
                                        selected = autoLockMinutes == mins,
                                        onClick = { onSelectAutoLockMinutes(mins) },
                                        label = label
                                    )
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // 6. APPEARANCE, ACCENT COLOR & LANGUAGE (SEAM-FREE HOVER PILLS)
                    // -----------------------------------------------------------------
                    if (activeSettingsTab == "appearance") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "emerald" to i18n.t("material_color_emerald"),
                                    "ocean" to i18n.t("material_color_ocean"),
                                    "terracotta" to i18n.t("material_color_terracotta"),
                                    "slate" to i18n.t("material_color_slate")
                                ).forEach { (accentId, label) ->
                                    SmoothFilterPill(
                                        selected = !useDeviceDynamicColor && materialAccentId == accentId,
                                        onClick = {
                                            if (useDeviceDynamicColor) onToggleDeviceDynamicColor(false)
                                            onSelectMaterialAccent(accentId)
                                        },
                                        label = label
                                    )
                                }
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
                                    label = "🌐 " + i18n.t(
                                        "settings_language_system",
                                        mapOf("locale" to "${i18n.detectedSystemLocale.flagEmoji} ${i18n.detectedSystemLocale.nativeDisplayName}")
                                    )
                                )
                                SupportedLocale.entries.forEach { locale ->
                                    SmoothFilterPill(
                                        selected = manualLocaleOverride == locale,
                                        onClick = { onSelectLocaleOverride(locale) },
                                        label = "${locale.flagEmoji} ${locale.nativeDisplayName}"
                                    )
                                }
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // 7. PRIVACY-SCRUBBED DIAGNOSTICS & ERROR LOG
                    // -----------------------------------------------------------------
                    if (activeSettingsTab == "updates") OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        SmoothTextButton(
                                            onClick = {
                                                val report = diagnosticEvents.joinToString("\n") { ev ->
                                                    "[${ev.code}] ${ev.operation}: ${ev.scrubbedDetail}"
                                                }
                                                onCopyToClipboard("Diagnostics", report, false)
                                                statusFeedback = "✓ ${i18n.t("action_copied")}"
                                            }
                                        ) {
                                            Text(i18n.t("action_copy"))
                                        }
                                        SmoothTextButton(onClick = onClearDiagnosticsLog) {
                                            Text(i18n.t("err_clear_log"))
                                        }
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmoothPrimaryButton(onClick = onDismiss) {
                        Text(i18n.t("action_done"))
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun StorageModeSelectionCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    val primary = MaterialTheme.colorScheme.primary

    val bg by animateColorAsState(
        targetValue = when {
            selected && isPressed -> primary.copy(alpha = 0.18f)
            selected && isHovered -> primary.copy(alpha = 0.14f)
            selected -> primary.copy(alpha = 0.11f)
            isPressed -> primary.copy(alpha = 0.10f)
            isHovered -> primary.copy(alpha = 0.05f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "storageCardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> primary
            isHovered -> primary.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "storageCardBorder"
    )
    val titleColor = if (selected) primary else MaterialTheme.colorScheme.onSurface
    val iconColor = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = shape)
            .hoverable(interactionSource, enabled = enabled)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon(iconColor)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MainAppFlagLanguageSelector(
    i18n: LocalizationEngine,
    activeLocale: SupportedLocale,
    manualLocaleOverride: SupportedLocale?,
    onSelectLocaleOverride: (SupportedLocale?) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val badgeFlag = if (manualLocaleOverride == null) {
        "🌐 ${activeLocale.flagEmoji}"
    } else {
        activeLocale.flagEmoji
    }
    val targetHeight = if (compact) 34.dp else 36.dp

    Box {
        SmoothOutlinedButton(
            onClick = { expanded = true },
            modifier = modifier.height(targetHeight),
            shape = RoundedCornerShape(10.dp),
            minHeight = targetHeight,
            contentPadding = PaddingValues(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = 0.dp
            )
        ) {
            Text(
                text = if (compact) {
                    activeLocale.flagEmoji
                } else {
                    "$badgeFlag ${activeLocale.code.uppercase()}"
                },
                style = MaterialTheme.typography.labelMedium.withCenteredMetrics(),
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ProvideSeamFreeHoverIndication {
                Column {
                    val sysCheck = if (manualLocaleOverride == null) "✓ " else ""
                    SmoothContextMenuItem(
                        text = "${sysCheck}🌐 " + i18n.t(
                            "settings_language_system",
                            mapOf("locale" to "${i18n.detectedSystemLocale.flagEmoji} ${i18n.detectedSystemLocale.nativeDisplayName}")
                        ),
                        accentHighlight = manualLocaleOverride == null,
                        onClick = {
                            expanded = false
                            onSelectLocaleOverride(null)
                        }
                    )
                    HorizontalDivider()
                    SupportedLocale.entries.forEach { locale ->
                        val check = if (manualLocaleOverride == locale) "✓ " else ""
                        SmoothContextMenuItem(
                            text = "$check${locale.flagEmoji}  ${locale.nativeDisplayName} (${locale.code.uppercase()})",
                            accentHighlight = manualLocaleOverride == locale,
                            onClick = {
                                expanded = false
                                onSelectLocaleOverride(locale)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedVaultView(
    i18n: LocalizationEngine,
    activeLocale: SupportedLocale,
    manualLocaleOverride: SupportedLocale?,
    onSelectLocaleOverride: (SupportedLocale?) -> Unit,
    requiresPassword: Boolean,
    onUnlockWithPassword: suspend (String) -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var unlockError by remember { mutableStateOf(false) }
    val shakeOffsetPx = remember { Animatable(0f) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .offset { IntOffset(shakeOffsetPx.value.roundToInt(), 0) }
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    MainAppFlagLanguageSelector(
                        i18n = i18n,
                        activeLocale = activeLocale,
                        manualLocaleOverride = manualLocaleOverride,
                        onSelectLocaleOverride = onSelectLocaleOverride
                    )
                }
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
                    if (unlockError) {
                        Text(
                            text = i18n.t("security_invalid_master_password"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SmoothPrimaryButton(
                    onClick = {
                        coroutineScope.launch {
                            val ok = onUnlockWithPassword(password)
                            if (!ok) {
                                unlockError = true
                                shakeOffsetPx.animateTo(-18f, tween(55))
                                shakeOffsetPx.animateTo(16f, tween(55))
                                shakeOffsetPx.animateTo(-10f, tween(55))
                                shakeOffsetPx.animateTo(0f, tween(70))
                            } else {
                                password = ""
                            }
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

// ============================================================================
// PHASE 2: PINNED WALLET DECK, CLIPBOARD COUNTDOWN PILL, SEPA QR & PASS MODE
// ============================================================================

@Composable
private fun PinnedWalletDeckStrip(
    i18n: LocalizationEngine,
    items: List<VaultItemPayload>,
    selectedItemId: String?,
    onSelectItem: (VaultItemPayload) -> Unit
) {
    val pinnedItems = remember(items) { items.filter { it.pinned } }
    if (pinnedItems.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📌 ${i18n.t("wallet_deck_pinned_title").uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = pinnedItems.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            pinnedItems.take(8).forEach { item ->
                val resolved = remember(item) { InstitutionBrandRegistry.resolveForItem(item) }
                val (gradStart, gradEnd) = remember(item.cardTheme, resolved) {
                    resolveCardGradientColors(item, resolved)
                }
                val compactPrimary = remember(item) {
                    item.primaryCopyableField()?.value?.replace(Regex("[\\s-]+"), "").orEmpty()
                }
                val tailBadge = if (compactPrimary.length >= 4) {
                    "•• ${compactPrimary.takeLast(4)}"
                } else {
                    resolved.networkDisplayLabel
                }
                val isSelected = selectedItemId == item.id

                Column(
                    modifier = Modifier
                        .width(146.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(gradStart, gradEnd),
                                start = Offset(0f, 0f),
                                end = Offset(320f, 180f)
                            )
                        )
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectItem(item) }
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tailBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1
                        )
                        Text(
                            text = resolved.networkDisplayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardCountdownToastPill(
    i18n: LocalizationEngine,
    secondsLeft: Int,
    onClearNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val sweep = (secondsLeft.coerceIn(0, 30) / 30f) * 360f

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 2.6.dp.toPx()
                    val inset = strokePx / 2f
                    val arcSize = Size(size.width - strokePx, size.height - strokePx)
                    drawArc(
                        color = track,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx)
                    )
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
                Text(
                    text = secondsLeft.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }

            Text(
                text = i18n.t("clipboard_hygiene_active", mapOf("seconds" to secondsLeft)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            SmoothOutlinedButton(
                onClick = onClearNow,
                shape = CircleShape,
                minHeight = 28.dp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    text = i18n.t("action_clear_now"),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun OfflineQrCodeCanvas(
    payload: String,
    sizeDp: androidx.compose.ui.unit.Dp = 156.dp
) {
    val matrix = remember(payload) { EpcSepaAndQrEngine.encodeQrMatrix(payload) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Canvas(modifier = Modifier.size(sizeDp)) {
            val quiet = 2
            val total = matrix.size + quiet * 2
            val cell = size.minDimension / total.toFloat()
            for (r in matrix.indices) {
                for (c in matrix[r].indices) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = Color(0xFF0F172A),
                            topLeft = Offset((c + quiet) * cell, (r + quiet) * cell),
                            size = Size(cell + 0.4f, cell + 0.4f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineCode128Canvas(
    payload: String,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(82.dp)
) {
    val modules = remember(payload) { EpcSepaAndQrEngine.encodeCode128BModules(payload) }
    Canvas(modifier = modifier) {
        if (modules.isEmpty()) return@Canvas
        val barWidth = size.width / modules.size.toFloat()
        for (i in modules.indices) {
            if (modules[i]) {
                drawRect(
                    color = Color(0xFF0F172A),
                    topLeft = Offset(i * barWidth, 0f),
                    size = Size(barWidth + 0.35f, size.height)
                )
            }
        }
    }
}

@Composable
private fun SepaQrSectionCard(
    i18n: LocalizationEngine,
    item: VaultItemPayload
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    var amountInput by remember(item.id) { mutableStateOf("") }
    var purposeInput by remember(item.id) { mutableStateOf("") }

    val iban = remember(item) { item.fieldValue(CanonicalFieldKeys.IBAN) }
    val holder = remember(item) {
        item.fieldValue(CanonicalFieldKeys.ACCOUNT_HOLDER).ifBlank { item.title }
    }
    val bic = remember(item) { item.fieldValue(CanonicalFieldKeys.BIC) }

    val epcPayload = remember(holder, iban, bic, amountInput, purposeInput) {
        EpcSepaAndQrEngine.buildEpcSepaQrPayload(
            holderName = holder,
            iban = iban,
            bic = bic,
            amountEur = amountInput,
            remittanceText = purposeInput
        )
    } ?: return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = i18n.t("sepa_qr_title"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = i18n.t("sepa_qr_subtitle"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                SmoothPrimaryButton(onClick = { expanded = !expanded }) {
                    Text(i18n.t(if (expanded) "sepa_qr_hide_btn" else "sepa_qr_show_btn"))
                }
            }

            if (expanded) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OfflineQrCodeCanvas(payload = epcPayload, sizeDp = 144.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = holder,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (bic.isNotBlank()) "$iban • $bic" else iban,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = amountInput,
                            onValueChange = { amountInput = it },
                            label = { Text(i18n.t("sepa_qr_amount_label")) },
                            placeholder = { Text("25.00") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = purposeInput,
                            onValueChange = { purposeInput = it.take(40) },
                            label = { Text(i18n.t("sepa_qr_purpose_label")) },
                            placeholder = { Text("Dinner / Invoice") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassPresentationDialog(
    i18n: LocalizationEngine,
    item: VaultItemPayload,
    onDismiss: () -> Unit
) {
    var activeTab by remember(item.id) { mutableStateOf("digital") }
    var useQrMode by remember(item.id) {
        mutableStateOf(item.cardTheme?.barcodeFormat == BarcodeFormat.QR_CODE)
    }

    val iban = remember(item) { item.fieldValue(CanonicalFieldKeys.IBAN) }
    val holder = remember(item) {
        item.fieldValue(CanonicalFieldKeys.ACCOUNT_HOLDER).ifBlank { item.title }
    }
    val bic = remember(item) { item.fieldValue(CanonicalFieldKeys.BIC) }
    val epcBankPayload = remember(item, holder, iban, bic) {
        if (item.category == VaultCategory.BANK_ACCOUNT) {
            EpcSepaAndQrEngine.buildEpcSepaQrPayload(holderName = holder, iban = iban, bic = bic)
        } else null
    }
    val readableValue = remember(item, iban) {
        listOf(
            item.fieldValue(CanonicalFieldKeys.BARCODE_VALUE),
            item.fieldValue(CanonicalFieldKeys.MEMBERSHIP_ID),
            item.fieldValue(CanonicalFieldKeys.INSURANCE_NUMBER),
            item.fieldValue(CanonicalFieldKeys.DOCUMENT_NUMBER),
            iban,
            item.fieldValue(CanonicalFieldKeys.CARD_NUMBER)
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }
    val readAloudBlocks = remember(readableValue) {
        EpcSepaAndQrEngine.buildReadAloudBlocks(readableValue, groupSize = 4)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = i18n.t("present_wake_lock_active"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                    SmoothPrimaryButton(onClick = onDismiss) {
                        Text(i18n.t("present_close_btn"))
                    }
                }

                if (readAloudBlocks.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmoothFilterPill(
                            selected = activeTab == "digital",
                            onClick = { activeTab = "digital" },
                            label = i18n.t("pass_tab_digital")
                        )
                        SmoothFilterPill(
                            selected = activeTab == "read_aloud",
                            onClick = { activeTab = "read_aloud" },
                            label = i18n.t("pass_tab_read_aloud")
                        )
                    }
                }

                if (activeTab == "read_aloud" && readAloudBlocks.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = i18n.t("pass_read_aloud_hint"),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF475569)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                readAloudBlocks.forEach { block ->
                                    Column(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFF8FAFC))
                                            .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "#${block.index}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF64748B)
                                        )
                                        Text(
                                            text = block.chars,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF0F172A)
                                        )
                                    }
                                }
                            }
                            if (bic.isNotBlank()) {
                                Text(
                                    text = "BIC: $bic",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF334155)
                                )
                            }
                        }
                    }
                } else {
                    DualBrandVisualCardHero(
                        item = item,
                        categoryLabelText = categorySingularLabel(i18n, item.category),
                        displaySubtitleText = i18n.resolveDisplaySubtitle(item.subtitle, item.category),
                        flipLabelFront = i18n.t("card_flip_to_front"),
                        flipLabelBack = i18n.t("card_flip_to_back"),
                        reverseBadgeLabel = i18n.t("card_back_badge"),
                        reverseEmptyHint = i18n.t("card_back_empty_hint"),
                        cvvLabelText = i18n.t("field_cvv")
                    )

                    if (readableValue.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (epcBankPayload == null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SmoothFilterPill(
                                            selected = !useQrMode,
                                            onClick = { useQrMode = false },
                                            label = i18n.t("barcode_format_code128")
                                        )
                                        SmoothFilterPill(
                                            selected = useQrMode,
                                            onClick = { useQrMode = true },
                                            label = i18n.t("barcode_format_qr")
                                        )
                                    }
                                } else {
                                    Text(
                                        text = i18n.t("sepa_qr_title"),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF334155)
                                    )
                                }

                                if (epcBankPayload != null || useQrMode) {
                                    OfflineQrCodeCanvas(
                                        payload = epcBankPayload ?: readableValue,
                                        sizeDp = 168.dp
                                    )
                                } else {
                                    OfflineCode128Canvas(payload = readableValue)
                                }

                                Text(
                                    text = readableValue,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteDialog(
    i18n: LocalizationEngine,
    searchEngine: MultilingualSearchEngine,
    items: List<VaultItemPayload>,
    onSelectAndClose: (VaultItemPayload) -> Unit,
    onCopyPrimaryAndClose: (VaultItemPayload) -> Unit,
    onCopySecondaryAndClose: (VaultItemPayload) -> Unit,
    onPresentPassAndClose: (VaultItemPayload) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var activeIndex by remember { mutableIntStateOf(0) }
    val inputFocusRequester = remember { FocusRequester() }

    val matches = remember(items, query, searchEngine) {
        val raw = searchEngine.search(items = items, rawQuery = query)
        searchEngine.sortItems(raw, VaultSortMode.PINNED_RECENT).take(12)
    }

    LaunchedEffect(matches.size) {
        if (activeIndex >= matches.size) {
            activeIndex = (matches.size - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(Unit) {
        delay(40L)
        runCatching { inputFocusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        ev.key == Key.DirectionDown -> {
                            if (matches.isNotEmpty()) {
                                activeIndex = (activeIndex + 1) % matches.size
                            }
                            true
                        }
                        ev.key == Key.DirectionUp -> {
                            if (matches.isNotEmpty()) {
                                activeIndex = (activeIndex - 1 + matches.size) % matches.size
                            }
                            true
                        }
                        ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                            val target = matches.getOrNull(activeIndex)
                            if (target != null) {
                                if (ev.isShiftPressed) {
                                    onCopySecondaryAndClose(target)
                                } else {
                                    onCopyPrimaryAndClose(target)
                                }
                            }
                            true
                        }
                        (ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.P -> {
                            matches.getOrNull(activeIndex)?.let { onPresentPassAndClose(it) }
                            true
                        }
                        ev.key == Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⌘K  ${i18n.t("cmd_palette_title")}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    SmoothTextButton(onClick = onDismiss) {
                        Text("✕")
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        activeIndex = 0
                    },
                    placeholder = { Text(i18n.t("cmd_palette_placeholder"), maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                )

                if (matches.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = i18n.t("search_empty_title"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        matches.forEachIndexed { idx, entry ->
                            val isHighlighted = idx == activeIndex
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isHighlighted) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectAndClose(entry) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    InstitutionMiniAvatar(item = entry, size = 32.dp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = i18n.resolveDisplaySubtitle(entry.subtitle, entry.category),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (entry.primaryCopyableField() != null) {
                                            SmoothTonalButton(
                                                onClick = { onCopyPrimaryAndClose(entry) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Text("↵ ${i18n.t("action_copy")}", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        SmoothOutlinedButton(
                                            onClick = { onCopySecondaryAndClose(entry) },
                                            minHeight = 32.dp,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                                        ) {
                                            Text("⇧↵", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                        }
                                        if (entry.category != VaultCategory.SECURE_NOTE) {
                                            SmoothOutlinedButton(
                                                onClick = { onPresentPassAndClose(entry) },
                                                minHeight = 32.dp,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
                                            ) {
                                                Text(i18n.t("action_present_card"), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = i18n.t("cmd_palette_hint_enter"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = i18n.t("cmd_palette_hint_shift_enter"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = i18n.t("cmd_palette_hint_pass"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
