package com.paircar.feature.carpool

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.core.model.GeoPoint
import com.paircar.domain.repository.RoutePreview
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolAction
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.DrivingPolicy
import com.paircar.domain.model.DrivingState
import com.paircar.domain.model.GuidanceState
import com.paircar.domain.model.NaviCommandResult
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviError
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.DrivingStateRepository
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import com.paircar.domain.model.estimateRideWon
import com.paircar.domain.usecase.CarpoolRouteSync
import com.paircar.domain.usecase.FinishCarpoolUseCase
import com.paircar.domain.usecase.StartCarpoolGuidanceUseCase
import com.paircar.domain.usecase.TripFinish
import com.paircar.domain.usecase.TripRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CarpoolUiState(
    val trip: CarpoolTrip? = null,
    val naviProvider: NaviProvider = NaviProvider.MOCK,
    val naviConnection: NaviConnectionState = NaviConnectionState.Disconnected,
    val guidance: GuidanceState = GuidanceState(),
    val driving: DrivingState = DrivingState.Unavailable,
    val message: String? = null,
    val currentLocation: GeoPoint? = null,
    val routePreview: RoutePreview? = null,
    /** 경유지 없이 바로 갈 때 (경로 상세의 비교용) */
    val directPreview: RoutePreview? = null,
    val crew: Crew? = null,
    /** 방금 끝낸 운행의 기록 처리 결과 (운행 종료 화면) */
    val tripFinish: TripFinish? = null,
    /** 첫 동승자의 이번 운행 예상 분담금 (원). 경로를 못 구했으면 null */
    val estimatedShareWon: Long? = null,
) {
    fun isAllowed(action: CarpoolAction) = DrivingPolicy.isAllowed(action, driving)
}

