package com.paircar.feature.crew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.ChevronIcon
import com.paircar.core.ui.components.ChoiceChip
import com.paircar.core.ui.components.DepartIcon
import com.paircar.core.ui.components.DsSwitch
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.FieldBox
import com.paircar.core.ui.components.FieldInput
import com.paircar.core.ui.components.FieldTextStyle
import com.paircar.core.ui.components.FormNote
import com.paircar.core.ui.components.FormSectionTitle
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.PinIcon
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.ConsentStatus
import com.paircar.domain.model.CostRules
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInfo
import com.paircar.domain.model.Place
import com.paircar.domain.model.korean
import com.paircar.core.model.GeoPoint
import com.paircar.feature.crewsetup.PlacePickerModal
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val MINUTE_STEP = 5L

// 시안(2000px 캡처 = 화면 2560px) 비율에서 잰 값
private val ModalWidth = 960.dp
private val DayLabelWidth = 170.dp
private val DayChipHeight = 60.dp

// ───────────────────────── 크루 정보 설정 ─────────────────────────

/**
 * 크루 정보 설정: 이름, 출발 예정 시각, 반복 운행 요일, 출발지·도착지.
 * 출발지·도착지는 지도에서 핀으로 고른다 (장소 검색 API 가 없어 좌표로 정한다).
 */
@Composable
internal fun BoxScope.CrewInfoModal(
    crew: Crew,
    trip: CarpoolTrip,
    current: GeoPoint?,
    onSave: (CrewInfo) -> Unit,
    @Suppress("UNUSED_PARAMETER") onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(crew.name) }
    var time by remember { mutableStateOf(trip.departureTime) }
    var days by remember { mutableStateOf(crew.days) }
    var origin by remember { mutableStateOf(trip.origin) }
    var destination by remember { mutableStateOf(trip.destination) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = 출발지, false = 도착지

    InScreenModal(onDismiss = onClose, maxWidth = ModalWidth) {
        TitleWithSubtitle("크루 정보 설정", "반복 운행에 적용할 정보를 관리해요", onClose)
        HorizontalDivider(color = DsColors.Divider)
        SideBySide(
            first = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormSectionTitle("크루 이름")
                    FieldBox(null) { FieldInput(name, { name = it.take(CrewViewModel.MAX_NAME) }, placeholder = "크루 이름") }
                }
            },
            second = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormSectionTitle("출발 예정 시각")
                    TimeStepper(time) { time = it }
                }
            },
        )
        HorizontalDivider(color = DsColors.Divider)
        // 시안: 왼쪽에 이름, 오른쪽 남은 폭을 7개 요일이 똑같이 나눠 오른쪽 끝까지 채운다
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chips: @Composable (Modifier) -> Unit = { modifier ->
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        ChoiceChip(
                            day.korean(),
                            day in days,
                            Modifier.weight(1f).height(DayChipHeight),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            days = if (day in days) days - day else days + day
                        }
                    }
                }
            }
            if (maxWidth >= ExpandedWidthThreshold * 0.8f) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(DayLabelWidth)) { FormSectionTitle("반복 운행 요일") }
                    chips(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormSectionTitle("반복 운행 요일")
                    chips(Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider(color = DsColors.Divider)
        SideBySide(
            first = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormSectionTitle("출발지")
                    PlaceField(origin, depart = true, onClick = { picking = true })
                }
            },
            second = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormSectionTitle("도착지")
                    PlaceField(destination, depart = false, onClick = { picking = false })
                }
            },
        )
        FormNote(
            if (trip.status == CarpoolStatus.SCHEDULED) "출발지·목적지를 변경하면 경유지와 예상 이동 시간이 달라질 수 있어요"
            else "운행을 시작해 오늘 시각은 바뀌지 않아요. 바꾼 시각은 다음 운행부터 적용돼요",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryActionButton(
                "변경 사항 저장",
                {
                    onSave(
                        CrewInfo(
                            name, time, days,
                            origin = origin.takeIf { it != trip.origin },
                            destination = destination.takeIf { it != trip.destination },
                        ),
                    )
                },
                Modifier.weight(1f),
            )
            TonalActionButton("닫기", onClose, Modifier.weight(1f))
        }
    }

    when (picking) {
        true -> PlacePickerModal("출발지 고르기", origin, "집", current, { origin = it; picking = null }, { picking = null })
        false -> PlacePickerModal("도착지 고르기", destination, "회사", current, { destination = it; picking = null }, { picking = null })
        null -> Unit
    }
}

