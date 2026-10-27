package com.ncthanhngo.pollmaster.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.ui.common.FirstFitText
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.ui.projects.ProjectsScreen
import com.ncthanhngo.pollmaster.ui.records.RecordsScreen
import com.ncthanhngo.pollmaster.ui.settings.SettingsScreen
import com.ncthanhngo.pollmaster.ui.templates.TemplatesScreen

/** Root tabs: records, projects, templates, settings. */
@Composable
fun MainScreen(
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenTemplate: (Template) -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { onTab(0) },
                    icon = { Icon(Icons.Filled.Description, null) }, // one line: narrow phones (e.g. 360 dp) get the short label instead of "sát" wrapping
                    label = { FirstFitText(listOf(stringResource(R.string.tab_records), stringResource(R.string.tab_records_short))) },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { onTab(1) },
                    icon = { Icon(Icons.Filled.Folder, null) }, label = { Text(stringResource(R.string.tab_projects)) },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { onTab(2) },
                    icon = { Icon(Icons.Filled.Inventory2, null) }, label = { Text(stringResource(R.string.tab_templates)) },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { onTab(3) },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> RecordsScreen(onOpenRecord, onOpenHistory)
                1 -> ProjectsScreen(onOpenProject)
                2 -> TemplatesScreen(onOpenTemplate)
                else -> SettingsScreen()
            }
        }
    }
}
