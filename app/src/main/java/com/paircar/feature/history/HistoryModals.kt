package com.paircar.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.model.GeoPoint
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.RouteComparison
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStopType

/**
 * 운행 기록 상세: 경유지 없이 갔을 때와 동승 경로를 비교하고,
 * 더 간 거리·시간과 경유지(누가 어디서 탔는지)를 보여준다.
 */
@Composable
internal fun BoxScope.RouteDetailModal(record: TripRecord, path: List<GeoPoint>, onClose: () -> Unit) {
    InScreenModal(onDismiss = onClose, maxWidth = 1200.dp) {
        ModalTitle("운행 기록 상세", onClose)
        Text(
            "${record.date.monthDayWeek()} · ${record.startTime.hm()}~${record.endTime.hm()} · ${formatDuration(record.durationSeconds)}",
            style = MaterialTheme.typography.bodyMedium,
            color = DsColors.OnSurfaceMuted,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val map: @Composable (Modifier) -> Unit = { modifier ->
                CarpoolMap(
                    stops = record.toMapStops(),
                    route = path,
                    current = null,
                    interactive = false,
                    insets = MapInsets(48),
                    modifier = modifier.clip(RoundedCornerShape(16.dp)),
                )
            }
            if (maxWidth >= ExpandedWidthThreshold) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SubTitle("경로 비교")
                        map(Modifier.fillMaxWidth().height(300.dp))
                        Comparison(record)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Extras(record.routeComparison)
                        Stops(record)
                        SettlementNote()
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SubTitle("경로 비교")
                    map(Modifier.fillMaxWidth().height(260.dp))
                    Comparison(record)
                    Extras(record.routeComparison)
                    Stops(record)
                    SettlementNote()
                }
            }
        }
    }
}

/** 직접 이동 / 동승 경로 두 칸 */
@Composable
private fun Comparison(record: TripRecord) {
    val comparison = record.routeComparison
    val pickups = record.stops.count { it.type == TripStopType.PICKUP }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RouteTile(
            title = "직접 이동",
            value = comparison?.let { km(it.directKm) } ?: "-",
            detail = comparison?.let { "경유지 없이 이동 · 약 ${formatDuration(it.directSeconds)}" } ?: "경유지 없이 이동",
            modifier = Modifier.weight(1f),
        )
        RouteTile(
            title = "동승 경로",
            value = comparison?.let { km(it.carpoolKm) } ?: km(record.distanceKm),
            detail = buildString {
                append(if (pickups == 0) "경유지 없음" else "픽업 경유지 ${pickups}곳")
                comparison?.let { append(" · 약 ${formatDuration(it.carpoolSeconds)}") }
            },
            modifier = Modifier.weight(1f),
            emphasized = true,
        )
    }
    if (comparison == null) {
        Note(
            if (pickups == 0) "동승자 없이 직접 이동한 운행이라 비교할 경로가 없어요."
            else "길찾기 결과를 받지 못해 경로를 비교하지 못했어요.",
        )
    } else {
        Note("두 경로 모두 경로 미리보기(OSRM) 예상값이에요. 실제로 달린 길·시간과 다를 수 있어요.")
    }
}

@Composable
private fun RouteTile(title: String, value: String, detail: String, modifier: Modifier, emphasized: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (emphasized) DsColors.SurfaceHigh else DsColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 추가 이동 거리 / 추가 소요 시간 */
@Composable
private fun Extras(comparison: RouteComparison?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ExtraTile("추가 이동 거리", comparison?.let { "+${km(it.extraKm)}" } ?: "-", Modifier.weight(1f))
        ExtraTile("추가 소요 시간", comparison?.let { "+${formatDuration(it.extraSeconds)}" } ?: "-", Modifier.weight(1f))
    }
}

@Composable
private fun ExtraTile(label: String, value: String, modifier: Modifier, caption: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DsColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted) }
    }
}

@Composable
private fun Stops(record: TripRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SubTitle("경유지 정보")
        Timeline(record.stops)
    }
}

/** 지금 정산 규칙(공동 구간 N분의 1 + 더 간 거리는 탄 사람 몫)을 안내한다 */
@Composable
private fun SettlementNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DsColors.Background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoIcon(Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("정산 안내", style = MaterialTheme.typography.titleMedium)
            Text(
                "함께 탄 구간은 탄 인원으로 나누고, 동승자를 태우느라 더 간 거리는 그 동승자 몫으로 계산해요. 금액은 월별 정산에서 확인할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
        }
    }
}

/**
 * 운행 요약 공유: 동승자 이름과 상세 주소 없이 시간·거리·시·구 단위 지역만 보여주고 그대로 공유한다.
 * [onShare] 는 공유 문구([shareText])를 받는다.
 */
@Composable
internal fun BoxScope.ShareSummaryModal(record: TripRecord, onShare: (String) -> Unit, onClose: () -> Unit) {
    InScreenModal(onDismiss = onClose, maxWidth = 760.dp) {
        ModalTitle("운행 요약 공유", onClose)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.date.monthDayWeek(), style = MaterialTheme.typography.titleMedium)
            Text(
                "${record.startTime.hm()}~${record.endTime.hm()} · ${formatDuration(record.durationSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = DsColors.OnSurfaceMuted,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExtraTile(
                "총 이동 거리",
                km(record.distanceKm),
                Modifier.weight(1f),
                caption = if (record.distanceSource == DistanceSource.ROUTE_ESTIMATE) "경로 거리로 추정" else null,
            )
            ExtraTile("추가 우회", record.detourKm?.let { "+${km(it)}" } ?: "-", Modifier.weight(1f))
            ExtraTile("동승 인원", "${record.passengerNames.size}명", Modifier.weight(1f))
        }
        Column {
            record.stops.forEachIndexed { index, stop ->
                if (index > 0) HorizontalDivider(color = DsColors.Divider)
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stop.time.hm(), style = MaterialTheme.typography.titleMedium)
                    GrayChip(stop.type.label())
                    Text(
                        stop.area ?: "지역 정보 없음",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (stop.area == null) DsColors.OnSurfaceMuted else DsColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoIcon(Modifier.size(20.dp))
            Text(
                "공유 요약에는 동승자 이름과 상세 출발 주소를 포함하지 않아요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalActionButton("닫기", onClose, Modifier.weight(1f))
            PrimaryActionButton("공유하기", { onShare(record.shareText()) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
}
