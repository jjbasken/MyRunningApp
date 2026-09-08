package com.myrunningapp.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.myrunningapp.R
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Shared map; location comes from the tracker, never a second GPS subscription. */
@Composable
fun RouteMap(
    points: List<RouteCoordinate>,
    modifier: Modifier = Modifier,
    currentPosition: RouteCoordinate? = null,
    live: Boolean = false,
    colorByPace: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    var following by rememberSaveable { mutableStateOf(true) }
    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(3.0)
            // Allow map gestures inside the scrolling Track screen.
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL,
                )
                if (event.actionMasked == MotionEvent.ACTION_MOVE) following = false
                false
            }
        }
    }
    DisposableEffect(map, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }
    // Pace bands are the expensive part; recompute only when the route changes.
    val paceBands = remember(points, colorByPace) {
        if (colorByPace) PaceColors.bands(points) else emptyList()
    }
    // Timer recompositions do not rebuild route overlays or reset a panned camera.
    LaunchedEffect(map, points, currentPosition, paceBands) {
        map.overlays.toList().forEach { it.onDetach(map) }
        map.overlays.clear()
        val strokeWidth = 5 * context.resources.displayMetrics.density
        if (paceBands.isNotEmpty()) {
            paceBands.forEach { band ->
                map.overlays.add(Polyline(map).apply {
                    setPoints(band.points.map { it.geoPoint() })
                    outlinePaint.color = band.rgb or ALPHA_OPAQUE
                    outlinePaint.strokeWidth = strokeWidth
                })
            }
        } else {
            // No pace to show, or the route is too short to have one: fall back
            // to a single colour per segment rather than drawing nothing.
            points.groupBy { it.segmentIndex }.values.forEach { segment ->
                map.overlays.add(Polyline(map).apply {
                    setPoints(segment.map { it.geoPoint() })
                    outlinePaint.color = Color.rgb(25, 100, 210)
                    outlinePaint.strokeWidth = strokeWidth
                })
            }
        }
        if (!live) {
            mileMarkers(points).forEach { mile ->
                map.overlays.add(Marker(map).apply {
                    position = mile.position.geoPoint()
                    title = context.getString(R.string.map_mile, mile.number)
                    icon = numberedPin(map, mile.number)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
            }
        }
        val position = currentPosition ?: points.lastOrNull()
        if (position != null) {
            map.overlays.add(Marker(map).apply {
                this.position = position.geoPoint()
                title = context.getString(if (live) R.string.map_current_position else R.string.map_finish)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        map.invalidate()
    }
    LaunchedEffect(map, points, live) {
        if (!live && points.isNotEmpty()) {
            map.doOnLayout {
                map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map { it.geoPoint() }),
                    false, (48 * context.resources.displayMetrics.density).toInt(), 18.0, null)
            }
        }
    }
    LaunchedEffect(map, currentPosition, following) {
        if (live && following && currentPosition != null) {
            if (map.zoomLevelDouble < 15) map.controller.setZoom(17.0)
            map.controller.setCenter(currentPosition.geoPoint())
        }
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.matchParentSize())
        if (live) {
            Surface(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.small) {
                TextButton(onClick = { following = !following }) {
                    Text(stringResource(if (following) R.string.map_unlock else R.string.map_follow))
                }
            }
        }
        if (paceBands.isNotEmpty()) {
            PaceLegend(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        if (points.isEmpty() && currentPosition == null) {
            Surface(modifier = Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.small) {
                Text(stringResource(if (live) R.string.map_waiting else R.string.map_empty), Modifier.padding(12.dp))
            }
        }
        Surface(modifier = Modifier.align(Alignment.BottomStart)) {
            TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) {
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** osmdroid paints with ARGB ints; [PaceColors] deals only in RGB. */
private const val ALPHA_OPAQUE = 0xFF000000.toInt()

private fun RouteCoordinate.geoPoint() = GeoPoint(latitude, longitude)

/**
 * Says which end of the ramp is which. Without it the colours are decorative —
 * green and red mean nothing until you know they mean fast and slow.
 */
@Composable
private fun PaceLegend(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.map_pace_faster),
                style = MaterialTheme.typography.labelSmall,
            )
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .size(width = 48.dp, height = 6.dp)
                    .background(
                        Brush.horizontalGradient(
                            PaceColors.rampStops().map { ComposeColor(it or ALPHA_OPAQUE) },
                        ),
                        MaterialTheme.shapes.extraSmall,
                    ),
            )
            Text(
                stringResource(R.string.map_pace_slower),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun numberedPin(map: MapView, number: Int): BitmapDrawable {
    val size = (30 * map.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val center = size / 2f
    canvas.drawCircle(center, center, center, paint)
    paint.color = Color.rgb(25, 100, 210)
    canvas.drawCircle(center, center, center * .88f, paint)
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = size * .48f
    paint.isFakeBoldText = true
    canvas.drawText(number.toString(), center, center - (paint.ascent() + paint.descent()) / 2, paint)
    return BitmapDrawable(map.resources, bitmap)
}
