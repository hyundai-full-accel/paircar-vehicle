package com.paircar.core.ui.map

import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStopType

/** 카풀 일정 → 지도 정류장 마커 (탑승을 마친 픽업 지점은 회색) */
fun CarpoolTrip.toMapStops(): List<MapStop> = buildList {
    add(MapStop(origin.point, StopKind.ORIGIN))
    pickupsInOrder.forEach { passenger ->
        val done = passenger.boardingStatus == BoardingStatus.BOARDED ||
            passenger.boardingStatus == BoardingStatus.DROPPED_OFF
        add(MapStop(passenger.pickup.point, if (done) StopKind.PICKUP_DONE else StopKind.PICKUP))
    }
    add(MapStop(destination.point, StopKind.DESTINATION))
}

/** 끝난 운행 → 지도 정류장 마커 (탄 지점은 모두 완료 표시) */
fun TripRecord.toMapStops(): List<MapStop> = stops.map { stop ->
    val kind = when (stop.type) {
        TripStopType.DEPART -> StopKind.ORIGIN
        TripStopType.PICKUP -> StopKind.PICKUP_DONE
        TripStopType.ARRIVE -> StopKind.DESTINATION
    }
    MapStop(stop.point, kind)
}
