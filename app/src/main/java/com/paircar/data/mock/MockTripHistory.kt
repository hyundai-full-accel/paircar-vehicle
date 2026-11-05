package com.paircar.data.mock

import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStop
import com.paircar.domain.model.TripStopType
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 화면 개발용 지난 운행 기록 (크루 시작일 ~ 어제의 평일 출근).
 * 거리는 오늘 일정과 같은 경로의 OSRM 값(판교역 경유 68.3km, 직행 59.4km)에 날마다 조금씩 차이를 둔 값이다.
 * 실제 운행을 마치면 이 목록이 아니라 기기 파일에 저장된다(LocalTripRecordRepository).
 */
object MockTripHistory {

    // 개천절 대체공휴일, 한글날
    private val holidays = setOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9))

    // 차수아가 불참해 혼자 간 날
    private val soloDays = setOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 23))

    private const val CARPOOL_KM = 68.3
    private const val DIRECT_KM = 59.4

    fun records(until: LocalDate): List<TripRecord> {
        val from = MockCarpoolData.crew.startDate
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { it < until }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY && it !in holidays }
            .map(::record)
            .toList()
            .reversed()
    }

    private fun record(date: LocalDate): TripRecord {
        val trip = MockCarpoolData.todayTrip(date)
        val pickup = trip.passengers.first { it.name == "차수아" }
        val solo = date in soloDays
        // 날마다 다른 값이 나오되 실행할 때마다 같도록 날짜로 정한다
        val jitter = date.dayOfYear % 5
        val start = trip.departureTime.plusMinutes((jitter - 2).toLong())
        val end = trip.estimatedArrivalTime.plusMinutes((jitter * 2 - 4).toLong()).minusMinutes(if (solo) 9 else 0)
        val stops = buildList {
            add(TripStop(start, TripStopType.DEPART, trip.origin.name, trip.origin.point))
            if (!solo) {
                add(
                    TripStop(
                        pickup.estimatedPickupTime.plusMinutes(jitter.toLong()),
                        TripStopType.PICKUP,
                        pickup.pickup.name,
                        pickup.pickup.point,
                        passengerName = pickup.name,
                    ),
                )
            }
            add(TripStop(end, TripStopType.ARRIVE, trip.destination.name, trip.destination.point))
        }
        return TripRecord(
            id = "mock-$date",
            kind = if (solo) TripKind.SOLO else TripKind.CARPOOL,
            date = date,
            startTime = start,
            endTime = end,
            distanceKm = (if (solo) DIRECT_KM else CARPOOL_KM) + (jitter - 2) * 0.3,
            distanceSource = DistanceSource.ROUTE_ESTIMATE,
            detourKm = if (solo) null else CARPOOL_KM - DIRECT_KM,
            stops = stops,
            path = emptyList(),
            arrivedByNavi = true,
        )
    }
}
