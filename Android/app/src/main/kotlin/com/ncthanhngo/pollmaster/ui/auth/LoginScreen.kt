package com.ncthanhngo.pollmaster.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.data.ErpException
import com.ncthanhngo.pollmaster.ui.common.IconBadge
import kotlinx.coroutines.launch
import java.io.IOException

/** ERP sign-in: the same email and password as the ERP web and mobile app (EVSELab staff and partner organisations such as Kimsen). */
@Composable
fun LoginScreen() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val expired by container.erp.syncError.collectAsState()
    var error by remember { mutableStateOf(expired) }
    val resetPending by produceState(false) { value = container.erp.localDataResetPending() }
    val wipePrompt by container.erp.wipePrompt.collectAsState()
    val canSubmit = email.contains('@') && password.isNotEmpty() && !busy

    /** Runs one sign-in step; failures are shown on the screen. */
    fun run(step: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try {
                step()
            } catch (e: ErpException) {
                error = e.message
            } catch (e: IOException) {
                error = "Không kết nối được máy chủ ERP. Kiểm tra mạng rồi thử lại."
            } catch (e: Exception) { // shown to the user, never swallowed
                error = e.message ?: e.javaClass.simpleName
            }
            busy = false
        }
    }

    fun submit() = run { container.erp.login(email, password) }

    wipePrompt?.let {
        WipeDialog(
            it,
            onBackup = { run(container.erp::backupThenWipe) },
            onForce = { run(container.erp::wipeWithoutBackup) },
            onCancel = container.erp::cancelWipe,
        )
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(Icons.AutoMirrored.Filled.Assignment, size = 64.dp, modifier = Modifier.padding(top = 48.dp))
            Text("PollMaster", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.login_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.login_email)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.login_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (canSubmit) submit() }),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.login_action))
            }
            Text(stringResource(R.string.login_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (resetPending) Text(stringResource(R.string.login_reset_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
