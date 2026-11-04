package com.paircar.feature.startup

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paircar.R
import com.paircar.core.ui.components.CheckBadgeIcon
import com.paircar.core.ui.components.CheckIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.theme.DsColors
import com.paircar.feature.settings.connectionChip
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val COUNTDOWN_SECONDS = 3

/** 앱 시작 화면: 차량 연결을 확인하고, 끝나면 [COUNTDOWN_SECONDS] 초 뒤 오늘의 카풀로 넘어간다. */
@Composable
fun StartupScreen(state: StartupUiState, onRetry: () -> Unit, onFinish: () -> Unit) {
    var countdown by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
    LaunchedEffect(state.done) {
        if (!state.done) return@LaunchedEffect
        for (n in COUNTDOWN_SECONDS downTo 1) {
            countdown = n
            delay(1_000)
        }
        onFinish()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFF3F5F9)),
    ) {
        val wide = maxWidth >= ExpandedWidthThreshold
        Image(
            painterResource(R.drawable.startup_car),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomEnd,
            modifier = Modifier.fillMaxSize(),
        )
        // 좁은 창에서는 글자가 차 그림 위에 올라오므로 왼쪽을 밝게 덮는다
        if (!wide) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(0f to Color(0xF2F3F5F9), 0.75f to Color(0xF2F3F5F9), 1f to Color.Transparent)),
            )
        }

        Column(
            Modifier
                .fillMaxHeight()
                .then(if (wide) Modifier.widthIn(max = 780.dp) else Modifier.fillMaxWidth())
                .padding(horizontal = 40.dp, vertical = 36.dp),
        ) {
            Text(
                "마지막 확인 ${state.lastCheckedAt?.let(::formatChecked) ?: "없음"}",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("MY CAR", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(state.vehicleName, style = MaterialTheme.typography.headlineLarge, maxLines = 1)
                GrayChip(connectionChip(state.connection, state.provider))
            }
            Rule()
            StartupStep.entries.forEach { step -> StepLine(state, step) }
            Rule()
            Text("지원 기능", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            state.features.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    pair.forEach { FeatureItem(it, Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "*연결된 차량 정보가 달라지면 자동 감지 범위도 달라질 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
            Spacer(Modifier.weight(1f))
            if (!wide) Actions(state, countdown, onRetry, onFinish, Modifier.fillMaxWidth())
        }
        if (wide) {
            Actions(
                state,
                countdown,
                onRetry,
                onFinish,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(36.dp)
                    .width(420.dp),
            )
        }
    }
}

@Composable
private fun Rule() {
    Box(
        Modifier
            .padding(vertical = 28.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DsColors.Divider),
    )
}

/** 끝난 단계는 진하게, 진행 중은 흐리게, 남은 단계는 더 흐리게 */
@Composable
private fun StepLine(state: StartupUiState, step: StartupStep) {
    val failedHere = state.failed && step == state.step
    val text = when (step) {
        StartupStep.CONNECT -> if (failedHere) "차량을 연결하지 못했습니다." else "차량을 연결 중 입니다."
        StartupStep.CHANGES -> when (val count = state.changeCount) {
            null -> "변경사항을 반영하고 있습니다."
            0 -> "새 변경사항이 없습니다."
            else -> "변경사항 ${count}건을 반영하고 있습니다."
        }
        StartupStep.STOPS -> "경유지 변경사항을 확인 중 입니다."
        StartupStep.FEATURES -> "지원 기능을 확인하고 있습니다."
    }
    val color = when {
        failedHere -> DsColors.Warning
        state.isFinished(step) -> DsColors.OnSurface
        step == state.step && !state.failed -> DsColors.OnSurface.copy(alpha = 0.55f)
        else -> DsColors.OnSurface.copy(alpha = 0.22f)
    }
    Row(
        Modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failedHere) InfoIcon(Modifier.size(22.dp), color) else CheckIcon(Modifier.size(22.dp), color)
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
private fun FeatureItem(feature: StartupFeature, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (feature.verified == true) {
            CheckBadgeIcon(Modifier.size(22.dp))
        } else {
            InfoIcon(Modifier.size(22.dp))
        }
        Text(
            feature.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (feature.verified == true) DsColors.OnSurface else DsColors.OnSurfaceMuted,
            modifier = Modifier.weight(1f, fill = false),
        )
        when (feature.verified) {
            true -> Unit
            null -> Text("확인 중...", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
            false -> Text("미확인", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun Actions(state: StartupUiState, countdown: Int, onRetry: () -> Unit, onFinish: () -> Unit, modifier: Modifier) {
    when {
        state.done -> PrimaryActionButton("설정 완료  $countdown", onFinish, modifier)
        state.failed -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalActionButton("연결 없이 시작", onFinish, Modifier.weight(1f))
            PrimaryActionButton("다시 연결", onRetry, Modifier.weight(1f))
        }
        else -> PrimaryActionButton("차량 정보를 확인하고 있어요", {}, modifier, enabled = false)
    }
}

private val TimeFormat = DateTimeFormatter.ofPattern("a hh:mm", Locale.KOREAN)
private val DateTimeFormat = DateTimeFormatter.ofPattern("M월 d일 a hh:mm", Locale.KOREAN)

private fun formatChecked(time: LocalDateTime): String =
    if (time.toLocalDate() == LocalDate.now()) "오늘 ${time.format(TimeFormat)}" else time.format(DateTimeFormat)
