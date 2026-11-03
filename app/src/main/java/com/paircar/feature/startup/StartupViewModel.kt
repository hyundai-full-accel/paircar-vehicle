package com.paircar.feature.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.core.common.VehicleCheckStore
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.NaviRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/** 시작 화면에서 차례로 확인하는 단계 */
enum class StartupStep { CONNECT, CHANGES, STOPS, FEATURES }

/** 지원 기능 한 줄. [verified] 가 null 이면 아직 확인 중, false 면 확인할 수 있는 차량 API 가 없음 */
data class StartupFeature(val label: String, val verified: Boolean?)

data class StartupUiState(
    val vehicleName: String = "",
    val provider: NaviProvider = NaviProvider.MOCK,
    val connection: NaviConnectionState = NaviConnectionState.Disconnected,
    val lastCheckedAt: LocalDateTime? = null,
    val step: StartupStep = StartupStep.CONNECT,
    /** 이번 확인에서 반영한 변경사항(불참 등) 수. 동기화 전이면 null */
    val changeCount: Int? = null,
    val hasLocation: Boolean = false,
    val failed: Boolean = false,
    val done: Boolean = false,
) {
    val connected: Boolean
        get() = connection is NaviConnectionState.Connected

    fun isFinished(step: StartupStep): Boolean = done || step < this.step

    val features: List<StartupFeature>
        get() {
            val checked = done || failed
            fun status(ok: Boolean) = if (ok) true else if (checked) false else null
            return listOf(
                // getCurrentLocationInfo 응답이 왔는지
                StartupFeature("차량 위치 및 주행 거리", status(hasLocation)),
                // NaviHelper 연결
                StartupFeature("내비게이션 경로 연동", status(connected)),
                // 아래 둘은 아직 확인할 수 있는 차량 API 가 없다
                StartupFeature("좌석 / 안전벨트 / 도어 확인", if (checked) false else null),
                StartupFeature("차량 연비 / 전비 정보", if (checked) false else null),
            )
        }
}

private data class Progress(
    val step: StartupStep = StartupStep.CONNECT,
    val changeCount: Int? = null,
    val failed: Boolean = false,
    val done: Boolean = false,
)

/**
 * 앱 시작 시 차량 연결 확인: 내비 연결 → 오늘 운행 동기화 → 경로/경유지 상태 요청 → 지원 기능 정리.
 * 단계마다 최소 [STEP_MILLIS] 동안 보여줘서 진행이 눈에 보이게 한다.
 */
class StartupViewModel(
    private val naviRepository: NaviRepository,
    private val carpoolRepository: CarpoolRepository,
    private val store: VehicleCheckStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val progress = MutableStateFlow(Progress())
    private val lastCheckedAt = MutableStateFlow(store.lastCheckedAt())
    private var job: Job? = null

    val uiState: StateFlow<StartupUiState> = combine(
        naviRepository.connectionState,
        naviRepository.currentLocation,
        carpoolRepository.todayTrip,
        lastCheckedAt,
        progress,
    ) { connection, location, trip, checkedAt, p ->
        StartupUiState(
            vehicleName = trip?.driver?.carModel.orEmpty(),
            provider = naviRepository.provider,
            connection = connection,
            lastCheckedAt = checkedAt,
            step = p.step,
            changeCount = p.changeCount,
            hasLocation = location != null,
            failed = p.failed,
            done = p.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartupUiState(provider = naviRepository.provider))

    init {
        start()
    }

    /** 확인을 처음부터 다시 한다(연결 실패 후 재시도). */
    fun start() {
        if (job?.isActive == true || progress.value.done) return
        progress.value = Progress()
        job = viewModelScope.launch {
            if (!paced { connect() }) {
                progress.update { it.copy(failed = true) }
                return@launch
            }

            progress.update { it.copy(step = StartupStep.CHANGES) }
            val changes = paced {
                carpoolRepository.refresh()
                carpoolRepository.todayTrip.value?.passengers?.count { !it.isAttending } ?: 0
            }
            progress.update { it.copy(changeCount = changes, step = StartupStep.STOPS) }

            paced {
                naviRepository.refreshRouteState()
                // 위치·경유지 응답은 콜백으로 늦게 온다
                withTimeoutOrNull(RESPONSE_TIMEOUT_MILLIS) { naviRepository.currentLocation.first { it != null } }
            }

            progress.update { it.copy(step = StartupStep.FEATURES) }
            paced { }

            val checkedAt = now()
            store.saveCheckedAt(checkedAt)
            lastCheckedAt.value = checkedAt
            progress.update { it.copy(done = true) }
        }
    }

    private suspend fun connect(): Boolean {
        when (naviRepository.connectionState.value) {
            is NaviConnectionState.Connected -> return true
            is NaviConnectionState.Failed -> {
                naviRepository.disconnect()
                naviRepository.connect()
            }
            NaviConnectionState.Connecting -> Unit
            NaviConnectionState.Disconnected -> naviRepository.connect()
        }
        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            naviRepository.connectionState.first { it is NaviConnectionState.Connected || it is NaviConnectionState.Failed }
        }
        return result is NaviConnectionState.Connected
    }

    private suspend fun <T> paced(block: suspend () -> T): T {
        val started = clock()
        val result = block()
        delay((STEP_MILLIS - (clock() - started)).coerceAtLeast(0))
        return result
    }

    companion object {
        const val STEP_MILLIS = 700L
        private const val CONNECT_TIMEOUT_MILLIS = 8_000L
        private const val RESPONSE_TIMEOUT_MILLIS = 2_000L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                StartupViewModel(container.naviRepository, container.carpoolRepository, container.vehicleCheckStore)
            }
        }
    }
}
