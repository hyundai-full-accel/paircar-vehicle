package com.paircar.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** 같은 시간·경로로 정기 운행하는 카풀 묶음. 크루원(운전자·동승자)은 [CarpoolTrip] 에서 가져온다. */
data class Crew(
    val id: String,
    val name: String,
    val startDate: LocalDate,
    /** "월-금" */
    val weekdays: String,
)

/**
 * QR 초대. QR 에는 [url] 이 들어가고, 휴대폰으로 스캔하면 모바일웹 가입 화면이 열린다.
 * 코드 발급·검증은 서버 몫(미구축). 지금은 Mock 이 코드를 만든다.
 */
data class CrewInvite(
    val code: String,
    val url: String,
    val expiresAt: LocalDateTime,
)

object InviteLinks {
    /** 사람이 옮겨 적기 쉬운 문자만 (0/O, 1/I/L 제외) */
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6

    fun build(baseUrl: String, code: String): String = "${baseUrl.trimEnd('/')}?code=$code"
}
