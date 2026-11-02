package com.paircar.feature.crew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paircar.PairCarApplication
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.repository.CarpoolRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class InviteUiState(
    val invite: CrewInvite? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** 내 크루 화면의 QR 초대. 크루·운행 정보는 CarpoolViewModel 상태를 같이 쓴다. */
class CrewViewModel(
    private val carpoolRepository: CarpoolRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {

    private val _invite = MutableStateFlow(InviteUiState())
    val invite: StateFlow<InviteUiState> = _invite.asStateFlow()

    /** QR 을 열 때: 아직 유효한 초대가 있으면 그대로 쓴다. */
    fun ensureInvite() {
        val current = _invite.value.invite
        if (current == null || !current.expiresAt.isAfter(now())) createInvite()
    }

    fun createInvite() {
        if (_invite.value.loading) return
        _invite.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            _invite.value = runCatching { carpoolRepository.createInvite() }.fold(
                onSuccess = { InviteUiState(invite = it) },
                onFailure = { InviteUiState(invite = _invite.value.invite, error = "QR 을 만들지 못했어요. 다시 시도해 주세요.") },
            )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as PairCarApplication).container
                CrewViewModel(container.carpoolRepository)
            }
        }
    }
}
