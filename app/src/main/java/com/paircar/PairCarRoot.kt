package com.paircar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paircar.core.navigation.Screen
import com.paircar.core.ui.components.BackIcon
import com.paircar.core.ui.components.CheckBadgeIcon
import com.paircar.core.ui.components.GearIcon
import com.paircar.core.ui.components.TouchTargetHeight
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.DrivingPolicy
import com.paircar.domain.model.NaviScreen
import com.paircar.feature.carpool.CarpoolDetailScreen
import com.paircar.feature.carpool.CarpoolViewModel
import com.paircar.feature.crew.CrewScreen
import com.paircar.feature.crew.CrewViewModel
import com.paircar.feature.driving.DrivingScreen
import com.paircar.feature.home.HomeScreen
import com.paircar.feature.matching.MatchingScreen
import com.paircar.feature.matching.MatchingViewModel
import com.paircar.feature.navipoc.NaviPocScreen
import com.paircar.feature.navipoc.NaviPocViewModel
import com.paircar.feature.settings.SettingsScreen
import com.paircar.feature.settings.SettingsViewModel

private val BackStackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.map(Screen::valueOf) },
)

/** 확정 시안의 상단 탭. 월별 정산은 이번 MVP 제외 기능 */
private enum class Tab(val label: String, val screen: Screen?) {
    TODAY("오늘의 카풀", Screen.HOME),
    CREW("내 크루", Screen.CREW),
    HISTORY("운행 기록", Screen.DETAIL),
    SETTLEMENT("월별 정산", null),
}

private fun Screen.tab(): Tab? = when (this) {
    Screen.HOME, Screen.DRIVING -> Tab.TODAY
    Screen.CREW, Screen.MATCHING -> Tab.CREW
    Screen.DETAIL -> Tab.HISTORY
    Screen.SETTINGS, Screen.NAVI_POC -> null
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
    val notReady: (String) -> Unit = carpoolViewModel::showMessage

    BackHandler(enabled = canGoBack) { backStack = backStack.dropLast(1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TopBar(
                selected = shown.tab(),
                settingsSelected = shown == Screen.SETTINGS,
                canGoBack = canGoBack,
                onBack = { backStack = backStack.dropLast(1) },
                onHome = { backStack = listOf(Screen.HOME) },
                onSelectTab = { tab ->
                    val screen = tab.screen
                    if (screen == null) notReady("월별 정산은 다음 단계에서 제공돼요") else backStack = listOf(screen)
                },
                onOpenSettings = { navigate(Screen.SETTINGS) },
            )
            state.message?.let { MessageBanner(it, carpoolViewModel::dismissMessage) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (shown) {
                    Screen.HOME -> HomeScreen(
                        state = state,
                        onStartCarpool = { carpoolViewModel.startGuidance { navigate(Screen.DRIVING) } },
                        onOpenDriving = { navigate(Screen.DRIVING) },
                        onOpenMatching = { navigate(Screen.MATCHING) },
                    )
                    Screen.CREW -> {
                        val crewViewModel: CrewViewModel = viewModel(factory = CrewViewModel.Factory)
                        val invite by crewViewModel.invite.collectAsStateWithLifecycle()
                        CrewScreen(
                            state = state,
                            invite = invite,
                            onOpenInvite = crewViewModel::ensureInvite,
                            onRegenerateInvite = crewViewModel::createInvite,
                            onOpenHistory = { backStack = listOf(Screen.DETAIL) },
                            onNotReady = notReady,
                        )
                    }
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
                    Screen.SETTINGS -> {
                        val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
                        val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
                        SettingsScreen(
                            state = settingsState,
                            onRecheck = settingsViewModel::recheck,
                            onDisconnect = settingsViewModel::disconnect,
                            onOpenDiagnostics = { navigate(Screen.NAVI_POC) },
                            onNotReady = notReady,
                        )
                    }
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

/** [로고/뒤로] [오늘의 카풀 | 내 크루 | 운행 기록 | 월별 정산] [설정] */
@Composable
private fun TopBar(
    selected: Tab?,
    settingsSelected: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onSelectTab: (Tab) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TouchTargetHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SquareButton(onClick = if (canGoBack) onBack else onHome, selected = false) {
            if (canGoBack) BackIcon(Modifier.size(36.dp)) else CheckBadgeIcon(Modifier.size(36.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(22.dp))
                .background(DsColors.Segment)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEachIndexed { index, tab ->
                val isSelected = tab == selected
                // 선택된 탭 양옆에는 구분선을 그리지 않는다(시안)
                val showDivider = index > 0 && !isSelected && Tab.entries[index - 1] != selected
                if (index > 0) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(if (showDivider) DsColors.Divider else DsColors.Segment),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (isSelected) Modifier.shadow(2.dp, RoundedCornerShape(18.dp)) else Modifier)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isSelected) DsColors.Surface else DsColors.Segment)
                        .clickable { onSelectTab(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (tab.screen == null) DsColors.OnSurfaceMuted else DsColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SquareButton(onClick = onOpenSettings, selected = settingsSelected) {
            GearIcon(Modifier.size(36.dp), hole = if (settingsSelected) DsColors.Segment else DsColors.Surface)
        }
    }
}

@Composable
private fun SquareButton(onClick: () -> Unit, selected: Boolean, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(TouchTargetHeight)
            .shadow(if (selected) 0.dp else 2.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) DsColors.Segment else DsColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
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
