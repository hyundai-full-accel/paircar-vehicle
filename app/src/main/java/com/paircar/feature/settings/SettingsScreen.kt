package com.paircar.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenLoading
import com.paircar.core.ui.components.Panel
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.ScrollColumn
import com.paircar.core.ui.components.SettingRow
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviProvider
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 지원 기능 한 줄. [verified] 가 null 이면 "검토 중" */
private data class SupportFeature(val label: String, val verified: Boolean?)

private fun SettingsUiState.supportFeatures() = listOf(
    SupportFeature("내비 경로 연동", connected),
    SupportFeature("도착 예정 시간", connected),
    SupportFeature("남은 거리 정보", connected),
    SupportFeature("탑승 자동 감지", connected),
    SupportFeature("음성 명령", null),
)

/** 설정: 운행 설정, 지원 기능, 연결된 차량 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onRecheck: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onNotReady: (String) -> Unit,
) {
    val notReady = { onNotReady("이 설정은 다음 단계에서 바꿀 수 있어요") }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= ExpandedWidthThreshold
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingsPanel(state, twoColumns = true, notReady, Modifier.weight(1.7f).fillMaxHeight())
                VehiclePanel(state, onRecheck, onDisconnect, onOpenDiagnostics, fillHeight = true, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            ScrollColumn {
                VehiclePanel(state, onRecheck, onDisconnect, onOpenDiagnostics, fillHeight = false, Modifier.fillMaxWidth())
                SettingsPanel(state, twoColumns = false, notReady, Modifier.fillMaxWidth())
            }
        }
        if (state.checking) InScreenLoading("차량 정보를\n확인하고 있어요.")
    }
}

@Composable
private fun SettingsPanel(state: SettingsUiState, twoColumns: Boolean, onNotReady: () -> Unit, modifier: Modifier) {
    Panel(modifier.then(if (twoColumns) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Text("설정", style = MaterialTheme.typography.titleLarge)
        Text("운행 설정", style = MaterialTheme.typography.titleMedium)
        SettingRow("기본 크루", value = state.crewName ?: "없음", onClick = onNotReady)
        Grid(
            twoColumns,
            listOf(
                { m -> SettingRow("탑승 확인 방식", m, value = "자동 감지", onClick = onNotReady) },
                { m -> SettingRow("주행중 안내", m, value = "핵심 정보만", onClick = onNotReady) },
                { m -> SettingRow("변경 사항 안내", m, value = "화면 안내", onClick = onNotReady) },
                { m -> SettingRow("운행 종료 후 저장", m, value = "자동", onClick = onNotReady) },
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text("지원 기능", style = MaterialTheme.typography.titleMedium)
        val features = state.supportFeatures()
        features.first().let { SettingRow(it.label, chip = it.chipText(), onClick = onNotReady) }
        Grid(
            twoColumns,
            features.drop(1).map { feature ->
                { m: Modifier -> SettingRow(feature.label, m, chip = feature.chipText(), onClick = onNotReady) }
            },
        )
    }
}

private fun SupportFeature.chipText() = when (verified) {
    true -> "확인됨"
    false -> "확인 필요"
    null -> "검토 중"
}

/** 넓으면 2열, 좁으면 1열 */
@Composable
private fun Grid(twoColumns: Boolean, cells: List<@Composable (Modifier) -> Unit>) {
    if (!twoColumns) {
        cells.forEach { it(Modifier.fillMaxWidth()) }
        return
    }
    cells.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            pair.forEach { it(Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun VehiclePanel(
    state: SettingsUiState,
    onRecheck: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    fillHeight: Boolean,
    modifier: Modifier,
) {
    val features = state.supportFeatures()
    Panel(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFE8EDF5), DsColors.Surface))),
    ) {
        Text("연결된 차량", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.vehicleName, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f, fill = false))
            GrayChip(state.connectionChip())
        }
        Text(
            "마지막 연결 확인: ${state.lastCheckedAt?.format(CheckedFormat) ?: "-"}",
            style = MaterialTheme.typography.bodySmall,
            color = DsColors.OnSurfaceMuted,
        )
        // 넓은 화면에서는 버튼을 아래로 붙인다(시안). 좁은 화면은 스크롤 안이라 고정 간격.
        Spacer(if (fillHeight) Modifier.weight(1f) else Modifier.height(24.dp))
        Column {
            SettingRow("차량 연결 상태", value = state.connectionStatus(), onClick = onRecheck)
            SettingRow("차량 정보 동기화", value = if (state.connected) "완료" else "대기", onClick = onRecheck)
            SettingRow(
                "지원 기능 확인",
                value = "${features.count { it.verified == true }}개 확인됨",
                onClick = onOpenDiagnostics,
            )
        }
        Spacer(if (fillHeight) Modifier.weight(1f) else Modifier.height(24.dp))
        PrimaryActionButton("차량 연결 재확인", onRecheck, Modifier.fillMaxWidth(), enabled = !state.checking)
        TonalActionButton("차량 연결 해제", onDisconnect, Modifier.fillMaxWidth(), enabled = state.connected && !state.checking)
    }
}

private val CheckedFormat = DateTimeFormatter.ofPattern("M월 d일 a h:mm", Locale.KOREAN)

private fun SettingsUiState.connectionChip(): String = when (connection) {
    is NaviConnectionState.Connected -> if (provider == NaviProvider.MOCK) "Mock 연결 •" else "연결됨 •"
    NaviConnectionState.Connecting -> "연결 중"
    NaviConnectionState.Disconnected -> "연결 안 됨"
    is NaviConnectionState.Failed -> "연결 실패"
}

private fun SettingsUiState.connectionStatus(): String = when (connection) {
    is NaviConnectionState.Connected -> "정상"
    NaviConnectionState.Connecting -> "확인 중"
    NaviConnectionState.Disconnected -> "연결 안 됨"
    is NaviConnectionState.Failed -> "확인 필요"
}
