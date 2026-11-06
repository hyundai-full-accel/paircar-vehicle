package com.paircar.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paircar.R
import com.paircar.core.model.GeoPoint
import com.paircar.core.ui.components.ChevronIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.Panel
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.ScrollColumn
import com.paircar.core.ui.components.SectionHeader
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.components.headerBackground
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStop
import com.paircar.domain.model.TripStopType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 운행 기록: 이달 요약, 운행 내역(필터·주간·하루 타임라인), 고른 운행의 상세 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectFilter: (TripFilter) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectRecord: (TripRecord) -> Unit,
    onMoveWeek: (Long) -> Unit,
    onMessage: (String) -> Unit,
) {
    val month = state.month
    if (month == null) {
        Panel(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("아직 운행 기록이 없어요", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    var routeDetail by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val summary: @Composable (Modifier, Boolean) -> Unit = { modifier, fillHeight ->
            SummaryCard(state, month, onSelectMonth, onSelectFilter, onSelectDate, onSelectRecord, onMoveWeek, fillHeight, modifier)
        }
        val detail: @Composable (Modifier, Boolean) -> Unit = { modifier, fillHeight ->
            DetailPanel(
                state = state,
                fillHeight = fillHeight,
                onOpenRoute = { routeDetail = true },
                onShare = { share = true },
                modifier = modifier,
            )
        }
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                summary(Modifier.weight(1.75f).fillMaxHeight(), true)
                detail(Modifier.weight(1f).fillMaxHeight(), true)
            }
        } else {
            ScrollColumn {
                summary(Modifier.fillMaxWidth(), false)
                detail(Modifier.fillMaxWidth(), false)
            }
        }

        val selected = state.selected
        if (routeDetail && selected != null) {
            RouteDetailModal(selected, state.selectedPath) { routeDetail = false }
        }
        if (share && selected != null) {
            ShareSummaryModal(
                record = selected,
                onShare = { text -> onMessage(shareTripSummary(context, text)) },
                onClose = { share = false },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    state: HistoryUiState,
    month: YearMonth,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectFilter: (TripFilter) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectRecord: (TripRecord) -> Unit,
    onMoveWeek: (Long) -> Unit,
    fillHeight: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface),
    ) {
        // 시안의 머리 영역 배경 그림
        Column(
            Modifier
                .fillMaxWidth()
                .headerBackground(R.drawable.bg_header_history)
                .padding(start = 36.dp, end = 36.dp, top = 32.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MonthPicker(month, state.months, onSelectMonth)
                Text("${month.year}.${month.monthValue}.1 ~ ${month.monthValue}.${month.lengthOfMonth()}", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("총 운행", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    "${state.monthRecords.size}회",
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrayChip("총 주행 ${"%,.0f".format(state.totalKm)}km")
                GrayChip("동승 운행 ${state.count(TripFilter.CARPOOL)}회")
                GrayChip("기록 저장 완료")
            }
        }
        Column(
            Modifier
                .then(if (fillHeight) Modifier.weight(1f) else Modifier)
                .padding(start = 36.dp, end = 36.dp, top = 12.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("운행 내역", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
                TripFilter.entries.forEach { filter ->
                    FilterChip("${filter.label}(${state.count(filter)})", filter == state.filter) { onSelectFilter(filter) }
                }
            }
            HorizontalDivider(color = DsColors.Divider)
            WeekStrip(state, onSelectDate, onMoveWeek)
            // 넓은 화면에서만 타임라인을 따로 스크롤한다. 좁은 창은 화면 전체가 스크롤되므로 겹치면 안 된다.
            Column(
                if (fillHeight) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier,
            ) {
                if (state.dayRecords.size > 1) {
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.dayRecords.forEach { record ->
                            FilterChip("${record.startTime.hm()} 운행", record.id == state.selected?.id) { onSelectRecord(record) }
                        }
                    }
                }
                val selected = state.selected
                if (selected == null) {
                    Text(
                        "이 날은 운행 기록이 없어요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DsColors.OnSurfaceMuted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    Timeline(selected.stops)
                }
            }
        }
    }
}

@Composable
private fun MonthPicker(month: YearMonth, months: List<YearMonth>, onSelect: (YearMonth) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(month.format(MonthFormat), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            ChevronIcon(Modifier.size(28.dp).rotate(90f), color = DsColors.OnSurface)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            months.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.format(MonthFormat), style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) DsColors.OnSurface else DsColors.OnSurfaceMuted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DsColors.Segment else DsColors.SurfaceHigh.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 월~일. 기록이 있는 날은 점, 고른 날은 파랑. 양끝 화살표로 주를 옮긴다. */
@Composable
private fun WeekStrip(state: HistoryUiState, onSelectDate: (LocalDate) -> Unit, onMoveWeek: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        WeekArrow(left = true) { onMoveWeek(-1) }
        state.week.forEach { date ->
            val selected = date == state.selectedDate
            val hasRecord = date in state.recordDays
            val color = when {
                selected -> DsColors.Route
                hasRecord -> DsColors.OnSurface
                else -> DsColors.OnSurfaceMuted.copy(alpha = 0.5f)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectDate(date) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), style = MaterialTheme.typography.bodySmall, color = color)
                Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium), color = color)
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(5.dp)
                        .background(if (hasRecord) color else Color.Transparent, CircleShape),
                )
            }
        }
        WeekArrow(left = false) { onMoveWeek(1) }
    }
    HorizontalDivider(color = DsColors.Divider, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun WeekArrow(left: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ChevronIcon(Modifier.size(24.dp).rotate(if (left) 180f else 0f))
    }
}

