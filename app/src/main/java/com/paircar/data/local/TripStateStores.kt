package com.paircar.data.local

import android.content.Context
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.domain.model.TripSession
import com.paircar.domain.repository.TripSessionStore
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 오늘 운행의 진행 상태(운행 상태·탑승 상태). 서버가 생기면 서버가 이 값을 가진다. */
data class SavedTripState(
    val date: LocalDate,
    val status: CarpoolStatus,
    val boarding: Map<String, BoardingStatus>,
    val boardedAt: Map<String, LocalTime>,
) {
    fun applyTo(trip: CarpoolTrip): CarpoolTrip =
        if (trip.date != date) {
            trip
        } else {
            trip.copy(
                status = status,
                passengers = trip.passengers.map { p ->
                    p.copy(boardingStatus = boarding[p.id] ?: p.boardingStatus, boardedAt = boardedAt[p.id] ?: p.boardedAt)
                },
            )
        }

    companion object {
        fun of(trip: CarpoolTrip) = SavedTripState(
            date = trip.date,
            status = trip.status,
            boarding = trip.passengers.associate { it.id to it.boardingStatus },
            boardedAt = trip.passengers.mapNotNull { p -> p.boardedAt?.let { p.id to it } }.toMap(),
        )
    }
}

interface TripStateStore {
    fun load(): SavedTripState?
    fun save(state: SavedTripState)
}

/**
 * Pleos 는 창을 닫으면(3손가락 스와이프·닫기 버튼) 앱 프로세스를 끝낸다(logcat "Killing … remove task").
 * 운행 중에 창을 닫았다 다시 열어도 이어지도록 진행 상태와 기록 시작값을 기기에 둔다.
 */
class PrefsTripStores(context: Context) : TripStateStore, TripSessionStore {

    private val prefs = context.getSharedPreferences("active_trip", Context.MODE_PRIVATE)

    override fun load(): SavedTripState? = runCatching {
        val json = JSONObject(prefs.getString(KEY_STATE, null) ?: return null)
        val boarding = json.getJSONObject("boarding")
        val boardedAt = json.getJSONObject("boardedAt")
        SavedTripState(
            date = LocalDate.parse(json.getString("date")),
            status = CarpoolStatus.valueOf(json.getString("status")),
            boarding = boarding.keys().asSequence().associateWith { BoardingStatus.valueOf(boarding.getString(it)) },
            boardedAt = boardedAt.keys().asSequence().associateWith { LocalTime.parse(boardedAt.getString(it)) },
        )
    }.getOrNull()

    override fun save(state: SavedTripState) {
        val json = JSONObject()
            .put("date", state.date.toString())
            .put("status", state.status.name)
            .put("boarding", JSONObject(state.boarding.mapValues { it.value.name }))
            .put("boardedAt", JSONObject(state.boardedAt.mapValues { it.value.toString() }))
        prefs.edit().putString(KEY_STATE, json.toString()).apply()
    }

    override fun loadSession(): TripSession? {
        val startedAt = prefs.getString(KEY_STARTED_AT, null) ?: return null
        return TripSession(
            startedAt = LocalDateTime.parse(startedAt),
            startOdometerKm = prefs.getString(KEY_START_ODOMETER, null)?.toDoubleOrNull(),
        )
    }

    override fun saveSession(session: TripSession?) {
        prefs.edit().apply {
            if (session == null) {
                remove(KEY_STARTED_AT)
                remove(KEY_START_ODOMETER)
            } else {
                putString(KEY_STARTED_AT, session.startedAt.toString())
                putString(KEY_START_ODOMETER, session.startOdometerKm?.toString())
            }
        }.apply()
    }

    private companion object {
        const val KEY_STATE = "state"
        const val KEY_STARTED_AT = "session_started_at"
        const val KEY_START_ODOMETER = "session_start_odometer"
    }
}
