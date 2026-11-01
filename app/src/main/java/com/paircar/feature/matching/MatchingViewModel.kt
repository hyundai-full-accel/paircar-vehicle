package com.paircar.feature.matching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.CarpoolAction
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.DrivingPolicy
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.DrivingStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MatchingUiState(
    val trip: CarpoolTrip? = null,
    /** 서버 응답 대기 중인 동승자 ID (중복 탭 방지) */
    val pendingIds: Set<String> = emptySet(),
)

class MatchingViewModel(
    private val carpoolRepository: CarpoolRepository,
    private val drivingStateRepository: DrivingStateRepository,
) : ViewModel() {

    private val pendingIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<MatchingUiState> =
        combine(carpoolRepository.todayTrip, pendingIds, ::MatchingUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatchingUiState(carpoolRepository.todayTrip.value))

    fun accept(passengerId: String) = respond(passengerId, MatchStatus.ACCEPTED)

    fun decline(passengerId: String) = respond(passengerId, MatchStatus.DECLINED)

    private fun respond(passengerId: String, status: MatchStatus) {
        if (!DrivingPolicy.isAllowed(CarpoolAction.RESPOND_TO_REQUEST, drivingStateRepository.state.value)) return
        if (passengerId in pendingIds.value) return
        pendingIds.update { it + passengerId }
        viewModelScope.launch {
            try {
                carpoolRepository.updateMatchStatus(passengerId, status)
            } finally {
                pendingIds.update { it - passengerId }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                MatchingViewModel(container.carpoolRepository, container.drivingStateRepository)
            }
        }
    }
}
