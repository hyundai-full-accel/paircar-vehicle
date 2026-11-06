package com.paircar.feature.settlement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paircar.R
import com.paircar.core.ui.components.ActionTile
import com.paircar.core.ui.components.ChevronIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.InitialAvatar
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.Panel
import com.paircar.core.ui.components.PlusIcon
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.ScrollColumn
import com.paircar.core.ui.components.SectionHeader
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.components.headerBackground
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.EfficiencySource
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.PaymentStatus
import com.paircar.domain.model.SettlementReceipt
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface SettlementModal {
    data object None : SettlementModal
    data class Receipt(val name: String) : SettlementModal
    data object Policy : SettlementModal
}

/** 월별 정산: 총 정산 금액, 멤버별 현황, 고른 멤버의 영수증·계좌 */
@Composable
fun SettlementScreen(
    state: SettlementUiState,
    onSelectMonth: (YearMonth) -> Unit,
    onOpenHistory: () -> Unit,
    onNotReady: (String) -> Unit,
) {
    val settlement = state.settlement
    if (settlement == null) {
        EmptySettlement()
        return
    }
    // 처음에는 아직 받을 돈이 있는 멤버를 보여준다
    var selectedName by rememberSaveable(settlement.month) {
        mutableStateOf(settlement.receipts.firstOrNull { it.dueWon > 0 }?.member?.name ?: settlement.members.first().name)
    }
    var modal by remember { mutableStateOf<SettlementModal>(SettlementModal.None) }
    val selected = settlement.receipts.first { it.member.name == selectedName }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val summary: @Composable (Modifier, Boolean) -> Unit = { modifier, fillHeight ->
            SummaryCard(
                settlement = settlement,
                months = state.months,
                selectedName = selectedName,
                onSelectMonth = onSelectMonth,
                onSelectMember = { selectedName = it },
                onOpenReceipt = { modal = SettlementModal.Receipt(it) },
                onOpenPolicy = { modal = SettlementModal.Policy },
                onOpenHistory = onOpenHistory,
                onNotReady = onNotReady,
                fillHeight = fillHeight,
                modifier = modifier,
            )
        }
        val receiptPanel: @Composable (Modifier, Boolean) -> Unit = { modifier, fillHeight ->
            ReceiptPanel(
                settlement = settlement,
                receipt = selected,
                onOpenReceipt = { modal = SettlementModal.Receipt(selected.member.name) },
                onRegisterAccount = { onNotReady("수취 계좌 등록은 다음 단계에서 제공돼요") },
                fillHeight = fillHeight,
                modifier = modifier,
            )
        }
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                summary(Modifier.weight(1.75f).fillMaxHeight(), true)
                receiptPanel(Modifier.weight(1f).fillMaxHeight(), true)
            }
        } else {
            ScrollColumn {
                summary(Modifier.fillMaxWidth(), false)
                receiptPanel(Modifier.fillMaxWidth(), false)
            }
        }

        when (val shown = modal) {
            is SettlementModal.Receipt -> {
                val receipt = settlement.receipts.first { it.member.name == shown.name }
                ReceiptModal(settlement, receipt) { modal = SettlementModal.None }
            }
            SettlementModal.Policy -> PolicyModal(settlement) { modal = SettlementModal.None }
            SettlementModal.None -> Unit
        }
    }
}

