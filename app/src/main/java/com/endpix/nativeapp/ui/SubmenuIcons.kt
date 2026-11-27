package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 子菜单方形按钮用的程序化线性图标(24×24 视口, 1.8px 描边, 圆头圆角)
// 与 Icons.kt 的 EpIcon 保持同一视觉语言, 仅使用纯矢量绘制
@Composable
fun SubIcon(kind: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val st = Stroke(width = 1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, o(x1, y1), o(x2, y2), strokeWidth = 1.8f * k, cap = StrokeCap.Round)
        fun path(build: Path.() -> Unit, close: Boolean = false) {
            val p = Path()
            p.build()
            if (close) p.close()
            drawPath(p, tint, style = st)
        }
        // 实心小方点(工具端点/顶点)
        fun dot(x: Float, y: Float, r: Float = 1.15f) =
            drawRect(tint, topLeft = o(x - r, y - r), size = Size(2f * r * k, 2f * r * k))
        // 描边方框
        fun box(x: Float, y: Float, w: Float, h: Float) =
            drawRect(tint, topLeft = o(x, y), size = Size(w * k, h * k), style = st)

        when (kind) {
            50 -> { // 直线工具: 斜线 + 两端小方点
                line(6f, 18f, 18f, 6f)
                dot(5f, 19f, 1.3f)
                dot(19f, 5f, 1.3f)
            }
            51 -> { // 矩形工具
                box(4f, 5f, 16f, 14f)
            }
            52 -> { // 椭圆工具
                drawArc(tint, 0f, 360f, false,
                    topLeft = o(3.5f, 5.5f), size = Size(17f * k, 13f * k), style = st)
            }
            53 -> { // 套索填充: 闭合手绘曲线 + 内部斜线/点
                path({
                    moveTo(11.5f * k, 4.5f * k)
                    cubicTo(16.5f * k, 4.5f * k, 20f * k, 8f * k, 19.5f * k, 12f * k)
                    cubicTo(19f * k, 16.5f * k, 14.5f * k, 20f * k, 9f * k, 19f * k)
                    cubicTo(4f * k, 18f * k, 3.5f * k, 12.5f * k, 6f * k, 8.5f * k)
                    cubicTo(7.2f * k, 6.6f * k, 9.2f * k, 4.5f * k, 11.5f * k, 4.5f * k)
                }, close = true)
                line(8.5f, 15.5f, 11.5f, 12.5f)
                line(12f, 16.5f, 15.5f, 13f)
                dot(9.8f, 10.2f, 0.9f)
                dot(14.6f, 10.4f, 0.9f)
            }
            54 -> { // 柳叶: 两端尖的叶片轮廓 + 叶脉
                path({
                    moveTo(4.5f * k, 19.5f * k)
                    cubicTo(12f * k, 16.5f * k, 17.3f * k, 11.3f * k, 19.5f * k, 4.5f * k)
                    cubicTo(12.7f * k, 6.7f * k, 7.5f * k, 12f * k, 4.5f * k, 19.5f * k)
                }, close = true)
                line(6.5f, 17.5f, 17.5f, 6.5f)
            }
            55 -> { // 测距: 斜线 + 两端刻度 + 中间短竖线
                line(5f, 18.5f, 19f, 6f)
                line(3.5f, 16.9f, 6.5f, 20.1f)
                line(17.5f, 4.4f, 20.5f, 7.6f)
                line(12f, 9.8f, 12f, 14.7f)
            }
            56 -> { // 多边形套索: 闭合折线 + 顶点小方点
                path({
                    moveTo(4.5f * k, 17.5f * k)
                    lineTo(4f * k, 9f * k)
                    lineTo(11f * k, 4f * k)
                    lineTo(20f * k, 8f * k)
                    lineTo(18.5f * k, 16.5f * k)
                    close()
                })
                for (pt in listOf(4.5f to 17.5f, 4f to 9f, 11f to 4f, 20f to 8f, 18.5f to 16.5f))
                    dot(pt.first, pt.second, 1.1f)
            }
            57 -> { // 本层蒙版: 层叠方块 + 遮罩斜线
                box(9f, 3.5f, 11f, 11f)
                box(3.5f, 8.5f, 11f, 11f)
                line(5f, 18f, 12.5f, 10.5f)
                line(8.5f, 19f, 14f, 13.5f)
            }
            58 -> { // 全部层: 三层堆叠方块
                box(12f, 3f, 9f, 9f)
                box(7.5f, 7.5f, 9f, 9f)
                box(3f, 12f, 9f, 9f)
            }
            59 -> { // 全部帧: 两张错开的相框
                box(4f, 5.5f, 13f, 10.5f)
                box(7f, 9.5f, 13f, 10.5f)
                drawCircle(tint, 1f * k, o(16.8f, 12.8f))
            }
            60 -> { // 拉伸旋转: 方框 + 旋转弧/箭头
                box(8f, 8f, 8f, 8f)
                drawArc(tint, 30f, 280f, false,
                    topLeft = o(3.5f, 3.5f), size = Size(17f * k, 17f * k), style = st)
                line(19.2f, 6.9f, 15.7f, 6f)
                line(19.2f, 6.9f, 17.7f, 3.7f)
            }
            61 -> { // 重回中心: 十字准星 + 中心点
                line(12f, 3.5f, 12f, 20.5f)
                line(3.5f, 12f, 20.5f, 12f)
                drawCircle(tint, 1.8f * k, o(12f, 12f))
            }
            62 -> { // 灰度: 半黑半白圆
                drawArc(tint, 90f, 180f, true,
                    topLeft = o(4f, 4f), size = Size(16f * k, 16f * k))
                drawCircle(tint, 8f * k, o(12f, 12f), style = st)
            }
            63 -> { // 翻转横向: 左右镜像三角 + 中间竖轴
                line(12f, 4f, 12f, 20f)
                path({ moveTo(9.5f * k, 7f * k); lineTo(4f * k, 12f * k); lineTo(9.5f * k, 17f * k); close() })
                path({ moveTo(14.5f * k, 7f * k); lineTo(20f * k, 12f * k); lineTo(14.5f * k, 17f * k); close() })
            }
            64 -> { // 翻转纵向: 上下镜像三角 + 中间横轴
                line(4f, 12f, 20f, 12f)
                path({ moveTo(7f * k, 9.5f * k); lineTo(12f * k, 4f * k); lineTo(17f * k, 9.5f * k); close() })
                path({ moveTo(7f * k, 14.5f * k); lineTo(12f * k, 20f * k); lineTo(17f * k, 14.5f * k); close() })
            }
            65 -> { // 藏色: 调色盘 + 遮罩斜线
                drawCircle(tint, 7.5f * k, o(12f, 11.5f), style = st)
                drawCircle(tint, 1.1f * k, o(8.5f, 9.2f))
                drawCircle(tint, 1.1f * k, o(13.5f, 8.2f))
                drawCircle(tint, 1.1f * k, o(15.8f, 12.2f))
                line(5f, 20f, 19f, 4.5f)
            }
            66 -> { // 固定竖屏: 竖手机 + 锁
                box(6.5f, 2.5f, 9f, 14.5f)
                line(9.5f, 5f, 12.5f, 5f)
                box(13.5f, 15.5f, 7f, 5f)
                drawArc(tint, 180f, 180f, false,
                    topLeft = o(15f, 11.5f), size = Size(4f * k, 4f * k),
                    style = Stroke(1.6f * k, cap = StrokeCap.Round))
                line(15f, 13.5f, 15f, 15.5f)
                line(19f, 13.5f, 19f, 15.5f)
            }
            71 -> { // 小地图: 面板框 + 内部地图线 + 小视口框
                box(3.5f, 3.5f, 17f, 17f)
                line(4.5f, 14f, 9f, 9.5f)
                line(9f, 9.5f, 12.5f, 13f)
                line(12.5f, 13f, 16f, 8.5f)
                line(16f, 8.5f, 19.5f, 11f)
                box(8f, 7.5f, 6f, 5.5f)
            }
            72 -> { // 参考图: 相框 + 山形 + 太阳
                box(3.5f, 4.5f, 17f, 15f)
                path({
                    moveTo(5.5f * k, 17f * k)
                    lineTo(10f * k, 11.5f * k)
                    lineTo(13f * k, 14.8f * k)
                    lineTo(15.5f * k, 12f * k)
                    lineTo(18.5f * k, 17f * k)
                })
                drawCircle(tint, 1.3f * k, o(15.5f, 8f))
            }
            67 -> { // 完美线条: L 形折线 + 拐角打叉
                path({
                    moveTo(5f * k, 4.5f * k)
                    lineTo(5f * k, 16.5f * k)
                    lineTo(19f * k, 16.5f * k)
                })
                line(3.7f, 15.2f, 6.3f, 17.8f)
                line(6.3f, 15.2f, 3.7f, 17.8f)
            }
            68 -> { // 填充: 实心方块 + 边框
                box(4f, 4f, 16f, 16f)
                drawRect(tint, topLeft = o(6.5f, 6.5f), size = Size(11f * k, 11f * k))
            }
            70 -> { // 叠色: 两个半透明交叠圆(交集更深)
                drawCircle(tint.copy(alpha = 0.22f), 6f * k, o(9f, 12f))
                drawCircle(tint.copy(alpha = 0.22f), 6f * k, o(15f, 12f))
                drawCircle(tint, 6f * k, o(9f, 12f), style = st)
                drawCircle(tint, 6f * k, o(15f, 12f), style = st)
            }
            69 -> { // 比例锁: 1:1 方框 + 链接
                box(3f, 3f, 12f, 12f)
                drawCircle(tint, 2.3f * k, o(16f, 17.5f),
                    style = Stroke(1.6f * k, cap = StrokeCap.Round))
                drawCircle(tint, 2.3f * k, o(19f, 14.5f),
                    style = Stroke(1.6f * k, cap = StrokeCap.Round))
            }
            73 -> { // 网格: 3×3 网格线
                box(3.5f, 3.5f, 17f, 17f)
                line(3.5f, 9.2f, 20.5f, 9.2f)
                line(3.5f, 14.8f, 20.5f, 14.8f)
                line(9.2f, 3.5f, 9.2f, 20.5f)
                line(14.8f, 3.5f, 14.8f, 20.5f)
            }
            74 -> { // 棋盘格: 2×2 交错方块
                box(3.5f, 3.5f, 17f, 17f)
                drawRect(tint, topLeft = o(3.5f, 3.5f), size = Size(8.5f * k, 8.5f * k))
                drawRect(tint, topLeft = o(12f, 12f), size = Size(8.5f * k, 8.5f * k))
            }
            75 -> { // 白噪音: 三条起伏声波
                path({
                    moveTo(3f * k, 12f * k)
                    cubicTo(6f * k, 6f * k, 9f * k, 18f * k, 12f * k, 12f * k)
                })
                path({
                    moveTo(9f * k, 12f * k)
                    cubicTo(12f * k, 6f * k, 15f * k, 18f * k, 18f * k, 12f * k)
                })
                path({ moveTo(15f * k, 12f * k); lineTo(21f * k, 12f * k) })
            }
            76 -> { // 文字: T 字 + 基线
                line(5f, 5f, 19f, 5f)
                line(12f, 5f, 12f, 18f)
                line(8f, 19.5f, 16f, 19.5f)
            }
            77 -> { // 方形渐变: 方框 + 水平渐隐填充
                drawRect(Brush.horizontalGradient(listOf(tint, tint.copy(alpha = 0.06f))),
                    topLeft = o(5.5f, 6.5f), size = Size(13f * k, 11f * k))
                box(4f, 5f, 16f, 14f)
            }
            78 -> { // 圆形渐变: 圆 + 同心圈(中心实→边缘透)
                drawCircle(tint.copy(alpha = 0.16f), radius = 8.2f * k, center = o(12f, 12f))
                drawCircle(tint, radius = 8.5f * k, center = o(12f, 12f), style = st)
                drawCircle(tint.copy(alpha = 0.55f), radius = 5.2f * k, center = o(12f, 12f),
                    style = Stroke(1.2f * k))
                drawCircle(tint.copy(alpha = 0.3f), radius = 2.4f * k, center = o(12f, 12f),
                    style = Stroke(1f * k))
            }
            79 -> { // 标签栏: 顶部长条 + 分隔的标签页
                box(3.5f, 5f, 17f, 14f)
                line(3.5f, 9.5f, 20.5f, 9.5f)
                line(10f, 5f, 10f, 9.5f)
                line(15f, 5f, 15f, 9.5f)
            }
            80 -> { // 裁剪导出: 托盘 + 向上箭头
                path({
                    moveTo(4f * k, 14f * k); lineTo(4f * k, 20f * k)
                    lineTo(20f * k, 20f * k); lineTo(20f * k, 14f * k)
                })
                line(12f, 15f, 12f, 4f)
                path({ moveTo(8f * k, 8f * k); lineTo(12f * k, 4f * k); lineTo(16f * k, 8f * k) })
            }
            81 -> { // 新标签打开: 标签框 + 加号
                path({
                    moveTo(4f * k, 8f * k); lineTo(4f * k, 20f * k)
                    lineTo(17f * k, 20f * k); lineTo(17f * k, 12f * k)
                })
                line(4f, 8f, 10f, 8f)
                line(16f, 4f, 16f, 11f)
                line(12.5f, 7.5f, 19.5f, 7.5f)
            }
            82 -> { // 透明叠加: 两层重叠方框 + 前层斜线
                box(4f, 4f, 10f, 10f)
                box(10f, 10f, 10f, 10f)
                line(12f, 13f, 18f, 19f)
                line(13f, 19f, 19f, 13f)
            }
            83 -> { // 透视变形: 梯形四角 + 角点
                val p = Path()
                p.moveTo(4f * k, 6f * k)
                p.lineTo(20f * k, 6f * k)
                p.lineTo(17f * k, 19f * k)
                p.lineTo(7f * k, 19f * k)
                p.close()
                drawPath(p, tint, style = st)
                dot(4f, 6f); dot(20f, 6f); dot(7f, 19f); dot(17f, 19f)
            }
            else -> { // 未知编号: 简单圆环+圆点
                drawCircle(tint, 7f * k, o(12f, 12f), style = st)
                drawCircle(tint, 1.2f * k, o(12f, 12f))
            }
        }
    }
}
