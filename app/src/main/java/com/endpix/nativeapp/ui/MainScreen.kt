package com.endpix.nativeapp.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private fun toolLogName(id: Int): String = when (id) {
    0 -> "画笔"
    1 -> "框选"
    2 -> "橡皮"
    3 -> "油漆桶"
    4 -> "图形"
    5 -> "更多工具"
    else -> "工具$id"
}

private class ToolUi(val id: Int, val icon: Int, val enabled: Boolean)

// 旧项目工具栏顺序: 画笔/橡皮/油漆/图形/框选/工具
private val TOOLS = listOf(
    ToolUi(0, 0, true),    // 画笔
    ToolUi(2, 1, true),    // 橡皮
    ToolUi(3, 2, true),    // 油漆桶
    ToolUi(4, 13, true),   // 图形
    ToolUi(1, 5, true),    // 框选
    ToolUi(5, 14, true),   // 更多工具菜单
)

// 预编译 stats 正则: MainScreen 每次 stats 刷新都会读这些字段, 避免每条都 new Regex
private val RE_SEL_ACTIVE = Regex("""sel \d+""")
private val RE_FRAME_INFO = Regex("""fr=(\d+)/(\d+)""")
private val RE_PLAYING = Regex("""play=(\d)""")
private val RE_PLAY_SPEED = Regex("""spd=(\d+)""")
private val RE_LOOP = Regex("""loop=(-?\d+)-(-?\d+)""")
private val RE_LAST_CUT = Regex("""lc=(\d)""")
private val RE_RECORD = Regex("""rec=(\d)/(\d+)""")
private val RE_UNDO = Regex("""\su=(\d+)""")
private val RE_REDO = Regex("""\sr=(\d+)""")
private val RE_ONION = Regex("""onion=(\d)""")
private val RE_VIS = Regex("""vis=([0-9A-Fa-f]+)""")
private val RE_LCK = Regex("""lck=([0-9A-Fa-f]+)""")
private val RE_CANVAS_DIMS = Regex("""(\d+)x(\d+)""")
private val RE_LAYER_N = Regex("""L(\d+) A""")
private val RE_ACTIVE_LAYER = Regex("""L\d+ A(\d+)""")
private val RE_ZOOM = Regex("""z=([\d.]+)""")
private val RE_FPS = Regex("""fps=(\d+)""")
private val RE_COMP = Regex("""comp=([\d.]+)""")
private val RE_RSS = Regex("""rss=(\d+)MB""")
private val RE_CAP = Regex("""cap=(\d+)""")
private val RE_LCSKIP = Regex("""lcskip=(\d+)""")
private val RE_WSKIP = Regex("""wskip=(\d+)""")
private val RE_FR_IDX = Regex("""fr=(\d+)/""")
private val RE_FR_COUNT = Regex("""fr=\d+/(\d+)""")
private val RE_CV = Regex("""cv=(-?\d+),(-?\d+),(-?\d+),(-?\d+)""")