@Composable
private fun EmptySettlement() {
    Panel(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("아직 정산할 운행이 없어요", style = MaterialTheme.typography.titleMedium)
                Text(
                    "운행을 마치면 이번 달 정산 내역이 여기에 쌓여요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DsColors.OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    settlement: MonthlySettlement,
    months: List<YearMonth>,
    selectedName: String,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectMember: (String) -> Unit,
    onOpenReceipt: (String) -> Unit,
    onOpenPolicy: () -> Unit,
    onOpenHistory: () -> Unit,
    onNotReady: (String) -> Unit,
    fillHeight: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface),
    ) {
        // 시안의 머리 영역 배경 그림
        Column(
            Modifier
                .fillMaxWidth()
                .headerBackground(R.drawable.bg_header_settlement)
                .padding(start = 36.dp, end = 36.dp, top = 32.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MonthPicker(settlement.month, months, onSelectMonth)
                Text(
                    settlement.month.period(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DsColors.OnSurfaceMuted,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(DsColors.Dark)
                        .clickable { onNotReady("정산 항목 추가는 다음 단계에서 제공돼요") },
                    contentAlignment = Alignment.Center,
                ) {
                    PlusIcon(Modifier.size(28.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("총 정산 금액", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    "${settlement.totalDueWon.won()} 원",
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrayChip("${settlement.carpoolDays}회 동승")
                GrayChip("${settlement.sharedKm.km()}km 동승 구간")
                GrayChip(if (settlement.requested) "정산 요청 완료" else "정산 요청 전")
            }
        }
        Column(
            Modifier
                .then(if (fillHeight) Modifier.weight(1f) else Modifier)
                .padding(start = 36.dp, end = 36.dp, top = 12.dp, bottom = 32.dp),
        ) {
            SectionHeader("멤버별 정산 현황") {
                Text(
                    "편집",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { onNotReady("정산 멤버 편집은 다음 단계에서 제공돼요") }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            settlement.receipts.forEach { receipt ->
                MemberRow(
                    receipt = receipt,
                    selected = receipt.member.name == selectedName,
                    onSelect = { onSelectMember(receipt.member.name) },
                    onOpenReceipt = { onOpenReceipt(receipt.member.name) },
                )
            }
            // 넓은 화면에서는 타일을 카드 아래에 붙인다(시안)
            if (fillHeight) Spacer(Modifier.weight(1f))
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 24.dp), color = DsColors.Divider)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionTile("정산 정보 입력", { onNotReady("전비·충전 단가 입력은 다음 단계에서 제공돼요") }, Modifier.weight(1f))
                ActionTile("운행 내역", onOpenHistory, Modifier.weight(1f))
                ActionTile("정산 기준 확인", onOpenPolicy, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MonthPicker(month: YearMonth, months: List<YearMonth>, onSelect: (YearMonth) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(month.format(MonthFormat), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            ChevronIcon(Modifier.size(28.dp).rotate(90f), color = DsColors.OnSurface)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            months.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.format(MonthFormat), style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun MemberRow(receipt: SettlementReceipt, selected: Boolean, onSelect: () -> Unit, onOpenReceipt: () -> Unit) {
    val member = receipt.member
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) DsColors.SurfaceHigh.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(enabled = !member.isExcluded, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InitialAvatar(member.name)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(member.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!member.isExcluded) GrayChip(if (member.payment == PaymentStatus.PAID) "입금 완료" else "입금 미확인")
            }
            Text(
                if (member.isExcluded) "탑승 이력 없음 · 정산 제외" else "${member.rides}회 탑승 · 실비 분담",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
        Text("${receipt.dueWon.won()}원", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Box(Modifier.width(1.dp).height(24.dp).background(DsColors.Divider))
        // 받을 돈이 남은 멤버만 영수증을 만든다
        Box(Modifier.width(150.dp), contentAlignment = Alignment.CenterEnd) {
            if (receipt.dueWon > 0) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenReceipt)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("영수증 생성", style = MaterialTheme.typography.labelLarge)
                    ChevronIcon(Modifier.size(24.dp), color = DsColors.OnSurface)
                }
            }
        }
    }
}

@Composable
private fun ReceiptPanel(
    settlement: MonthlySettlement,
    receipt: SettlementReceipt,
    onOpenReceipt: () -> Unit,
    onRegisterAccount: () -> Unit,
    fillHeight: Boolean,
    modifier: Modifier,
) {
    Panel(modifier) {
        SectionHeader("영수증 · 계좌")
        AmountRow("충전비", "${receipt.chargeWon.won()}원")
        AmountRow("통행료", "${receipt.tollWon.won()}원")
        AmountRow("${settlement.policy.roundUpUnit}원 단위 올림", "+${receipt.roundingWon.won()}원")
        HorizontalDivider(color = DsColors.Divider)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("최종 금액", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${receipt.totalWon.won()}원",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        HorizontalDivider(color = DsColors.Divider)
        AmountRow("받는 사람", settlement.receiverName)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("수취 계좌", style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
            settlement.receiverAccount?.let { Text(it, style = MaterialTheme.typography.labelMedium) } ?: GrayChip("! 수취 계좌 미등록")
        }
        Text(
            "실제 송금은 은행앱에서 진행해요.",
            style = MaterialTheme.typography.bodySmall,
            color = DsColors.OnSurfaceMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        // 넓은 화면에서만 버튼을 카드 아래에 붙인다(좁은 창은 스크롤)
        if (fillHeight) Spacer(Modifier.weight(1f))
        PrimaryActionButton("영수증 상세 보기", onOpenReceipt, Modifier.fillMaxWidth())
        TonalActionButton(
            if (settlement.receiverAccount == null) "수취 계좌 등록" else "수취 계좌 변경",
            onRegisterAccount,
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

/** 정산 영수증: 부담 거리 → 충전비 → 최종 부담액 계산 과정 */
@Composable
private fun BoxScope.ReceiptModal(
    settlement: MonthlySettlement,
    receipt: SettlementReceipt,
    onClose: () -> Unit,
) {
    val member = receipt.member
    val policy = settlement.policy
    InScreenModal(onDismiss = onClose, maxWidth = 760.dp) {
        ModalTitle("정산 영수증", onClose)
        Text("${settlement.crewName} · ${member.name} 님", style = MaterialTheme.typography.labelLarge)
        HorizontalDivider(color = DsColors.Divider)
        AmountRow("함께 탄 거리 / 인원", "${member.sharedKm.km()}km ÷ ${member.headcount} = ${receipt.sharedShareKm.km()}km")
        AmountRow(
            "추가 우회거리",
            "${"%.1f".format(member.detourKmPerRide)}km × ${member.rides}일 = ${receipt.detourKm.km()}km",
        )
        AmountRow("부담 거리", "${receipt.burdenKm.km()}km")
        AmountRow(
            "전비(${policy.efficiencySource.label()}) / 충전 단가",
            "${policy.efficiencyKmPerKwh.efficiency()}km/kWh · ${policy.chargePricePerKwh.toLong().won()}원/kWh",
        )
        AmountRow("충전비 산출", "${receipt.chargeWon.won()}원 (반올림 표기)")
        AmountRow("통행료 · 별도 배분", "${receipt.tollWon.won()}원")
        HorizontalDivider(color = DsColors.Divider)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("최종 부담액", style = MaterialTheme.typography.titleMedium)
                Text("${policy.roundUpUnit}원 단위 올림", style = MaterialTheme.typography.bodySmall)
            }
            Text("${receipt.totalWon.won()}원", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoIcon(Modifier.size(18.dp))
            Text(receipt.paymentNote(settlement), style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun BoxScope.PolicyModal(settlement: MonthlySettlement, onClose: () -> Unit) {
    val policy = settlement.policy
    InScreenModal(onDismiss = onClose, maxWidth = 760.dp) {
        ModalTitle("정산 기준", onClose)
        PolicyLine("부담 거리", "함께 탄 거리를 운전자 포함 인원으로 나누고, 그 동승자를 태우느라 더 돈 우회거리를 더해요.")
        PolicyLine(
            "충전비",
            "부담 거리 ÷ 전비(${policy.efficiencyKmPerKwh.efficiency()}km/kWh) × 충전 단가(${policy.chargePricePerKwh.toLong().won()}원/kWh)",
        )
        PolicyLine("통행료", "탄 구간에 있는 통행료만 따로 나눠요.")
        PolicyLine("최종 금액", "송금하기 쉽게 ${policy.roundUpUnit}원 단위로 올려요.")
        PolicyLine("정산 제외", "그 달에 한 번도 타지 않은 멤버는 정산하지 않아요.")
        Text(
            when (policy.efficiencySource) {
                EfficiencySource.MANUAL -> "이 차량에서 전비를 읽지 못해 [정산 정보 입력]에서 정한 전비를 써요. 충전 단가도 입력값이에요."
                else -> "전비는 차량에서 읽은 ${policy.efficiencySource.label()} 전비를 써요. 충전 단가는 [정산 정보 입력]에서 정한 값이에요."
            },
            style = MaterialTheme.typography.bodySmall,
            color = DsColors.OnSurfaceMuted,
        )
    }
}

private fun EfficiencySource.label(): String = when (this) {
    EfficiencySource.VEHICLE_CUMULATIVE -> "차량 누적"
    EfficiencySource.VEHICLE_SINCE_CHARGE -> "차량 충전 후"
    EfficiencySource.MANUAL -> "입력값"
}

/** 5.5 → "5.5", 5.4321 → "5.4" */
private fun Double.efficiency(): String = "%.1f".format(this)

@Composable
private fun PolicyLine(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
    }
}

private fun SettlementReceipt.paymentNote(settlement: MonthlySettlement): String = when {
    member.payment == PaymentStatus.PAID -> "입금 완료"
    settlement.receiverAccount == null -> "수취 계좌 미등록 · 송금 여부 확인되지 않음"
    else -> "입금 확인 전"
}

private val MonthFormat = DateTimeFormatter.ofPattern("yyyy년 M월")
private val WonFormat = NumberFormat.getIntegerInstance(Locale.KOREA)

private fun Long.won(): String = WonFormat.format(this)

/** 216.0 → "216", 3.5 → "3.5" */
private fun Double.km(): String = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(this)

/** 2026.10.1 ~ 10.31 */
private fun YearMonth.period(): String = "$year.$monthValue.1 ~ $monthValue.${lengthOfMonth()}"
