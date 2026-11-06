package com.paircar.feature.crew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.ConsentStatus
import com.paircar.domain.model.CostRules
import com.paircar.domain.model.CrewInfo
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.RouteRequest
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.SettlementSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class InviteUiState(
    val invite: CrewInvite? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** 정산 기준 관리: 비용 항목(월별 정산과 같은 설정)과 크루원별 동의 상태 */
data class CrewSettlementState(
    val rules: CostRules = CostRules(),
    /** 이름 → 최근 정산 기준 동의 상태 (동승자 앱 연동 전에는 예시 데이터) */
    val consents: Map<String, ConsentStatus> = emptyMap(),
)

/**
 * 내 크루: QR 초대, 크루 정보·오늘 참석·정산 기준 저장. 크루·운행 정보는 CarpoolViewModel 상태를 같이 쓴다.
 */
class CrewViewModel(
    private val carpoolRepository: CarpoolRepository,
    settlementRepository: SettlementRepository,
    private val settingsStore: SettlementSettingsStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    private val _invite = MutableStateFlow(InviteUiState())
    val invite: StateFlow<InviteUiState> = _invite.asStateFlow()

    val settlement: StateFlow<CrewSettlementState> =
        combine(settlementRepository.settlements, settingsStore.settings) { settlements, settings ->
            CrewSettlementState(
                rules = settings.rules,
                consents = settlements.firstOrNull()?.members?.associate { it.name to it.consent }.orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrewSettlementState())

    /** QR 을 열 때: 아직 유효한 초대가 있으면 그대로 쓴다. */
    fun ensureInvite() {
        val current = _invite.value.invite
        if (current == null || !current.expiresAt.isAfter(now())) createInvite()
    }

    fun createInvite() {
        if (_invite.value.loading) return
        _invite.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            _invite.value = runCatching { carpoolRepository.createInvite() }.fold(
                onSuccess = { InviteUiState(invite = it) },
                onFailure = { InviteUiState(invite = _invite.value.invite, error = "QR 을 만들지 못했어요. 다시 시도해 주세요.") },
            )
        }
    }

    fun saveCrewInfo(info: CrewInfo, onDone: (String) -> Unit) {
        validateCrewInfo(info)?.let { return onDone(it) }
        viewModelScope.launch {
            val started = carpoolRepository.todayTrip.value?.status != CarpoolStatus.SCHEDULED
            runCatching { carpoolRepository.updateCrewInfo(info.copy(name = info.name.trim())) }
                .onSuccess { onDone(if (started) "크루 정보를 저장했어요. 오늘 운행 시각은 그대로예요" else "크루 정보를 저장했어요") }
                .onFailure { onDone("크루 정보를 저장하지 못했어요") }
        }
    }

    /** 오늘 참석 여부를 바꾼다. 바뀐 사람만 [changes] 에 넣는다. */
    fun saveAttendance(trip: CarpoolTrip, changes: Map<String, Attendance>, onDone: (String) -> Unit) {
        validateAttendance(trip, changes)?.let { return onDone(it) }
        if (changes.isEmpty()) return onDone("바뀐 픽업 정보가 없어요")
        viewModelScope.launch {
            runCatching { changes.forEach { (id, attendance) -> carpoolRepository.updateAttendance(id, attendance) } }
                .onSuccess { onDone("픽업 정보를 저장했어요. 출발할 때 바뀐 경유지로 내비에 요청해요") }
                .onFailure { onDone("픽업 정보를 저장하지 못했어요") }
        }
    }

    fun saveRules(rules: CostRules, onDone: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { settingsStore.saveSettings(settingsStore.settings.value.copy(rules = rules)) }
                .onSuccess { onDone("정산 기준을 저장했어요") }
                .onFailure { onDone("정산 기준을 저장하지 못했어요") }
        }
    }

    companion object {
        /** NaviHelper requestRouteWithWaypoints 가 받는 경유지 수 */
        const val MAX_PICKUPS = RouteRequest.MAX_WAYPOINTS
        const val MAX_NAME = 20

        fun validateCrewInfo(info: CrewInfo): String? = when {
            info.name.isBlank() -> "크루 이름을 입력해 주세요"
            info.name.trim().length > MAX_NAME -> "크루 이름은 ${MAX_NAME}자까지예요"
            info.days.isEmpty() -> "반복 운행 요일을 하나 이상 골라 주세요"
            else -> null
        }

        /** 출발 전에만 바꿀 수 있고, 참석하는 동승자(= 경유지)는 [MAX_PICKUPS] 명까지 */
        fun validateAttendance(trip: CarpoolTrip, changes: Map<String, Attendance>): String? {
            if (trip.status != CarpoolStatus.SCHEDULED) return "운행을 시작한 뒤에는 픽업 정보를 바꿀 수 없어요"
            val attending = trip.acceptedPassengers.count { (changes[it.id] ?: it.attendance) == Attendance.ATTENDING }
            return if (attending > MAX_PICKUPS) "픽업 경유지는 최대 ${MAX_PICKUPS}곳까지예요" else null
        }

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                CrewViewModel(container.carpoolRepository, container.settlementRepository, container.settlementSettingsStore)
            }
        }
    }
}