/** 홈 / 카풀 상세 / 주행 중 화면이 공유하는 카풀 세션 상태 */
class CarpoolViewModel(
    private val carpoolRepository: CarpoolRepository,
    private val naviRepository: NaviRepository,
    private val drivingStateRepository: DrivingStateRepository,
    private val startCarpoolGuidance: StartCarpoolGuidanceUseCase,
    private val routeSync: CarpoolRouteSync,
    private val finishCarpool: FinishCarpoolUseCase,
    tripRecorder: TripRecorder,
    private val routePreviewRepository: RoutePreviewRepository,
    private val settlementRepository: SettlementRepository,
    private val energyRepository: VehicleEnergyRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val routePreview = MutableStateFlow<RoutePreview?>(null)
    private val directPreview = MutableStateFlow<RoutePreview?>(null)
    /** 첫 픽업 지점 → 목적지 (동승 구간) */
    private val sharedPreview = MutableStateFlow<RoutePreview?>(null)
    private val estimatedShare = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<CarpoolUiState> = combine(
        combine(
            carpoolRepository.todayTrip,
            naviRepository.connectionState,
            naviRepository.guidanceState,
            drivingStateRepository.state,
            message,
        ) { trip, connection, guidance, driving, msg ->
            CarpoolUiState(trip, naviRepository.provider, connection, guidance, driving, msg)
        },
        naviRepository.currentLocation,
        routePreview,
        directPreview,
        combine(carpoolRepository.crew, tripRecorder.lastFinish, estimatedShare, ::Triple),
    ) { state, location, preview, direct, (crew, finish, share) ->
        state.copy(
            currentLocation = location,
            routePreview = preview,
            directPreview = direct,
            crew = crew,
            tripFinish = finish,
            estimatedShareWon = share,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CarpoolUiState(
            trip = carpoolRepository.todayTrip.value,
            naviProvider = naviRepository.provider,
            crew = carpoolRepository.crew.value,
        ),
    )

    init {
        // 출발지 → 오늘 참석하는 동승자 픽업(시간순) → 목적지. 정류장이 바뀔 때만 다시 구한다.
        previewWhenChanged(routePreview) { it.planStops() }
        previewWhenChanged(directPreview) { it.directStops() }
        previewWhenChanged(sharedPreview) { trip ->
            trip.pickupsInOrder.firstOrNull()?.let { listOf(it.pickup.point, trip.destination.point) }
        }
        estimateShare()
    }

    /**
     * 예상 분담금 = (동승 구간 ÷ 인원 + 우회거리) ÷ 전비 × 충전 단가, 100원 올림 (월 정산과 같은 기준).
     * 전비는 차량(Vehicle SDK) 값이 있으면 그 값, 없으면 정산 입력값.
     */
    private fun estimateShare() {
        viewModelScope.launch {
            val energy = energyRepository.read()
            combine(carpoolRepository.todayTrip, routePreview, directPreview, sharedPreview, settlementRepository.settlements) { trip, planned, direct, shared, settlements ->
                val policy = settlements.firstOrNull()?.policy?.withVehicle(energy)
                val routes = listOfNotNull(planned, direct, shared)
                if (trip == null || policy == null || routes.size < 3 || routes.any { it.isApproximate }) return@combine null
                val detourKm = ((planned!!.distanceMeters - direct!!.distanceMeters) / 1000.0).coerceAtLeast(0.0)
                policy.estimateRideWon(shared!!.distanceMeters / 1000.0, headcount = 1 + trip.pickupsInOrder.size, detourKm = detourKm)
            }.collect { estimatedShare.value = it }
        }
    }

    private fun previewWhenChanged(target: MutableStateFlow<RoutePreview?>, stopsOf: (CarpoolTrip) -> List<GeoPoint>?) {
        viewModelScope.launch {
            carpoolRepository.todayTrip
                .map { trip -> trip?.let(stopsOf) }
                .distinctUntilChanged()
                .collectLatest { stops ->
                    target.value = stops?.let { routePreviewRepository.preview(it) }
                }
        }
    }

    /** 내비 남은 거리/시간·현재 위치를 다시 요청한다 (주행 화면이 보이는 동안 주기적으로 호출). */
    fun refreshNavi() {
        naviRepository.refreshRouteState()
    }

    /** 남은 픽업 지점을 경유지로 내비 경로를 요청하고, 성공하면 운행 중으로 바꾼다. */
    fun startGuidance(onRequested: () -> Unit = {}) {
        val trip = carpoolRepository.todayTrip.value ?: return
        if (!guard(CarpoolAction.START_GUIDANCE)) return
        viewModelScope.launch {
            when (val result = startCarpoolGuidance(trip)) {
                NaviCommandResult.Requested -> onRequested()
                is NaviCommandResult.Rejected -> message.value = result.toUserMessage()
            }
        }
    }

    fun openNavigation(screen: NaviScreen = NaviScreen.MAP) {
        val result = naviRepository.showNavigationScreen(screen)
        if (result is NaviCommandResult.Rejected) message.value = result.toUserMessage()
    }

    fun confirmBoarding(passengerId: String) = updateBoarding(passengerId, BoardingStatus.BOARDED)

    fun markNoShow(passengerId: String) = updateBoarding(passengerId, BoardingStatus.NO_SHOW)

    /** 내비 도착 이벤트 없이 운전자가 운행을 끝낸다: 안내 종료 → 하차 처리 → 기록 저장 */
    fun finishTrip() {
        if (!guard(CarpoolAction.FINISH_TRIP)) return
        viewModelScope.launch {
            naviRepository.cancelRoute()
            finishCarpool(arrivedByNavi = false)
        }
    }

    fun cancelCarpool() {
        if (!guard(CarpoolAction.CANCEL_CARPOOL)) return
        viewModelScope.launch {
            naviRepository.cancelRoute()
            carpoolRepository.updateTripStatus(CarpoolStatus.CANCELLED)
        }
    }

    fun showMessage(text: String) {
        message.value = text
    }

    fun dismissMessage() {
        message.value = null
    }

    /** 탑승 상태를 바꾸고, 남은 경유지가 달라졌으면 내비 경로를 다시 요청한다. */
    private fun updateBoarding(passengerId: String, status: BoardingStatus) {
        if (!guard(CarpoolAction.CONFIRM_BOARDING)) return
        viewModelScope.launch {
            carpoolRepository.updateBoardingStatus(passengerId, status)
            val trip = carpoolRepository.todayTrip.value ?: return@launch
            when (val result = routeSync.sync(trip)) {
                NaviCommandResult.Requested -> message.value = "남은 경유지로 내비 경로를 다시 요청했어요"
                is NaviCommandResult.Rejected -> message.value = result.toUserMessage()
                null -> Unit
            }
        }
    }

    /** UI 에서 버튼을 숨기더라도 상태 전환 경합에 대비해 한 번 더 확인한다. */
    private fun guard(action: CarpoolAction): Boolean {
        val allowed = DrivingPolicy.isAllowed(action, drivingStateRepository.state.value)
        if (!allowed) message.value = "정차 중에만 할 수 있어요"
        return allowed
    }

    private fun NaviCommandResult.Rejected.toUserMessage(): String = when (error) {
        NaviError.NOT_CONNECTED -> "차량 내비게이션에 연결할 수 없어요. 내비에서 목적지를 직접 설정해 주세요."
        NaviError.PERMISSION_DENIED -> "내비 연동 권한이 없어요 ($message)"
        else -> "내비 요청 실패: $message"
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                CarpoolViewModel(
                    container.carpoolRepository,
                    container.naviRepository,
                    container.drivingStateRepository,
                    container.startCarpoolGuidance,
                    container.routeSync,
                    container.finishCarpool,
                    container.tripRecorder,
                    container.routePreviewRepository,
                    container.settlementRepository,
                    container.vehicleEnergyRepository,
                )
            }
        }
    }
}
