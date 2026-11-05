package com.paircar.core.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pleos 앱 영역은 1개(portrait) 또는 2개(landscape) window 를 쓴다.
 * 사용자가 window bar/제스처로 크기를 바꾸므로 Activity 는 그대로 두고 가용 폭으로 레이아웃을 정한다.
 * - 넓을 때(landscape, 2 window): 좌우 2단
 * - 좁을 때(portrait, 1 window = 내비와 나란히): 1단
 */
val ExpandedWidthThreshold: Dp = 1000.dp
