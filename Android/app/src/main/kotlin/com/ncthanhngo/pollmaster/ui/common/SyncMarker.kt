package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.sync.RecordSyncState
import com.ncthanhngo.pollmaster.ui.theme.attentionColor
import com.ncthanhngo.pollmaster.ui.theme.warnColor

/** Sync state of a record (ux-spec N): "Đã đồng bộ dd-MM HH:mm" · "Chờ đồng bộ" · "Xung đột" · "Lỗi: …". */
@Composable
fun SyncMarker(record: Record, failure: String?, modifier: Modifier = Modifier) {
    val state = RecordSyncState.of(record, failure)
    val color = when (state) {
        is RecordSyncState.Synced -> MaterialTheme.colorScheme.onSurfaceVariant
        RecordSyncState.Pending -> warnColor()
        RecordSyncState.Conflict -> attentionColor()
        is RecordSyncState.Failed -> MaterialTheme.colorScheme.error
    }
    Text(state.text(), modifier, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
}
