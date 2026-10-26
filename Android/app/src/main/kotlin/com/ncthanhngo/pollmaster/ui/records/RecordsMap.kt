package com.ncthanhngo.pollmaster.ui.records

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.sites.MapPins
import com.ncthanhngo.pollmaster.core.sites.PinStatus
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.pinStatus
import com.ncthanhngo.pollmaster.data.RecordItem
import com.ncthanhngo.pollmaster.ui.common.AppCard
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/** What a tapped pin stands for: a record (opens it) or a planned site without a record yet. */
private sealed interface Pin {
    data class OfRecord(val item: RecordItem) : Pin
    data class OfSite(val site: Site) : Pin
}

private const val DEFAULT_ZOOM = 5.0
private const val PIN_DP = 30
private val STATUS_COLOR = mapOf(
    PinStatus.DRAFT to 0xFF6B7773.toInt(),
    PinStatus.COMPLETED to 0xFF1B8A4A.toInt(),
    PinStatus.RETURNED to 0xFFB35400.toInt(), // AttentionLight
)
private const val PLANNED_COLOR = 0xFF1B6B5A.toInt()

private fun configure(context: Context) {
    Configuration.getInstance().apply {
        // Tiles are cached in the app cache (no storage permission) and OSM requires an identifying user agent.
        userAgentValue = context.packageName
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

/**
 * Pin glyph at ([cx], [cy]): filled circle with a mark per status so the pins read without colour (dot = Nháp,
 * check = Hoàn thành, U-turn = Cần sửa); [status] null = planned site (white circle, coloured ring).
 */
private fun drawPin(canvas: android.graphics.Canvas, cx: Float, cy: Float, r: Float, color: Int, status: PinStatus?) {
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
private fun pinDrawable(context: Context, color: Int, status: PinStatus?, label: String): BitmapDrawable {
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

/**
 * Map of the records (pin per record that has a GPS value, coloured and marked by status, labelled with its code) and of
 * the planned sites that have no record yet (empty pins labelled with the site code). OpenStreetMap tiles need network; pins are drawn without it.
 */
@SuppressLint("ClickableViewAccessibility") // the listener only forwards a parent-intercept request; the map handles its own gestures
@Composable
fun RecordsMap(items: List<RecordItem>, sites: List<Site>, onOpenRecord: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Pin?>(null) }
    val recordPins = remember(items) {
        items.mapNotNull { item -> item.template?.let { t -> MapPins.position(t, item.record)?.let { item to it } } }
    }
    val planned = remember(items, sites) {
        MapPins.unvisited(sites, MapPins.visitedCodes(items.mapNotNull { i -> i.template?.let { it to i.record } }))
    }
    val mapView = remember { configure(context); MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) mapView.onResume() else if (event == Lifecycle.Event.ON_PAUSE) mapView.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onDetach() }
    }

    Box(modifier.fillMaxSize().clipToBounds()) {
        AndroidView(
            factory = {
                // A parent scroller must not steal pans / pinches from the map.
                mapView.apply { setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true); false } }
            },
            update = { map ->
                map.overlays.clear()
                recordPins.forEach { (item, pos) ->
                    val status = item.record.pinStatus()
                    // record code only ("TDP-007" of "TDP-007 · 12 Lê Lợi"): long titles collide on the map
                    val icon = pinDrawable(context, STATUS_COLOR.getValue(status), status, item.title.substringBefore(" · "))
                    map.overlays += marker(map, pos.lat, pos.lng, icon) { selected = Pin.OfRecord(item) }
                }
                planned.forEach { s -> map.overlays += marker(map, s.lat!!, s.lng!!, pinDrawable(context, PLANNED_COLOR, null, s.code)) { selected = Pin.OfSite(s) } }
                val points = recordPins.map { GeoPoint(it.second.lat, it.second.lng) } + planned.map { GeoPoint(it.lat!!, it.lng!!) }
                map.post { fit(map, points) }
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (recordPins.isNotEmpty() || planned.isNotEmpty()) Legend(planned.isNotEmpty(), Modifier.align(Alignment.TopStart))
        if (recordPins.isEmpty() && planned.isEmpty()) {
            AppCard(Modifier.align(Alignment.Center).padding(24.dp)) { Text(stringResource(R.string.map_empty), Modifier.padding(16.dp)) }
        }
        selected?.let { pin ->
            AppCard(Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 88.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (pin) {
                        is Pin.OfRecord -> {
                            Text(pin.item.title, style = MaterialTheme.typography.titleMedium)
                            Text(pin.item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                RecordChips(pin.item)
                                Box(Modifier.weight(1f))
                                TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_close)) }
                                Button(onClick = { onOpenRecord(pin.item.record.id) }) { Text(stringResource(R.string.map_open_record)) }
                            }
                        }
                        is Pin.OfSite -> {
                            Text(pin.site.code, style = MaterialTheme.typography.titleMedium)
                            if (pin.site.address.isNotBlank()) Text(pin.site.address, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.map_site_planned), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_close)) }
                        }
                    }
                }
            }
        }
    }
}

private fun marker(map: MapView, lat: Double, lng: Double, pin: BitmapDrawable, onTap: () -> Unit) =
    Marker(map).apply {
        position = GeoPoint(lat, lng)
        icon = pin
        // the point is the centre of the circle, at the bottom of the drawable under the label
        val pinPx = PIN_DP * map.context.resources.displayMetrics.density
        setAnchor(Marker.ANCHOR_CENTER, 1f - pinPx / 2 / pin.bitmap.height)
        setOnMarkerClickListener { _, _ -> onTap(); true }
    }

private fun fit(map: MapView, points: List<GeoPoint>) {
    when {
        points.isEmpty() -> { map.controller.setZoom(DEFAULT_ZOOM); map.controller.setCenter(GeoPoint(16.0, 106.0)) }
        points.size == 1 -> { map.controller.setZoom(16.0); map.controller.setCenter(points.first()) }
        else -> map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 120)
    }
}

@Composable
private fun Legend(withPlanned: Boolean, modifier: Modifier) {
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
private fun LegendRow(color: Int, status: PinStatus?, label: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) { drawIntoCanvas { drawPin(it.nativeCanvas, size.width / 2, size.height / 2, size.width / 2, color, status) } }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}
