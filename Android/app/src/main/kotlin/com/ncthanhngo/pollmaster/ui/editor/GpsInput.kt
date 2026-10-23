package com.ncthanhngo.pollmaster.ui.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.formatNumber
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.platform.LocationHelper
import com.ncthanhngo.pollmaster.ui.common.PermissionRationale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun GpsInput(value: JsonElement?, enabled: Boolean, onValue: (JsonElement?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val helper = remember { LocationHelper(context) }
    val obj = value.asObject()
    val accuracy = obj?.get("accuracy").asDouble()

    fun show(key: String) = obj?.get(key).asDouble()?.let { formatNumber(it, 7) }.orEmpty()
    var lat by rememberSaveable { mutableStateOf(show("lat")) }
    var lng by rememberSaveable { mutableStateOf(show("lng")) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var rationale by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (lat.toDoubleOrNull() != obj?.get("lat").asDouble()) lat = show("lat")
        if (lng.toDoubleOrNull() != obj?.get("lng").asDouble()) lng = show("lng")
    }

    fun commit(newLat: String, newLng: String, acc: Double?) {
        val la = newLat.replace(',', '.').toDoubleOrNull()
        val ln = newLng.replace(',', '.').toDoubleOrNull()
        if (la == null || ln == null) {
            if (newLat.isBlank() && newLng.isBlank()) onValue(null)
            return
        }
        onValue(
            JsonObject(
                buildMap {
                    put("lat", JsonPrimitive(la))
                    put("lng", JsonPrimitive(ln))
                    acc?.let { put("accuracy", JsonPrimitive(it)) }
                    put("capturedAt", JsonPrimitive(nowIso()))
                },
            ),
        )
    }

    fun fetch() {
        scope.launch {
            busy = true
            failed = false
            val loc = helper.current()
            busy = false
            if (loc == null) {
                failed = true
            } else {
                lat = formatNumber(loc.latitude, 7)
                lng = formatNumber(loc.longitude, 7)
                commit(lat, lng, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) fetch() else failed = true
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat, enabled = enabled,
                onValueChange = { lat = it; commit(it, lng, accuracy) },
                label = { Text(stringResource(R.string.gps_lat)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lng, enabled = enabled,
                onValueChange = { lng = it; commit(lat, it, accuracy) },
                label = { Text(stringResource(R.string.gps_lng)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
        }
        accuracy?.let { Text(stringResource(R.string.gps_accuracy, formatNumber(it, 0)), style = MaterialTheme.typography.bodySmall) }
        if (failed) Text(stringResource(R.string.gps_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = enabled && !busy,
                onClick = { if (helper.hasPermission()) fetch() else rationale = true },
            ) { Text(stringResource(if (busy) R.string.gps_waiting else R.string.gps_get)) }
            val la = lat.toDoubleOrNull()
            val ln = lng.toDoubleOrNull()
            OutlinedButton(
                enabled = la != null && ln != null,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$la,$ln?q=$la,$ln"))
                    runCatching { context.startActivity(intent) }
                },
            ) { Text(stringResource(R.string.gps_open_map)) }
        }
    }
    if (rationale) {
        PermissionRationale(
            message = stringResource(R.string.perm_location_message),
            onAllow = { rationale = false; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
            onDismiss = { rationale = false },
        )
    }
}
