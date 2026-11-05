package com.paircar.feature.history

import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.TripRecord
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
