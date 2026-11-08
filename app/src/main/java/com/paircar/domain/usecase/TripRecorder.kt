package com.paircar.domain.usecase

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStop
import com.paircar.domain.model.TripSession
import com.paircar.domain.model.TripStopType
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.TripRecordRepository
import com.paircar.domain.repository.TripSessionStore
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
    routePreviewRepository: RoutePreviewRepository,
    private val sessionStore: TripSessionStore = MemoryTripSessionStore(),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {

    private val routeComparer = RouteComparer(routePreviewRepository)

    private val _lastFinish = MutableStateFlow<TripFinish?>(null)
    val lastFinish: StateFlow<TripFinish?> = _lastFinish.asStateFlow()

    suspend fun start() {
        sessionStore.saveSession(TripSession(now(), energyRepository.read().odometerKm))
        _lastFinish.value = null
    }

    suspend fun finish(trip: CarpoolTrip, arrivedByNavi: Boolean) {
        val endedAt = now()
        val session = sessionStore.loadSession()?.takeIf { it.startedAt.toLocalDate() == trip.date }
        // 시작값을 잃었으면(다른 날 기록 등) 일정상 출발 시각을 쓴다
        val started = session?.startedAt ?: trip.date.atTime(trip.departureTime)
        val startOdometerKm = session?.startOdometerKm
        val endOdometerKm = energyRepository.read().odometerKm

        val boarded = trip.acceptedPassengers
            .filter { it.boardedAt != null && it.boardingStatus in RODE }
            .sortedBy { it.boardedAt }
        val via = routeComparer.via(trip, boarded.map { it.pickup })
        val planned = via.route

        val odometerKm = startOdometerKm?.let { start -> endOdometerKm?.minus(start) }
        val (distanceKm, source) = if (odometerKm != null && odometerKm >= MIN_ODOMETER_KM) {
            odometerKm to DistanceSource.ODOMETER
        } else {
            planned.distanceMeters / 1000.0 to DistanceSource.ROUTE_ESTIMATE
        }

        // 화면에 보이는 단위(0.1km·분)로 맞춰 둬야 두 값의 차이가 표시된 추가 거리·시간과 맞는다
        val comparison = via.comparison?.rounded()

        val record = TripRecord(
            id = "trip-$started",
            kind = if (boarded.isEmpty()) TripKind.SOLO else TripKind.CARPOOL,
            date = trip.date,
            startTime = started.toLocalTime().withNano(0),
            endTime = endedAt.toLocalTime().withNano(0),
            distanceKm = distanceKm,
            distanceSource = source,
            detourKm = comparison?.extraKm,
            stops = buildList {
                add(TripStop(started.toLocalTime().withNano(0), TripStopType.DEPART, trip.origin.name, trip.origin.point, area = trip.origin.area))
                boarded.forEach { passenger ->
                    add(TripStop(passenger.boardedAt!!.withNano(0), TripStopType.PICKUP, passenger.pickup.name, passenger.pickup.point, passenger.name, passenger.pickup.area))
                }
                add(TripStop(endedAt.toLocalTime().withNano(0), TripStopType.ARRIVE, trip.destination.name, trip.destination.point, area = trip.destination.area))
            },
            path = planned.path,
            arrivedByNavi = arrivedByNavi,
            routeComparison = comparison,
        )
        _lastFinish.value = TripFinish(record, boarded.size, saved = null)
        val saved = runCatching { recordRepository.save(record) }.isSuccess
        _lastFinish.value = TripFinish(record, boarded.size, saved = saved)
        sessionStore.saveSession(null)
    }

    private companion object {
        val RODE = setOf(BoardingStatus.BOARDED, BoardingStatus.DROPPED_OFF)

        /** 이보다 짧으면 주행거리 값이 안 바뀐 것으로 본다 */
        const val MIN_ODOMETER_KM = 0.1
    }
}

/** 기기에 두지 않는 기본값 (테스트용) */
class MemoryTripSessionStore : TripSessionStore {
    private var session: TripSession? = null
    override fun loadSession() = session
    override fun saveSession(session: TripSession?) {
        this.session = session
    }
}
