package com.paircar.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.theme.DsColors

// 팝업 안 입력 양식 (정산 정보 설정·크루 정보 설정 등이 같이 쓴다)

/** 입력 칸 틀: 위에 작은 이름(없어도 됨), 아래에 값 */
@Composable
fun FieldBox(label: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, DsColors.Divider, RoundedCornerShape(16.dp))
            .background(DsColors.Surface)
            // 이름 없는 칸은 높이를 고정해, 글자 칸과 ▲▼ 버튼이 있는 칸이 나란히 있어도 높이가 같게 한다
            .then(if (label == null) Modifier.height(FieldHeight) else Modifier.heightIn(min = FieldHeight))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        label?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted) }
        content()
    }
}

/** 입력 칸 높이 (시안 기준) */
val FieldHeight = 72.dp

@Composable
fun FieldTextStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(color = DsColors.OnSurface)

/** 한 줄 글자 입력. [filter] 로 입력을 거른다(숫자만 등) */
@Composable
fun FieldInput(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = { onValue(filter(it)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = FieldTextStyle(),
        decorationBox = { field ->
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = FieldTextStyle().copy(color = DsColors.OnSurfaceMuted))
            }
            field()
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** 이름·설명 + 오른쪽 스위치 */
@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
        }
        DsSwitch(checked, onChange)
    }
}

@Composable
fun DsSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = DsColors.Surface,
            checkedTrackColor = DsColors.Dark,
            uncheckedThumbColor = DsColors.Surface,
            uncheckedTrackColor = DsColors.SurfaceHigh,
            uncheckedBorderColor = DsColors.Divider,
        ),
    )
}

/**
 * 고를 수 있는 칩 (멤버 필터·요일 등). 글자는 가운데 놓여서 [modifier] 로 폭·높이를 키워도 가운데에 있다.
 * [shape] 기본은 알약 모양, 요일처럼 칸을 채울 때는 둥근 사각형을 쓴다.
 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(if (selected) DsColors.Segment else DsColors.Background)
            .border(1.dp, if (selected) DsColors.OnSurfaceMuted.copy(alpha = 0.3f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) DsColors.OnSurface else DsColors.OnSurfaceMuted,
            maxLines = 1,
        )
    }
}

/** 구획 제목 + 작은 설명 */
@Composable
fun FormSectionTitle(title: String, detail: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted) }
    }
}

/** ⓘ 안내 한 줄 */
@Composable
fun FormNote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        InfoIcon(Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted)
    }
}
