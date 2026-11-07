package com.paircar.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** 같은 시간·경로로 정기 운행하는 카풀 묶음. 크루원(운전자·동승자)은 [CarpoolTrip] 에서 가져온다. */
data class Crew(
    val id: String,
    val name: String,
    val startDate: LocalDate,
    /** 반복 운행 요일 */
    val days: Set<DayOfWeek>,
    /** 서버의 크루 명단 (운전자 먼저, 가입순). 서버 연결 전(Mock)에는 비어 있고 화면은 오늘 운행의 동승자를 보여준다. */
    val members: List<CrewMember> = emptyList(),
) {
    /** 월-금 · 주말 · 매일 · 월·수·목 */
    val daysLabel: String
        get() = when (days) {
            DayOfWeek.entries.toSet() -> "매일"
            WEEKDAYS -> "월-금"
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "주말"
            else -> days.sorted().joinToString("·") { it.korean() }
        }

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it.value <= 5 }.toSet()
    }
}

/** 크루 명단의 한 사람. 서버 crewMember */
data class CrewMember(
    val id: String,
    val name: String,
    val isDriver: Boolean,
    /** 가입 시각 (KST) */
    val joinedAt: LocalDateTime,
    /** 오늘 운행의 동승자([Passenger.id])와 같은 사람이면 그 ID. 초대 링크로 막 들어온 멤버는 null */
    val passengerId: String? = null,
)

/** 월, 화, … */
fun DayOfWeek.korean(): String = getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/**
 * [크루 정보 설정]에서 운전자가 바꾸는 값. 출발 시각을 바꾸면 도착·픽업 예정 시각도 같은 만큼 옮긴다.
 * 출발지·도착지는 장소 검색 연동 전이라 아직 바꿀 수 없다.
 */
data class CrewInfo(
    val name: String,
    val departureTime: LocalTime,
    val days: Set<DayOfWeek>,
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
