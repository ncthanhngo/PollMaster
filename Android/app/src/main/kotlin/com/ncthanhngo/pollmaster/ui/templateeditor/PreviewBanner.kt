package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.ui.theme.warnColor

/** The top bar of a record screen; for the throw-away record of "Xem trước" a banner sits under it. */
@Composable
fun PreviewTopBar(recordId: String, bar: @Composable () -> Unit) {
    val preview = LocalContainer.current.records.isPreview(recordId)
    Column {
        bar()
        if (preview) {
            Surface(color = warnColor().copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.te_preview_banner), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = warnColor(), maxLines = 1,
                )
            }
        }
    }
}
