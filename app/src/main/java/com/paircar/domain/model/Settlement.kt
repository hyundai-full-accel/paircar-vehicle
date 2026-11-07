package com.paircar.domain.model

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/** 정산에 쓴 전비가 어디서 왔는지 */
enum class EfficiencySource {
    /** Vehicle SDK EvBattery.getCumulativeEvEfficiency */
    VEHICLE_CUMULATIVE,

    /** Vehicle SDK EvBattery.getSinceChargeEvEfficiency */
    VEHICLE_SINCE_CHARGE,

    /** 차량이 전비를 주지 않아 운전자가 [정산 정보 설정]에서 정한 값 */
    MANUAL,
}

/** 운전자가 [정산 정보 설정]에서 고르는 비용 항목 */
data class CostRules(
    val includeCharge: Boolean = true,
    val includeToll: Boolean = true,
    val includeParking: Boolean = false,
    /** 최종 금액을 100원 단위로 올릴지 */
    val roundUp: Boolean = true,
)

/**
 * 정산 기준 (전기차).
 * - 전비 km/kWh: 차량(Vehicle SDK)에서 읽거나, 못 읽으면 입력값
 * - 충전 단가 원/kWh: 차량에서 나오지 않으므로 운전자가 입력한다
 * - 어떤 비용을 넣을지·올림 여부는 [rules]
 */
data class SettlementPolicy(
    val efficiencyKmPerKwh: Double,
    val efficiencySource: EfficiencySource,
    val chargePricePerKwh: Int,
    val rules: CostRules = CostRules(),
) {
    /** 최종 금액을 이 단위로 올린다 (올리지 않으면 1원) */
    val roundUpUnit: Int
        get() = if (rules.roundUp) ROUND_UP_WON else 1

    /** 운전자가 정한 단가·전비·비용 항목을 반영한다. 정하지 않은 값은 그대로 둔다. */
    fun withSettings(settings: SettlementSettings): SettlementPolicy = copy(
        efficiencyKmPerKwh = settings.efficiencyKmPerKwh ?: efficiencyKmPerKwh,
        efficiencySource = if (settings.efficiencyKmPerKwh != null) EfficiencySource.MANUAL else efficiencySource,
        chargePricePerKwh = settings.chargePricePerKwh ?: chargePricePerKwh,
        rules = settings.rules,
    )

    /** 차량에서 읽은 전비가 있으면 그것으로 바꾼다. 없으면 그대로 둔다. */
    fun withVehicle(energy: VehicleEnergy): SettlementPolicy = when {
        energy.cumulativeEvEfficiency != null ->
            copy(efficiencyKmPerKwh = energy.cumulativeEvEfficiency, efficiencySource = EfficiencySource.VEHICLE_CUMULATIVE)
        energy.sinceChargeEvEfficiency != null ->
            copy(efficiencyKmPerKwh = energy.sinceChargeEvEfficiency, efficiencySource = EfficiencySource.VEHICLE_SINCE_CHARGE)
        else -> this
    }

    companion object {
        const val ROUND_UP_WON = 100
    }
}

/**
 * 운행 한 번의 예상 분담금 (주행 중 화면). 월 정산과 같은 계산을 한 번 탄 것으로 한다.
 * @param sharedKm 동승자가 타는 구간 (픽업 → 목적지)
 * @param headcount 그 구간을 함께 타는 인원 (운전자 포함)
 */
fun SettlementPolicy.estimateRideWon(sharedKm: Double, headcount: Int, detourKm: Double): Long =
    SettlementReceipt(
        MemberRides(name = "", rides = 1, sharedKm = sharedKm, headcount = headcount, detourKmPerRide = detourKm),
        this,
    ).totalWon

/** 동승자가 낸 돈의 상태 */
enum class PaymentStatus { UNCONFIRMED, PAID }

/** 동승자가 이번 달 정산 기준에 동의했는지 (동승자 앱에서 받는 값, 서버 연동 전에는 예시 데이터) */
enum class ConsentStatus { NEEDED, AGREED }

/**
 * 한 달 동안 한 동승자의 탑승 기록 요약.
 * 부담 거리 = 함께 탄 거리 ÷ 인원 + 1회 우회거리 × 탑승 횟수
 */
data class MemberRides(
    val name: String,
    val rides: Int,
    /** 이 동승자가 탄 구간의 합 (km) */
    val sharedKm: Double,
    /** 함께 탄 구간을 나누는 인원 (운전자 포함) */
    val headcount: Int,
    /** 이 동승자를 태우느라 더 돈 거리, 1회 기준 (km) */
    val detourKmPerRide: Double,
    /** 통행료 중 이 동승자 몫 (원) */
    val tollWon: Int = 0,
    /** 주차비 중 이 동승자 몫 (원). 주차비 입력 기능 전까지 0 */
    val parkingWon: Int = 0,
    val payment: PaymentStatus = PaymentStatus.UNCONFIRMED,
    val consent: ConsentStatus = ConsentStatus.NEEDED,
    /** 서버가 발행한 영수증 금액. 있으면 앱에서 다시 계산하지 않고 이 값을 보여준다 */
    val issued: IssuedAmounts? = null,
) {
    val isExcluded: Boolean
        get() = rides == 0
}

/**
 * 서버 월 영수증(POST·GET /api/crews/{crewId}/receipts)의 금액. 발행 뒤에는 바뀌지 않는다.
 * 서버는 구간마다 탄 인원으로 나눠 계산하므로 앱의 "함께 탄 거리 ÷ 인원" 식과 다를 수 있어 그대로 쓴다.
 */
