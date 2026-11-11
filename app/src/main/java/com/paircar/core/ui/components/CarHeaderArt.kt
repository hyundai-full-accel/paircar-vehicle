package com.paircar.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.paircar.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 시안의 카드 머리 배경 그림 (왼쪽이 흰색으로 빠지고 오른쪽에 그림).
 * 영역을 꽉 채우도록 잘라(Crop) 그 뒤에 그린다. 크기 측정에는 끼어들지 않아 머리 영역 높이는 내용대로 정해진다.
 */
@Composable
fun Modifier.headerBackground(@DrawableRes image: Int, alignment: Alignment = Alignment.CenterEnd): Modifier {
    val bitmap = ImageBitmap.imageResource(image)
    return drawBehind {
        val scale = max(size.width / bitmap.width, size.height / bitmap.height)
        val drawn = IntSize((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
        val offset = alignment.align(drawn, IntSize(size.width.roundToInt(), size.height.roundToInt()), layoutDirection)
        clipRect {
            drawImage(bitmap, dstOffset = offset, dstSize = drawn)
        }
    }
}

/**
 * 차 그림 배경 (오늘의 카풀 머리·주행 패널 다음 픽업 카드·운행 종료 카드). 깔 영역 크기를 modifier 로 정한다.
 * 그림 아래쪽 절반에 차가 있어, 위아래가 잘릴 때 차가 남도록 조금 아래 기준으로 자른다.
 */
@Composable
fun CarHeaderArt(modifier: Modifier) {
    Box(modifier.headerBackground(R.drawable.bg_header_car, CarAlignment))
}

private val CarAlignment = BiasAlignment(horizontalBias = 1f, verticalBias = 0.45f)

/**
 * 오늘의 카풀 카드 머리 배경 (시안): 그림을 카드 너비에 꽉 맞추고(왼쪽은 흰색으로 빠짐) 머리 영역 높이로 위아래를 자른다.
 * 카드가 넓고 머리가 낮을수록 많이 잘리므로, 차가 머리 영역 높이의 [CarFocusY] 지점에 오도록 잘라 차가 늘 보이게 한다.
 */
@Composable
fun CarHeaderBackdrop(modifier: Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.bg_header_car)
    Box(
        modifier.drawBehind {
            val scale = max(size.width / bitmap.width, size.height / bitmap.height)
            val drawnW = bitmap.width * scale
            val drawnH = bitmap.height * scale
            val y = (size.height * CarFocusY - drawnH * CarCenterInImage).coerceIn(size.height - drawnH, 0f)
            clipRect {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset((size.width - drawnW).roundToInt(), y.roundToInt()),
                    dstSize = IntSize(drawnW.roundToInt(), drawnH.roundToInt()),
                )
            }
        },
    )
}

/** bg_header_car 에서 차의 세로 중심 (그림 높이 비율) */
private const val CarCenterInImage = 0.51f

/** 머리 영역에서 차 중심을 둘 높이 (시안: 머리 영역의 약 60~70% 지점) */
private const val CarFocusY = 0.62f
