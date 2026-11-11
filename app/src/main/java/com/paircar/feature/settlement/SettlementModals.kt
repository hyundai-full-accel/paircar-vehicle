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
import androidx.compose.ui.unit.sp
import com.paircar.core.ui.components.ChevronIcon
import com.paircar.core.ui.components.ChoiceChip
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.FieldBox
import com.paircar.core.ui.components.FieldTextStyle
import com.paircar.core.ui.components.FormNote
import com.paircar.core.ui.components.FormSectionTitle
import com.paircar.core.ui.components.ToggleRow
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

/**
 * 수취 계좌로 고를 수 있는 은행. 정식 로고 이미지 대신 은행별 대표색 원형 배지에 약칭을 쓴다
 * (로고는 각 은행 상표라 받아 넣지 않는다. 승인받은 로고가 생기면 [BankBadge] 만 이미지로 바꾸면 된다).
 */
private data class BankMark(val name: String, val short: String, val color: Color, val onColor: Color = Color.White)

private val Banks = listOf(
    BankMark("KB국민", "KB", Color(0xFFFFBC00), Color(0xFF4E4637)),
    BankMark("신한", "신한", Color(0xFF0046FF)),
    BankMark("우리", "우리", Color(0xFF0067AC)),
    BankMark("하나", "하나", Color(0xFF009490)),
    BankMark("NH농협", "NH", Color(0xFF0BA04B)),
    BankMark("IBK기업", "IBK", Color(0xFF004B9C)),
    BankMark("카카오뱅크", "카카오", Color(0xFFFFE300), Color(0xFF3A1D1D)),
    BankMark("토스뱅크", "토스", Color(0xFF0064FF)),
    BankMark("케이뱅크", "K", Color(0xFF1E1A8C)),
    BankMark("SC제일", "SC", Color(0xFF0473EA)),
    BankMark("우체국", "우체국", Color(0xFFE4002B)),
    BankMark("새마을금고", "MG", Color(0xFF0A3F8F)),
    BankMark("부산", "부산", Color(0xFFD7282F)),
    BankMark("대구", "대구", Color(0xFF0067B1)),
    BankMark("경남", "경남", Color(0xFFC8102E)),
    BankMark("광주", "광주", Color(0xFF00467F)),
)

