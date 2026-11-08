package com.paircar.data.remote

import com.paircar.core.model.GeoPoint
import com.paircar.domain.repository.RoutePreview
import com.paircar.domain.repository.RoutePreviewRepository

/**
 * 같은 정류장 목록의 경로는 다시 묻지 않는다.
 * 홈 화면 미리보기·운행 기록·우회 거리 등록이 같은 경로(직행, 경유)를 여러 번 묻고,
 * OSRM 데모 서버는 사용량 제한이 있다. 길찾기에 실패한 직선 추정은 다음에 다시 묻도록 저장하지 않는다.
 */
class CachingRoutePreviewRepository(
    private val inner: RoutePreviewRepository,
    private val maxEntries: Int = 32,
) : RoutePreviewRepository {

    private val cache = object : LinkedHashMap<List<GeoPoint>, RoutePreview>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<GeoPoint>, RoutePreview>?): Boolean = size > maxEntries
    }

    override suspend fun preview(stops: List<GeoPoint>): RoutePreview {
        synchronized(cache) { cache[stops] }?.let { return it }
        val preview = inner.preview(stops)
        if (!preview.isApproximate) synchronized(cache) { cache[stops] = preview }
        return preview
    }
}
