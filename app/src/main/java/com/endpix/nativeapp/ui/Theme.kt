package com.endpix.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 扁平风格 token: 纯色、无渐变/阴影, 1–2px 分割线, 2–4px 圆角
object Ep {
    // 旧项目主题(GitHub 暗色 + 蓝色强调)
    val Bg = Color(0xFF0D1117)
    val Panel = Color(0xFF161B22)
    val Panel2 = Color(0xFF21262D)
    val Line = Color(0xFF30363D)
    val Text = Color(0xFFE6EDF3)
    val Sub = Color(0xFF8B949E)
    val Disabled = Color(0xFF4D4D4D)
    val Accent = Color(0xFF58A6FF)
    val HighlightSel = Color(0xFF858383)
    val Danger = Color(0xFFCC4040)
}

@Composable
fun EndpixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ep.Accent,
            onPrimary = Ep.Bg,
            background = Ep.Bg,
            onBackground = Ep.Text,
            surface = Ep.Panel,
            onSurface = Ep.Text,
            surfaceVariant = Ep.Panel2,
            outline = Ep.Line,
        ),
        content = content
    )
}

// 旧项目布局尺寸(顶栏/标签栏/左右栏/动画栏/底栏)
// 旧项目 525×1167 参考坐标 → dp(×0.781)
object EpLayout {
    val btn = 41.dp          // 52u
    val btnGap = 6.dp        // 8u
    val toolPanelW = 56.dp   // 72u
    val toolPanelH = 294.dp  // 376u
    val swatch = 44.dp       // 56u(含边框)
    val swatchStep = 47.dp   // 60u
    val infoW = 153.dp       // 196u
    val infoH = 25.dp        // 30u → 25dp: 三行文字行距 8dp, 第三行不顶出背景
    val infoX = 34.dp        // 44u
    // [pm929/pm977] 性能栏夹摄像头: 左右段垂直居中于圆心(0.66*挖孔高), 宽/边距按挖孔换算(本机 414px/81px)
    val perfW = 158.dp
    val perfX = 31.dp
    val perfTop = 11.dp
    val ringSize = 34.3.dp   // 挖孔高 90px
    val ringTop = 5.5.dp     // 圆心 59.4px - 半径 45px
    val tabY = 39.dp         // 50u
    val tabH = 20.dp         // 26u
    val animY = 67.dp        // 86u
    val animH = 78.dp        // 100u
    val frame = 69.dp        // 88u
    val bottomY1 = 34.dp     // 44u
    val bottomY2 = 100.dp    // 128u
    val edge = 3.dp          // 4u
    val edge2 = 50.dp        // 64u
    val layerPanelW = 122.dp // 156u
    val layerTop = 135.dp    // 173u
    val layerBottom = 147.dp // 188u
}
