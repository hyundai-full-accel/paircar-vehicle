package com.paircar.data.remote

import android.util.Log
import com.paircar.core.model.GeoPoint
import com.paircar.domain.repository.RoutePreview
import com.paircar.domain.repository.RoutePreviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 프로토타입용 경로 미리보기: OSRM 공개 데모 서버(router.project-osrm.org).
 * 데모 서버는 사용량 제한이 있고 상용 서비스용이 아니므로, 실서비스 전에는
 * 서버 측 길찾기 API(지도 사업자 등)로 교체한다. 실패하면 지점 간 직선으로 대체한다.
 */
class OsrmRoutePreviewRepository(
    private val baseUrl: String = "https://router.project-osrm.org",
) : RoutePreviewRepository {

    override suspend fun preview(stops: List<GeoPoint>): RoutePreview = withContext(Dispatchers.IO) {
        if (stops.size < 2) return@withContext straightLine(stops)
        runCatching { fetch(stops) }
            .onFailure { Log.w(TAG, "route preview failed, using straight line", it) }
            .getOrElse { straightLine(stops) }
    }

    private fun fetch(stops: List<GeoPoint>): RoutePreview {
        val coordinates = stops.joinToString(";") { "${it.longitude},${it.latitude}" }
        val connection = URL("$baseUrl/route/v1/driving/$coordinates?overview=full&geometries=geojson")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        // 데모 서버는 기본 Dalvik User-Agent 요청을 403 으로 거부한다(확인함)
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val path = (0 until coords.length()).map { i ->
                val pair = coords.getJSONArray(i)
                GeoPoint(latitude = pair.getDouble(1), longitude = pair.getDouble(0))
            }
            return RoutePreview(
                path = path,
                distanceMeters = route.getDouble("distance").toInt(),
                durationSeconds = route.getDouble("duration").toInt(),
                isApproximate = false,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun straightLine(stops: List<GeoPoint>): RoutePreview {
        val meters = stops.zipWithNext { a, b -> haversineMeters(a, b) }.sum()
        return RoutePreview(
            path = stops,
            distanceMeters = meters.toInt(),
            // 도심 평균 30km/h 가정
            durationSeconds = (meters / (30_000.0 / 3600)).toInt(),
            isApproximate = true,
        )
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    private companion object {
        const val TAG = "OsrmRoutePreview"
        const val TIMEOUT_MILLIS = 5_000
        const val USER_AGENT = "PairCar-Prototype/0.1 (Pleos Connect contest demo)"
    }
}
