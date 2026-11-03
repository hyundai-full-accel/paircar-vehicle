package com.paircar.core.common

import android.content.Context
import com.paircar.BuildConfig
import com.paircar.core.notification.PickupNotifier
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.remote.OsrmRoutePreviewRepository
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.DrivingStateRepository
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.usecase.ApplyNaviEventUseCase
import com.paircar.domain.usecase.StartCarpoolGuidanceUseCase
import com.paircar.vehicle.navi.NaviRepositoryFactory
import com.paircar.vehicle.state.CarDrivingStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 수동 DI 컨테이너. MVP 규모에서는 Hilt 등 DI 프레임워크를 쓰지 않는다. */
class AppContainer(context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 서버 API 확정 후 REST 구현으로 교체
    val carpoolRepository: CarpoolRepository = MockCarpoolRepository(inviteBaseUrl = BuildConfig.INVITE_BASE_URL)

    val naviRepository: NaviRepository = NaviRepositoryFactory.create(context, applicationScope)

    val drivingStateRepository: DrivingStateRepository = CarDrivingStateRepository(context)

    // 앱 자체 지도의 경로선 미리보기 (프로토타입: OSRM 데모 서버)
    val routePreviewRepository: RoutePreviewRepository = OsrmRoutePreviewRepository()

    val pickupNotifier = PickupNotifier(context)

    val vehicleCheckStore: VehicleCheckStore = PrefsVehicleCheckStore(context)

    val startCarpoolGuidance = StartCarpoolGuidanceUseCase(carpoolRepository, naviRepository)

    val applyNaviEvent = ApplyNaviEventUseCase(carpoolRepository)
}
