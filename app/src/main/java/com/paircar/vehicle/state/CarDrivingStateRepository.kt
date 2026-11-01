package com.paircar.vehicle.state

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.paircar.domain.model.DrivingState
import com.paircar.domain.repository.DrivingStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AAOS CarUxRestrictionsManager 로 주행 규제 상태를 구독한다.
 * https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
 *
 * 차량 API 가 없는 기기에서는 UNAVAILABLE(정차로 간주)로 동작하며,
 * 개발 중에는 [setSimulatedDriving] 으로 주행 상태를 강제할 수 있다.
 */
class CarDrivingStateRepository(
    context: Context,
) : DrivingStateRepository {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(DrivingState.Unavailable)
    override val state: StateFlow<DrivingState> = _state.asStateFlow()

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var vehicleState: DrivingState = DrivingState.Unavailable
    private var simulatedDriving: Boolean? = null

    override fun start() {
        if (car != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        ) {
            publish()
            return
        }
        try {
            val newCar = Car.createCar(appContext) ?: return publish()
            @Suppress("DEPRECATION")
            val manager = newCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (manager == null) {
                newCar.disconnect()
                return publish()
            }
            car = newCar
            uxManager = manager
            manager.registerListener { restrictions ->
                vehicleState = restrictions.toDrivingState()
                publish()
            }
            vehicleState = manager.currentCarUxRestrictions?.toDrivingState() ?: DrivingState.Unavailable
        } catch (t: Throwable) {
            // android.car 라이브러리가 없는 기기: NoClassDefFoundError 등
            Log.w(TAG, "CarUxRestrictions unavailable", t)
            vehicleState = DrivingState.Unavailable
        }
        publish()
    }

    override fun stop() {
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        uxManager = null
        car = null
    }

    override fun setSimulatedDriving(driving: Boolean?) {
        simulatedDriving = driving
        publish()
    }

    private fun publish() {
        val simulated = simulatedDriving
        _state.value = if (simulated != null) {
            DrivingState(restricted = simulated, source = DrivingState.Source.SIMULATED)
        } else {
            vehicleState
        }
    }

    private fun CarUxRestrictions.toDrivingState(): DrivingState {
        val active = activeRestrictions
        return DrivingState(
            restricted = isRequiresDistractionOptimization,
            noKeyboard = active and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD != 0,
            limitContent = active and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0,
            source = DrivingState.Source.CAR_UX_RESTRICTIONS,
        )
    }

    private companion object {
        const val TAG = "CarDrivingState"
    }
}
