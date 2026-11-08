package com.paircar.domain.model

import com.paircar.core.model.GeoPoint
import com.paircar.domain.repository.RoutePreview
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

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

    /** 화면에 보이는 단위(0.1km·분)로 맞춘다. 그래야 두 값의 차이가 화면의 추가 거리·시간과 같다 */
    fun rounded(): RouteComparison = RouteComparison(
        directKm = tenthKm(directKm),
        directSeconds = wholeMinutes(directSeconds),
        carpoolKm = tenthKm(carpoolKm),
        carpoolSeconds = wholeMinutes(carpoolSeconds),
    )

    companion object {
        /**
         * 경유 경로와 직행 경로를 비교한다. 어느 한쪽이라도 길찾기에 실패해 직선 추정이면 null
         * (직선 추정이 섞이면 차이가 틀어진다).
         */
        fun of(via: RoutePreview, direct: RoutePreview): RouteComparison? =
            if (via.isApproximate || direct.isApproximate) {
                null
            } else {
                RouteComparison(
                    directKm = direct.distanceMeters / 1000.0,
                    directSeconds = direct.durationSeconds,
                    carpoolKm = via.distanceMeters / 1000.0,
                    carpoolSeconds = via.durationSeconds,
                )
            }

        private fun tenthKm(km: Double): Double = (km * 10).roundToInt() / 10.0

        private fun wholeMinutes(seconds: Int): Int = (seconds / 60.0).roundToInt() * 60
    }
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
