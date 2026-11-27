package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// 程序化线性图标(扁平, 1.8px 描边), 按 24×24 视口绘制
@Composable
fun EpIcon(kind: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, enabled: Boolean = true) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val c = if (enabled) tint else tint.copy(alpha = 0.32f)
        val st = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(c, o(x1, y1), o(x2, y2), strokeWidth = 1.8f * k, cap = StrokeCap.Round)
        fun path(build: Path.() -> Unit, close: Boolean = false, fill: Boolean = false) {
            val p = Path()
            p.build()
            if (close) p.close()
            if (fill) drawPath(p, c) else drawPath(p, c, style = st)
        }
        // 设置(齿轮): 编号10显式使用, else 兜底复用
        fun gear() {
            drawCircle(c, 3.2f * k, o(12f, 12f), style = Stroke(1.8f * k))
            drawCircle(c, 1.1f * k, o(12f, 12f))
            for (i in 0 until 8) {
                val a = i * Math.PI.toFloat() / 4f
                val r0 = 6.2f
                val r1 = 8.6f
                line(12f + cos(a) * r0, 12f + sin(a) * r0, 12f + cos(a) * r1, 12f + sin(a) * r1)
            }
        }
        // 眼睛轮廓: 编号22(睁开)/23(闭眼加斜线)共用
        fun eye() {
            path({
                moveTo(3f * k, 12f * k)
                cubicTo(6f * k, 7.2f * k, 9.4f * k, 5.2f * k, 12f * k, 5.2f * k)
                cubicTo(14.6f * k, 5.2f * k, 18f * k, 7.2f * k, 21f * k, 12f * k)
                cubicTo(18f * k, 16.8f * k, 14.6f * k, 18.8f * k, 12f * k, 18.8f * k)
                cubicTo(9.4f * k, 18.8f * k, 6f * k, 16.8f * k, 3f * k, 12f * k)
            })
        }
        when (kind) {
            0 -> { // 画笔(铅笔)
                path({ moveTo(5f * k, 19f * k); lineTo(7f * k, 13.5f * k); lineTo(15.5f * k, 5f * k); lineTo(19f * k, 8.5f * k); lineTo(10.5f * k, 17f * k); close() })
                line(7f, 13.5f, 10.5f, 17f)
            }
            1 -> { // 橡皮
                path({ moveTo(4.5f * k, 16f * k); lineTo(12f * k, 8.5f * k); lineTo(19.5f * k, 16f * k); lineTo(12f * k, 23.5f * k); close() })
                line(4.5f, 20.5f, 19.5f, 20.5f)
            }
            2 -> { // 油漆桶
                path({ moveTo(4.5f * k, 10f * k); lineTo(12f * k, 17.5f * k); lineTo(19f * k, 10.5f * k); lineTo(11.5f * k, 3f * k); close() })
                drawArc(c, -70f, 140f, false, topLeft = o(12.5f, 12.5f), size = Size(7f * k, 7f * k), style = st)
                drawCircle(c, 1.5f * k, o(19.5f, 18.5f), style = Stroke(1.6f * k))
            }
            3 -> { // 魔棒
                line(4.5f, 19.5f, 15.5f, 8.5f)
                line(18f, 4.5f, 18f, 8.5f)
                line(16f, 6.5f, 20f, 6.5f)
                line(20f, 11.5f, 20f, 14f)
                line(18.75f, 12.75f, 21.25f, 12.75f)
            }
            4 -> { // 套索
                drawArc(c, 200f, 220f, false, topLeft = o(4f, 4f), size = Size(16f * k, 11f * k), style = st)
                line(8.5f, 14.5f, 7.5f, 21f)
            }
            5 -> { // 矩形框选(虚线)
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.4f * k, 2.2f * k), 0f)
                drawRect(c, topLeft = o(4f, 5f), size = Size(16f * k, 14f * k),
                    style = Stroke(width = 1.6f * k, pathEffect = dash))
                for (pt in listOf(4f to 5f, 20f to 5f, 4f to 19f, 20f to 19f))
                    drawRect(c, topLeft = o(pt.first - 0.8f, pt.second - 0.8f), size = Size(1.6f * k, 1.6f * k))
            }
            6 -> { // 移动(四向)
                line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f)
                line(12f, 4f, 9.5f, 6.5f); line(12f, 4f, 14.5f, 6.5f)
                line(12f, 20f, 9.5f, 17.5f); line(12f, 20f, 14.5f, 17.5f)
                line(4f, 12f, 6.5f, 9.5f); line(4f, 12f, 6.5f, 14.5f)
                line(20f, 12f, 17.5f, 9.5f); line(20f, 12f, 17.5f, 14.5f)
            }
            7 -> { // 撤销
                path({ moveTo(19f * k, 17f * k); cubicTo(19f * k, 9f * k, 10f * k, 8f * k, 6.5f * k, 11.5f * k) })
                line(10f, 8f, 6.5f, 11.5f); line(6.5f, 11.5f, 10f, 15f)
            }
            8 -> { // 重做
                path({ moveTo(5f * k, 17f * k); cubicTo(5f * k, 9f * k, 14f * k, 8f * k, 17.5f * k, 11.5f * k) })
                line(14f, 8f, 17.5f, 11.5f); line(17.5f, 11.5f, 14f, 15f)
            }
            9 -> { // 图层
                path({ moveTo(12f * k, 4f * k); lineTo(20f * k, 8.5f * k); lineTo(12f * k, 13f * k); lineTo(4f * k, 8.5f * k); close() })
                path({ moveTo(4f * k, 12.5f * k); lineTo(12f * k, 17f * k); lineTo(20f * k, 12.5f * k) })
                path({ moveTo(4f * k, 16.5f * k); lineTo(12f * k, 21f * k); lineTo(20f * k, 16.5f * k) })
            }
            10 -> gear() // 设置(齿轮): 设置菜单/底栏设置按钮, 显式实现
            11 -> { // 动画(播放)
                path({ moveTo(8f * k, 5f * k); lineTo(19f * k, 12f * k); lineTo(8f * k, 19f * k); close() }, close = false, fill = false)
            }
            12 -> { // 抽屉(菜单)
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 15f, 17f)
            }
            13 -> { // 图形(方框)
                drawRect(c, topLeft = o(4f, 4f), size = Size(16f * k, 16f * k), style = st)
            }
            14 -> { // 更多(三点)
                for (x in listOf(6f, 12f, 18f)) drawCircle(c, 1.4f * k, o(x, 12f))
            }
            15 -> { // 快捷键(键盘)
                drawRect(c, topLeft = o(3f, 6f), size = Size(18f * k, 12f * k), style = st)
                for (x in listOf(7f, 12f, 17f)) drawCircle(c, 0.9f * k, o(x, 13f))
            }
            16 -> { // 抽屉(面板+上箭头)
                drawRect(c, topLeft = o(4f, 5f), size = Size(16f * k, 14f * k), style = st)
                line(12f, 9f, 12f, 15f); line(12f, 9f, 9.5f, 11.5f); line(12f, 9f, 14.5f, 11.5f)
            }
            17 -> { // 放大(+)
                line(12f, 6f, 12f, 18f); line(6f, 12f, 18f, 12f)
            }
            18 -> { // 缩小(−)
                line(6f, 12f, 18f, 12f)
            }
            19 -> { // 时间轴
                line(3f, 7f, 21f, 7f); line(3f, 12f, 21f, 12f); line(3f, 17f, 21f, 17f)
                line(12f, 4f, 12f, 20f)
            }
            20 -> { // 取色器(滴管)
                line(5f, 19f, 15.5f, 8.5f)
                line(8.5f, 15.5f, 12f, 19f)
                drawCircle(c, 2.6f * k, o(17.5f, 6.5f), style = Stroke(1.6f * k))
                line(4f, 20f, 6.5f, 17.5f)
            }
            30 -> { // 框选画布(全选): 虚线方框+四角
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.4f * k, 1.8f * k), 0f)
                drawRect(c, topLeft = o(4.5f, 5.5f), size = Size(15f * k, 13f * k),
                    style = Stroke(1.6f * k, pathEffect = dash))
                line(3f, 8f, 3f, 4f); line(3f, 4f, 7f, 4f)
                line(21f, 16f, 21f, 20f); line(21f, 20f, 17f, 20f)
            }
            31 -> { // 汇入图像: 方框+向下箭头
                line(5f, 15.5f, 5f, 19.5f); line(5f, 19.5f, 19f, 19.5f); line(19f, 19.5f, 19f, 15.5f)
                line(12f, 4.5f, 12f, 14f); line(8.5f, 10.5f, 12f, 14f); line(15.5f, 10.5f, 12f, 14f)
            }
            32 -> { // 上次裁剪: 剪刀+虚线
                drawCircle(c, 2.4f * k, o(7f, 17f), style = st)
                drawCircle(c, 2.4f * k, o(17f, 17f), style = st)
                line(8.6f, 15.2f, 18f, 4.5f); line(15.4f, 15.2f, 6f, 4.5f)
            }
            33 -> { // 复原变换: 回转箭头
                drawArc(c, 40f, 280f, false, topLeft = o(5f, 5f), size = Size(14f * k, 14f * k), style = st)
                path({ moveTo(18.5f * k, 2.5f * k); lineTo(21.5f * k, 6.5f * k); lineTo(16.5f * k, 7.5f * k) })
            }
            34 -> { // 复制: 两个叠放方框
                drawRect(c, topLeft = o(4f, 4f), size = Size(11f * k, 13f * k), style = st)
                drawRect(c, topLeft = o(9f, 8f), size = Size(11f * k, 13f * k), style = st)
            }
            35 -> { // 移除: 垃圾桶
                line(5f, 7f, 19f, 7f); line(9.5f, 4.5f, 14.5f, 4.5f)
                path({ moveTo(7f * k, 7f * k); lineTo(8f * k, 20f * k); lineTo(16f * k, 20f * k); lineTo(17f * k, 7f * k) })
                line(10.5f, 10f, 10.5f, 17f); line(13.5f, 10f, 13.5f, 17f)
            }
            36 -> { // 取消: X
                line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f)
            }
            37 -> { // 拉伸: 四角箭头
                line(4f, 9f, 4f, 4f); line(4f, 4f, 9f, 4f)
                line(20f, 15f, 20f, 20f); line(20f, 20f, 15f, 20f)
                line(4f, 4f, 20f, 20f)
            }
            38 -> { // 横翻
                line(12f, 4f, 12f, 20f)
                path({ moveTo(9f * k, 7f * k); lineTo(4f * k, 12f * k); lineTo(9f * k, 17f * k); close() })
                path({ moveTo(15f * k, 7f * k); lineTo(20f * k, 12f * k); lineTo(15f * k, 17f * k); close() })
            }
            39 -> { // 竖翻
                line(4f, 12f, 20f, 12f)
                path({ moveTo(7f * k, 9f * k); lineTo(12f * k, 4f * k); lineTo(17f * k, 9f * k); close() })
                path({ moveTo(7f * k, 15f * k); lineTo(12f * k, 20f * k); lineTo(17f * k, 15f * k); close() })
            }
            40 -> { // 确认: 勾
                line(5f, 12.5f, 10f, 18f); line(10f, 18f, 19.5f, 6f)
            }
            41 -> { // 保存(软盘)
                drawRect(c, topLeft = o(4f, 4f), size = Size(16f * k, 16f * k), style = st)
                drawRect(c, topLeft = o(8f, 4f), size = Size(8f * k, 6f * k), style = Stroke(1.4f * k))
                drawRect(c, topLeft = o(7f, 13f), size = Size(10f * k, 7f * k), style = Stroke(1.4f * k))
            }
            42 -> { // 导出(托盘+上箭头)
                line(5f, 15f, 5f, 19.5f); line(5f, 19.5f, 19f, 19.5f); line(19f, 19.5f, 19f, 15f)
                line(12f, 4.5f, 12f, 14.5f); line(8.5f, 8f, 12f, 4.5f); line(15.5f, 8f, 12f, 4.5f)
            }
            43 -> { // 调整大小(角箭头)
                line(4f, 9f, 4f, 4f); line(4f, 4f, 9f, 4f)
                line(20f, 15f, 20f, 20f); line(20f, 20f, 15f, 20f)
                line(4f, 4f, 10f, 10f); line(20f, 20f, 14f, 14f)
            }
            45 -> { // 色阶(直方图)
                line(4f, 20f, 20f, 20f)
                val bars = floatArrayOf(5f, 9f, 6f, 13f, 10f, 15f, 8f)
                for (i in bars.indices) {
                    val h = bars[i]
                    drawRect(c, topLeft = o(5f + i * 2.2f, 20f - h), size = Size(1.6f * k, h * k))
                }
            }
            44 -> { // 信息(i)
                drawCircle(c, 8f * k, o(12f, 12f), style = st)
                drawCircle(c, 1.1f * k, o(12f, 8.2f))
                line(12f, 11f, 12f, 16.5f)
            }
            21 -> { // HSV(色环+三角)
                drawCircle(c, 8f * k, o(12f, 12f), style = Stroke(1.6f * k))
                path({ moveTo(12f * k, 6.5f * k); lineTo(6.5f * k, 15.5f * k); lineTo(17.5f * k, 15.5f * k); close() }, close = true)
            }
            22 -> { // 图层可见(眼睛睁开)
                eye()
                drawCircle(c, 2.3f * k, o(12f, 12f), style = Stroke(1.6f * k))
            }
            23 -> { // 图层隐藏(闭眼+斜线)
                eye()
                line(4.5f, 19.5f, 19.5f, 4.5f)
            }
            24 -> { // 图层锁定(闭合锁: 锁体+锁梁)
                drawRect(c, topLeft = o(5f, 10.5f), size = Size(14f * k, 9f * k), style = st)
                drawArc(c, 180f, 180f, false, topLeft = o(8f, 4.5f),
                    size = Size(8f * k, 12f * k), style = st)
                drawCircle(c, 1f * k, o(12f, 14f))
                line(12f, 15f, 12f, 16.8f)
            }
            25 -> { // 图层解锁(开口锁: 锁梁悬空, 与锁体留明显缺口)
                drawRect(c, topLeft = o(4f, 12f), size = Size(13f * k, 8f * k), style = st)
                path({
                    moveTo(11f * k, 9f * k); lineTo(11f * k, 6.4f * k)
                    cubicTo(11f * k, 4.2f * k, 12.4f * k, 3f * k, 14.1f * k, 3f * k)
                    cubicTo(16f * k, 3f * k, 17.5f * k, 4.4f * k, 17.5f * k, 6.6f * k)
                    lineTo(17.5f * k, 9f * k)
                })
                drawCircle(c, 1f * k, o(10.5f, 15f))
                line(10.5f, 16f, 10.5f, 17.4f)
            }
            26 -> { // 编辑图层设置(铅笔+编辑基线)
                path({
                    moveTo(5f * k, 16.5f * k); lineTo(7f * k, 11.5f * k)
                    lineTo(14.5f * k, 4f * k); lineTo(18f * k, 7.5f * k)
                    lineTo(10.5f * k, 15f * k); close()
                })
                line(7f, 11.5f, 10.5f, 15f)
                line(4.5f, 20f, 19.5f, 20f)
            }
            46 -> { // 文件夹(加载项目)
                path({ moveTo(3.5f * k, 6.5f * k); lineTo(9f * k, 6.5f * k); lineTo(10.8f * k, 8.8f * k)
                    lineTo(20.5f * k, 8.8f * k); lineTo(20.5f * k, 18.5f * k); lineTo(3.5f * k, 18.5f * k); close() })
            }
            47 -> { // 新建画布(方框+加号)
                drawRect(c, topLeft = o(4f, 4f), size = Size(16f * k, 16f * k), style = st)
                line(12f, 8.5f, 12f, 15.5f)
                line(8.5f, 12f, 15.5f, 12f)
            }
            49 -> { // 调色板画布(画板+四色点)
                drawRect(c, topLeft = o(4f, 5f), size = Size(16f * k, 14f * k), style = st)
                drawCircle(c, 1.7f * k, o(9f, 9.5f))
                drawCircle(c, 1.7f * k, o(15f, 9.5f))
                drawCircle(c, 1.7f * k, o(9f, 14.5f))
                drawCircle(c, 1.7f * k, o(15f, 14.5f))
            }
            48 -> { // 模板库(2×2 图块)
                drawRect(c, topLeft = o(3.8f, 3.8f), size = Size(16.4f * k, 16.4f * k), style = st)
                drawRect(c, topLeft = o(6.2f, 6.2f), size = Size(4.8f * k, 4.8f * k))
                drawRect(c, topLeft = o(13f, 6.2f), size = Size(4.8f * k, 4.8f * k))
                drawRect(c, topLeft = o(6.2f, 13f), size = Size(4.8f * k, 4.8f * k))
                drawRect(c, topLeft = o(13f, 13f), size = Size(4.8f * k, 4.8f * k))
            }
            88 -> { // 浏览器(地球)
                drawCircle(c, 8.3f * k, o(12f, 12f), style = Stroke(1.6f * k))
                line(3.8f, 12f, 20.2f, 12f)
                path({
                    moveTo(12f * k, 3.7f * k)
                    cubicTo(8.2f * k, 7.2f * k, 8.2f * k, 16.8f * k, 12f * k, 20.3f * k)
                    cubicTo(15.8f * k, 16.8f * k, 15.8f * k, 7.2f * k, 12f * k, 3.7f * k)
                })
            }
            80 -> { // 裁剪导出: 托盘 + 向上箭头
                path({ moveTo(4f * k, 14f * k); lineTo(4f * k, 20f * k); lineTo(20f * k, 20f * k); lineTo(20f * k, 14f * k) })
                line(12f, 15f, 12f, 4f)
                path({ moveTo(8f * k, 8f * k); lineTo(12f * k, 4f * k); lineTo(16f * k, 8f * k) })
            }
            81 -> { // 新标签打开: 标签框 + 加号
                path({ moveTo(4f * k, 8f * k); lineTo(4f * k, 20f * k); lineTo(17f * k, 20f * k); lineTo(17f * k, 12f * k) })
                line(4f, 8f, 10f, 8f)
                line(16f, 4f, 16f, 11f)
                line(12.5f, 7.5f, 19.5f, 7.5f)
            }
            82 -> { // 透明叠加: 两层重叠方框 + 前层斜线(半透明)
                drawRect(c, topLeft = o(4f, 4f), size = Size(10f * k, 10f * k), style = st)
                drawRect(c, topLeft = o(10f, 10f), size = Size(10f * k, 10f * k), style = st)
                line(12f, 13f, 18f, 19f)
                line(13f, 19f, 19f, 13f)
            }
            else -> gear() // 未定义编号兜底: 设置齿轮
        }
    }
}
