package com.paircar.data.server

import com.paircar.domain.model.Attendance
import com.paircar.domain.model.BoardingStatus
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.Crew
import com.paircar.domain.model.CrewInvite
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.repository.CarpoolRepository
import com.paircar.domain.repository.SettlementSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 앱(Mock) 카풀 저장소에 서버 동기화를 얹는다. 화면·유스케이스는 [CarpoolRepository] 만 알므로 그대로 둔다.
 * - 운행 시작(SCHEDULED → IN_PROGRESS), 탑승·하차·미탑승·불참, 운행 종료(완료·취소) → 서버 운행 기록
 * - QR 초대 → 서버가 발급한 초대 코드
 */
class ServerCarpoolRepository(
    private val local: CarpoolRepository,
    private val sync: ServerSync,
    scope: CoroutineScope,
    /** 운전자가 [크루 정보 설정]에서 바꾼 이름. 서버에 크루 이름 변경 API 가 없어 기기 값을 우선한다. */
    private val editedCrewName: () -> String? = { null },
) : CarpoolRepository by local {

    /** 크루 이름·시작일·명단은 서버, 반복 요일은 앱(서버에 없음) */
    override val crew: StateFlow<Crew?> =
        combine(local.crew, sync.crew) { localCrew, serverCrew -> mergeServerCrew(localCrew, serverCrew, editedCrewName()) }
            .stateIn(scope, SharingStarted.Eagerly, mergeServerCrew(local.crew.value, sync.crew.value, editedCrewName()))

    override suspend fun refresh() {
        local.refresh()
        sync.refreshCrew()
    }

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

/**
 * 앱 크루에 서버 크루를 덮는다. 서버 값을 아직 못 받았으면 앱 값 그대로.
 * 시작일은 서버에 따로 없어 가장 먼저 가입한 사람(= 크루를 만든 운전자)의 가입일로 본다.
 */
fun mergeServerCrew(local: Crew?, server: ServerCrew?, editedName: String?): Crew? {
    if (server == null) return local
    return Crew(
        id = server.crewId.toString(),
        name = editedName ?: server.name,
        startDate = server.members.minOfOrNull { it.joinedAt }?.toLocalDate() ?: local?.startDate ?: return local,
        days = local?.days ?: Crew.WEEKDAYS,
        members = server.members,
    )
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
