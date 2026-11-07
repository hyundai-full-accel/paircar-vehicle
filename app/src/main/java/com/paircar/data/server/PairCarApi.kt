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
    }
}
