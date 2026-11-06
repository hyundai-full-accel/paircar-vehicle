package com.paircar.feature.settlement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.SettlementRequest
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.model.resolve
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.SettlementSettingsStore
import com.paircar.domain.repository.TripRecordRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class SettlementUiState(
    /** 정산 내역이 있는 달, 최근 달이 먼저 */
    val months: List<YearMonth> = emptyList(),
    val settlement: MonthlySettlement? = null,
    val settings: SettlementSettings = SettlementSettings(),
    /** 차량(Vehicle SDK)에서 읽은 전비. 못 읽으면 null 이고 입력값을 쓴다 */
    val vehicleEfficiency: Double? = null,
    /** 고른 달의 운행 기록 중 동승자가 탄 것 (분담 내역의 운행별 목록), 최근 운행이 먼저 */
    val carpoolRecords: List<TripRecord> = emptyList(),
)

/**
 * 월별 정산: 고른 달(기본은 가장 최근 달)의 정산을 보여준다.
 * 운전자 설정(단가·비용 항목)과 차량 전비를 반영하고, 요청한 달은 요청 때 기준으로 고정한다.
 */
class SettlementViewModel(
    repository: SettlementRepository,
    private val settingsStore: SettlementSettingsStore,
    tripRecordRepository: TripRecordRepository,
    private val energyRepository: VehicleEnergyRepository,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val selectedMonth = MutableStateFlow<YearMonth?>(null)
    private val energy = MutableStateFlow(VehicleEnergy())

    private val resolved = combine(
        repository.settlements,
        selectedMonth,
        settingsStore.settings,
        settingsStore.requests,
        energy,
    ) { settlements, month, settings, requests, energy ->
        val shown = settlements.firstOrNull { it.month == month } ?: settlements.firstOrNull()
        SettlementUiState(
            months = settlements.map { it.month },
            settlement = shown?.resolve(settings, requests.firstOrNull { it.month == shown.month }, energy),
            settings = settings,
            vehicleEfficiency = energy.evEfficiency,
        )
    }

    val uiState: StateFlow<SettlementUiState> = combine(resolved, tripRecordRepository.records) { state, records ->
        val month = state.settlement?.month
        state.copy(carpoolRecords = records.filter { YearMonth.from(it.date) == month && it.passengerNames.isNotEmpty() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettlementUiState())

    init {
        viewModelScope.launch { energy.value = energyRepository.read() }
    }

    fun selectMonth(month: YearMonth) {
        selectedMonth.value = month
    }

    fun saveSettings(settings: SettlementSettings, onDone: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { settingsStore.saveSettings(settings) }
                .onSuccess { onDone("정산 정보를 저장했어요") }
                .onFailure { onDone("정산 정보를 저장하지 못했어요") }
        }
    }

    /**
     * 고른 달 정산을 지금 기준으로 확정해 기기에 기록한다.
     * 정산 서버가 없어 동승자에게 알림은 아직 보내지 못한다.
     */
    fun requestSettlement(onDone: (String) -> Unit) {
        val settlement = uiState.value.settlement ?: return
        when {
            settlement.receiverAccount == null -> onDone("수취 계좌를 먼저 등록해 주세요")
            settlement.requested -> onDone("이미 요청한 정산이에요")
            else -> viewModelScope.launch {
                runCatching { settingsStore.saveRequest(SettlementRequest(settlement.month, today(), settlement.policy)) }
                    .onSuccess { onDone("정산 금액을 확정했어요. 동승자 알림은 정산 서버 연동 후 보낼 수 있어요") }
                    .onFailure { onDone("정산 요청을 기록하지 못했어요") }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                SettlementViewModel(
                    container.settlementRepository,
                    container.settlementSettingsStore,
                    container.tripRecordRepository,
                    container.vehicleEnergyRepository,
                )
            }
        }
    }
}