private fun bankMark(name: String?): BankMark? = Banks.firstOrNull { it.name == name }

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
    var pickingBank by remember { mutableStateOf(false) }

    InScreenModal(onDismiss = onClose, maxWidth = 1100.dp) {
        ModalTitle("정산 정보 설정", onClose)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val account: @Composable (Modifier) -> Unit = { modifier ->
                AccountSection(bank, { pickingBank = true }, number, { number = it }, settlement.receiverName, modifier)
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
        FormNote("변경한 기준은 다음 정산부터 적용돼요. 이미 요청한 정산 금액은 바뀌지 않아요.")
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
    // 설정 창 위에 겹쳐 띄운다 (뒤로 가기는 이 창부터 닫힌다)
    if (pickingBank) {
        BankSheet(
            selected = bank,
            onPick = {
                bank = it
                error = null
                pickingBank = false
            },
            onClose = { pickingBank = false },
        )
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
    onPickBank: () -> Unit,
    number: String,
    onNumber: (String) -> Unit,
    holder: String,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormSectionTitle("수취 계좌 등록", "정산 금액을 받을 계좌를 등록해요")
        BankField(bank, onPickBank)
        FieldBox("계좌번호") {
            BasicTextField(
                value = number,
                onValueChange = { text -> onNumber(text.filter(Char::isDigit).take(AccountDigits.last)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = FieldTextStyle(),
                decorationBox = { field ->
                    if (number.isEmpty()) Text("숫자만 입력", style = FieldTextStyle().copy(color = DsColors.OnSurfaceMuted))
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

/** 은행 칸: 고른 은행의 배지와 이름. 누르면 은행 선택 창이 열린다 */
@Composable
private fun BankField(bank: String?, onPick: () -> Unit) {
    val mark = bankMark(bank)
    FieldBox("은행", Modifier.clickable(onClick = onPick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (mark != null) BankBadge(mark, 32.dp)
            Text(
                bank ?: "은행을 골라 주세요",
                style = FieldTextStyle().copy(color = if (bank == null) DsColors.OnSurfaceMuted else DsColors.OnSurface),
                modifier = Modifier.weight(1f),
            )
            ChevronIcon(Modifier.size(24.dp).rotate(90f), color = DsColors.OnSurface)
        }
    }
}

/** 은행 선택 창: 4열 그리드. 고르면 바로 닫힌다 */
@Composable
private fun BoxScope.BankSheet(selected: String?, onPick: (String) -> Unit, onClose: () -> Unit) {
    InScreenModal(onDismiss = onClose, maxWidth = 760.dp) {
        ModalTitle("은행 선택", onClose)
        Text("정산 금액을 받을 은행을 골라 주세요", style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banks.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { mark -> BankTile(mark, mark.name == selected, { onPick(mark.name) }, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BankTile(mark: BankMark, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DsColors.Brand else DsColors.Divider, shape)
            .background(if (selected) DsColors.Brand.copy(alpha = 0.06f) else DsColors.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BankBadge(mark, 52.dp)
        Text(
            mark.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) DsColors.Brand else DsColors.OnSurface,
            maxLines = 1,
        )
    }
}

/** 은행 대표색 원형 배지 + 약칭 */
@Composable
private fun BankBadge(mark: BankMark, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(mark.color), contentAlignment = Alignment.Center) {
        val scale = size.value / 52f
        Text(
            mark.short,
            color = mark.onColor,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (if (mark.short.length >= 3) 13 else 16).sp * scale,
            letterSpacing = (-0.3).sp,
            maxLines = 1,
        )
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
        FormSectionTitle("비용 기준", "매 정산에 적용할 옵션을 설정해요")
        ToggleRow("충전비 포함", "전비와 충전 단가로 계산한 충전 비용", rules.includeCharge) { onRules(rules.copy(includeCharge = it)) }
        if (rules.includeCharge) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBox("충전 단가 (원/kWh)", price, onPrice, decimal = false, Modifier.weight(1f))
                if (vehicleEfficiency == null) {
                    NumberBox("전비 (km/kWh)", efficiency, onEfficiency, decimal = true, Modifier.weight(1f))
                } else {
                    FieldBox("전비 (차량 값)", Modifier.weight(1f)) {
                        Text("${vehicleEfficiency.efficiency()} km/kWh", style = FieldTextStyle())
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
private fun NumberBox(label: String, value: String, onValue: (String) -> Unit, decimal: Boolean, modifier: Modifier) {
    FieldBox(label, modifier) {
        BasicTextField(
            value = value,
            onValueChange = { text ->
                onValue(text.filter { it.isDigit() || (decimal && it == '.') }.take(6))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
            textStyle = FieldTextStyle(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
        FormNote("운행별 금액은 이번 달 탑승 거리를 탄 횟수로 고르게 나눈 값이에요. 월 금액은 합계에서 한 번만 올림해요.")
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
            settlement.requestedAt != null -> FormNote(
                "${settlement.requestedAt.monthValue}월 ${settlement.requestedAt.dayOfMonth}일에 정산 금액을 확정했어요. " +
                    if (settlement.members.any { it.issued != null }) {
                        "서버가 동승자에게 영수증을 보냈어요."
                    } else {
                        "동승자 알림은 정산 서버 연동 후 보낼 수 있어요."
                    },
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
            settlement.totalDueWon == 0L -> FormNote("받을 금액이 없어 요청할 정산이 없어요.")
            else -> FormNote("요청하면 지금 기준으로 이번 달 금액이 확정돼요. 동승자 알림은 정산 서버 연동 후 보낼 수 있어요.")
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
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
}

