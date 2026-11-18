package com.safepocket.android

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.safepocket.android.data.AndroidSampleVault
import com.safepocket.android.data.AndroidVaultRepository
import com.safepocket.android.ui.AdaptiveSafePocketShell
import com.safepocket.android.update.PlayProtectPackageInstaller
import com.safepocket.shared.error.SafePocketErrorHandler
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.i18n.SupportedLocale
import com.safepocket.shared.model.ThemePreference
import com.safepocket.shared.model.VaultCategory
import com.safepocket.shared.model.VaultItemPayload
import com.safepocket.shared.model.VaultStorageMode
import com.safepocket.shared.update.CrossPlatformUpdateService
import kotlinx.coroutines.launch
import java.util.Locale

data class FoldablePostureState(
    val isSeparatingHinge: Boolean = false,
    val isHalfOpened: Boolean = false,
    val hingeBoundsLeftPx: Int? = null
)

/**
 * Single Adaptive Activity for Android 10+ / Android 17+ Smartphones, Unfolded Foldables,
 * and Googlebook OS / ChromeOS Desktop Windows (`SafePocket v0.3`, `Build 5`).
 */
class MainActivity : ComponentActivity() {

    private var postureState by mutableStateOf(FoldablePostureState())
    private lateinit var i18nEngine: LocalizationEngine
    private lateinit var vaultRepository: AndroidVaultRepository
    private lateinit var playProtectInstaller: PlayProtectPackageInstaller
    private lateinit var updateService: CrossPlatformUpdateService

