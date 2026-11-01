package com.paircar.feature.navipoc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.core.model.GeoPoint
import com.paircar.core.notification.PickupNotifier
import com.paircar.data.mock.MockCarpoolData
import com.paircar.domain.model.DrivingState
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviDiagnosticCall
import com.paircar.domain.model.NaviError
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointSlot
import com.paircar.domain.repository.DrivingStateRepository
import com.paircar.domain.repository.NaviRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class NaviPocUiState(
    val provider: NaviProvider,
    val connection: NaviConnectionState = NaviConnectionState.Disconnected,
    val guidance: GuidanceState = GuidanceState(),
    val driving: DrivingState = DrivingState.Unavailable,
    val log: List<String> = emptyList(),
)

/** NaviHelper API 를 하나씩 호출해 보고 콜백을 로그로 확인하는 검증용 화면 */
class NaviPocViewModel(
    private val naviRepository: NaviRepository,
    private val drivingStateRepository: DrivingStateRepository,
    private val pickupNotifier: PickupNotifier,
) : ViewModel() {

    private val log = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<NaviPocUiState> = combine(
        naviRepository.connectionState,
        naviRepository.guidanceState,
        drivingStateRepository.state,
        log,
    ) { connection, guidance, driving, entries ->
        NaviPocUiState(naviRepository.provider, connection, guidance, driving, entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NaviPocUiState(naviRepository.provider))

    init {
        viewModelScope.launch {
            naviRepository.events.collect { append("EVENT $it") }
        }
    }

    fun connect() = call("connect") { naviRepository.connect(); null }
    fun disconnect() = call("disconnect") { naviRepository.disconnect(); null }

    fun requestRouteOfficialSample() = call("requestRoute(42dot)") {
        naviRepository.requestRoute(RouteRequest(OfficialSampleDestination, emptyList()))
    }

    fun requestRouteMockDestination() = call("requestRoute(양재사옥, poiId 없음)") {
        naviRepository.requestRoute(RouteRequest(MockCarpoolData.destination, emptyList()))
    }

    fun requestRouteWithWaypoints() = call("requestRouteWithWaypoints") {
        naviRepository.requestRoute(RouteRequest(OfficialSampleDestination, listOf(OfficialSampleWaypoint)))
    }

    fun addWaypoint() = call("addWaypoint(FIRST)") {
        naviRepository.addWaypoint(OfficialSampleWaypoint, WaypointSlot.FIRST)
    }

    fun cancelRoute() = call("cancelRoute") { naviRepository.cancelRoute() }
    fun runDiagnostic(target: NaviDiagnosticCall) = call(target.label) { naviRepository.runDiagnostic(target) }
    fun refresh() = call("getRouteStateInfo/getDestinationInfo/getWaypointInfo") { naviRepository.refreshRouteState() }
    fun showMap() = call("requestNavigationScreen(Foreground)") { naviRepository.showNavigationScreen(NaviScreen.MAP) }
    fun showOverview() = call("requestNavigationScreen(OverviewRoute)") {
        naviRepository.showNavigationScreen(NaviScreen.ROUTE_OVERVIEW)
    }

    fun postTestNotification() {
        val posted = pickupNotifier.post(PickupNotifier.TEST_ID, "다음 픽업 · 김민지", "판교역 1번 출구 · 약 5분")
        append(if (posted) "NOTIFY 테스트 알림 게시 (Heads-up 표시 여부를 화면에서 확인)" else "NOTIFY 알림 권한 없음")
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        append("NOTIFY POST_NOTIFICATIONS 권한 ${if (granted) "허용" else "거부"}")
        if (granted) postTestNotification()
    }

    fun canNotify() = pickupNotifier.canNotify()

    fun setSimulatedDriving(driving: Boolean?) {
        drivingStateRepository.setSimulatedDriving(driving)
        append("UXR 시뮬레이션 = ${driving ?: "해제(차량 값 사용)"}")
    }

    private fun call(name: String, block: () -> NaviCommandResult?) {
        val result = runCatching(block).getOrElse { NaviCommandResult.Rejected(NaviError.UNKNOWN, it.toString()) }
        append("CALL $name → ${result ?: "OK"}")
    }

    private fun append(line: String) {
        val time = LocalTime.now().format(TimeFormat)
        log.update { (listOf("$time  $line") + it).take(MAX_LOG) }
    }

    companion object {
        private const val MAX_LOG = 50
        private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** 공식 문서 샘플 값(POI ID 포함): NaviHelper/destinations */
        val OfficialSampleDestination = Place(
            name = "42dot",
            address = "서울시 강남구 남부순환로 2621",
            point = GeoPoint(37.48544722, 127.03636666),
            poiId = "36khp37hh8gqcm5nqhe5",
        )

        /** 공식 문서 샘플 값(POI ID 포함): NaviHelper/waypoint-settings */
        val OfficialSampleWaypoint = Place(
            name = "소프트웨어드림센터",
            address = "경기도 성남시 수정구 창업로40번길 6",
            point = GeoPoint(37.41275833, 127.09481944),
            poiId = "36khp2hdj2cfcm5nqhe3",
        )

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                NaviPocViewModel(container.naviRepository, container.drivingStateRepository, container.pickupNotifier)
            }
        }
    }
}
