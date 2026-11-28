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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

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

// 浮层/菜单入场: fade + 0.96→1 + 轻微位移; 关闭时缩小淡出。
// side: -1 左侧滑入, 1 右侧滑入, 0 上下滑入
@Composable
fun MotionPanel(
    visible: Boolean,
    modifier: Modifier = Modifier,
    side: Int = 0,
    origin: TransformOrigin = TransformOrigin(0.5f, 0.5f),
    content: @Composable () -> Unit,
) {
    val slide = when (side) {
        -1 -> slideInHorizontally(animationSpec = Motion.SlideIn) { -it / 12 }
        1 -> slideInHorizontally(animationSpec = Motion.SlideIn) { it / 12 }
        else -> slideInVertically(animationSpec = Motion.SlideIn) { it / 14 }
    }
    val state = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { state.targetState = visible }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(Motion.PanelIn) +
            scaleIn(initialScale = 0.96f, animationSpec = Motion.PanelIn, transformOrigin = origin) +
            slide,
        exit = fadeOut(Motion.PanelOut) +
            scaleOut(targetScale = 0.98f, animationSpec = Motion.PanelOut, transformOrigin = origin),
    ) { content() }
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
