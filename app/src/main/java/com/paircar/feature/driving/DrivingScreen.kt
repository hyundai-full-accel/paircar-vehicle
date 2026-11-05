package com.paircar.feature.driving

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.CloseIcon
import com.paircar.core.ui.components.ParkedOnlyNotice
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.components.TouchTargetHeight
import com.paircar.core.ui.components.formatDistance
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolAction
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.GuidanceStatus
import com.paircar.feature.carpool.CarpoolUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime

/**
 * V-06 주행 중 현황. 지도 위에 "다음 경유지까지 남은 거리" 카드 하나와 도착 정보만 크게 띄운다.
 * - 남은 거리/시간: NaviHelper onWayPointInfo / onDestinationInfo (m, 초)
 * - 차량 위치: getCurrentLocationInfo (화면이 보이는 동안 5초 간격 요청)
 * - 주행 중 조작은 "내비 보기" 하나. 탑승 완료·운행 종료는 정차 중에만.
 * - 운행이 끝나 기록 처리 결과가 있으면 운행 종료 화면([TripEndScreen])을 보여준다.
 * - 운행 중 좁은 창(내비와 나란히 3분할)에서는 지도 없이 [DrivingPanel] 을 보여준다.
 */
@Composable
fun DrivingScreen(
    state: CarpoolUiState,
    onOpenNavigation: () -> Unit,
    onConfirmBoarding: (passengerId: String) -> Unit,
    onMarkNoShow: (passengerId: String) -> Unit,
    onStartCarpool: () -> Unit,
    onFinishTrip: () -> Unit,
    onTripDone: () -> Unit,
    panelMode: Boolean,
    onClosePanel: () -> Unit,
    onRefresh: () -> Unit,
) {
    val trip = state.trip
    val finish = state.tripFinish
    if (trip?.status == CarpoolStatus.COMPLETED && finish != null) {
        TripEndScreen(finish, trip.destination.name, onTripDone)
        return
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            onRefresh()
            delay(REFRESH_MILLIS)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))) {
        val compact = maxWidth < 900.dp
        if (panelMode && trip?.status == CarpoolStatus.IN_PROGRESS) {
            DrivingPanel(state, trip, onConfirmBoarding, onMarkNoShow, onFinishTrip, onClosePanel)
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        CarpoolMap(
            stops = trip?.toMapStops().orEmpty(),
            route = state.routePreview?.path.orEmpty(),
            current = state.currentLocation,
            // 카드가 덮는 영역만큼 비워 경로가 카드 밖에 보이도록 맞춘다
            insets = with(density) {
                if (compact) {
                    MapInsets(80.dp.roundToPx(), 470.dp.roundToPx(), 80.dp.roundToPx(), 140.dp.roundToPx())
                } else {
                    MapInsets(640.dp.roundToPx(), 100.dp.roundToPx(), 100.dp.roundToPx(), 140.dp.roundToPx())
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(560.dp)),
        ) {
            NextStopCard(state, trip, onConfirmBoarding, onMarkNoShow, onStartCarpool, onFinishTrip)
            // 앱이 3분할을 직접 요청할 API 가 없어(Pleos) 운전자에게 window bar 를 안내한다
            var hintClosed by rememberSaveable { mutableStateOf(false) }
            if (!compact && trip?.status == CarpoolStatus.IN_PROGRESS && !hintClosed) {
                SplitHint(onClose = { hintClosed = true }, modifier = Modifier.padding(top = 16.dp))
            }
        }
        Button(
            onClick = onOpenNavigation,
            modifier = Modifier.align(Alignment.BottomStart).padding(20.dp).heightIn(min = TouchTargetHeight),
            shape = RoundedCornerShape(40.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DsColors.Surface, contentColor = DsColors.OnSurface),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        ) {
            Text("내비 보기", style = MaterialTheme.typography.labelLarge)
        }
        if (trip != null) {
            ArrivalPill(state, trip, Modifier.align(Alignment.BottomEnd).padding(20.dp))
        }
    }
}

@Composable
private fun NextStopCard(
    state: CarpoolUiState,
    trip: CarpoolTrip?,
    onConfirmBoarding: (String) -> Unit,
    onMarkNoShow: (String) -> Unit,
    onStartCarpool: () -> Unit,
    onFinishTrip: () -> Unit,
) {
    Column(
        Modifier
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .background(DsColors.Surface, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            trip == null || trip.status == CarpoolStatus.CANCELLED -> {
                Text("진행 중인 카풀이 없어요", style = MaterialTheme.typography.headlineMedium)
            }
            trip.status == CarpoolStatus.SCHEDULED -> {
                Caption("출발 전 · ${trip.title}")
                Big("${trip.departureTime.hhmm()} 출발")
                Sub("${trip.origin.name} → ${trip.destination.name}")
                PrimaryActionButton("카풀 시작", onStartCarpool, Modifier.fillMaxWidth())
            }
            trip.status == CarpoolStatus.COMPLETED -> {
                Caption("운행 종료")
                Big("0m 남음")
                val dropped = trip.acceptedPassengers.count { it.boardingStatus == BoardingStatus.DROPPED_OFF }
                Sub("${trip.destination.name} 도착 · 동승자 ${dropped}명 하차")
            }
            else -> {
                val next = trip.nextPickup
                if (next != null) {
                    val index = trip.pickupsInOrder.indexOf(next) + 1
                    val waypoint = state.guidance.waypoints.firstOrNull()
                    val arrived = next.boardingStatus == BoardingStatus.ARRIVED_AT_PICKUP
                    Caption(if (arrived) "경유지 $index 도착 · ${next.name}" else "다음 경유지 $index · ${next.name}")
                    Big(waypoint?.remainDistanceMeters?.let { "${formatDistance(it)} 남음" } ?: "${next.estimatedPickupTime.hhmm()} 픽업")
                    Sub(listOfNotNull(next.pickup.name, waypoint?.remainTimeSeconds?.let(::formatDuration)).joinToString(" · "))
                    StopProgress(trip)
                    if (state.isAllowed(CarpoolAction.CONFIRM_BOARDING)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryActionButton("${next.name} 탑승 완료", { onConfirmBoarding(next.id) }, Modifier.weight(2f))
                            // 픽업 지점에 나오지 않으면 미탑승으로 넘기고 다음 경유지로
                            TonalActionButton("미탑승", { onMarkNoShow(next.id) }, Modifier.weight(1f))
                        }
                    } else {
                        ParkedOnlyNotice("정차 후 탑승을 확인하세요")
                    }
                } else {
                    Caption("목적지")
                    Big(state.guidance.remainDistanceMeters?.let { "${formatDistance(it)} 남음" } ?: trip.destination.name)
                    Sub(listOfNotNull(trip.destination.name, state.guidance.remainTimeSeconds?.let(::formatDuration)).joinToString(" · "))
                    StopProgress(trip)
                    // 내비 도착 이벤트가 오지 않을 때(에뮬레이터 등) 운전자가 직접 끝낸다
                    if (state.isAllowed(CarpoolAction.FINISH_TRIP)) {
                        TonalActionButton("운행 종료", onFinishTrip, Modifier.fillMaxWidth())
                    } else {
                        ParkedOnlyNotice("정차 후 운행을 종료할 수 있어요")
                    }
                }
            }
        }
    }
}

