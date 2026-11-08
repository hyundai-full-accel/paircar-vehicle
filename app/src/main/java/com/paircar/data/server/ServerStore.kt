package com.paircar.data.server

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 서버에서 받은 ID·기기 토큰과 아직 못 보낸 운행 요청.
 * 토큰은 멤버 생성 응답에 한 번만 오므로 잃으면 새 멤버를 만들어야 한다.
 */
interface ServerStore {
    var token: String?
    var memberId: Long?
    var crewId: Long?
    var carId: Long?

    /** 차량 등록 키. 서버를 초기화해도 같은 차로 보이도록 [reset] 에서 지우지 않는다. */
    val vehicleKey: String

    /** 이 기기(운전자)의 크루 멤버 ID. 우회 거리 등록의 driverId */
    var driverCrewMemberId: Long?

    /** 서버에 등록·확인한 우회 거리 ("동승자ID@장소키") */
    var registeredDetours: Set<String>

    /** 개발용: 대신 가입시킨 예시 동승자의 토큰 (동승자 웹 동작을 흉내 내 확인할 때만 쓴다) */
    var seededRiderTokens: Map<String, String>

    /** [PlaceRef.key] → 크루 장소 placeId */
    var placeIds: Map<String, Long>

    /** 앱 동승자 ID → 크루 멤버 crewMemberId */
    var crewMemberIds: Map<String, Long>

    /** 앱 운행 ID → 서버 tripId. 시작이 거절된 운행은 [FAILED_TRIP] */
    var tripIds: Map<String, Long>

    /** 진행 중인 운행의 탑승·하차 주행거리 (끝날 때 구간을 나눈다) */
    var rideLog: RideLog?

    var outbox: List<TripOp>

    /** 서버에 마지막으로 올린 계좌(해시)와 충전 단가. 바뀌었을 때만 다시 올린다. */
    var syncedAccountHash: String?
    var syncedFuelPrice: String?

    /** 서버 DB 가 초기화됐거나 토큰이 무효일 때. 못 보낸 운행 요청과 차량 키는 남긴다. */
    fun reset()

    companion object {
        const val FAILED_TRIP = -1L
    }
}

data class RideLog(val tripKey: String, val startOdometerKm: Double, val events: List<RideEvent>)

class PrefsServerStore(context: Context) : ServerStore {

    private val prefs = context.getSharedPreferences("server_session", Context.MODE_PRIVATE)

    override var token: String?
        get() = prefs.getString("token", null)
        set(value) = put("token", value)

    override var memberId: Long? by longPref("member_id")
    override var crewId: Long? by longPref("crew_id")
    override var carId: Long? by longPref("car_id")

    override val vehicleKey: String
        get() = prefs.getString("vehicle_key", null) ?: UUID.randomUUID().toString().also { put("vehicle_key", it) }

    override var driverCrewMemberId: Long? by longPref("driver_crew_member_id")

    override var registeredDetours: Set<String>
        get() = prefs.getStringSet("registered_detours", null)?.toSet().orEmpty()
        set(value) {
            prefs.edit().putStringSet("registered_detours", value).commit()
        }

    override var seededRiderTokens: Map<String, String>
        get() = runCatching {
            val json = JSONObject(prefs.getString("seeded_rider_tokens", null) ?: return emptyMap())
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
        set(value) = put("seeded_rider_tokens", JSONObject(value).toString())

    override var placeIds: Map<String, Long>
        get() = readMap("place_ids")
        set(value) = writeMap("place_ids", value)

    override var crewMemberIds: Map<String, Long>
        get() = readMap("crew_member_ids")
        set(value) = writeMap("crew_member_ids", value)

    override var tripIds: Map<String, Long>
        get() = readMap("trip_ids")
        set(value) = writeMap("trip_ids", value)

    override var rideLog: RideLog?
        get() = runCatching {
            val json = JSONObject(prefs.getString("ride_log", null) ?: return null)
            val events = json.getJSONArray("events")
            RideLog(
                tripKey = json.getString("tripKey"),
                startOdometerKm = json.getDouble("start"),
                events = (0 until events.length()).map { i ->
                    val e = events.getJSONObject(i)
                    RideEvent(e.getString("p"), e.getBoolean("boarded"), e.getDouble("odo"))
                },
            )
        }.getOrNull()
        set(value) = put(
            "ride_log",
            value?.let { log ->
                JSONObject()
                    .put("tripKey", log.tripKey)
                    .put("start", log.startOdometerKm)
                    .put("events", JSONArray(log.events.map { JSONObject().put("p", it.passengerId).put("boarded", it.boarded).put("odo", it.odometerKm) }))
                    .toString()
            },
        )

    override var outbox: List<TripOp>
        get() = runCatching {
            val array = JSONArray(prefs.getString("outbox", null) ?: return emptyList())
            (0 until array.length()).mapNotNull { TripOpJson.decode(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(value) {
            prefs.edit().putString("outbox", JSONArray(value.map(TripOpJson::encode)).toString()).commit()
        }

    override var syncedAccountHash: String?
        get() = prefs.getString("synced_account", null)
        set(value) = put("synced_account", value)

    override var syncedFuelPrice: String?
        get() = prefs.getString("synced_fuel_price", null)
        set(value) = put("synced_fuel_price", value)

    override fun reset() {
        // 지난 서버의 tripId 는 새 서버에 없으므로 지운다. 남은 요청은 운행 시작부터 다시 보낸다.
        val keep = setOf("vehicle_key", "outbox", "ride_log")
        prefs.edit().apply { prefs.all.keys.filterNot { it in keep }.forEach(::remove) }.commit()
    }

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }

    private fun readMap(key: String): Map<String, Long> = runCatching {
        val json = JSONObject(prefs.getString(key, null) ?: return emptyMap())
        json.keys().asSequence().associateWith { json.getLong(it) }
    }.getOrDefault(emptyMap())

    private fun writeMap(key: String, value: Map<String, Long>) = put(key, JSONObject(value).toString())

    private fun longPref(key: String) = object : kotlin.properties.ReadWriteProperty<Any, Long?> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>): Long? =
            prefs.getString(key, null)?.toLongOrNull()

        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Long?) = put(key, value?.toString())
    }
}

