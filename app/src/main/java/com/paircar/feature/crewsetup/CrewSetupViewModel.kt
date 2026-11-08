package com.paircar.feature.crewsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewSetup
import com.paircar.domain.model.CrewSetupState
import com.paircar.domain.model.Place
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.NaviRepository
import com.paircar.feature.crew.CrewViewModel
import com.paircar.feature.crew.InviteUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** [크루 만들기] 입력값 */
data class CrewSetupForm(
    val name: String = "",
    val departureTime: LocalTime = LocalTime.of(8, 0),
    val days: Set<DayOfWeek> = Crew.WEEKDAYS,
    val origin: Place? = null,
    val destination: Place? = null,
)

data class CrewSetupUiState(
    val setupState: CrewSetupState = CrewSetupState.LOADING,
    val form: CrewSetupForm = CrewSetupForm(),
    /** 서버에 크루는 있는데 일정만 없으면 그 크루 (이름을 채워 둔다) */
    val existingCrew: Crew? = null,
    val saving: Boolean = false,
    /** 만들고 나서 보여줄 QR 초대. null 이면 아직 만들기 전 */
    val createdCrew: Crew? = null,
    val invite: InviteUiState = InviteUiState(),
)

/**
 * 크루 만들기: 크루 이름·출발 시각·반복 요일·출발지·도착지를 정해 크루를 만들고 일정을 저장한 뒤,
 * 동승자를 초대할 QR 을 바로 보여준다.
 */
class CrewSetupViewModel(
    private val carpoolRepository: CarpoolRepository,
    private val naviRepository: NaviRepository,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val form = MutableStateFlow(CrewSetupForm())
    private val progress = MutableStateFlow(Progress())

    private data class Progress(val saving: Boolean = false, val created: Crew? = null, val invite: InviteUiState = InviteUiState())

    val uiState: StateFlow<CrewSetupUiState> =
        combine(carpoolRepository.setupState, carpoolRepository.crew, form, progress) { setup, crew, form, progress ->
            CrewSetupUiState(
                setupState = setup,
                form = form.copy(name = form.name.ifEmpty { crew?.name.orEmpty() }),
                existingCrew = crew,
                saving = progress.saving,
                createdCrew = progress.created,
                invite = progress.invite,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrewSetupUiState())

    /** 출발지·도착지 고르기에서 [현재 위치로]에 쓰는 차량 위치 (Pleos NaviHelper) */
    val currentLocation = naviRepository.currentLocation

    init {
        naviRepository.refreshRouteState()
    }

    fun update(transform: (CrewSetupForm) -> CrewSetupForm) = form.update(transform)

    fun create(onMessage: (String) -> Unit) {
        val current = uiState.value.form
        validate(current)?.let { return onMessage(it) }
        if (progress.value.saving) return
        progress.update { it.copy(saving = true) }
        viewModelScope.launch {
            val setup = CrewSetup(current.name.trim(), current.departureTime, current.days, current.origin!!, current.destination!!)
            runCatching { carpoolRepository.setupCrew(setup) }
                .onSuccess {
                    val crew = carpoolRepository.crew.value ?: Crew("crew", setup.name, today(), setup.days)
                    progress.value = Progress(created = crew, invite = InviteUiState(loading = true))
                    createInvite()
                }
                .onFailure {
                    progress.value = Progress()
                    onMessage(it.message?.takeIf { msg -> msg.any { c -> c in '가'..'힣' } } ?: "크루를 만들지 못했어요. 서버 연결을 확인해 주세요")
                }
        }
    }

    fun createInvite() {
        progress.update { it.copy(invite = it.invite.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = runCatching { carpoolRepository.createInvite() }
            progress.update { p ->
                p.copy(
                    invite = result.fold(
                        onSuccess = { InviteUiState(invite = it) },
                        onFailure = { InviteUiState(error = "QR 을 만들지 못했어요. 다시 시도해 주세요.") },
                    ),
                )
            }
        }
    }

    /** QR 을 닫으면 오늘의 카풀로 넘어간다 */
    fun finish() {
        progress.value = Progress()
    }

    companion object {
        fun validate(form: CrewSetupForm): String? = when {
            form.name.isBlank() -> "크루 이름을 입력해 주세요"
            form.name.trim().length > CrewViewModel.MAX_NAME -> "크루 이름은 ${CrewViewModel.MAX_NAME}자까지예요"
            form.days.isEmpty() -> "반복 운행 요일을 하나 이상 골라 주세요"
            form.origin == null -> "출발지를 정해 주세요"
            form.destination == null -> "도착지를 정해 주세요"
            form.origin.name == form.destination.name -> "출발지와 도착지 이름을 다르게 정해 주세요"
            form.origin.point == form.destination.point -> "출발지와 도착지를 다른 곳으로 정해 주세요"
            else -> null
        }

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                CrewSetupViewModel(container.carpoolRepository, container.naviRepository)
            }
        }
    }
}
