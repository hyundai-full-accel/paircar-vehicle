package com.paircar.data.local

import android.util.Log
import com.paircar.core.model.GeoPoint
import com.paircar.domain.model.DistanceSource
import com.paircar.domain.model.RouteComparison
import com.paircar.domain.model.TripKind
import com.paircar.domain.model.TripRecord
import com.paircar.domain.model.TripStop
import com.paircar.domain.model.TripStopType
import com.paircar.domain.repository.TripRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * 운행 기록을 앱 내부 저장소의 JSON 파일 하나에 저장한다 (정산 서버 미구축).
 * [seed] 는 화면 개발용 지난 기록(Mock)으로, 파일에는 쓰지 않고 목록에만 합친다.
 */
class LocalTripRecordRepository(
    private val file: File,
    private val seed: List<TripRecord> = emptyList(),
) : TripRecordRepository {

    private val saved = MutableStateFlow(load())
    private val all = MutableStateFlow(merge(saved.value))
    override val records: StateFlow<List<TripRecord>> = all.asStateFlow()

    override suspend fun save(record: TripRecord) {
        val next = saved.value.filterNot { it.id == record.id } + record
        withContext(Dispatchers.IO) {
            // 쓰다가 앱이 꺼져도 기존 파일이 깨지지 않게 임시 파일에 쓰고 바꾼다
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(JSONArray(next.map { it.toJson() }).toString())
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "운행 기록 파일을 저장하지 못했어요" }
            }
        }
        saved.value = next
        all.update { merge(next) }
    }

    private fun merge(records: List<TripRecord>): List<TripRecord> =
        (seed.filterNot { s -> records.any { it.id == s.id } } + records)
            .sortedWith(compareByDescending<TripRecord> { it.date }.thenByDescending { it.startTime })

    private fun load(): List<TripRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getJSONObject(it).toRecord() }
        }.onFailure { Log.w(TAG, "load failed: $it") }.getOrDefault(emptyList())
    }

    private fun TripRecord.toJson() = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("date", date.toString())
        .put("start", startTime.toString())
        .put("end", endTime.toString())
        .put("distanceKm", distanceKm)
        .put("distanceSource", distanceSource.name)
        .put("detourKm", detourKm ?: JSONObject.NULL)
        .put("arrivedByNavi", arrivedByNavi)
        .put("stops", JSONArray(stops.map { it.toJson() }))
        .put("path", JSONArray(path.map { JSONArray().put(it.latitude).put(it.longitude) }))
        .put("comparison", routeComparison?.toJson() ?: JSONObject.NULL)

    private fun RouteComparison.toJson() = JSONObject()
        .put("directKm", directKm)
        .put("directSeconds", directSeconds)
        .put("carpoolKm", carpoolKm)
        .put("carpoolSeconds", carpoolSeconds)

    private fun TripStop.toJson() = JSONObject()
        .put("time", time.toString())
        .put("type", type.name)
        .put("place", placeName)
        .put("lat", point.latitude)
        .put("lng", point.longitude)
        .put("passenger", passengerName ?: JSONObject.NULL)
        .put("area", area ?: JSONObject.NULL)

    private fun JSONObject.toRecord(): TripRecord {
        val stops = getJSONArray("stops")
        val path = getJSONArray("path")
        return TripRecord(
            id = getString("id"),
            kind = TripKind.valueOf(getString("kind")),
            date = LocalDate.parse(getString("date")),
            startTime = LocalTime.parse(getString("start")),
            endTime = LocalTime.parse(getString("end")),
            distanceKm = getDouble("distanceKm"),
            distanceSource = DistanceSource.valueOf(getString("distanceSource")),
            detourKm = if (isNull("detourKm")) null else getDouble("detourKm"),
            stops = (0 until stops.length()).map { stops.getJSONObject(it).toStop() },
            path = (0 until path.length()).map { path.getJSONArray(it).let { p -> GeoPoint(p.getDouble(0), p.getDouble(1)) } },
            arrivedByNavi = getBoolean("arrivedByNavi"),
            // 이 값이 생기기 전에 저장한 기록에는 없다
            routeComparison = optJSONObject("comparison")?.let {
                RouteComparison(
                    directKm = it.getDouble("directKm"),
                    directSeconds = it.getInt("directSeconds"),
                    carpoolKm = it.getDouble("carpoolKm"),
                    carpoolSeconds = it.getInt("carpoolSeconds"),
                )
            },
        )
    }

    private fun JSONObject.toStop() = TripStop(
        time = LocalTime.parse(getString("time")),
        type = TripStopType.valueOf(getString("type")),
        placeName = getString("place"),
        point = GeoPoint(getDouble("lat"), getDouble("lng")),
        passengerName = if (isNull("passenger")) null else getString("passenger"),
        area = if (has("area") && !isNull("area")) getString("area") else null,
    )

    private companion object {
        const val TAG = "LocalTripRecords"
    }
}
