package com.paircar.feature.settlement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.ChevronIcon
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.GrayChip
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.InfoIcon
import com.paircar.core.ui.components.InitialAvatar
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.ConsentStatus
import com.paircar.domain.model.CostRules
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.ReceiverAccount
import com.paircar.domain.model.SettlementReceipt
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStopType
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 수취 계좌로 고를 수 있는 은행 */
private val Banks = listOf(
    "KB국민", "신한", "우리", "하나", "NH농협", "IBK기업", "카카오뱅크", "토스뱅크",
    "케이뱅크", "SC제일", "우체국", "새마을금고", "부산", "대구", "경남", "광주",
)

private val AccountDigits = 10..14
private val PriceRange = 1..2_000
private val EfficiencyRange = 1.0..15.0

// ───────────────────────── 정산 정보 설정 ─────────────────────────

/**
 * 정산 정보 설정: 수취 계좌(은행·계좌번호, 예금주는 운전자)와 비용 기준(충전비·통행료·주차비·100원 올림),
 * 충전비 계산에 쓰는 충전 단가·전비. 계좌번호는 저장할 때 암호화된다(PrefsSettlementSettingsStore).
 */
@Composable
internal fun BoxScope.SettingsModal(
    settlement: MonthlySettlement,
    settings: SettlementSettings,
    vehicleEfficiency: Double?,
    onSave: (SettlementSettings) -> Unit,
    onClose: () -> Unit,
) {
    var bank by remember { mutableStateOf(settings.account?.bank) }
    var number by remember { mutableStateOf(settings.account?.number.orEmpty()) }
    var rules by remember { mutableStateOf(settings.rules) }
    var price by remember { mutableStateOf((settings.chargePricePerKwh ?: settlement.policy.chargePricePerKwh).toString()) }
    var efficiency by remember { mutableStateOf((settings.efficiencyKmPerKwh ?: settlement.policy.efficiencyKmPerKwh).efficiency()) }
    var error by remember { mutableStateOf<String?>(null) }

    InScreenModal(onDismiss = onClose, maxWidth = 1100.dp) {
        ModalTitle("정산 정보 설정", onClose)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val account: @Composable (Modifier) -> Unit = { modifier ->
                AccountSection(bank, { bank = it }, number, { number = it }, settlement.receiverName, modifier)
            }
            val costs: @Composable (Modifier) -> Unit = { modifier ->
                CostSection(
                    rules = rules,
                    onRules = { rules = it },
                    price = price,
                    onPrice = { price = it },
                    efficiency = efficiency,
                    onEfficiency = { efficiency = it },
                    vehicleEfficiency = vehicleEfficiency,
                    modifier = modifier,
                )
            }
            if (maxWidth >= ExpandedWidthThreshold) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    account(Modifier.weight(1f))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(DsColors.Divider))
                    costs(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    account(Modifier.fillMaxWidth())
                    HorizontalDivider(color = DsColors.Divider)
                    costs(Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider(color = DsColors.Divider)
        Note("변경한 기준은 다음 정산부터 적용돼요. 이미 요청한 정산 금액은 바뀌지 않아요.")
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = DsColors.Danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryActionButton(
                "정산 정보 저장",
                {
                    val result = validate(bank, number, price, efficiency.takeIf { vehicleEfficiency == null }, rules, settings)
                    result.onSuccess(onSave).onFailure { error = it.message }
                },
                Modifier.weight(1f),
            )
            TonalActionButton("닫기", onClose, Modifier.weight(1f))
        }
    }
}

/** 입력값을 검사해 저장할 설정을 만든다. 문제가 있으면 안내 문구를 담은 실패 */
private fun validate(
    bank: String?,
    number: String,
    price: String,
    efficiency: String?,
    rules: CostRules,
    current: SettlementSettings,
): Result<SettlementSettings> {
    val account = when {
        bank == null && number.isEmpty() -> null
        bank == null -> return Result.failure(IllegalArgumentException("은행을 골라 주세요"))
        number.length !in AccountDigits ->
            return Result.failure(IllegalArgumentException("계좌번호는 숫자 ${AccountDigits.first}~${AccountDigits.last}자리로 입력해 주세요"))
        else -> ReceiverAccount(bank, number)
    }
    val priceWon = price.toIntOrNull()?.takeIf { it in PriceRange }
        ?: return Result.failure(IllegalArgumentException("충전 단가는 ${PriceRange.first}~${PriceRange.last}원/kWh 사이로 입력해 주세요"))
    val kmPerKwh = if (efficiency == null) current.efficiencyKmPerKwh else {
        efficiency.toDoubleOrNull()?.takeIf { it in EfficiencyRange }
            ?: return Result.failure(IllegalArgumentException("전비는 1~15km/kWh 사이로 입력해 주세요"))
    }
    return Result.success(SettlementSettings(account, rules, priceWon, kmPerKwh))
}

