package com.paircar.domain.model

import java.time.YearMonth
import kotlin.math.roundToLong

/** 정산에 쓴 전비가 어디서 왔는지 */
enum class EfficiencySource {
    /** Vehicle SDK EvBattery.getCumulativeEvEfficiency */
    VEHICLE_CUMULATIVE,

    /** Vehicle SDK EvBattery.getSinceChargeEvEfficiency */
    VEHICLE_SINCE_CHARGE,

    /** 차량이 전비를 주지 않아 운전자가 [정산 정보 입력]에서 정한 값 */
    MANUAL,
}

/**
 * 정산 기준 (전기차).
 * - 전비 km/kWh: 차량(Vehicle SDK)에서 읽거나, 못 읽으면 입력값
 * - 충전 단가 원/kWh: 차량에서 나오지 않으므로 운전자가 입력한다
 * - 최종 금액은 [roundUpUnit] 원 단위로 올린다
 */
data class SettlementPolicy(
    val efficiencyKmPerKwh: Double,
    val efficiencySource: EfficiencySource,
    val chargePricePerKwh: Int,
    val roundUpUnit: Int = 100,
) {
    /** 차량에서 읽은 전비가 있으면 그것으로 바꾼다. 없으면 그대로 둔다. */
    fun withVehicle(energy: VehicleEnergy): SettlementPolicy = when {
        energy.cumulativeEvEfficiency != null ->
            copy(efficiencyKmPerKwh = energy.cumulativeEvEfficiency, efficiencySource = EfficiencySource.VEHICLE_CUMULATIVE)
        energy.sinceChargeEvEfficiency != null ->
            copy(efficiencyKmPerKwh = energy.sinceChargeEvEfficiency, efficiencySource = EfficiencySource.VEHICLE_SINCE_CHARGE)
        else -> this
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
    val payment: PaymentStatus = PaymentStatus.UNCONFIRMED,
) {
    val isExcluded: Boolean
        get() = rides == 0
}

/** 영수증 한 장에 들어가는 계산 과정 */
data class SettlementReceipt(
    val member: MemberRides,
    val policy: SettlementPolicy,
) {
    val sharedShareKm: Double = if (member.isExcluded) 0.0 else member.sharedKm / member.headcount
    val detourKm: Double = member.detourKmPerRide * member.rides
    val burdenKm: Double = sharedShareKm + detourKm

    /** 충전비는 원 단위 반올림 */
    val chargeWon: Long = (burdenKm / policy.efficiencyKmPerKwh * policy.chargePricePerKwh).roundToLong()
    val tollWon: Long = member.tollWon.toLong()
    val subtotalWon: Long = chargeWon + tollWon

    /** 송금하기 쉽게 [SettlementPolicy.roundUpUnit] 원 단위로 올린다 */
    val totalWon: Long = ceilTo(subtotalWon, policy.roundUpUnit.toLong())
    val roundingWon: Long = totalWon - subtotalWon

    /** 아직 받지 못한 금액 */
    val dueWon: Long = if (member.payment == PaymentStatus.PAID) 0 else totalWon

    private fun ceilTo(value: Long, unit: Long): Long = (value + unit - 1) / unit * unit
}

/** 한 크루의 월 정산 */
data class MonthlySettlement(
    val month: YearMonth,
    val crewName: String,
    /** 돈을 받는 사람 (운전자) */
    val receiverName: String,
    /** 수취 계좌. 등록 전이면 null */
    val receiverAccount: String?,
    /** 이번 달 운행 횟수 (동승자가 한 명 이상 탄 날) */
    val carpoolDays: Int,
    /** 동승자가 한 명 이상 탄 구간의 합 (km) */
    val sharedKm: Double,
    val policy: SettlementPolicy,
    val members: List<MemberRides>,
    /** 동승자에게 정산 요청을 보냈는지. 정산 서버가 없어 아직 보낼 수 없다. */
    val requested: Boolean = false,
) {
    val receipts: List<SettlementReceipt> = members.map { SettlementReceipt(it, policy) }

    /** 아직 받지 못한 금액의 합 */
    val totalDueWon: Long = receipts.sumOf { it.dueWon }
}