/** 7 : 10 ⇕ (5분씩) · AM ⇕ */
@Composable
internal fun TimeStepper(time: LocalTime, onChange: (LocalTime) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 시안 비율: 시각 칸 : AM 칸 ≈ 1.7 : 1
        FieldBox(null, Modifier.weight(1.7f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${time.hour.let { if (it % 12 == 0) 12 else it % 12 }}  :  ${"%02d".format(time.minute)}",
                    style = FieldTextStyle(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                UpDown(
                    onUp = { onChange(time.plusMinutes(MINUTE_STEP)) },
                    onDown = { onChange(time.minusMinutes(MINUTE_STEP)) },
                )
            }
        }
        FieldBox(null, Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (time.hour < 12) "AM" else "PM", style = FieldTextStyle(), modifier = Modifier.weight(1f))
                UpDown(onUp = { onChange(time.plusHours(12)) }, onDown = { onChange(time.minusHours(12)) })
            }
        }
    }
}

@Composable
private fun UpDown(onUp: () -> Unit, onDown: () -> Unit) {
    Column {
        Box(Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onUp), contentAlignment = Alignment.Center) {
            ChevronIcon(Modifier.size(22.dp).rotate(-90f), color = DsColors.OnSurface)
        }
        Box(Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDown), contentAlignment = Alignment.Center) {
            ChevronIcon(Modifier.size(22.dp).rotate(90f), color = DsColors.OnSurface)
        }
    }
}

