package com.paircar.feature.driving

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paircar.R
import com.paircar.core.ui.components.CarHeaderArt
import com.paircar.core.ui.components.CloseIcon
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.TouchTargetHeight
import com.paircar.core.ui.components.formatDistance
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolAction
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.GuidanceStatus
import com.paircar.domain.model.Passenger
import com.paircar.feature.carpool.CarpoolUiState
import java.text.NumberFormat
import java.util.Locale

/**
 * 주행 중 3분할(Driving view · 내비 · 페어카)의 페어카 창. 지도는 옆의 내비가 보여주므로 그리지 않고
 * 다음 픽업까지 남은 거리, 탑승 현황, 예상 분담금, 이번 운행 단계만 크게 보여준다.
 * - 남은 거리/시간: NaviHelper onWayPointInfo / onDestinationInfo
 * - [onClose]: 페어카 창을 닫는다(내비가 넓어진다). 앱 아이콘으로 다시 열면 이 화면으로 돌아온다.
 */
@Composable
fun DrivingPanel(
    state: CarpoolUiState,
    trip: CarpoolTrip,
    onConfirmBoarding: (String) -> Unit,
    onMarkNoShow: (String) -> Unit,
    onFinishTrip: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFF3F5F8))
            .verticalScroll(rememberScrollState()),
    ) {
        Header(state, onClose)
        Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NextStopCard(state, trip)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val next = trip.nextPickup
                Tile(
                    title = "픽업 예정",
                    value = "${trip.boardedCount} / ${trip.pickupsInOrder.size} 명",
                    note = next?.let { "${it.name} 픽업 대기중" } ?: "모두 탑승",
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    title = "예상 분담 금액",
                    value = state.estimatedShareWon?.let { "${WonFormat.format(it)}원" } ?: "-",
                    note = "실제 탑승 구간 기준 정산",
                    modifier = Modifier.weight(1f),
                )
            }
            StepsCard(state, trip, onConfirmBoarding, onMarkNoShow, onFinishTrip)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoIcon(Modifier.size(18.dp))
                Text("탑승 확인 후 동승 구간 기록이 시작돼요", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
            }
        }
    }
}

@Composable
private fun Header(state: CarpoolUiState, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DsColors.Surface)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 페어카 로고 (앱 아이콘과 같은 이미지, 모서리가 이미 둥글다)
        Image(
            painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text("Pair Car", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text(state.crew?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        }
        // 페어카 창 닫기 (주행 중에도 한 번의 탭)
        Box(
            Modifier
                .size(TouchTargetHeight)
                .clip(CircleShape)
                .background(DsColors.Segment)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(Modifier.size(28.dp))
        }
    }
}

/** 다음 픽업(없으면 목적지)까지 남은 거리 */
@Composable
private fun NextStopCard(state: CarpoolUiState, trip: CarpoolTrip) {
    val next = trip.nextPickup
    val guidance = state.guidance.takeIf { it.status == GuidanceStatus.GUIDING }
    val waypoint = guidance?.waypoints?.firstOrNull()
    val remainMeters = if (next != null) waypoint?.remainDistanceMeters else guidance?.remainDistanceMeters
    val remainSeconds = if (next != null) waypoint?.remainTimeSeconds else guidance?.remainTimeSeconds
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface),
    ) {
        CarHeaderArt(Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (next != null) "다음 픽업" else "목적지", style = MaterialTheme.typography.labelLarge, color = DsColors.OnSurfaceMuted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (next != null) "${next.name} 픽업" else "${trip.destination.name} 도착 예정",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                if (next != null) GrayChip(next.stepLabel())
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    remainMeters?.let(::formatDistance) ?: (next?.estimatedPickupTime ?: trip.estimatedArrivalTime).hhmm(),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 92.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    if (remainMeters != null) "남은 거리" else if (next != null) "픽업 예정" else "도착 예정",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).padding(bottom = 14.dp),
                )
                remainSeconds?.let {
                    Text("약 ${(it + 59) / 60}분", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 14.dp))
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, note: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DsColors.Surface)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 시안의 회색 사각형은 아이콘 자리
            Box(Modifier.size(24.dp).background(DsColors.Segment, RoundedCornerShape(6.dp)))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.align(Alignment.End),
        )
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = DsColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

/** 이번 운행: 출발 → 픽업(들) → 목적지. 지금 단계에만 정차 중 조작 버튼을 둔다. */
@Composable
private fun StepsCard(
    state: CarpoolUiState,
    trip: CarpoolTrip,
    onConfirmBoarding: (String) -> Unit,
    onMarkNoShow: (String) -> Unit,
    onFinishTrip: () -> Unit,
) {
    val next = trip.nextPickup
    val canAct = state.isAllowed(CarpoolAction.CONFIRM_BOARDING)
    // 내비가 안내 중일 때만 남은 거리를 믿는다(경로 선택 중에는 0m 이 온다)
    val guidance = state.guidance.takeIf { it.status == GuidanceStatus.GUIDING }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DsColors.Surface)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(24.dp).background(DsColors.Segment, RoundedCornerShape(6.dp)))
            Text("이번 운행", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        StepRow(current = false, title = "출발 완료", detail = "운행 시작")
        trip.pickupsInOrder.forEach { passenger ->
            val current = passenger == next
            val waypoint = guidance?.waypoints?.firstOrNull()?.takeIf { current }
            StepRow(
                current = current,
                title = "${passenger.name} ${passenger.stepTitle()}",
                detail = listOfNotNull(passenger.pickup.name, waypoint?.remainDistanceMeters?.let(::formatDistance)).joinToString(" · "),
            ) {
                if (current && canAct) {
                    StepButton("탑승 완료") { onConfirmBoarding(passenger.id) }
                    StepButton("미탑승") { onMarkNoShow(passenger.id) }
                }
            }
        }
        StepRow(
            current = next == null,
            title = "${trip.destination.name} 도착",
            detail = listOfNotNull("운행 종료", guidance?.remainDistanceMeters?.let(::formatDistance)).joinToString(" · "),
        ) {
            // 내비 도착 이벤트가 안 올 때(에뮬레이터 등) 운전자가 직접 끝낸다
            if (next == null && state.isAllowed(CarpoolAction.FINISH_TRIP)) StepButton("운행 종료", onFinishTrip)
        }
    }
}

@Composable
private fun StepRow(current: Boolean, title: String, detail: String, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(if (current) 14.dp else 10.dp)
                .background(if (current) DsColors.OnSurface else DsColors.OnSurfaceMuted.copy(alpha = 0.5f), CircleShape),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (current) FontWeight.Bold else FontWeight.Normal),
            color = if (current) DsColors.OnSurface else DsColors.OnSurfaceMuted,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = DsColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** 단계 옆의 작은 회색 버튼 (시안의 "다음 단계" 칩) */
@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DsColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

private fun Passenger.stepTitle(): String = when (boardingStatus) {
    BoardingStatus.BOARDED, BoardingStatus.DROPPED_OFF -> "탑승 완료"
    BoardingStatus.NO_SHOW -> "미탑승"
    BoardingStatus.ARRIVED_AT_PICKUP -> "픽업 지점 도착"
    BoardingStatus.WAITING -> "탑승 대기"
}

private fun Passenger.stepLabel(): String =
    if (boardingStatus == BoardingStatus.ARRIVED_AT_PICKUP) "픽업 지점 도착" else "탑승 예정"

private val WonFormat = NumberFormat.getIntegerInstance(Locale.KOREA)