@Composable
fun MainScreen(
    stats: String,
    tool: Int,
    layers: Int,
    activeLayer: Int,
    brushColor: Int,
    onTool: (Int) -> Unit,
    onColor: (Int) -> Unit,
    onHsvColor: (Int) -> Unit = {},
    onLayerOp: (Int, Int) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCrop: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    panMode: Boolean,
    onMove: () -> Unit,
    sidePalette: IntArray,
    picking: Boolean,
    onPickRequested: () -> Unit,
    onDeselect: () -> Unit,
    onCancelPick: () -> Unit,
    brushPx: Float,
    brushMax: Int = 60,
    eraserPx: Float = 6f,
    eraserMax: Int = 60,
    brushAlpha: Int,
    eraseMode: Boolean,
    pinchUndoMs: Int = 3000,
    onPinchUndoMs: (Int) -> Unit = {},
    onBrushPx: (Float) -> Unit,
    onBrushMax: (Int) -> Unit = {},
    onEraserPx: (Float) -> Unit = {},
    onEraserMax: (Int) -> Unit = {},
    onBrushAlpha: (Int) -> Unit,
    onEraseMode: (Boolean) -> Unit,
    shapeType: Int,
    shapeFill: Boolean,
    onShapeType: (Int) -> Unit,
    onShapeFill: (Boolean) -> Unit,
    shapePx: Float,
    onShapePx: (Float) -> Unit,
    // 测距模式按钮(旧 Measure.cs): 固定起点/待设起点/回到手指起点
    measureFixed: Boolean = false,
    measurePending: Boolean = false,
    onMeasureSetStart: () -> Unit = {},
    onMeasureCenterStart: () -> Unit = {},
    onMeasureFingerStart: () -> Unit = {},
    // 多边形套索浮条(旧 pm615): 顶点数 + 撤销点/重置点/完成/取消
    polyVerts: Int = 0,
    onPolyUndo: () -> Unit = {},
    onPolyReset: () -> Unit = {},
    onPolyFinish: () -> Unit = {},
    onPolyCancel: () -> Unit = {},
    perfectLine: Boolean,
    onPerfectLine: (Boolean) -> Unit,
    leafW: Float,
    onLeafW: (Float) -> Unit,
    shapeRatio: Boolean,
    onShapeRatio: (Boolean) -> Unit,
    alphaLock: Boolean,
    buildup: Boolean = false,
    onAlphaLock: (Boolean) -> Unit,
    onBuildup: (Boolean) -> Unit = {},
    batteryPct: Int,
    charging: Boolean,
    memPct: Int,
    portraitLock: Boolean,
    onPortraitLock: (Boolean) -> Unit,
    selRect: androidx.compose.runtime.State<IntArray>,
    selFlag: androidx.compose.runtime.State<Int>,
    measUi: androidx.compose.runtime.State<IntArray> = androidx.compose.runtime.mutableStateOf(IntArray(12)),
    stretchMode: Boolean,
    onSetStretch: (Boolean) -> Unit,
    onSelReset: () -> Unit,
    onFrameOp: (Int, Int) -> Unit,
    frameThumb: (Int, Int) -> IntArray,
    minimapThumb: () -> IntArray = { IntArray(0) },
    compositeThumb: () -> IntArray = { IntArray(0) },
    resizeThumb: () -> IntArray = { IntArray(0) },   // 调整画布预览用 192² 合成缩略图
    exportThumb: () -> IntArray = { IntArray(0) },   // 导出预览用 384² 合成缩略图
    onPasteLastCut: () -> Unit,
    onCopySel: () -> Unit,
    onDeleteSel: () -> Unit,
    onConfirmSel: () -> Unit,
    onCancelSel: () -> Unit,
    onRotateSel: (Int) -> Unit,
    onFlipSel: (Boolean) -> Unit,
    onImportImage: () -> Unit,
    onExportPng: (Int) -> Unit = {},
    onExportWebp: (Int) -> Unit = {},
    onExportProject: () -> Unit = {},
    onExportPsd: () -> Unit = {},
    onExportReplay: () -> Unit = {},
    onLoadProject: () -> Unit = {},
    onLoadFile: () -> Unit = {},
    onLevelsHistogram: (Int) -> IntArray = { IntArray(256) },
    onPreviewLevels: (IntArray?) -> Unit = {},
    onApplyLevelsAll: (IntArray) -> Unit = {},
    watermark: com.endpix.nativeapp.ui.WatermarkSettings = com.endpix.nativeapp.ui.WatermarkSettings(),
    onWatermark: (com.endpix.nativeapp.ui.WatermarkSettings) -> Unit = {},
    sigIcon: android.graphics.Bitmap? = null,
    onPickSigIcon: () -> Unit = {},
    onClearSigIcon: () -> Unit = {},
    canvasStyle: com.endpix.nativeapp.ui.CanvasStyleSettings = com.endpix.nativeapp.ui.CanvasStyleSettings(),
    onCanvasStyle: (com.endpix.nativeapp.ui.CanvasStyleSettings) -> Unit = {},
    uiScalePct: Int = 100,
    onUiScalePct: (Int) -> Unit = {},
    pngCompress: Int = 1,
    onPngCompress: (Int) -> Unit = {},
    toolYOffset: Int = 0,
    onToolYOffset: (Int) -> Unit = {},
    sfxOn: Boolean = true,
    onSfxOn: (Boolean) -> Unit = {},
    selAllLayers: Boolean,
    onSelAllLayers: (Boolean) -> Unit,
    selAllFrames: Boolean = false,
    onSelAllFrames: (Boolean) -> Unit = {},
    onStretch: (Int, Int) -> Unit,
    onPreset: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onZoomFit: () -> Unit,
    onFlip: (Boolean) -> Unit,
    onGrayscale: () -> Unit,
    overlayChk: Boolean,
    overlayGrid: Boolean,
    onOverlayChk: (Boolean) -> Unit,
    onOverlayGrid: (Boolean) -> Unit,
    onSelShape: (Int) -> Unit = {},
    onOnion: (Boolean) -> Unit = {},
    tabs: List<Pair<Int, String>> = emptyList(),
    tabSize: (Int) -> Pair<Int, Int>? = { null },
    activeTab: Int = 1,
    onTabSelect: (Int) -> Unit = {},
    onTabAdd: (Int, Int) -> Unit = { _, _ -> },
    onTabRename: (Int, String) -> Unit = { _, _ -> },
    tabThumb: (Int) -> IntArray? = { null },
    palettePresets: List<PalettePreset> = emptyList(),
    onPresetSave: (String) -> Unit = {},
    onPresetApply: (Int) -> Unit = {},
    onPresetDelete: (Int) -> Unit = {},
    onPresetImport: () -> Unit = {},
    onPresetExport: () -> Unit = {},
    onTabClose: (Int) -> Unit = {},
    projectFolder: String = "",
    onPickProjectFolder: () -> Unit = {},
    onClearProjectFolder: () -> Unit = {},
    autoSaveMin: Int = 0,
    autoSaveRemain: Int = -1,
    autoSaveSaving: Boolean = false,
    onAutoSaveMin: (Int) -> Unit = {},
    onClearCanvas: () -> Unit = {},
    onResizeCanvas: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    // 白噪音系统(更多工具): 本地实时合成 + 5 层混音 + 音量
    noiseMix: NoiseMix = NoiseMix(),
    onNoiseMix: (NoiseMix) -> Unit = {},
    // 模板库: 选择旧项目模板图片, 新建对应尺寸画布并导入
    onPickTemplate: (String) -> Unit = {},
    templateRev: Int = 0,
    onImportTemplate: () -> Unit = {},
    onExportTemplate: (String) -> Unit = { _ -> },
    onDeleteTemplate: (String, Boolean) -> Unit = { _, _ -> },
    onSaveNow: () -> Unit = {},
    versionName: String = "1.0",
    recordOn: Boolean = false,
    recordedFrames: Int = 0,
    onRecord: (Boolean) -> Unit = {},
    timelineOpen: Boolean = false,
    onTimelineOpen: (Boolean) -> Unit = {},
    playBrush: Boolean = false,
    onPlayBrush: (Boolean) -> Unit = {},
    onLoopRange: (Int, Int) -> Unit = { _, _ -> },
    onReorderFrame: (Int, Int) -> Unit = { _, _ -> },
    onSwapFrames: (Int, Int) -> Unit = { _, _ -> },
    onReorderLayer: (Int, Int) -> Unit = { _, _ -> },
    selModeInit: Int = 0,
    onSelMode: (Int) -> Unit = {},
    layerMeta: List<Pair<String, Int>> = emptyList(),
    onRenameLayer: (Int, String) -> Unit = { _, _ -> },
    onLayerOpacity: (Int, Int) -> Unit = { _, _ -> },
    minimapOn: Boolean = false,
    thumbRev: Int = 0,
    onMinimapOn: (Boolean) -> Unit = {},
    minimapScale: Float = 1f,
    onMinimapScale: (Float) -> Unit = {},
    minimapX: Float = 8f,
    minimapY: Float = 68f,
    onMinimapPos: (Float, Float) -> Unit = { _, _ -> },
    onMinimapNavigate: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    onMinimapBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    minimapViewport: (FloatArray) -> Unit = {},
    minimapBg: Int = 3,
    onMinimapBg: (Int) -> Unit = {},
    refOn: Boolean = false,
    onRefOn: (Boolean) -> Unit = {},
    refScale: Float = 1f,
    onRefScale: (Float) -> Unit = {},
    refX: Float = -1f,
    refY: Float = 68f,
    onRefPos: (Float, Float) -> Unit = { _, _ -> },
    refBitmap: android.graphics.Bitmap? = null,
    onPickRefImage: () -> Unit = {},
    onRefBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    var showTimeline by remember { mutableStateOf(timelineOpen) }
    fun setTimelineOpen(open: Boolean) {
        // 状态没变不要重复回调: 设置菜单等按钮无条件调 setTimelineOpen(false),
        // 之前会在时间轴本来就关着时触发"恢复打开前视角", 导致点设置后视角乱跳/缩放变化
        if (open == showTimeline) return
        showTimeline = open
        onTimelineOpen(open)
    }
    var showSettings by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var showPaletteDialog by remember { mutableStateOf(false) }
    var showHsv by remember { mutableStateOf(false) }
    // 右侧色板 6–60 色(长按色板弹窗切换); 6 色整格、7–18 色半格、19 色起小格可滚动
    val palCount = ToolPaletteLayout.paletteCount(SidePalettePrefs.count.intValue)
    val visPalette = sidePalette.copyOf(palCount.coerceAtMost(sidePalette.size))
    // 选中的色槽: 点哪格选哪格(同色不双选); 该格显示当前画笔色
    var paletteSelIdx by remember {
        mutableIntStateOf(
            visPalette.indexOfFirst { (it and 0xFFFFFF) == (brushColor and 0xFFFFFF) }
                .coerceAtLeast(0)
        )
    }
    LaunchedEffect(palCount) {
        if (paletteSelIdx >= palCount) paletteSelIdx = 0
    }
    var toolMenu by remember { mutableIntStateOf(-1) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showNoisePanel by remember { mutableStateOf(false) }
    var showProcessDialog by remember { mutableStateOf(false) }
    var showExportPngDialog by remember { mutableStateOf(false) }
    var showExportWebpDialog by remember { mutableStateOf(false) }
    var showLevels by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // 工具栏上下位置: toolYOffset 是 -50..50 的百分比(0=居中), 随屏幕/UI缩放保持比例
    val toolYDp = rememberToolShiftDp(toolYOffset)
    var hsvStartMode by remember { mutableIntStateOf(0) }
    var showStretch by remember { mutableStateOf(false) }
    var selMode by remember { mutableIntStateOf(selModeInit.coerceIn(0, 2)) }
    var subToast by remember { mutableStateOf("") }
    // 回放模式: 画布区域变成播放器; 编辑 UI 都退到回放层之下, 只保留标签栏
    val replayOn = ReplayHub.visible && ReplayHub.session != null
    val browserOn = BrowserHub.visible
    LaunchedEffect(replayOn, browserOn) {
        if (replayOn || browserOn) {
            setTimelineOpen(false)
            showSettings = false
            showSettingsMenu = false
            showMore = false
            toolMenu = -1
            colorMenu = false
            showHsv = false
            showPaletteDialog = false
            showNoisePanel = false
            showProcessDialog = false
            StudioHub.mode = -1
            showLevels = false
            showExportPngDialog = false
            showExportWebpDialog = false
            showStretch = false
            if (SelDrawer.open) closeDrawerRestore()
        }
    }

    val ui = remember(stats) { parseStats(stats) }
    val selActive = remember(stats) { RE_SEL_ACTIVE.containsMatchIn(stats) }
    val frameInfo = remember(stats) { RE_FRAME_INFO.find(stats)?.groupValues }
    val frameIdx = (frameInfo?.get(1)?.toIntOrNull() ?: 1) - 1
    val frameCount = frameInfo?.get(2)?.toIntOrNull() ?: 1
    val playing = remember(stats) { RE_PLAYING.find(stats)?.groupValues?.get(1) == "1" }
    val playSpeed = remember(stats) { RE_PLAY_SPEED.find(stats)?.groupValues?.get(1)?.toIntOrNull() ?: 12 }
    val loopInfo = remember(stats) { RE_LOOP.find(stats)?.groupValues }
    val hasLastCut = remember(stats) { RE_LAST_CUT.find(stats)?.groupValues?.get(1) == "1" }
    val recordInfo = remember(stats) { RE_RECORD.find(stats)?.groupValues }
    val onionOn = remember(stats) { RE_ONION.find(stats)?.groupValues?.get(1) == "1" }
    val visBits = remember(stats) { RE_VIS.find(stats)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L }
    val lckBits = remember(stats) { RE_LCK.find(stats)?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L }

    // 油漆桶超预算提示(2.2s 自动消失)
    var capToast by remember { mutableStateOf(false) }
    var lcToast by remember { mutableStateOf(false) }
    var mskToast by remember { mutableStateOf(false) }
    var lastWSkip by remember { mutableIntStateOf(ui.wskip) }
    LaunchedEffect(ui.wskip) {
        if (ui.wskip != lastWSkip) {
            lastWSkip = ui.wskip
            mskToast = true
            kotlinx.coroutines.delay(2200)
            mskToast = false
        }
    }
    var lastLcSkip by remember { mutableIntStateOf(ui.lcskip) }
    LaunchedEffect(ui.lcskip) {
        if (ui.lcskip != lastLcSkip) {
            lastLcSkip = ui.lcskip
            lcToast = true
            kotlinx.coroutines.delay(2200)
            lcToast = false
        }
    }
    var lastCap by remember { mutableIntStateOf(ui.cap) }
    LaunchedEffect(ui.cap) {
        if (ui.cap != lastCap) {
            lastCap = ui.cap
            capToast = true
            kotlinx.coroutines.delay(2200)
            capToast = false
        }
    }

    // 屏幕圆角避让: 左右下角的骰子/水印紧贴底边, 圆角边框会盖住外侧一角。
    // 按系统上报的圆角半径 r 与元素离底距离 b, 横向至少内缩 x = r - sqrt(r²-(r-b)²)。
    val bottomCornerR = rememberBottomCornerRadiusDp()
    val cornerShift = remember(bottomCornerR) {
        val r = bottomCornerR
        val b = 2f
        if (r <= b + 1f) 0.dp else {
            val need = r - kotlin.math.sqrt(r * r - (r - b) * (r - b))
            ((need - 4f).coerceAtLeast(0f) + 2f).dp
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 旧 ScreenFx: 上下渐变暗角 + 四角径向暗角(不挡触摸)
        ScreenVignette()
        // 顶部左右性能信息板(旧 BuildTopBar/p977): 按挖孔位置动态夹在前摄电量环两侧
        val perfDensity = androidx.compose.ui.platform.LocalDensity.current
        val perfCtx = androidx.compose.ui.platform.LocalContext.current
        // 用真实物理宽度: UI 缩放会放大 LocalDensity, 不能再用 screenWidthDp*density 推屏幕像素
        val screenWpx = perfCtx.resources.displayMetrics.widthPixels.toFloat()
        val cutout = androidx.core.view.ViewCompat.getRootWindowInsets(
            androidx.compose.ui.platform.LocalView.current
        )?.displayCutout
        val cutRect = cutout?.boundingRects?.maxByOrNull { it.width() * it.height() }
        val cutWpx = (cutRect?.width() ?: 62).toFloat()
        val cutHpx = (cutRect?.height() ?: 90).toFloat()
        val cutTopPx = (cutRect?.top ?: 0).toFloat()
        val camLeft = (cutRect?.left ?: ((screenWpx - cutWpx) * 0.5f)).toFloat()
        val camRight = (cutRect?.right ?: ((screenWpx + cutWpx) * 0.5f)).toFloat()
        // 旧项目定案: 手动「信息栏高度」(TopBarCenterY) >0 优先, 否则自动 = 挖孔高 * 0.66 (本机=59.4)
        val manualCenterY = TopBarPrefs.centerY
        val camCenterY = if (manualCenterY > 0) manualCenterY.coerceIn(10, 320).toFloat()
                         else cutTopPx + cutHpx * 0.66f
        // 与旧版一致: 圆角安全区 + 挖孔两侧留缝, 左右性能栏同宽
        val sideSafePx = maxOf(60f, cutHpx * 0.9f)
        val gapPx = maxOf(10f, cutWpx * 0.22f)
        val perfPanelWpx = maxOf(80f,
            minOf(camLeft - sideSafePx - gapPx, (screenWpx - sideSafePx) - camRight - gapPx))
        val perfStart = with(perfDensity) { sideSafePx.toDp() }
        val perfPanelW = with(perfDensity) { perfPanelWpx.toDp() }
        val perfTop = with(perfDensity) {
            (camCenterY - EpLayout.infoH.toPx() * 0.5f).coerceAtLeast(0f).toDp()
        }
        InfoPanel(
            ui, left = true, batteryPct = batteryPct, charging = charging, panelW = perfPanelW,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = perfStart, top = perfTop)
        )
        InfoPanel(
            ui, left = false, memPct = memPct, panelW = perfPanelW,
            autoSaveMin = autoSaveMin,
            autoSaveRemain = autoSaveRemain,
            autoSaveSaving = autoSaveSaving,
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(end = perfStart, top = perfTop)
        )
        // 前摄挖孔电量环(旧 BatteryRing): 20 段, 弧长=电量, 红→绿, 充电白段呼吸
        // 环径按旧项目: 控件=挖孔高(90), 可见外径=0.936*90≈84, 内径≈64 (刚好套住 62 的前摄)
        val ringSizePx = (maxOf(48f, cutHpx) * 0.936f)
        val ringSizeDp = with(perfDensity) { ringSizePx.toDp() }
        val ringTopDp = with(perfDensity) { (camCenterY - ringSizePx * 0.5f).toDp() }
        val ringBottomDp = with(perfDensity) { (camCenterY + ringSizePx * 0.5f).toDp() }
        val tabTopDp = ringBottomDp + 2.dp
        val ringOffX = with(perfDensity) { ((camLeft + camRight) * 0.5f - screenWpx * 0.5f).toDp() }

        // 设置菜单打开时: 点画布空白处关闭(放在工具栏/标签栏之前, 不挡后面的按钮)
        if (showSettingsMenu) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = null, indication = null
            ) { showSettingsMenu = false })
        }
        // 工具子菜单/更多工具/颜色菜单/抽屉打开时: 画布点击只负责关闭菜单, 不再落笔
        if (showMore || toolMenu != -1 || colorMenu || SelDrawer.open) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = null, indication = null
            ) {
                showMore = false
                toolMenu = -1
                colorMenu = false
                if (SelDrawer.open) closeDrawerRestore()
            })
        }

        // 时间轴: 遮罩只吃点击、不关闭 —— 只能点时间轴右上角 ✕ 关闭(防误触)
        if (showTimeline) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = null, indication = null
            ) { /* 吃点击, 防止误触画布; 不关闭时间轴 */ })
        }
        // 设置面板: 点空白关闭(底部按钮仍可点)
        if (showSettings) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = null, indication = null
            ) { showSettings = false })
        }

        // 左侧工具栏(6 钮 52u, 垂直居中, 左缘 -6u); 时间轴专注模式/回放时隐藏
        if (!showTimeline) ToolColumn(
            tool = tool,
            brushPx = brushPx,
            eraserPx = eraserPx,
            bucketErase = eraseMode,
            shapeType = shapeType,
            selMode = selMode,
            onTool = { t ->
                ReplayHub.log("切换工具: " + toolLogName(t))
                // [pm143 旧项目] 单指移动中切工具: 立即退出移动模式(更多工具除外)
                if (panMode && t != 5) onMove()
                // 记下当前"更多工具"是否已打开: 下面会先清掉所有浮层, 否则再点一次会被当成重新打开
                val moreWasOpen = showMore
                // 任何工具点击都先关掉其它浮层/子菜单, 再打开新子菜单(同一时间只保留一个)
                showMore = false
                colorMenu = false
                showSettingsMenu = false
                if (SelDrawer.open) closeDrawerRestore()
                if (t == 5) {
                    // 更多工具永远只开/关通用「更多工具」, 不再因框选中/有选区跳到框选子菜单
                    toolMenu = -1
                    showMore = !moreWasOpen
                } else if (tool != t) {
                    // 切到别的工具: 只切换, 不弹子菜单
                    onTool(t)
                    toolMenu = -1
                } else {
                    // 已经选中的工具, 再点一次才开/关它的子菜单
                    toolMenu = if (toolMenu == t) -1 else t
                }
            },
            modifier = Modifier.align(Alignment.CenterStart)
                .padding(start = EpLayout.edge)
                .offset { androidx.compose.ui.unit.IntOffset(0, toolYDp.roundToPx()) }
        )

        // 右侧色板(6–18 色, 垂直居中, 右缘 4u); 图层/设置面板打开/回放时让位
        if (!showTimeline && !showSettings) {
            ColorColumn(
                colors = visPalette, brushColor = brushColor, selectedIdx = paletteSelIdx,
                onColor = { i, c ->
                    paletteSelIdx = i
                    ReplayHub.log(String.format("选择颜色 #%06X", c and 0xFFFFFF))
                    if (panMode) onMove()
                    onColor(c)
                },
                onSelectedTap = {
                    if (panMode) onMove()
                    colorMenu = !colorMenu
                    if (colorMenu) { toolMenu = -1; ReplayHub.log("打开颜色菜单") }
                },
                onLongPress = {
                    colorMenu = false
                    showPaletteDialog = true
                    ReplayHub.log("打开调色板")
                },
                paletteMaxH = rememberPaletteMaxH(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = EpLayout.edge)
                    // 6 色整格盘与工具栏一起上下移动; 18/32 色保持居中
                    .offset(y = ToolPaletteLayout.paletteOffset(palCount, toolYDp))
            )
        }

        // 左侧工具栏子菜单(旧 ShowToolSubmenu)
        if (toolMenu == 4) {
            ShapeSubmenu(
                shapeType = shapeType, filled = shapeFill,
                onType = { id ->
                    ReplayHub.log("图形类型 $id")
                    onShapeType(id)
                    if (id != 4) toolMenu = -1   // 旧项目选完图形即收起子菜单(柳叶留叶宽滑杆)
                },
                onFilled = { f -> ReplayHub.log(if (f) "图形填充" else "图形空心"); onShapeFill(f) },
                ratio = shapeRatio,
                onRatio = { v -> ReplayHub.log(if (v) "比例锁 开" else "比例锁 关"); onShapeRatio(v) },
                leafW = leafW,
                onLeafW = { v -> ReplayHub.log("叶宽 " + v.roundToInt()); onLeafW(v) },
                linePx = shapePx,
                onLinePx = { v -> ReplayHub.log("图形线宽 " + v.roundToInt()); onShapePx(v) },
                onText = {
                    ReplayHub.log("打开添加文字")
                    toolMenu = -1
                    showMore = false
                    SelDrawer.open = true
                    SelDrawer.tab = 2
                },
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = EpLayout.btn + 12.dp)
                    .offset(y = (EpLayout.btn + EpLayout.btnGap) * 0.5f + toolYDp)
            )
        }
        SelectOverlay(
            show = toolMenu == 1,
            mode = selMode,
            onMode = { m ->
                selMode = m
                onSelShape(m)
                onSelMode(m)
            },
            selActive = selActive,
            onSelectAll = onSelectAll,
            onImport = onImportImage,
            onPasteLastCut = onPasteLastCut,
            alphaLock = alphaLock, onAlphaLock = onAlphaLock,
            stretchMode = stretchMode,
            onStretchMode = { on -> onSetStretch(on) },
            allLayers = selAllLayers, onAllLayers = onSelAllLayers,
            allFrames = selAllFrames, onAllFrames = onSelAllFrames,
            onReset = {
                onSelReset()
                onSetStretch(false)
                SelectionPerspectiveHub.set(false)
            },
            onStretch = { showStretch = true },
            onMore = { toolMenu = -1; showMore = true },
            hasLastCut = hasLastCut,
            onToast = { subToast = it },
            onClose = { toolMenu = -1 },
            toolYDp = toolYDp,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        MoreToolsOverlay(
            show = showMore,
            portraitLock = portraitLock, onPortraitLock = onPortraitLock,
            recordOn = recordOn, recordCount = recordInfo?.get(1)?.toIntOrNull() ?: 0,
            onRecord = onRecord,
            onFit = onZoomFit,
            onFlipH = { onFlip(true) },
            onFlipV = { onFlip(false) },
            onGray = onGrayscale,
            onHideColor = { onDeselect(); onCancelPick(); hsvStartMode = 2; showHsv = true },
            onLevels = { showLevels = true },
            minimapOn = minimapOn, onMinimapOn = onMinimapOn,
            refOn = refOn, onRefOn = onRefOn,
            chkOn = overlayChk, onChk = onOverlayChk,
            gridOn = overlayGrid, onGrid = onOverlayGrid,
            noiseOn = noiseMix.on, onNoisePanel = { showNoisePanel = true },
            onText = { SelDrawer.open = true; SelDrawer.tab = 2 },
            onClose = { showMore = false },
            toolYDp = toolYDp,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        if (toolMenu == 3) {
            BucketSubmenu(
                erase = eraseMode,
                onErase = { v -> ReplayHub.log(if (v) "油漆桶 移除像素" else "油漆桶 填充"); onEraseMode(v) },
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = EpLayout.btn + 12.dp)
                    .offset(y = (EpLayout.btn + EpLayout.btnGap) * (-0.5f) + toolYDp)
            )
        }
        if (toolMenu == 0 || toolMenu == 2) {
            val pen = toolMenu == 0
            PenSubmenu(
                isPen = pen, color = brushColor,
                px = if (pen) brushPx else eraserPx,
                maxPx = if (pen) brushMax else eraserMax,
                alpha = brushAlpha,
                onPx = { v ->
                    ReplayHub.log((if (pen) "画笔大小 " else "橡皮大小 ") + v.roundToInt())
                    if (pen) onBrushPx(v) else onEraserPx(v)
                },
                onMaxPx = { m -> ReplayHub.log("笔刷上限 $m"); if (pen) onBrushMax(m) else onEraserMax(m) },
                onAlpha = { a -> ReplayHub.log("透明度 $a%"); onBrushAlpha(a) },
                perfectLine = perfectLine,
                onPerfectLine = { v -> ReplayHub.log(if (v) "完美线条 开" else "完美线条 关"); onPerfectLine(v) },
                alphaLock = alphaLock,
                onAlphaLock = { v -> ReplayHub.log(if (v) "本层蒙版 开" else "本层蒙版 关"); onAlphaLock(v) },
                buildup = buildup,
                onBuildup = { v -> ReplayHub.log(if (v) "叠色 开" else "叠色 关"); onBuildup(v) },
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = EpLayout.btn + 12.dp)
                    .offset(y = (EpLayout.btn + EpLayout.btnGap) * (if (pen) -2.5f else -1.5f) + toolYDp)
            )
        }

        // 旧项目交互: 点当前选中色块 → [取色器] [两色混合] [HSV] 三个按钮(色块左侧)
        val selIdx = paletteSelIdx.coerceIn(0, (visPalette.size - 1).coerceAtLeast(0))
        if (colorMenu && !showHsv) {
            toolMenu = -1
            // 6/18 色菜单跟着选中行居中; 32 色是滚动盘, 菜单固定屏幕中间避免跑到可视区外
            ColorOptionsMenu(
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = EpLayout.swatch + 10.dp)
                    .offset(y = ToolPaletteLayout.colorMenuY(visPalette.size, selIdx)),
                onPick = { colorMenu = false; onDeselect(); onPickRequested(); ReplayHub.log("取色器") },
                onMix = {
                    colorMenu = false
                    onDeselect()
                    if (panMode) onMove()
                    MixPickHub.onStart?.invoke()
                    ReplayHub.log("两色混合")
                },
                onHsv = { colorMenu = false; onDeselect(); onCancelPick(); hsvStartMode = 0; showHsv = true; ReplayHub.log("打开 HSV") },
            )
        }

        // 长按色板: 色格数量(6–60) + 槽位编辑 + 调色板预设管理
        if (showPaletteDialog) {
            PaletteDialog(
                palette = visPalette,
                brushColor = brushColor,
                selectedIdx = paletteSelIdx,
                count = palCount,
                onCount = {
                    val c = it.coerceIn(6, 60)
                    SidePalettePrefs.count.intValue = c
                    PaletteHub.onCountPersist?.invoke(c)
                    ReplayHub.log("色格数量 $c 色")
                },
                onColor = { i, c ->
                    paletteSelIdx = i
                    ReplayHub.log(String.format("调色板取色 #%06X", c and 0xFFFFFF))
                    onColor(c)
                },
                presets = palettePresets,
                onPresetSave = { name ->
                    ReplayHub.log("保存调色板预设 $name")
                    onPresetSave(name)
                },
                onPresetApply = { i -> ReplayHub.log("应用调色板预设 $i"); onPresetApply(i) },
                onPresetDelete = { i -> ReplayHub.log("删除调色板预设 $i"); onPresetDelete(i) },
                onPresetImport = { ReplayHub.log("导入调色板预设"); onPresetImport() },
                onPresetExport = { ReplayHub.log("导出调色板预设"); onPresetExport() },
                onClose = { showPaletteDialog = false },
            )
        }

        PickHintBanner(
            picking = picking,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 230.dp)
        )

        // 测距读数 + 起/中/终标签: 数据来自 60Hz 轻量轮询(measUi), 跟手不卡
        MeasureOverlay(measUi = measUi, measureFixed = measureFixed, measurePending = measurePending)

        if (subToast.isNotEmpty()) {
            LaunchedEffect(subToast) { kotlinx.coroutines.delay(1800); subToast = "" }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 250.dp)
                    .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text(subToast, color = Ep.Text, fontSize = 11.sp) }
        }

        if (lcToast) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 230.dp)
                    .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("选区过大，上次裁剪未缓存（放大后再裁剪）", color = Ep.Text, fontSize = 11.sp) }
        }

        if (mskToast) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 230.dp)
                    .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) { Text("魔棒/套索区域过大，已取消", color = Ep.Text, fontSize = 11.sp) }
        }

        if (capToast) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 230.dp)
                    .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("填充范围过大，已撤销（放大后再试）", color = Ep.Text, fontSize = 11.sp)
            }
        }

        // 选区操作面板(旧 RefreshSelectionUI): 位置来自 60Hz 轻量轮询, offset 只做布局位移
        // 旧 UpdateSelSizeLabel: 蚂蚁线裁剪左下角外侧尺寸标签(宽×高/角度)
        SelectionSizeLabel(selRect = selRect, selFlag = selFlag)
        SelectionOpsPanel(
            selRect = selRect, selFlag = selFlag,
            onCopy = { ReplayHub.log("复制选区"); onCopySel(); subToast = "已复制选区，拖动可放置副本" },
            onCut = { ReplayHub.log("裁剪选区"); onCrop() },
            onDelete = { ReplayHub.log("删除选区"); onDeleteSel() },
            onCancel = { ReplayHub.log("取消选区"); onCancelSel() },
            onSelectAll = { ReplayHub.log("全选"); onSelectAll() },
            onRotate = { ReplayHub.log("旋转选区"); onRotateSel(1) },
            onFlipH = { ReplayHub.log("选区左右翻"); onFlipSel(true) },
            onFlipV = { ReplayHub.log("选区上下翻"); onFlipSel(false) },
            onConfirm = { ReplayHub.log("确认选区"); onConfirmSel() }
        )
        // 方形/圆形渐变: 拖出形状后显示简化预设 + 应用/取消(裁剪状态)
        GradientPanel(Modifier.align(Alignment.BottomCenter))
        if (minimapOn && !showTimeline) {
            MinimapPanel(
                frameIdx = frameIdx, activeLayer = activeLayer, version = thumbRev,
                frameThumb = frameThumb, minimapThumb = minimapThumb, compositeThumb = compositeThumb,
                canvasLabel = ui.canvas,
                scale = minimapScale, xDp = minimapX, yDp = minimapY,
                bgMode = minimapBg, onBgMode = onMinimapBg,
                chkA = canvasStyle.chkA, chkB = canvasStyle.chkB,
                onScale = onMinimapScale, onPos = onMinimapPos,
                onNavigate = onMinimapNavigate, onBounds = onMinimapBounds,
                viewportProvider = minimapViewport
            )
        }
        if (refOn && !showTimeline) {
            ReferencePanel(
                bitmap = refBitmap, scale = refScale, xDp = refX, yDp = refY,
                onScale = onRefScale, onPos = onRefPos, onPickImage = onPickRefImage,
                onBounds = onRefBounds
            )
        }
        // 底部按钮组(预设: 左=设置(含标签)/快捷键/抽屉, 中=单指移动/放大/缩小, 右=时间轴/撤销/重做)
        if (!showTimeline) BottomButtons(
            onTimeline = {
                ReplayHub.log(if (showTimeline) "关闭时间轴" else "打开时间轴")
                setTimelineOpen(!showTimeline); showSettings = false; showSettingsMenu = false
            },
            onSettings = {
                showSettingsMenu = !showSettingsMenu
                showSettings = false; setTimelineOpen(false)
                toolMenu = -1; colorMenu = false; showMore = false
            },
            onDrawer = {
                if (SelDrawer.open) closeDrawerRestore() else {
                    // 抽屉视为子菜单: 与工具子菜单/更多工具/颜色菜单互斥
                    toolMenu = -1; showMore = false; colorMenu = false; showSettingsMenu = false
                    SelDrawer.open = true
                }
            },
            onZoomIn = { ReplayHub.log("放大"); onZoomIn() },
            onZoomOut = { ReplayHub.log("缩小"); onZoomOut() },
            onUndo = { ReplayHub.log("撤销"); onUndo() },
            onRedo = { ReplayHub.log("重做"); onRedo() },
            undoCount = ui.undo, redoCount = ui.redo,
            panMode = panMode, onMove = onMove,
            modifier = Modifier.align(Alignment.BottomStart)
        )

        // 抽屉面板(颜色调整/像素描边): 横向从设置列到时间轴列, 底边压在"单指移动"那一排上面
        if (SelDrawer.open) {
            SelectionDrawer(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = EpLayout.edge, end = EpLayout.edge,
                        bottom = EpLayout.bottomY1 + EpLayout.btn + 6.dp),
                selActive = selActive, brushColor = brushColor, onBrushColor = onColor,
                alphaLock = alphaLock, onAlphaLock = onAlphaLock
            )
        }

        // 旋转/透视手柄画在所有常驻按钮/面板之上; 触摸命中由引擎命中区处理
        SelectionHandles(
            selRect = selRect, selFlag = selFlag,
            stretch = stretchMode && !SelectionPerspectiveHub.on,
            perspective = SelectionPerspectiveHub.on
        )

        // 测距模式面板(旧 Measure.cs): 主动设置起点/中心设置起点/回到手指起点
        if (tool == 4 && shapeType == 6 && !showTimeline) {
            MeasurePanelHost(
                measUi = measUi,
                fixed = measureFixed, pending = measurePending,
                onSetStart = onMeasureSetStart,
                onCenter = onMeasureCenterStart,
                onFinger = onMeasureFingerStart,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)
            )
        }
        // 多边形套索浮条(旧 pm615 顶部浮条): 撤销点/重置点/完成/取消
        if (tool == 4 && shapeType == 7 && !showTimeline) {
            PolyBar(
                verts = polyVerts,
                onUndo = onPolyUndo, onReset = onPolyReset,
                onFinish = onPolyFinish, onCancel = onPolyCancel,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
            )
        }

        if (showSettingsMenu) {
            SettingsMenu(
                onSaveProject = { showSettingsMenu = false; onSaveNow() },
                onExportPng = { showSettingsMenu = false; showExportPngDialog = true },
                onExportWebp = { showSettingsMenu = false; showExportWebpDialog = true },
                onExportProject = { showSettingsMenu = false; onExportProject() },
                onExportCache = { showSettingsMenu = false; ProjectCacheHub.saveActive { } },
                onExportPsd = { showSettingsMenu = false; onExportPsd() },
                onExportReplay = { showSettingsMenu = false; showProcessDialog = true },
                onLoadFile = { showSettingsMenu = false; onLoadFile() },
                onCreateCanvas = { showSettingsMenu = false; StudioHub.mode = 0 },
                onResizeCanvas = { showSettingsMenu = false; StudioHub.mode = 1 },
                onAdjustSettings = { showSettingsMenu = false; showSettings = true },
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = EpLayout.edge + EpLayout.btn + 6.dp, bottom = EpLayout.bottomY2)
            )
        }

        if (showTimeline) {
            TimelinePanel2(
                layers = layers, active = activeLayer,
                frameIdx = frameIdx, frameCount = frameCount, playing = playing,
                speed = playSpeed, visBits = visBits, lckBits = lckBits,
                loopStart = (loopInfo?.get(1)?.toIntOrNull() ?: 1) - 1,
                loopEnd = (loopInfo?.get(2)?.toIntOrNull() ?: 0) - 1,
                onion = onionOn, playBrush = playBrush,
                frameThumb = frameThumb, thumbRev = thumbRev,
                layerMeta = layerMeta,
                onFrameOp = { op, arg -> ReplayHub.log("帧操作 $op/$arg"); onFrameOp(op, arg) },
                onLayerOp = { op, arg -> ReplayHub.log("图层操作 $op/$arg"); onLayerOp(op, arg) },
                onRenameLayer = onRenameLayer, onLayerOpacity = onLayerOpacity,
                onLoopRange = onLoopRange,
                onReorderFrame = onReorderFrame, onSwapFrames = onSwapFrames,
                onReorderLayer = onReorderLayer,
                onOnion = { v -> ReplayHub.log(if (v) "洋葱皮 开" else "洋葱皮 关"); onOnion(v) },
                onPlayBrush = { v -> ReplayHub.log(if (v) "播放画笔 开" else "播放画笔 关"); onPlayBrush(v) },
                onClose = { setTimelineOpen(false) },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (showSettings) {
            AdjustSettingsDialog(
                versionName = versionName,
                canvasLabel = ui.canvas,
                chkOn = overlayChk, gridOn = overlayGrid, portraitLock = portraitLock,
                projectFolder = projectFolder,
                onPickProjectFolder = onPickProjectFolder,
                onClearProjectFolder = onClearProjectFolder,
                autoSaveMin = autoSaveMin, recordOn = recordOn,
                onChk = onOverlayChk, onGrid = onOverlayGrid,
                onPortraitLock = onPortraitLock,
                onAutoSaveMin = onAutoSaveMin, onRecord = onRecord,
                perfectLine = perfectLine, onPerfectLine = onPerfectLine,
                buildup = buildup, onBuildup = onBuildup,
                alphaLock = alphaLock, onAlphaLock = onAlphaLock,
                eraseMode = eraseMode, onEraseMode = onEraseMode,
                pinchUndoMs = pinchUndoMs, onPinchUndoMs = onPinchUndoMs,
                onClearCanvas = onClearCanvas, onResizeCanvas = onResizeCanvas, onSaveNow = onSaveNow,
                onZoomFit = onZoomFit,
                onClose = { showSettings = false },
                thumb = compositeThumb(),
                watermark = watermark, onWatermark = onWatermark,
                sigIcon = sigIcon, onPickSigIcon = onPickSigIcon, onClearSigIcon = onClearSigIcon,
                canvasStyle = canvasStyle, onCanvasStyle = onCanvasStyle,
                uiScalePct = uiScalePct, onUiScalePct = onUiScalePct,
                pngCompress = pngCompress, onPngCompress = onPngCompress,
                toolYOffset = toolYOffset, onToolYOffset = onToolYOffset,
                sfxOn = sfxOn, onSfxOn = onSfxOn,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (StudioHub.mode >= 0) {
            val dims = ui.canvas.split("×").mapNotNull { it.toIntOrNull() }
            // 调整大小预览: 打开时现生成 192² 合成缩略图, 失败回退 48²
            val rt = remember(StudioHub.mode) { if (StudioHub.mode == 1) resizeThumb() else IntArray(0) }
            CanvasStudioDialog(
                initialMode = StudioMode.of(StudioHub.mode),
                defaultW = dims.getOrNull(0) ?: 512,
                defaultH = dims.getOrNull(1) ?: 512,
                currentW = dims.getOrNull(0) ?: 512,
                currentH = dims.getOrNull(1) ?: 512,
                resizeThumb = if (rt.size >= 64) rt else compositeThumb(),
                templateRev = templateRev,
                onDismiss = { StudioHub.mode = -1 },
                onCreateCanvas = { w, h -> StudioHub.mode = -1; onTabAdd(w, h) },
                onResizeCanvas = { w, h, ax, ay -> StudioHub.mode = -1; onResizeCanvas(w, h, ax, ay) },
                onPickTemplate = { id -> StudioHub.mode = -1; onPickTemplate(id) },
                onImportTemplate = onImportTemplate,
                onExportTemplate = onExportTemplate,
                onDeleteTemplate = onDeleteTemplate
            )
        }
        if (showNoisePanel) {
            NoisePanel(mix = noiseMix, onChange = onNoiseMix, onClose = { showNoisePanel = false })
        }
        if (showProcessDialog) {
            ProcessDialog(
                recordOn = recordOn,
                frameCount = maxOf(recordedFrames, recordInfo?.get(1)?.toIntOrNull() ?: 0),
                onToggleRecord = { on -> onRecord(on) },
                onSave = { showProcessDialog = false; onExportReplay() },
                onPreview = {
                    showProcessDialog = false
                    ReplayHub.previewSink?.invoke()
                },
                onClose = { showProcessDialog = false }
            )
        }
        if (showLevels) {
            LevelsDialog(
                onHistogram = onLevelsHistogram,
                onPreview = onPreviewLevels,
                onApplyAll = onApplyLevelsAll,
                onDismiss = { showLevels = false }
            )
        }
        if (showExportPngDialog) {
            val dims = ui.canvas.split("×").mapNotNull { it.toIntOrNull() }
            val px = remember { exportThumb() }
            ExportPngDialog(
                currentW = dims.getOrNull(0) ?: 512,
                currentH = dims.getOrNull(1) ?: 512,
                thumb = if (px.isNotEmpty()) px else (tabThumb(activeTab) ?: IntArray(0)),
                onDismiss = { showExportPngDialog = false },
                onExport = { scale ->
                    showExportPngDialog = false
                    onExportPng(scale)
                }
            )
        }
        if (showExportWebpDialog) {
            val dims = ui.canvas.split("×").mapNotNull { it.toIntOrNull() }
            val px = remember { exportThumb() }
            ExportPngDialog(
                currentW = dims.getOrNull(0) ?: 512,
                currentH = dims.getOrNull(1) ?: 512,
                thumb = if (px.isNotEmpty()) px else (tabThumb(activeTab) ?: IntArray(0)),
                onDismiss = { showExportWebpDialog = false },
                onExport = { scale ->
                    showExportWebpDialog = false
                    onExportWebp(scale)
                },
                format = "WebP",
                allowMultiModes = false
            )
        }
        // 旧项目 HSV 对话框(全屏遮罩+居中面板): 方块/色环 + H/S/V/A + HEX + 取消/确认
        if (showStretch) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                StretchDialog(
                    onApply = { w, h -> ReplayHub.log("拉伸选区 ${w}×${h}"); onStretch(w, h) },
                    onClose = { showStretch = false },
                    onRotate = { q -> ReplayHub.log("旋转选区 ${q * 90}°"); onRotateSel(q) },
                    onFlip = { h -> ReplayHub.log(if (h) "选区左右翻" else "选区上下翻"); onFlipSel(h) }
                )
            }
        }
        if (showHsv) {
            HsvDialog(
                current = (brushColor and 0x00FFFFFF) or (brushAlpha shl 24),
                onColor = { c -> ReplayHub.log("HSV 调色"); onHsvColor(c) },
                onClose = { showHsv = false },
                startMode = hsvStartMode
            )
        }

        // 回放层: 盖住画布区域(编辑 UI 都在它下面), 标签栏再盖在最上面
        if (replayOn) {
            ReplayLayer(ui.canvas)
        }
        // 浏览器层(常驻虚拟标签): 同样盖住画布/编辑 UI, 标签栏保持在最上面
        if (browserOn) BrowserScreen()

        // 顶部画布标签栏(旧 BuildTabBar 适配); 更多工具「标签栏」可隐藏(默认显示)
        if (!showSettings && !showTimeline && !showHsv && tabs.isNotEmpty() && TabBarPrefs.visible) {
            CanvasTabBar(
                tabs = tabs.map { UiTab(it.first, it.second) },
                activeId = if (replayOn || browserOn) -1 else activeTab,
                defaultW = ui.canvas.substringBefore("×").toIntOrNull() ?: 512,
                defaultH = ui.canvas.substringAfter("×").toIntOrNull() ?: 512,
                // 当前标签用引擎实时合成缩略图(不靠磁盘 sidecar, 画完 2.5s 内就更新);
                // 其它标签用保存时写的合成缩略图
                tabThumb = { id ->
                    if (id == activeTab) compositeThumb().takeIf { it.size >= 48 * 48 } ?: tabThumb(id)
                    else tabThumb(id)
                },
                tabSize = tabSize,
                onSelect = { id ->
                    ReplayHub.log("切换标签 $id")
                    ReplayHub.visible = false
                    BrowserHub.close()
                    onTabSelect(id)
                },
                onAdd = { w, h ->
                    ReplayHub.log("新建标签")
                    ReplayHub.visible = false
                    BrowserHub.close()
                    onTabAdd(w, h)
                },
                onClose = { },
                replayAvailable = ReplayHub.session != null,
                replaySelected = replayOn,
                onReplaySelect = { BrowserHub.close(); ReplayHub.visible = true },
                onReplayClose = { ReplayHub.close() },
                browserSelected = browserOn,
                onBrowserSelect = { BrowserHub.open() },
                onRename = { id, n -> ReplayHub.log("重命名标签 $n"); onTabRename(id, n) },
                onLoadProject = onLoadProject,
                onLoadFile = onLoadFile,
                onNewCanvas = {
                    ReplayHub.log("新建画布")
                    ReplayHub.visible = false
                    BrowserHub.close()
                    StudioHub.mode = 0
                },
                onTemplate = {
                    ReplayHub.log("模板库")
                    ReplayHub.visible = false
                    BrowserHub.close()
                    StudioHub.mode = 2
                },
                modifier = Modifier.align(Alignment.TopCenter).offset(y = tabTopDp)
            )
        }
        // 关闭画布标签: 关闭提示 + 导出 / 保留缓存(完整快照)
        CloseTabDialogHost()
        // 自动检查更新提示(GitHub Releases, 不走 endpix.com)
        UpdatePromptHost()
        // 电量环放在最上层: 模板/参考图等浮层拖到顶部时不能把它盖成半个"C"
        CameraBatteryRing(            pct = batteryPct, charging = charging, ringSize = ringSizeDp,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = ringOffX, y = ringTopDp)
        )

    }
}

