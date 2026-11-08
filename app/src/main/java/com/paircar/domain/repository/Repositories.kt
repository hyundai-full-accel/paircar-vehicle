package com.paircar.domain.repository

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInfo
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.CrewSetup
import com.paircar.domain.model.CrewSetupState
import com.paircar.domain.model.DrivingState
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviDiagnosticCall
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.SettlementRequest
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripSession
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.model.WaypointSlot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.YearMonth

/** 카풀 일정/매칭/크루 데이터. 현재는 Mock, 이후 REST API 구현으로 교체한다. */
interface CarpoolRepository {
    val todayTrip: StateFlow<CarpoolTrip?>
    val crew: StateFlow<Crew?>

    /** 크루를 만들어야 하는지 (오늘의 카풀·내 크루가 비어 있을 때 무엇을 보여줄지) */
    val setupState: StateFlow<CrewSetupState>

    /**
     * 크루를 만들고 반복 운행 일정(출발 시각·요일·출발지·도착지)을 정한다.
     * 이미 크루가 있으면(일정만 없을 때) 그 크루의 일정을 채운다. 실패하면 예외.
     */
    suspend fun setupCrew(setup: CrewSetup)

    suspend fun refresh()
    suspend fun updateMatchStatus(passengerId: String, status: MatchStatus)
    suspend fun updateBoardingStatus(passengerId: String, status: BoardingStatus)
    suspend fun updateTripStatus(status: CarpoolStatus)

    /** 오늘 운행 참석 여부 (불참이면 경유지에서 빠진다) */
    suspend fun updateAttendance(passengerId: String, attendance: Attendance)

    /** 동승자가 오늘 탈 지점을 바꿨다 (동승자 웹 → 서버 → 실시간 알림). 바뀌기 전 지점은 경유지 변경 알림에 쓴다 */
    suspend fun updatePickup(passengerId: String, pickup: Place)

    /**
     * 서버 크루 일정·출석으로 만든 오늘 운행으로 바꾼다. 출발 전(SCHEDULED)일 때만 바꾸고,
     * 같은 동승자의 탑승 상태는 이어간다. 출발 뒤에는 진행 중인 운행을 건드리지 않는다.
     */
    suspend fun applySchedule(trip: CarpoolTrip)

    /**
     * 끝났거나 취소한 오늘 운행을 다시 출발 전으로 돌린다 (퇴근길 등 같은 날 한 번 더 운행).
     * 탑승 상태는 처음으로 돌리고 참석 여부·탈 곳은 그대로 둔다.
     */
    suspend fun prepareNextTrip()

    /** 크루 이름·출발 시각·반복 요일 */
    suspend fun updateCrewInfo(info: CrewInfo)

    /** 크루 초대 QR 에 넣을 모바일웹 주소를 발급한다. */
    suspend fun createInvite(): CrewInvite
}

/** 월별 정산. 구현: data.server.ServerSettlementRepository(서버 월 영수증), data.mock.MockSettlementRepository */
interface SettlementRepository {
    /** 정산 내역이 있는 달, 최근 달이 먼저 */
    val settlements: StateFlow<List<MonthlySettlement>>

    /** true 면 정산 요청을 서버가 영수증으로 발행한다 ([issue]). false 면 기기에만 기록한다 */
    val issuesOnServer: Boolean
        get() = false

    suspend fun refresh() {}

    /** 이 달을 지금 정산 요청할 수 있는지 (서버는 끝난 달만 발행한다) */
    fun canIssue(month: YearMonth): Boolean = true

    /** 고른 달의 영수증을 서버에서 발행한다 (발행 뒤 금액은 바뀌지 않는다) */
    suspend fun issue(month: YearMonth) {}
}

/**
 * 운전자가 정한 정산 설정(수취 계좌·비용 기준·단가)과 정산 요청 기록.
 * 정산 서버가 없어 기기에 둔다. 계좌번호는 암호화해 저장한다.
 */
interface SettlementSettingsStore {
    val settings: StateFlow<SettlementSettings>
    val requests: StateFlow<List<SettlementRequest>>

    suspend fun saveSettings(settings: SettlementSettings)
    suspend fun saveRequest(request: SettlementRequest)
}

/** 끝난 운행 기록. 정산 서버가 없어 기기에 저장한다. */
interface TripRecordRepository {
    /** 최근 운행이 먼저 */
    val records: StateFlow<List<TripRecord>>

    suspend fun save(record: TripRecord)
}

/** 운행 기록 시작값 보관. 앱 프로세스가 끝나도 이어서 기록할 수 있게 한다. */
interface TripSessionStore {
    fun loadSession(): TripSession?
    fun saveSession(session: TripSession?)
}

/**
 * 차량 전비·주행거리 조회. 구현: vehicle.energy.PleosVehicleEnergyRepository(Vehicle SDK).
 * 차량이 아니거나 값을 못 읽으면 해당 값이 null 이다.
 */
interface VehicleEnergyRepository {
    suspend fun read(): VehicleEnergy
}

/**
 * 차량 내비게이션 연동. UI 는 이 인터페이스만 알고 Pleos SDK 타입을 직접 참조하지 않는다.
 * 구현: vehicle.navi.PleosNaviRepository(NaviHelper SDK), vehicle.navi.MockNaviRepository
 */
interface NaviRepository {
    val provider: NaviProvider
    val connectionState: StateFlow<NaviConnectionState>
    val guidanceState: StateFlow<GuidanceState>
    /** 차량 현재 위치. Pleos: getCurrentLocationInfo 결과 ([refreshRouteState] 시 갱신) */
    val currentLocation: StateFlow<GeoPoint?>
    val events: SharedFlow<NaviEvent>

    fun connect()
    fun disconnect()

    fun requestRoute(request: RouteRequest): NaviCommandResult
    fun addWaypoint(place: Place, slot: WaypointSlot): NaviCommandResult
    fun cancelRoute(): NaviCommandResult
    /** 경로 상태/목적지/경유지 정보를 다시 요청한다. 결과는 [guidanceState]에 반영된다. */
    fun refreshRouteState(): NaviCommandResult
    fun showNavigationScreen(screen: NaviScreen): NaviCommandResult

    /** 개발용 API 점검. 결과 콜백은 [events] 에 Info 로 전달된다. */
    fun runDiagnostic(call: NaviDiagnosticCall): NaviCommandResult
}

interface DrivingStateRepository {
    val state: StateFlow<DrivingState>

    fun start()
    fun stop()

    /** 개발용: null 이면 차량 값 사용, true/false 면 주행/정차를 강제한다. */
    fun setSimulatedDriving(driving: Boolean?)
}
