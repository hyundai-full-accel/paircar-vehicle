package com.paircar.feature.settlement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

data class SettlementUiState(
    /** 정산 내역이 있는 달, 최근 달이 먼저 */
    val months: List<YearMonth> = emptyList(),
    val settlement: MonthlySettlement? = null,
)

/**
 * 월별 정산: 고른 달(기본은 가장 최근 달)의 정산을 보여준다.
 * 차량(Vehicle SDK)에서 전비를 읽으면 입력값 대신 그 값으로 계산한다.
 */
class SettlementViewModel(
    repository: SettlementRepository,
    private val energyRepository: VehicleEnergyRepository,
) : ViewModel() {

    private val selectedMonth = MutableStateFlow<YearMonth?>(null)
    private val energy = MutableStateFlow(VehicleEnergy())

    val uiState: StateFlow<SettlementUiState> = combine(repository.settlements, selectedMonth, energy) { settlements, month, energy ->
        val shown = settlements.firstOrNull { it.month == month } ?: settlements.firstOrNull()
        SettlementUiState(
            months = settlements.map { it.month },
            settlement = shown?.copy(policy = shown.policy.withVehicle(energy)),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettlementUiState())

    init {
        viewModelScope.launch { energy.value = energyRepository.read() }
    }

    fun selectMonth(month: YearMonth) {
        selectedMonth.value = month
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                SettlementViewModel(container.settlementRepository, container.vehicleEnergyRepository)
            }
        }
    }
}
