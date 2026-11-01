package com.paircar.domain.model

/** 어떤 구현이 내비 연동을 담당하는지 */
enum class NaviProvider { PLEOS, MOCK }

sealed interface NaviConnectionState {
    data object Disconnected : NaviConnectionState
    data object Connecting : NaviConnectionState
    data class Connected(val provider: NaviProvider) : NaviConnectionState
    data class Failed(val reason: String) : NaviConnectionState
}

enum class GuidanceStatus { IDLE, GUIDING, ARRIVED, CANCELLED }

data class WaypointProgress(
    val name: String,
    val remainDistanceMeters: Int?,
    val remainTimeSeconds: Int?,
)

/**
 * 내비 경로 안내 상태. Pleos 구현에서는 NaviHelper 콜백 값으로 채워진다.
 * 거리/시간 단위(미터/초)는 공식 문서에 명시되어 있지 않아 에뮬레이터 검증이 필요하다.
 */
data class GuidanceState(
    val status: GuidanceStatus = GuidanceStatus.IDLE,
    val destinationName: String? = null,
    val remainDistanceMeters: Int? = null,
    val remainTimeSeconds: Int? = null,
    val waypoints: List<WaypointProgress> = emptyList(),
)

/** NaviHelper 는 경유지를 최대 2개(FIRST, SECOND)까지 지원한다. */
enum class WaypointSlot { FIRST, SECOND }

enum class NaviScreen { MAP, ROUTE_OVERVIEW }

/** 개발용(PoC 화면): 앱 기능에서 아직 쓰지 않는 NaviHelper API 를 하나씩 호출해 동작을 확인한다. */
enum class NaviDiagnosticCall(val label: String) {
    REMOVE_WAYPOINT_FIRST("removeWaypoint(FIRST)"),
    CHANGE_ROUTE_OPTION("changeRouteOption(SHORT_TIME)"),
    REQUEST_REROUTE("requestReRoute()"),
    GET_TBT("getTBTInfo()"),
    GET_CURRENT_LOCATION("getCurrentLocationInfo()"),
    GET_RECENT_DESTINATIONS("getRecentDestinationInfo()"),
    GET_BOOKMARKS("getBookmarkInfo()"),
    GET_CHARGER_OPERATORS("getChargerOperatorInfo()"),
    REQUEST_TRIP("requestTrip(양재사옥)"),
    SCREEN_SEARCH_PLACE("화면: SearchPlace(판교역)"),
    SCREEN_ROUTE_OPTIONS("화면: RouteOptionList"),
    SCREEN_EDIT_ROUTE("화면: EditRoute"),
    SCREEN_RECENT_DESTINATIONS("화면: RecentDestinations"),
    SCREEN_CURRENT_LOCATION("화면: MoveToCurrentLocationOnMap"),
}

data class RouteRequest(
    val destination: Place,
    /** 순서대로 경유. 최대 [MAX_WAYPOINTS]개 */
    val waypoints: List<Place>,
) {
    init {
        require(waypoints.size <= MAX_WAYPOINTS) { "NaviHelper supports up to $MAX_WAYPOINTS waypoints" }
    }

    companion object {
        const val MAX_WAYPOINTS = 2
    }
}

enum class NaviError {
    LOW_BATTERY,
    ROUTE_TOO_FAR,
    ROUTE_TOO_SHORT,
    ROUTE_SEARCH_FAILED,
    TRIP_SEARCH_FAILED,
    NOT_CONNECTED,
    PERMISSION_DENIED,
    UNKNOWN,
}

/**
 * NaviHelper 요청 API는 반환값 없이 결과를 콜백으로 전달한다.
 * 따라서 [Requested]는 "요청 전달 성공"이며, 실제 결과는 [NaviEvent]로 확인한다.
 */
sealed interface NaviCommandResult {
    data object Requested : NaviCommandResult
    data class Rejected(val error: NaviError, val message: String) : NaviCommandResult
}

sealed interface NaviEvent {
    data class RouteStarted(val distanceMeters: Int, val durationSeconds: Int) : NaviEvent
    data object RoutingCompleted : NaviEvent
    data object RouteCancelled : NaviEvent
    data object ReRouted : NaviEvent
    data class WaypointArrived(val name: String) : NaviEvent
    data class DestinationArrived(val name: String) : NaviEvent
    data class Error(val error: NaviError, val message: String) : NaviEvent
    data class Info(val message: String) : NaviEvent
}