/** 07:10 [출발] 집 ……… 출발 */
@Composable
internal fun Timeline(stops: List<TripStop>) {
    Column {
        stops.forEachIndexed { index, stop ->
            Row(
                Modifier.fillMaxWidth().height(60.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 도착은 진한 고리, 나머지는 옅은 고리 (시안)
                Box(
                    Modifier
                        .size(18.dp)
                        .border(
                            width = if (index == stops.lastIndex) 4.dp else 3.dp,
                            color = if (index == stops.lastIndex) DsColors.OnSurface else DsColors.OnSurfaceMuted.copy(alpha = 0.4f),
                            shape = CircleShape,
                        ),
                )
                Text(stop.time.hm(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium))
                GrayChip(
                    stop.type.label(),
                )
                Text(
                    stop.placeName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (stop.type) {
                        TripStopType.DEPART -> "출발"
                        TripStopType.PICKUP -> "${stop.passengerName}님 탑승"
                        TripStopType.ARRIVE -> "도착"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DsColors.OnSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun DetailPanel(
    state: HistoryUiState,
    fillHeight: Boolean,
    onOpenRoute: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier,
) {
    val record = state.selected
    Panel(modifier) {
        SectionHeader("운행 상세")
        if (record == null) {
            Text(
                "운행을 고르면 상세가 보여요",
                style = MaterialTheme.typography.bodyMedium,
                color = DsColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
            )
            return@Panel
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(record.date.monthDayWeek(), style = MaterialTheme.typography.titleMedium)
                Text("${record.startTime.hm()} → ${record.endTime.hm()}", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
            }
            Text(km(record.distanceKm), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
        CarpoolMap(
            stops = record.toMapStops(),
            route = state.selectedPath,
            current = null,
            interactive = false,
            insets = MapInsets(40),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (fillHeight) 220.dp else 260.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
        DetailRow("총 이동 거리", record.distanceLabel())
        DetailRow("총 소요시간", formatDuration(record.durationSeconds))
        DetailRow("추가 우회 거리", record.detourKm?.let { "+${km(it)}" } ?: "-")
        if (record.distanceSource == DistanceSource.ROUTE_ESTIMATE) {
            Text(
                "차량 주행거리를 읽지 못해 경로 거리로 표시했어요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
        }
        if (fillHeight) Spacer(Modifier.weight(1f))
        PrimaryActionButton("경로 상세 보기 →", onOpenRoute, Modifier.fillMaxWidth())
        TonalActionButton("운행 요약 공유", onShare, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

private val MonthFormat = DateTimeFormatter.ofPattern("yyyy년 M월")
