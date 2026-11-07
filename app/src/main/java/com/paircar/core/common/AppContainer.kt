package com.paircar.core.common

import android.content.Context
import android.util.Log
import com.paircar.BuildConfig
import com.paircar.core.notification.PickupNotifier
import com.paircar.data.local.LocalTripRecordRepository
import com.paircar.data.local.PrefsTripStores
import com.paircar.data.local.KeystoreAccountCipher
import com.paircar.data.local.PrefsCrewInfoStore
import com.paircar.data.local.PrefsSettlementSettingsStore
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.mock.MockSettlementRepository
import com.paircar.data.mock.MockTripHistory
import com.paircar.data.remote.OsrmRoutePreviewRepository
import com.paircar.data.server.PairCarApi
import com.paircar.data.server.PrefsServerStore
import com.paircar.data.server.ServerCarpoolRepository
import com.paircar.data.server.ServerSettlementSettingsStore
import com.paircar.data.server.ServerSync
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.DrivingStateRepository
import com.paircar.domain.repository.NaviRepository
import com.paircar.domain.repository.SettlementRepository
import com.paircar.domain.repository.SettlementSettingsStore
import com.paircar.domain.repository.TripRecordRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import com.paircar.domain.usecase.ApplyNaviEventUseCase
import com.paircar.domain.usecase.CarpoolRouteSync
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

    private val crewInfoStore = PrefsCrewInfoStore(context)

    // 오늘 운행·동승자·일정은 서버에 아직 없어 앱(Mock)이 갖는다
    private val localCarpoolRepository: CarpoolRepository = MockCarpoolRepository(
        inviteBaseUrl = BuildConfig.INVITE_BASE_URL,
        stateStore = tripStores,
        crewInfoStore = crewInfoStore,
    )

    private val localSettlementSettingsStore: SettlementSettingsStore =
        PrefsSettlementSettingsStore(context, KeystoreAccountCipher())

    val vehicleEnergyRepository: VehicleEnergyRepository = PleosVehicleEnergyRepository.create(context)

    /** 페어카 크루 서버 동기화. API_BASE_URL 이 비어 있으면(릴리스 기본값) 서버 없이 Mock 만 쓴다. */
    val serverSync: ServerSync? = BuildConfig.API_BASE_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
        val store = PrefsServerStore(context)
        ServerSync(
            api = PairCarApi(baseUrl) { store.token },
            store = store,
            carpool = localCarpoolRepository,
            settings = { localSettlementSettingsStore.settings.value },
            energy = vehicleEnergyRepository,
            scope = applicationScope,
            seedPassengers = BuildConfig.SEED_SERVER_PASSENGERS,
            log = { Log.i("PairCarServer", it) },
        )
    }

    val carpoolRepository: CarpoolRepository =
        serverSync?.let {
            ServerCarpoolRepository(localCarpoolRepository, it, applicationScope, editedCrewName = { crewInfoStore.load()?.name })
        } ?: localCarpoolRepository

    // 정산 내역(월 영수증)은 아직 Mock. 서버 영수증 API(GET /api/crews/{crewId}/receipts) 연결은 다음 단계
    val settlementRepository: SettlementRepository = MockSettlementRepository()

    val settlementSettingsStore: SettlementSettingsStore =
        serverSync?.let { sync -> ServerSettlementSettingsStore(localSettlementSettingsStore) { sync.onSettingsChanged() } }
            ?: localSettlementSettingsStore

    // 끝난 운행은 기기에 저장한다. 지난 기록(Mock)은 화면 개발용으로 함께 보여준다.
    val tripRecordRepository: TripRecordRepository = LocalTripRecordRepository(
        file = File(context.filesDir, "trip_records.json"),
        seed = MockTripHistory.records(until = LocalDate.now()),
    )

    val naviRepository: NaviRepository = NaviRepositoryFactory.create(context, applicationScope)

    val drivingStateRepository: DrivingStateRepository = CarDrivingStateRepository(context)

    // 앱 자체 지도의 경로선 미리보기 (프로토타입: OSRM 데모 서버)
    val routePreviewRepository: RoutePreviewRepository = OsrmRoutePreviewRepository()

    val pickupNotifier = PickupNotifier(context)

    val vehicleCheckStore: VehicleCheckStore = PrefsVehicleCheckStore(context)

    val tripRecorder = TripRecorder(tripRecordRepository, vehicleEnergyRepository, routePreviewRepository, tripStores)

    val routeSync = CarpoolRouteSync(naviRepository)

    val startCarpoolGuidance = StartCarpoolGuidanceUseCase(carpoolRepository, routeSync, tripRecorder)

    val finishCarpool = FinishCarpoolUseCase(carpoolRepository, tripRecorder)

    val applyNaviEvent = ApplyNaviEventUseCase(carpoolRepository, finishCarpool, routeSync)
}
