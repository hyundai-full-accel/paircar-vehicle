package com.paircar.vehicle.navi

import android.content.Context
import android.content.pm.PackageManager
import com.paircar.BuildConfig
import com.paircar.domain.repository.NaviRepository
import kotlinx.coroutines.CoroutineScope

object NaviRepositoryFactory {

    /**
     * BuildConfig.NAVI_MODE
     * - "pleos": 항상 NaviHelper SDK 사용
     * - "mock" : 항상 시뮬레이터 사용
     * - "auto" : 차량(AAOS) 기기면 NaviHelper, 아니면 시뮬레이터
     *
     * Pleos 연결이 실패하면 PleosNaviRepository 가 Failed 상태를 내보내고,
     * UI 는 "차량 내비에서 직접 안내" 안내로 대체한다(임의로 Mock 으로 바꾸지 않는다).
     */
    fun create(context: Context, scope: CoroutineScope): NaviRepository = when (BuildConfig.NAVI_MODE) {
        "pleos" -> PleosNaviRepository(context)
        "mock" -> MockNaviRepository(scope)
        else -> if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            PleosNaviRepository(context)
        } else {
            MockNaviRepository(scope)
        }
    }
}
