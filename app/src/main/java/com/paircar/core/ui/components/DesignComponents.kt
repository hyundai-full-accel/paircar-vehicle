package com.paircar.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paircar.core.ui.theme.DsColors

// 확정 시안(내 크루·설정)의 공통 요소

/** 시안의 회색 채움 보조 버튼 (예: 차량 연결 해제, QR로 초대하기) */
@Composable
fun TonalActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = TouchTargetHeight),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DsColors.Segment, contentColor = DsColors.OnSurface),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 회색 배경의 작은 상태 칩 (탑승 예정, 확인됨, 월-금 …) */
@Composable
fun GrayChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = DsColors.OnSurfaceMuted,
        maxLines = 1,
        modifier = modifier
            .background(DsColors.SurfaceHigh, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** 카드 안 구획 제목 + 오른쪽 보조 동작 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            trailing()
        }
        HorizontalDivider(color = DsColors.Divider)
    }
}

/** 이름 첫 글자 원형 아바타 */
@Composable
fun InitialAvatar(name: String, size: Dp = 48.dp) {
    Box(
        Modifier.size(size).background(DsColors.Segment, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = MaterialTheme.typography.labelMedium, color = DsColors.OnSurfaceMuted)
    }
}

/**
 * 설정 목록 한 줄: [아이콘 자리] 이름 ……… 값/칩 ›
 * 시안의 회색 사각형은 아이콘 자리라 그대로 둔다.
 */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    chip: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTargetHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp).background(DsColors.Segment, RoundedCornerShape(8.dp)))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = DsColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            value?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            chip?.let { GrayChip(it) }
            if (onClick != null) ChevronIcon(Modifier.size(24.dp))
        }
        HorizontalDivider(color = DsColors.Divider)
    }
}

/** 큰 회색 타일 버튼 (크루 정보 수정 › …) */
@Composable
fun ActionTile(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DsColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .heightIn(min = 104.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = DsColors.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        ChevronIcon(Modifier.size(28.dp).align(Alignment.End), color = DsColors.OnSurface)
    }
}

/**
 * 화면 안쪽만 어둡게 덮는 모달. 탭 바는 그대로 보이고 조작은 막힌다.
 * Pleos 화면 위에 띄우는 팝업이 아니라 페어카 화면 안의 레이어다.
 */
@Composable
fun BoxScope.InScreenModal(
    onDismiss: (() -> Unit)?,
    maxWidth: Dp = 920.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = onDismiss != null) { onDismiss?.invoke() }
    Box(
        Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(24.dp))
                .background(DsColors.Surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

/** 모달 제목 줄 + 닫기 */
@Composable
fun ModalTitle(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(TouchTargetHeight).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            CloseIcon(Modifier.size(32.dp))
        }
    }
}

/** 화면 안쪽을 덮는 로딩 표시 */
@Composable
fun BoxScope.InScreenLoading(text: String) {
    InScreenModal(onDismiss = null, maxWidth = 380.dp) {
        CircularProgressIndicator(
            color = DsColors.OnSurfaceMuted,
            modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 둥근 흰 카드 (시안 패널) */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(DsColors.Surface)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** 스크롤되는 세로 배치 (좁은 창용) */
@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}
