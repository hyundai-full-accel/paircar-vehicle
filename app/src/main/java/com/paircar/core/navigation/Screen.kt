package com.paircar.core.navigation

import com.paircar.domain.model.CarpoolAction

/** 화면과, 그 화면을 보여주기 위해 필요한 조작 권한(주행 중 허용 여부 판단용) */
enum class Screen(val title: String, val action: CarpoolAction) {
    HOME("페어카", CarpoolAction.VIEW_HOME),
    CREW("내 크루", CarpoolAction.VIEW_CREW),
    MATCHING("카풀 매칭", CarpoolAction.BROWSE_MATCHING),
    DETAIL("카풀 상세", CarpoolAction.VIEW_TRIP_DETAIL),
    DRIVING("주행 중", CarpoolAction.VIEW_DRIVING_SUMMARY),
    SETTINGS("설정", CarpoolAction.VIEW_SETTINGS),
    NAVI_POC("내비 연동 PoC", CarpoolAction.NAVI_DEBUG),
}
