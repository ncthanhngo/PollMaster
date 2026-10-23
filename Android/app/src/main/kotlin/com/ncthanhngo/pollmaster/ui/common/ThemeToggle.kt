package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs

/** Top-bar action that flips Light <-> Dark and saves the concrete choice. */
@Composable
fun ThemeToggleButton() {
    val settings = LocalContainer.current.settings
    val dark = LocalUiPrefs.current.dark
    val systemDark = isSystemInDarkTheme()
    IconButton(onClick = { settings.setThemeMode(settings.themeMode.value.toggled(systemDark)) }) {
        Icon(
            if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = stringResource(if (dark) R.string.theme_switch_light else R.string.theme_switch_dark),
        )
    }
}
