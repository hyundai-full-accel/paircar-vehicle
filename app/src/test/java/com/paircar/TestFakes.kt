package com.paircar

import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.VehicleEnergy
import com.paircar.domain.repository.RoutePreview
import com.paircar.domain.repository.RoutePreviewRepository
import com.paircar.domain.repository.TripRecordRepository
import com.paircar.domain.repository.VehicleEnergyRepository
import kotlinx.coroutines.flow.MutableStateFlow

class MemoryTripRecords : TripRecordRepository {
    override val records = MutableStateFlow<List<TripRecord>>(emptyList())
    override suspend fun save(record: TripRecord) {
        records.value = listOf(record) + records.value
    }
}

/** read() 할 때마다 다음 값을 돌려준다 (출발·도착 주행거리 흉내) */
class SequenceEnergy(vararg values: VehicleEnergy) : VehicleEnergyRepository {
    private val queue = ArrayDeque(values.toList())
    override suspend fun read(): VehicleEnergy = queue.removeFirstOrNull() ?: VehicleEnergy()
}

/**
 * 정류장 수에 따라 거리를 정한다: 2곳(직행) 59.4km, 3곳 이상(경유) 68.3km.
 * [directFails] 면 직행 길찾기가 실패해 직선 추정(48.1km)으로 돌아온 것처럼 한다.
 */
class FakeRoutes(private val directFails: Boolean = false) : RoutePreviewRepository {
    override suspend fun preview(stops: List<GeoPoint>): RoutePreview {
        val direct = stops.size <= 2
        val failed = direct && directFails
        return RoutePreview(
            path = stops,
            distanceMeters = when {
                failed -> 48_100
                direct -> 59_400
                else -> 68_300
            },
            durationSeconds = if (direct) 3_120 else 3_660,
            isApproximate = failed,
        )
    }
}
