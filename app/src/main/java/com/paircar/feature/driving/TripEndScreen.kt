package com.paircar.feature.driving

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paircar.core.ui.components.CarHeaderArt
import com.paircar.core.ui.components.CheckBadgeIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.Panel
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.ScrollColumn
import com.paircar.core.ui.components.SectionHeader
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.usecase.TripFinish
import com.paircar.feature.history.distanceLabel
import com.paircar.feature.history.dots
import com.paircar.feature.history.hm
import kotlinx.coroutines.delay

private const val DONE_SECONDS = 5

/**
 * 운행 종료: 도착 확인 → 탑승 기록 → 기록 저장 결과를 보여주고,
 * 저장이 끝나면 [DONE_SECONDS] 초 뒤 운행 기록으로 넘어간다.
 */
@Composable
fun TripEndScreen(finish: TripFinish, destinationName: String, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    var countdown by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(finish.saved) {
        if (finish.saved != true) return@LaunchedEffect
        for (n in DONE_SECONDS downTo 1) {
            countdown = n
            delay(1_000)
        }
        done()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ArrivalCard(finish, destinationName)
                    // 넓은 화면에서는 버튼을 카드 아래에 붙인다(시안)
                    ProcessCard(finish, countdown, onDone, fillHeight = true, Modifier.weight(1f))
                }
                RouteCard(finish, fillMap = true, Modifier.weight(1.6f).fillMaxHeight())
            }
        } else {
            ScrollColumn {
                ArrivalCard(finish, destinationName)
                ProcessCard(finish, countdown, onDone, fillHeight = false, Modifier)
                RouteCard(finish, fillMap = false, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ArrivalCard(finish: TripFinish, destinationName: String) {
    val record = finish.record
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface),
    ) {
        CarHeaderArt(Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("운행 종료", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    listOfNotNull(
                        record.date.dots(),
                        "${record.startTime.hm()}~${record.endTime.hm()}",
                        if (finish.saved == true) "기록 저장" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = DsColors.OnSurfaceMuted,
                )
            }
            HorizontalDivider(color = DsColors.Divider)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$destinationName 도착", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                GrayChip("도착 완료")
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("0m", style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 96.sp, fontWeight = FontWeight.Bold))
                Text("남은 거리", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 14.dp))
            }
        }
    }
}

@Composable
private fun ProcessCard(finish: TripFinish, countdown: Int?, onDone: () -> Unit, fillHeight: Boolean, modifier: Modifier) {
    val record = finish.record
    Panel(modifier.fillMaxWidth()) {
        SectionHeader("운행 처리 상태")
        StepRow(
            done = true,
            title = "목적지 도착",
            detail = if (record.arrivedByNavi) "내비 도착 이벤트 확인" else "운전자가 직접 운행 종료",
        )
        StepRow(
            done = true,
            title = "탑승 기록",
            detail = when (finish.boardedCount) {
                0 -> "동승자 없음 · 직접 이동으로 기록"
                1 -> "경유지1 탑승 구간 반영"
                else -> "경유지 ${finish.boardedCount}곳 탑승 구간 반영"
            },
        )
        StepRow(
            done = finish.saved,
            title = "기록 저장",
            detail = when (finish.saved) {
                null -> "저장 중..."
                true -> "로컬 기록 저장 완료"
                false -> "저장하지 못했어요"
            },
        )
        Spacer(if (fillHeight) Modifier.weight(1f) else Modifier.height(4.dp))
        PrimaryActionButton(
            countdown?.let { "운행 종료  $it" } ?: "운행 종료",
            onDone,
            Modifier.fillMaxWidth(),
            enabled = finish.saved != null,
        )
    }
}

/** [done] null 이면 진행 중, false 면 실패 */
@Composable
private fun StepRow(done: Boolean?, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (done) {
                true -> CheckBadgeIcon(Modifier.size(28.dp), color = DsColors.OnSurfaceMuted)
                false -> InfoIcon(Modifier.size(28.dp), color = DsColors.Warning)
                null -> CircularProgressIndicator(Modifier.size(22.dp), color = DsColors.OnSurfaceMuted, strokeWidth = 2.dp)
            }
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (done == false) DsColors.Warning else DsColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun RouteCard(finish: TripFinish, fillMap: Boolean, modifier: Modifier) {
    val record = finish.record
    Panel(modifier) {
        CarpoolMap(
            stops = record.toMapStops(),
            route = record.path,
            current = null,
            insets = MapInsets(64),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillMap) Modifier.weight(1f) else Modifier.height(320.dp))
                .clip(RoundedCornerShape(16.dp)),
        )
        HorizontalDivider(color = DsColors.Divider)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Metric("총 주행거리", record.distanceLabel())
            Metric("총 소요 시간", formatDuration(record.durationSeconds))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold))
    }
}
