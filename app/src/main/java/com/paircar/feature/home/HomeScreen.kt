package com.paircar.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.DsCard
import com.paircar.core.ui.components.ExpandedWidthThreshold
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.StatusBadge
import com.paircar.core.ui.components.formatDistance
import com.paircar.core.ui.components.formatDuration
import com.paircar.core.ui.components.hhmm
import com.paircar.core.ui.components.label
import com.paircar.core.ui.map.CarpoolMap
import com.paircar.core.ui.map.MapInsets
import com.paircar.core.ui.map.toMapStops
import com.paircar.core.ui.theme.DsColors
import com.paircar.domain.model.CarpoolStatus
import com.paircar.domain.model.CarpoolTrip
import com.paircar.feature.carpool.CarpoolUiState

/** V-02 오늘의 카풀: 출발 시각, 정류장 타임라인, 예상 거리·시간, 동승자, 경로 미리보기 지도 */
@Composable
fun HomeScreen(
    state: CarpoolUiState,
    onStartCarpool: () -> Unit,
    onOpenDriving: () -> Unit,
    onOpenMatching: () -> Unit,
) {
    val trip = state.trip
    if (trip == null) {
        Text("오늘 카풀 일정이 없어요", style = MaterialTheme.typography.headlineMedium)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= ExpandedWidthThreshold) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TripCard(state, trip, onStartCarpool, onOpenDriving, Modifier.weight(1.2f).fillMaxSize())
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PassengerCard(trip, onOpenMatching, Modifier.fillMaxWidth())
                    RouteMapCard(state, trip, Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                TripCard(state, trip, onStartCarpool, onOpenDriving, Modifier.fillMaxWidth())
                RouteMapCard(state, trip, Modifier.fillMaxWidth().height(360.dp))
                PassengerCard(trip, onOpenMatching, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TripCard(
    state: CarpoolUiState,
    trip: CarpoolTrip,
    onStartCarpool: () -> Unit,
    onOpenDriving: () -> Unit,
    modifier: Modifier,
) {
    DsCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("오늘 ${trip.title} 카풀", style = MaterialTheme.typography.titleMedium, color = DsColors.OnSurfaceMuted)
            val (text, color) = trip.status.label()
            StatusBadge(text, color)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(trip.departureTime.hhmm(), style = MaterialTheme.typography.displayLarge)
            Text("출발 예정", style = MaterialTheme.typography.titleLarge, color = DsColors.OnSurfaceMuted,
                modifier = Modifier.padding(bottom = 12.dp))
        }
        Timeline(trip)
        Spacer(Modifier.weight(1f, fill = false))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            val preview = state.routePreview
            val suffix = if (preview?.isApproximate == true) " (추정)" else ""
            Metric("예상 거리", preview?.let { formatDistance(it.distanceMeters) + suffix } ?: "-")
            Metric("소요 시간", preview?.let { formatDuration(it.durationSeconds) + suffix } ?: "-")
            Spacer(Modifier.weight(1f))
            when (trip.status) {
                CarpoolStatus.SCHEDULED -> PrimaryActionButton("카풀 시작", onStartCarpool, Modifier.width(220.dp))
                CarpoolStatus.IN_PROGRESS -> PrimaryActionButton("주행 현황", onOpenDriving, Modifier.width(220.dp))
                else -> Unit
            }
        }
    }
}

@Composable
private fun Timeline(trip: CarpoolTrip) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TimelineRow(trip.departureTime.hhmm(), trip.origin.name, "출발지", DsColors.OnSurfaceMuted)
        trip.pickupsInOrder.forEachIndexed { index, passenger ->
            val (status, _) = passenger.boardingStatus.label()
            TimelineRow(
                passenger.estimatedPickupTime.hhmm(),
                passenger.pickup.name,
                "경유지 ${index + 1} · ${passenger.name} · $status",
                DsColors.Route,
            )
        }
        TimelineRow(trip.estimatedArrivalTime.hhmm(), trip.destination.name, "도착", DsColors.Dark)
    }
}

@Composable
private fun TimelineRow(time: String, place: String, sub: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(time, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(84.dp))
        Box(Modifier.size(14.dp).background(dot, CircleShape))
        Column {
            Text(place, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, maxLines = 1)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun PassengerCard(trip: CarpoolTrip, onOpenMatching: () -> Unit, modifier: Modifier) {
    DsCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("동승자 ${trip.acceptedPassengers.size}명", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (trip.requestedPassengers.isNotEmpty()) {
                TextButton(onClick = onOpenMatching) {
                    Text("새 요청 ${trip.requestedPassengers.size}건 ›", style = MaterialTheme.typography.labelMedium, color = DsColors.Brand)
                }
            }
        }
        trip.pickupsInOrder.forEach { passenger ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(passenger.name, style = MaterialTheme.typography.titleMedium)
                    Text("${passenger.estimatedPickupTime.hhmm()} · ${passenger.pickup.name}",
                        style = MaterialTheme.typography.bodyMedium, color = DsColors.OnSurfaceMuted, maxLines = 1)
                }
                val (text, color) = passenger.boardingStatus.label()
                StatusBadge(text, color)
            }
        }
    }
}

@Composable
private fun RouteMapCard(state: CarpoolUiState, trip: CarpoolTrip, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(DsColors.Surface)) {
        MapCaption("경로 미리보기")
        CarpoolMap(
            stops = trip.toMapStops(),
            route = state.routePreview?.path.orEmpty(),
            current = state.currentLocation,
            interactive = false,
            insets = MapInsets(80),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun ColumnScope.MapCaption(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
}