private class UiStats(
    val canvas: String, val layers: String, val zoom: String,
    val fps: String, val comp: String, val mem: String, val cap: Int, val lcskip: Int,
    val wskip: Int = 0, val frIdx: Int = 1, val frCount: Int = 1,
    val undo: Int = 0, val redo: Int = 0,
    val cv: IntArray = IntArray(0),
)

private fun parseStats(s: String): UiStats {
    fun g(r: Regex): String? = r.find(s)?.groupValues?.get(1)
    val dims = RE_CANVAS_DIMS.find(s)
    val layerN = g(RE_LAYER_N) ?: "?"
    val active = g(RE_ACTIVE_LAYER) ?: "0"
    val zoomRaw = g(RE_ZOOM)?.toFloatOrNull() ?: 1f
    return UiStats(
        canvas = if (dims != null) "${dims.groupValues[1]}×${dims.groupValues[2]}" else "--",
        layers = "层${(active.toIntOrNull() ?: 0) + 1}/${layerN}",
        zoom = "${(zoomRaw * 100).toInt()}%",
        fps = (g(RE_FPS) ?: "--") + "fps",
        comp = g(RE_COMP) ?: "--",
        mem = g(RE_RSS) ?: "--",
        cap = g(RE_CAP)?.toIntOrNull() ?: 0,
        lcskip = g(RE_LCSKIP)?.toIntOrNull() ?: 0,
        wskip = g(RE_WSKIP)?.toIntOrNull() ?: 0,
        frIdx = g(RE_FR_IDX)?.toIntOrNull() ?: 1,
        frCount = g(RE_FR_COUNT)?.toIntOrNull() ?: 1,
        undo = g(RE_UNDO)?.toIntOrNull() ?: 0,
        redo = g(RE_REDO)?.toIntOrNull() ?: 0,
        cv = RE_CV.find(s)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() }?.toIntArray()
            ?: IntArray(0),
    )
}

