package com.ncthanhngo.pollmaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.ncthanhngo.pollmaster.ui.AppNav
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ncthanhngo.pollmaster.ui.theme.PollMasterTheme
import com.ncthanhngo.pollmaster.ui.theme.UiPrefs

class MainActivity : ComponentActivity() {
    private val container get() = (application as PollMasterApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                val compact by container.settings.compactMode.collectAsState()
                val contrast by container.settings.highContrast.collectAsState()
                PollMasterTheme(UiPrefs(compact, contrast)) { AppNav() }
            }
        }
    }

    override fun onStop() {
        container.records.flushAll()
        container.records.openIds().forEach(container.backup::requestIfNeeded)
        super.onStop()
    }
}
