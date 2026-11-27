package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 裁剪目标的额外操作: 裁剪导出 / 新标签打开.
 * 目标像素来自引擎的「上次裁剪」(lastCut, 活动层 ABGR), 不修改当前画布.
 * 全局 object: MainScreen 已贴 64K, 不再加参数.
 */
object CropExportHub {
    var w by mutableIntStateOf(0)
    var h by mutableIntStateOf(0)
    var pixels: IntArray? = null        // 预乘 ABGR, w*h
    var thumb: IntArray? = null         // 64² 预览(方形), 给导出对话框
    var exportOpen by mutableStateOf(false)
    /** 当前是否有"正在框选且已成功裁剪(浮起)"的目标 — 由 MainActivity 的 60Hz 选区轮询更新. */
    var targetReady by mutableStateOf(false)
    private var pendingScale = 0
    var onRequest: () -> Unit = {}      // MainActivity: 读 lastCut -> 填 w/h/pixels/thumb -> exportOpen=true
    var onNewTab: () -> Unit = {}       // MainActivity: 用 lastCut 新建标签
    var onStartExport: (Int) -> Unit = {}  // MainActivity: 记住 scale 并拉起保存对话框

    fun requestExport() { onRequest() }
    fun newTab() { onNewTab() }
    fun startExport(scale: Int) {
        exportOpen = false
        pendingScale = scale
        onStartExport(scale)
    }
    /** doExportPng 里调用: 返回本次裁剪导出的倍率, 0=不是裁剪导出. */
    fun consumeExportScale(): Int {
        val s = pendingScale
        pendingScale = 0
        return s
    }
    fun clear() {
        pixels = null
        thumb = null
        w = 0
        h = 0
    }
}