data class IssuedAmounts(
    /** 부담 거리 중 함께 탄 거리 몫 (서버 ownDistanceKm) */
    val sharedShareKm: Double,
    val detourKm: Double,
    val chargeWon: Long,
    val tollWon: Long,
    /** 영수증 금액 (서버 amountWon, 올림 포함) */
    val totalWon: Long,
    /** 운행 1회 평균 금액 (서버 항목 amountWon 평균, 올림 전) */
    val perRideWon: Long,
    /** 운전자가 받지 않기로 한 영수증 (서버 waivedAt) */
    val waived: Boolean = false,
)

/** 영수증 한 장에 들어가는 계산 과정. 서버가 발행했으면 [MemberRides.issued] 값을 그대로 쓴다 */
data class SettlementReceipt(
    val member: MemberRides,
    val policy: SettlementPolicy,
) {
    private val issued = member.issued

    val sharedShareKm: Double = issued?.sharedShareKm ?: if (member.isExcluded) 0.0 else member.sharedKm / member.headcount
    val detourKm: Double = issued?.detourKm ?: (member.detourKmPerRide * member.rides)
    val burdenKm: Double = sharedShareKm + detourKm

    /** 충전비는 원 단위 반올림 */
    val chargeWon: Long = issued?.chargeWon ?: if (policy.rules.includeCharge) chargeOf(burdenKm) else 0
    val tollWon: Long = issued?.tollWon ?: if (policy.rules.includeToll) member.tollWon.toLong() else 0
    val parkingWon: Long = if (issued == null && policy.rules.includeParking) member.parkingWon.toLong() else 0
    val subtotalWon: Long = chargeWon + tollWon + parkingWon

    /** 송금하기 쉽게 [SettlementPolicy.roundUpUnit] 원 단위로 올린다 */
    val totalWon: Long = issued?.totalWon ?: ceilTo(subtotalWon, policy.roundUpUnit.toLong())
    val roundingWon: Long = totalWon - subtotalWon

    /** 아직 받지 못한 금액 */
    val dueWon: Long = if (member.payment == PaymentStatus.PAID || issued?.waived == true) 0 else totalWon

    /**
     * 한 번 탔을 때의 몫 (올림 전). 함께 탄 거리는 탄 횟수로 고르게 나눈다.
     * 분담 내역의 운행별 금액으로 쓰고, 월 금액은 합계를 한 번에 올린다.
     */
    val perRideWon: Long = issued?.perRideWon ?: if (member.isExcluded) 0 else {
        val km = member.sharedKm / member.rides / member.headcount + member.detourKmPerRide
        (if (policy.rules.includeCharge) chargeOf(km) else 0) +
            (if (policy.rules.includeToll) member.tollWon.toLong() / member.rides else 0) +
            (if (policy.rules.includeParking) member.parkingWon.toLong() / member.rides else 0)
    }

    private fun chargeOf(km: Double): Long = (km / policy.efficiencyKmPerKwh * policy.chargePricePerKwh).roundToLong()

    private fun ceilTo(value: Long, unit: Long): Long = (value + unit - 1) / unit * unit
}

/** 한 크루의 월 정산 */
data class MonthlySettlement(
    val month: YearMonth,
    val crewName: String,
    /** 돈을 받는 사람 (운전자) */
    val receiverName: String,
    /** 수취 계좌. 등록 전이면 null */
    val receiverAccount: ReceiverAccount?,
    /** 이번 달 운행 횟수 (동승자가 한 명 이상 탄 날) */
    val carpoolDays: Int,
    /** 동승자가 한 명 이상 탄 구간의 합 (km) */
    val sharedKm: Double,
    val policy: SettlementPolicy,
    val members: List<MemberRides>,
    /** 정산을 요청한 날. 요청 전이면 null (정산 서버가 없어 기기에만 기록한다) */
    val requestedAt: LocalDate? = null,
) {
    val requested: Boolean
        get() = requestedAt != null

    val receipts: List<SettlementReceipt> = members.map { SettlementReceipt(it, policy) }

    /** 아직 받지 못한 금액의 합 */
    val totalDueWon: Long = receipts.sumOf { it.dueWon }
}

/** 정산금을 받을 운전자 계좌 */
data class ReceiverAccount(val bank: String, val number: String) {
    /** 화면에는 끝 4자리만 보인다 */
    val masked: String
        get() = "$bank ····${number.takeLast(4)}"
}

/**
 * [정산 정보 설정]에서 운전자가 정한 값. null 이면 기본값(정산 데이터의 값)을 쓴다.
 * 비용 기준은 아직 요청하지 않은 정산(= 다음 정산)부터 적용된다.
 */
data class SettlementSettings(
    val account: ReceiverAccount? = null,
    val rules: CostRules = CostRules(),
    val chargePricePerKwh: Int? = null,
    val efficiencyKmPerKwh: Double? = null,
)

/** 정산 요청 기록. 요청한 때의 기준을 고정해 둬서 이후 설정을 바꿔도 금액이 변하지 않는다. */
data class SettlementRequest(
    val month: YearMonth,
    val requestedAt: LocalDate,
    val policy: SettlementPolicy,
)

/**
 * 화면에 보일 정산: 요청한 달은 요청 때 기준 그대로, 아직 요청 전이면 지금 설정과 차량 전비를 반영한다.
 * 수취 계좌는 운전자 한 명의 값이라 모든 달에 같다.
 */
fun MonthlySettlement.resolve(
    settings: SettlementSettings,
    request: SettlementRequest?,
    energy: VehicleEnergy,
): MonthlySettlement =
    if (request == null && requestedAt != null) {
        // 서버가 이미 발행한 달: 발행 때 기준(전비·단가·계좌)을 그대로 둔다
        this
    } else {
        copy(
            receiverAccount = settings.account,
            policy = request?.policy ?: policy.withSettings(settings).withVehicle(energy),
            requestedAt = request?.requestedAt ?: requestedAt,
        )
    }
