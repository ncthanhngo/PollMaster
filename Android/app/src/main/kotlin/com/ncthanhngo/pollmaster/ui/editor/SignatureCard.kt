package com.ncthanhngo.pollmaster.ui.editor

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ncthanhngo.pollmaster.ui.common.AppCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.signatureFileName
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.SignatureDef
import com.ncthanhngo.pollmaster.core.model.SignatureValue
import com.ncthanhngo.pollmaster.core.editor.EditorController
import java.io.File

/** Signer name + hand-drawn signature saved as PNG in the record's media folder. */
@Composable
fun SignatureCard(def: SignatureDef, recordId: String, controller: EditorController, value: SignatureValue?, enabled: Boolean) {
    val container = LocalContainer.current
    val mediaDir = remember(recordId) { container.records.mediaDir(recordId) }
    var name by rememberSaveable(def.id) { mutableStateOf(value?.name.orEmpty()) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun save() {
        if (strokes.isEmpty() || size == IntSize.Zero) return
        val file = File(mediaDir, signatureFileName(def.id))
        val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { pts ->
            val p = android.graphics.Path()
            pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
            if (pts.size == 1) canvas.drawPoint(pts[0].x, pts[0].y, paint) else canvas.drawPath(p, paint)
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.setSignature(def.id, SignatureValue("media/${file.name}", name.trim().ifEmpty { null }, nowIso()))
    }

    AppCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(def.label, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    value?.let { v -> controller.setSignature(def.id, v.copy(name = it.trim().ifEmpty { null })) }
                },
                label = { Text(stringResource(R.string.signer_name)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                Modifier.fillMaxWidth().height(160.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            ) {
                if (value != null) {
                    AsyncImage(
                        model = File(mediaDir, value.file.substringAfterLast('/')),
                        contentDescription = def.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                } else {
                    Canvas(
                        Modifier.fillMaxWidth().height(160.dp)
                            .onSizeChanged { size = it }
                            .pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { strokes.add(listOf(it)) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        strokes[strokes.lastIndex] = strokes.last() + change.position
                                    },
                                    onDragEnd = { save() },
                                )
                            },
                    ) {
                        strokes.forEach { pts ->
                            val path = Path()
                            pts.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                            drawPath(path, Color.Black, style = Stroke(width = 5f))
                        }
                    }
                    if (strokes.isEmpty()) {
                        Text(stringResource(R.string.signature_hint), Modifier.padding(12.dp), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(
                enabled = enabled && (value != null || strokes.isNotEmpty()),
                onClick = {
                    strokes.clear()
                    value?.let { File(mediaDir, it.file.substringAfterLast('/')).delete() }
                    controller.setSignature(def.id, null)
                },
            ) { Text(stringResource(R.string.signature_clear)) }
        }
    }
}
