package com.paircar.domain.usecase

import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteComparison
import com.paircar.domain.repository.RoutePreview
import com.paircar.domain.repository.RoutePreviewRepository

/** 동승자를 태우며 가는 경로와, 직행과의 비교 */
data class ViaRoute(
    /** 출발지 → 픽업 지점들 → 목적지 */
    val route: RoutePreview,
    /** 직행과 비교. 픽업이 없거나 어느 쪽이든 직선 추정이면 null */
    val comparison: RouteComparison?,
) {
    /** 우회 거리 (km). 비교할 수 없으면 null */
    val detourKm: Double?
        get() = comparison?.extraKm
}

/**
 * 경로 비교를 한곳에서 한다: 우회 거리 = (출발지 → 픽업 → 목적지) − (출발지 → 목적지).
 * 쓰는 곳: 운행 기록(직행 대비 추가 거리·시간), 서버 우회 거리 등록(동승자 1명씩).
 * 화면의 예상 분담금처럼 이미 미리보기를 가진 곳은 [RouteComparison.of] 를 바로 쓴다.
 */
class RouteComparer(private val routes: RoutePreviewRepository) {

    /** [pickups] 를 순서대로 들르는 경로와 직행 비교. 픽업이 없으면 직행을 다시 구하지 않는다 */
    suspend fun via(trip: CarpoolTrip, pickups: List<Place>): ViaRoute {
        val route = routes.preview(trip.stopsVia(pickups))
        if (pickups.isEmpty()) return ViaRoute(route, comparison = null)
        val direct = routes.preview(trip.directStops())
        return ViaRoute(route, RouteComparison.of(route, direct))
    }

    /** 동승자 한 명을 태우느라 더 가는 거리 (운전자 출발지 기준). 비교할 수 없으면 null */
    suspend fun detourKm(trip: CarpoolTrip, pickup: Place): Double? = via(trip, listOf(pickup)).detourKm
}
