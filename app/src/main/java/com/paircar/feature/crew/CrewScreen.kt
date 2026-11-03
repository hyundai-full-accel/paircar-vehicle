package com.paircar.feature.crew

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.ActionTile
import com.paircar.core.ui.components.ChevronIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.InitialAvatar
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.Panel
import com.paircar.core.ui.components.PlusIcon
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.ScrollColumn
import com.paircar.core.ui.components.SectionHeader
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.components.formatDistance
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.qr.QrCode
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.Passenger
import com.paircar.feature.carpool.CarpoolUiState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class CrewModal { NONE, ROUTE_DETAIL, INVITE }

/** 내 크루: 크루 일정·멤버, 경로 미리보기, 경로 상세(직접 vs 동승 비교), QR 초대 */
@Composable
fun CrewScreen(
    state: CarpoolUiState,
    invite: InviteUiState,
    onOpenInvite: () -> Unit,
    onRegenerateInvite: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettlement: () -> Unit,
    onNotReady: (String) -> Unit,
) {
    val trip = state.trip
    val crew = state.crew
    if (trip == null || crew == null) {
        Text("소속된 크루가 없어요", style = MaterialTheme.typography.headlineMedium)
        return
    }
    var modal by rememberSaveable { mutableStateOf(CrewModal.NONE) }
    val openInvite = {
        onOpenInvite()
        modal = CrewModal.INVITE
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val crewPanel: @Composable (Modifier) -> Unit = { modifier ->
            CrewPanel(crew, trip, openInvite, onOpenHistory, onOpenSettlement, onNotReady, modifier)
        }
        val routePanel: @Composable (Modifier, Boolean) -> Unit = { modifier, fillMap ->
            RoutePanel(state, trip, fillMap, { modal = CrewModal.ROUTE_DETAIL }, openInvite, modifier)
        }
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                crewPanel(Modifier.weight(1.7f).fillMaxHeight())
                routePanel(Modifier.weight(1f).fillMaxHeight(), true)
            }
        } else {
            ScrollColumn {
                crewPanel(Modifier.fillMaxWidth())
                routePanel(Modifier.fillMaxWidth(), false)
            }
        }

        when (modal) {
            CrewModal.ROUTE_DETAIL -> RouteDetailModal(state, trip, crew) { modal = CrewModal.NONE }
            CrewModal.INVITE -> InviteModal(crew, invite, onRegenerateInvite) { modal = CrewModal.NONE }
            CrewModal.NONE -> Unit
        }
    }
}

@Composable
private fun CrewPanel(
    crew: Crew,
    trip: CarpoolTrip,
    onInvite: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettlement: () -> Unit,
    onNotReady: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface),
    ) {
        // 시안의 연한 그라데이션 머리 영역
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(DsColors.Surface, Color(0xFFE8EDF5))))
                .padding(start = 28.dp, end = 28.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.clickable { onNotReady("다른 크루 선택은 다음 단계에서 제공돼요") },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(crew.name, style = MaterialTheme.typography.titleLarge)
                    ChevronIcon(Modifier.size(28.dp).rotate(90f), color = DsColors.OnSurface)
                }
                Text(
                    "${crew.startDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))} ~",
                    style = MaterialTheme.typography.bodySmall,
                    color = DsColors.OnSurfaceMuted,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(DsColors.Dark).clickable(onClick = onInvite),
                    contentAlignment = Alignment.Center,
                ) {
                    PlusIcon(Modifier.size(28.dp))
                }
            }
            Text(
                "${trip.departureTime.short()} ~ ${trip.estimatedArrivalTime.short()}",
                style = MaterialTheme.typography.displayMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrayChip(crew.weekdays)
                GrayChip("${trip.departureTime.withMeridiem()} 출발")
                GrayChip("${trip.destination.name} 도착")
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(
            Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HorizontalDivider(color = DsColors.Divider)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionTile("크루 정보 수정", { onNotReady("크루 정보 수정은 다음 단계에서 제공돼요") }, Modifier.weight(1f))
                ActionTile("운행 내역 상세", onOpenHistory, Modifier.weight(1f))
                ActionTile("정산 기준 확인", onOpenSettlement, Modifier.weight(1f))
            }
            Column {
                SectionHeader("크루 멤버") {
                    Text(
                        "편집",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable { onNotReady("멤버 편집은 다음 단계에서 제공돼요") }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                MemberRow(trip.driver.name, "운전자", "${trip.origin.name}에서 출발", "탑승 중")
                trip.acceptedPassengers.forEachIndexed { index, passenger ->
                    MemberRow(passenger.name, "동승자${index + 1}", trip.memberNote(passenger), passenger.crewStatus())
                }
            }
        }
    }
}

