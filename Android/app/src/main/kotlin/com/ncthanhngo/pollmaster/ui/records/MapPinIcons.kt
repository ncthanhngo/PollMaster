package com.ncthanhngo.pollmaster.ui.records

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.sites.PinStatus
import com.ncthanhngo.pollmaster.ui.common.AppCard

internal const val PIN_DP = 30
internal val STATUS_COLOR = mapOf(
    PinStatus.DRAFT to 0xFF6B7773.toInt(),
    PinStatus.COMPLETED to 0xFF1B8A4A.toInt(),
    PinStatus.RETURNED to 0xFFB35400.toInt(), // AttentionLight
)
internal const val PLANNED_COLOR = 0xFF1B6B5A.toInt()

/**
 * Pin glyph at ([cx], [cy]): filled circle with a mark per status so the pins read without colour (dot = Nháp,
 * check = Hoàn thành, U-turn = Cần sửa); [status] null = planned site (white circle, coloured ring).
 */
internal fun drawPin(canvas: android.graphics.Canvas, cx: Float, cy: Float, r: Float, color: Int, status: PinStatus?) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = if (status == null) AndroidColor.WHITE else color
    canvas.drawCircle(cx, cy, r, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = r * 0.2f
    paint.color = if (status == null) color else AndroidColor.WHITE
    canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2, paint)
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = r * 0.18f
    when (status) {
        PinStatus.COMPLETED -> canvas.drawPath(Path().apply {
            moveTo(cx - r * 0.4f, cy); lineTo(cx - r * 0.1f, cy + r * 0.32f); lineTo(cx + r * 0.42f, cy - r * 0.3f)
        }, paint)
        PinStatus.RETURNED -> {
            val k = r * 0.38f
            canvas.drawArc(RectF(cx - k, cy - k, cx + k, cy + k), 180f, 270f, false, paint)
            // arrow head at the start of the arc (left side), pointing down
            canvas.drawPath(Path().apply { moveTo(cx - k - r * 0.2f, cy - r * 0.12f); lineTo(cx - k, cy + r * 0.1f); lineTo(cx - k + r * 0.2f, cy - r * 0.12f) }, paint)
        }
        PinStatus.DRAFT -> { paint.style = Paint.Style.FILL; canvas.drawCircle(cx, cy, r * 0.28f, paint) }
        null -> Unit
    }
}

/** Pin with an always-visible label above it (osmdroid only shows titles in a tap bubble). */
internal fun pinDrawable(context: Context, color: Int, status: PinStatus?, label: String): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        this.color = 0xFF1C2321.toInt()
    }
    val pad = 6 * density
    val labelW = text.measureText(label) + 2 * pad
    val labelH = text.textSize + 4 * density
    val pin = PIN_DP * density
    val w = maxOf(labelW, pin).toInt() + 2
    val h = (labelH + 2 * density + pin).toInt() + 2
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val left = (w - labelW) / 2
    canvas.drawRoundRect(RectF(left, 0f, left + labelW, labelH), labelH / 2, labelH / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xEEFFFFFF.toInt() })
    canvas.drawText(label, left + pad, labelH / 2 - (text.ascent() + text.descent()) / 2, text)
    drawPin(canvas, w / 2f, h - pin / 2 - 1, pin / 2, color, status)
    return BitmapDrawable(context.resources, bitmap)
}

internal fun statusLabel(status: PinStatus): Int = when (status) {
    PinStatus.DRAFT -> R.string.status_draft
    PinStatus.COMPLETED -> R.string.status_completed
    PinStatus.RETURNED -> R.string.status_returned
}

@Composable
internal fun Legend(withPlanned: Boolean, modifier: Modifier) {
    AppCard(modifier.padding(8.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendRow(STATUS_COLOR.getValue(PinStatus.DRAFT), PinStatus.DRAFT, R.string.status_draft)
            LegendRow(STATUS_COLOR.getValue(PinStatus.RETURNED), PinStatus.RETURNED, R.string.status_returned)
            LegendRow(STATUS_COLOR.getValue(PinStatus.COMPLETED), PinStatus.COMPLETED, R.string.status_completed)
            if (withPlanned) LegendRow(PLANNED_COLOR, null, R.string.map_legend_planned)
        }
    }
}

@Composable
internal fun LegendRow(color: Int, status: PinStatus?, label: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) { drawIntoCanvas { drawPin(it.nativeCanvas, size.width / 2, size.height / 2, size.width / 2, color, status) } }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}