@Composable
private fun InfoPanel(
    ui: UiStats, left: Boolean, modifier: Modifier = Modifier,
    batteryPct: Int = -1, charging: Boolean = false, memPct: Int = -1,
    panelW: androidx.compose.ui.unit.Dp = EpLayout.infoW,
    autoSaveMin: Int = 0, autoSaveRemain: Int = -1, autoSaveSaving: Boolean = false,
) {
    if (ReplayHub.visible) return
    if (TopBarPrefs.hidePerf) return
    val time = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())   // 旧 pm1067: 只显示到分
        while (true) {
            time.value = fmt.format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }
    // 旧 pm1041: 对角淡化背景(靠摄像头一侧实, 外侧渐隐); 左栏右实左透, 右栏左实右透
    val grad = Modifier.drawBehind {
        val solid = Color.Black.copy(alpha = 0.62f)
        val clear = Color.Transparent
        val brush = if (left)
            Brush.linearGradient(listOf(clear, solid), Offset(0f, 0f), Offset(size.width, size.height))
        else
            Brush.linearGradient(listOf(solid, clear), Offset(0f, 0f), Offset(size.width, size.height))
        drawRect(brush)
    }
    // 旧 pm1053: 三行手工定位(行高 10u≈7.8dp, 字号 11u), 关掉等距布局避免第三行被顶出背景
    val memTxt = if (memPct in 0..100) "内存:${memPct}%" else "内存:--"
    val saveTxt = when {
        autoSaveMin <= 0 -> "自动保存 关"
        autoSaveSaving -> "自动保存 保存中"
        autoSaveRemain < 0 -> "自动保存 --:--"
        else -> "自动保存 ${autoSaveRemain / 60}:${(autoSaveRemain % 60).toString().padStart(2, '0')}"
    }
    val lines = if (left)
        listOf(ui.canvas, "帧${ui.frIdx}/${ui.frCount} ${ui.layers} ${ui.zoom}", time.value)
    else
        listOf("${ui.fps} $memTxt", "合成:${ui.comp}ms 正常", saveTxt)
    Box(modifier.width(panelW).height(EpLayout.infoH).then(grad)) {
        lines.forEachIndexed { i, txt ->
            Text(
                txt, color = Color.White, fontSize = 9.sp, lineHeight = 9.sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.fillMaxWidth().offset(y = (i * 8.0f).dp)
                    .padding(horizontal = 6.dp),
                textAlign = if (left) androidx.compose.ui.text.style.TextAlign.End
                else androidx.compose.ui.text.style.TextAlign.Start
            )
        }
    }
}

// 电量色: 0%=红, 100%=绿(旧环 red→green)
private fun batteryColor(pct: Int): Color {
    val p = (pct.coerceIn(0, 100)) / 100f
    return Color.hsv(p * 120f, 0.78f, 0.95f)
}

// 性能栏左缘电量竖条(旧 pm649): 轨道+填充, 满高=栏高
@Composable
private fun BatteryStrip(pct: Int, charging: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = w / 2f
        drawRoundRect(Color(0xFF30363D), topLeft = Offset(0f, 0f), size = Size(w, h),
            cornerRadius = CornerRadius(r, r))
        if (pct >= 0) {
            val fh = (h * pct / 100f).coerceAtLeast(if (pct > 0) r * 2 else 0f)
            drawRoundRect(batteryColor(pct), topLeft = Offset(0f, h - fh), size = Size(w, fh),
                cornerRadius = CornerRadius(r, r))
        }
        if (charging) {
            drawRoundRect(Color.White.copy(alpha = 0.55f), topLeft = Offset(0f, 0f),
                size = Size(w, h), cornerRadius = CornerRadius(r, r), style = Stroke(1f))
        }
    }
}

// 前摄挖孔电量环(旧 BatteryRing): 20 段等分, 段间留缝; 充电时下一格白色呼吸
@Composable
private fun CameraBatteryRing(pct: Int, charging: Boolean, ringSize: androidx.compose.ui.unit.Dp = 34.3.dp, modifier: Modifier = Modifier) {
    if (ReplayHub.visible) return
    if (TopBarPrefs.hideBattery) return
    val segs = 20
    val lit = if (pct < 0) 0 else (pct * segs / 100).coerceIn(0, segs)
    // 旧项目 BatteryRing.UpdateChargeSeg: 白色"下一格"按 unscaledTime*3.2 正弦呼吸, alpha 0.3..0.85。
    // 系统"动画缩放=0"时 Compose 动画会停住, 所以用逐帧时钟自己算(不依赖系统动画开关)。
    var pulse by remember { mutableFloatStateOf(0.575f) }
    LaunchedEffect(charging, pct) {
        if (!charging || pct <= 0 || pct >= 100) return@LaunchedEffect
        val t0 = System.nanoTime()
        while (true) {
            withFrameNanos { }
            val t = (System.nanoTime() - t0) / 1_000_000_000.0
            pulse = (0.3 + 0.55 * (0.5 + 0.5 * kotlin.math.sin(t * 3.2))).toFloat()
        }
    }
    val pulseAlpha = pulse   // 在组合期读取, 保证每帧重组重绘
    Canvas(modifier.size(ringSize)) {
        // 旧项目纹理比例: 可见外径 0.936 / 内径 0.71, 环厚≈外径 0.12;
        // 段长 = 每段弧长 * 0.37 (单边) → 段间留缝 26% (18° 里 13.32° 实心 + 4.68° 缝)
        val stroke = size.minDimension * 0.119f
        val rMid = (size.minDimension - stroke) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val segDeg = 360f / segs
        val segArc = (Math.PI * 2.0 / segs).toFloat()
        val halfLen = segArc * 0.37f * rMid
        val corner = CornerRadius(minOf(halfLen, stroke / 2f) * 0.8f)
        for (i in 0 until segs) {
            // 旧项目: 0 号段从正上方逆时针排布, 圆角短段(胶囊形)
            val deg = -(i + 0.5f) * segDeg
            rotate(deg, pivot = c) {
                val tl = Offset(c.x - halfLen, c.y - rMid - stroke / 2f)
                val sz = Size(halfLen * 2f, stroke)
                drawRoundRect(Color(0xFF30363D), topLeft = tl, size = sz, cornerRadius = corner)
                if (i < lit) drawRoundRect(batteryColor(pct), topLeft = tl, size = sz, cornerRadius = corner)
            }
        }
        if (charging && pct > 0 && lit < segs) {
            // 充电: 下一格白色呼吸(旧 pm955), 叠在深灰段上
            val deg = -(lit + 0.5f) * segDeg
            rotate(deg, pivot = c) {
                val tl = Offset(c.x - halfLen, c.y - rMid - stroke / 2f)
                drawRoundRect(Color.White.copy(alpha = pulseAlpha), topLeft = tl,
                    size = Size(halfLen * 2f, stroke), cornerRadius = corner)
            }
        }
    }
}

// 旧选区操作面板: 4/6 方钮, 贴选区右下角; 位置读取走 offset{} 布局阶段, 不触发重组
@Composable
private fun MeasureTag(x: Int, y: Int, text: String) {
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x, y) }
        .background(Color(0xE6161B22), RoundedCornerShape(2.dp))
        .border(1.dp, Ep.Line, RoundedCornerShape(2.dp))
        .padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(text, color = Ep.Text, fontSize = 8.sp, lineHeight = 10.sp)
    }
}

// 测距读数 + 起/中/终三点标签: 只在这个子 Composable 里读 60Hz 状态,
// 重组范围被限制在这块, 不会带着整个界面一起重组(之前挂在 500ms stats 上所以"跟手太慢")。
@Composable
private fun BoxScope.MeasureOverlay(
    measUi: androidx.compose.runtime.State<IntArray>,
    measureFixed: Boolean, measurePending: Boolean,
) {
    if (ReplayHub.visible) return
    val v = measUi.value
    if (v.size < 12 || v[0] == 0) return
    val mx0 = v[1]; val my0 = v[2]; val mx1 = v[3]; val my1 = v[4]
    val dragging = v[5] == 1
    val sx0 = v[8]; val sy0 = v[9]; val sx1 = v[10]; val sy1 = v[11]
    val dx = mx1 - mx0; val dy = my1 - my0
    val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
    // 旧 pm141: 上=0°, 右=+90° (atan2(dx,dy))
    val ang = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble()))
    val hx = Math.round(dx * 0.5f)
    val hy = Math.round(dy * 0.5f)
    val mode = if (measureFixed) "固" else "指"
    val midPart = if (measureFixed) "" else
        " 中(${if (hx >= 0) "+$hx" else "$hx"},${if (hy >= 0) "+$hy" else "$hy"})"
    val pendPart = if (measurePending) " 设起点中" else ""
    Box(
        Modifier.align(Alignment.TopStart).padding(start = EpLayout.infoX, top = 52.dp)
            .background(Color(0xE6161B22), RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            "[$mode]$pendPart 起($mx0,$my0) 终($mx1,$my1)$midPart\n" +
                "Δx${if (dx >= 0) "+$dx" else "$dx"} Δy${if (dy >= 0) "+$dy" else "$dy"} " +
                "斜${String.format("%.1f", dist)}px ${String.format("%.1f", ang)}°(上0°)",
            color = Ep.Text, fontSize = 10.sp
        )
    }
    if (dragging) return   // 拖动中只显示红线, 松手后才显示三点标签(旧 pm134)
    val midX = (mx0 + mx1) / 2; val midY = (my0 + my1) / 2
    val angle = kotlin.math.round(Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dy.toDouble()))).toInt()
    val midSx = (sx0 + sx1) / 2; val midSy = (sy0 + sy1) / 2
    MeasureTag((sx0 - 120).coerceAtLeast(2), (sy0 - 52).coerceAtLeast(2),
        "起点\n画布 $mx0,$my0\n测距 0,0")
    MeasureTag((sx1 + 8).coerceAtMost(700), (sy1 + 6).coerceAtLeast(2),
        "终点\n画布 $mx1,$my1\n测距 ${if (dx >= 0) "+$dx" else "$dx"},${if (dy >= 0) "+$dy" else "$dy"}\n$angle°")
    if (!measureFixed) MeasureTag((midSx - 60).coerceAtLeast(2), (midSy + 10).coerceAtLeast(2),
        "中心\n画布 $midX,$midY\n测距 ${if (hx >= 0) "+$hx" else "$hx"},${if (hy >= 0) "+$hy" else "$hy"}")
}

@Composable
private fun MeasurePanelHost(
    measUi: androidx.compose.runtime.State<IntArray>,
    fixed: Boolean, pending: Boolean,
    onSetStart: () -> Unit, onCenter: () -> Unit, onFinger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ReplayHub.visible) return
    MeasurePanel(
        fixed = fixed, pending = pending, hasPoints = measUi.value.getOrNull(0) == 1,
        onSetStart = onSetStart, onCenter = onCenter, onFinger = onFinger,
        modifier = modifier
    )
}

