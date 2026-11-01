package com.paircar.vehicle.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.GuidanceStatus
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviDiagnosticCall
import com.paircar.domain.model.NaviError
import com.paircar.domain.model.NaviEvent
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.model.Place
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.model.WaypointProgress
import com.paircar.domain.model.WaypointSlot
import com.paircar.domain.repository.NaviRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos NaviHelper SDK(ai.pleos.playground:NaviHelper:3.0.5) 연동 구현.
 *
 * 시그니처 근거: 공식 문서
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro
 * 및 NaviHelper-3.0.5.aar / navi-data-3.0.4.aar 의 공개 API(javap 확인).
 *
 * - 생성 → initialize(onConnected, onFailed) → onConnected 이후 addListener 및 API 호출
 * - 해제: removeListener → release(). release 후 재사용하려면 새 객체를 만들어 다시 initialize 한다.
 * - 요청 API는 반환값이 없고 결과는 NaviHelperEventListener 콜백으로 온다.
 * - 콜백 스레드는 문서에 명시되지 않아 StateFlow/SharedFlow 로만 전달한다(스레드 안전).
 */
class PleosNaviRepository(
    context: Context,
) : NaviRepository {

    private val appContext = context.applicationContext

    override val provider = NaviProvider.PLEOS

    private val _connectionState = MutableStateFlow<NaviConnectionState>(NaviConnectionState.Disconnected)
    override val connectionState: StateFlow<NaviConnectionState> = _connectionState.asStateFlow()

    private val _guidanceState = MutableStateFlow(GuidanceState())
    override val guidanceState: StateFlow<GuidanceState> = _guidanceState.asStateFlow()

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    override val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    @Volatile
    private var naviHelper: NaviHelper? = null

    @Volatile
    private var connected = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            // 에뮬레이터 확인: 경유지가 있으면 name/distance/duration 은 첫 경유지까지의 구간 값이다.
            // 목적지 값은 onDestinationInfo 로 받는다.
            Log.d(TAG, "onRouteStarted $routeStartInfo")
            _guidanceState.update { it.copy(status = GuidanceStatus.GUIDING) }
            emit(NaviEvent.RouteStarted(routeStartInfo.distance, routeStartInfo.duration))
            requestInfoUpdates()
        }

        override fun onRoutingCompleted() {
            emit(NaviEvent.RoutingCompleted)
            requestInfoUpdates()
        }

        // SDK 3.0.5 문서상 onRoutingCompleted 로 대체되었지만, Pleos Emulator v2.1.0 의
        // 내비 서비스(3.0.4)는 경로 탐색 완료 시 onRouteEnded 를 보낸다(logcat 확인). 둘 다 처리한다.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onRouteEnded() {
            emit(NaviEvent.RoutingCompleted)
            requestInfoUpdates()
        }

        override fun onReRouteEnded() {
            emit(NaviEvent.ReRouted)
            requestInfoUpdates()
        }

        override fun onRouteCancelled() {
            _guidanceState.value = GuidanceState(status = GuidanceStatus.CANCELLED)
            emit(NaviEvent.RouteCancelled)
        }

        override fun onDestinationChanged() = requestInfoUpdates()

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) =
            requestInfoUpdates()

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            emit(NaviEvent.WaypointArrived(waypointArrivedInfo.name))
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _guidanceState.update {
                it.copy(status = GuidanceStatus.ARRIVED, remainDistanceMeters = 0, remainTimeSeconds = 0)
            }
            emit(NaviEvent.DestinationArrived(destinationArrivedInfo.name))
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.d(TAG, "onDestinationInfo $destinationInfo")
            _guidanceState.update {
                it.copy(
                    destinationName = destinationInfo.name,
                    remainDistanceMeters = destinationInfo.remainDistance,
                    remainTimeSeconds = destinationInfo.remainTime,
                )
            }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            Log.d(TAG, "onWayPointInfo $waypointInfoList")
            _guidanceState.update { state ->
                state.copy(waypoints = waypointInfoList.map { WaypointProgress(it.name, it.remainDistance, it.remainTime) })
            }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            // 에뮬레이터 확인: 경로 안내 중에도 isRouting=false, naviMode=ROUTE_GUIDING 으로 온다.
            Log.d(TAG, "onRouteStateInfo $routeStateInfo")
            val guiding = routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING ||
                routeStateInfo.naviMode == NaviMode.SIMULATION_GUIDING
            if (guiding) {
                _guidanceState.update { it.copy(status = GuidanceStatus.GUIDING) }
            }
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            // 값: INACTIVE, INITIALIZING, FREE_DRIVE, ROUTE_DRIVE
            Log.d(TAG, "onNavigationStatus $status")
            emit(NaviEvent.Info("내비 상태: $status"))
            when (status) {
                NavigationStatus.ROUTE_DRIVE -> {
                    _guidanceState.update { it.copy(status = GuidanceStatus.GUIDING) }
                    requestInfoUpdates()
                }
                NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> _guidanceState.update {
                    if (it.status == GuidanceStatus.GUIDING) it.copy(status = GuidanceStatus.IDLE) else it
                }
                NavigationStatus.INITIALIZING -> Unit
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            emit(NaviEvent.Error(errorCode.toDomain(), errorCode.name))
        }

        // 아래는 앱 기능에서 아직 쓰지 않는 콜백. PoC 화면에서 값 확인용으로 기록만 한다.
        override fun onRouteOptionChanged(routeOption: RouteOption) = info("onRouteOptionChanged", routeOption)
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = info("onTBTInfo", tbtInfoList)
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _currentLocation.value = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
            info("onCurrentLocationInfo", currentLocationInfo)
        }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) =
            info("onRecentDestinationInfo", recentDestinationInfoList)
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = info("onBookmarkInfo", bookmarkInfoList)
        override fun onBookMarkChanged() = info("onBookMarkChanged", "")
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) =
            info("onChargerOperatorInfo", chargerOperatorList)
        override fun onTripInfo(tripInfo: TripInfo) = info("onTripInfo", tripInfo)
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = info("onDrivingInfo", drivingInfo)
        // 약 1초마다 반복 수신되므로(에뮬레이터 확인) 화면 로그에는 올리지 않는다.
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            Log.v(TAG, "onCurrentRoadSpeedLimit $speedLimit")
        }
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = info("onCameraAlert", cameraAlertList)
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = info("onCautionPointInfo", cautionPointList)

        private fun info(callback: String, value: Any) {
            Log.d(TAG, "$callback $value")
            emit(NaviEvent.Info("$callback ${value.toString().take(300)}"))
        }
    }

    override fun connect() {
        if (naviHelper != null) return
        _connectionState.value = NaviConnectionState.Connecting
        val helper = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            fail("NaviHelper 생성 실패", t)
            return
        }
        naviHelper = helper
        try {
            helper.initialize(
                onConnected = {
                    // initialize 도중 disconnect() 된 경우 이미 release 된 객체이므로 무시한다.
                    if (naviHelper === helper) {
                        helper.addListener(listener)
                        connected = true
                        _connectionState.value = NaviConnectionState.Connected(NaviProvider.PLEOS)
                        emit(NaviEvent.Info("NaviHelper 연결됨"))
                        requestInfoUpdates()
                    }
                },
                onFailed = { throwable ->
                    if (naviHelper === helper) {
                        connected = false
                        fail("NaviHelper 연결 실패", throwable)
                    }
                },
            )
        } catch (t: Throwable) {
            naviHelper = null
            fail("NaviHelper initialize 예외", t)
        }
    }

    override fun disconnect() {
        val helper = naviHelper ?: return
        naviHelper = null
        connected = false
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
            .onFailure { Log.w(TAG, "release failed", it) }
        _connectionState.value = NaviConnectionState.Disconnected
    }

    override fun requestRoute(request: RouteRequest): NaviCommandResult = command { helper ->
        val destination = request.destination.toRouteInfo()
        if (request.waypoints.isEmpty()) {
            helper.requestRoute(destination)
        } else {
            helper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    destination = destination,
                    waypoints = request.waypoints.mapIndexed { index, place ->
                        place.toRequestWaypointInfo(WaypointSlot.entries[index].toSdk())
                    },
                ),
            )
        }
        _guidanceState.update { it.copy(destinationName = request.destination.name) }
    }

    override fun addWaypoint(place: Place, slot: WaypointSlot): NaviCommandResult = command { helper ->
        helper.addWaypoint(place.toRequestWaypointInfo(slot.toSdk()))
    }

    override fun cancelRoute(): NaviCommandResult = command { it.cancelRoute() }

    override fun refreshRouteState(): NaviCommandResult = command {
        it.getRouteStateInfo()
        it.getDestinationInfo()
        it.getWaypointInfo()
        it.getCurrentLocationInfo()
    }

    override fun showNavigationScreen(screen: NaviScreen): NaviCommandResult = command { helper ->
        val request = when (screen) {
            NaviScreen.MAP -> NavigationScreenRequest.Foreground
            NaviScreen.ROUTE_OVERVIEW -> NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo())
        }
        helper.requestNavigationScreen(request)
    }

    override fun runDiagnostic(call: NaviDiagnosticCall): NaviCommandResult = command { helper ->
        when (call) {
            NaviDiagnosticCall.REMOVE_WAYPOINT_FIRST -> helper.removeWaypoint(WaypointIndex.FIRST)
            NaviDiagnosticCall.CHANGE_ROUTE_OPTION -> helper.changeRouteOption(RouteOption.SHORT_TIME)
            NaviDiagnosticCall.REQUEST_REROUTE -> helper.requestReRoute()
            NaviDiagnosticCall.GET_TBT -> helper.getTBTInfo()
            NaviDiagnosticCall.GET_CURRENT_LOCATION -> helper.getCurrentLocationInfo()
            NaviDiagnosticCall.GET_RECENT_DESTINATIONS -> helper.getRecentDestinationInfo()
            NaviDiagnosticCall.GET_BOOKMARKS -> helper.getBookmarkInfo()
            NaviDiagnosticCall.GET_CHARGER_OPERATORS -> helper.getChargerOperatorInfo()
            NaviDiagnosticCall.REQUEST_TRIP -> helper.requestTrip(
                TripRequest(
                    latitude = 37.4632,
                    longitude = 127.0423,
                    poiId = "",
                    poiName = "현대자동차 양재사옥",
                    poiSubId = "0",
                ),
            )
            NaviDiagnosticCall.SCREEN_SEARCH_PLACE ->
                helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace("판교역"))
            NaviDiagnosticCall.SCREEN_ROUTE_OPTIONS ->
                helper.requestNavigationScreen(NavigationScreenRequest.RouteOptionList)
            NaviDiagnosticCall.SCREEN_EDIT_ROUTE ->
                helper.requestNavigationScreen(NavigationScreenRequest.EditRoute)
            NaviDiagnosticCall.SCREEN_RECENT_DESTINATIONS ->
                helper.requestNavigationScreen(NavigationScreenRequest.RecentDestinations)
            NaviDiagnosticCall.SCREEN_CURRENT_LOCATION ->
                helper.requestNavigationScreen(NavigationScreenRequest.MoveToCurrentLocationOnMap)
        }
    }

    private fun requestInfoUpdates() {
        val helper = naviHelper ?: return
        if (!connected) return
        runCatching {
            helper.getRouteStateInfo()
            helper.getDestinationInfo()
            helper.getWaypointInfo()
        }.onFailure { Log.w(TAG, "info request failed", it) }
    }

    private inline fun command(block: (NaviHelper) -> Unit): NaviCommandResult {
        val helper = naviHelper
        if (helper == null || !connected) {
            return NaviCommandResult.Rejected(NaviError.NOT_CONNECTED, "내비게이션에 연결되지 않았습니다")
        }
        return try {
            block(helper)
            NaviCommandResult.Requested
        } catch (e: SecurityException) {
            Log.w(TAG, "permission denied", e)
            NaviCommandResult.Rejected(NaviError.PERMISSION_DENIED, e.message ?: "pleos.car.permission 거부")
        } catch (t: Throwable) {
            Log.w(TAG, "command failed", t)
            NaviCommandResult.Rejected(NaviError.UNKNOWN, t.message ?: t::class.java.simpleName)
        }
    }

    private fun fail(message: String, t: Throwable) {
        Log.w(TAG, message, t)
        val reason = "$message: ${t.message ?: t::class.java.simpleName}"
        _connectionState.value = NaviConnectionState.Failed(reason)
        emit(NaviEvent.Error(NaviError.NOT_CONNECTED, reason))
    }

    private fun emit(event: NaviEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val TAG = "PleosNaviRepository"
    }
}

