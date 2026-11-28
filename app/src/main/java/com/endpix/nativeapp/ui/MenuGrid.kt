package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 子菜单统一方钮: 1:1, 上图标下文字
@Composable
fun SquareBtn(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dim: Boolean = false,
) {
    val src = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) Ep.HighlightSel else Ep.Panel2,
        androidx.compose.animation.core.tween(150), label = "sq-bg"
    )
    val line by androidx.compose.animation.animateColorAsState(
        if (selected) Ep.Accent else Ep.Line,
        androidx.compose.animation.core.tween(150), label = "sq-line"
    )
    Column(
        modifier.size(38.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = src, indication = null) { onClick() }
            .pressScale(src, pressedScale = 0.92f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val tint = if (selected) Color.Black else if (dim) Ep.Sub else Ep.Text
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (icon >= 50) SubIcon(icon, tint, size = 17.dp)
            else EpIcon(icon, tint, size = 17.dp, enabled = !dim)
        }
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            color = if (selected) Color.Black else if (dim) Ep.Sub else Ep.Text,
            fontSize = 8.sp, lineHeight = 8.sp, maxLines = 1, softWrap = false
        )
    }
}

data class MenuBtn(
    val icon: Int,
    val label: String,
    val id: Int,
    val selected: Boolean = false,
    val dim: Boolean = false,
)

// 按 4 个一行排布方钮
@Composable
fun SquareGrid(items: List<MenuBtn>, onClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { it2 ->
                    SquareBtn(it2.icon, it2.label, { onClick(it2.id) },
                        selected = it2.selected, dim = it2.dim)
                }
            }
        }
    }
}
