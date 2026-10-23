package com.ncthanhngo.pollmaster.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.BuildConfig
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.data.PhotoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val name by container.settings.surveyorName.collectAsState()
    val quality by container.settings.photoQuality.collectAsState()
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { used = withContext(Dispatchers.IO) { container.recordStore.usedBytes() } }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = container.settings::setSurveyorName,
                label = { Text(stringResource(R.string.settings_surveyor)) },
                supportingText = { Text(stringResource(R.string.settings_surveyor_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_quality), style = MaterialTheme.typography.titleSmall)
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
            InfoRow(stringResource(R.string.settings_storage), Formatter.formatShortFileSize(context, used))
            InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