/** [TripOp] ↔ JSON (기기 저장용) */
internal object TripOpJson {

    fun encode(op: TripOp): JSONObject = JSONObject().put("tripKey", op.tripKey).also { json ->
        when (op) {
            is TripOp.Start -> json.put("type", "start")
                .put("startedAt", op.startedAt)
                .put("odo", op.odometerKm)
                .put("origin", place(op.origin))
                .put("destination", place(op.destination))
                .put("riders", JSONArray(op.riders.map { JSONObject().put("p", it.passengerId).put("board", place(it.board)).put("alight", place(it.alight)) }))
            is TripOp.Board -> json.put("type", "board").put("p", op.passengerId).put("at", op.occurredAt).put("odo", op.odometerKm)
            is TripOp.Alight -> json.put("type", "alight").put("p", op.passengerId).put("at", op.occurredAt).put("odo", op.odometerKm)
            is TripOp.Absent -> json.put("type", "absent").put("p", op.passengerId)
            is TripOp.Segments -> json.put("type", "segments").put(
                "segments",
                JSONArray(
                    op.segments.map {
                        JSONObject().put("seq", it.seq).put("start", it.startOdometerKm).put("end", it.endOdometerKm)
                            .put("fe", it.fuelEconomy).put("src", it.fuelEconomySource).put("riders", JSONArray(it.riderPassengerIds))
                    },
                ),
            )
            is TripOp.End -> json.put("type", "end").put("at", op.endedAt).put("odo", op.odometerKm).put("toll", op.tollFareWon)
        }
    }

    fun decode(json: JSONObject): TripOp? {
        val key = json.getString("tripKey")
        return when (json.getString("type")) {
            "start" -> TripOp.Start(
                tripKey = key,
                startedAt = json.getString("startedAt"),
                odometerKm = json.getDouble("odo"),
                origin = place(json.getJSONObject("origin")),
                destination = place(json.getJSONObject("destination")),
                riders = json.getJSONArray("riders").let { a ->
                    (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        RiderRef(r.getString("p"), place(r.getJSONObject("board")), place(r.getJSONObject("alight")))
                    }
                },
            )
            "board" -> TripOp.Board(key, json.getString("p"), json.getString("at"), json.getDouble("odo"))
            "alight" -> TripOp.Alight(key, json.getString("p"), json.getString("at"), json.getDouble("odo"))
            "absent" -> TripOp.Absent(key, json.getString("p"))
            "segments" -> TripOp.Segments(
                key,
                json.getJSONArray("segments").let { a ->
                    (0 until a.length()).map { i ->
                        val s = a.getJSONObject(i)
                        val riders = s.getJSONArray("riders")
                        SegmentRef(
                            s.getInt("seq"), s.getDouble("start"), s.getDouble("end"), s.getDouble("fe"), s.getString("src"),
                            (0 until riders.length()).map(riders::getString),
                        )
                    }
                },
            )
            "end" -> TripOp.End(key, json.getString("at"), json.getDouble("odo"), json.getInt("toll"))
            else -> null
        }
    }

    private fun place(p: PlaceRef) = JSONObject().put("name", p.name).put("lat", p.latitude).put("lng", p.longitude)

    private fun place(json: JSONObject) = PlaceRef(json.getString("name"), json.getDouble("lat"), json.getDouble("lng"))
}
