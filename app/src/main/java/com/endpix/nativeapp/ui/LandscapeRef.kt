package com.endpix.nativeapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.os.Handler
import android.os.Looper

/**
 * 横屏模式额外图片层: 在画布上方显示一张可缩放/旋转/拖动的图片(参考/临摹用).
 * 状态放全局 object: MainScreen 已贴 JVM 64K, 不再给 MainScreen/LandscapeScreen 加参数.
 * 图片选择/持久化由 MainActivity 接入(prefs landscapeRefOn + files/landscape_image.png).
 */
object LandscapeRefHub {
    var visible by mutableStateOf(false)
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var scale by mutableFloatStateOf(1f)
    var rotation by mutableFloatStateOf(0f)   // 角度, 可连续旋转; 显示时按 360 归一
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    // 系统截屏时临时隐藏横屏菜单/☰, 让截图干净; 1.5s 后自动恢复
    var chromeHidden by mutableStateOf(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val restoreChrome = Runnable { chromeHidden = false }
    var pick: () -> Unit = {}
    var persistVisible: ((Boolean) -> Unit)? = null

    fun hideChromeTemporarily(ms: Long = 1500L) {
        chromeHidden = true
        uiHandler.removeCallbacks(restoreChrome)
        uiHandler.postDelayed(restoreChrome, ms)
    }

    fun resetChrome() {
        uiHandler.removeCallbacks(restoreChrome)
        chromeHidden = false
    }

    /** 横屏 ☰ 里的「显示/隐藏图片」单按键: 没图片时首次点击直接进选图. */
    fun toggle() {
        if (bitmap == null) { pick(); return }
        visible = !visible
        persistVisible?.invoke(visible)
    }

    fun setBitmap(bmp: ImageBitmap, show: Boolean = true) {
        bitmap = bmp
        visible = show
        resetTransform()
        persistVisible?.invoke(visible)
    }

    fun resetTransform() {
        scale = 1f
        rotation = 0f
        offsetX = 0f
        offsetY = 0f
    }
}

/** 绘制在横屏画布之上的图片层; 单指拖动, 大小/旋转在横屏 ☰ 子菜单用滑条调整. */
@Composable
fun LandscapeRefLayer(modifier: Modifier = Modifier) {
    val bmp = LandscapeRefHub.bitmap
    if (!LandscapeRefHub.visible || bmp == null) return
    Box(
        modifier.fillMaxSize().pointerInput(bmp) {
            detectDragGestures { change, drag ->
                change.consume()
                LandscapeRefHub.offsetX += drag.x
                LandscapeRefHub.offsetY += drag.y
            }
        }
    ) {
        Image(
            bmp, contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = LandscapeRefHub.offsetX
                translationY = LandscapeRefHub.offsetY
                scaleX = LandscapeRefHub.scale
                scaleY = LandscapeRefHub.scale
                rotationZ = LandscapeRefHub.rotation
            },
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None   // 像素图必须最近邻, 不能双线性糊
        )
    }
}
