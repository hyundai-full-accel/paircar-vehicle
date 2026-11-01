package com.paircar

import android.app.Application
import com.paircar.core.common.AppContainer
import com.paircar.domain.model.NaviEvent
import kotlinx.coroutines.launch

class PairCarApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        observeNaviEvents()
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
                }
            }
        }
    }
}
