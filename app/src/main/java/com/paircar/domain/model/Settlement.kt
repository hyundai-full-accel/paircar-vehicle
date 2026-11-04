package com.paircar.domain.model

import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * 정산 기준. 차량에서 연비를 읽는 API 가 없으므로 운전자가 [정산 정보 입력]에서 정한 값을 쓴다.
 * - 연비 km/L, 기준 유가 원/L
 * - 최종 금액은 [roundUpUnit] 원 단위로 올린다
 */
data class SettlementPolicy(
    val fuelEfficiencyKmPerL: Double,
    val fuelPricePerL: Int,
    val roundUpUnit: Int = 100,
)

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

    /** 유류비는 원 단위 반올림 */
    val fuelWon: Long = (burdenKm / policy.fuelEfficiencyKmPerL * policy.fuelPricePerL).roundToLong()
    val tollWon: Long = member.tollWon.toLong()
    val subtotalWon: Long = fuelWon + tollWon

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