@Composable
private fun AccountSection(
    bank: String?,
    onBank: (String) -> Unit,
    number: String,
    onNumber: (String) -> Unit,
    holder: String,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("수취 계좌 등록", "정산 금액을 받을 계좌를 등록해요")
        BankPicker(bank, onBank)
        InputBox("계좌번호") {
            BasicTextField(
                value = number,
                onValueChange = { text -> onNumber(text.filter(Char::isDigit).take(AccountDigits.last)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = InputTextStyle(),
                decorationBox = { field ->
                    if (number.isEmpty()) Text("숫자만 입력", style = InputTextStyle().copy(color = DsColors.OnSurfaceMuted))
                    field()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DsColors.Background)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("예금주", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(holder, style = MaterialTheme.typography.labelLarge)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DsColors.Background)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfoIcon(Modifier.size(22.dp))
            Text(
                "등록한 계좌는 월별 정산 안내에만 쓰이고, 기기에 암호화해 보관해요. 화면에는 끝 4자리만 보여요.",
                style = MaterialTheme.typography.bodySmall,
                color = DsColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun BankPicker(bank: String?, onBank: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        InputBox("은행", Modifier.clickable { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bank ?: "은행 선택",
                    style = InputTextStyle().copy(color = if (bank == null) DsColors.OnSurfaceMuted else DsColors.OnSurface),
                    modifier = Modifier.weight(1f),
                )
                ChevronIcon(Modifier.size(24.dp).rotate(90f), color = DsColors.OnSurface)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Banks.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        onBank(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun CostSection(
    rules: CostRules,
    onRules: (CostRules) -> Unit,
    price: String,
    onPrice: (String) -> Unit,
    efficiency: String,
    onEfficiency: (String) -> Unit,
    vehicleEfficiency: Double?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("비용 기준", "매 정산에 적용할 옵션을 설정해요")
        ToggleRow("충전비 포함", "전비와 충전 단가로 계산한 충전 비용", rules.includeCharge) { onRules(rules.copy(includeCharge = it)) }
        if (rules.includeCharge) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBox("충전 단가 (원/kWh)", price, onPrice, decimal = false, Modifier.weight(1f))
                if (vehicleEfficiency == null) {
                    NumberBox("전비 (km/kWh)", efficiency, onEfficiency, decimal = true, Modifier.weight(1f))
                } else {
                    InputBox("전비 (차량 값)", Modifier.weight(1f)) {
                        Text("${vehicleEfficiency.efficiency()} km/kWh", style = InputTextStyle())
                    }
                }
            }
        }
        HorizontalDivider(color = DsColors.Divider)
        ToggleRow("통행료 포함", "고속·유료도로 등 통행료", rules.includeToll) { onRules(rules.copy(includeToll = it)) }
        HorizontalDivider(color = DsColors.Divider)
        ToggleRow("주차비 포함", "운행 중 발생한 주차비", rules.includeParking) { onRules(rules.copy(includeParking = it)) }
        HorizontalDivider(color = DsColors.Divider)
        ToggleRow("100원 단위 올림", "정산 금액을 100원 단위로 올림", rules.roundUp) { onRules(rules.copy(roundUp = it)) }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DsColors.Surface,
                checkedTrackColor = DsColors.Dark,
                uncheckedThumbColor = DsColors.Surface,
                uncheckedTrackColor = DsColors.SurfaceHigh,
                uncheckedBorderColor = DsColors.Divider,
            ),
        )
    }
}

@Composable
private fun NumberBox(label: String, value: String, onValue: (String) -> Unit, decimal: Boolean, modifier: Modifier) {
    InputBox(label, modifier) {
        BasicTextField(
            value = value,
            onValueChange = { text ->
                onValue(text.filter { it.isDigit() || (decimal && it == '.') }.take(6))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
            textStyle = InputTextStyle(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 시안의 입력 칸: 위에 작은 이름, 아래에 값 */
@Composable
private fun InputBox(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, DsColors.Divider, RoundedCornerShape(16.dp))
            .background(DsColors.Surface)
            .heightIn(min = 72.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        content()
    }
}

@Composable
private fun InputTextStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(color = DsColors.OnSurface)

// ───────────────────────── 분담 내역 확인 ─────────────────────────

private val RideDate = DateTimeFormatter.ofPattern("M.d(E)", Locale.KOREAN)

/**
 * 분담 내역 확인: 멤버별(또는 전체) 운행 목록과 운행별 몫, 이번 달 비용 산정.
 * 운행을 누르면 운행 기록에서 그 운행을 연다.
 */
@Composable
internal fun BoxScope.BreakdownModal(
    settlement: MonthlySettlement,
    records: List<TripRecord>,
    initialMember: String,
    onOpenTrip: (TripRecord) -> Unit,
    onClose: () -> Unit,
) {
    var member by remember { mutableStateOf<String?>(initialMember) }
    val receipts = settlement.receipts.filter { member == null || it.member.name == member }
    val names = receipts.map { it.member.name }.toSet()
    val rows = records.filter { record -> record.passengerNames.any { it in names } }

    InScreenModal(onDismiss = onClose, maxWidth = 1100.dp) {
        ModalTitle("분담 내역 확인", onClose)
        MemberChips(settlement, member) { member = it }
        TwoColumns(
            left = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SubTitle("탑승 기록")
                    ListBox {
                        when {
                            rows.isNotEmpty() -> rows.forEachIndexed { index, record ->
                                if (index > 0) HorizontalDivider(color = DsColors.Divider)
                                RideRow(record, receipts) { onOpenTrip(record) }
                            }
                            receipts.all { it.member.isExcluded } -> EmptyText("탑승 이력이 없어 이번 달 정산에서 제외돼요")
                            else -> EmptyText("이 달 운행 기록에서 탑승을 찾지 못했어요. 금액은 정산 데이터 기준이에요.")
                        }
                    }
                }
            },
            right = { modifier -> MonthlyCost(settlement, receipts, member, modifier) },
        )
        Note("운행별 금액은 이번 달 탑승 거리를 탄 횟수로 고르게 나눈 값이에요. 월 금액은 합계에서 한 번만 올림해요.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryActionButton("확인", onClose, Modifier.weight(1f))
            TonalActionButton("닫기", onClose, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RideRow(record: TripRecord, receipts: List<SettlementReceipt>, onClick: () -> Unit) {
    val riders = receipts.filter { it.member.name in record.passengerNames }
    val pickup = record.stops.firstOrNull { it.type == TripStopType.PICKUP && it.passengerName in riders.map { r -> r.member.name } }
    val arrive = record.stops.lastOrNull { it.type == TripStopType.ARRIVE }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(DsColors.OnSurfaceMuted))
        Text(record.date.format(RideDate), style = MaterialTheme.typography.labelLarge)
        Text(
            listOfNotNull(pickup?.placeName, arrive?.placeName).joinToString(" → "),
            style = MaterialTheme.typography.bodySmall,
            color = DsColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GrayChip("${riders.sumOf { it.perRideWon }.won()}원")
        ChevronIcon(Modifier.size(22.dp), color = DsColors.OnSurface)
    }
}

// ───────────────────────── 정산 진행 관리 ─────────────────────────

/**
 * 정산 진행 관리: 동승자별 정산 기준 동의·입금 상태와 정산 요청.
 * 동의·입금은 동승자 앱에서 받는 값이라 서버 연동 전에는 예시 데이터다.
 * 요청은 수취 계좌가 있어야 할 수 있고, 지금은 금액을 확정해 기기에 기록하는 데까지 한다.
 */
@Composable
internal fun BoxScope.ProgressModal(
    settlement: MonthlySettlement,
    initialMember: String,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var member by remember { mutableStateOf<String?>(initialMember) }
    val receipts = settlement.receipts.filter { member == null || it.member.name == member }
    val canRequest = settlement.receiverAccount != null && !settlement.requested && settlement.totalDueWon > 0

    InScreenModal(onDismiss = onClose, maxWidth = 1100.dp) {
        ModalTitle("정산 진행 관리", onClose)
        MemberChips(settlement, member) { member = it }
        TwoColumns(
            left = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SubTitle("진행 현황")
                    ListBox {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            TableHead("동승자", Modifier.weight(1f))
                            TableHead("정산 기준 동의", Modifier.weight(1f))
                            TableHead("입금 상태", Modifier.weight(0.8f))
                        }
                        receipts.forEach { receipt ->
                            HorizontalDivider(color = DsColors.Divider)
                            ProgressRow(receipt)
                        }
                    }
                }
            },
            right = { modifier -> MonthlyCost(settlement, receipts, member, modifier) },
        )
        when {
            settlement.requestedAt != null -> Note(
                "${settlement.requestedAt.monthValue}월 ${settlement.requestedAt.dayOfMonth}일에 정산 금액을 확정했어요. 동승자 알림은 정산 서버 연동 후 보낼 수 있어요.",
            )
            settlement.receiverAccount == null -> Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenSettings).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoIcon(Modifier.size(18.dp))
                Text("수취 계좌 미등록 / 계좌 등록 후 요청 가능", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
                Text("계좌 등록 ›", style = MaterialTheme.typography.labelMedium)
            }
            settlement.totalDueWon == 0L -> Note("받을 금액이 없어 요청할 정산이 없어요.")
            else -> Note("요청하면 지금 기준으로 이번 달 금액이 확정돼요. 동승자 알림은 정산 서버 연동 후 보낼 수 있어요.")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryActionButton(if (settlement.requested) "요청 완료" else "정산 요청", onRequest, Modifier.weight(1f), enabled = canRequest)
            TonalActionButton("닫기", onClose, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProgressRow(receipt: SettlementReceipt) {
    val member = receipt.member
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InitialAvatar(member.name, size = 32.dp)
            Text(member.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Text(
            when {
                member.isExcluded -> "정산 제외"
                member.consent == ConsentStatus.AGREED -> "동의 완료"
                else -> "동의 확인 필요"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = DsColors.OnSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.weight(0.8f)) {
            if (member.isExcluded) Text("-", style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
            else GrayChip(member.payment.label())
        }
    }
}

@Composable
private fun TableHead(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, modifier = modifier)
}

// ───────────────────────── 공통 ─────────────────────────

/** 전체(합계 횟수) + 멤버별(탄 횟수) 고르기 */
@Composable
private fun MemberChips(settlement: MonthlySettlement, selected: String?, onSelect: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip("전체(${settlement.members.sumOf { it.rides }}회)", selected == null) { onSelect(null) }
        settlement.members.forEach { member ->
            ChoiceChip("${member.name}(${member.rides}회)", selected == member.name) { onSelect(member.name) }
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
        color = if (selected) DsColors.OnSurface else DsColors.OnSurfaceMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DsColors.Segment else DsColors.Background)
            .border(1.dp, if (selected) DsColors.OnSurfaceMuted.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** 월별 비용 산정: 횟수·거리, 항목별 금액, 최종 분담 금액 */
@Composable
private fun MonthlyCost(settlement: MonthlySettlement, receipts: List<SettlementReceipt>, member: String?, modifier: Modifier) {
    val rides = receipts.sumOf { it.member.rides }
    val km = if (member == null) settlement.sharedKm else receipts.sumOf { it.member.sharedKm }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SubTitle("월별 비용 산정")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrayChip("${rides}회 동승")
            GrayChip("총 ${km.km()}km")
        }
        HorizontalDivider(color = DsColors.Divider)
        CostLines(receipts, settlement)
        HorizontalDivider(color = DsColors.Divider)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(member ?: "전체", style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
                Text("최종 분담 금액", style = MaterialTheme.typography.titleMedium)
            }
            Text("${receipts.sumOf { it.totalWon }.won()}원", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** 넓으면 왼쪽(목록) 1.4 : 오른쪽(비용) 1, 좁으면 위아래 */
@Composable
private fun TwoColumns(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                left(Modifier.weight(1.4f))
                right(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                left(Modifier.fillMaxWidth())
                right(Modifier.fillMaxWidth())
            }
        }
    }
}

/** 테두리 있는 목록 칸. 길면 안에서 스크롤한다 */
@Composable
private fun ListBox(height: Dp = 300.dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, DsColors.Divider, RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, modifier = Modifier.padding(20.dp))
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
}

@Composable
private fun Note(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InfoIcon(Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
    }
}
