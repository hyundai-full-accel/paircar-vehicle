package com.paircar.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.paircar.R
import com.paircar.core.ui.theme.DsColors

/**
 * 시작 화면 일러스트에서 차 부분만 잘라 카드 오른쪽 위에 흐리게 깐다 (오늘의 카풀·운행 종료 머리 영역).
 * 높이만 정하면 너비는 잘라낸 영역 비율로 정해진다.
 */
@Composable
fun CarHeaderArt(modifier: Modifier, background: Color = DsColors.Surface) {
    val bitmap = ImageBitmap.imageResource(R.drawable.startup_car)
    val painter = remember(bitmap) { BitmapPainter(bitmap, srcOffset = ArtOffset, srcSize = ArtSize) }
    // 상자 비율을 잘라낸 영역과 같게 두어 늘어나거나 잘리지 않게 한다
    Box(modifier.aspectRatio(ArtSize.width.toFloat() / ArtSize.height)) {
        Image(painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to background, 0.4f to Color.Transparent)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to background.copy(alpha = 0.6f), 0.3f to Color.Transparent, 0.9f to Color.Transparent, 1f to background)),
        )
    }
}

// startup_car.webp(1601x946) 에서 차가 있는 오른쪽 아래 영역 (오른쪽 끝 테두리선은 뺀다)
private val ArtOffset = IntOffset(680, 190)
private val ArtSize = IntSize(880, 540)
