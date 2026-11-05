package com.paircar.core.common

import android.content.Context
import com.paircar.BuildConfig
import com.paircar.core.notification.PickupNotifier
import com.paircar.data.local.LocalTripRecordRepository
import com.paircar.data.local.PrefsTripStores
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.mock.MockSettlementRepository
import com.paircar.data.mock.MockTripHistory
import com.paircar.data.remote.OsrmRoutePreviewRepository
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.DrivingStateRepository
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.TripRecordRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import com.paircar.domain.usecase.ApplyNaviEventUseCase
import com.paircar.domain.usecase.FinishCarpoolUseCase
import com.paircar.domain.usecase.StartCarpoolGuidanceUseCase
import com.paircar.domain.usecase.TripRecorder
import com.paircar.vehicle.energy.PleosVehicleEnergyRepository
import com.paircar.vehicle.navi.NaviRepositoryFactory
import com.paircar.vehicle.state.CarDrivingStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.LocalDate

/** 수동 DI 컨테이너. MVP 규모에서는 Hilt 등 DI 프레임워크를 쓰지 않는다. */
class AppContainer(context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Pleos 는 창을 닫으면 앱 프로세스를 끝내므로 진행 중인 운행은 기기에 둔다
    private val tripStores = PrefsTripStores(context)

    // 서버 API 확정 후 REST 구현으로 교체
    val carpoolRepository: CarpoolRepository = MockCarpoolRepository(
        inviteBaseUrl = BuildConfig.INVITE_BASE_URL,
        stateStore = tripStores,
    )

    // 정산 서버 확정 후 교체
    val settlementRepository: SettlementRepository = MockSettlementRepository()

    // 끝난 운행은 기기에 저장한다. 지난 기록(Mock)은 화면 개발용으로 함께 보여준다.
    val tripRecordRepository: TripRecordRepository = LocalTripRecordRepository(
        file = File(context.filesDir, "trip_records.json"),
        seed = MockTripHistory.records(until = LocalDate.now()),
    )

    val naviRepository: NaviRepository = NaviRepositoryFactory.create(context, applicationScope)

    val vehicleEnergyRepository: VehicleEnergyRepository = PleosVehicleEnergyRepository.create(context)

    val drivingStateRepository: DrivingStateRepository = CarDrivingStateRepository(context)

    // 앱 자체 지도의 경로선 미리보기 (프로토타입: OSRM 데모 서버)
    val routePreviewRepository: RoutePreviewRepository = OsrmRoutePreviewRepository()

    val pickupNotifier = PickupNotifier(context)

    val vehicleCheckStore: VehicleCheckStore = PrefsVehicleCheckStore(context)

    val tripRecorder = TripRecorder(tripRecordRepository, vehicleEnergyRepository, routePreviewRepository, tripStores)

    val startCarpoolGuidance = StartCarpoolGuidanceUseCase(carpoolRepository, naviRepository, tripRecorder)

    val finishCarpool = FinishCarpoolUseCase(carpoolRepository, tripRecorder)

    val applyNaviEvent = ApplyNaviEventUseCase(carpoolRepository, finishCarpool)
}
