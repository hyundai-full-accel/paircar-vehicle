package com.paircar.domain.model

import com.paircar.core.model.GeoPoint
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 동승자를 태웠는지 */
enum class TripKind { CARPOOL, SOLO }

/** 주행 거리를 어디서 구했는지 */
enum class DistanceSource {
    /** Vehicle SDK Odometer 출발·도착 값의 차이 */
    ODOMETER,

    /** 주행 거리를 못 읽어 경로 미리보기(OSRM) 거리로 대신함 */
    ROUTE_ESTIMATE,
}

enum class TripStopType { DEPART, PICKUP, ARRIVE }

data class TripStop(
    val time: LocalTime,
    val type: TripStopType,
    val placeName: String,
    val point: GeoPoint,
    /** 픽업이면 탄 동승자 */
    val passengerName: String? = null,
    /** 공유 요약에 쓰는 시·구 단위 지역 (상세 주소 대신). 모르면 null */
    val area: String? = null,
)

/**
 * 같은 출발·도착을 경유지 없이 갔을 때와 동승자를 태우며 갔을 때의 경로 미리보기 비교.
 * 둘 다 실제로 길찾기가 된 값일 때만 만든다(직선 추정이 섞이면 차이가 틀어진다).
 */
data class RouteComparison(
    val directKm: Double,
    val directSeconds: Int,
    val carpoolKm: Double,
    val carpoolSeconds: Int,
) {
    val extraKm: Double get() = (carpoolKm - directKm).coerceAtLeast(0.0)
    val extraSeconds: Int get() = (carpoolSeconds - directSeconds).coerceAtLeast(0)
}

/** 진행 중인 운행의 기록 시작값 (출발 시각, 출발 때 주행거리) */
data class TripSession(
    val startedAt: LocalDateTime,
    val startOdometerKm: Double?,
)

/** 끝난 운행 한 건. 운행을 마치면 기기에 저장한다(정산 서버 미구축). */
data class TripRecord(
    val id: String,
    val kind: TripKind,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val distanceKm: Double,
    val distanceSource: DistanceSource,
    /** 동승자를 태우느라 더 돈 거리 (경유 경로 − 직행 경로). 직접 이동이면 null */
    val detourKm: Double?,
    val stops: List<TripStop>,
    /** 지도에 그릴 경로선. 없으면 정류장만 표시 */
    val path: List<GeoPoint>,
    /** 이 운행이 내비 도착 이벤트로 끝났는지 (false 면 운전자가 직접 종료) */
    val arrivedByNavi: Boolean,
    /** 직접 이동 vs 동승 경로 비교. 동승자가 없거나 길찾기가 실패했으면 null */
    val routeComparison: RouteComparison? = null,
) {
    val durationSeconds: Int
        get() = Duration.between(startTime, endTime).seconds.toInt().coerceAtLeast(0)

    val passengerNames: List<String>
        get() = stops.mapNotNull { it.passengerName }
}
