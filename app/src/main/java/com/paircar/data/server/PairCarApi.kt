package com.paircar.data.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 서버 오류. [code] 는 서버의 `error.code` (네트워크 오류면 NETWORK).
 * 문서 규칙: 5xx·429 는 잠시 뒤 다시 보내고, 그 밖의 4xx 는 다시 보내도 실패한다.
 */
class ApiException(val status: Int, val code: String, message: String) : IOException("$status $code: $message") {
    val retryable: Boolean
        get() = status == 0 || status == 429 || status >= 500
}

/**
 * 페어카 크루 서버 REST 클라이언트 (Swagger: /swagger-ui/index.html).
 * 응답 `{ success, data }` 에서 data 만 돌려주고, 실패면 [ApiException] 을 던진다.
 * 인증은 기기 토큰 `Authorization: Bearer {token}`.
 */
class PairCarApi(
    private val baseUrl: String,
    private val token: () -> String?,
) {

    // 멤버

    suspend fun createMember(nickname: String): JSONObject =
        call("POST", "/api/members", JSONObject().put("nickname", nickname), auth = false) as JSONObject

    suspend fun me(): JSONObject = call("GET", "/api/members/me") as JSONObject

    suspend fun putBankAccount(bankName: String, accountNumber: String, accountHolder: String): JSONObject =
        call(
            "PUT", "/api/members/me/bank-account",
            JSONObject().put("bankName", bankName).put("accountNumber", accountNumber).put("accountHolder", accountHolder),
        ) as JSONObject

    // 차량

    suspend fun registerCar(vehicleKey: String, fuelType: String, manualFuelEconomy: Double?): JSONObject =
        call(
            "POST", "/api/cars",
            JSONObject().put("vehicleKey", vehicleKey).put("fuelType", fuelType).put("manualFuelEconomy", manualFuelEconomy ?: JSONObject.NULL),
        ) as JSONObject

    // 크루

    suspend fun createCrew(name: String): JSONObject =
        call("POST", "/api/crews", JSONObject().put("name", name)) as JSONObject

    suspend fun myCrews(): JSONArray = call("GET", "/api/crews/me") as JSONArray

    suspend fun crew(crewId: Long): JSONObject = call("GET", "/api/crews/$crewId") as JSONObject

    suspend fun crewMembers(crewId: Long): JSONArray = call("GET", "/api/crews/$crewId/members") as JSONArray

    suspend fun registerPlace(crewId: Long, name: String, latitude: Double, longitude: Double): JSONObject =
        call(
            "POST", "/api/crews/$crewId/places",
            JSONObject().put("name", name).put("latitude", latitude).put("longitude", longitude),
        ) as JSONObject

    suspend fun createInvite(crewId: Long): JSONObject = call("POST", "/api/crews/$crewId/invites") as JSONObject

    suspend fun joinAsNewMember(code: String, nickname: String): JSONObject =
        call("POST", "/api/invites/$code/members", JSONObject().put("nickname", nickname), auth = false) as JSONObject

    suspend fun changeFuelPrice(crewId: Long, priceWon: Int?): JSONObject =
        call("PUT", "/api/crews/$crewId/fuel-price", JSONObject().put("priceWon", priceWon ?: JSONObject.NULL)) as JSONObject

    // 정산 (month: "2026-11")

    /** 내 월 영수증 (운전자면 받을 영수증, 동승자면 낼 영수증) */
    suspend fun receipts(crewId: Long, month: String): JSONArray =
        call("GET", "/api/crews/$crewId/receipts?month=$month") as JSONArray

    /** 월 영수증 발행. 새로 발행한 영수증만 돌려준다 (이미 발행된 것은 빈 목록) */
    suspend fun issueReceipts(crewId: Long, month: String): JSONArray =
        call("POST", "/api/crews/$crewId/receipts?month=$month") as JSONArray

    // 우회 거리 (passengerId·driverId 는 crewMemberId)

    suspend fun registerDetour(crewId: Long, passengerId: Long, driverId: Long, placeId: Long, detourKm: Double): JSONObject =
        call(
            "PUT", "/api/crews/$crewId/detours",
            JSONObject().put("passengerId", passengerId).put("driverId", driverId).put("placeId", placeId).put("detourKm", detourKm),
        ) as JSONObject

    suspend fun confirmDetour(crewId: Long, detourId: Long): JSONObject =
        call("POST", "/api/crews/$crewId/detours/$detourId/confirm") as JSONObject

    // 실시간 알림 [운전자] (저장하지 않고 동승자에게 바로 전달)

    suspend fun liveLocation(tripId: Long, latitude: Double, longitude: Double, remainsByRider: JSONArray) {
        call(
            "POST", "/api/trips/$tripId/live/location",
            JSONObject().put("latitude", latitude).put("longitude", longitude).put("remainsByRider", remainsByRider),
        )
    }

    suspend fun liveApproaching(tripId: Long, crewMemberId: Long) {
        call("POST", "/api/trips/$tripId/live/riders/$crewMemberId/approaching")
    }

    suspend fun liveArrived(tripId: Long, crewMemberId: Long) {
        call("POST", "/api/trips/$tripId/live/riders/$crewMemberId/arrived")
    }

    suspend fun liveDriverDelay(tripId: Long, minutes: Int) {
        call("POST", "/api/trips/$tripId/live/delay", JSONObject().put("minutes", minutes))
    }

    // 실시간 구독 (SSE)

    /** 60초짜리 일회용 구독 티켓. 토큰이 URL·로그에 남지 않도록 구독 주소에는 티켓만 넣는다 */
    suspend fun liveTicket(): String = (call("POST", "/api/live/tickets") as JSONObject).getString("ticket")

    /**
     * SSE 를 구독해 이벤트마다 [onEvent](이벤트 이름, data) 를 부른다. 연결이 끊기면 돌아온다.
     * 서버는 25초마다 keepalive 주석을 보내므로 그보다 길게 아무것도 안 오면 끊긴 것으로 본다.
     */
    suspend fun streamEvents(ticket: String, onEvent: suspend (event: String, data: String) -> Unit) = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + "/api/live/events?ticket=" + ticket).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = SSE_READ_TIMEOUT_MS
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw ApiException(0, "NETWORK", e.message ?: e.javaClass.simpleName)
            }
            if (status != 200) {
                val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                throw ApiException(status, error?.optString("code") ?: "HTTP_$status", error?.optString("message") ?: text.take(200))
            }
            val parser = SseParser()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    parser.feed(line)?.let { (event, data) -> onEvent(event, data) }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // 운행 (모든 응답은 운행 전체 상태 TripResult)

    suspend fun startTrip(body: JSONObject): JSONObject = call("POST", "/api/trips", body) as JSONObject

    suspend fun trip(tripId: Long): JSONObject = call("GET", "/api/trips/$tripId") as JSONObject

    suspend fun board(tripId: Long, crewMemberId: Long, occurredAt: String, odometerKm: Double): JSONObject =
        call("POST", "/api/trips/$tripId/boardings/$crewMemberId/board", boardingEvent(occurredAt, odometerKm)) as JSONObject

    suspend fun alight(tripId: Long, crewMemberId: Long, occurredAt: String, odometerKm: Double): JSONObject =
        call("POST", "/api/trips/$tripId/boardings/$crewMemberId/alight", boardingEvent(occurredAt, odometerKm)) as JSONObject

    suspend fun absent(tripId: Long, crewMemberId: Long): JSONObject =
        call("DELETE", "/api/trips/$tripId/boardings/$crewMemberId") as JSONObject

    suspend fun uploadSegments(tripId: Long, segments: JSONArray): JSONObject =
        call("POST", "/api/trips/$tripId/segments", JSONObject().put("segments", segments)) as JSONObject

    suspend fun endTrip(tripId: Long, endedAt: String, endOdometerKm: Double, tollFareWon: Int): JSONObject =
        call(
            "POST", "/api/trips/$tripId/end",
            JSONObject().put("endedAt", endedAt).put("endOdometerKm", endOdometerKm).put("tollFareWon", tollFareWon),
        ) as JSONObject

    private fun boardingEvent(occurredAt: String, odometerKm: Double) =
        JSONObject().put("occurredAt", occurredAt).put("odometerKm", odometerKm)

    /** data 값(JSONObject·JSONArray·null)을 돌려준다 */
    private suspend fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): Any? =
        withContext(Dispatchers.IO) {
            val connection = try {
                (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    if (auth) token()?.let { setRequestProperty("Authorization", "Bearer $it") }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
            } catch (e: IOException) {
                throw ApiException(0, "NETWORK", e.message ?: e.javaClass.simpleName)
            }
            try {
                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw ApiException(0, "NETWORK", e.message ?: e.javaClass.simpleName)
                }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (status !in 200..299 || json?.optBoolean("success") != true) {
                    val error = json?.optJSONObject("error")
                    throw ApiException(
                        status,
                        error?.optString("code")?.takeIf { it.isNotEmpty() } ?: "HTTP_$status",
                        error?.optString("message").orEmpty().ifEmpty { text.take(200) },
                    )
                }
                json.opt("data").takeUnless { it == JSONObject.NULL }
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val SSE_READ_TIMEOUT_MS = 60_000
    }
}

/**
 * SSE 줄 단위 파서 (text/event-stream). 빈 줄에서 이벤트 하나를 끝낸다.
 * `:` 로 시작하는 줄은 주석(keepalive)이라 버린다. data 가 여러 줄이면 줄바꿈으로 잇는다.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()

    /** 이벤트가 끝났으면 (이벤트 이름, data) */
    fun feed(line: String): Pair<String, String>? {
        if (line.isEmpty()) {
            val done = if (data.isNotEmpty() || event != null) (event ?: "message") to data.toString() else null
            event = null
            data.clear()
            return done
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }
}
