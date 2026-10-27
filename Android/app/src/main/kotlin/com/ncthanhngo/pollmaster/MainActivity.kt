package com.ncthanhngo.pollmaster

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.compose.runtime.CompositionLocalProvider
import com.ncthanhngo.pollmaster.ui.AppNav
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ncthanhngo.pollmaster.ui.theme.PollMasterTheme
import com.ncthanhngo.pollmaster.ui.theme.UiPrefs
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object {
        val LIGHT_BG = Color.parseColor("#E9EEEC")
        val DARK_BG = Color.parseColor("#0E1211")
        val LIGHT_SCRIM = Color.argb(0xe6, 0xff, 0xff, 0xff)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    private val container get() = (application as PollMasterApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First frame follows the stored preference (default light) so a dark system never flashes.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyWindowTheme(container.settings.themeMode.value.isDark(systemDark))
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                val compact by container.settings.compactMode.collectAsState()
                val contrast by container.settings.highContrast.collectAsState()
                val oneHand by container.settings.oneHand.collectAsState()
                val mode by container.settings.themeMode.collectAsState()
                val dark = mode.isDark(isSystemInDarkTheme())
                LaunchedEffect(dark) { applyWindowTheme(dark) }
                PollMasterTheme(UiPrefs(compact, contrast, dark, oneHand)) { AppNav() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A package opened from another app (VIEW) or shared to it (SEND); its content is validated by the import preview. */
    private fun handleShared(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) container.pendingBundle.value = uri
    }

    /** System bar icon colors and window background follow the app scheme, not the system one. */
    private fun applyWindowTheme(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        window.setBackgroundDrawable(ColorDrawable(if (dark) DARK_BG else LIGHT_BG))
    }

    override fun onStart() {
        super.onStart()
        container.scope.launch { container.erp.sync() }
    }

    override fun onStop() {
        container.records.flushAll()
        container.records.openIds().forEach(container.backup::requestIfNeeded)
        super.onStop()
    }
}
