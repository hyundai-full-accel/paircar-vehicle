package com.paircar.data.mock

import com.paircar.domain.model.ConsentStatus
import com.paircar.domain.model.EfficiencySource
import com.paircar.domain.model.MemberRides
import com.paircar.domain.model.MonthlySettlement
import com.paircar.domain.model.PaymentStatus
import com.paircar.domain.model.SettlementPolicy
import com.paircar.domain.repository.SettlementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.YearMonth

/**
 * 정산 서버 확정 전 화면 개발용 데이터 (확정 시안의 2026년 10월 정산, 거리·횟수는 시안 값).
 * 전비·충전 단가는 운전자 입력값 예시다. 차량이 전비를 주면 SettlementViewModel 이 차량 값으로 바꾼다.
 */
object MockSettlementData {

    val october2026 = MonthlySettlement(
        month = YearMonth.of(2026, 10),
        crewName = MockCarpoolData.crew.name,
        receiverName = MockCarpoolData.driver.name,
        receiverAccount = null,
        carpoolDays = 18,
        sharedKm = 216.0,
        policy = SettlementPolicy(
            efficiencyKmPerKwh = 5.5,
            efficiencySource = EfficiencySource.MANUAL,
            chargePricePerKwh = 347,
        ),
        members = listOf(
            MemberRides(name = "차수아", rides = 18, sharedKm = 216.0, headcount = 2, detourKmPerRide = 3.0),
            // 10월에 함께 탔고 이미 입금한 동승자
            MemberRides(
                name = "이도윤",
                rides = 18,
                sharedKm = 216.0,
                headcount = 2,
                detourKmPerRide = 3.0,
                payment = PaymentStatus.PAID,
                consent = ConsentStatus.AGREED,
            ),
            MemberRides(name = "한민서", rides = 0, sharedKm = 0.0, headcount = 2, detourKmPerRide = 0.0),
        ),
    )
}

class MockSettlementRepository(
    settlements: List<MonthlySettlement> = listOf(MockSettlementData.october2026),
) : SettlementRepository {
    override val settlements: StateFlow<List<MonthlySettlement>> = MutableStateFlow(settlements).asStateFlow()
}
