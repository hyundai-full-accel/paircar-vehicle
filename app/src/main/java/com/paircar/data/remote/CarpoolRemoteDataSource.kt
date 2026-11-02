package com.paircar.data.remote

/**
 * 서버 REST API 연동 지점 (미구현).
 *
 * API 명세가 확정되면 이 인터페이스를 Retrofit/Ktor 로 구현하고,
 * data.repository 에 CarpoolRepository 구현(DTO ↔ domain 매핑, 캐시)을 추가해
 * AppContainer 에서 MockCarpoolRepository 대신 주입한다. UI/도메인 코드는 바뀌지 않는다.
 */
interface CarpoolRemoteDataSource {
    suspend fun fetchTodayTrip(driverId: String): CarpoolTripDto?
    suspend fun respondToRequest(tripId: String, passengerId: String, accept: Boolean)
    suspend fun updateBoarding(tripId: String, passengerId: String, status: String)
    suspend fun updateTripStatus(tripId: String, status: String)
    /** QR 초대 코드 발급. 모바일웹이 같은 코드로 가입을 확인한다. */
    suspend fun createCrewInvite(crewId: String): CrewInviteDto
}

data class CrewInviteDto(
    val code: String,
    val url: String,
    val expiresAt: String,
)

/** 서버 응답 형태 가안. 필드는 API 확정 시 교체한다. */
data class CarpoolTripDto(
    val id: String,
    val status: String,
    val departureTime: String,
    val passengers: List<PassengerDto>,
)

data class PassengerDto(
    val id: String,
    val name: String,
    val pickupName: String,
    val pickupLatitude: Double,
    val pickupLongitude: Double,
    val pickupTime: String,
    val matchStatus: String,
    val boardingStatus: String,
)
