package com.paircar.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripRecord
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.TripRecordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class TripFilter(val label: String) { ALL("전체"), CARPOOL("동승"), SOLO("직접 이동") }

data class HistoryUiState(
    /** 기록이 있는 달, 최근 달이 먼저 */
    val months: List<YearMonth> = emptyList(),
    val month: YearMonth? = null,
    /** 고른 달의 전체 기록 (필터 전) */
    val monthRecords: List<TripRecord> = emptyList(),
    val filter: TripFilter = TripFilter.ALL,
    /** 주간 막대에 보여줄 월~일 */
    val week: List<LocalDate> = emptyList(),
    /** 필터를 통과한 기록이 있는 날 */
    val recordDays: Set<LocalDate> = emptySet(),
    val selectedDate: LocalDate? = null,
    /** 고른 날의 기록 (필터 적용) */
    val dayRecords: List<TripRecord> = emptyList(),
    val selected: TripRecord? = null,
    /** 고른 기록의 경로선. 기록에 없으면 경로 미리보기로 구한 값 */
    val selectedPath: List<GeoPoint> = emptyList(),
) {
    fun count(filter: TripFilter): Int = monthRecords.count { it.matches(filter) }

    val totalKm: Double
        get() = monthRecords.sumOf { it.distanceKm }
}

private fun TripRecord.matches(filter: TripFilter) = when (filter) {
    TripFilter.ALL -> true
    TripFilter.CARPOOL -> kind == TripKind.CARPOOL
    TripFilter.SOLO -> kind == TripKind.SOLO
}

private data class Selection(
    val month: YearMonth? = null,
    val filter: TripFilter = TripFilter.ALL,
    val date: LocalDate? = null,
    val recordId: String? = null,
)

/** 운행 기록: 달·필터·날짜를 골라 기록을 보고, 고른 기록의 상세를 보여준다. 기본은 가장 최근 운행. */
class HistoryViewModel(
    repository: TripRecordRepository,
    private val routePreviewRepository: RoutePreviewRepository,
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())
    private val paths = MutableStateFlow<Map<String, List<GeoPoint>>>(emptyMap())

    val uiState: StateFlow<HistoryUiState> = combine(repository.records, selection, paths) { records, sel, paths ->
        val months = records.map { YearMonth.from(it.date) }.distinct()
        val month = sel.month?.takeIf { it in months } ?: months.firstOrNull()
        val monthRecords = records.filter { YearMonth.from(it.date) == month }
        val filtered = monthRecords.filter { it.matches(sel.filter) }
        val selected = filtered.firstOrNull { it.id == sel.recordId }
            ?: sel.date?.let { date -> filtered.firstOrNull { it.date == date } }
            ?: filtered.firstOrNull().takeIf { sel.date == null }
        val date = selected?.date ?: sel.date ?: filtered.firstOrNull()?.date ?: month?.atEndOfMonth()
        HistoryUiState(
            months = months,
            month = month,
            monthRecords = monthRecords,
            filter = sel.filter,
            week = date?.weekOf().orEmpty(),
            recordDays = filtered.map { it.date }.toSet(),
            selectedDate = date,
            dayRecords = filtered.filter { it.date == date }.sortedBy { it.startTime },
            selected = selected,
            selectedPath = selected?.let { it.path.ifEmpty { paths[it.id].orEmpty() } }.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        // 지난 기록(Mock)에는 경로선이 없어 고를 때 경로 미리보기로 구해 둔다
        viewModelScope.launch {
            uiState.collect { state ->
                val record = state.selected ?: return@collect
                if (record.path.isNotEmpty() || record.id in paths.value) return@collect
                paths.update { it + (record.id to emptyList()) }
                val path = routePreviewRepository.preview(record.stops.map { it.point }).path
                paths.update { it + (record.id to path) }
            }
        }
    }

    fun selectMonth(month: YearMonth) {
        selection.value = Selection(month = month, filter = selection.value.filter)
    }

    fun selectFilter(filter: TripFilter) {
        selection.update { it.copy(filter = filter, recordId = null) }
    }

    fun selectDate(date: LocalDate) {
        selection.update { it.copy(month = YearMonth.from(date), date = date, recordId = null) }
    }

    fun selectRecord(record: TripRecord) {
        selection.update { it.copy(date = record.date, recordId = record.id) }
    }

    /** 한 주 앞/뒤로. 그 주에 기록이 없으면 날짜만 옮긴다. */
    fun moveWeek(weeks: Long) {
        val date = uiState.value.selectedDate ?: return
        selectDate(date.plusWeeks(weeks))
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                HistoryViewModel(container.tripRecordRepository, container.routePreviewRepository)
            }
        }
    }
}

/** 이 날짜가 있는 주의 월~일 */
private fun LocalDate.weekOf(): List<LocalDate> {
    val monday = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0L until 7L).map { monday.plusDays(it) }
}
