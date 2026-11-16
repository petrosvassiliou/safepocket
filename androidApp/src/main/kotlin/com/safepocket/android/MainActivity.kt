package com.safepocket.android

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
import com.safepocket.android.ui.AdaptiveSafePocketShell
import com.safepocket.shared.i18n.LocalizationEngine
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
 * Single Adaptive Activity for Android 17+ Smartphones, Unfolded Foldables, and Googlebook OS.
 *
 * - Sets `FLAG_SECURE` to protect sensitive card numbers/passports from task-switcher screenshots.
 * - Observes `WindowInfoTracker` `FoldingFeature` transitions seamlessly without activity recreation.
 * - Uses Light Theme (`ThemePreference.LIGHT`) by default with Dark/System option.
 */
class MainActivity : ComponentActivity() {

    private var postureState by mutableStateOf(FoldablePostureState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent plaintext vault exposure in Recents / untrusted screen recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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

        val i18n = LocalizationEngine(
            initialSystemLanguageTag = Locale.getDefault().toLanguageTag(),
            catalogsByCode = emptyMap()
        )

        setContent {
            var selectedCategory by remember { mutableStateOf<VaultCategory?>(null) }
            var selectedItem by remember { mutableStateOf<VaultItemPayload?>(null) }
            var themePreference by remember { mutableStateOf(ThemePreference.LIGHT) }
            var useDeviceDynamicColor by remember { mutableStateOf(true) }

            AdaptiveSafePocketShell(
                i18n = i18n,
                items = emptyList(),
                selectedCategory = selectedCategory,
                selectedItem = selectedItem,
                themePreference = themePreference,
                useDeviceDynamicColor = useDeviceDynamicColor,
                onSelectThemePreference = { themePreference = it },
                onToggleDeviceDynamicColor = { useDeviceDynamicColor = it },
                isFoldableSeparating = postureState.isSeparatingHinge,
                onSelectCategory = { selectedCategory = it },
                onSelectItem = { selectedItem = it },
                onFocusSearch = {},
                onNewItem = {},
                onLockVault = {},
                onOpenSettings = {},
                onDropDocumentAttachment = { true }
            )
        }
    }
}
