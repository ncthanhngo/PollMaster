package com.ncthanhngo.pollmaster.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.ui.records.RecordsScreen
import com.ncthanhngo.pollmaster.ui.settings.SettingsScreen
import com.ncthanhngo.pollmaster.ui.templates.TemplatesScreen

/** Root tabs: records, templates, settings. */
@Composable
fun MainScreen(onOpenRecord: (String) -> Unit, onOpenTemplate: (Template) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Description, null) }, label = { Text(stringResource(R.string.tab_records)) },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Inventory2, null) }, label = { Text(stringResource(R.string.tab_templates)) },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> RecordsScreen(onOpenRecord)
                1 -> TemplatesScreen(onOpenTemplate)
                else -> SettingsScreen()
            }
        }
    }
}