/** ➤ 집 · 서울 광진구 (장소 이름 · 시·구) */
@Composable
private fun PlaceField(place: Place, depart: Boolean, onClick: () -> Unit) {
    FieldBox(null, Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (depart) DepartIcon(Modifier.size(22.dp)) else PinIcon(Modifier.size(22.dp))
            Text(if (place.area.isBlank()) place.name else "${place.name} · ${place.area}", style = FieldTextStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────── 탑승·픽업 관리 ─────────────────────────

/**
 * 탑승·픽업 관리: 오늘 픽업 경유지(최대 2곳)와 크루 멤버별 픽업 장소·예정 시각.
 * [픽업 정보 수정]을 누르면 동승자별 오늘 참석/불참을 바꿀 수 있다(출발 전만).
 */
@Composable
internal fun BoxScope.PickupModal(
    trip: CarpoolTrip,
    onSave: (Map<String, Attendance>) -> Unit,
    onClose: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var changes by remember { mutableStateOf(emptyMap<String, Attendance>()) }
    val attendanceOf = { id: String, current: Attendance -> changes[id] ?: current }
    // 고치는 중이면 바뀐 참석으로 경유지를 다시 보여준다
    val pickups = trip.acceptedPassengers
        .filter { attendanceOf(it.id, it.attendance) == Attendance.ATTENDING }
        .sortedBy { it.estimatedPickupTime }
    val canEdit = trip.status == CarpoolStatus.SCHEDULED

    InScreenModal(onDismiss = onClose, maxWidth = ModalWidth) {
        TitleWithSubtitle("탑승·픽업 관리", "오늘 운행의 픽업 경유지와 크루 멤버를 관리해요", onClose)
        HorizontalDivider(color = DsColors.Divider)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormSectionTitle("현재 픽업 경유지")
            GrayChip("최대 ${CrewViewModel.MAX_PICKUPS}곳")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(CrewViewModel.MAX_PICKUPS) { index ->
                val passenger = pickups.getOrNull(index)
                FieldBox(null, Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PinIcon(Modifier.size(22.dp), color = if (passenger == null) DsColors.OnSurfaceMuted else DsColors.OnSurface)
                        Text(
                            passenger?.pickup?.name ?: "-",
                            style = FieldTextStyle().copy(color = if (passenger == null) DsColors.OnSurfaceMuted else DsColors.OnSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (pickups.size > CrewViewModel.MAX_PICKUPS) {
            Text("픽업 경유지는 최대 ${CrewViewModel.MAX_PICKUPS}곳까지예요. 참석 인원을 줄여 주세요.", style = MaterialTheme.typography.bodyMedium, color = DsColors.Danger)
        }
        HorizontalDivider(color = DsColors.Divider)
        FormSectionTitle("크루 멤버")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DsColors.Divider, RoundedCornerShape(16.dp)),
        ) {
            MemberLine(trip.driver.name, "운전자 · ${trip.origin.name}에서 탑승", "${trip.departureTime.hm()} 예정") {}
            trip.acceptedPassengers.forEach { passenger ->
                val attendance = attendanceOf(passenger.id, passenger.attendance)
                HorizontalDivider(color = DsColors.Divider)
                MemberLine(
                    name = passenger.name,
                    detail = "동승자 · ${passenger.pickup.name}",
                    trailing = if (attendance == Attendance.ATTENDING) "${passenger.estimatedPickupTime.hm()} 예정" else "오늘 불참",
                ) {
                    if (editing) {
                        ChoiceChip(if (attendance == Attendance.ATTENDING) "참석" else "불참", attendance == Attendance.ATTENDING) {
                            val next = if (attendance == Attendance.ATTENDING) Attendance.ABSENT else Attendance.ATTENDING
                            changes = if (next == passenger.attendance) changes - passenger.id else changes + (passenger.id to next)
                        }
                    }
                }
            }
        }
        FormNote(
            if (canEdit) "경유지를 변경할 때: 탑승자 변경사항을 확인한 후 내비 경로에 반영해요. (주행 중 직접 편집 제한)"
            else "운행을 시작해 픽업 정보를 바꿀 수 없어요. 주행 화면에서 탑승 완료·미탑승으로 처리해요",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing) {
                PrimaryActionButton("픽업 정보 저장", { onSave(changes) }, Modifier.weight(1f))
                TonalActionButton("취소", {
                    editing = false
                    changes = emptyMap()
                }, Modifier.weight(1f))
            } else {
                PrimaryActionButton("픽업 정보 수정", { editing = true }, Modifier.weight(1f), enabled = canEdit)
                TonalActionButton("닫기", onClose, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemberLine(name: String, detail: String, trailing: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        action()
    }
}

// ───────────────────────── 정산 기준 관리 ─────────────────────────

/**
 * 정산 기준 관리: 분담 방식과 비용 항목(월별 정산 [정산 정보 설정]과 같은 값), 크루원 동의 현황.
 * 동의는 동승자 앱에서 받는 값이라 다시 요청하는 기능은 서버 연동 후에 생긴다.
 */
@Composable
internal fun BoxScope.RulesModal(
    trip: CarpoolTrip,
    state: CrewSettlementState,
    onSave: (CostRules) -> Unit,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    var rules by remember { mutableStateOf(state.rules) }

    InScreenModal(onDismiss = onClose, maxWidth = ModalWidth) {
        TitleWithSubtitle("정산 기준 관리", "동승 전에 합의할 비용 분담 기준", onClose)
        HorizontalDivider(color = DsColors.Divider)
        RuleLine("분담 방식", "각자 실제 탑승한 구간 기준") { GrayChip("기본 적용") }
        HorizontalDivider(color = DsColors.Divider)
        RuleLine("충전비", "운행 비용에 포함") { DsSwitch(rules.includeCharge) { rules = rules.copy(includeCharge = it) } }
        RuleLine("통행료", "운행 비용에 포함") { DsSwitch(rules.includeToll) { rules = rules.copy(includeToll = it) } }
        RuleLine("단위 올림", "100원 단위 · 총액 기준") { DsSwitch(rules.roundUp) { rules = rules.copy(roundUp = it) } }
        HorizontalDivider(color = DsColors.Divider)
        FormSectionTitle("크루원 동의 현황")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, DsColors.Divider, RoundedCornerShape(16.dp)),
        ) {
            trip.acceptedPassengers.forEachIndexed { index, passenger ->
                if (index > 0) HorizontalDivider(color = DsColors.Divider)
                MemberLine(passenger.name, "동승자 · ${passenger.pickup.name}", "") {
                    when (state.consents[passenger.name]) {
                        ConsentStatus.AGREED -> GrayChip("동의 확인")
                        ConsentStatus.NEEDED -> Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(DsColors.SurfaceHigh)
                                .clickable { onMessage("동의 요청은 동승자 앱 연동 후 보낼 수 있어요") }
                                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("응답 확인 필요", style = MaterialTheme.typography.labelSmall, color = DsColors.OnSurfaceMuted)
                            ChevronIcon(Modifier.size(20.dp))
                        }
                        null -> GrayChip("정산 이력 없음")
                    }
                }
            }
        }
        FormNote("정산 기준을 변경하면 기존 동승자의 동의를 다시 확인해야 해요. 월별 정산 비용 기준과 같은 설정이에요.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryActionButton("정산 기준 저장", { onSave(rules) }, Modifier.weight(1f))
            TonalActionButton("닫기", onClose, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RuleLine(title: String, detail: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(140.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ───────────────────────── 공통 ─────────────────────────

@Composable
private fun TitleWithSubtitle(title: String, subtitle: String, onClose: () -> Unit) {
    Column {
        ModalTitle(title, onClose)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
    }
}

/** 넓으면 나란히, 좁으면 위아래 */
@Composable
private fun SideBySide(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= ExpandedWidthThreshold * 0.8f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

private fun LocalTime.hm(): String = format(HourMinute)
