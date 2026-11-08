package com.paircar.data.server

import com.paircar.domain.model.CostRules
import com.paircar.domain.model.EfficiencySource
import com.paircar.domain.model.IssuedAmounts
import com.paircar.domain.model.MemberRides
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.ReceiverAccount
import com.paircar.domain.model.SettlementPolicy
import com.paircar.domain.model.TripRecord
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.TripRecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 서버 월 영수증 한 장 (운전자 ← 동승자 한 명) */
data class ServerReceipt(
    val receiptId: Long,
    val payerNickname: String,
    val payeeNickname: String,
    val boardingDays: Int,
    val sharedDistanceKm: Double,
    val detourKm: Double,
    /** 함께 탄 구간을 탄 인원으로 나눈 이 동승자 몫 (km) */
    val ownDistanceKm: Double,
    val fuelEconomy: Double,
    val fuelEconomySource: String,
    val fuelPriceWon: Int,
    val tollWon: Double,
    val roundingWon: Double,
    val amountWon: Long,
    val payeeBankName: String,
    val payeeAccountNumber: String,
    val waived: Boolean,
    /** KST */
    val issuedAt: LocalDateTime,
    val items: List<ServerReceiptItem>,
)

data class ServerReceiptItem(val serviceDate: LocalDate, val amountWon: Double)

/**
 * 발행한 달: 서버 영수증을 화면 모델로 바꾼다. 금액은 서버 값 그대로([IssuedAmounts]).
 * 영수증이 없는 크루원(그 달에 타지 않음)은 정산 제외로 넣는다.
 */
fun issuedSettlement(month: YearMonth, crewName: String, receipts: List<ServerReceipt>, roster: List<String>): MonthlySettlement {
    val first = receipts.first()
    val members = receipts.map { r ->
        val tollWon = r.tollWon.roundToLong()
        val subtotalWon = (r.amountWon - r.roundingWon).roundToLong()
        // 서버 ownDistanceKm = 내 부담 거리 (함께 탄 구간의 내 몫 + 우회 거리 전부). 화면은 둘을 나눠 보여준다
        val sharedShareKm = (r.ownDistanceKm - r.detourKm).coerceAtLeast(0.0)
        MemberRides(
            name = r.payerNickname,
            rides = r.boardingDays,
            sharedKm = r.sharedDistanceKm,
            // 서버는 구간마다 인원으로 나눈다. 화면의 "거리 ÷ 인원" 표기용 평균 인원
            headcount = if (sharedShareKm > 0) (r.sharedDistanceKm / sharedShareKm).roundToInt().coerceAtLeast(1) else 1,
            detourKmPerRide = if (r.boardingDays > 0) r.detourKm / r.boardingDays else 0.0,
            tollWon = tollWon.toInt(),
            issued = IssuedAmounts(
                sharedShareKm = sharedShareKm,
                detourKm = r.detourKm,
                chargeWon = subtotalWon - tollWon,
                tollWon = tollWon,
                totalWon = r.amountWon,
                perRideWon = r.items.map { it.amountWon }.average().takeUnless { it.isNaN() }?.roundToLong() ?: 0,
                waived = r.waived,
            ),
        )
    }
    val excluded = roster.filter { name -> members.none { it.name == name } }
        .map { MemberRides(name = it, rides = 0, sharedKm = 0.0, headcount = 2, detourKmPerRide = 0.0) }
    return MonthlySettlement(
        month = month,
        crewName = crewName,
        receiverName = first.payeeNickname,
        receiverAccount = first.payeeAccountNumber.takeIf { it.isNotBlank() }?.let { ReceiverAccount(first.payeeBankName, it) },
        carpoolDays = receipts.flatMap { r -> r.items.map { it.serviceDate } }.distinct().size,
        sharedKm = receipts.maxOf { it.sharedDistanceKm },
        policy = SettlementPolicy(
            efficiencyKmPerKwh = first.fuelEconomy,
            efficiencySource = if (first.fuelEconomySource == "MEASURED") EfficiencySource.VEHICLE_CUMULATIVE else EfficiencySource.MANUAL,
            chargePricePerKwh = first.fuelPriceWon,
            // 서버 계산 방식: 충전비 + 통행료, 100원 단위 올림
            rules = CostRules(includeCharge = true, includeToll = true, includeParking = false, roundUp = true),
        ),
        members = members + excluded,
        requestedAt = receipts.minOf { it.issuedAt }.toLocalDate(),
    )
}

/**
 * 아직 발행하지 않은 달: 서버에 미리보기 API 가 없어 기기의 운행 기록으로 어림한다.
 * [정산 요청]을 누르면 서버가 실제 탑승·구간 기록으로 계산해 발행하고, 그 뒤에는 서버 금액을 보여준다.
 * 보여줄 크루원이 없으면 null.
 */
