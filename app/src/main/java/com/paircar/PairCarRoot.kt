package com.paircar

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paircar.core.navigation.Screen
import com.paircar.core.ui.components.BackIcon
import com.paircar.core.ui.components.CheckBadgeIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GearIcon
import com.paircar.core.ui.components.TouchTargetHeight
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.DrivingPolicy
import com.paircar.domain.model.NaviScreen
import com.paircar.feature.carpool.CarpoolViewModel
import com.paircar.feature.crew.CrewScreen
import com.paircar.feature.crew.CrewViewModel
import com.paircar.feature.driving.DrivingScreen
import com.paircar.feature.history.HistoryScreen
import com.paircar.feature.history.HistoryViewModel
import com.paircar.domain.model.CrewSetupState
import com.paircar.feature.crewsetup.CrewSetupScreen
import com.paircar.feature.crewsetup.NoCrewScreen
import com.paircar.feature.crewsetup.CrewSetupViewModel
import com.paircar.feature.home.HomeScreen
import com.paircar.feature.matching.MatchingScreen
import com.paircar.feature.matching.MatchingViewModel
import com.paircar.feature.navipoc.NaviPocScreen
import com.paircar.feature.navipoc.NaviPocViewModel
import com.paircar.feature.settings.SettingsScreen
import com.paircar.feature.settings.SettingsViewModel
import com.paircar.feature.settlement.SettlementScreen
import com.paircar.feature.settlement.SettlementViewModel
import com.paircar.feature.startup.StartupScreen
import com.paircar.feature.startup.StartupViewModel
import kotlinx.coroutines.delay

private val BackStackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { it.name } },
    restore = { names -> names.map(Screen::valueOf) },
)

/** 확정 시안의 상단 탭 */
private enum class Tab(val label: String, val screen: Screen) {
    TODAY("오늘의 카풀", Screen.HOME),
    CREW("내 크루", Screen.CREW),
    HISTORY("운행 기록", Screen.HISTORY),
    SETTLEMENT("월별 정산", Screen.SETTLEMENT),
}