/** 출발 · 경유지 · 도착 진행 표시 (완료 회색, 다음 파랑, 남은 곳 테두리) */
@Composable
private fun StopProgress(trip: CarpoolTrip) {
    val next = trip.nextPickup
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        StopDot("출발", done = true, current = false)
        trip.pickupsInOrder.forEachIndexed { index, passenger ->
            StopDot("경유 ${index + 1}", done = !passenger.isPendingPickup, current = passenger == next)
        }
        StopDot("도착", done = false, current = next == null)
    }
}

@Composable
private fun StopDot(label: String, done: Boolean, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = when {
            current -> DsColors.Route
            done -> Color(0xFF9AA1AB)
            else -> Color.Transparent
        }
        Box(
            Modifier
                .size(14.dp)
                .background(color, CircleShape)
                .border(2.dp, if (current) DsColors.Route else Color(0xFF9AA1AB), CircleShape),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (current) DsColors.Route else DsColors.OnSurfaceMuted)
    }
}

@Composable
private fun ArrivalPill(state: CarpoolUiState, trip: CarpoolTrip, modifier: Modifier) {
    val guiding = state.guidance.status == GuidanceStatus.GUIDING
    val eta = state.guidance.remainTimeSeconds?.takeIf { guiding }?.let { LocalTime.now().plusSeconds(it.toLong()) }
        ?: trip.estimatedArrivalTime
    val distance = state.guidance.remainDistanceMeters?.takeIf { guiding } ?: state.routePreview?.distanceMeters
    Text(
        listOfNotNull("도착 ${eta.hhmm()}", distance?.let(::formatDistance)).joinToString("  |  "),
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(40.dp))
            .background(DsColors.Surface, RoundedCornerShape(40.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp),
    )
}

/** 내비 + 페어카 3분할 안내. 내비가 넓게 떠 있으면 화면 오른쪽 끝 ⋮ 를 끌어야 페어카가 옆에 좁게 열린다. */
@Composable
private fun SplitHint(onClose: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .background(DsColors.Surface, RoundedCornerShape(20.dp))
            .padding(start = 24.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("내비와 나란히 보기", style = MaterialTheme.typography.titleMedium)
            Text(
                "화면 오른쪽 끝 ⋮ 막대를 왼쪽으로 끌면 내비 옆에 페어카가 열려요. 다음부터는 주행을 시작해도 나란히 유지돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
        }
        Box(
            Modifier.size(TouchTargetHeight).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = DsColors.OnSurfaceMuted)

@Composable
private fun Big(text: String) =
    Text(text, style = MaterialTheme.typography.displayMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Sub(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)

private const val REFRESH_MILLIS = 5_000L
