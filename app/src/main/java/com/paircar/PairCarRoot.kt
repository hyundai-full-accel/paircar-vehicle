package com.paircar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paircar.core.navigation.Screen
import com.paircar.core.ui.components.StatusBadge
import com.paircar.core.ui.components.TouchTargetHeight
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.DrivingPolicy
import com.paircar.domain.model.DrivingState
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.model.NaviScreen
import com.paircar.feature.carpool.CarpoolDetailScreen
import com.paircar.feature.carpool.CarpoolViewModel
import com.paircar.feature.driving.DrivingScreen
import com.paircar.feature.home.HomeScreen
import com.paircar.feature.matching.MatchingScreen
import com.paircar.feature.matching.MatchingViewModel
import com.paircar.feature.navipoc.NaviPocScreen
import com.paircar.feature.navipoc.NaviPocViewModel

private val BackStackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.map(Screen::valueOf) },
)

/** 와이어프레임 상단 탭. 정산은 이번 MVP 제외 기능 */
private enum class Tab(val label: String, val screen: Screen?) {
    TODAY("오늘의 카풀", Screen.HOME),
    CREW("내 크루", Screen.MATCHING),
    HISTORY("운행 기록", Screen.DETAIL),
    SETTLEMENT("정산 정보", null),
}

private fun Screen.tab(): Tab? = when (this) {
    Screen.HOME, Screen.DRIVING -> Tab.TODAY
    Screen.MATCHING -> Tab.CREW
    Screen.DETAIL -> Tab.HISTORY
    Screen.NAVI_POC -> null
}

/**
 * 앱 루트. 주행 규제(CarUxRestrictions) 중에는 주행 중 허용되지 않은 화면 대신
 * 주행 현황 화면을 보여준다(백스택은 유지 → 정차하면 원래 화면으로 복귀).
 */
@Composable
fun PairCarRoot() {
    val carpoolViewModel: CarpoolViewModel = viewModel(factory = CarpoolViewModel.Factory)
    val state by carpoolViewModel.uiState.collectAsStateWithLifecycle()

    var backStack by rememberSaveable(stateSaver = BackStackSaver) { mutableStateOf(listOf(Screen.HOME)) }
    val current = backStack.last()
    val shown = if (DrivingPolicy.isAllowed(current.action, state.driving)) current else Screen.DRIVING
    val navigate: (Screen) -> Unit = { screen -> if (backStack.last() != screen) backStack = backStack + screen }
    val canGoBack = backStack.size > 1 && shown == current

    BackHandler(enabled = canGoBack) { backStack = backStack.dropLast(1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TopBar(
                selected = shown.tab(),
                canGoBack = canGoBack,
                onBack = { backStack = backStack.dropLast(1) },
                onSelectTab = { tab ->
                    val screen = tab.screen
                    if (screen == null) carpoolViewModel.showMessage("정산은 다음 단계에서 제공돼요") else backStack = listOf(screen)
                },
                onOpenSettings = { navigate(Screen.NAVI_POC) },
                naviProvider = state.naviProvider,
                naviConnection = state.naviConnection,
                driving = state.driving,
            )
            state.message?.let { MessageBanner(it, carpoolViewModel::dismissMessage) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (shown) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        onStartCarpool = { carpoolViewModel.startGuidance { navigate(Screen.DRIVING) } },
                        onOpenDriving = { navigate(Screen.DRIVING) },
                        onOpenMatching = { backStack = listOf(Screen.MATCHING) },
                    )
                    Screen.MATCHING -> {
                        val matchingViewModel: MatchingViewModel = viewModel(factory = MatchingViewModel.Factory)
                        val matchingState by matchingViewModel.uiState.collectAsStateWithLifecycle()
                        MatchingScreen(matchingState, matchingViewModel::accept, matchingViewModel::decline)
                    }
                    Screen.DETAIL -> CarpoolDetailScreen(
                        state = state,
                        onSetRoute = { carpoolViewModel.startGuidance() },
                        onOpenNavigation = { carpoolViewModel.openNavigation(NaviScreen.MAP) },
                        onShowRouteOverview = { carpoolViewModel.openNavigation(NaviScreen.ROUTE_OVERVIEW) },
                        onConfirmBoarding = carpoolViewModel::confirmBoarding,
                        onMarkNoShow = carpoolViewModel::markNoShow,
                    )
                    Screen.DRIVING -> DrivingScreen(
                        state = state,
                        onOpenNavigation = { carpoolViewModel.openNavigation(NaviScreen.MAP) },
                        onConfirmBoarding = carpoolViewModel::confirmBoarding,
                        onStartCarpool = { carpoolViewModel.startGuidance() },
                        onRefresh = carpoolViewModel::refreshNavi,
                    )
                    Screen.NAVI_POC -> {
                        val pocViewModel: NaviPocViewModel = viewModel(factory = NaviPocViewModel.Factory)
                        val pocState by pocViewModel.uiState.collectAsStateWithLifecycle()
                        NaviPocScreen(pocState, pocViewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    selected: Tab?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    onOpenSettings: () -> Unit,
    naviProvider: NaviProvider,
    naviConnection: NaviConnectionState,
    driving: DrivingState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTargetHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = TouchTargetHeight)) {
                Text("‹", style = MaterialTheme.typography.headlineLarge)
            }
        }
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(40.dp))
                    .background(DsColors.SurfaceHigh)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Tab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isSelected -> DsColors.OnSurface
                            tab.screen == null -> DsColors.OnSurfaceMuted.copy(alpha = 0.6f)
                            else -> DsColors.OnSurfaceMuted
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(40.dp))
                            .background(if (isSelected) DsColors.Surface else DsColors.SurfaceHigh)
                            .clickable { onSelectTab(tab) }
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                    )
                }
            }
        }
        val (naviText, naviColor) = when (naviConnection) {
            is NaviConnectionState.Connected ->
                (if (naviProvider == NaviProvider.MOCK) "내비 Mock" else "내비 연결") to DsColors.Positive
            NaviConnectionState.Connecting -> "내비 연결 중" to DsColors.Info
            NaviConnectionState.Disconnected -> "내비 미연결" to DsColors.OnSurfaceMuted
            is NaviConnectionState.Failed -> "내비 연결 실패" to DsColors.Danger
        }
        StatusBadge(naviText, naviColor)
        val drivingText = when {
            driving.restricted && driving.source == DrivingState.Source.SIMULATED -> "주행(시뮬)"
            driving.restricted -> "주행 중"
            else -> "정차"
        }
        StatusBadge(drivingText, if (driving.restricted) DsColors.Warning else DsColors.OnSurfaceMuted)
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = TouchTargetHeight)) {
            Text("⚙", style = MaterialTheme.typography.headlineMedium, color = DsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DsColors.Surface, MaterialTheme.shapes.large)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = DsColors.Warning, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTargetHeight)) {
            Text("확인", style = MaterialTheme.typography.labelLarge)
        }
    }
}
