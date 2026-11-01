package com.paircar.data.mock

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.repository.CarpoolRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MockCarpoolRepository(
    private val latencyMillis: Long = 150,
    private val tripFactory: () -> CarpoolTrip = { MockCarpoolData.todayTrip() },
) : CarpoolRepository {

    private val trip = MutableStateFlow<CarpoolTrip?>(tripFactory())
    override val todayTrip: StateFlow<CarpoolTrip?> = trip.asStateFlow()

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
        updatePassenger(passengerId) { it.copy(boardingStatus = status) }
    }

    override suspend fun updateTripStatus(status: CarpoolStatus) {
        delay(latencyMillis)
        trip.update { it?.copy(status = status) }
    }

    private fun updatePassenger(passengerId: String, transform: (Passenger) -> Passenger) {
        trip.update { current ->
            current?.copy(passengers = current.passengers.map { if (it.id == passengerId) transform(it) else it })
        }
    }
}
