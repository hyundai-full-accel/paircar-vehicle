package com.paircar.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paircar.core.ui.components.CarHeaderArt
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.InitialAvatar
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.formatDistance
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.Passenger
import com.paircar.feature.carpool.CarpoolUiState
import com.paircar.feature.settings.connectionChip
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

private const val AUTO_START_SECONDS = 5

/**
 * V-02 오늘의 카풀: 크루·출발 시각, 경유지 변경 알림, 정류장 타임라인, 예상 거리·시간 / 크루 멤버, 경로 미리보기, 주행 시작.
 *
 * [autoStart] 면(시작 화면에서 막 넘어온 경우) [AUTO_START_SECONDS] 초 뒤 경로 안내를 시작한다.
 * 화면 어디든 누르면 카운트다운을 멈춘다.
 */
@Composable
fun HomeScreen(
    state: CarpoolUiState,
    autoStart: Boolean,
    onAutoStartHandled: () -> Unit,
    onStartCarpool: () -> Unit,
    onOpenDriving: () -> Unit,
    onOpenMatching: () -> Unit,
) {
    val trip = state.trip
    if (trip == null) {
        Text("오늘 카풀 일정이 없어요", style = MaterialTheme.typography.headlineMedium)
        return
    }

    val handled by rememberUpdatedState(onAutoStartHandled)
    val autoStartNow by rememberUpdatedState(autoStart)
    val start by rememberUpdatedState(onStartCarpool)
    var countdown by remember { mutableStateOf<Int?>(null) }
    val canAutoStart = autoStart && trip.status == CarpoolStatus.SCHEDULED &&
        state.naviConnection is NaviConnectionState.Connected
    LaunchedEffect(canAutoStart) {
        if (!canAutoStart) {
            countdown = null
            return@LaunchedEffect
        }
        for (n in AUTO_START_SECONDS downTo 1) {
            countdown = n
            delay(1_000)
        }
        countdown = null
        handled()
        start()
    }
    // 다른 탭으로 가면 자동 시작은 끝난 것으로 본다
    DisposableEffect(Unit) { onDispose { if (autoStartNow) handled() } }

    val startButton: @Composable (Modifier) -> Unit = { modifier ->
        when (trip.status) {
            CarpoolStatus.SCHEDULED -> PrimaryActionButton(
                countdown?.let { "주행 시작  $it" } ?: "주행 시작",
                onStartCarpool,
                modifier,
            )
            CarpoolStatus.IN_PROGRESS -> PrimaryActionButton("주행 현황", onOpenDriving, modifier)
            else -> Unit
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(autoStart) {
                if (!autoStart) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    countdown = null
                    handled()
                }
            },
    ) {
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TripCard(state, trip, onOpenMatching, fillHeight = true, Modifier.weight(1.75f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    MembersCard(trip, Modifier.fillMaxWidth())
                    RouteMapCard(state, trip, Modifier.weight(1f).fillMaxWidth(), startButton)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TripCard(state, trip, onOpenMatching, fillHeight = false, Modifier.fillMaxWidth())
                MembersCard(trip, Modifier.fillMaxWidth())
                RouteMapCard(state, trip, Modifier.fillMaxWidth().height(460.dp), startButton)
            }
        }
    }
}

@Composable
private fun TripCard(state: CarpoolUiState, trip: CarpoolTrip, onOpenMatching: () -> Unit, fillHeight: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(DsColors.Surface)) {
        // 헤더(구분선 위) 높이에 맞춰야 차가 경유지 변경 알림 밑으로 숨지 않는다
        CarHeaderArt(Modifier.align(Alignment.TopEnd).height(220.dp))
        Column(Modifier.padding(horizontal = 36.dp, vertical = 32.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(28.dp).background(DsColors.Segment, RoundedCornerShape(6.dp)))
                        Text(state.crew?.name ?: "오늘 ${trip.title}", style = MaterialTheme.typography.titleMedium)
                        Text(trip.date.format(DateFormat), style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(trip.departureTime.format(DepartureFormat), style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 100.sp))
                        Text("출발 예정", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 16.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GrayChip("경유지 ${trip.pickupsInOrder.size}곳")
                        if (trip.requestedPassengers.isNotEmpty()) {
                            GrayChip("새 동승 요청 ${trip.requestedPassengers.size}건 ›", Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenMatching))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("오늘도 안전 운전하세요!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    GrayChip(connectionChip(state.naviConnection, state.naviProvider), Modifier.background(DsColors.Surface, RoundedCornerShape(50)))
                }
            }
            CardDivider()
            trip.changedPickups.forEach { passenger -> PickupChangeNotice(passenger) }
            if (trip.changedPickups.isNotEmpty()) CardDivider()
            if (fillHeight) Spacer(Modifier.weight(1f))
            Timeline(trip)
            if (fillHeight) Spacer(Modifier.weight(1f))
            CardDivider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val preview = state.routePreview
                val suffix = if (preview?.isApproximate == true) " (추정)" else ""
                Metric("예상 거리", preview?.let { formatDistance(it.distanceMeters) + suffix } ?: "-")
                // 내 크루 화면과 같은 기준: 일정상 출발~도착 (경로 미리보기는 교통 미반영)
                Metric("소요 시간", formatDuration(trip.scheduledDurationSeconds))
            }
        }
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(Modifier.padding(vertical = 24.dp), color = DsColors.Divider)
}

