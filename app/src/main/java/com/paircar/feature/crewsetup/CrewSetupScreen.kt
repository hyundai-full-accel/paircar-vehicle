package com.paircar.feature.crewsetup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.model.GeoPoint
import com.paircar.core.ui.components.ChoiceChip
import com.paircar.core.ui.components.DepartIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.FieldBox
import com.paircar.core.ui.components.FieldInput
import com.paircar.core.ui.components.FieldTextStyle
import com.paircar.core.ui.components.FormNote
import com.paircar.core.ui.components.FormSectionTitle
import com.paircar.core.ui.components.PinIcon
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.CrewSetupState
import com.paircar.domain.model.Place
import com.paircar.domain.model.korean
import com.paircar.feature.crew.CrewViewModel
import com.paircar.feature.crew.InviteModal
import com.paircar.feature.crew.TimeStepper
import java.time.DayOfWeek

private enum class PickTarget { ORIGIN, DESTINATION }

/**
 * 크루 만들기 (오늘의 카풀·내 크루가 비어 있을 때).
 * 크루 이름·출발 시각·반복 요일·출발지·도착지를 정하면 크루를 만들고 일정을 저장한 뒤 QR 초대를 보여준다.
 * 이미 크루가 있는데 일정만 없으면 같은 화면에서 일정만 채운다.
 */
@Composable
fun CrewSetupScreen(
    state: CrewSetupUiState,
    current: GeoPoint?,
    onUpdate: ((CrewSetupForm) -> CrewSetupForm) -> Unit,
    onCreate: () -> Unit,
    onRegenerateInvite: () -> Unit,
    onFinish: () -> Unit,
) {
    if (state.setupState == CrewSetupState.LOADING && state.createdCrew == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = DsColors.Brand)
            Text("크루 정보를 불러오는 중이에요", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
        }
        return
    }
    val form = state.form
    val scheduleOnly = state.existingCrew != null
    var picking by rememberSaveable { mutableStateOf<PickTarget?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= ExpandedWidthThreshold
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(DsColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (scheduleOnly) "카풀 일정 설정" else "크루 만들기", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (scheduleOnly) "반복 운행할 출발 시각과 출발지·도착지를 정하면 오늘의 카풀이 만들어져요"
                    else "함께 출퇴근할 크루를 만들고 반복 운행 일정을 정해요. 만들고 나면 QR 로 동승자를 초대할 수 있어요",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DsColors.OnSurfaceMuted,
                )
            }
            HorizontalDivider(color = DsColors.Divider)

            val nameAndTime: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormSectionTitle("크루 이름")
                        FieldBox(null) {
                            FieldInput(form.name, { name -> onUpdate { it.copy(name = name.take(CrewViewModel.MAX_NAME)) } }, placeholder = "예: 판교 출근 크루")
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormSectionTitle("출발 예정 시각")
                        TimeStepper(form.departureTime) { time -> onUpdate { it.copy(departureTime = time) } }
                    }
                }
            }
            val places: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormSectionTitle("출발지")
                        PlaceButton(form.origin, depart = true, hint = "출발지 고르기") { picking = PickTarget.ORIGIN }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormSectionTitle("도착지")
                        PlaceButton(form.destination, depart = false, hint = "도착지 고르기") { picking = PickTarget.DESTINATION }
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    nameAndTime(Modifier.weight(1f))
                    places(Modifier.weight(1f))
                }
            } else {
                nameAndTime(Modifier.fillMaxWidth())
                places(Modifier.fillMaxWidth())
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormSectionTitle("반복 운행 요일")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        ChoiceChip(day.korean(), day in form.days, Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(12.dp)) {
                            onUpdate { it.copy(days = if (day in it.days) it.days - day else it.days + day) }
                        }
                    }
                }
            }
            FormNote("동승자는 크루를 만든 뒤 QR 초대로 들어와 자기 탈 곳을 정해요. 출발지·도착지는 내 크루에서 언제든 바꿀 수 있어요")
            PrimaryActionButton(
                when {
                    state.saving -> "만드는 중…"
                    scheduleOnly -> "일정 저장"
                    else -> "크루 만들기"
                },
                onCreate,
                Modifier.fillMaxWidth().widthIn(max = 560.dp).align(Alignment.CenterHorizontally),
                enabled = !state.saving,
            )
        }

        when (picking) {
            PickTarget.ORIGIN -> PlacePickerModal(
                title = "출발지 고르기",
                initial = form.origin,
                defaultName = "집",
                current = current,
                onPick = { place ->
                    onUpdate { it.copy(origin = place) }
                    picking = null
                },
                onClose = { picking = null },
            )
            PickTarget.DESTINATION -> PlacePickerModal(
                title = "도착지 고르기",
                initial = form.destination,
                defaultName = "회사",
                current = current,
                onPick = { place ->
                    onUpdate { it.copy(destination = place) }
                    picking = null
                },
                onClose = { picking = null },
            )
            null -> Unit
        }

        state.createdCrew?.let { crew -> InviteModal(crew, state.invite, onRegenerateInvite, onFinish) }
    }
}

/** ➤ 집 · 37.53700, 127.08500 / 아직 안 골랐으면 안내 문구 */
@Composable
private fun PlaceButton(place: Place?, depart: Boolean, hint: String, onClick: () -> Unit) {
    FieldBox(null, Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (depart) DepartIcon(Modifier.size(22.dp)) else PinIcon(Modifier.size(22.dp))
            Text(
                place?.let { "${it.name} · %.4f, %.4f".format(it.point.latitude, it.point.longitude) } ?: hint,
                style = FieldTextStyle().let { style -> if (place == null) style.copy(color = DsColors.OnSurfaceMuted) else style },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 오늘의 카풀: 크루가 없으면 빈 화면에 안내와 [크루 만들기] 버튼만 둔다.
 * 크루 만들기는 내 크루 탭에서 한다.
 */
@Composable
fun NoCrewScreen(loading: Boolean, hasCrew: Boolean, onCreateCrew: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface)
            .padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(color = DsColors.Brand)
            Text("크루 정보를 불러오는 중이에요", style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        // 크루는 있는데 출발지·도착지·출발 시각만 없으면 일정을 정하게 한다
        Text(if (hasCrew) "카풀 일정이 없습니다" else "현재 크루가 없습니다", style = MaterialTheme.typography.headlineMedium)
        Text(if (hasCrew) "내 크루에서 출발 시각과 출발지·도착지를 정해주세요" else "크루를 만들어주세요", style = MaterialTheme.typography.bodyLarge, color = DsColors.OnSurfaceMuted)
        PrimaryActionButton(if (hasCrew) "카풀 일정 정하기" else "크루 만들기", onCreateCrew, Modifier.padding(top = 8.dp).widthIn(min = 320.dp))
    }
}
