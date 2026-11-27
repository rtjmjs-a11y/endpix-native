package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// [两色混合] 取色按钮 + 提示条, 风格对齐 Palette.ColorMenuBtn / MainScreen 的 picking 提示条

// 图标用色: 蓝 / 橙, 重叠区取两者 HSV 最短弧中点色
// #29ADFF = HSV(203°, 0.839, 1.000), #EF7D57 = HSV(15°, 0.636, 0.937)
// hue 走 203°→360°→15° 的短弧, 中点 289°; S/V 取平均 → HSVToColor ≈ #D641F7
private val MixBlue = Color(0xFF29ADFF)
private val MixOrange = Color(0xFFEF7D57)
private val MixOverlap = Color(0xFFD641F7)
private val MixIconEdge = Color(0xCC000000) // 黑色 80% 描边, 24dp 下也能分清两个圆

@Composable
fun TwoColorMixButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val k = size.minDimension / 24f
            val r = 6.6f * k
            val leftC = Offset(8.8f * k, 12f * k)
            val rightC = Offset(15.2f * k, 12f * k)
            val rightOval = Path().apply {
                addOval(
                    Rect(
                        rightC.x - r, rightC.y - r,
                        rightC.x + r, rightC.y + r
                    )
                )
            }
            // 先铺右橙圆, 再铺左蓝圆; 两圆交集用 HSV 中间色覆盖 → 三段纯色, 无渐变
            drawCircle(MixOrange, r, rightC)
            drawCircle(MixBlue, r, leftC)
            clipPath(rightOval) { drawCircle(MixOverlap, r, leftC) }
            // 两个圆的轮廓都描一遍, 交集边界保持清晰
            val edge = Stroke(1.15f * k)
            drawCircle(MixIconEdge, r, leftC, style = edge)
            drawCircle(MixIconEdge, r, rightC, style = edge)
        }
    }
}

@Composable
fun PickHintBanner(picking: Boolean, modifier: Modifier = Modifier) {
    if (!picking) return
    val stage = MixPickHub.stage.intValue
    if (stage >= 0) {
        MixPickBanner(stage, MixPickHub.colorA.intValue, modifier)
    } else {
        Box(
            modifier
                .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) { Text("取色器：点击画布取色", color = Ep.Text, fontSize = 11.sp) }
    }
}

@Composable
fun MixPickBanner(stage: Int, colorA: Int, modifier: Modifier = Modifier) {
    if (stage != 0 && stage != 1) return

    Row(
        modifier
            .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (stage == 1) {
            Box(
                Modifier.size(14.dp)
                    .background(Color(colorA), RoundedCornerShape(2.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(2.dp))
            )
            val hex = (colorA and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
            Text(
                "已吸 #$hex · 再吸第 2 色",
                color = Ep.Text,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                "两色混合：点击画布吸取第 1 色",
                color = Ep.Text,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
