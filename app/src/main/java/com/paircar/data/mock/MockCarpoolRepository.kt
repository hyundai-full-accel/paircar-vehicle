package com.paircar.data.mock

import com.paircar.data.local.CrewInfoStore
import com.paircar.data.local.SavedTripState
import com.paircar.data.local.TripStateStore
import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInfo
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
    /** 진행 상태를 기기에 둔다. 앱 프로세스가 끝나도(Pleos 창 닫기) 운행이 이어진다. */
    private val stateStore: TripStateStore? = null,
    /** 운전자가 바꾼 크루 이름·출발 시각·요일 */
    private val crewInfoStore: CrewInfoStore? = null,
) : CarpoolRepository {

    private val savedInfo = crewInfoStore?.load()

    private val trip = MutableStateFlow<CarpoolTrip?>(restore(scheduled(tripFactory())))
    override val todayTrip: StateFlow<CarpoolTrip?> = trip.asStateFlow()

    private val _crew = MutableStateFlow<Crew?>(savedInfo?.let { crew.copy(name = it.name, days = it.days) } ?: crew)
    override val crew: StateFlow<Crew?> = _crew.asStateFlow()

    private val random = SecureRandom()

    override suspend fun refresh() {
        delay(latencyMillis)
        if (trip.value == null) trip.value = scheduled(tripFactory())
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
        persist()
    }

    override suspend fun updateAttendance(passengerId: String, attendance: Attendance) {
        delay(latencyMillis)
        updatePassenger(passengerId) { it.copy(attendance = attendance) }
    }

    /** 출발 시각은 아직 출발 전인 오늘 운행에만 반영한다 (진행 중인 운행의 시각은 바꾸지 않는다) */
    override suspend fun updateCrewInfo(info: CrewInfo) {
        delay(latencyMillis)
        _crew.update { it?.copy(name = info.name, days = info.days) }
        trip.update { current ->
            if (current?.status == CarpoolStatus.SCHEDULED) current.rescheduled(info.departureTime) else current
        }
        crewInfoStore?.save(info)
        persist()
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
        persist()
    }

    private fun scheduled(fresh: CarpoolTrip): CarpoolTrip = savedInfo?.let { fresh.rescheduled(it.departureTime) } ?: fresh

    private fun restore(fresh: CarpoolTrip): CarpoolTrip = stateStore?.load()?.applyTo(fresh) ?: fresh

    private fun persist() {
        trip.value?.let { stateStore?.save(SavedTripState.of(it)) }
    }
}