fun estimatedSettlement(
    month: YearMonth,
    crewName: String,
    driverName: String,
    roster: List<String>,
    records: List<TripRecord>,
): MonthlySettlement? {
    val carpools = records.filter { YearMonth.from(it.date) == month && it.passengerNames.isNotEmpty() }
    val names = (roster + carpools.flatMap { it.passengerNames }).distinct()
    if (names.isEmpty()) return null
    val members = names.map { name ->
        val rides = carpools.filter { name in it.passengerNames }
        if (rides.isEmpty()) {
            MemberRides(name = name, rides = 0, sharedKm = 0.0, headcount = 2, detourKmPerRide = 0.0)
        } else {
            MemberRides(
                name = name,
                rides = rides.size,
                sharedKm = rides.sumOf { it.distanceKm },
                headcount = rides.map { it.passengerNames.size + 1 }.average().roundToInt(),
                detourKmPerRide = rides.map { (it.detourKm ?: 0.0) / it.passengerNames.size }.average(),
            )
        }
    }
    return MonthlySettlement(
        month = month,
        crewName = crewName,
        receiverName = driverName,
        receiverAccount = null,
        carpoolDays = carpools.map { it.date }.distinct().size,
        sharedKm = carpools.sumOf { it.distanceKm },
        // 정산 화면이 운전자 설정·차량 전비로 바꾼다 (MonthlySettlement.resolve)
        policy = SettlementPolicy(efficiencyKmPerKwh = 5.5, efficiencySource = EfficiencySource.MANUAL, chargePricePerKwh = 347),
        members = members,
    )
}

/**
 * 월별 정산을 서버 영수증으로 보여준다. 크루가 시작한 달부터 이번 달까지(KST),
 * 발행한 달은 서버 금액, 발행 전인 달은 기기 운행 기록으로 어림한 금액.
 */
class ServerSettlementRepository(
    private val sync: ServerSync,
    private val carpool: CarpoolRepository,
    records: TripRecordRepository,
    scope: CoroutineScope,
    private val today: () -> LocalDate = { LocalDate.now(ServerTime.KST) },
    private val log: (String) -> Unit = {},
) : SettlementRepository {

    private val receipts = MutableStateFlow<Map<YearMonth, List<ServerReceipt>>>(emptyMap())

    override val issuesOnServer: Boolean = true

    override val settlements: StateFlow<List<MonthlySettlement>> =
        combine(receipts, carpool.crew, carpool.todayTrip, records.records) { receipts, crew, trip, records ->
            val crewName = crew?.name ?: trip?.title.orEmpty()
            val roster = crew?.members?.takeIf { it.isNotEmpty() }?.filterNot { it.isDriver }?.map { it.name }
                ?: trip?.acceptedPassengers?.map { it.name }.orEmpty()
            val driverName = crew?.members?.firstOrNull { it.isDriver }?.name ?: trip?.driver?.name.orEmpty()
            months().mapNotNull { month ->
                val issued = receipts[month].orEmpty()
                if (issued.isNotEmpty()) issuedSettlement(month, crewName, issued, roster)
                else estimatedSettlement(month, crewName, driverName, roster, records)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun refresh() {
        months().forEach { month ->
            runCatching { sync.receipts(month) }
                .onSuccess { list -> receipts.update { it + (month to list) } }
                .onFailure { log("$month 영수증을 받지 못했습니다: $it") }
        }
    }

    /** 끝난 달만 수동 발행할 수 있다. 지난달 영수증은 매달 1일 04:00(KST)에 서버가 자동 발행한다 */
    override fun canIssue(month: YearMonth): Boolean = month < YearMonth.from(today())

    override suspend fun issue(month: YearMonth) {
        check(canIssue(month)) { "이번 달 정산은 다음 달 1일에 자동으로 발행돼요" }
        try {
            sync.issueReceipts(month)
        } catch (e: ApiException) {
            throw IllegalStateException(
                when (e.code) {
                    "SETTLEMENT_MONTH_NOT_CLOSED" -> "이번 달 정산은 다음 달 1일에 자동으로 발행돼요"
                    "FUEL_PRICE_NOT_FOUND" -> "충전 단가가 없어 정산할 수 없어요. 정산 정보 설정에서 충전 단가를 입력해 주세요"
                    "BANK_ACCOUNT_REQUIRED" -> "수취 계좌를 먼저 등록해 주세요"
                    else -> "정산을 요청하지 못했어요"
                },
                e,
            )
        }
        val issued = sync.receipts(month)
        receipts.update { it + (month to issued) }
        check(issued.isNotEmpty()) { "서버에 이 달 탑승 기록이 없어 발행할 영수증이 없어요" }
    }

    /** 최근 달이 먼저. 크루 시작 달(서버 가입일)부터, 최대 1년 */
    private fun months(): List<YearMonth> {
        val current = YearMonth.from(today())
        val start = carpool.crew.value?.startDate?.let(YearMonth::from)?.coerceAtMost(current) ?: current
        return generateSequence(current) { it.minusMonths(1) }.takeWhile { it >= start }.take(MAX_MONTHS).toList()
    }

    private companion object {
        const val MAX_MONTHS = 12
    }
}