@Composable
private fun SelectionOpsPanel(
    selRect: androidx.compose.runtime.State<IntArray>,
    selFlag: androidx.compose.runtime.State<Int>,
    onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit,
    onRotate: () -> Unit, onFlipH: () -> Unit, onFlipV: () -> Unit,
    onConfirm: () -> Unit, onSelectAll: () -> Unit,
) {
    if (ReplayHub.visible) return
    val flag = selFlag.value
    if ((flag and 1) == 0) return
    val floating = (flag and 2) != 0
    val view = LocalView.current
    var lastFloat by remember { mutableStateOf(false) }
    LaunchedEffect(floating) {
        if (floating && !lastFloat) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        lastFloat = floating
    }
    val d = androidx.compose.ui.platform.LocalDensity.current.density
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val swPx = cfg.screenWidthDp * d
    val shPx = cfg.screenHeightDp * d
    val bs = 44.dp
    val gap = 4.dp
    val n = if (floating) 5 else 4
    val panelWpx = 44 * d
    val panelHpx = (44 * n + 4 * (n - 1)) * d
    // 可放置范围: 左工具栏右侧 ~ 右色板左侧, 上方留性能栏, 下方留底部按钮区, 防止压住按钮
    val leftLimit = (3 + 41 + 6) * d
    val rightLimit = swPx - (3 + 44 + 6) * d
    val bottomLimit = shPx - (100 + 41 + 10) * d
    Column(
        Modifier.offset {
                val v = selRect.value
                val sx0 = v[0].toFloat(); val sy1 = v[3].toFloat()
                val sx1 = v[2].toFloat()
                var x = sx1 + 6 * d
                if (x + panelWpx > rightLimit) x = sx0 - 6 * d - panelWpx
                x = x.coerceIn(leftLimit, maxOf(leftLimit, rightLimit - panelWpx))
                val y = (sy1 - panelHpx).coerceIn(40 * d, maxOf(40 * d, bottomLimit - panelHpx))
                androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
            }
        ,
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        val items: List<Pair<Int, () -> Unit>> = if (!floating)
            listOf(34 to onCopy, 32 to onCut, 35 to onDelete, 36 to onCancel)
        else
            listOf(
                33 to onRotate, 38 to onFlipH, 39 to onFlipV, 40 to onConfirm,
                36 to onCancel
            )
        items.forEach { (icon, cb) ->
            val base = Modifier.size(bs)
                .background(Ep.Panel2, RoundedCornerShape(3.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            // 旧项目: 剪切按钮短按=剪切, 长按1秒=全选画布
            val tapMod = if (icon == 32) Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(1000) { waitForUpOrCancellation() }
                    if (up == null) {
                        onSelectAll()
                    } else {
                        down.consume()
                        cb()
                    }
                }
            } else Modifier.clickable(interactionSource = null, indication = null) { cb() }
            Box(base.then(tapMod), contentAlignment = Alignment.Center) {
                EpIcon(icon, Ep.Text, size = 24.dp)
            }
        }
    }
    // 裁剪导出: 默认范围=裁剪目标(宽高/pixels 由 MainActivity 从 lastCut 读取)
    if (com.endpix.nativeapp.ui.CropExportHub.exportOpen) {
        ExportPngDialog(
            currentW = com.endpix.nativeapp.ui.CropExportHub.w,
            currentH = com.endpix.nativeapp.ui.CropExportHub.h,
            thumb = com.endpix.nativeapp.ui.CropExportHub.thumb,
            onDismiss = { com.endpix.nativeapp.ui.CropExportHub.exportOpen = false },
            onExport = { scale -> com.endpix.nativeapp.ui.CropExportHub.startExport(scale) },
            allowMultiModes = false
        )
    }
}

// 方形/圆形渐变: 裁剪状态下的预设面板 + 角度/透明位置/范围
@Composable
private fun GradientPanel(modifier: Modifier = Modifier) {
    if (ReplayHub.visible) return
    if (!GradHub.active) return
    val shape = GradHub.shape
    val preset = GradHub.preset
    val n = if (shape == 0) 4 else 2
    Column(
        modifier.padding(bottom = 148.dp).width(198.dp)
            .background(Ep.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            // 面板自身吃掉触摸, 否则点在按钮外会漏到画布上(会取消预览/重开一笔)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (shape == 0) "方形渐变" else "圆形渐变",
                color = Ep.Text, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            Text("取消", color = Ep.Sub, fontSize = 11.sp,
                modifier = Modifier
                    .clickable(interactionSource = null, indication = null) { GradHub.onCancel() }
                    .padding(horizontal = 9.dp, vertical = 8.dp))
            Spacer(Modifier.width(4.dp))
            Text("应用", color = Ep.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(interactionSource = null, indication = null) { GradHub.onApply() }
                    .padding(horizontal = 9.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until n) GradientChip(shape, i, i == preset) { GradHub.onPreset(i) }
        }
        Spacer(Modifier.height(2.dp))
        // 方形渐变角度: 任意旋转(0=→ 90=↓ 180=← 270=↑); 圆形不需要
        if (shape == 0) {
            SubSlider("角度", (GradHub.angle / 359f).coerceIn(0f, 1f), "${GradHub.angle}°",
                { f ->
                    val v = (f * 359f).roundToInt().coerceIn(0, 359)
                    if (v != GradHub.angle) {
                        GradHub.angle = v
                        GradHub.onAngle(v)
                    }
                }, gestureKey = 359) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
            }
        }
        // 透明位置: 淡出起点沿渐变轴移动(0=实色端开始, 50=从中间开始透明)
        SubSlider("位置", (GradHub.pos / 100f).coerceIn(0f, 1f), "${GradHub.pos}%",
            { f ->
                val v = (f * 100f).roundToInt().coerceIn(0, 100)
                if (v != GradHub.pos) {
                    GradHub.pos = v
                    GradHub.onPos(v)
                }
            }, gestureKey = 100) {
            drawRect(Brush.horizontalGradient(listOf(Ep.Accent, Ep.Panel2)))
        }
        // 渐变范围: 过渡带占剩余路径的比例(实色起点不动, 只改淡出长度)
        SubSlider("范围", ((GradHub.range - 5) / 95f).coerceIn(0f, 1f), "${GradHub.range}%",
            { f ->
                val v = (5f + f * 95f).roundToInt().coerceIn(5, 100)
                if (v != GradHub.range) {
                    GradHub.range = v
                    GradHub.onRange(v)
                }
            }) {
            drawRect(Color(0xFF30363D))
            drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
        }
    }
}

@Composable
private fun GradientChip(shape: Int, preset: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp, 26.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF14181E))
            .border(if (selected) 1.5.dp else 1.dp,
                if (selected) Ep.Accent else Ep.Line, RoundedCornerShape(2.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val light = Color(0xFFE9EDF3)
            val dark = Color(0xFF151A20)
            if (shape == 0) {
                val (s, e) = when (preset) {
                    1 -> Offset(size.width * 0.5f, size.height) to Offset(size.width * 0.5f, 0f)
                    2 -> Offset(0f, size.height * 0.5f) to Offset(size.width, size.height * 0.5f)
                    3 -> Offset(size.width, size.height * 0.5f) to Offset(0f, size.height * 0.5f)
                    else -> Offset(size.width * 0.5f, 0f) to Offset(size.width * 0.5f, size.height)
                }
                drawRect(Brush.linearGradient(listOf(light, dark), s, e))
            } else {
                val c = Offset(size.width * 0.5f, size.height * 0.5f)
                val r = minOf(size.width, size.height) * 0.5f
                drawRect(dark)
                val cols = if (preset == 1) listOf(dark, light) else listOf(light, dark)
                drawCircle(Brush.radialGradient(cols, c, r), radius = r, center = c)
            }
        }
    }
}

// 旧 UpdateSelSizeLabel: 选区左下角外侧显示"宽×高"(旋转时追加角度), 黑底半透明/白字
@Composable
private fun SelectionSizeLabel(
    selRect: androidx.compose.runtime.State<IntArray>,
    selFlag: androidx.compose.runtime.State<Int>,
) {
    if (ReplayHub.visible) return
    if ((selFlag.value and 1) == 0) return
    val v = selRect.value
    if (v.size < 18) return
    val cw = v[15]; val ch = v[16]
    if (cw <= 0 || ch <= 0) return
    val view = androidx.compose.ui.platform.LocalView.current
    val sw = view.width.toFloat(); val sh = view.height.toFloat()
    if (sw <= 0f || sh <= 0f) return
    // 选区完全出屏时隐藏(旧 pm1112)
    if (v[2] < 0 || v[0] > sw || v[3] < 0 || v[1] > sh) return
    val rot = v[17]
    val text = if (rot % 360 != 0) "$cw×$ch $rot°" else "$cw×$ch"
    // 蚂蚁线始终画在选区包围盒上(引擎 setSelection 用的是 x0..y1 包围盒, 旋转时也不跟着转),
    // 所以尺寸标签优先贴蚂蚁线左下角 = 包围盒左下角(v[0],v[3]); 旋转时不再跟旋转角点跑。
    val bx = v[0].toFloat()
    val by = v[3].toFloat()
    val d = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(d) { 4.dp.toPx() }
    // 只用于贴边夹紧的估算宽度; 背景板本身按文字实际宽度自适应, 不再固定宽度裁掉末位数字/角度
    val lblW = with(d) { (text.length * 9f + 16).dp.toPx() }
    val lblH = with(d) { 20.dp.toPx() }
    var lx = bx
    var ly = by + margin   // 选区左下角下方
    if (lx + lblW > sw - margin) lx = sw - margin - lblW
    if (lx < margin) lx = margin
    if (ly + lblH > sh - margin) ly = sh - margin - lblH
    if (ly < margin) ly = margin
    Box(
        Modifier.offset { androidx.compose.ui.unit.IntOffset(lx.toInt(), ly.toInt()) }
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp,
            maxLines = 1, softWrap = false
        )
    }
}

// 变换手柄: 拉伸旋转=四角+边中点+旋转柄; 透视变形=四角自由拖 + 四边形轮廓
@Composable
private fun SelectionHandles(
    selRect: androidx.compose.runtime.State<IntArray>,
    selFlag: androidx.compose.runtime.State<Int>,
    stretch: Boolean,
    perspective: Boolean = false,
) {
    if (ReplayHub.visible) return
    if (!stretch && !perspective) return
    val flag = selFlag.value
    if ((flag and 3) != 3) return
    val v = selRect.value
    if (v.size < 15) return
    Canvas(Modifier.fillMaxSize()) {
        val d = this.density
        val hs = 6.dp.toPx()
        if (perspective) {
            // 四边形轮廓 + 四角独立手柄; 不画旋转柄/边中点
            val px = FloatArray(8) { v[5 + it].toFloat() }
            val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0)
            for (e in 0 until 4) {
                val a = edges[e * 2] * 2
                val b = edges[e * 2 + 1] * 2
                drawLine(
                    Ep.Accent, Offset(px[a], px[a + 1]), Offset(px[b], px[b + 1]),
                    strokeWidth = 2f * d
                )
            }
            for (i in 0..3) {
                val cx = px[i * 2]; val cy = px[i * 2 + 1]
                drawRect(
                    Color.White, Offset(cx - hs, cy - hs), Size(hs * 2, hs * 2),
                    style = Stroke(2f * d)
                )
                drawRect(
                    Ep.Accent, Offset(cx - hs * 0.45f, cy - hs * 0.45f),
                    Size(hs * 0.9f, hs * 0.9f)
                )
            }
            return@Canvas
        }
        val topMidX = (v[5] + v[7]) * 0.5f
        val topMidY = (v[6] + v[8]) * 0.5f
        drawLine(Color.White.copy(alpha = 0.8f), Offset(topMidX, topMidY), Offset(v[13].toFloat(), v[14].toFloat()), strokeWidth = 1.5f * d)
        for (i in 0..3) {
            val cx = v[5 + i * 2].toFloat(); val cy = v[6 + i * 2].toFloat()
            drawRect(Color.White, Offset(cx - hs, cy - hs), Size(hs * 2, hs * 2), style = Stroke(2f * d))
        }
        // 四条边中点: 单轴拉伸
        val mh = hs * 0.8f
        val mids = listOf(
            Pair((v[5] + v[7]) * 0.5f, (v[6] + v[8]) * 0.5f),
            Pair((v[7] + v[11]) * 0.5f, (v[8] + v[12]) * 0.5f),
            Pair((v[9] + v[11]) * 0.5f, (v[10] + v[12]) * 0.5f),
            Pair((v[5] + v[9]) * 0.5f, (v[6] + v[10]) * 0.5f)
        )
        for ((cx, cy) in mids)
            drawRect(Color.White, Offset(cx - mh, cy - mh), Size(mh * 2, mh * 2), style = Stroke(1.6f * d))
        drawCircle(Color.White, 6.dp.toPx(), Offset(v[13].toFloat(), v[14].toFloat()), style = Stroke(2f * d))
        drawCircle(Ep.Accent, 3.dp.toPx(), Offset(v[13].toFloat(), v[14].toFloat()))
    }
}

