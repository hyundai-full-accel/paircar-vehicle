package com.paircar.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.paircar.R

/**
 * 확정 시안 톤의 라이트 테마. Pleos 시스템 UI 도 라이트 톤이다.
 * Pleos 디자인 가이드: 대비 4.5:1 이상, 핵심 정보는 큰 글자.
 */
object DsColors {
    val Background = Color(0xFFF2F3F5)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFEDEFF2)
    /** 탭 바·보조 버튼 배경 */
    val Segment = Color(0xFFE9EBEE)
    val Divider = Color(0xFFE3E6EA)
    /** 모달 뒤 어둡게 */
    val Scrim = Color(0x8C8A93A3)
    val OnSurface = Color(0xFF16181D)
    val OnSurfaceMuted = Color(0xFF5F6670)
    val Dark = Color(0xFF3A3E46)
    val Brand = Color(0xFF2F6BFF)
    val Info = Color(0xFF2F6BFF)
    val Positive = Color(0xFF15803D)
    val Warning = Color(0xFFB45309)
    val Danger = Color(0xFFDC2626)
    val Route = Color(0xFF2F6BFF)
}

private val colorScheme = lightColorScheme(
    primary = DsColors.Dark,
    onPrimary = Color.White,
    secondary = DsColors.Brand,
    background = DsColors.Background,
    onBackground = DsColors.OnSurface,
    surface = DsColors.Surface,
    onSurface = DsColors.OnSurface,
    surfaceVariant = DsColors.SurfaceHigh,
    onSurfaceVariant = DsColors.OnSurfaceMuted,
    outline = Color(0xFFD5D9DF),
    error = DsColors.Danger,
)

/** Pretendard v1.3.9 (SIL OFL 1.1, docs/licenses/Pretendard-OFL.txt) */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int = size + 8) =
    TextStyle(fontFamily = Pretendard, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)

private val typography = Typography(
    displayLarge = style(72, FontWeight.Bold, 80),
    displayMedium = style(56, FontWeight.Bold, 64),
    headlineLarge = style(40, FontWeight.Bold),
    headlineMedium = style(32, FontWeight.SemiBold),
    titleLarge = style(28, FontWeight.SemiBold),
    titleMedium = style(24, FontWeight.SemiBold),
    bodyLarge = style(24, FontWeight.Normal),
    bodyMedium = style(20, FontWeight.Normal),
    bodySmall = style(18, FontWeight.Normal),
    labelLarge = style(24, FontWeight.SemiBold),
    labelMedium = style(20, FontWeight.Medium),
    labelSmall = style(18, FontWeight.Medium),
)

@Composable
fun PairCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
