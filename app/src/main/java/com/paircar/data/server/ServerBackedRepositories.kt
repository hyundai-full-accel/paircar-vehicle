package com.paircar.data.server

import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.SettlementSettingsStore

/**
 * 앱(Mock) 카풀 저장소에 서버 동기화를 얹는다. 화면·유스케이스는 [CarpoolRepository] 만 알므로 그대로 둔다.
 * - 운행 시작(SCHEDULED → IN_PROGRESS), 탑승·하차·미탑승·불참, 운행 종료(완료·취소) → 서버 운행 기록
 * - QR 초대 → 서버가 발급한 초대 코드
 */
class ServerCarpoolRepository(
    private val local: CarpoolRepository,
    private val sync: ServerSync,
) : CarpoolRepository by local {

    override suspend fun updateTripStatus(status: CarpoolStatus) {
        val before = local.todayTrip.value?.status
        local.updateTripStatus(status)
        val trip = local.todayTrip.value ?: return
        when {
            before == CarpoolStatus.SCHEDULED && status == CarpoolStatus.IN_PROGRESS -> sync.onTripStarted(trip)
            before == CarpoolStatus.IN_PROGRESS && (status == CarpoolStatus.COMPLETED || status == CarpoolStatus.CANCELLED) ->
                sync.onTripEnded(trip)
        }
    }

    override suspend fun updateBoardingStatus(passengerId: String, status: BoardingStatus) {
        val before = local.todayTrip.value?.passengers?.firstOrNull { it.id == passengerId }?.boardingStatus
        local.updateBoardingStatus(passengerId, status)
        val trip = local.todayTrip.value ?: return
        if (before != status && trip.status == CarpoolStatus.IN_PROGRESS) sync.onBoardingChanged(trip, passengerId, status)
    }

    override suspend fun updateAttendance(passengerId: String, attendance: Attendance) {
        val before = local.todayTrip.value?.passengers?.firstOrNull { it.id == passengerId }?.attendance
        local.updateAttendance(passengerId, attendance)
        val trip = local.todayTrip.value ?: return
        if (before != attendance && trip.status == CarpoolStatus.IN_PROGRESS) sync.onAttendanceChanged(trip, passengerId, attendance)
    }

    override suspend fun createInvite(): CrewInvite = sync.createInvite()
}

/** 정산 설정은 기기에 두고, 계좌·충전 단가가 바뀌면 서버에도 올린다 (운행 시작에 운전자 계좌가 필요하다) */
class ServerSettlementSettingsStore(
    private val local: SettlementSettingsStore,
    private val onChanged: () -> Unit,
) : SettlementSettingsStore by local {

    override suspend fun saveSettings(settings: SettlementSettings) {
        local.saveSettings(settings)
        onChanged()
    }
}
