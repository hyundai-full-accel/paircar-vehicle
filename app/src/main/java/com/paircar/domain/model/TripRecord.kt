package com.paircar.domain.model

import com.paircar.core.model.GeoPoint
import java.time.Duration
import java.time.LocalDate
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
) {
    val durationSeconds: Int
        get() = Duration.between(startTime, endTime).seconds.toInt().coerceAtLeast(0)

    val passengerNames: List<String>
        get() = stops.mapNotNull { it.passengerName }
}
