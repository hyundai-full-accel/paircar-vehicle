package com.paircar.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 차량 터치 타깃: 최소 76dp 높이 */
val TouchTargetHeight = 76.dp

@Composable
fun DsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .background(DsColors.Surface, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTargetHeight),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DsColors.Dark, contentColor = Color.White),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTargetHeight),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun StatusBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f),
        )
    }
}

/** 주행 중 제한된 조작 대신 보여주는 안내 */
@Composable
fun ParkedOnlyNotice(text: String = "정차 중에만 사용할 수 있어요", modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = DsColors.Warning,
        modifier = modifier
            .fillMaxWidth()
            .background(DsColors.SurfaceHigh, RoundedCornerShape(16.dp))
            .padding(20.dp),
    )
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.hhmm(): String = format(timeFormatter)

fun CarpoolStatus.label(): Pair<String, Color> = when (this) {
    CarpoolStatus.SCHEDULED -> "출발 전" to DsColors.Info
    CarpoolStatus.IN_PROGRESS -> "운행 중" to DsColors.Brand
    CarpoolStatus.COMPLETED -> "운행 완료" to DsColors.OnSurfaceMuted
    CarpoolStatus.CANCELLED -> "취소됨" to DsColors.Danger
}

fun MatchStatus.label(): Pair<String, Color> = when (this) {
    MatchStatus.REQUESTED -> "요청됨" to DsColors.Warning
    MatchStatus.ACCEPTED -> "수락" to DsColors.Brand
    MatchStatus.DECLINED -> "거절" to DsColors.OnSurfaceMuted
    MatchStatus.CANCELLED -> "취소" to DsColors.Danger
}

fun BoardingStatus.label(): Pair<String, Color> = when (this) {
    BoardingStatus.WAITING -> "대기" to DsColors.Info
    BoardingStatus.ARRIVED_AT_PICKUP -> "픽업 지점 도착" to DsColors.Warning
    BoardingStatus.BOARDED -> "탑승" to DsColors.Brand
    BoardingStatus.NO_SHOW -> "미탑승" to DsColors.Danger
    BoardingStatus.DROPPED_OFF -> "하차" to DsColors.OnSurfaceMuted
}

/** 오늘 불참이면 탑승 상태 대신 "불참" */
fun Passenger.statusLabel(): Pair<String, Color> =
    if (isAttending) boardingStatus.label() else "불참" to DsColors.OnSurfaceMuted

/** 남은 시간(초)을 "약 N분"으로. 단위가 초라는 가정은 Pleos 에서 미검증. */
fun formatRemainMinutes(seconds: Int?): String? =
    seconds?.let { "약 ${(it + 59) / 60}분" }

/** 403m / 3.1km */
fun formatDistance(meters: Int): String =
    if (meters < 1000) "${meters}m" else String.format(java.util.Locale.KOREA, "%.1fkm", meters / 1000.0)

/** 4분 / 1시간 24분 */
fun formatDuration(seconds: Int): String {
    val minutes = (seconds + 59) / 60
    return if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
}
