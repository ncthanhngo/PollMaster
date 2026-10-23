package com.ncthanhngo.pollmaster.ui.records

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    PinStatus.RETURNED to 0xFFE07B00.toInt(),
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

private fun pinDrawable(context: Context, color: Int, filled: Boolean): GradientDrawable {
    val px = (PIN_DP * context.resources.displayMetrics.density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(px, px)
        setColor(if (filled) color else AndroidColor.WHITE)
        setStroke((3 * context.resources.displayMetrics.density).toInt(), if (filled) AndroidColor.WHITE else color)
    }
}

/**
 * Map of the records (pin per record that has a GPS value, coloured by status) and of the planned sites that have no
 * record yet (empty pins). OpenStreetMap tiles need network; pins are drawn without it.
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
                    map.overlays += marker(context, map, pos.lat, pos.lng, STATUS_COLOR.getValue(item.record.pinStatus()), true) { selected = Pin.OfRecord(item) }
                }
                planned.forEach { s -> map.overlays += marker(context, map, s.lat!!, s.lng!!, PLANNED_COLOR, false) { selected = Pin.OfSite(s) } }
                val points = recordPins.map { GeoPoint(it.second.lat, it.second.lng) } + planned.map { GeoPoint(it.lat!!, it.lng!!) }
                map.post { fit(map, points) }
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize(),
        )
        Legend(Modifier.align(Alignment.TopStart))
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

private fun marker(context: Context, map: MapView, lat: Double, lng: Double, color: Int, filled: Boolean, onTap: () -> Unit) =
    Marker(map).apply {
        position = GeoPoint(lat, lng)
        icon = pinDrawable(context, color, filled)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
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
private fun Legend(modifier: Modifier) {
    AppCard(modifier.padding(8.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(STATUS_COLOR.getValue(PinStatus.DRAFT), true); Text(stringResource(R.string.status_draft), style = MaterialTheme.typography.labelSmall)
            Dot(STATUS_COLOR.getValue(PinStatus.COMPLETED), true); Text(stringResource(R.string.status_completed), style = MaterialTheme.typography.labelSmall)
            Dot(STATUS_COLOR.getValue(PinStatus.RETURNED), true); Text(stringResource(R.string.status_returned), style = MaterialTheme.typography.labelSmall)
            Dot(PLANNED_COLOR, false); Text(stringResource(R.string.map_legend_planned), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Dot(color: Int, filled: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        val c = Color(color)
        if (filled) drawCircle(c) else drawCircle(c, style = Stroke(width = 3f))
    }
}
