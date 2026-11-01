package com.paircar.core.ui.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paircar.core.model.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

enum class StopKind { ORIGIN, PICKUP, PICKUP_DONE, DESTINATION }

data class MapStop(val point: GeoPoint, val kind: StopKind)

/** 경로를 화면에 맞출 때 비워 둘 여백(px). 지도 위에 카드를 띄우면 그만큼 비운다. */
data class MapInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    constructor(all: Int) : this(all, all, all, all)
}

/**
 * 페어카 앱 자체 지도. Pleos 내비 지도를 앱 안에 넣는 API 는 없으므로 독립 지도 SDK(MapLibre)로 그린다.
 * - [route]: 경로선 (OSRM 미리보기 또는 직선. Pleos 내비의 실제 안내 경로와 다를 수 있음)
 * - [current]: NaviHelper getCurrentLocationInfo 로 받은 차량 위치
 * 주행 중 표시는 애니메이션 없이 위치만 갱신한다(카메라는 경로가 바뀔 때만 맞춤).
 */
@Composable
fun CarpoolMap(
    stops: List<MapStop>,
    route: List<GeoPoint>,
    current: GeoPoint?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    insets: MapInsets = MapInsets(120),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(style, stops, route, current) {
        val loaded = style ?: return@LaunchedEffect
        loaded.setData(SOURCE_ROUTE, lineOf(route))
        StopKind.entries.forEach { kind -> loaded.setData(kind.sourceId, pointsOf(stops.filter { it.kind == kind }.map { it.point })) }
        loaded.setData(SOURCE_CURRENT, pointsOf(listOfNotNull(current)))
    }

    LaunchedEffect(map, style, stops, route, insets) {
        val loadedMap = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val points = (route + stops.map { it.point }).map { LatLng(it.latitude, it.longitude) }
        when {
            points.size >= 2 -> loadedMap.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(points).build(),
                    insets.left, insets.top, insets.right, insets.bottom,
                ),
            )
            points.size == 1 -> loadedMap.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14.0))
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { loadedMap ->
                loadedMap.uiSettings.setAllGesturesEnabled(interactive)
                loadedMap.uiSettings.isCompassEnabled = false
                loadedMap.setStyle(Style.Builder().fromUri(STYLE_URL)) { loadedStyle ->
                    loadedStyle.addLayers()
                    map = loadedMap
                    style = loadedStyle
                }
            }
            mapView
        },
        modifier = modifier,
    )
}

private val StopKind.sourceId get() = "stops-${name.lowercase()}"

private fun Style.addLayers() {
    addSource(GeoJsonSource(SOURCE_ROUTE))
    addLayer(
        LineLayer("route-casing", SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(12f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    addLayer(
        LineLayer("route-line", SOURCE_ROUTE).withProperties(
            PropertyFactory.lineColor("#2F6BFF"),
            PropertyFactory.lineWidth(7f),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    val stopColors = mapOf(
        StopKind.ORIGIN to "#5F6670",
        StopKind.PICKUP_DONE to "#9AA1AB",
        StopKind.PICKUP to "#2F6BFF",
        StopKind.DESTINATION to "#1F2329",
    )
    stopColors.forEach { (kind, color) ->
        addSource(GeoJsonSource(kind.sourceId))
        addLayer(
            CircleLayer("${kind.sourceId}-layer", kind.sourceId).withProperties(
                PropertyFactory.circleColor(color),
                PropertyFactory.circleRadius(if (kind == StopKind.DESTINATION) 14f else 12f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(4f),
            ),
        )
    }
    addSource(GeoJsonSource(SOURCE_CURRENT))
    addLayer(
        CircleLayer("current-halo", SOURCE_CURRENT).withProperties(
            PropertyFactory.circleColor("#2F6BFF"),
            PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleRadius(30f),
        ),
    )
    addLayer(
        CircleLayer("current-dot", SOURCE_CURRENT).withProperties(
            PropertyFactory.circleColor("#2F6BFF"),
            PropertyFactory.circleRadius(13f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(5f),
        ),
    )
}

private fun Style.setData(sourceId: String, data: FeatureCollection) {
    getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(data)
}

private fun lineOf(points: List<GeoPoint>): FeatureCollection =
    if (points.size < 2) {
        FeatureCollection.fromFeatures(emptyList())
    } else {
        FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { it.toPoint() })))
    }

private fun pointsOf(points: List<GeoPoint>): FeatureCollection =
    FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(it.toPoint()) })

private fun GeoPoint.toPoint(): Point = Point.fromLngLat(longitude, latitude)

/** OpenFreeMap Positron: 회색 톤 라이트 지도 (API 키 불필요). 실서비스 전 상용 지도/약관 검토 필요 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private const val SOURCE_ROUTE = "route"
private const val SOURCE_CURRENT = "current"
