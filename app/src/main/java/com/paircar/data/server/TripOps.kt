package com.paircar.data.server

/** 서버에 올릴 장소. 같은 이름·좌표면 같은 크루 장소(placeId)로 본다. */
data class PlaceRef(val name: String, val latitude: Double, val longitude: Double) {
    val key: String
        get() = "$name@$latitude,$longitude"
}

/** 운행 시작 때 알리는 동승자. 서버는 여기 없는 동승자의 탑승 기록을 받지 않는다 (BOARDING_NOT_FOUND). */
data class RiderRef(val passengerId: String, val board: PlaceRef, val alight: PlaceRef)

/** 구간: 탑승 인원이 바뀌는 지점마다 나눈 주행 구간 */
data class SegmentRef(
    val seq: Int,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val fuelEconomy: Double,
    /** MEASURED(차량 값) | MANUAL(운전자 입력) */
    val fuelEconomySource: String,
    val riderPassengerIds: List<String>,
)

/**
 * 서버에 보낼 운행 요청. 오프라인이어도 잃지 않도록 기기에 쌓아 두고 발생 순서대로 보낸다
 * (서버 문서: 탑승 → 하차 → 구간 → 종료, 같은 요청을 다시 보내도 결과가 같다).
 * 서버 ID 대신 앱 ID(동승자 ID·장소)로 적어 두고, 보낼 때 서버 ID 로 바꾼다.
 * 시각은 [ServerTime.format] 으로 만든 오프셋 포함 문자열이다.
 */
sealed interface TripOp {
    /** 앱의 운행 ID. 시작 요청이 성공하면 서버 tripId 와 짝지어 둔다. */
    val tripKey: String

    data class Start(
        override val tripKey: String,
        val startedAt: String,
        val odometerKm: Double,
        val origin: PlaceRef,
        val destination: PlaceRef,
        val riders: List<RiderRef>,
    ) : TripOp

    data class Board(override val tripKey: String, val passengerId: String, val occurredAt: String, val odometerKm: Double) : TripOp

    data class Alight(override val tripKey: String, val passengerId: String, val occurredAt: String, val odometerKm: Double) : TripOp

    /** 불참 (미탑승 포함) */
    data class Absent(override val tripKey: String, val passengerId: String) : TripOp

    data class Segments(override val tripKey: String, val segments: List<SegmentRef>) : TripOp

    data class End(override val tripKey: String, val endedAt: String, val odometerKm: Double, val tollFareWon: Int) : TripOp
}

/** 구간을 나누려고 기억해 두는 탑승·하차 시점의 주행거리 */
data class RideEvent(val passengerId: String, val boarded: Boolean, val odometerKm: Double)

/**
 * 출발~도착 주행거리를 탑승·하차 지점에서 잘라 구간을 만든다.
 * - 거리가 0인 구간은 빼고, 붙어 있는 구간의 탑승 인원이 같으면 하나로 합친다.
 * - 주행거리가 거꾸로 가면(센서 값 흔들림) 앞 값에 맞춘다. 서버는 줄어드는 값을 거절한다.
 * - 에뮬레이터처럼 주행거리가 안 바뀌면 구간이 없다 (빈 목록).
 */
fun buildSegments(
    startOdometerKm: Double,
    endOdometerKm: Double,
    events: List<RideEvent>,
    fuelEconomy: Double,
    fuelEconomySource: String,
): List<SegmentRef> {
    data class Span(val start: Double, val end: Double, val riders: List<String>)

    val spans = mutableListOf<Span>()
    val riders = linkedSetOf<String>()
    var cursor = startOdometerKm

    fun close(at: Double) {
        val end = maxOf(at, cursor)
        if (end > cursor) {
            val current = riders.toList().sorted()
            val last = spans.lastOrNull()
            if (last != null && last.riders == current && last.end == cursor) {
                spans[spans.lastIndex] = last.copy(end = end)
            } else {
                spans += Span(cursor, end, current)
            }
        }
        cursor = end
    }

    events.forEach { event ->
        close(event.odometerKm)
        if (event.boarded) riders += event.passengerId else riders -= event.passengerId
    }
    close(endOdometerKm)

    return spans.mapIndexed { index, span ->
        SegmentRef(
            seq = index + 1,
            startOdometerKm = roundTenth(span.start),
            endOdometerKm = roundTenth(span.end),
            fuelEconomy = Math.round(fuelEconomy * 100) / 100.0,
            fuelEconomySource = fuelEconomySource,
            riderPassengerIds = span.riders,
        )
    }
}

/** 서버 규칙: km 는 소수 1자리 */
fun roundTenth(km: Double): Double = Math.round(km * 10) / 10.0

/** 서버 좌표 정밀도: 소수 6자리 (약 10cm) */
fun roundCoordinate(value: Double): Double = Math.round(value * 1_000_000) / 1_000_000.0