/** ⓘ 경유지 변경 알림   기존 | 판교역 3번 출구  →  변경 | 판교역 2번 출구 */
@Composable
private fun PickupChangeNotice(passenger: Passenger) {
    val previous = passenger.previousPickup ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .background(DsColors.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoIcon(Modifier.size(20.dp))
        Text("경유지 변경 알림", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
        Text("기존 | ${previous.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("  →  ", style = MaterialTheme.typography.bodyMedium)
        Text("변경 | ${passenger.pickup.name}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun Timeline(trip: CarpoolTrip) {
    val pickups = trip.pickupsInOrder
    Column(Modifier.padding(horizontal = 8.dp)) {
        TimelineRow(trip.departureTime.hhmm(), trip.origin.name, null, "출발", filled = false, lineAbove = false, lineBelow = true)
        pickups.forEachIndexed { index, passenger ->
            TimelineRow(
                passenger.estimatedPickupTime.hhmm(),
                "경유지 ${index + 1}",
                "${passenger.name} 픽업",
                passenger.boardingStatus.memberLabel(),
                filled = false,
                lineAbove = true,
                lineBelow = true,
            )
        }
        TimelineRow(trip.estimatedArrivalTime.hhmm(), trip.destination.name, null, "목적지 도착", filled = true, lineAbove = true, lineBelow = false)
    }
}

@Composable
private fun TimelineRow(
    time: String,
    title: String,
    chip: String?,
    trailing: String,
    filled: Boolean,
    lineAbove: Boolean,
    lineBelow: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        TimelineMarker(filled, lineAbove, lineBelow, Modifier.width(24.dp).fillMaxHeight())
        Spacer(Modifier.width(28.dp))
        Text(time, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(112.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (chip != null) {
            Spacer(Modifier.width(12.dp))
            GrayChip(chip)
        }
        Spacer(Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
    }
}

/** ○ / ● 와 점선 */
@Composable
private fun TimelineMarker(filled: Boolean, lineAbove: Boolean, lineBelow: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val r = 10.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        val lineColor = DsColors.Divider
        val stroke = 2.dp.toPx()
        if (lineAbove) drawLine(lineColor, Offset(center.x, 0f), Offset(center.x, center.y - r), stroke, pathEffect = dash)
        if (lineBelow) drawLine(lineColor, Offset(center.x, center.y + r), Offset(center.x, size.height), stroke, pathEffect = dash)
        if (filled) {
            drawCircle(DsColors.Divider, r)
            drawCircle(DsColors.OnSurface, r * 0.55f)
        } else {
            drawCircle(DsColors.OnSurfaceMuted.copy(alpha = 0.5f), r - stroke, style = Stroke(stroke * 1.5f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = DsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 48.sp, lineHeight = 56.sp))
    }
}

/** 운전자 + 오늘 함께하는 크루원(불참 포함) */
@Composable
private fun MembersCard(trip: CarpoolTrip, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        Text("크루 멤버", style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp), color = DsColors.Divider)
        MemberRow(trip.driver.name, "운전자", "${trip.origin.name}에서 출발", "탑승 중")
        trip.acceptedPassengers.sortedBy { it.estimatedPickupTime }.forEachIndexed { index, passenger ->
            MemberRow(
                passenger.name,
                "동승자${index + 1}",
                passenger.pickup.name,
                if (passenger.isAttending) passenger.boardingStatus.memberLabel() else "불참 확정",
            )
        }
    }
}

@Composable
private fun MemberRow(name: String, role: String, sub: String, status: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InitialAvatar(name, 48.dp)
        Column(Modifier.weight(1f)) {
            Text("$name ($role)", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, maxLines = 1)
        }
        GrayChip(status)
    }
}

private fun BoardingStatus.memberLabel(): String = when (this) {
    BoardingStatus.WAITING -> "탑승 예정"
    BoardingStatus.ARRIVED_AT_PICKUP -> "픽업 지점 도착"
    BoardingStatus.BOARDED -> "탑승 중"
    BoardingStatus.NO_SHOW -> "미탑승"
    BoardingStatus.DROPPED_OFF -> "하차"
}

@Composable
private fun RouteMapCard(state: CarpoolUiState, trip: CarpoolTrip, modifier: Modifier, startButton: @Composable (Modifier) -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("경로 미리보기", style = MaterialTheme.typography.titleMedium)
        CarpoolMap(
            stops = trip.toMapStops(),
            route = state.routePreview?.path.orEmpty(),
            current = state.currentLocation,
            interactive = false,
            insets = MapInsets(48),
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
        )
        startButton(Modifier.fillMaxWidth())
    }
}

private val DateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val DepartureFormat = DateTimeFormatter.ofPattern("HH : mm")
