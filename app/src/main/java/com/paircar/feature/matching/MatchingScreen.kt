package com.paircar.feature.matching

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.DsCard
import com.paircar.core.ui.components.InfoRow
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.SecondaryActionButton
import com.paircar.core.ui.components.StatusBadge
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.components.label
import com.paircar.core.ui.theme.PairCarTheme
import com.paircar.data.mock.MockCarpoolData
import com.paircar.domain.model.MatchStatus
import com.paircar.domain.model.Passenger

/** 정차 중 전용 화면 (목록 탐색 + 요청 수락/거절) */
@Composable
fun MatchingScreen(
    state: MatchingUiState,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    val trip = state.trip ?: return
    val ordered = trip.passengers.sortedWith(
        compareBy<Passenger> { it.matchStatus != MatchStatus.REQUESTED }.thenBy { it.estimatedPickupTime },
    )
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            DsCard(Modifier.fillMaxWidth()) {
                Text("출퇴근 경로", style = MaterialTheme.typography.titleLarge)
                InfoRow("출발 ${trip.departureTime.hhmm()}", trip.origin.name)
                InfoRow("도착 ${trip.estimatedArrivalTime.hhmm()}", trip.destination.name)
            }
        }
        items(ordered, key = { it.id }) { passenger ->
            MatchingItem(
                passenger = passenger,
                busy = passenger.id in state.pendingIds,
                onAccept = { onAccept(passenger.id) },
                onDecline = { onDecline(passenger.id) },
            )
        }
    }
}

@Composable
private fun MatchingItem(passenger: Passenger, busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    DsCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(passenger.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${passenger.estimatedPickupTime.hhmm()} 픽업 · ${passenger.pickup.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val (text, color) = passenger.matchStatus.label()
            StatusBadge(text, color)
        }
        if (passenger.matchStatus == MatchStatus.REQUESTED) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryActionButton("수락", onAccept, Modifier.weight(1f), enabled = !busy)
                SecondaryActionButton("거절", onDecline, Modifier.weight(1f), enabled = !busy)
            }
        }
    }
}

@Preview(widthDp = 900, heightDp = 1200, backgroundColor = 0xFF0E1116, showBackground = true)
@Composable
private fun MatchingPreview() {
    PairCarTheme { MatchingScreen(MatchingUiState(MockCarpoolData.todayTrip()), {}, {}) }
}
