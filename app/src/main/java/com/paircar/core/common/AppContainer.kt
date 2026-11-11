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
import com.paircar.data.mock.MockCarpoolData
import com.paircar.data.mock.MockCarpoolRepository
import com.paircar.data.mock.MockSettlementRepository
import com.paircar.data.mock.MockTripHistory
import com.paircar.data.remote.CachingRoutePreviewRepository
import com.paircar.data.remote.OsrmRoutePreviewRepository
import com.paircar.domain.usecase.RouteComparer
import com.paircar.data.server.PairCarApi
import com.paircar.data.server.PrefsServerStore
import com.paircar.data.server.ServerCarpoolRepository
import com.paircar.data.server.ServerLiveCoordinator
import com.paircar.data.server.ServerSettlementRepository
import com.paircar.domain.usecase.ApplyCrewLiveEventUseCase
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
import com.paircar.vehicle.navi.SimulatedDriveNavi
import com.paircar.domain.model.NaviProvider
import com.paircar.vehicle.tts.Speaker
import com.paircar.vehicle.tts.VehicleSpeaker
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

    // 앱 자체 지도의 경로선 미리보기 (프로토타입: OSRM 데모 서버). 같은 경로는 다시 묻지 않는다
    val routePreviewRepository: RoutePreviewRepository = CachingRoutePreviewRepository(OsrmRoutePreviewRepository())

    /** 서버 주소가 있으면(디버그) 서버 데이터만 쓰고, 없으면(릴리스 기본값) 화면 확인용 예시 데이터를 쓴다 */
    private val useServer = BuildConfig.API_BASE_URL.isNotBlank()

    // 오늘 운행의 진행 상태를 기기에 둔다. 서버 모드에서는 서버 일정이 올 때까지 비어 있다
    private val localCarpoolRepository: CarpoolRepository = MockCarpoolRepository(
        tripFactory = if (useServer) ({ null }) else ({ MockCarpoolData.todayTrip() }),
        crew = if (useServer) null else MockCarpoolData.crew,
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
            ServerCarpoolRepository(
                local = localCarpoolRepository,
                sync = it,
                scope = applicationScope,
                editedCrewName = { crewInfoStore.load()?.name },
                routes = routePreviewRepository,
                // 서버 차량에는 이름이 없어 등록한 연료 종류로 보여준다
                vehicleName = "전기차",
                log = { msg -> Log.i("PairCarServer", msg) },
            )
        } ?: localCarpoolRepository

    // 월별 정산: 서버 월 영수증. 서버가 없으면 Mock
    val settlementRepository: SettlementRepository by lazy {
        serverSync?.let {
            ServerSettlementRepository(it, carpoolRepository, tripRecordRepository, applicationScope, log = { msg -> Log.i("PairCarServer", msg) })
        } ?: MockSettlementRepository()
    }

    val settlementSettingsStore: SettlementSettingsStore =
        serverSync?.let { sync -> ServerSettlementSettingsStore(localSettlementSettingsStore) { sync.onSettingsChanged() } }
            ?: localSettlementSettingsStore

    // 끝난 운행은 기기에 저장한다. 서버 없이 쓸 때만 화면 확인용 지난 기록(예시)을 함께 보여준다.
    val tripRecordRepository: TripRecordRepository = LocalTripRecordRepository(
        file = File(context.filesDir, "trip_records.json"),
        seed = if (useServer) emptyList() else MockTripHistory.records(until = LocalDate.now()),
    )

    private val realNavi: NaviRepository = NaviRepositoryFactory.create(context, applicationScope)

    /**
     * 시연용 차량 이동 시뮬레이션 (디버그 빌드 + Pleos 내비). 에뮬레이터 내비는 차가 움직이지 않아
     * 주행을 시작하면 경로를 따라 차가 가는 것처럼 위치·경유지 도착·목적지 도착을 만든다.
     */
    val simulatedDrive: SimulatedDriveNavi? =
        if (BuildConfig.SIMULATE_DRIVE && realNavi.provider == NaviProvider.PLEOS) {
            SimulatedDriveNavi(realNavi, routePreviewRepository, applicationScope, startPoint = { carpoolRepository.todayTrip.value?.origin?.point })
        } else {
            null
        }

    val naviRepository: NaviRepository = simulatedDrive ?: realNavi

    val drivingStateRepository: DrivingStateRepository = CarDrivingStateRepository(context)

    /** 경유 vs 직행 비교 (우회 거리). 운행 기록·서버 우회 거리 등록이 같이 쓴다 */
    val routeComparer = RouteComparer(routePreviewRepository)

    val pickupNotifier = PickupNotifier(context)

    /** 운전자 음성 안내 (Pleos TextToSpeech SDK, 못 쓰면 안드로이드 기본 TTS) */
    val speaker: Speaker = VehicleSpeaker(context)

    val vehicleCheckStore: VehicleCheckStore = PrefsVehicleCheckStore(context)

    val tripRecorder = TripRecorder(tripRecordRepository, vehicleEnergyRepository, routePreviewRepository, tripStores)

    val routeSync = CarpoolRouteSync(naviRepository)

    val startCarpoolGuidance = StartCarpoolGuidanceUseCase(carpoolRepository, routeSync, tripRecorder)

    val finishCarpool = FinishCarpoolUseCase(carpoolRepository, tripRecorder)

    val applyNaviEvent = ApplyNaviEventUseCase(carpoolRepository, finishCarpool, routeSync)

    /** 서버 실시간 구독(SSE)·위치 보내기·우회 거리. 서버가 없으면 null */
    val serverLive: ServerLiveCoordinator? = serverSync?.let { sync ->
        var notificationId = LIVE_NOTIFICATION_ID
        ServerLiveCoordinator(
            sync = sync,
            carpool = carpoolRepository,
            navi = naviRepository,
            routes = routeComparer,
            // 서버에서 온 변경은 서버로 되돌려 보내지 않도록 앱 저장소(local)에 직접 반영한다
            applyEvent = ApplyCrewLiveEventUseCase(
                carpool = localCarpoolRepository,
                routeSync = routeSync,
                notify = { title, text ->
                    Log.i("PairCarServer", "알림: $title · $text")
                    pickupNotifier.post(notificationId++, title, text)
                    // 주행 중에는 화면을 보지 않아도 되도록 소리로도 알린다 ("차수아님이 5분 늦어요")
                    speaker.speak(title)
                },
                onReceiptIssued = { settlementRepository.refresh() },
                onCrewChanged = { sync.refreshCrew() },
            ),
            scope = applicationScope,
            log = { Log.i("PairCarServer", it) },
        )
    }

    private companion object {
        const val LIVE_NOTIFICATION_ID = 2000
    }
}
