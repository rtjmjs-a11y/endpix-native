package com.endpix.nativeapp.ui

/** 画布标签信息(长按标签弹出)的一行: 左侧标签 + 右侧值. */
data class TabField(val label: String, val value: String)

/** 画布标签信息快照(名称 + 若干行), 由 MainActivity 组装. */
data class TabInfo(val tab: UiTab, val fields: List<TabField>)

/**
 * 标签信息提供者. 用全局 object 而不是给 CanvasTabBar/MainScreen 加参数:
 * MainScreen 已贴 JVM 64K 方法上限, 再加 lambda 参数会导致 MethodTooLarge.
 */
object TabInfoHub {
    var provider: (Int) -> TabInfo? = { null }
}
