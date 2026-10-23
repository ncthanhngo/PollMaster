package com.ncthanhngo.pollmaster.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.core.sites.SitesCsvException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Returns a function that opens the CSV file picker and imports the chosen file as the sites list of project [projectId]; [onResult] gets the count or the error. */
@Composable
fun rememberSitesCsvPicker(projectId: String?, onResult: (Result<Int>) -> Unit): () -> Unit {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw SitesCsvException("Không mở được tệp")
                    container.sites.importCsv(projectId ?: throw SitesCsvException("Phiếu chưa thuộc dự án nào"), text)
                }
            }
            onResult(result)
        }
    }
    return remember(picker) { { picker.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) } }
}
