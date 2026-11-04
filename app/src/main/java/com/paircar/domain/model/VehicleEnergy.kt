package com.paircar.domain.model

/**
 * 차량에서 읽은 전비·주행거리 (Pleos Vehicle SDK). 못 읽은 값은 null 이다.
 * 정산의 전비는 한 달 평균에 가까운 [cumulativeEvEfficiency] 를 먼저 쓴다.
 */
data class VehicleEnergy(
    /** 누적 평균 전비 (km/kWh). EvBattery.getCumulativeEvEfficiency */
    val cumulativeEvEfficiency: Double? = null,
    /** 충전 후 평균 전비 (km/kWh). EvBattery.getSinceChargeEvEfficiency */
    val sinceChargeEvEfficiency: Double? = null,
    /** 총 주행 거리 (km). Odometer.getCurrentDistanceDriven */
    val odometerKm: Double? = null,
) {
    val evEfficiency: Double?
        get() = cumulativeEvEfficiency ?: sinceChargeEvEfficiency
}