private fun Place.toRouteInfo() = RouteInfo(
    latitude = point.latitude,
    longitude = point.longitude,
    displayLatitude = null,
    displayLongitude = null,
    poiName = name,
    // POI ID 없이 좌표만으로 경로 요청이 되는지는 미검증 (공식 샘플은 모두 poiId 지정)
    poiId = poiId.orEmpty(),
    poiSubId = "0",
    address = address,
    routeOption = RouteOption.RECOMMENDED,
)

private fun Place.toRequestWaypointInfo(index: WaypointIndex) = RequestWaypointInfo(
    latitude = point.latitude,
    longitude = point.longitude,
    poiName = name,
    waypointIndex = index,
    poiId = poiId.orEmpty(),
    address = address,
    poiSubId = "0",
)

private fun WaypointSlot.toSdk(): WaypointIndex = when (this) {
    WaypointSlot.FIRST -> WaypointIndex.FIRST
    WaypointSlot.SECOND -> WaypointIndex.SECOND
}

private fun NaviErrorCode.toDomain(): NaviError = when (this) {
    NaviErrorCode.CAUSE_LOW_BATTERY -> NaviError.LOW_BATTERY
    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> NaviError.ROUTE_TOO_FAR
    NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> NaviError.ROUTE_TOO_SHORT
    NaviErrorCode.CAUSE_SEARCH_ROUTE -> NaviError.ROUTE_SEARCH_FAILED
    NaviErrorCode.CAUSE_SEARCH_TRIP -> NaviError.TRIP_SEARCH_FAILED
}