    private fun loadBundledArbCatalogs(): Map<String, String> =
        SupportedLocale.entries.associate { locale ->
            val rawJson = runCatching {
                assets.open("${locale.code}.arb.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.recoverCatching {
                assets.open("i18n/${locale.code}.arb.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("{}")
            locale.code to rawJson
        }

    private fun applyScreenshotWindowPolicy(allowScreenshots: Boolean) {
        if (allowScreenshots) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install global uncaught exception recorder (privacy-scrubbed) before delegating
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                SafePocketErrorHandler.record("uncaught:${thread.name}", throwable, System.currentTimeMillis())
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialAllowScreenshots = prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, true)
        applyScreenshotWindowPolicy(initialAllowScreenshots)

        val initialTheme = runCatching {
            ThemePreference.valueOf(prefs.getString(KEY_THEME_PREFERENCE, ThemePreference.LIGHT.name)!!)
        }.getOrDefault(ThemePreference.LIGHT)
        val initialDynamicColor = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, true)
        val savedManualLocaleCode = prefs.getString(KEY_MANUAL_LOCALE, null)

        i18nEngine = LocalizationEngine(
            initialSystemLanguageTag = Locale.getDefault().toLanguageTag(),
            catalogsByCode = loadBundledArbCatalogs()
        ).also { engine ->
            if (!savedManualLocaleCode.isNullOrBlank()) {
                val savedLocale = SupportedLocale.entries.firstOrNull { it.code == savedManualLocaleCode }
                engine.setManualLocaleOverride(savedLocale)
            }
        }

        vaultRepository = AndroidVaultRepository(applicationContext, prefs)
        playProtectInstaller = PlayProtectPackageInstaller(applicationContext)
        updateService = CrossPlatformUpdateService(
            httpClient = vaultRepository.httpClient,
            primitives = vaultRepository.primitives,
            installer = playProtectInstaller,
            initialAutoUpdateEnabled = vaultRepository.autoCheckUpdates
        )

        val bootstrapFallbackItems = if (vaultRepository.storageMode == VaultStorageMode.LOCAL_ONLY) {
            AndroidSampleVault.decodeItemsOrDefault(
                prefs.getString(KEY_VAULT_ITEMS, null),
                vaultRepository.sampleDataPurged
            )
        } else {
            emptyList()
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { layoutInfo ->
                        val fold = layoutInfo.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull()
                        postureState = if (fold != null) {
                            FoldablePostureState(
                                isSeparatingHinge = fold.isSeparating,
                                isHalfOpened = fold.state == FoldingFeature.State.HALF_OPENED,
                                hingeBoundsLeftPx = fold.bounds.left
                            )
                        } else {
                            FoldablePostureState()
                        }
                    }
            }
        }

        setContent {
            val coroutineScope = rememberCoroutineScope()
            var vaultItems by remember { mutableStateOf(bootstrapFallbackItems) }
            var selectedCategory by remember { mutableStateOf<VaultCategory?>(null) }
            var favoritesOnly by remember { mutableStateOf(false) }
            var selectedItem by remember { mutableStateOf<VaultItemPayload?>(bootstrapFallbackItems.firstOrNull()) }
            var themePreference by remember { mutableStateOf(initialTheme) }
            var useDeviceDynamicColor by remember { mutableStateOf(initialDynamicColor) }
            var allowScreenshots by remember { mutableStateOf(initialAllowScreenshots) }
            var autoDownloadLogos by remember { mutableStateOf(vaultRepository.autoDownloadLogos) }
            var showSettingsDialog by remember { mutableStateOf(false) }
            var showEditorDialog by remember { mutableStateOf(false) }
            var editingItem by remember { mutableStateOf<VaultItemPayload?>(null) }
            var pendingCreateCategory by remember { mutableStateOf(VaultCategory.PAYMENT_CARD) }
            var isVaultLocked by remember { mutableStateOf(false) }
            var focusSearchTrigger by remember { mutableIntStateOf(0) }
            var statusTick by remember { mutableIntStateOf(0) }
            var statusBannerMessage by remember { mutableStateOf<String?>(null) }

            val syncIndicator by vaultRepository.syncIndicatorFlow.collectAsState()
            val updateCheckState by updateService.state.collectAsState()
            val autoUpdateEnabled by updateService.autoUpdateEnabled.collectAsState()
            val latestBannerError by SafePocketErrorHandler.latestBannerError.collectAsState()
            val diagnosticEvents by SafePocketErrorHandler.events.collectAsState()

            // Initialize active encrypted vault store + automatic signed update check on startup
            LaunchedEffect(Unit) {
                val loaded = vaultRepository.initializeAndLoadItems()
                vaultItems = loaded
                selectedItem = loaded.firstOrNull { it.id == selectedItem?.id } ?: loaded.firstOrNull()
                statusTick++
                runCatching {
                    updateService.onAppStartup(i18nEngine.activeLocale.value)
                }
            }

            // Collect zero-user-input automatic Cloud Sync offline->online reconciliations
            LaunchedEffect(Unit) {
                vaultRepository.autoReconciledItems.collect { reconciled ->
                    vaultItems = reconciled
                    selectedItem = reconciled.firstOrNull { it.id == selectedItem?.id } ?: reconciled.firstOrNull()
                    statusTick++
                }
            }

            val localDbStatus = remember(vaultItems, statusTick, syncIndicator) {
                vaultRepository.getLocalDbStatus(vaultItems)
            }
            val cloudSyncStatus = remember(statusTick, syncIndicator) {
                vaultRepository.getCloudSyncStatus()
            }

            val upsertAndEnrichItem: (VaultItemPayload) -> Unit = { candidate ->
                val exists = vaultItems.any { it.id == candidate.id }
                val updatedList = if (exists) {
                    vaultItems.map { if (it.id == candidate.id) candidate else it }
                } else {
                    listOf(candidate) + vaultItems
                }
                vaultItems = updatedList
                selectedItem = candidate
                showEditorDialog = false
                editingItem = null

                coroutineScope.launch {
                    vaultRepository.upsertItem(candidate, updatedList)
                    statusTick++
                    if (autoDownloadLogos && candidate.cardTheme?.institutionLogoDataUri == null) {
                        val enriched = vaultRepository.enrichItemBranding(candidate, forceOnlineFetch = false)
                        if (enriched != candidate) {
                            val afterLogoList = vaultItems.map { if (it.id == enriched.id) enriched else it }
                            vaultItems = afterLogoList
                            if (selectedItem?.id == enriched.id) {
                                selectedItem = enriched
                            }
                            vaultRepository.upsertItem(enriched, afterLogoList)
                            statusTick++
                        }
                    }
                }
            }

            AdaptiveSafePocketShell(
                i18n = i18nEngine,
                items = vaultItems,
                selectedCategory = selectedCategory,
                favoritesOnly = favoritesOnly,
                selectedItem = selectedItem,
                themePreference = themePreference,
                useDeviceDynamicColor = useDeviceDynamicColor,
                allowScreenshots = allowScreenshots,
                autoDownloadLogos = autoDownloadLogos,
                isVaultLocked = isVaultLocked,
                hasCustomMasterPassword = localDbStatus.hasCustomMasterPassword,
                localDbStatus = localDbStatus,
                cloudSyncStatus = cloudSyncStatus,
                syncIndicator = syncIndicator,
                updateCheckState = updateCheckState,
                autoUpdateEnabled = autoUpdateEnabled,
                latestBannerError = latestBannerError,
                diagnosticEvents = diagnosticEvents,
                showSettingsDialog = showSettingsDialog,
                showEditorDialog = showEditorDialog,
                editingItem = editingItem,
                initialCreateCategory = pendingCreateCategory,
                focusSearchTrigger = focusSearchTrigger,
                statusBannerMessage = statusBannerMessage,
                onDismissStatusBanner = { statusBannerMessage = null },
                onDismissErrorBanner = { SafePocketErrorHandler.dismissBanner() },
                onClearDiagnosticsLog = { SafePocketErrorHandler.clearDiagnostics() },
                onSelectThemePreference = { nextTheme ->
                    themePreference = nextTheme
                    prefs.edit().putString(KEY_THEME_PREFERENCE, nextTheme.name).apply()
                },
                onToggleDeviceDynamicColor = { enabled ->
                    useDeviceDynamicColor = enabled
                    prefs.edit().putBoolean(KEY_USE_DYNAMIC_COLOR, enabled).apply()
                },
                onToggleAllowScreenshots = { enabled ->
                    allowScreenshots = enabled
                    applyScreenshotWindowPolicy(enabled)
                    prefs.edit().putBoolean(KEY_ALLOW_SCREENSHOTS, enabled).apply()
                },
                onToggleAutoDownloadLogos = { enabled ->
                    autoDownloadLogos = enabled
                    vaultRepository.autoDownloadLogos = enabled
                },
                onSelectLocaleOverride = { localeOverride ->
                    i18nEngine.setManualLocaleOverride(localeOverride)
                    prefs.edit().apply {
                        if (localeOverride == null) remove(KEY_MANUAL_LOCALE)
                        else putString(KEY_MANUAL_LOCALE, localeOverride.code)
                    }.apply()
                },
                isFoldableSeparating = postureState.isSeparatingHinge,
                onSelectCategory = { cat ->
                    favoritesOnly = false
                    selectedCategory = cat
                },
                onSelectFavorites = {
                    selectedCategory = null
                    favoritesOnly = true
                },
                onSelectItem = { selectedItem = it },
                onToggleFavoriteItem = { target ->
                    val updated = target.copy(
                        favorite = !target.favorite,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    upsertAndEnrichItem(updated)
                },
                onTogglePinItem = { target ->
                    val updated = target.copy(
                        pinned = !target.pinned,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    upsertAndEnrichItem(updated)
                },
                onDuplicateItem = { target ->
                    val now = System.currentTimeMillis()
                    val copyItem = target.copy(
                        id = "item-$now",
                        title = "${target.title} (Copy)",
                        isSampleItem = false,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                    upsertAndEnrichItem(copyItem)
                },
                onEditItem = { target ->
                    editingItem = target
                    showEditorDialog = true
                },
                onDeleteItem = { target ->
                    val nextList = vaultItems.filterNot { it.id == target.id }
                    vaultItems = nextList
                    if (selectedItem?.id == target.id) {
                        selectedItem = nextList.firstOrNull()
                    }
                    coroutineScope.launch {
                        vaultRepository.deleteItem(target, nextList)
                        statusTick++
                    }
                },
                onDeleteAllExampleData = {
                    val (removedCount, remaining) = vaultRepository.deleteAllExampleData(vaultItems)
                    vaultItems = remaining
                    selectedItem = remaining.firstOrNull { it.id == selectedItem?.id } ?: remaining.firstOrNull()
                    statusTick++
                    removedCount
                },
                onSaveEditorItem = upsertAndEnrichItem,
                onFetchBrandLogoPreview = { draft ->
                    vaultRepository.enrichItemBranding(draft, forceOnlineFetch = true)
                },
                onVerifyWebsiteReachability = { url ->
                    vaultRepository.verifyWebsiteExists(url)
                },
                onFocusSearch = { focusSearchTrigger++ },
                onNewItem = {
                    pendingCreateCategory = selectedCategory ?: VaultCategory.PAYMENT_CARD
                    editingItem = null
                    showEditorDialog = true
                },
                onNewItemWithCategory = { chosenCategory ->
                    pendingCreateCategory = chosenCategory
                    editingItem = null
                    showEditorDialog = true
                },
                onDismissEditor = {
                    showEditorDialog = false
                    editingItem = null
                },
                onLockVault = { isVaultLocked = true },
                onUnlockVaultWithPassword = { candidatePw ->
                    val ok = vaultRepository.verifyMasterPassword(candidatePw)
                    if (ok) isVaultLocked = false
                    ok
                },
                onOpenSettings = { showSettingsDialog = true },
                onDismissSettings = { showSettingsDialog = false },
                onConfigureVaultCredentials = { masterPw, secretKeyOrUri ->
                    vaultRepository.configureVaultCredentials(masterPw, secretKeyOrUri, vaultItems)
                    statusTick++
                },
                onSwitchStorageMode = { nextMode ->
                    val updated = if (nextMode == VaultStorageMode.CLOUD_SYNC) {
                        vaultRepository.switchToCloudSyncExclusiveMode(vaultItems)
                    } else {
                        vaultRepository.switchToLocalOnlyExclusiveMode(vaultItems)
                    }
                    vaultItems = updated
                    selectedItem = updated.firstOrNull { it.id == selectedItem?.id } ?: updated.firstOrNull()
                    statusTick++
                },
                onCreateFirebaseAccount = { email, pw ->
                    val (_, reconciled) = vaultRepository.createFirebaseAccount(email, pw, vaultItems)
                    vaultItems = reconciled
                    selectedItem = reconciled.firstOrNull { it.id == selectedItem?.id } ?: reconciled.firstOrNull()
                    statusTick++
                },
                onSignInFirebaseAccount = { email, pw ->
                    val (_, reconciled) = vaultRepository.signInFirebaseAccount(email, pw, vaultItems)
                    vaultItems = reconciled
                    selectedItem = reconciled.firstOrNull { it.id == selectedItem?.id } ?: reconciled.firstOrNull()
                    statusTick++
                },
                onSignOutFirebaseAccount = {
                    val localItems = vaultRepository.signOutFirebaseAndReturnToLocal(vaultItems)
                    vaultItems = localItems
                    statusTick++
                },
                onSyncNow = {
                    val report = vaultRepository.syncNow(vaultItems)
                    vaultItems = report.updatedItems
                    selectedItem = report.updatedItems.firstOrNull { it.id == selectedItem?.id }
                        ?: report.updatedItems.firstOrNull()
                    statusTick++
                    report
                },
                onToggleAutoUpdate = { enabled ->
                    vaultRepository.autoCheckUpdates = enabled
                    updateService.setAutomaticUpdates(enabled)
                },
                onCheckForUpdatesNow = {
                    updateService.checkForUpdates(i18nEngine.activeLocale.value)
                },
                onInstallVerifiedUpdate = { manifest, artifact ->
                    if (!playProtectInstaller.canInstallPackages()) {
                        startActivity(playProtectInstaller.createManageUnknownAppSourcesIntent())
                        i18nEngine.t("update_permission_prompt")
                    } else {
                        updateService.performVerifiedUpdate(manifest, artifact)
                        null
                    }
                },
                onExportEncryptedBackup = { backupPw ->
                    vaultRepository.exportEncryptedContainer(vaultItems, backupPw).encodeToByteArray()
                },
                onRestoreEncryptedBackup = { rawJson, pw, sk, replace ->
                    val updated = vaultRepository.restoreEncryptedContainer(
                        rawJson = rawJson,
                        backupPassword = pw,
                        backupSecretKey = sk,
                        existingItems = vaultItems,
                        replaceExisting = replace
                    )
                    vaultItems = updated
                    selectedItem = updated.firstOrNull()
                    statusTick++
                },
                onImport1PasswordOrCsv = { fileName, rawBytes, includeLogins ->
                    val (report, merged) = vaultRepository.importFromFileBytes(
                        fileName = fileName,
                        rawBytes = rawBytes,
                        includeLoginsAsNotes = includeLogins,
                        existingItems = vaultItems
                    )
                    vaultItems = merged
                    selectedItem = report.importedItems.firstOrNull() ?: merged.firstOrNull()
                    statusTick++
                    report
                },
                onVerifyMasterPassword = { pw -> vaultRepository.verifyMasterPassword(pw) },
                onExport1Password1Pux = { vaultRepository.export1Password1PuxBytes(vaultItems) },
                onExport1PasswordCsv = { vaultRepository.export1PasswordCsvText(vaultItems).encodeToByteArray() },
                onExportSafePocketCsv = { vaultRepository.exportSafePocketCsvText(vaultItems).encodeToByteArray() },
                onExportEmergencyKitHtml = { vaultRepository.buildEmergencyKitHtml().encodeToByteArray() },
                onDropDocumentAttachment = { true }
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::i18nEngine.isInitialized && newConfig.locales.size() > 0) {
            i18nEngine.onSystemLocaleChanged(newConfig.locales[0].toLanguageTag())
        }
    }

    companion object {
        const val PREFS_NAME = "safepocket_android_prefs"
        const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
        const val KEY_THEME_PREFERENCE = "theme_preference"
        const val KEY_USE_DYNAMIC_COLOR = "use_device_dynamic_color"
        const val KEY_MANUAL_LOCALE = "manual_locale"
        const val KEY_VAULT_ITEMS = "vault_items_json"
    }
}
