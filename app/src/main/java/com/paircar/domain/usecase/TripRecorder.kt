package com.paircar.domain.usecase

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStop
import com.paircar.domain.model.TripStopType
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.TripRecordRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime

/** 운행 종료 화면에 보여줄 처리 결과 */
data class TripFinish(
    val record: TripRecord,
    /** 이번 운행에서 탄 동승자 수 */
    val boardedCount: Int,
    /** null 이면 저장 중 */
    val saved: Boolean?,
)

/**
 * 운행 한 건을 기록한다: 시작할 때 시각·주행거리(Vehicle SDK Odometer)를 잡아 두고,
 * 끝날 때 거리·시간·탑승 지점을 모아 [TripRecordRepository] 에 저장한다.
 * 주행거리를 못 읽으면(에뮬레이터는 0.0 고정) 경로 미리보기 거리로 대신하고 그렇게 표시한다.
 */
class TripRecorder(
    private val recordRepository: TripRecordRepository,
    private val energyRepository: VehicleEnergyRepository,
    private val routePreviewRepository: RoutePreviewRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private var startedAt: LocalDateTime? = null
    private var startOdometerKm: Double? = null

    private val _lastFinish = MutableStateFlow<TripFinish?>(null)
    val lastFinish: StateFlow<TripFinish?> = _lastFinish.asStateFlow()

    suspend fun start() {
        startedAt = now()
        startOdometerKm = energyRepository.read().odometerKm
        _lastFinish.value = null
    }

    suspend fun finish(trip: CarpoolTrip, arrivedByNavi: Boolean) {
        val endedAt = now()
        // 앱이 다시 켜져 시작 시각을 잃었으면 일정상 출발 시각을 쓴다
        val started = startedAt?.takeIf { it.toLocalDate() == trip.date } ?: trip.date.atTime(trip.departureTime)
        val endOdometerKm = energyRepository.read().odometerKm

        val boarded = trip.acceptedPassengers
            .filter { it.boardedAt != null && it.boardingStatus in RODE }
            .sortedBy { it.boardedAt }
        val planned = routePreviewRepository.preview(listOf(trip.origin.point) + boarded.map { it.pickup.point } + trip.destination.point)
        val direct = if (boarded.isEmpty()) null else routePreviewRepository.preview(trip.directStops())

        val odometerKm = startOdometerKm?.let { start -> endOdometerKm?.minus(start) }
        val (distanceKm, source) = if (odometerKm != null && odometerKm >= MIN_ODOMETER_KM) {
            odometerKm to DistanceSource.ODOMETER
        } else {
            planned.distanceMeters / 1000.0 to DistanceSource.ROUTE_ESTIMATE
        }

        val record = TripRecord(
            id = "trip-$started",
            kind = if (boarded.isEmpty()) TripKind.SOLO else TripKind.CARPOOL,
            date = trip.date,
            startTime = started.toLocalTime().withNano(0),
            endTime = endedAt.toLocalTime().withNano(0),
            distanceKm = distanceKm,
            distanceSource = source,
            // 길찾기가 실패해 직선 추정이 섞이면 우회거리가 틀어지므로 비워 둔다
            detourKm = direct
                ?.takeIf { !it.isApproximate && !planned.isApproximate }
                ?.let { ((planned.distanceMeters - it.distanceMeters) / 1000.0).coerceAtLeast(0.0) },
            stops = buildList {
                add(TripStop(started.toLocalTime().withNano(0), TripStopType.DEPART, trip.origin.name, trip.origin.point))
                boarded.forEach { passenger ->
                    add(TripStop(passenger.boardedAt!!.withNano(0), TripStopType.PICKUP, passenger.pickup.name, passenger.pickup.point, passenger.name))
                }
                add(TripStop(endedAt.toLocalTime().withNano(0), TripStopType.ARRIVE, trip.destination.name, trip.destination.point))
            },
            path = planned.path,
            arrivedByNavi = arrivedByNavi,
        )
        _lastFinish.value = TripFinish(record, boarded.size, saved = null)
        val saved = runCatching { recordRepository.save(record) }.isSuccess
        _lastFinish.value = TripFinish(record, boarded.size, saved = saved)
        startedAt = null
        startOdometerKm = null
    }

    private companion object {
        val RODE = setOf(BoardingStatus.BOARDED, BoardingStatus.DROPPED_OFF)

        /** 이보다 짧으면 주행거리 값이 안 바뀐 것으로 본다 */
        const val MIN_ODOMETER_KM = 0.1
    }
}
