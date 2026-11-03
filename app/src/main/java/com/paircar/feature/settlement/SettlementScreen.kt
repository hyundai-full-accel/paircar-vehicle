package com.paircar.feature.settlement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.components.Panel
import com.paircar.core.ui.components.SectionHeader
import com.paircar.core.ui.theme.DsColors
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 월별 정산. 정산 서버가 없어 이번 달 내역은 비어 있고, 정산 기준만 안내한다. */
@Composable
fun SettlementScreen(month: YearMonth = YearMonth.now()) {
    Panel(Modifier.fillMaxSize()) {
        Text("월별 정산", style = MaterialTheme.typography.titleLarge)
        SectionHeader(month.format(DateTimeFormatter.ofPattern("yyyy년 M월")))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("아직 정산할 운행이 없어요", style = MaterialTheme.typography.titleMedium)
                Text(
                    "운행을 마치면 이번 달 정산 내역이 여기에 쌓여요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DsColors.OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        SectionHeader("정산 기준")
        Text(
            "실제 운행 거리와 탑승 구간을 기준으로 정산하며, 우회거리는 합의한 정산 기준에 따라 반영돼요.",
            style = MaterialTheme.typography.bodyMedium,
            color = DsColors.OnSurfaceMuted,
        )
    }
}
