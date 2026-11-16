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
import com.safepocket.android.ui.AdaptiveSafePocketShell
import com.safepocket.shared.i18n.LocalizationEngine
import com.safepocket.shared.i18n.SupportedLocale
import com.safepocket.shared.model.ThemePreference
import com.safepocket.shared.model.VaultCategory
import com.safepocket.shared.model.VaultItemPayload
import kotlinx.coroutines.launch
import java.util.Locale

data class FoldablePostureState(
    val isSeparatingHinge: Boolean = false,
    val isHalfOpened: Boolean = false,
    val hingeBoundsLeftPx: Int? = null
)

/**
 * Single Adaptive Activity for Android 10+ / Android 17+ Smartphones, Unfolded Foldables,
 * and Googlebook OS / ChromeOS Desktop Windows.
 *
 * - Loads all 6 bundled `.arb.json` localization catalogs (`en`, `de`, `it`, `fr`, `es`, `el`)
 *   from APK assets so every menu item, category, field label, and dialog displays its localized name.
 * - Allows screenshots by default (`allowScreenshots = true`) and provides a real-time Settings option
 *   to enable or disable screenshots (`FLAG_SECURE`) without restarting the Activity.
 * - Uses Light Theme (`ThemePreference.LIGHT`) by default with Dark & Use Device Theme options.
 */
class MainActivity : ComponentActivity() {

    private var postureState by mutableStateOf(FoldablePostureState())
    private lateinit var i18nEngine: LocalizationEngine

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

        val initialItems = AndroidSampleVault.decodeItemsOrDefault(prefs.getString(KEY_VAULT_ITEMS, null))

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
            var vaultItems by remember { mutableStateOf(initialItems) }
            var selectedCategory by remember { mutableStateOf<VaultCategory?>(null) }
            var favoritesOnly by remember { mutableStateOf(false) }
            var selectedItem by remember { mutableStateOf<VaultItemPayload?>(initialItems.firstOrNull()) }
            var themePreference by remember { mutableStateOf(initialTheme) }
            var useDeviceDynamicColor by remember { mutableStateOf(initialDynamicColor) }
            var allowScreenshots by remember { mutableStateOf(initialAllowScreenshots) }
            var showSettingsDialog by remember { mutableStateOf(false) }
            var showNewItemDialog by remember { mutableStateOf(false) }
            var isVaultLocked by remember { mutableStateOf(false) }
            var focusSearchTrigger by remember { mutableIntStateOf(0) }

            val persistItems: (List<VaultItemPayload>) -> Unit = { updated ->
                vaultItems = updated
                prefs.edit().putString(KEY_VAULT_ITEMS, AndroidSampleVault.encodeItems(updated)).apply()
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
                isVaultLocked = isVaultLocked,
                showSettingsDialog = showSettingsDialog,
                showNewItemDialog = showNewItemDialog,
                focusSearchTrigger = focusSearchTrigger,
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
                    val nextList = vaultItems.map {
                        if (it.id == target.id) it.copy(favorite = !it.favorite, updatedAtEpochMs = System.currentTimeMillis()) else it
                    }
                    persistItems(nextList)
                    selectedItem = nextList.firstOrNull { it.id == target.id }
                },
                onDeleteItem = { target ->
                    val nextList = vaultItems.filterNot { it.id == target.id }
                    persistItems(nextList)
                    if (selectedItem?.id == target.id) {
                        selectedItem = nextList.firstOrNull()
                    }
                },
                onCreateItem = { created ->
                    val nextList = listOf(created) + vaultItems
                    persistItems(nextList)
                    selectedItem = created
                    showNewItemDialog = false
                },
                onFocusSearch = { focusSearchTrigger++ },
                onNewItem = { showNewItemDialog = true },
                onDismissNewItem = { showNewItemDialog = false },
                onLockVault = { isVaultLocked = true },
                onUnlockVault = { isVaultLocked = false },
                onOpenSettings = { showSettingsDialog = true },
                onDismissSettings = { showSettingsDialog = false },
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
