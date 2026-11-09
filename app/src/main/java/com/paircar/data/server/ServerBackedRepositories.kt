package com.paircar.data.server

import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInfo
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.CrewSetup
import com.paircar.domain.model.CrewSetupState
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.SettlementSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 앱 카풀 저장소에 서버를 얹는다. 화면·유스케이스는 [CarpoolRepository] 만 알므로 그대로 둔다.
 * - 오늘 운행: 서버 크루 일정(출발 시각·출발지·목적지)·동승자 평소 탈 곳·오늘 출석으로 만든다.
 *   서버에 일정이 없으면 앱 예시 운행을 쓴다.
 * - 크루 정보·출발 전 출석 변경 → 서버
 * - 운행 시작·탑승·하차·미탑승·운행 중 불참·종료 → 서버 운행 기록
 * - QR 초대 → 서버가 발급한 초대 코드
 */
class ServerCarpoolRepository(
    private val local: CarpoolRepository,
    private val sync: ServerSync,
    private val scope: CoroutineScope,
    /** 운전자가 [크루 정보 설정]에서 바꾼 이름. 서버에 올리기 전·실패했을 때 기기 값을 보여준다 */
    private val editedCrewName: () -> String? = { null },
    /** 경유 시간 계산(픽업 예정 시각). null 이면 서버 일정으로 오늘 운행을 만들지 않는다 (테스트) */
    private val routes: RoutePreviewRepository? = null,
    /** 서버에 없는 차량 이름 (화면 표시용) */
    private val vehicleName: String = "",
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = {},
) : CarpoolRepository by local {

    /** 크루 이름·시작일·명단·반복 요일은 서버 */
    override val crew: StateFlow<Crew?> =
        combine(local.crew, sync.crew) { localCrew, serverCrew -> mergeServerCrew(localCrew, serverCrew, editedCrewName()) }
            .stateIn(scope, SharingStarted.Eagerly, mergeServerCrew(local.crew.value, sync.crew.value, editedCrewName()))

    /** 서버 크루가 없거나 일정(출발 시각·출발지·도착지)이 없으면 [크루 만들기]가 필요하다 */
    override val setupState: StateFlow<CrewSetupState> =
        combine(sync.crewLoaded, sync.crew) { loaded, serverCrew ->
            when {
                serverCrew?.hasSchedule == true -> CrewSetupState.READY
                !loaded -> CrewSetupState.LOADING
                else -> CrewSetupState.NEEDS_SETUP
            }
        }.stateIn(scope, SharingStarted.Eagerly, CrewSetupState.LOADING)

    override suspend fun setupCrew(setup: CrewSetup) = sync.setupCrew(setup)

    init {
        if (routes != null) {
            scope.launch(Dispatchers.IO) {
                sync.crew.filterNotNull().collectLatest { serverCrew ->
                    val date = today()
                    try {
                        val trip = buildTodayTrip(date, serverCrew, local.todayTrip.value, vehicleName) { stops -> routes.preview(stops).durationSeconds }
                        if (trip == null) {
                            log("서버 크루에 출발 시각·출발지·목적지가 없어 오늘 운행을 만들 수 없습니다")
                        } else {
                            local.applySchedule(trip)
                            log("서버 일정으로 오늘 운행: ${trip.departureTime} 출발, 동승자 ${trip.passengers.map { "${it.name}(${it.pickup.name}, ${it.attendance})" }}")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        log("서버 일정으로 오늘 운행을 만들지 못했습니다: $e")
                    }
                }
            }
        }
    }

    override suspend fun refresh() {
        local.refresh()
        sync.refreshCrew()
    }

    override suspend fun pollCrew() = sync.pollCrew()

    override suspend fun updateTripStatus(status: CarpoolStatus) {
        val before = local.todayTrip.value?.status
        local.updateTripStatus(status)
        val trip = local.todayTrip.value ?: return
        when {
            before == CarpoolStatus.SCHEDULED && status == CarpoolStatus.IN_PROGRESS -> sync.onTripStarted(trip)
            before == CarpoolStatus.IN_PROGRESS && (status == CarpoolStatus.COMPLETED || status == CarpoolStatus.CANCELLED) ->
                sync.onTripEnded(trip)
        }
    }

    override suspend fun updateBoardingStatus(passengerId: String, status: BoardingStatus) {
        val before = local.todayTrip.value?.passengers?.firstOrNull { it.id == passengerId }?.boardingStatus
        local.updateBoardingStatus(passengerId, status)
        val trip = local.todayTrip.value ?: return
        if (before != status && trip.status == CarpoolStatus.IN_PROGRESS) sync.onBoardingChanged(trip, passengerId, status)
    }

    /** 출발 전이면 서버 출석으로, 운행 중이면 운행 불참(DELETE boarding)으로 올린다 */
    override suspend fun updateAttendance(passengerId: String, attendance: Attendance) {
        val before = local.todayTrip.value?.passengers?.firstOrNull { it.id == passengerId }?.attendance
        local.updateAttendance(passengerId, attendance)
        val trip = local.todayTrip.value ?: return
        if (before == attendance) return
        when (trip.status) {
            CarpoolStatus.SCHEDULED -> scope.launch(Dispatchers.IO) { sync.pushAttendance(trip.date, passengerId, attendance) }
            CarpoolStatus.IN_PROGRESS -> sync.onAttendanceChanged(trip, passengerId, attendance)
            else -> Unit
        }
    }

    /** 이름·출발 시각·요일은 서버 크루 설정으로 올린다 (차를 등록한 운전자만) */
    override suspend fun updateCrewInfo(info: CrewInfo) {
        local.updateCrewInfo(info)
        scope.launch(Dispatchers.IO) { sync.pushCrewInfo(info) }
    }

    /** 같은 날 한 번 더 운행: 앱 운행을 출발 전으로 돌리고 서버 일정·출석을 다시 받아 맞춘다 */
    override suspend fun prepareNextTrip() {
        local.prepareNextTrip()
        scope.launch(Dispatchers.IO) { sync.refreshCrew() }
    }

    override suspend fun createInvite(): CrewInvite = sync.createInvite()
}

/**
 * 앱 크루에 서버 크루를 덮는다. 서버 값을 아직 못 받았으면 앱 값 그대로.
 * 시작일은 서버에 따로 없어 가장 먼저 가입한 사람(= 크루를 만든 운전자)의 가입일로 본다.
 */
fun mergeServerCrew(local: Crew?, server: ServerCrew?, editedName: String?): Crew? {
    if (server == null) return local
    return Crew(
        id = server.crewId.toString(),
        name = editedName ?: server.name,
        startDate = server.members.minOfOrNull { it.joinedAt }?.toLocalDate() ?: local?.startDate ?: return local,
        days = server.departureDays.takeIf { it.isNotEmpty() } ?: local?.days ?: Crew.WEEKDAYS,
        members = server.members,
    )
}

/** 정산 설정은 기기에 두고, 계좌·충전 단가·전비가 바뀌면 서버에도 올린다 (운행 시작에 운전자 계좌가 필요하다) */
class ServerSettlementSettingsStore(
    private val local: SettlementSettingsStore,
    private val onChanged: () -> Unit,
) : SettlementSettingsStore by local {

    override suspend fun saveSettings(settings: SettlementSettings) {
        local.saveSettings(settings)
        onChanged()
    }
}
