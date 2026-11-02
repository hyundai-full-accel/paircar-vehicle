package com.paircar.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.NaviConnectionState
import com.paircar.domain.model.NaviProvider
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.NaviRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

data class SettingsUiState(
    val vehicleName: String = "",
    val crewName: String? = null,
    val provider: NaviProvider = NaviProvider.MOCK,
    val connection: NaviConnectionState = NaviConnectionState.Disconnected,
    val lastCheckedAt: LocalDateTime? = null,
    val checking: Boolean = false,
) {
    val connected: Boolean
        get() = connection is NaviConnectionState.Connected
}

/** 설정: 연결된 차량(= Pleos 내비 연결) 확인·재확인·해제 */
class SettingsViewModel(
    private val naviRepository: NaviRepository,
    carpoolRepository: CarpoolRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    private val lastCheckedAt = MutableStateFlow<LocalDateTime?>(null)
    private val checking = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        naviRepository.connectionState,
        lastCheckedAt,
        checking,
        carpoolRepository.todayTrip,
        carpoolRepository.crew,
    ) { connection, checkedAt, isChecking, trip, crew ->
        SettingsUiState(
            vehicleName = trip?.driver?.carModel.orEmpty(),
            crewName = crew?.name,
            provider = naviRepository.provider,
            connection = connection,
            lastCheckedAt = checkedAt,
            checking = isChecking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(provider = naviRepository.provider))

    init {
        // 화면을 열었을 때 이미 연결돼 있으면 그 시점을 마지막 확인 시각으로 둔다.
        viewModelScope.launch {
            naviRepository.connectionState.first { it is NaviConnectionState.Connected }
            if (lastCheckedAt.value == null) lastCheckedAt.value = now()
        }
    }

    /** 연결돼 있으면 내비 정보를 다시 받고, 끊겨 있으면 다시 연결한다. 로딩은 최소 [MIN_LOADING_MILLIS] 보여준다. */
    fun recheck() {
        if (checking.value) return
        checking.value = true
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            when (naviRepository.connectionState.value) {
                is NaviConnectionState.Connected -> naviRepository.refreshRouteState()
                is NaviConnectionState.Failed -> {
                    naviRepository.disconnect()
                    naviRepository.connect()
                }
                else -> naviRepository.connect()
            }
            val result = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                naviRepository.connectionState.first { it is NaviConnectionState.Connected || it is NaviConnectionState.Failed }
            }
            delay((MIN_LOADING_MILLIS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            if (result is NaviConnectionState.Connected) lastCheckedAt.value = now()
            checking.value = false
        }
    }

    fun disconnect() {
        naviRepository.disconnect()
    }

    companion object {
        private const val MIN_LOADING_MILLIS = 1_200L
        private const val CONNECT_TIMEOUT_MILLIS = 8_000L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                SettingsViewModel(container.naviRepository, container.carpoolRepository)
            }
        }
    }
}