private fun Screen.tab(): Tab? = when (this) {
    Screen.HOME, Screen.DRIVING -> Tab.TODAY
    Screen.CREW, Screen.MATCHING -> Tab.CREW
    Screen.HISTORY -> Tab.HISTORY
    Screen.SETTLEMENT -> Tab.SETTLEMENT
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
    val setupViewModel: CrewSetupViewModel = viewModel(factory = CrewSetupViewModel.Factory)
    val setupState by setupViewModel.uiState.collectAsStateWithLifecycle()

    // 운행 중에 창을 닫았다가 다시 열면 시작 화면 없이 주행 화면으로 바로 돌아온다
    val resumingTrip = remember { carpoolViewModel.uiState.value.trip?.status == CarpoolStatus.IN_PROGRESS }
    var backStack by rememberSaveable(stateSaver = BackStackSaver) {
        mutableStateOf(if (resumingTrip) listOf(Screen.HOME, Screen.DRIVING) else listOf(Screen.HOME))
    }
    val current = backStack.last()
    val shown = if (DrivingPolicy.isAllowed(current.action, state.driving)) current else Screen.DRIVING
    val navigate: (Screen) -> Unit = { screen -> if (backStack.last() != screen) backStack = backStack + screen }
    val canGoBack = backStack.size > 1 && shown == current
    val notReady: (String) -> Unit = carpoolViewModel::showMessage
    // 앱을 켤 때 한 번, 오늘의 카풀 자리에 차량 연결 확인 화면을 먼저 보여준다
    var startupDone by rememberSaveable { mutableStateOf(resumingTrip) }
    // 내비와 나란히 놓인 좁은 창(3분할)에서 운행 중이면 탭 없이 주행 패널만 보여준다
    val narrow = LocalConfiguration.current.screenWidthDp < ExpandedWidthThreshold.value
    val panelMode = narrow && shown == Screen.DRIVING && state.trip?.status == CarpoolStatus.IN_PROGRESS
    val activity = LocalActivity.current
    // 연결 확인을 마치고 넘어오면 오늘의 카풀에서 주행 시작 카운트다운을 한다
    var autoStartPending by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { backStack = backStack.dropLast(1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (!panelMode) {
                    TopBar(
                        selected = shown.tab(),
                        settingsSelected = shown == Screen.SETTINGS,
                        canGoBack = canGoBack,
                        onBack = { backStack = backStack.dropLast(1) },
                        onHome = { backStack = listOf(Screen.HOME) },
                        onSelectTab = { tab -> backStack = listOf(tab.screen) },
                        onOpenSettings = { navigate(Screen.SETTINGS) },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 크루·일정이 없으면 내 크루 자리에 [크루 만들기]를 보여준다 (만든 뒤 QR 을 닫을 때까지)
                    val crewSetup: @Composable () -> Unit = {
                        val currentLocation by setupViewModel.currentLocation.collectAsStateWithLifecycle()
                        CrewSetupScreen(
                            state = setupState,
                            current = currentLocation,
                            onUpdate = setupViewModel::update,
                            onCreate = { setupViewModel.create(notReady) },
                            onRegenerateInvite = setupViewModel::createInvite,
                            onFinish = {
                                setupViewModel.finish()
                                // QR 을 닫으면 내 크루 탭에서 만든 크루를 보여준다
                            },
                        )
                    }
                    val needsSetup = state.trip == null || setupState.createdCrew != null
                    when (shown) {
                        Screen.HOME -> if (startupDone && state.trip == null) {
                            // 크루가 없으면 안내만 두고, 크루 만들기는 내 크루 탭에서 한다
                            NoCrewScreen(
                                loading = setupState.setupState == CrewSetupState.LOADING,
                                hasCrew = setupState.existingCrew != null,
                                onCreateCrew = { backStack = listOf(Screen.CREW) },
                            )
                        } else if (!startupDone) {
                            val startupViewModel: StartupViewModel = viewModel(factory = StartupViewModel.Factory)
                            val startupState by startupViewModel.uiState.collectAsStateWithLifecycle()
                            StartupScreen(startupState, onRetry = startupViewModel::start, onFinish = {
                                startupDone = true
                                autoStartPending = startupState.done
                            })
                        } else {
                            HomeScreen(
                                state = state,
                                autoStart = autoStartPending,
                                onAutoStartHandled = { autoStartPending = false },
                                onStartCarpool = { carpoolViewModel.startGuidance { navigate(Screen.DRIVING) } },
                                onPrepareNextTrip = carpoolViewModel::prepareNextTrip,
                                onOpenDriving = { navigate(Screen.DRIVING) },
                                onOpenMatching = { navigate(Screen.MATCHING) },
                            )
                        }
                        Screen.CREW -> if (needsSetup) {
                            crewSetup()
                        } else {
                            val crewViewModel: CrewViewModel = viewModel(factory = CrewViewModel.Factory)
                            val invite by crewViewModel.invite.collectAsStateWithLifecycle()
                            val crewSettlement by crewViewModel.settlement.collectAsStateWithLifecycle()
                            CrewScreen(
                                state = state,
                                invite = invite,
                                onOpenInvite = crewViewModel::ensureInvite,
                                onRegenerateInvite = crewViewModel::createInvite,
                                settlement = crewSettlement,
                                onSaveCrewInfo = { crewViewModel.saveCrewInfo(it, notReady) },
                                onSaveAttendance = { changes -> state.trip?.let { crewViewModel.saveAttendance(it, changes, notReady) } },
                                onSaveRules = { crewViewModel.saveRules(it, notReady) },
                                onNotReady = notReady,
                            )
                        }
                        Screen.SETTLEMENT -> {
                            val settlementViewModel: SettlementViewModel = viewModel(factory = SettlementViewModel.Factory)
                            val settlementState by settlementViewModel.uiState.collectAsStateWithLifecycle()
                            // 분담 내역에서 운행을 누르면 운행 기록 탭에서 그 운행을 연다
                            val historyViewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
                            SettlementScreen(
                                state = settlementState,
                                onSelectMonth = settlementViewModel::selectMonth,
                                onSaveSettings = { settlementViewModel.saveSettings(it, notReady) },
                                onRequest = { settlementViewModel.requestSettlement(notReady) },
                                onOpenTrip = { record ->
                                    historyViewModel.openRecord(record)
                                    backStack = listOf(Screen.HISTORY)
                                },
                                onNotReady = notReady,
                            )
                        }
                        Screen.HISTORY -> {
                            val historyViewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
                            val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
                            HistoryScreen(
                                state = historyState,
                                onSelectMonth = historyViewModel::selectMonth,
                                onSelectFilter = historyViewModel::selectFilter,
                                onSelectDate = historyViewModel::selectDate,
                                onSelectRecord = historyViewModel::selectRecord,
                                onMoveWeek = historyViewModel::moveWeek,
                                onMessage = notReady,
                            )
                        }
                        Screen.MATCHING -> {
                            val matchingViewModel: MatchingViewModel = viewModel(factory = MatchingViewModel.Factory)
                            val matchingState by matchingViewModel.uiState.collectAsStateWithLifecycle()
                            MatchingScreen(matchingState, matchingViewModel::accept, matchingViewModel::decline)
                        }
                        Screen.DRIVING -> DrivingScreen(
                            state = state,
                            onOpenNavigation = { carpoolViewModel.openNavigation(NaviScreen.MAP) },
                            onConfirmBoarding = carpoolViewModel::confirmBoarding,
                            onMarkNoShow = carpoolViewModel::markNoShow,
                            onStartCarpool = { carpoolViewModel.startGuidance() },
                            onFinishTrip = carpoolViewModel::finishTrip,
                            // 운행 종료 후에는 방금 저장한 기록(가장 최근)을 보여준다
                            onTripDone = { backStack = listOf(Screen.HISTORY) },
                            panelMode = panelMode,
                            // 창을 닫으면 Pleos 가 그 자리를 내비로 채운다(moveTaskToBack 은 Pleos 창에서 무시돼 finish 를 쓴다).
                            // 운행 상태는 앱 범위(AppContainer)에 있어 앱 아이콘으로 다시 열면 주행 화면으로 돌아온다.
                            onClosePanel = { activity?.finish() },
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
            // 화면 배치와 상관없이 위에 떠서 잠깐 보였다 사라진다
            MessageToast(state.message, carpoolViewModel::dismissMessage)
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
                        color = DsColors.OnSurface,
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

/**
 * 안내 문구 토스트: 화면 아래쪽 가운데에 떠서 잠깐 보였다 사라진다(배치를 밀지 않음).
 * Pleos 에서는 시스템 Toast 가 보이지 않아(SystemUI 가 사용자 0 에서 받아 숨김) 앱 안에 직접 그린다.
 */
@Composable
private fun BoxScope.MessageToast(message: String?, onShown: () -> Unit) {
    // 사라지는 동안에도 글자가 남도록 마지막 문구를 들고 있는다
    var shownText by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        shownText = message
        delay(if (message.length > 30) 3_500 else 2_000)
        onShown()
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 24.dp, end = 24.dp),
    ) {
        Text(
            shownText,
            style = MaterialTheme.typography.bodyLarge,
            color = DsColors.Surface,
            modifier = Modifier
                .widthIn(max = 900.dp)
                .background(DsColors.Dark.copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(horizontal = 36.dp, vertical = 20.dp),
        )
    }
}
