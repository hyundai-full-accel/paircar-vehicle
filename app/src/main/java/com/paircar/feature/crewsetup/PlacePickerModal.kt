package com.paircar.feature.crewsetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.paircar.core.model.GeoPoint
import com.paircar.core.ui.components.FieldBox
import com.paircar.core.ui.components.FieldInput
import com.paircar.core.ui.components.FormNote
import com.paircar.core.ui.components.FormSectionTitle
import com.paircar.core.ui.components.InScreenModal
import com.paircar.core.ui.components.ModalTitle
import com.paircar.core.ui.components.PrimaryActionButton
import com.paircar.core.ui.components.TonalActionButton
import com.paircar.core.ui.map.PlacePickerMap
import com.paircar.domain.model.Place

/** 장소 이름 최대 길이 (서버 크루 장소 이름 50자) */
private const val MAX_PLACE_NAME = 30

/** 서울시청. 현재 위치도 앞서 고른 장소도 없을 때 지도를 여는 곳 */
private val DefaultCenter = GeoPoint(37.5665, 126.9780)

/**
 * 출발지·도착지 고르기: 장소 이름을 정하고 지도를 움직여 핀을 맞춘다.
 * 장소 검색 API 가 없어 좌표로 정한다. 크루 안에서 장소 이름은 하나뿐이라 같은 이름은 같은 장소로 쓴다.
 */
@Composable
internal fun BoxScope.PlacePickerModal(
    title: String,
    initial: Place?,
    defaultName: String,
    current: GeoPoint?,
    onPick: (Place) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: defaultName) }
    var center by remember { mutableStateOf(initial?.point ?: current ?: DefaultCenter) }
    var focus by remember { mutableStateOf(center) }

    InScreenModal(onDismiss = onClose, maxWidth = 960.dp) {
        ModalTitle(title, onClose)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FormSectionTitle("장소 이름", "크루원에게 보이는 이름이에요 (예: 집, 회사, ○○아파트 정문)")
            FieldBox(null) { FieldInput(name, { name = it.take(MAX_PLACE_NAME) }, placeholder = defaultName) }
        }
        PlacePickerMap(
            focus = focus,
            onCenterChanged = { center = it },
            modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(16.dp)),
        )
        FormNote("지도를 움직여 핀을 정확한 위치에 맞춰 주세요")
        Text("%.5f, %.5f".format(center.latitude, center.longitude), color = com.paircar.core.ui.theme.DsColors.OnSurfaceMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalActionButton("현재 위치로", { current?.let { focus = it } }, Modifier.weight(1f), enabled = current != null)
            PrimaryActionButton(
                "이 위치로 정하기",
                { onPick(Place(name.trim().ifEmpty { defaultName }, "", center)) },
                Modifier.weight(1f),
            )
        }
    }
}
