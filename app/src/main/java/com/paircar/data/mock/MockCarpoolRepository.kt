package com.paircar.data.mock

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.InviteLinks
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.repository.CarpoolRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom
import java.time.LocalDateTime

class MockCarpoolRepository(
    private val latencyMillis: Long = 150,
    private val tripFactory: () -> CarpoolTrip = { MockCarpoolData.todayTrip() },
    crew: Crew = MockCarpoolData.crew,
    private val inviteBaseUrl: String = MockCarpoolData.INVITE_BASE_URL_PLACEHOLDER,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : CarpoolRepository {

    private val trip = MutableStateFlow<CarpoolTrip?>(tripFactory())
    override val todayTrip: StateFlow<CarpoolTrip?> = trip.asStateFlow()

    override val crew: StateFlow<Crew?> = MutableStateFlow(crew).asStateFlow()

    private val random = SecureRandom()

    override suspend fun refresh() {
        delay(latencyMillis)
        if (trip.value == null) trip.value = tripFactory()
    }

    override suspend fun updateMatchStatus(passengerId: String, status: MatchStatus) {
        delay(latencyMillis)
        updatePassenger(passengerId) { it.copy(matchStatus = status) }
    }

    override suspend fun updateBoardingStatus(passengerId: String, status: BoardingStatus) {
        delay(latencyMillis)
        updatePassenger(passengerId) {
            it.copy(
                boardingStatus = status,
                boardedAt = if (status == BoardingStatus.BOARDED) now().toLocalTime() else it.boardedAt,
            )
        }
    }

    override suspend fun updateTripStatus(status: CarpoolStatus) {
        delay(latencyMillis)
        trip.update { it?.copy(status = status) }
    }

    /** 서버가 생기면 서버가 코드를 발급·검증한다. Mock 은 24시간짜리 코드를 만들기만 한다. */
    override suspend fun createInvite(): CrewInvite {
        delay(latencyMillis)
        val code = (1..InviteLinks.CODE_LENGTH)
            .map { InviteLinks.CODE_ALPHABET[random.nextInt(InviteLinks.CODE_ALPHABET.length)] }
            .joinToString("")
        return CrewInvite(code, InviteLinks.build(inviteBaseUrl, code), now().plusHours(24))
    }

    private fun updatePassenger(passengerId: String, transform: (Passenger) -> Passenger) {
        trip.update { current ->
            current?.copy(passengers = current.passengers.map { if (it.id == passengerId) transform(it) else it })
        }
    }
}
