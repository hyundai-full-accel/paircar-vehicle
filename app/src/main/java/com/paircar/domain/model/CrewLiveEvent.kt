package com.paircar.domain.model

/**
 * 크루 서버가 실시간 구독(SSE)으로 차량 앱에 보내는 알림.
 * 동승자 웹 → 서버 → 차량 앱 (서로 직접 통신하지 않는다).
 * [passengerId] 는 오늘 운행의 동승자와 짝지어졌을 때만 있다 (초대 링크로 막 들어온 사람은 null).
 */
sealed interface CrewLiveEvent {

    sealed interface Rider : CrewLiveEvent {
        val passengerId: String?
        val name: String
    }

    /** 동승자 "N분 늦어요" (경로는 그대로) */
    data class RiderDelayed(override val passengerId: String?, override val name: String, val minutes: Int) : Rider

    /** 동승자 "나와 있어요" */
    data class RiderHere(override val passengerId: String?, override val name: String) : Rider

    /** 동승자가 오늘 탈 지점을 바꿈 → 경유지 갱신 */
    data class RiderPlaceChanged(override val passengerId: String?, override val name: String, val place: Place) : Rider

    /** 동승자 "오늘은 안 타요" → 경유지에서 빼고 정산에서 빠진다 */
    data class RiderAbsent(override val passengerId: String?, override val name: String) : Rider

    /**
     * 출발 전 출석 변경 ("내일은 안 타요", "오늘은 후문에서 탈게요").
     * [pickup] 은 그날 탈 곳 (null 이면 평소 탈 곳). 운행 중 불참은 [RiderAbsent].
     */
    data class RiderAttendanceChanged(
        override val passengerId: String?,
        override val name: String,
        val date: java.time.LocalDate,
        val attending: Boolean,
        val pickup: Place?,
    ) : Rider

    /** 월 영수증 발행 */
    data object ReceiptIssued : CrewLiveEvent

    /** 우회 거리 확인 요청 (운전자·동승자 둘 다 확인해야 정산에 쓴다) */
    data class DetourConfirmRequested(val detourId: Long, val placeName: String, val detourKm: Double) : CrewLiveEvent

    /** 앱이 아직 처리하지 않는 알림 */
    data class Other(val type: String) : CrewLiveEvent
}
