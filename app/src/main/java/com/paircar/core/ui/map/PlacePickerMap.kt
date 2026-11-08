package com.paircar.core.ui.map

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paircar.core.model.GeoPoint
import com.paircar.core.ui.components.PinIcon
import com.paircar.core.ui.theme.DsColors
import com.paircar.data.server.roundCoordinate
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * 지도를 움직여 가운데 핀으로 장소를 고른다 (장소 검색 API 가 없어 좌표로 정한다).
 * 지도가 멈출 때마다 [onCenterChanged] 로 가운데 좌표를 알려준다.
 * [focus] 가 바뀌면 그 위치로 지도를 옮긴다 (현재 위치로 가기 등).
 */
@Composable
fun PlacePickerMap(
    focus: GeoPoint,
    onCenterChanged: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val centerChanged by rememberUpdatedState(onCenterChanged)

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
        // 이미 시작된 화면이면 addObserver 가 ON_START·ON_RESUME 을 바로 전달한다
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, focus) {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(focus.latitude, focus.longitude), PICK_ZOOM))
    }

    Box(modifier) {
        AndroidView(
            factory = {
                mapView.getMapAsync { loadedMap ->
                    loadedMap.uiSettings.isCompassEnabled = false
                    loadedMap.uiSettings.isRotateGesturesEnabled = false
                    loadedMap.uiSettings.isTiltGesturesEnabled = false
                    loadedMap.setStyle(Style.Builder().fromUri(STYLE_URL)) {
                        loadedMap.addOnCameraIdleListener {
                            val target = loadedMap.cameraPosition.target ?: return@addOnCameraIdleListener
                            centerChanged(GeoPoint(roundCoordinate(target.latitude), roundCoordinate(target.longitude)))
                        }
                        map = loadedMap
                    }
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 핀 끝이 지도 가운데에 오도록 핀 높이의 절반만큼 올린다
        PinIcon(Modifier.align(Alignment.Center).offset(y = (-22).dp).size(44.dp), color = DsColors.Brand)
    }
}

private const val PICK_ZOOM = 15.0

/** CarpoolMap 과 같은 지도 (OpenFreeMap Positron) */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
