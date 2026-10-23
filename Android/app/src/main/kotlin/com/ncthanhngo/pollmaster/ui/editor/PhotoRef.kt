package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.findField

/** Under a field with `photoRef`: thumbnails of the referenced photo field and a button that captures into it. */
@Composable
fun PhotoRefRow(refId: String, env: EditorEnv, captureTrigger: Int = 0) {
    val target = env.controller.template.findField(refId) ?: return
    if (!target.isMedia) return
    MediaField(
        target, env.scope.get(target.id), env.scope, env, enabled = true, asValue = true,
        referenceOnly = true, captureTrigger = captureTrigger,
    )
}

/** On a photo field that other fields point to: "Dùng cho: B-01, A-07". */
@Composable
fun PhotoUsers(fieldId: String, env: EditorEnv) {
    val users = env.photoUsers[fieldId].orEmpty()
    if (users.isEmpty()) return
    Text(
        stringResource(R.string.photo_used_for, users.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
