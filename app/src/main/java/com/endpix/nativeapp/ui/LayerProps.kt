package com.endpix.nativeapp.ui

// 图层模式/平铺/混合/2.5D 的原生桥(全局对象: 不给 MainScreen 加参数, 避免 64K 方法上限)
// props 每层 10 个值: 结构模式, 网格宽, 网格高, 偏移X, 偏移Y, 显示网格, 网格颜色(ABGR),
//                    不跟随动画, 锁定帧, 混合模式
object LayerOps {
    const val STRIDE = 10

    var setProps: ((layer: Int, mode: Int, tw: Int, th: Int, ox: Int, oy: Int,
                    showGrid: Boolean, gridColor: Int, indep: Boolean, lockFrame: Int,
                    blend: Int) -> Unit)? = null
    var getProps: (() -> IntArray)? = null
    var set25D: ((on: Boolean, depth: Int, dir: Int) -> Unit)? = null
    var reset25DView: (() -> Unit)? = null
    var get25D: (() -> IntArray)? = null

    fun propsFor(layer: Int): IntArray {
        val all = try { getProps?.invoke() } catch (_: Throwable) { null } ?: return IntArray(0)
        val o = layer * STRIDE
        if (o < 0 || o + STRIDE > all.size) return IntArray(0)
        return all.copyOfRange(o, o + STRIDE)
    }

    fun state25D(): IntArray = try { get25D?.invoke() } catch (_: Throwable) { null } ?: intArrayOf(0, 5, 0)
}
