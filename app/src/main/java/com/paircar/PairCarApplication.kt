package com.paircar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.paircar.core.model.GeoPoint
import com.paircar.core.common.AppContainer
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.NaviEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PairCarApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        observeNaviEvents()
        listenPlaceCar()
        // 서버 준비(멤버·크루 등)와 지난번에 못 보낸 운행 기록 보내기
        container.serverSync?.start()
        // 동승자 알림(SSE) 받기, 운행 중 위치 보내기, 우회 거리 등록
        container.serverLive?.start()
    }

    /**
     * 내비 이벤트 → 카풀 상태 반영은 화면과 무관하게 동작해야 하므로 앱 범위에서 구독한다.
     * (내비와 분할 화면으로 표시되거나 다른 화면에 있어도 픽업 도착이 반영되도록)
     */
    private fun observeNaviEvents() = with(container) {
        applicationScope.launch {
            naviRepository.events.collect { event ->
                applyNaviEvent(event)
                if (event is NaviEvent.WaypointArrived) {
                    pickupNotifier.notifyPickupArrived(event.name)
                    if (simulatedDrive?.enabled == true) simulateBoarding()
                }
            }
        }
    }

    /**
     * 시연용: 실차는 도어 → 벨트로 탑승을 감지한다. 시뮬레이션에서는 경유지에 도착하고 잠시 뒤
     * 그 자리에서 기다리던 동승자를 탑승 처리하고 음성으로 알린다 (운전자가 버튼을 누르지 않아도 된다).
     */
    private fun AppContainer.simulateBoarding() {
        applicationScope.launch {
            delay(BOARDING_DELAY_MILLIS)
            val trip = carpoolRepository.todayTrip.value ?: return@launch
            if (trip.status != CarpoolStatus.IN_PROGRESS) return@launch
            val rider = trip.passengers.firstOrNull { it.boardingStatus == BoardingStatus.ARRIVED_AT_PICKUP } ?: return@launch
            carpoolRepository.updateBoardingStatus(rider.id, BoardingStatus.BOARDED)
            speaker.speak("${rider.name}님 탑승했어요")
        }
    }

    /** 시연용(디버그 주행 시뮬레이션): adb 브로드캐스트로 운행 전 차 위치를 옮긴다 ([SimulatedDriveNavi.placeCar]) */
    private fun listenPlaceCar() {
        val sim = container.simulatedDrive ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val lat = intent.getDoubleExtra("lat", intent.getFloatExtra("lat", Float.NaN).toDouble())
                val lng = intent.getDoubleExtra("lng", intent.getFloatExtra("lng", Float.NaN).toDouble())
                if (!lat.isNaN() && !lng.isNaN()) sim.placeCar(GeoPoint(lat, lng))
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_PLACE_CAR), ContextCompat.RECEIVER_EXPORTED)
    }

    private companion object {
        const val BOARDING_DELAY_MILLIS = 4_000L
        const val ACTION_PLACE_CAR = "com.paircar.debug.PLACE_CAR"
    }
}
