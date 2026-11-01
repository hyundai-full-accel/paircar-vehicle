package com.paircar.domain.repository

import com.paircar.core.model.GeoPoint

/**
 * 앱 자체 지도에 그릴 경로선. NaviHelper 는 경로 좌표열을 제공하지 않으므로 별도로 구한다.
 * Pleos 내비가 실제로 안내하는 경로와 다를 수 있어 "미리보기" 용도로만 쓴다.
 */
data class RoutePreview(
    val path: List<GeoPoint>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** true 면 길찾기 실패로 지점 간 직선 + 추정값 */
    val isApproximate: Boolean,
)

interface RoutePreviewRepository {
    suspend fun preview(stops: List<GeoPoint>): RoutePreview
}
