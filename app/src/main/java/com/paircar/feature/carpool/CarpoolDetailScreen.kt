package com.paircar.feature.carpool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.AdaptivePanes
import com.paircar.core.ui.components.DsCard
import com.paircar.core.ui.components.InfoRow
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.SecondaryActionButton
import com.paircar.core.ui.components.StatusBadge
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.components.label
import com.paircar.core.ui.theme.PairCarTheme
import com.paircar.core.ui.theme.DsColors
import com.paircar.data.mock.MockCarpoolData
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.Passenger
import com.paircar.domain.usecase.buildCarpoolRoutePlan

@Composable
fun CarpoolDetailScreen(
    state: CarpoolUiState,
    onSetRoute: () -> Unit,
    onOpenNavigation: () -> Unit,
    onShowRouteOverview: () -> Unit,
    onConfirmBoarding: (String) -> Unit,
    onMarkNoShow: (String) -> Unit,
) {
    val trip = state.trip ?: return
    val plan = buildCarpoolRoutePlan(trip)

    AdaptivePanes(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        primary = {
            DsCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${trip.title} 카풀", style = MaterialTheme.typography.headlineMedium)
                    val (text, color) = trip.status.label()
                    StatusBadge(text, color)
                }
                InfoRow("운전자", trip.driver.name)
                InfoRow("차량", "${trip.driver.carModel} · ${trip.driver.plateNumber}")
            }
            DsCard(Modifier.fillMaxWidth()) {
                Text("경로", style = MaterialTheme.typography.titleLarge)
                InfoRow("출발 ${trip.departureTime.hhmm()}", trip.origin.name)
                trip.acceptedPassengers.sortedBy { it.estimatedPickupTime }.forEach {
                    InfoRow("경유 ${it.estimatedPickupTime.hhmm()}", "${it.pickup.name} (${it.name})")
                }
                InfoRow("도착 ${trip.estimatedArrivalTime.hhmm()}", trip.destination.name)
                if (plan.deferredPickups.isNotEmpty()) {
                    Text(
                        "내비 경유지는 최대 2곳입니다. ${plan.deferredPickups.joinToString { it.name }} 님은 앞선 픽업 후 경로를 다시 설정합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DsColors.Warning,
                    )
                }
            }
            PrimaryActionButton(
                if (trip.status == CarpoolStatus.IN_PROGRESS) "내비 경로 다시 설정" else "내비 경로 설정 (경유지 포함)",
                onSetRoute,
                Modifier.fillMaxWidth(),
                enabled = trip.status == CarpoolStatus.SCHEDULED || trip.status == CarpoolStatus.IN_PROGRESS,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryActionButton("내비 화면", onOpenNavigation, Modifier.weight(1f))
                SecondaryActionButton("경로 개요", onShowRouteOverview, Modifier.weight(1f))
            }
        },
        secondary = {
            DsCard(Modifier.fillMaxWidth()) {
                Text("동승자", style = MaterialTheme.typography.titleLarge)
                trip.acceptedPassengers.sortedBy { it.estimatedPickupTime }.forEachIndexed { index, passenger ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    PassengerDetail(
                        passenger = passenger,
                        showBoardingActions = trip.status == CarpoolStatus.IN_PROGRESS && passenger.isPendingPickup,
                        onConfirmBoarding = { onConfirmBoarding(passenger.id) },
                        onMarkNoShow = { onMarkNoShow(passenger.id) },
                    )
                }
            }
        },
    )
}

@Composable
private fun PassengerDetail(
    passenger: Passenger,
    showBoardingActions: Boolean,
    onConfirmBoarding: () -> Unit,
    onMarkNoShow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(passenger.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val (match, matchColor) = passenger.matchStatus.label()
            StatusBadge(match, matchColor)
            val (boarding, boardingColor) = passenger.boardingStatus.label()
            StatusBadge(boarding, boardingColor)
        }
        Text(
            "${passenger.estimatedPickupTime.hhmm()} 픽업 · ${passenger.pickup.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showBoardingActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryActionButton("탑승 확인", onConfirmBoarding, Modifier.weight(1f))
                SecondaryActionButton("미탑승", onMarkNoShow, Modifier.weight(1f))
            }
        }
    }
}

@Preview(widthDp = 1400, heightDp = 1200, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun CarpoolDetailPreview() {
    PairCarTheme {
        CarpoolDetailScreen(CarpoolUiState(trip = MockCarpoolData.todayTrip()), {}, {}, {}, {}, {})
    }
}
