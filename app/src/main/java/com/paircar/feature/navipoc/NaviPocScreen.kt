package com.paircar.feature.navipoc

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.DsCard
import com.paircar.core.ui.components.InfoRow
import com.paircar.core.ui.components.SecondaryActionButton
import com.paircar.domain.model.NaviDiagnosticCall

/** 정차 중 전용 개발 화면: NaviHelper API 호출과 콜백, UXR, 알림을 직접 확인한다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaviPocScreen(state: NaviPocUiState, viewModel: NaviPocViewModel) {
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPermissionResult(it)
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            DsCard(Modifier.fillMaxWidth()) {
                Text("상태", style = MaterialTheme.typography.titleLarge)
                InfoRow("구현", state.provider.name)
                InfoRow("연결", state.connection.toString())
                InfoRow("안내", state.guidance.status.name)
                InfoRow("목적지", state.guidance.destinationName ?: "-")
                InfoRow("남은 거리/시간", "${state.guidance.remainDistanceMeters ?: "-"} / ${state.guidance.remainTimeSeconds ?: "-"}")
                InfoRow("경유지", state.guidance.waypoints.joinToString { it.name }.ifEmpty { "-" })
                InfoRow("주행 규제", "${if (state.driving.restricted) "제한" else "없음"} (${state.driving.source})")
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryActionButton("1. 연결", viewModel::connect)
                SecondaryActionButton("해제", viewModel::disconnect)
                SecondaryActionButton("3. 목적지(공식 샘플)", viewModel::requestRouteOfficialSample)
                SecondaryActionButton("목적지(POI ID 없음)", viewModel::requestRouteMockDestination)
                SecondaryActionButton("경유지 포함 경로", viewModel::requestRouteWithWaypoints)
                SecondaryActionButton("4. 경유지 추가", viewModel::addWaypoint)
                SecondaryActionButton("5. 상태 조회", viewModel::refresh)
                SecondaryActionButton("6. 내비 화면", viewModel::showMap)
                SecondaryActionButton("경로 개요", viewModel::showOverview)
                SecondaryActionButton("경로 취소", viewModel::cancelRoute)
            }
        }
        item { Text("기타 NaviHelper API 점검", style = MaterialTheme.typography.titleLarge) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NaviDiagnosticCall.entries.forEach { target ->
                    SecondaryActionButton(target.label, { viewModel.runDiagnostic(target) })
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryActionButton("알림 테스트", {
                    if (!viewModel.canNotify() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.postTestNotification()
                    }
                })
                SecondaryActionButton("주행 시뮬레이션", { viewModel.setSimulatedDriving(true) })
                SecondaryActionButton("정차 시뮬레이션", { viewModel.setSimulatedDriving(false) })
                SecondaryActionButton("시뮬레이션 해제", { viewModel.setSimulatedDriving(null) })
            }
        }
        item { Text("로그 (최신순)", style = MaterialTheme.typography.titleLarge) }
        items(state.log) { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}
