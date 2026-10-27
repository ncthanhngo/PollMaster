package com.ncthanhngo.pollmaster.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ncthanhngo.pollmaster.ui.common.IconBadge
import com.ncthanhngo.pollmaster.core.theme.ThemeMode
import com.ncthanhngo.pollmaster.ui.common.ThemeToggleButton
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.BuildConfig
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.data.PhotoQuality
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val name by container.settings.surveyorName.collectAsState()
    val quality by container.settings.photoQuality.collectAsState()
    val compact by container.settings.compactMode.collectAsState()
    val contrast by container.settings.highContrast.collectAsState()
    val oneHand by container.settings.oneHand.collectAsState()
    val themeMode by container.settings.themeMode.collectAsState()
    val account by container.erp.account.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { container.recordStore.usedBytes() } }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }, actions = { ThemeToggleButton() }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            account?.let { a ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleSmall)
                    Text(a.name, style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(a.email, a.org).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.account_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { confirmLogout = true }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.account_logout), color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
            OutlinedTextField(
                value = name,
                onValueChange = container.settings::setSurveyorName,
                label = { Text(stringResource(R.string.settings_surveyor)) },
                supportingText = { Text(stringResource(R.string.settings_surveyor_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingTitle(Icons.Filled.PhotoCamera, Color(0xFF8E44AD), stringResource(R.string.settings_quality))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = quality == PhotoQuality.HIGH,
                        onClick = { container.settings.setPhotoQuality(PhotoQuality.HIGH) },
                        label = { Text(stringResource(R.string.quality_high)) },
                    )
                    FilterChip(
                        selected = quality == PhotoQuality.MEDIUM,
                        onClick = { container.settings.setPhotoQuality(PhotoQuality.MEDIUM) },
                        label = { Text(stringResource(R.string.quality_medium)) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingTitle(Icons.Filled.Contrast, Color(0xFF5856D6), stringResource(R.string.settings_theme))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark, ThemeMode.SYSTEM to R.string.theme_system)
                        .forEach { (mode, label) ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { container.settings.setThemeMode(mode) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                }
            }
            HorizontalDivider()
            SwitchRow(Icons.Filled.UnfoldLess, Color(0xFF0E9AA7), stringResource(R.string.settings_compact), stringResource(R.string.settings_compact_hint), compact, container.settings::setCompactMode)
            SwitchRow(Icons.Filled.Contrast, Color(0xFFE07B00), stringResource(R.string.settings_contrast), stringResource(R.string.settings_contrast_hint), contrast, container.settings::setHighContrast)
            SwitchRow(Icons.Filled.PanTool, Color(0xFFD81B60), stringResource(R.string.settings_one_hand), stringResource(R.string.settings_one_hand_hint), oneHand, container.settings::setOneHand)
            HorizontalDivider()
            BackupSection()
            HorizontalDivider()
            InfoRow(Icons.Filled.Storage, stringResource(R.string.settings_storage), Formatter.formatShortFileSize(context, used))
            InfoRow(Icons.Filled.Info, stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        }
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.account_logout),
            message = stringResource(R.string.account_logout_confirm),
            confirmLabel = stringResource(R.string.account_logout),
            onConfirm = { confirmLogout = false; scope.launch { container.erp.logout() } },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, tint: Color, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, tint = tint, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BackupSection() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auto by container.settings.autoBackup.collectAsState()
    val status by container.backup.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(Icons.Filled.Sync, Color(0xFF2E7D32), stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_folder, Backup.FOLDER), auto, container.settings::setAutoBackup)
        status.lastOkAt?.let { Text(stringResource(R.string.settings_backup_last, displayDateTime(it)), style = MaterialTheme.typography.bodySmall) }
        status.error?.let { Text(stringResource(R.string.settings_backup_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val (done, total) = container.backup.backupAll()
                    busy = false
                    Toast.makeText(context, context.getString(R.string.settings_backup_done, done, total), Toast.LENGTH_LONG).show()
                }
            },
        ) { Text(stringResource(R.string.settings_backup_now)) }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, tint = Color.Gray, size = 32.dp)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingTitle(icon: ImageVector, tint: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, tint = tint, size = 32.dp)
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}
