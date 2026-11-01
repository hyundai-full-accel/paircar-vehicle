package com.paircar.domain.model

/** CarUxRestrictions 를 앱이 쓰는 형태로 단순화한 주행 규제 상태 */
data class DrivingState(
    /** CarUxRestrictions.isRequiresDistractionOptimization() */
    val restricted: Boolean,
    /** UX_RESTRICTIONS_NO_KEYBOARD */
    val noKeyboard: Boolean = restricted,
    /** UX_RESTRICTIONS_LIMIT_CONTENT */
    val limitContent: Boolean = restricted,
    val source: Source,
) {
    enum class Source {
        /** 차량 CarUxRestrictionsManager 에서 수신 */
        CAR_UX_RESTRICTIONS,
        /** 개발용 주행 시뮬레이션 토글 */
        SIMULATED,
        /** 차량 API 없음(폰 에뮬레이터 등) → 정차로 간주 */
        UNAVAILABLE,
    }

    companion object {
        val Unavailable = DrivingState(restricted = false, source = Source.UNAVAILABLE)
    }
}

/** 화면/조작 단위의 주행 중 허용 정책 */
enum class CarpoolAction {
    VIEW_HOME,
    VIEW_DRIVING_SUMMARY,
    OPEN_NAVIGATION,
    START_GUIDANCE,
    CONFIRM_BOARDING,
    BROWSE_MATCHING,
    RESPOND_TO_REQUEST,
    VIEW_TRIP_DETAIL,
    CANCEL_CARPOOL,
    NAVI_DEBUG,
}

object DrivingPolicy {
    /**
     * 주행 중(restricted) 허용: 한 번의 탭으로 끝나는 조작과 한눈에 읽는 정보.
     * - 경로 시작은 미리 정해진 경로(프리셋) 선택이라 허용 (Pleos 가이드: 키보드보다 프리셋 선택 우선)
     * 정차 중에만 허용: 목록 탐색, 요청 수락/거절, 상세 정보, 탑승 확인, 취소, 개발용 화면
     */
    private val allowedWhileDriving = setOf(
        CarpoolAction.VIEW_DRIVING_SUMMARY,
        CarpoolAction.OPEN_NAVIGATION,
        CarpoolAction.START_GUIDANCE,
    )

    fun isAllowed(action: CarpoolAction, state: DrivingState): Boolean =
        !state.restricted || action in allowedWhileDriving
}
