package com.paircar.domain.repository

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.DrivingState
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviDiagnosticCall
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointSlot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 카풀 일정/매칭/크루 데이터. 현재는 Mock, 이후 REST API 구현으로 교체한다. */
interface CarpoolRepository {
    val todayTrip: StateFlow<CarpoolTrip?>
    val crew: StateFlow<Crew?>

    suspend fun refresh()
    suspend fun updateMatchStatus(passengerId: String, status: MatchStatus)
    suspend fun updateBoardingStatus(passengerId: String, status: BoardingStatus)
    suspend fun updateTripStatus(status: CarpoolStatus)

    /** 크루 초대 QR 에 넣을 모바일웹 주소를 발급한다. */
    suspend fun createInvite(): CrewInvite
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
