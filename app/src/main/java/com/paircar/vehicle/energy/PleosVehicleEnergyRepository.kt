package com.paircar.vehicle.energy

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 2.3.2 로 전비·주행거리를 읽는다.
 * https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro
 * 퍼미션: CAR_ENERGY(EvBattery), CAR_DRIVE(Odometer). 실차 배포는 Pleos App Market 승인 범위 안에서만 쓸 수 있다.
 * 시그니처는 Vehicle-2.3.2.aar 를 javap 로 확인한 값만 사용한다.
 */
class PleosVehicleEnergyRepository(context: Context) : VehicleEnergyRepository {

    private val appContext = context.applicationContext

    // 차량 서비스 연결은 처음 읽을 때 한 번만 한다
    private val vehicle: Vehicle by lazy { Vehicle(appContext).also { it.initialize() } }

    override suspend fun read(): VehicleEnergy {
        val battery = vehicle.getEvBattery()
        val odometer = vehicle.getOdometer()
        return VehicleEnergy(
            cumulativeEvEfficiency = get("getCumulativeEvEfficiency") { ok, fail -> battery.getCumulativeEvEfficiency(ok, fail) },
            sinceChargeEvEfficiency = get("getSinceChargeEvEfficiency") { ok, fail -> battery.getSinceChargeEvEfficiency(ok, fail) },
            odometerKm = get("getCurrentDistanceDriven") { ok, fail -> odometer.getCurrentDistanceDriven(ok, fail) },
        ).also { Log.i(TAG, "read: $it") }
    }

    /** IPC 콜백을 기다린다. 실패·시간 초과면 null */
    private suspend fun get(name: String, call: ((Float?) -> Unit, (Exception) -> Unit) -> Unit): Double? =
        withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    call(
                        { value -> if (cont.isActive) cont.resume(value?.toDouble()) },
                        { error ->
                            Log.w(TAG, "$name failed: $error")
                            if (cont.isActive) cont.resume(null)
                        },
                    )
                }.onFailure { error ->
                    Log.w(TAG, "$name threw: $error")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    companion object {
        private const val TAG = "PleosVehicleEnergy"
        private const val TIMEOUT_MILLIS = 3_000L

        /** 차량(AAOS) 기기가 아니면 Vehicle Service 가 없으므로 아무 값도 읽지 않는다. */
        fun create(context: Context): VehicleEnergyRepository =
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                PleosVehicleEnergyRepository(context)
            } else {
                object : VehicleEnergyRepository {
                    override suspend fun read() = VehicleEnergy()
                }
            }
    }
}
