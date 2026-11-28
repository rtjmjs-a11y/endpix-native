package com.endpix.nativeapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// 动效语言参考 guanmo-ai/ui-moments:
//  --ease: cubic-bezier(.16,1,.3,1)   --spring: cubic-bezier(.3,1.45,.5,1)
//  按压 45ms / 0.94~0.96 / alpha .8, 面板 0.96→1 + 8~14px 位移, 回弹用 spring.
object Motion {
    val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val SpringCurve = CubicBezierEasing(0.3f, 1.45f, 0.5f, 1f)
    val PressCurve = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    const val PressMs = 45
    val PanelIn = tween<Float>(durationMillis = 240, easing = Ease)
    val SlideIn = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 240, easing = Ease)
    val PanelOut = tween<Float>(durationMillis = 140, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f))
    val Spring = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
    val DialogIn = tween<Float>(durationMillis = 180, easing = Ease)
}

// 按压回弹: scale 0.94 + alpha .82, 松开 spring 回 1
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    pressedScale: Float = 0.94f,
    pressedAlpha: Float = 0.82f,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(Motion.PressMs, easing = Motion.PressCurve) else Motion.Spring,
        label = "press-scale",
    )
    val a by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else 1f,
        animationSpec = if (pressed) tween(Motion.PressMs) else Motion.Spring,
        label = "press-alpha",
    )
    return graphicsLayer { scaleX = s; scaleY = s; alpha = a }
}

// 浮层/菜单入场: 只做 graphicsLayer 变换(alpha/scale/位移), 不参与布局, 避免每帧 relayout。
// side: -1 左侧滑入(从左边缘长出), 1 右侧, 0 上下(默认从下往上)。
// 首次显示先等一帧让内容完成组合, 再启动弹簧, 避免"组合卡顿+动画"叠在同一帧。
@Composable
fun MotionPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    side: Int = 0,
    origin: TransformOrigin = when {
        side < 0 -> TransformOrigin(0f, 0.5f)
        side > 0 -> TransformOrigin(1f, 0.5f)
        else -> TransformOrigin(0.5f, 0.5f)
    },
    content: @Composable () -> Unit,
) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    val distPx = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
    LaunchedEffect(visible) {
        if (visible) {
            shown = true
            withFrameNanos { }
            progress.animateTo(
                1f,
                androidx.compose.animation.core.spring(
                    dampingRatio = 0.72f, stiffness = 680f)
            )
        } else if (shown) {
            progress.animateTo(
                0f,
                androidx.compose.animation.core.tween(
                    durationMillis = 150,
                    easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 1f, 1f))
            )
            shown = false
        }
    }
    if (shown) {
        val dir = when (side) { -1 -> -1f; 1 -> 1f; else -> 0f }
        androidx.compose.foundation.layout.Box(
            modifier
                .then(
                    if (!visible) Modifier.pointerInput(Unit) {
                        // 退场动画期间吞掉触摸, 防止点到正在淡出的菜单项
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    } else Modifier
                )
                .graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    val sc = 0.92f + 0.08f * p
                    scaleX = sc
                    scaleY = sc
                    if (dir != 0f) {
                        translationX = (1f - p) * dir * distPx
                        translationY = 0f
                    } else {
                        translationX = 0f
                        translationY = (1f - p) * distPx
                    }
                    transformOrigin = origin
                }
        ) { content() }
    }
}

// 对话框卡片入场(窗口本身没有退出动画): 0.975→1 + fade
@Composable
fun MotionDialogCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(
        targetValue = if (shown) 1f else 0.975f,
        animationSpec = Motion.Spring,
        label = "dialog-scale",
    )
    val a by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.DialogIn,
        label = "dialog-alpha",
    )
    Box(modifier.graphicsLayer { scaleX = s; scaleY = s; alpha = a }) { content() }
}
