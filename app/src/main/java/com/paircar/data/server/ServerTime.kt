package com.paircar.data.server

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 서버 시각 규칙: 서버는 시각을 UTC Instant 로 다루고 응답도 `...Z` 로 준다.
 * 운행일·정산 월 같은 날짜 경계만 KST 로 계산한다.
 *
 * - 보낼 때: 오프셋이 꼭 있어야 한다. 오프셋 없는 `2026-11-07T08:30:00` 은 400(INVALID_JSON).
 *   같은 순간을 KST 오프셋으로 적어 보낸다 (`2026-11-07T08:30:00+09:00`). 로그에서 읽기 쉽다.
 * - 받을 때: `Z`, `+09:00`, 나노초 자릿수가 달라도 같은 순간으로 읽고, 화면에는 KST 로 바꿔 쓴다.
 */
object ServerTime {

    /** 서버가 운행일·정산 월을 자르는 기준 */
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * 앱의 [LocalDateTime] 은 기기 시간대의 벽시계 값이다 (LocalDateTime.now()).
     * [deviceZone] 으로 실제 순간을 구한 뒤 KST 오프셋으로 적는다. 기기가 UTC 여도 순간은 맞다.
     */
    fun format(local: LocalDateTime, deviceZone: ZoneId = ZoneId.systemDefault()): String =
        format(local.atZone(deviceZone).toInstant())

    fun format(instant: Instant): String =
        instant.truncatedTo(ChronoUnit.SECONDS).atZone(KST).toOffsetDateTime().format(FORMAT)

    fun parse(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    /** 서버 시각을 화면용 KST 벽시계 값으로 */
    fun toKst(text: String): LocalDateTime = LocalDateTime.ofInstant(parse(text), KST)

    /** 서버 시각을 앱의 LocalDateTime.now() 와 비교할 수 있는 기기 벽시계 값으로 (한국 차량이면 KST 와 같다) */
    fun toDeviceLocal(text: String, deviceZone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(parse(text), deviceZone)
}
