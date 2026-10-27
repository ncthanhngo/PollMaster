package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.export.ErpUpload
import com.ncthanhngo.pollmaster.core.format.displayDateTimeDashed
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.ui.theme.warnColor

/** "Đã gửi ERP dd-MM-yyyy HH:mm", or "Có thay đổi chưa gửi" when edited since; nothing for a record never sent. */
@Composable
fun ErpMarker(record: Record, modifier: Modifier = Modifier) {
    val sentAt = record.erpUploadedAt?.takeIf { it.isNotEmpty() } ?: return
    if (ErpUpload.hasUnsentChanges(record)) {
        Text(stringResource(R.string.erp_unsent_changes), modifier, style = MaterialTheme.typography.bodySmall, color = warnColor(), maxLines = 1)
    } else {
        Text(
            stringResource(R.string.erp_sent_at, displayDateTimeDashed(sentAt)), modifier,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}
