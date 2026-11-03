package com.paircar.core.common

import android.content.Context
import java.time.LocalDateTime

/** 마지막으로 차량 연결 확인을 마친 시각. 앱을 다시 켜도 시작 화면에 보여준다. */
interface VehicleCheckStore {
    fun lastCheckedAt(): LocalDateTime?
    fun saveCheckedAt(time: LocalDateTime)
}

class PrefsVehicleCheckStore(context: Context) : VehicleCheckStore {
    private val prefs = context.getSharedPreferences("vehicle_check", Context.MODE_PRIVATE)

    override fun lastCheckedAt(): LocalDateTime? =
        prefs.getString(KEY_LAST_CHECKED, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    override fun saveCheckedAt(time: LocalDateTime) {
        prefs.edit().putString(KEY_LAST_CHECKED, time.toString()).apply()
    }

    private companion object {
        const val KEY_LAST_CHECKED = "last_checked_at"
    }
}
