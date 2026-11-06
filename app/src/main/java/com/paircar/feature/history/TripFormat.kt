package com.paircar.feature.history

import com.paircar.core.ui.components.formatDuration
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStopType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// 운행 종료·운행 기록 화면이 같이 쓰는 표기

private val DateDots = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val MonthDayWeek = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
private val HourMinute = DateTimeFormatter.ofPattern("HH:mm")

internal fun LocalDate.dots(): String = format(DateDots)

internal fun LocalDate.monthDayWeek(): String = format(MonthDayWeek)

internal fun LocalTime.hm(): String = format(HourMinute)

/** 82.1km */
internal fun km(value: Double): String = "%.1fkm".format(value)

/** 주행거리를 차량에서 못 읽어 경로 거리로 대신했으면 "(추정)" 을 붙인다 */
internal fun TripRecord.distanceLabel(): String =
    km(distanceKm) + if (distanceSource == DistanceSource.ROUTE_ESTIMATE) " (추정)" else ""

internal fun TripStopType.label(): String = when (this) {
    TripStopType.DEPART -> "출발"
    TripStopType.PICKUP -> "픽업"
    TripStopType.ARRIVE -> "도착"
}

/**
 * 공유용 요약 문구. 동승자 이름·장소 이름·상세 주소는 넣지 않고 시·구 단위 지역만 쓴다.
 */
internal fun TripRecord.shareText(): String = buildList {
    add("[페어카 운행 요약]")
    add("${date.monthDayWeek()} ${startTime.hm()}~${endTime.hm()} · ${formatDuration(durationSeconds)}")
    add(
        listOf(
            "총 이동 거리 ${distanceLabel()}",
            "추가 우회 ${detourKm?.let { "+${km(it)}" } ?: "-"}",
            "동승 ${passengerNames.size}명",
        ).joinToString(" · "),
    )
    stops.forEach { add("${it.time.hm()} ${it.type.label()} · ${it.area ?: "지역 정보 없음"}") }
}.joinToString("\n")
