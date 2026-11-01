package com.paircar.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 와이어프레임(Lo-Fi 1차) 톤의 라이트 테마. Pleos 시스템 UI 도 라이트 톤이다.
 * Pleos 디자인 가이드: 대비 4.5:1 이상, 핵심 정보는 큰 글자.
 */
object DsColors {
    val Background = Color(0xFFF2F3F5)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFEDEFF2)
    val OnSurface = Color(0xFF16181D)
    val OnSurfaceMuted = Color(0xFF5F6670)
    val Dark = Color(0xFF1F2329)
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

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Bold, lineHeight = 80.sp),
    displayMedium = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp),
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    headlineMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    titleMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun PairCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