@Composable
private fun TabBar(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(EpLayout.tabH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(27.dp))
        Box(
            Modifier.height(EpLayout.tabH).background(Ep.Panel2, RoundedCornerShape(3.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("画布 1", color = Ep.Text, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(20.dp).clickable { }, contentAlignment = Alignment.Center) {
            EpIcon(17, Ep.Sub, size = 16.dp)
        }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun AnimBar(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(EpLayout.animH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(EpLayout.frame).background(Ep.Panel2, RoundedCornerShape(2.dp))
                .border(2.dp, Ep.Accent, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 2.dp)
                    .size(24.dp, 17.dp).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text("1", color = Color.White, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(26.dp).clickable { }, contentAlignment = Alignment.Center) {
            EpIcon(17, Ep.Sub, size = 18.dp)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ToolColumn(
    tool: Int, brushPx: Float, eraserPx: Float, bucketErase: Boolean,
    shapeType: Int, selMode: Int,
    onTool: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    if (ReplayHub.visible) return
    Column(modifier.width(EpLayout.btn), verticalArrangement = Arrangement.spacedBy(EpLayout.btnGap)) {
        TOOLS.forEach { t ->
            val active = t.id == tool
            Box(
                Modifier.size(EpLayout.btn)
                    .background(
                        if (active) Ep.HighlightSel else Ep.Panel2,
                        RoundedCornerShape(3.dp)
                    )
                    .clickable(enabled = t.enabled) { onTool(t.id) },
                contentAlignment = Alignment.Center
            ) {
                if (t.id == 4) {
                    // 图形工具主图标随子类型切换(旧 RefreshShapeToolIcon306)
                    val k = when (shapeType) {
                        0 -> 50; 1 -> 51; 2 -> 52; 3 -> 53
                        4 -> 54; 6 -> 55; 7 -> 56
                        9 -> 77; 10 -> 78; else -> 13
                    }
                    // 50+ 走子菜单图标集, 13 等基础编号走 EpIcon(否则会落到 SubIcon 未知兜底)
                    if (k >= 50) SubIcon(k, if (active) Color.Black else Color.White, size = 24.dp)
                    else EpIcon(k, if (active) Color.Black else Color.White, size = 24.dp)
                } else if (t.id == 1) {
                    // 框选工具主图标随子模式切换(旧 RefreshSelectToolIcon306)
                    val k = when (selMode) { 0 -> 5; 1 -> 4; else -> 3 }
                    EpIcon(k, if (active) Color.Black else Color.White, size = 24.dp)
                } else {
                    EpIcon(
                        t.icon,
                        if (active) Color.Black else Color.White,
                        size = 24.dp,
                        enabled = t.enabled
                    )
                }
                // 旧项目工具角标: 画笔/橡皮右下角显示当前大小; 油漆桶移除像素时显示红点
                when (t.id) {
                    0, 2 -> Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(16.dp)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (if (t.id == 2) eraserPx else brushPx).roundToInt().toString(),
                            color = Color.White, fontSize = 8.sp, lineHeight = 8.sp,
                            maxLines = 1, softWrap = false
                        )
                    }
                    3 -> if (bucketErase) {
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .size(8.dp)
                                .background(Color(0xFFFF2B2B), CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorColumn(
    colors: IntArray, brushColor: Int, selectedIdx: Int,
    onColor: (Int, Int) -> Unit, onSelectedTap: () -> Unit,
    onLongPress: () -> Unit = {},
    paletteMaxH: Dp = 600.dp,
    modifier: Modifier = Modifier,
) {
    if (ReplayHub.visible) return
    // 6 色=43.5dp 与工具栏6键块等高(276dp, 同一偏移一起移动); 18 色=半格; 32 色=小格可滚动
    val small = colors.size > 18
    val cellH = when {
        colors.size <= 6 -> 43.5.dp
        colors.size <= 18 -> EpLayout.swatch * 0.5f
        else -> 20.dp
    }
    val gap = if (small) 2.dp else 3.dp
    val colMod = if (small)
        // 必须用 requiredWidth: 滚动容器的视口会被父级约束压窄, 导致色块被右边裁掉
        Modifier.requiredWidth(EpLayout.swatch).heightIn(max = paletteMaxH)
            .verticalScroll(rememberScrollState())
    else Modifier.width(EpLayout.swatch)
    Column(modifier.then(colMod), verticalArrangement = Arrangement.spacedBy(gap)) {
        colors.forEachIndexed { i, c ->
            val selected = i == selectedIdx
            val shown = if (selected) brushColor else c
            Box(
                Modifier.width(EpLayout.swatch).height(cellH)
                    .background(Color(shown))
                    .border(
                        2.dp,
                        if (selected) Color.White else Ep.Line,
                        RoundedCornerShape(2.dp)
                    )
                    .combinedClickable(
                        onClick = { if (selected) onSelectedTap() else onColor(i, c) },
                        onLongClick = onLongPress,
                    )
            )
        }
    }
}

@Composable
private fun BottomButtons(
    onTimeline: () -> Unit, onSettings: () -> Unit, onDrawer: () -> Unit,
    onZoomIn: () -> Unit, onZoomOut: () -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit,
    undoCount: Int = 0, redoCount: Int = 0,
    panMode: Boolean, onMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ReplayHub.visible) return
    Box(modifier.fillMaxWidth().height(150.dp)) {
        // 左下: 设置(含标签栏) / 快捷键 / 抽屉
        BottomIcon(10, Modifier.align(Alignment.BottomStart).padding(start = EpLayout.edge, bottom = EpLayout.bottomY2), onSettings)
        // 左下快捷键(旧 BtnShortcuts): 短按/长按可配置; 默认=网格线/重回中心
        ShortcutButton(Modifier.align(Alignment.BottomStart)
            .padding(start = EpLayout.edge, bottom = EpLayout.bottomY1))
        BottomIcon(16, Modifier.align(Alignment.BottomStart).padding(start = EpLayout.edge2, bottom = EpLayout.bottomY1), onDrawer)
        // 右下: 时间轴 / 撤销 / 重做
        BottomIcon(19, Modifier.align(Alignment.BottomEnd).padding(end = EpLayout.edge, bottom = EpLayout.bottomY2), onTimeline)
        BottomIcon(7, Modifier.align(Alignment.BottomEnd).padding(end = EpLayout.edge2, bottom = EpLayout.bottomY1),
            onUndo, badge = stepBadge(undoCount))
        BottomIcon(8, Modifier.align(Alignment.BottomEnd).padding(end = EpLayout.edge, bottom = EpLayout.bottomY1),
            onRedo, badge = stepBadge(redoCount))
        // 中下 3: 单指移动/放大/缩小(可在调整设置里隐藏)
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = EpLayout.bottomY1),
            horizontalArrangement = Arrangement.spacedBy(EpLayout.btnGap)
        ) {
            if (BottomBarPrefs.showMove.value)
                BottomIcon(6, active = panMode, onClick = onMove)
            if (BottomBarPrefs.showZoom.value) {
                BottomIcon(17, onClick = onZoomIn)
                BottomIcon(18, onClick = onZoomOut)
            }
        }
    }
}

// 撤销/重做步数徽标: 最高显示 99, 超过显示 99+
private fun stepBadge(n: Int): String = if (n > 99) "99+" else n.toString()

@Composable
private fun BottomIcon(icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit,
                       active: Boolean = false, badge: String? = null) {
    Box(
        modifier.size(EpLayout.btn)
            .background(if (active) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        EpIcon(icon, if (active) Color.Black else Color.White, size = 22.dp)
        if (badge != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(badge, color = Color.White, fontSize = 7.sp, lineHeight = 7.sp,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun TimelinePanel(
    layers: Int, active: Int, onLayerOp: (Int, Int) -> Unit,
    frameIdx: Int, frameCount: Int, playing: Boolean, onFrameOp: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(EpLayout.layerPanelW).background(Ep.Panel.copy(alpha = 0.94f))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(6.dp)
    ) {
        Text("时间轴", color = Ep.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        // 帧条(旧 AnimBar): 帧号槽位 + 选中高亮; 支持加/复制/删/播放
        Row(
            Modifier.fillMaxWidth().height(28.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (i in 0 until frameCount) {
                val on = i == frameIdx
                Box(
                    Modifier.size(26.dp)
                        .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
                        .border(if (on) 1.dp else 1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { onFrameOp(3, i) },
                    contentAlignment = Alignment.Center
                ) { Text("${i + 1}", color = if (on) Color.Black else Ep.Text, fontSize = 9.sp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FrameBtn(if (playing) "■" else "▶", Modifier.weight(1f)) { onFrameOp(4, 0) }
            FrameBtn("+帧", Modifier.weight(1f)) { onFrameOp(0, 0) }
            FrameBtn("复制", Modifier.weight(1f)) { onFrameOp(1, 0) }
            FrameBtn("删帧", Modifier.weight(1f)) { onFrameOp(2, 0) }
        }
        Spacer(Modifier.height(6.dp))
        Text("图层 ${layers}", color = Ep.Sub, fontSize = 9.sp)
        Spacer(Modifier.height(3.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (i in layers - 1 downTo 0) {
                val on = i == active
                Row(
                    Modifier.fillMaxWidth().height(26.dp)
                        .padding(vertical = 1.dp)
                        .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { onLayerOp(2, i) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("L${i + 1}", color = if (on) Color.Black else Ep.Text, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.weight(1f).height(28.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { onLayerOp(0, 0) },
                contentAlignment = Alignment.Center
            ) { Text("+ 层", color = Ep.Text, fontSize = 10.sp) }
            Box(
                Modifier.weight(1f).height(28.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { onLayerOp(1, 0) },
                contentAlignment = Alignment.Center
            ) { Text("− 层", color = Ep.Text, fontSize = 10.sp) }
        }
    }
}

// 矩阵时间轴(旧 Timeline970 适配): 行=图层(顶=最上层), 列=帧, 每格 48² 缩略图
@Composable
private fun TimelineSheet(
    layers: Int, active: Int, frameIdx: Int, frameCount: Int, playing: Boolean,
    speed: Int, loopStart: Int, loopEnd: Int, visBits: Long, lckBits: Long,
    onion: Boolean = false, onOnion: (Boolean) -> Unit = {},
    onFrameOp: (Int, Int) -> Unit, onLayerOp: (Int, Int) -> Unit,
    frameThumb: (Int, Int) -> IntArray, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    var nonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(700); nonce++ }
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null) { onClose() })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(440.dp)
                .background(Ep.Panel, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("时间轴", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text("帧 ${frameIdx + 1}/$frameCount · 层 $layers", color = Ep.Sub, fontSize = 9.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
                Box(
                    Modifier.height(24.dp)
                        .background(if (onion) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { onOnion(!onion) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text("洋葱", color = if (onion) Color.Black else Ep.Text, fontSize = 9.sp) }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(26.dp).clickable(interactionSource = null, indication = null) { onClose() },
                    contentAlignment = Alignment.Center) { Text("✕", color = Ep.Sub, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FrameBtn(if (playing) "■" else "▶", Modifier.width(40.dp)) { onFrameOp(4, 0) }
                Spacer(Modifier.width(4.dp))
                FrameBtn("+帧", Modifier.width(44.dp)) { onFrameOp(0, 0) }
                Spacer(Modifier.width(4.dp))
                FrameBtn("复制", Modifier.width(44.dp)) { onFrameOp(1, 0) }
                Spacer(Modifier.width(4.dp))
                FrameBtn("删帧", Modifier.width(44.dp)) { onFrameOp(2, 0) }
                Spacer(Modifier.width(4.dp))
                FrameBtn("◀", Modifier.width(26.dp)) { onFrameOp(3, (frameIdx - 1).coerceAtLeast(0)) }
                Spacer(Modifier.width(3.dp))
                FrameBtn("▶", Modifier.width(26.dp)) { onFrameOp(3, (frameIdx + 1).coerceAtMost(frameCount - 1)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("速度", color = Ep.Sub, fontSize = 9.sp)
                Spacer(Modifier.width(6.dp))
                // 本地跟手 + 50ms 节流下发: 滑块不再被 500ms 的 stats 拉回旧值
                var dragSpeed by remember { mutableFloatStateOf(speed.toFloat()) }
                var speedDragging by remember { mutableStateOf(false) }
                LaunchedEffect(speed) {
                    if (!speedDragging) dragSpeed = speed.toFloat()
                }
                LaunchedEffect(speedDragging) {
                    if (!speedDragging) return@LaunchedEffect
                    while (true) {
                        onFrameOp(5, dragSpeed.roundToInt())
                        kotlinx.coroutines.delay(50)
                    }
                }
                androidx.compose.material3.Slider(
                    value = dragSpeed, valueRange = 1f..60f,
                    onValueChange = { speedDragging = true; dragSpeed = it },
                    onValueChangeFinished = {
                        speedDragging = false
                        onFrameOp(5, dragSpeed.roundToInt())
                    },
                    modifier = Modifier.width(150.dp).height(24.dp)
                )
                Text("${dragSpeed.roundToInt()}fps", color = Ep.Sub, fontSize = 9.sp,
                    modifier = Modifier.width(44.dp))
                Spacer(Modifier.width(6.dp))
                val loopA = loopStart.coerceAtLeast(0)
                val loopB = if (loopEnd < 0) frameCount - 1 else loopEnd
                val loopLo = minOf(loopA, loopB)
                val loopHi = maxOf(loopA, loopB)
                Text(
                    if (loopLo <= 0 && loopHi >= frameCount - 1) "循环 全部"
                    else "循环 ${loopLo + 1}–${loopHi + 1}",
                    color = Ep.Sub, fontSize = 9.sp
                )
                Spacer(Modifier.width(6.dp))
                FrameBtn("A", Modifier.width(26.dp)) { onFrameOp(6, 0) }
                Spacer(Modifier.width(3.dp))
                FrameBtn("B", Modifier.width(26.dp)) { onFrameOp(7, 0) }
                Spacer(Modifier.width(3.dp))
                FrameBtn("清除", Modifier.width(40.dp)) { onFrameOp(8, 0) }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(96.dp).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("图层", color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Box(Modifier.size(20.dp).background(Ep.Panel2, RoundedCornerShape(2.dp))
                            .clickable(interactionSource = null, indication = null) { onLayerOp(0, 0) },
                            contentAlignment = Alignment.Center) {
                            Text("+", color = Ep.Text, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(3.dp))
                        Box(Modifier.size(20.dp).background(Ep.Panel2, RoundedCornerShape(2.dp))
                            .clickable(enabled = layers > 1, interactionSource = null, indication = null) {
                                if (layers > 1) onLayerOp(1, 0)
                            },
                            contentAlignment = Alignment.Center) {
                            Text("−", color = if (layers > 1) Ep.Text else Ep.Sub, fontSize = 12.sp)
                        }
                    }
                    for (l in layers - 1 downTo 0) {
                        val on = l == active
                        val vis = (visBits and (1L shl l)) != 0L
                        val lck = (lckBits and (1L shl l)) != 0L
                        Column(
                            Modifier.fillMaxWidth().height(52.dp)
                                .padding(vertical = 1.dp)
                                .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
                        ) {
                            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text("L${l + 1}", color = if (on) Color.Black else Ep.Text, fontSize = 9.sp,
                                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                                        .clickable(interactionSource = null, indication = null) { onLayerOp(2, l) })
                                Text(if (vis) "◉" else "○", color = if (on) Color.Black else Ep.Sub, fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 3.dp)
                                        .clickable(interactionSource = null, indication = null) { onLayerOp(4, l) })
                                Text(if (lck) "▣" else "▢", color = if (on) Color.Black else Ep.Sub, fontSize = 10.sp,
                                    modifier = Modifier.padding(end = 4.dp)
                                        .clickable(interactionSource = null, indication = null) { onLayerOp(5, l) })
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("▲", color = if (on) Color.Black else Ep.Sub, fontSize = 9.sp,
                                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                                        .clickable(interactionSource = null, indication = null) { onLayerOp(6, l) })
                                Text("▼", color = if (on) Color.Black else Ep.Sub, fontSize = 9.sp,
                                    modifier = Modifier.padding(end = 6.dp)
                                        .clickable(interactionSource = null, indication = null) { onLayerOp(7, l) })
                            }
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                    for (f in 0 until frameCount) {
                        Column(Modifier.width(56.dp).fillMaxHeight()) {
                            val fOn = f == frameIdx
                            Row(
                                Modifier.fillMaxWidth().height(26.dp)
                                    .background(if (fOn) Ep.Accent.copy(alpha = 0.35f) else Ep.Panel2, RoundedCornerShape(3.dp)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("◀", color = Ep.Sub, fontSize = 8.sp,
                                    modifier = Modifier.padding(start = 2.dp)
                                        .clickable(interactionSource = null, indication = null) {
                                            onFrameOp(3, (f - 1).coerceAtLeast(0))
                                        })
                                Text("${f + 1}", color = Ep.Text, fontSize = 9.sp,
                                    modifier = Modifier.weight(1f).clickable(interactionSource = null, indication = null) { onFrameOp(3, f) },
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Text("▶", color = Ep.Sub, fontSize = 8.sp,
                                    modifier = Modifier.padding(end = 2.dp)
                                        .clickable(interactionSource = null, indication = null) {
                                            onFrameOp(3, (f + 1).coerceAtMost(frameCount - 1))
                                        })
                            }
                            for (l in layers - 1 downTo 0) {
                                FrameCell(f, l, fOn && l == active,
                                    nonce = if (fOn) nonce else 0,
                                    frameThumb = frameThumb) {
                                    onFrameOp(3, f); onLayerOp(2, l)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameCell(
    frame: Int, layer: Int, selected: Boolean, nonce: Int,
    frameThumb: (Int, Int) -> IntArray, onClick: () -> Unit,
) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, frame, layer, nonce) {
        value = try {
            val px = frameThumb(frame, layer)
            val argb = IntArray(48 * 48)
            for (i in px.indices) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
                    ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
            }
            android.graphics.Bitmap.createBitmap(argb, 48, 48,
                android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        } catch (_: Throwable) { null }
    }
    Box(
        Modifier.size(52.dp).padding(2.dp)
            .background(Color(0xFFCCCCCC), RoundedCornerShape(2.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) Ep.Accent else Ep.Line, RoundedCornerShape(2.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun FrameBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(24.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 9.sp) }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ep.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(30.dp, 17.dp)
                .background(if (on) Ep.Accent else Ep.Panel2, RoundedCornerShape(9.dp))
                .padding(2.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(13.dp).background(Color.White, RoundedCornerShape(7.dp)))
        }
    }
}

// 旧项目横屏模式本地化: 隐藏全部 UI, 只留画布 + 左下角 ☰(全屏水印/截图/个性签名)
@Composable
fun LandscapeScreen(
    watermark: com.endpix.nativeapp.ui.WatermarkSettings,
    wmOn: Boolean,
    sigOn: Boolean,
    sigDate: Boolean = true,
    sigIcon: android.graphics.Bitmap? = null,
    onToggleWm: () -> Unit,
    onToggleSig: () -> Unit,
    onToggleSigDate: () -> Unit = {},
    onScreenshot: () -> Unit,
    onWatermark: (com.endpix.nativeapp.ui.WatermarkSettings) -> Unit = {},
    onPickSigIcon: () -> Unit = {},
    onClearSigIcon: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sigSettingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(LandscapeRefHub.chromeHidden) {
        if (LandscapeRefHub.chromeHidden) {
            menuOpen = false
            sigSettingsOpen = false
        }
    }
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val d = androidx.compose.ui.platform.LocalDensity.current.density
    val screenRect = intArrayOf(0, 0, (cfg.screenWidthDp * d).toInt(), (cfg.screenHeightDp * d).toInt())
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // 三指截图手势通常在系统层拦截; 在 Initial 阶段先看到三指按下就收起菜单/☰
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(
                        androidx.compose.ui.input.pointer.PointerEventPass.Initial
                    )
                    if (e.changes.count { it.pressed } >= 3) {
                        LandscapeRefHub.hideChromeTemporarily(1800L)
                    }
                }
            }
        }
    ) {
        // 横屏额外图片层(可缩放/旋转/拖动), 画在水印下面、画布上面
        LandscapeRefLayer(Modifier.fillMaxSize())
        // 横屏水印/签名按旧项目铺满整个屏幕(而不是画布 bbox)
        WatermarkOverlay(screenRect, watermark.copy(wmOn = wmOn, sigOn = sigOn), sigIcon)
        if (!LandscapeRefHub.chromeHidden) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (menuOpen) {
                    Column(
                        Modifier.width(180.dp).background(Ep.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp)).padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LandscapeMenuRow((if (wmOn) "● " else "○ ") + "全屏水印") {
                            onToggleWm()
                        }
                        LandscapeMenuRow("截图") {
                            onScreenshot()
                        }
                        // 横屏额外图片: 拖动移动; 大小/旋转在本子菜单用滑条
                        LandscapeMenuRow("放置图片") {
                            com.endpix.nativeapp.ui.LandscapeRefHub.pick()
                        }
                        LandscapeMenuRow(
                            (if (com.endpix.nativeapp.ui.LandscapeRefHub.visible) "● " else "○ ") + "显示图片"
                        ) {
                            com.endpix.nativeapp.ui.LandscapeRefHub.toggle()
                        }
                        if (com.endpix.nativeapp.ui.LandscapeRefHub.bitmap != null) {
                            val sc = com.endpix.nativeapp.ui.LandscapeRefHub.scale
                            LandscapeMenuSlider(
                                "大小", sc, 0.2f..12f, "${(sc * 100f).toInt()}%"
                            ) { com.endpix.nativeapp.ui.LandscapeRefHub.scale = it }
                            val rot = ((com.endpix.nativeapp.ui.LandscapeRefHub.rotation % 360f) + 360f) % 360f
                            LandscapeMenuSlider(
                                "旋转", rot, 0f..360f, "${rot.toInt()}°"
                            ) { com.endpix.nativeapp.ui.LandscapeRefHub.rotation = it }
                        }
                        LandscapeMenuRow((if (sigOn) "● " else "○ ") + "个性签名") {
                            onToggleSig()
                        }
                        // 横屏签名底板内的日期开关(只影响横屏/截图的签名日期)
                        LandscapeMenuRow((if (sigDate) "● " else "○ ") + "签名日期") {
                            onToggleSigDate()
                        }
                        LandscapeMenuRow("签名设置…") {
                            sigSettingsOpen = true
                        }
                    }
                }
                Box(
                    Modifier.size(44.dp).background(Color(0xCC333340), RoundedCornerShape(6.dp))
                        .clickable(interactionSource = null, indication = null) { menuOpen = !menuOpen },
                    contentAlignment = Alignment.Center
                ) { Text("☰", color = Color.White, fontSize = 20.sp) }
            }
        }
        // 横屏快捷签名设置: 直接复用「调整设置 → 水印与横屏 → 画布签名」的同一套设置项
        if (sigSettingsOpen) {
            com.endpix.nativeapp.ui.SignatureSettingsDialog(
                wm = watermark,
                onWatermark = onWatermark,
                sigIcon = sigIcon,
                onPickSigIcon = onPickSigIcon,
                onClearSigIcon = onClearSigIcon,
                onDismiss = { sigSettingsOpen = false }
            )
        }
    }
}

@Composable
private fun LandscapeMenuRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(30.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) { Text(label, color = Ep.Text, fontSize = 10.sp) }
}

@Composable
private fun LandscapeMenuSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ep.Sub, fontSize = 8.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Ep.Text, fontSize = 8.sp)
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(20.dp)
        )
    }
}


// 旧 ScreenFx.BuildVignettes: 上下80dp渐变(0.5) + 四角160dp径向(0.45)
@Composable
private fun ScreenVignette() {
    Canvas(Modifier.fillMaxSize()) {
        val h = 48.dp.toPx()
        val maxA = 0.32f
        drawRect(
            Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = maxA), Color.Transparent),
                startY = 0f, endY = h
            ),
            size = Size(size.width, h)
        )
        drawRect(
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = maxA)),
                startY = size.height - h, endY = size.height
            ),
            topLeft = Offset(0f, size.height - h),
            size = Size(size.width, h)
        )
        val c = 100.dp.toPx()
        val a = 0.28f
        val corners = listOf(
            Offset(0f, 0f), Offset(size.width, 0f),
            Offset(0f, size.height), Offset(size.width, size.height)
        )
        for (o in corners) {
            drawCircle(
                Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = a), Color.Transparent),
                    center = o, radius = c
                ),
                radius = c, center = o
            )
        }
    }
}

// 旧 pm950 左下角品牌水印: icon-curve + Immersion technology / 浸没制造;
// 单击淡出切版本号, 5秒淡回, 版本态再点续5秒。
// ===== 小地图 / 参考图(旧 MainUIController.Tabs.cs 本地化) =====
// 缩略图数据(引擎 RGBA 打包) → ImageBitmap
private fun thumbToBitmap(px: IntArray, n: Int): androidx.compose.ui.graphics.ImageBitmap? = try {
    val argb = IntArray(n * n)
    for (i in 0 until minOf(px.size, argb.size)) {
        val c = px[i]
        val a = (c ushr 24) and 0xFF
        argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
            ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
    }
    android.graphics.Bitmap.createBitmap(argb, n, n,
        android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (_: Throwable) { null }

// 按比例 contain 的绘制矩形
private fun fitRect(box: Size, ratio: Float): Pair<Offset, Size> {
    val r = if (ratio.isFinite() && ratio > 0f) ratio else 1f
    val w: Float
    val h: Float
    if (r >= 1f) { w = box.width; h = box.width / r } else { h = box.height; w = box.height * r }
    return Offset((box.width - w) / 2f, (box.height - h) / 2f) to Size(w, h)
}

// 面板底部小按钮
@Composable
private fun PanelMiniBtn(label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 12.sp, maxLines = 1) }
}

// [小地图] 默认左上角: 缩略图+红视口框, 底部 [−][背景模式][⠿拖动][+]; 点/拖缩略图=移动画布视角
@Composable
private fun MinimapPanel(
    frameIdx: Int, activeLayer: Int, version: Int,
    frameThumb: (Int, Int) -> IntArray,
    minimapThumb: () -> IntArray,
    compositeThumb: () -> IntArray,
    canvasLabel: String,
    scale: Float, xDp: Float, yDp: Float,
    bgMode: Int, onBgMode: (Int) -> Unit,
    chkA: Int, chkB: Int,
    onScale: (Float) -> Unit, onPos: (Float, Float) -> Unit,
    onNavigate: (Float, Float, Boolean) -> Unit,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    viewportProvider: (FloatArray) -> Unit = {},
) {
    if (ReplayHub.visible) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dm = ctx.resources.displayMetrics
    val screenWpx = dm.widthPixels.toFloat()
    val screenHpx = dm.heightPixels.toFloat()
    // 旧: 缩略图边长 = 屏宽/7(参考分辨率单位), 折成 dp 后约 30% 屏宽
    val base = with(density) { minOf(screenWpx * 0.32f, 200f * density.density).toDp() }
    val s = scale.coerceIn(0.8f, 1.8f)
    val thumbBox = base * s
    val pad = (3 * s).dp
    val panelW = thumbBox + pad * 2
    val rowH = 22.dp
    val btnS = 20.dp
    val canvasW = canvasLabel.substringBefore("×").toIntOrNull()?.coerceAtLeast(1) ?: 1
    val canvasH = canvasLabel.substringAfter("×").toIntOrNull()?.coerceAtLeast(1) ?: 1
    val ratio = canvasW.toFloat() / canvasH
    val screenWdp = with(density) { screenWpx.toDp() }
    val screenHdp = with(density) { screenHpx.toDp() }
    val panelH = thumbBox + rowH + 2.dp + pad * 2
    // 拖动只改面板本地坐标, 抬手才回写: 避免每帧整屏重组和 SharedPreferences 落盘(卡顿主因)
    var posX by remember(xDp) { mutableFloatStateOf(xDp) }
    var posY by remember(yDp) { mutableFloatStateOf(yDp) }
    val nav by rememberUpdatedState(onNavigate)
    val posCb by rememberUpdatedState(onPos)
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, frameIdx, activeLayer, version) {
        // 优先 192×192 小地图采样; 引擎还没生成时回退到时间轴 48 缩略图
        val mm = try { minimapThumb() } catch (_: Throwable) { IntArray(0) }
        if (mm.size >= 192 * 192) {
            value = thumbToBitmap(mm, 192)
        } else {
            val px = try { compositeThumb() } catch (_: Throwable) { IntArray(0) }
            value = if (px.size >= 48 * 48) thumbToBitmap(px, 48) else null
        }
    }
    // 旧项目 MinimapHandler.Update: 每帧刷新红框. 这里同样逐帧向引擎查询视口,
    // 只在数值变化时失效 Canvas 重绘(不重组、不等 stats)
    val vpState = remember { mutableStateOf(floatArrayOf(0f, 0f, 1f, 1f)) }
    val vpCb by rememberUpdatedState(viewportProvider)
    LaunchedEffect(Unit) {
        val tmp = FloatArray(4)
        while (true) {
            withFrameNanos { }
            try { vpCb(tmp) } catch (_: Throwable) { continue }
            if (!tmp.contentEquals(vpState.value)) vpState.value = tmp.copyOf()
        }
    }
    val coordsState = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val dragCoords = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Column(
        Modifier.graphicsLayer {
            translationX = posX.dp.toPx()
            translationY = posY.dp.toPx()
        }
            .width(panelW).height(panelH)
            .onGloballyPositioned {
                coordsState.value = it
                onBounds(it.boundsInWindow())
            }
            .background(if (bgMode == 3) Color.Transparent else Ep.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .padding(pad)
    ) {
        Canvas(
            Modifier.size(thumbBox)
                .pointerInput(canvasW, canvasH) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        var last = down.position
                        // 大画布上每次导航都是整屏重合成(~20-30ms): 拖小地图时限到 30Hz,
                        // 让手指/红框的 UI 不被 GL 合成拖住; 小画布保持 60Hz
                        val navThrottle = (canvasW.toLong() * canvasH > 4_000_000L)
                        var lastNavMs = 0L
                        fun push(p: Offset, animate: Boolean) {
                            val (o, sz) = fitRect(Size(size.width.toFloat(), size.height.toFloat()), ratio)
                            val nx = ((p.x - o.x) / sz.width).coerceIn(0f, 1f)
                            val ny = ((p.y - o.y) / sz.height).coerceIn(0f, 1f)
                            if (!animate && navThrottle) {
                                val now = System.currentTimeMillis()
                                if (now - lastNavMs < 33L) return
                                lastNavMs = now
                            }
                            nav(nx * canvasW, ny * canvasH, animate)
                        }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (!dragging &&
                                (ch.position - down.position).getDistance() > 12.dp.toPx()
                            ) dragging = true
                            if (dragging) push(ch.position, false)
                            last = ch.position
                            ev.changes.forEach { it.consume() }
                        }
                        if (!dragging) push(last, true)
                    }
                }
        ) {
            val (o, sz) = fitRect(size, ratio)
            when (bgMode) {
                1 -> drawRect(Color(chkA), o, sz)
                2 -> drawRect(Color(chkB), o, sz)
            }
            bmp?.let {
                drawImage(
                    it,
                    dstOffset = androidx.compose.ui.unit.IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(sz.width.roundToInt(), sz.height.roundToInt()),
                    filterQuality = FilterQuality.None
                )
            }
            val vp = vpState.value
            clipRect(o.x, o.y, o.x + sz.width, o.y + sz.height) {
                drawRect(
                    Color(0xFFFF2626),
                    topLeft = Offset(o.x + vp[0] * sz.width, o.y + vp[1] * sz.height),
                    size = Size(vp[2] * sz.width, vp[3] * sz.height),
                    style = Stroke(1.5.dp.toPx())
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
            PanelMiniBtn("−", btnS) { onScale((s - 0.2f).coerceIn(0.8f, 1.8f)) }
            Spacer(Modifier.width(2.dp))
            // 背景模式: 1=画布棋盘色A 2=棋盘色B 3=透明(默认)
            Box(
                Modifier.size(btnS).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) {
                        onBgMode(if (bgMode >= 3) 1 else bgMode + 1)
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(btnS * 0.6f)) {
                    val ca = Color(chkA)
                    val cb = Color(chkB)
                    val hw = size.width / 2f
                    val hh = size.height / 2f
                    if (bgMode == 1) drawRect(ca)
                    else if (bgMode == 2) drawRect(cb)
                    else {
                        drawRect(ca.copy(alpha = 0.67f), Offset.Zero, Size(hw, hh))
                        drawRect(cb.copy(alpha = 0.67f), Offset(hw, 0f), Size(hw, hh))
                        drawRect(cb.copy(alpha = 0.67f), Offset(0f, hh), Size(hw, hh))
                        drawRect(ca.copy(alpha = 0.67f), Offset(hw, hh), Size(hw, hh))
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
            // 拖动条: 1:1 跟随手指(旧 MinimapHandler 拖动位置)
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .onGloballyPositioned { dragCoords.value = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 用 window 坐标算位移: 面板移动后本地坐标跟着变, 增量会"追不上手"
                            val dc = dragCoords.value
                            val downWin = dc?.localToWindow(down.position) ?: down.position
                            val bx = posX
                            val by = posY
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val win = dc?.localToWindow(ch.position) ?: ch.position
                                val dxDp = (win.x - downWin.x) / density.density
                                val dyDp = (win.y - downWin.y) / density.density
                                posX = (bx + dxDp).coerceIn(0f, (screenWdp.value - panelW.value).coerceAtLeast(0f))
                                posY = (by + dyDp).coerceIn(0f, (screenHdp.value - panelH.value).coerceAtLeast(0f))
                                ch.consume()
                            }
                            posCb(posX, posY)
                            coordsState.value?.let { onBounds(it.boundsInWindow()) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("⠿ 拖动", color = Ep.Sub, fontSize = 9.sp, maxLines = 1) }
            Spacer(Modifier.width(2.dp))
            PanelMiniBtn("+", btnS) { onScale((s + 0.2f).coerceIn(0.8f, 1.8f)) }
        }
    }
}

// [参考图] 默认右上角: 图片 contain, 点空面板选图/双击复位, 内容拖拽+双指缩放, 底部 [−][⠿拖动][+]
@Composable
private fun ReferencePanel(
    bitmap: android.graphics.Bitmap?,
    scale: Float, xDp: Float, yDp: Float,
    onScale: (Float) -> Unit, onPos: (Float, Float) -> Unit,
    onPickImage: () -> Unit,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    if (ReplayHub.visible) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dm = ctx.resources.displayMetrics
    val screenWpx = dm.widthPixels.toFloat()
    val screenHpx = dm.heightPixels.toFloat()
    val base = with(density) { minOf(screenWpx * 0.32f, 200f * density.density).toDp() }
    val s = scale.coerceIn(0.8f, 1.8f)
    val thumbBox = base * s
    val pad = (3 * s).dp
    val panelW = thumbBox + pad * 2
    val rowH = 22.dp
    val btnS = 20.dp
    val screenWdp = with(density) { screenWpx.toDp() }
    val screenHdp = with(density) { screenHpx.toDp() }
    val panelH = thumbBox + rowH + 2.dp + pad * 2
    val effX = if (xDp < 0f) (screenWdp.value - panelW.value - 8f).coerceAtLeast(0f) else xDp
    var posX by remember(effX) { mutableFloatStateOf(effX) }
    var posY by remember(yDp) { mutableFloatStateOf(yDp) }
    // 面板缩放后把旧位置夹回屏幕内, 避免缩小后还停在屏外
    LaunchedEffect(panelW, panelH, screenWdp, screenHdp) {
        posX = posX.coerceIn(0f, (screenWdp.value - panelW.value).coerceAtLeast(0f))
        posY = posY.coerceIn(0f, (screenHdp.value - panelH.value).coerceAtLeast(0f))
    }
    val posCb by rememberUpdatedState(onPos)
    var imgScale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offY by remember(bitmap) { mutableFloatStateOf(0f) }
    val imgBmp = remember(bitmap) { bitmap?.asImageBitmap() }
    val coordsState = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val dragCoords = remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Column(
        Modifier.graphicsLayer {
            translationX = posX.dp.toPx()
            translationY = posY.dp.toPx()
        }
            .width(panelW).height(panelH)
            .onGloballyPositioned {
                coordsState.value = it
                onBounds(it.boundsInWindow())
            }
            .background(Ep.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .padding(pad)
    ) {
        Box(
            Modifier.size(thumbBox)
                .clipToBounds().background(Color(0xFF262626)),
            contentAlignment = Alignment.Center
        ) {
            if (imgBmp == null) {
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = null, indication = null
                    ) { onPickImage() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "📷 添加参考\n\n点击此处选择图片", color = Ep.Sub, fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            } else {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(imgBmp) {
                            // 单指拖动/双指缩放 + 双击复位(旧 RefImageHandler)
                            var lastTap = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                var canceled = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.any { it.isConsumed }) { canceled = true; break }
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    val z = ev.calculateZoom()
                                    val p = ev.calculatePan()
                                    if (!moved) {
                                        val spread = (pressed[0].position - down.position).getDistance()
                                        if (pressed.size >= 2 || spread > viewConfiguration.touchSlop) moved = true
                                    }
                                    if (moved) {
                                        if (z > 0f && z != 1f) imgScale = (imgScale * z).coerceIn(1f, 16f)
                                        offX += p.x
                                        offY += p.y
                                        // 缩放/平移后立即夹住偏移: 放大再缩小不会留下"碰撞残留"
                                        val vw = size.width.toFloat()
                                        val vh = size.height.toFloat()
                                        val ratio = (imgBmp?.width ?: 1).toFloat() /
                                            (imgBmp?.height ?: 1).coerceAtLeast(1).toFloat()
                                        val fitted = fitRect(
                                            androidx.compose.ui.geometry.Size(vw, vh), ratio
                                        ).second
                                        val dw = fitted.width * imgScale
                                        val dh = fitted.height * imgScale
                                        val mx = ((dw - fitted.width) * 0.5f).coerceAtLeast(0f)
                                        val my = ((dh - fitted.height) * 0.5f).coerceAtLeast(0f)
                                        offX = offX.coerceIn(-mx, mx)
                                        offY = offY.coerceIn(-my, my)
                                    }
                                    ev.changes.forEach { it.consume() }
                                }
                                if (!canceled && !moved) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTap < 400L) {
                                        imgScale = 1f; offX = 0f; offY = 0f; lastTap = 0L
                                    } else lastTap = now
                                }
                            }
                        }
                ) {
                    val src = imgBmp ?: return@Canvas
                    val ratio = src.width.toFloat() / src.height.coerceAtLeast(1)
                    val (o, sz) = fitRect(size, ratio)
                    val dw = (sz.width * imgScale).coerceAtLeast(1f)
                    val dh = (sz.height * imgScale).coerceAtLeast(1f)
                    // 再夹一次显示偏移, 保证缩放回来的画面回到正确位置
                    val mx = ((dw - sz.width) * 0.5f).coerceAtLeast(0f)
                    val my = ((dh - sz.height) * 0.5f).coerceAtLeast(0f)
                    val cx = offX.coerceIn(-mx, mx)
                    val cy = offY.coerceIn(-my, my)
                    val left = o.x + (sz.width - dw) * 0.5f + cx
                    val top = o.y + (sz.height - dh) * 0.5f + cy
                    drawImage(
                        src,
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            left.roundToInt(), top.roundToInt()
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            dw.roundToInt(), dh.roundToInt()
                        ),
                        filterQuality = FilterQuality.None
                    )
                }
            }
            // 参考图缩放倍率(×1.0 .. ×16.0): 盖在图片右上角, 面板缩小时也能看到
            if (imgBmp != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text(
                        String.format(java.util.Locale.US, "×%.1f", imgScale),
                        color = Color.White, fontSize = 9.sp, maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
            PanelMiniBtn("−", btnS) { onScale((s - 0.2f).coerceIn(0.8f, 1.8f)) }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .onGloballyPositioned { dragCoords.value = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 用 window 坐标算位移: 面板移动后本地坐标跟着变,
                            // positionChange() 会形成追不上手指的反馈卡顿.
                            val dc = dragCoords.value
                            val downWin = dc?.localToWindow(down.position) ?: down.position
                            val bx = posX
                            val by = posY
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val win = dc?.localToWindow(ch.position) ?: ch.position
                                val dxDp = (win.x - downWin.x) / density.density
                                val dyDp = (win.y - downWin.y) / density.density
                                posX = (bx + dxDp).coerceIn(0f,
                                    (screenWdp.value - panelW.value).coerceAtLeast(0f))
                                posY = (by + dyDp).coerceIn(0f,
                                    (screenHdp.value - panelH.value).coerceAtLeast(0f))
                                ch.consume()
                            }
                            posCb(posX, posY)
                            coordsState.value?.let { onBounds(it.boundsInWindow()) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("⠿ 拖动", color = Ep.Sub, fontSize = 9.sp, maxLines = 1) }
            Spacer(Modifier.width(2.dp))
            // 换图: 小缩略图按钮(像小地图底栏小图), 面板缩小时也一直显示当前参考图
            Box(
                Modifier.size(btnS)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Ep.Panel2)
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { onPickImage() },
                contentAlignment = Alignment.Center
            ) {
                if (imgBmp != null) {
                    Image(
                        imgBmp, contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(1.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("换", color = Ep.Accent, fontSize = 8.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(2.dp))
            PanelMiniBtn("+", btnS) { onScale((s + 0.2f).coerceIn(0.8f, 1.8f)) }
        }
    }
}

@Composable
private fun BrandWatermark(
    settings: com.endpix.nativeapp.ui.WatermarkSettings,
    sigIcon: android.graphics.Bitmap?,
    sfxOn: Boolean = true,
    scaleOverride: Float? = null,
    modifier: Modifier = Modifier,
) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    // 按屏宽适配, 右下角不挤占画布
    val wmScale = scaleOverride ?: (cfg.screenWidthDp / 390f).coerceIn(0.85f, 1.2f)
    val lines = remember(settings.sigText) {
        val t = settings.sigText.replace("\r", "")
        val l = t.split("\n").filter { it.isNotEmpty() }.take(2)
        if (t.isEmpty()) listOf("Immersion technology", "浸没制造") else l
    }
    // 竖屏签名: 只显示图标+文字, 不显示日期/时间(横屏与导出的日期在签名底板内)
    // 竖屏不绑定「向中心收拢」: 只按签名位置(四角)贴边; 收拢距离仅用于横屏/导出
    Column(
        modifier = modifier
            .background(Color(settings.sigBg), RoundedCornerShape(2.dp))
            .padding(horizontal = (5 * wmScale).dp, vertical = (2 * wmScale).dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            modifier = Modifier.height((34 * wmScale).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        val iconSize = (28 * wmScale).dp
        val iconBmp = remember(sigIcon) { sigIcon?.asImageBitmap() }
        if (iconBmp != null) {
            Image(
                iconBmp, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(iconSize).graphicsLayer(alpha = 0.7f)
            )
        } else {
            Canvas(Modifier.size(iconSize).graphicsLayer(alpha = 0.7f)) {
                val k = size.width / 100f
                val sw = 2.9f * k * 1.2f
                val seg = floatArrayOf(
                    64.21f,10.41f, 34.89f,35.56f, 34.89f,35.56f, 47.86f,48.56f,
                    64.21f,10.41f, 84.84f,40.78f,
                    63.62f,37.08f, 37.46f,59.50f, 37.46f,59.50f, 51.36f,73.48f,
                    35.44f,89.59f, 68.53f,59.46f,
                    7.21f,48.37f, 16.36f,32.25f, 16.36f,32.25f, 25.51f,48.02f,
                    7.21f,48.37f, 25.51f,48.02f,
                    3.48f,53.58f, 33.84f,53.08f,
                    66.54f,43.31f, 96.52f,42.33f,
                    73.16f,48.37f, 91.46f,47.98f,
                    73.16f,48.56f, 82.31f,64.13f,
                    91.46f,48.17f, 82.31f,64.13f
                )
                var px = 12.86f; var py = 53.58f
                val c1x = 20.16f; val c1y = 65.67f; val c2x = 27.8f; val c2y = 77.72f
                val p1x = 35.44f; val p1y = 89.59f
                val pts = ArrayList<Float>()
                for (v in seg) pts.add(v)
                for (i in 1..8) {
                    val t = i / 8f; val u = 1f - t
                    val bx = u*u*u*12.86f + 3f*u*u*t*c1x + 3f*u*t*t*c2x + t*t*t*p1x
                    val by = u*u*u*53.58f + 3f*u*u*t*c1y + 3f*u*t*t*c2y + t*t*t*p1y
                    pts.add(px); pts.add(py); pts.add(bx); pts.add(by)
                    px = bx; py = by
                }
                val col = Color.White.copy(alpha = 0.7f)
                var i = 0
                while (i + 3 < pts.size) {
                    drawLine(col,
                        Offset(pts[i] * k, pts[i + 1] * k),
                        Offset(pts[i + 2] * k, pts[i + 3] * k),
                        strokeWidth = sw, cap = StrokeCap.Round)
                    i += 4
                }
            }
        }
        Column(
            Modifier.padding(start = (6 * wmScale).dp)
                .widthIn(max = (300 * wmScale).dp)
                .graphicsLayer(alpha = 0.7f)
        ) {
            lines.take(2).forEachIndexed { i, ln ->
                Text(
                    ln, color = Color.White,
                    fontSize = ((if (i == 0) 15f else 12f) * wmScale).sp,
                    lineHeight = ((if (i == 0) 16f else 13f) * wmScale).sp,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                )
            }
        }
        }
    }
}

// 测距模式面板(旧 Measure.cs MeasureDlg): 三按钮切换起点模式
@Composable
private fun MeasurePanel(
    fixed: Boolean, pending: Boolean, hasPoints: Boolean,
    onSetStart: () -> Unit, onCenter: () -> Unit, onFinger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MeasureBtn("主动设置起点", pending || fixed, true, onSetStart)
        MeasureBtn("中心设置起点", false, !fixed && !pending && hasPoints, onCenter)
        MeasureBtn("回到手指起点", !fixed && !pending, true, onFinger)
    }
}

// 多边形套索浮条(旧 pm615): 顶点数 + 撤销点/重置点/完成/取消
@Composable
private fun PolyBar(
    verts: Int,
    onUndo: () -> Unit, onReset: () -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("多边形套索 · $verts 点", color = Ep.Text, fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 4.dp))
        MeasureBtn("撤销点", false, verts > 0, onUndo)
        MeasureBtn("重置点", false, verts > 0, onReset)
        MeasureBtn("完成", false, verts >= 3, onFinish)
        MeasureBtn("取消", false, true, onCancel)
    }
}

@Composable
private fun MeasureBtn(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(28.dp)
            .background(if (active) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(2.dp))
            .border(1.dp, if (active) Ep.Line else Color.Transparent, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                !enabled -> Ep.Text.copy(alpha = 0.35f)
                active -> Color.Black
                else -> Ep.Text
            },
            fontSize = 10.sp
        )
    }
}

// 左右下角圆角半径(dp): API 31+ 读窗口 RoundedCorner insets, 取较大的下角; 拿不到返回 0。
// 用物理 px 保存再按当前 LocalDensity 换算, 界面缩放改变 Compose 密度时仍按真实圆角避让。
@Composable
private fun rememberBottomCornerRadiusDp(): Float {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var radiusPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(view) {
        if (android.os.Build.VERSION.SDK_INT < 31) return@LaunchedEffect
        for (i in 0 until 20) {
            val wi = view.rootWindowInsets
            if (wi != null) {
                val r = maxOf(
                    wi.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
                    wi.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
                )
                if (r > 0) radiusPx = r
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
        }
    }
    return if (radiusPx > 0) radiusPx / density else 0f
}

// 框选专用子菜单（从 MainScreen 拆出：主方法已到 JVM 64K 上限）
@Composable
private fun SelectOverlay(
    show: Boolean, mode: Int, onMode: (Int) -> Unit, selActive: Boolean,
    onSelectAll: () -> Unit, onImport: () -> Unit, onPasteLastCut: () -> Unit,
    alphaLock: Boolean, onAlphaLock: (Boolean) -> Unit,
    stretchMode: Boolean, onStretchMode: (Boolean) -> Unit,
    allLayers: Boolean, onAllLayers: (Boolean) -> Unit,
    allFrames: Boolean, onAllFrames: (Boolean) -> Unit,
    onReset: () -> Unit, onStretch: () -> Unit, onMore: () -> Unit,
    hasLastCut: Boolean,
    onToast: (String) -> Unit, onClose: () -> Unit,
    toolYDp: Dp, modifier: Modifier = Modifier,
) {
    if (!show) return
    // 只有正在框选且已成功裁剪(浮起)的目标才能用裁剪导出/新标签打开
    val cropReady = com.endpix.nativeapp.ui.CropExportHub.targetReady
    SelectSubmenu(
        mode = mode,
        onMode = { m -> ReplayHub.log("选区模式 $m"); onMode(m) },
        selActive = selActive,
        onSelectAll = { ReplayHub.log("全选"); onSelectAll(); onClose() },
        onImport = { onToast("请选择要导入的图片…"); onImport(); onClose() },
        onPasteLastCut = {
            if (!hasLastCut) onToast("裁剪目标为空（先裁剪一次）")
            else { onPasteLastCut(); onClose(); onToast("已粘贴上次裁剪") }
        },
        alphaLock = alphaLock, onAlphaLock = onAlphaLock,
        stretchMode = stretchMode,
        onStretchMode = { on -> onStretchMode(on); if (on) onClose() },
        perspectiveMode = SelectionPerspectiveHub.on,
        onPerspectiveMode = { on ->
            if (on) onStretchMode(false)
            SelectionPerspectiveHub.set(on)
            ReplayHub.log(if (on) "透视变形 开" else "透视变形 关")
            if (on) onClose()
        },
        allLayers = allLayers, onAllLayers = onAllLayers,
        allFrames = allFrames, onAllFrames = onAllFrames,
        onReset = {
            if (!selActive) onToast("当前没有选区")
            else { onReset(); onClose() }
        },
        onStretch = { onClose(); onStretch() },
        onMore = onMore,
        cropReady = cropReady,
        onCropExport = {
            if (!cropReady) onToast("需要正在框选且已裁剪的目标")
            else { onClose(); ReplayHub.log("裁剪导出"); com.endpix.nativeapp.ui.CropExportHub.requestExport() }
        },
        onCropNewTab = {
            if (!cropReady) onToast("需要正在框选且已裁剪的目标")
            else { onClose(); ReplayHub.log("新标签打开裁剪"); com.endpix.nativeapp.ui.CropExportHub.newTab() }
        },
        modifier = modifier.padding(start = EpLayout.btn + 12.dp)
            .offset(y = (EpLayout.btn + EpLayout.btnGap) * 0.5f + toolYDp)
    )
}

// 通用更多工具（同样从 MainScreen 拆出）
@Composable
private fun MoreToolsOverlay(
    show: Boolean,
    portraitLock: Boolean, onPortraitLock: (Boolean) -> Unit,
    recordOn: Boolean, recordCount: Int, onRecord: (Boolean) -> Unit,
    onFit: () -> Unit, onFlipH: () -> Unit, onFlipV: () -> Unit,
    onGray: () -> Unit, onHideColor: () -> Unit, onLevels: () -> Unit,
    minimapOn: Boolean, onMinimapOn: (Boolean) -> Unit,
    refOn: Boolean, onRefOn: (Boolean) -> Unit,
    chkOn: Boolean, onChk: (Boolean) -> Unit,
    gridOn: Boolean, onGrid: (Boolean) -> Unit,
    noiseOn: Boolean, onNoisePanel: () -> Unit,
    onText: () -> Unit, onClose: () -> Unit,
    toolYDp: Dp, modifier: Modifier = Modifier,
) {
    if (!show) return
    MoreToolsMenu(
        portraitLock = portraitLock, onPortraitLock = onPortraitLock,
        recordOn = recordOn, recordCount = recordCount,
        onRecord = { on ->
            if (!on) ReplayHub.log("停止记录过程")
            onRecord(on)
            if (on) ReplayHub.log("开始记录过程")
        },
        onFit = { ReplayHub.log("重回中心"); onFit(); onClose() },
        onFlipH = { ReplayHub.log("水平翻转"); onFlipH(); onClose() },
        onFlipV = { ReplayHub.log("垂直翻转"); onFlipV(); onClose() },
        onGray = { ReplayHub.log("灰度"); onGray(); onClose() },
        onHideColor = { onClose(); onHideColor() },
        onLevels = { ReplayHub.log("色阶"); onLevels(); onClose() },
        minimapOn = minimapOn, onMinimapOn = { on -> onMinimapOn(on); onClose() },
        refOn = refOn, onRefOn = { on -> onRefOn(on); onClose() },
        chkOn = chkOn, onChk = onChk,
        gridOn = gridOn, onGrid = onGrid,
        noiseOn = noiseOn, onNoisePanel = { ReplayHub.log("打开白噪音"); onNoisePanel(); onClose() },
        onText = { ReplayHub.log("打开添加文字"); onText(); onClose() },
        modifier = modifier.padding(start = EpLayout.btn + 12.dp)
            .offset(y = (EpLayout.btn + EpLayout.btnGap) * 0.5f + toolYDp)
    )
}
