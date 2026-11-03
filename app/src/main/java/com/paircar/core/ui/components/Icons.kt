package com.paircar.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.paircar.core.ui.theme.DsColors

// 시안의 아이콘. 아이콘 라이브러리 의존성 없이 Canvas 로 그린다.

/** 앱 로고: 원 안의 체크 */
@Composable
fun CheckBadgeIcon(modifier: Modifier, color: Color = DsColors.OnSurface) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(color, r)
        val path = Path().apply {
            moveTo(center.x - r * 0.42f, center.y + r * 0.02f)
            lineTo(center.x - r * 0.1f, center.y + r * 0.34f)
            lineTo(center.x + r * 0.45f, center.y - r * 0.3f)
        }
        drawPath(path, Color.White, style = Stroke(r * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 톱니바퀴. [hole] 은 가운데 구멍 색(= 버튼 배경색) */
@Composable
fun GearIcon(modifier: Modifier, color: Color = DsColors.OnSurface, hole: Color = DsColors.Surface) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val tooth = Size(r * 0.42f, r * 0.5f)
        repeat(8) { i ->
            rotate(i * 45f) {
                drawRoundRect(
                    color,
                    topLeft = Offset(center.x - tooth.width / 2, center.y - r),
                    size = tooth,
                    cornerRadius = CornerRadius(r * 0.08f),
                )
            }
        }
        drawCircle(color, r * 0.74f)
        drawCircle(hole, r * 0.3f)
    }
}

/** ‹ 뒤로 */
@Composable
fun BackIcon(modifier: Modifier, color: Color = DsColors.OnSurface) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.2f)
            lineTo(size.width * 0.32f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.8f)
        }
        drawPath(path, color, style = Stroke(size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** › 이동 */
@Composable
fun ChevronIcon(modifier: Modifier, color: Color = DsColors.OnSurfaceMuted) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.38f, size.height * 0.2f)
            lineTo(size.width * 0.68f, size.height * 0.5f)
            lineTo(size.width * 0.38f, size.height * 0.8f)
        }
        drawPath(path, color, style = Stroke(size.minDimension * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** ✕ 닫기 */
@Composable
fun CloseIcon(modifier: Modifier, color: Color = DsColors.OnSurface) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.08f
        val a = size.minDimension * 0.2f
        val b = size.minDimension * 0.8f
        drawLine(color, Offset(a, a), Offset(b, b), stroke, StrokeCap.Round)
        drawLine(color, Offset(b, a), Offset(a, b), stroke, StrokeCap.Round)
    }
}

/** + 추가 */
@Composable
fun PlusIcon(modifier: Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.1f
        val a = size.minDimension * 0.22f
        val b = size.minDimension * 0.78f
        drawLine(color, Offset(center.x, a), Offset(center.x, b), stroke, StrokeCap.Round)
        drawLine(color, Offset(a, center.y), Offset(b, center.y), stroke, StrokeCap.Round)
    }
}

/** ✓ 테두리 없는 체크 */
@Composable
fun CheckIcon(modifier: Modifier, color: Color = DsColors.OnSurface) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.74f)
            lineTo(size.width * 0.84f, size.height * 0.3f)
        }
        drawPath(path, color, style = Stroke(size.minDimension * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** ⓘ 안내 */
@Composable
fun InfoIcon(modifier: Modifier, color: Color = DsColors.OnSurfaceMuted) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val stroke = r * 0.14f
        drawCircle(color, r - stroke / 2, style = Stroke(stroke))
        drawCircle(color, r * 0.1f, Offset(center.x, center.y - r * 0.42f))
        drawLine(color, Offset(center.x, center.y - r * 0.12f), Offset(center.x, center.y + r * 0.48f), stroke, StrokeCap.Round)
    }
}