@Composable
private fun MemberRow(name: String, role: String, note: String, status: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InitialAvatar(name)
        Column(Modifier.weight(1f)) {
            Text("$name ($role)", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, maxLines = 1)
        }
        GrayChip(status)
    }
}

@Composable
private fun RoutePanel(
    state: CarpoolUiState,
    trip: CarpoolTrip,
    fillMap: Boolean,
    onOpenDetail: () -> Unit,
    onInvite: () -> Unit,
    modifier: Modifier,
) {
    Panel(modifier) {
        Text("경로 미리보기", style = MaterialTheme.typography.titleMedium)
        CarpoolMap(
            stops = trip.toMapStops(),
            route = state.routePreview?.path.orEmpty(),
            current = state.currentLocation,
            interactive = false,
            insets = MapInsets(48),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillMap) Modifier.weight(1f) else Modifier.height(280.dp))
                .clip(RoundedCornerShape(16.dp)),
        )
        HorizontalDivider(color = DsColors.Divider)
        Column {
            val pickups = trip.pickupsInOrder
            StopRow(trip.origin.name, "출발", trip.departureTime.hhmm(), isLast = false)
            pickups.forEach { passenger ->
                StopRow("${passenger.name} 픽업", passenger.crewStatus(), passenger.estimatedPickupTime.hhmm(), isLast = false)
            }
            StopRow(trip.destination.name, "도착", trip.estimatedArrivalTime.hhmm(), isLast = true)
        }
        HorizontalDivider(color = DsColors.Divider)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val preview = state.routePreview
            SummaryValue("예상", preview?.let { formatDistance(it.distanceMeters) } ?: "-")
            Box(Modifier.width(1.dp).height(28.dp).background(DsColors.Divider))
            SummaryValue("총", formatDuration(trip.scheduledDurationSeconds))
        }
        PrimaryActionButton("경로 상세 보기", onOpenDetail, Modifier.fillMaxWidth())
        TonalActionButton("QR로 초대하기", onInvite, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

/** 타임라인 한 줄: 링 아이콘(다음 줄까지 점선) · 이름 · 상태 · 시각 */
@Composable
private fun StopRow(name: String, sub: String, time: String, isLast: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val r = 9.dp.toPx()
            val ringCenter = Offset(size.width / 2, 12.dp.toPx() + r)
            drawCircle(DsColors.OnSurfaceMuted, r, ringCenter, style = Stroke(3.dp.toPx()))
            if (!isLast) {
                drawLine(
                    DsColors.Divider,
                    Offset(ringCenter.x, ringCenter.y + r + 4.dp.toPx()),
                    Offset(ringCenter.x, size.height),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
        }
        Row(
            Modifier.weight(1f).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
            Text(time, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 경로 상세: 직접 이동 vs 동승 경로.
 * 거리와 "추가" 값은 길찾기 결과(교통 미반영)의 차이, 동승 경로 시간은 크루 일정(교통 반영 가정)을 쓴다.
 */
@Composable
private fun RouteDetailModal(state: CarpoolUiState, trip: CarpoolTrip, crew: Crew, onClose: () -> Unit) {
    val carpool = state.routePreview
    val direct = state.directPreview
    Box(Modifier.fillMaxSize()) {
        InScreenModal(onDismiss = onClose) {
            ModalTitle("${crew.name.removeSuffix(" 크루")} 경로 상세", onClose)
            Text("경로 비교", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = DsColors.Divider)
            if (carpool == null || direct == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = DsColors.OnSurfaceMuted)
                    Text("경로를 계산하고 있어요", style = MaterialTheme.typography.bodyLarge)
                }
                return@InScreenModal
            }
            val approximate = if (carpool.isApproximate || direct.isApproximate) " (추정)" else ""
            // 화면에 보이는 값(0.1km 단위)끼리 빼야 68.3 - 59.4 = 8.9 처럼 맞아 보인다
            val extraMeters = (carpool.distanceMeters.toTenthKm() - direct.distanceMeters.toTenthKm()).coerceAtLeast(0)
            // 시간도 화면 단위(분)로 맞춰서 "동승 - 추가 = 직접"이 그대로 성립하게 한다
            val extraSeconds = ((carpool.durationSeconds - direct.durationSeconds).coerceAtLeast(0) + 59) / 60 * 60
            val carpoolSeconds = trip.scheduledDurationSeconds
            val pickups = trip.pickupsInOrder

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CarpoolMap(
                    stops = trip.toMapStops(),
                    route = carpool.path,
                    current = null,
                    interactive = false,
                    insets = MapInsets(20),
                    modifier = Modifier.size(width = 220.dp, height = 150.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CompareRow(
                        "직접 이동",
                        "경유지 없이 이동 · 약 ${formatDuration((carpoolSeconds - extraSeconds).coerceAtLeast(0))}",
                        formatDistance(direct.distanceMeters) + approximate,
                    )
                    CompareRow(
                        "동승 경로",
                        "픽업 경유지 ${pickups.size}곳 · 약 ${formatDuration(carpoolSeconds)}",
                        formatDistance(carpool.distanceMeters) + approximate,
                    )
                }
            }
            HorizontalDivider(color = DsColors.Divider)
            ExtraRow("추가 이동 거리", "+${formatDistance(extraMeters)}", big = true)
            ExtraRow("추가 소요 시간", "+${formatDuration(extraSeconds)}", big = false)
            HorizontalDivider(color = DsColors.Divider)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("경유지 정보", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (pickups.isEmpty()) "경유지 없음" else pickups.joinToString(" → ") { "● ${it.pickup.name}" },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                )
            }
            HorizontalDivider(color = DsColors.Divider)
            Text("ⓘ 정산 안내", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
            Text(
                "실제 운행 거리와 탑승 구간을 기준으로 정산하며, 우회거리는 합의한 정산 기준에 따라 반영돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun CompareRow(title: String, sub: String, distance: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        }
        Text(distance, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ExtraRow(label: String, value: String, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
    }
}

/** QR 초대: 휴대폰으로 스캔하면 모바일웹 초대 화면으로 연결 (회의 2.4) */
@Composable
private fun InviteModal(crew: Crew, state: InviteUiState, onRegenerate: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        InScreenModal(onDismiss = onClose, maxWidth = 640.dp) {
            ModalTitle("${crew.name} 초대", onClose)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val invite = state.invite
                if (invite == null) {
                    Box(Modifier.size(320.dp), contentAlignment = Alignment.Center) {
                        if (state.error != null) {
                            Text(state.error, style = MaterialTheme.typography.bodyLarge, color = DsColors.Danger, textAlign = TextAlign.Center)
                        } else {
                            CircularProgressIndicator(Modifier.size(48.dp), color = DsColors.OnSurfaceMuted)
                        }
                    }
                } else {
                    QrCode(
                        invite.url,
                        Modifier
                            .size(320.dp)
                            .border(1.dp, DsColors.Divider, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
            Text(
                "꾹 누르면 연결됩니다",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "휴대폰 카메라로 QR코드를 비춘 뒤, 나타나는 링크를 꾹 누르면 모바일웹 초대 화면이 열려요",
                style = MaterialTheme.typography.bodyMedium,
                color = DsColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            state.invite?.let { invite ->
                Text(
                    "초대 코드 ${invite.code} · ${invite.expiresAt.format(ExpiryFormat)}까지",
                    style = MaterialTheme.typography.bodySmall,
                    color = DsColors.OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TonalActionButton("새 QR 만들기", onRegenerate, Modifier.weight(1f), enabled = !state.loading)
                PrimaryActionButton("닫기", onClose, Modifier.weight(1f))
            }
        }
    }
}

/** formatDistance 가 보여주는 단위로 반올림 (1km 미만은 m 그대로) */
private fun Int.toTenthKm(): Int = if (this < 1000) this else Math.round(this / 100.0).toInt() * 100

private val ExpiryFormat =DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

/** 7:10 */
private fun LocalTime.short(): String = format(DateTimeFormatter.ofPattern("H:mm"))

/** 오전 07:10 */
private fun LocalTime.withMeridiem(): String = format(DateTimeFormatter.ofPattern("a hh:mm", Locale.KOREAN))

private fun CarpoolTrip.memberNote(passenger: Passenger): String {
    if (!passenger.isAttending) return "오늘 불참 · 경유지 제외"
    val order = pickupsInOrder.indexOfFirst { it.id == passenger.id } + 1
    return "${passenger.pickup.name} · 경유지 $order"
}

private fun Passenger.crewStatus(): String = when {
    !isAttending -> "불참 확정"
    boardingStatus == BoardingStatus.BOARDED -> "탑승 중"
    boardingStatus == BoardingStatus.DROPPED_OFF -> "하차"
    boardingStatus == BoardingStatus.NO_SHOW -> "미탑승"
    else -> "탑승 예정"
}
