package com.paircar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.paircar.core.ui.theme.PairCarTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as PairCarApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NaviHelper: 생성/initialize 는 앱 화면 생성 시점, 해제는 onDestroy (공식 가이드)
        container.naviRepository.connect()
        container.drivingStateRepository.start()
        setContent {
            PairCarTheme {
                PairCarRoot()
            }
        }
    }

    override fun onDestroy() {
        // window 크기/방향 변경은 configChanges 로 처리하지만, 그 외 재생성 시에는 연결을 유지한다.
        if (!isChangingConfigurations) {
            container.naviRepository.disconnect()
            container.drivingStateRepository.stop()
        }
        super.onDestroy()
    }
}
