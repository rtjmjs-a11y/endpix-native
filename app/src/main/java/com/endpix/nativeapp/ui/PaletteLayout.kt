package com.endpix.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 工具栏/右侧色板的几何计算。抽到 MainScreen 外面, 避免主 composable 触碰 64K 方法上限。
 */
object ToolPaletteLayout {
    // 保留实际色数: 7–18 用半格, 19–60 用滚动小格; 只有 <6 才补到 6
    fun paletteCount(raw: Int): Int = raw.coerceIn(6, 60)

    fun toolBlockDp(): Dp = EpLayout.btn * 6 + EpLayout.btnGap * 5

    fun shiftDp(pct: Int, density: Density, screenHpx: Float): Dp {
        val blockPx = with(density) { toolBlockDp().toPx() }
        val travel = (screenHpx - blockPx).coerceAtLeast(1f)
        val px = travel * (pct.coerceIn(-50, 50) / 100f)
        return with(density) { px.toDp() }
    }

    fun paletteMaxH(density: Density, screenHpx: Float): Dp =
        with(density) { (screenHpx * 0.62f).toDp() }

    fun cellH(count: Int): Dp = when {
        count <= 6 -> 43.5.dp          // 与工具栏 6 键块等高(276dp)
        count <= 18 -> EpLayout.swatch * 0.5f
        else -> 20.dp                  // 32 色: 小格, 滚动
    }

    fun gap(count: Int): Dp = if (count > 18) 2.dp else 3.dp

    fun step(count: Int): Dp = cellH(count) + gap(count)

    // 点色块后的 [取色器][HSV] 菜单: 6/18 色跟选中行; 32 色滚动盘固定居中
    fun colorMenuY(count: Int, selIdx: Int): Dp {
        if (count > 18 || selIdx < 0) return 0.dp
        return step(count) * (selIdx - (count - 1) / 2f)
    }

    // 6 色盘跟工具栏同一偏移; 18/32 保持居中
    fun paletteOffset(count: Int, toolYDp: Dp): Dp = if (count == 6) toolYDp else 0.dp
}

@Composable
fun rememberToolShiftDp(pct: Int): Dp {
    val density = LocalDensity.current
    val screenHpx = LocalContext.current.resources.displayMetrics.heightPixels.toFloat()
    return remember(pct, density, screenHpx) {
        ToolPaletteLayout.shiftDp(pct, density, screenHpx)
    }
}

@Composable
fun rememberPaletteMaxH(): Dp {
    val density = LocalDensity.current
    val screenHpx = LocalContext.current.resources.displayMetrics.heightPixels.toFloat()
    return remember(density, screenHpx) {
        ToolPaletteLayout.paletteMaxH(density, screenHpx)
    }
}
