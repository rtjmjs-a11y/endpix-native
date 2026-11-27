#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <android/native_window.h>
#include "../canvas/canvas.h"
#include "../canvas/mip.h"
#include "../display/gl_display.h"
#include "selection.h"
#include "png_export.h"

namespace endpix {

// 常驻合成线程池(脏区 tile 并行)
class ComposePool {
public:
    void start(int workers);
    void stop();
    // 并行执行 fn(i) i=0..n-1; n 小则直接在当前线程跑
    void run(int n, const std::function<void(int)>& fn);
private:
    void worker();
    std::vector<std::thread> th_;
    std::mutex mu_;
    std::condition_variable cv_;
    std::atomic<int> gen_{0}, done_{0}, next_{0};
    std::atomic<bool> quit_{false};
    const std::function<void(int)>* job_ = nullptr;
    int n_ = 0;
};

// M1 垂直切片: 输入 -> 画笔 -> 脏 tile 合成 -> AHB 直写 -> 单 draw。
class Engine {
public:
    bool start(ANativeWindow* win, int vw, int vh, int canvasW, int canvasH, int layers = 2);
    void stop();
    bool isRunning() const { return running_.load(); }
    void resize(int vw, int vh);
    void setInsets(int l, int t, int r, int b);   // 画布视口内缩(左右栏/上下栏)
    void touch(int action, float x, float y, int64_t timeMs = 0);  // 0=down 1=move 2=up 3=cancel; timeMs=MotionEvent.eventTime
    void zoom(float factor, float fx, float fy); // 双指缩放(以屏幕点为中心)
    void beginZoomGesture(float fx, float fy);
    void zoomGesture(float totalFactor, float fx, float fy);
    void endZoomGesture();
    void requestCancelFirstStroke();
    void requestCancelSelectionGesture() {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({50, 0.0f, 0.0f, 0.0f});
    }   // 第二指确认双指: 撤销第一指刚画出的那笔(无 redo)
    void pan(float dx, float dy);                 // 双指平移(屏幕像素)
    void setStress(bool on) { stress_ = on; }
    void setMode(int m) { sel_.setMode(m); }
    void setPickMode(int n) {   // 取色器: 接下来 n 次点画布拾取可见色(1=普通, 2=两色混合)
        pickRemain_.store(n > 0 ? n : 0);
        pickMulti_.store(n > 1);
        pickMode_.store(n > 0);
    }
    void setEraseMode(bool on) { eraseMode_.store(on); } // 子菜单: 画笔当橡皮用
    void setFillTolerance(int t) { fillTol_.store(t); }  // 油漆桶容差 0..255
    void setShape(int type, bool filled) { shapeType_.store(type); shapeFill_.store(filled); }
    void setPerfectLine(bool on) { ppLine_.store(on); }
    void setLeafWidth(float w) { leafW_.store(w); }
    void setShapeRatio(bool on) { shapeRatio_.store(on); }
    void setShapeSize(float px) { shapePx_.store(px); }     // 图形线宽(屏幕px)
    // 测距模式(UI线程调用, 排队到渲染线程): 0=手指起点 1=固定起点
    void requestMeasureFixed(bool on);
    void requestMeasureCenterStart();      // 当前测距中心设为固定起点
    void requestMeasureSetStartPending();  // 下一次点画布设固定起点
    void requestPolyOp(int op);            // 多边形套索: 0撤销点 1重置点 2完成 3取消
    void requestCancelShapePreview() {     // 切标签/保存前丢弃未完成形状, 不把预览像素存进项目
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({79, 0.0f, 0.0f, 0.0f});
    }
    // 形状渐变(方形/圆形): 预览状态下换预设 / 应用 / 取消
    void requestGradPreset(int p) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({90, (float)p, 0.0f, 0.0f});
    }
    void requestGradApply() {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({91, 0.0f, 0.0f, 0.0f});
    }
    void requestGradCancel() {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({92, 0.0f, 0.0f, 0.0f});
    }
    // 渐变范围(5..100%, 过渡带占形状轴长的比例); 直接写原子量, 渲染线程每帧最多重画一次
    void setGradRange(int pct) { gradRangeA_.store(pct < 5 ? 5 : (pct > 100 ? 100 : pct)); }
    // 方形渐变角度(0..359°, 0=→ 90=↓ 180=← 270=↑); 圆形不用角度
    void setGradAngle(int deg) { gradAngleA_.store(((deg % 360) + 360) % 360); }
    // 透明位置(0..100%, 淡出起点沿渐变轴的位置): 0=实色端开始淡, 50=中间开始淡
    void setGradPos(int pct) { gradPosA_.store(pct < 0 ? 0 : (pct > 100 ? 100 : pct)); }
    // 按标签保存撤销/重做栈: 切标签前 park, 载入后 set, 关闭标签 drop
    void requestParkTabHistory() {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({80, 0.0f, 0.0f, 0.0f});
    }
    void requestSetTabHistory(int id) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({81, (float)id, 0.0f, 0.0f});
    }
    void requestDropTabHistory(int id) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({82, (float)id, 0.0f, 0.0f});
    }
    void setAlphaLock(bool on) { alphaLock_.store(on); }
    // 裁剪工具栏「透明叠加」: false(默认)=半透明落定直接覆盖; true=over 混合
    void setSelectionAlphaOverlay(bool on) { sel_.setAlphaOverlay(on); }
    void setSelectionHoldMs(int ms) { sel_.setHoldMs(ms); }   // 选区内长按裁剪触发时长
    void requestSetLayerName(int idx, std::string name);
    void requestSetLayerOpacity(int idx, float o);
    std::string layerMetaJoined();   // UI 线程: namepermille...
    void refreshLayerMetaCache();    // 渲染线程: Canvas -> 缓存
    void setSelAllLayers(bool on) { selAllLayers_.store(on); sel_.setAllLayers(on); }
    void setSelShape(int s) { selShape_.store(s); }   // 0=方框 1=套索 2=魔棒
    void setOnion(bool on) { onionOn_.store(on); onionGen_.fetch_add(1); }   // 洋葱皮
    // 调整设置·画布外观(ARGB 0xRRGGBB, alphaPct 0..100, lineW100=线宽*100)
    void setCanvasStyle(int gridW, int gridH, int gridColor, int gridAlphaPct, int lineW100,
                        int chkTile, int chkA, int chkB, int bgColor);
    void setMagnifier(bool on) { magnifierOn_.store(on); }
    // 色阶实时预览: luts 4通道×256(每通道0..255), on=false关闭; 不改画布
    void setLevelsPreview(bool on, const int* luts);
    void requestApplyLevelsAll(const uint8_t luts[4][256]);
    void setPngCompress(int mode) { pngCompress_.store(std::max(0, std::min(2, mode))); }
    void viewState(float* z, float* px, float* py) const {
        *z = display_.zoom(); *px = display_.panX(); *py = display_.panY();
    }
    // 小地图红框: 屏幕视口在画布中的归一化矩形(旧 UpdateViewport 的本地化; UI 每帧查询)
    void minimapViewportNormalized(float* out);
    void setViewState(float z, float px, float py) {
        display_.setView(z, px, py);
        screenFull_ = true;
    }
    // 后台返回: 与 snapshot 载入同队列, 确保在 load 之后恢复缩放/平移
    void requestSetView(float z, float px, float py) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({51, z, px, py});
    }
    // 小地图导航: animate=true 走平滑动画(点击), false 自由跟手(拖动)
    void requestAnimateViewCenter(float cx, float cy, bool animate) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({56, cx, cy, animate ? 1.0f : 0.0f});
    }
    // 时间轴: 循环区间/帧互换/图层重排/播放画笔/视角(zoom+pan)动画
    void requestSetLoopRange(int start, int end) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({57, (float)start, (float)end, 0.0f});
    }
    void requestSwapFrames(int a, int b) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({58, (float)a, (float)b, 0.0f});
    }
    void requestReorderFrame(int from, int to) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({62, (float)from, (float)to, 0.0f});
    }
    void requestReorderLayer(int from, int to) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({59, (float)from, (float)to, 0.0f});
    }
    void requestSetPlayBrush(bool on) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({60, on ? 1.0f : 0.0f, 0.0f, 0.0f});
    }
    void requestAnimateViewState(float z, float px, float py) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({61, z, px, py});
    }
    // 横竖屏/Surface 变化后强制整屏重绘(不依赖脏矩形)
    void requestRedraw() { redrawReq_.store(true); }
    int frameCountNow() const { return frameCount_.load(); }
    std::vector<uint32_t> readRegion(int x, int y, int w, int h);   // 合成像素读取(调色板画布)
    // 上次裁剪目标像素(ABGR, 活动层); 供「裁剪导出」「新标签打开」使用
    bool lastCutPixels(std::vector<uint32_t>& out, int& w, int& h);
    int layerCountNow() const { return layerCount_.load(); }
    void canvasSize(int* w, int* h, int* layers) const {
        if (w) *w = canvas_.width();
        if (h) *h = canvas_.height();
        if (layers) *layers = canvas_.layers();
    }
    // 标签载入/旋转后按当前视口重新适应画布
    void requestResetView() {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({18, 0.0f, 0.0f, 0.0f});
    }
    // 调试/回归脚本: 渲染线程执行, 结果追加到 logPath(仅调试构建使用)
    void debugScript(const std::string& text, const std::string& logPath);
    void requestScaleSel(int pctW, int pctH);
    void requestNewDocument(int w = 0, int h = 0);   // 标签栏: 新建画布(0=当前尺寸)
    void requestResizeCanvas(int w, int h, int ax, int ay);   // 调整当前标签画布尺寸
    void scaleSelection(int pctW, int pctH);
    void setTool(int t);                        // 0=画笔 1=框选 2=橡皮
    // 回放: 用项目画布视口直接显示回放帧(不覆盖项目像素/撤销), 每步由 JNI 推一帧
    struct ReplayImage {
        int w = 0, h = 0;
        std::vector<uint32_t> px;
    };
    void setReplayFrame(int w, int h, const uint32_t* px);
    void clearReplayFrame();
    bool replayFrameActive() const { return std::atomic_load(&replayImg_) != nullptr; }
    void setBrush(float canvasPx, uint32_t argb);   // 直径按画布像素
    void setBrushBuildup(bool on) { buildup_.store(on); }   // 叠色: 半透明笔在同一笔内累积
    // 笔刷预设(圆/方、硬度、间距、散射、长宽比、角度、流量); 默认值 = 原像素硬笔
    void setBrushStyle(int shape, int hardness, int spacing, int scatter,
                       int aspect, int angle, int flow) {
        brushShape_.store(shape == 1 ? 1 : 0);
        brushHardness_.store(std::max(0, std::min(100, hardness)));
        brushSpacing_.store(std::max(1, std::min(200, spacing)));
        brushScatter_.store(std::max(0, std::min(300, scatter)));
        brushAspect_.store(std::max(5, std::min(100, aspect)));
        brushAngle_.store(((angle % 360) + 360) % 360);
        brushFlow_.store(std::max(1, std::min(100, flow)));
    }
    void layerOp(int op, int arg);              // 0=加层 1=删活动层 2=选层
    SelectionTool& selTool() { return sel_; }
    void cropToSelection();
    void undo();
    void redo();
    void selectionScreenRect(int out[5]) const {   // UI 线程: 选区屏幕矩形+浮动态(每帧更新, 面板跟手用)
        out[0] = selScrX0_.load(); out[1] = selScrY0_.load();
        out[2] = selScrX1_.load(); out[3] = selScrY1_.load();
        out[4] = (selScrActive_.load() ? 1 : 0) | (selScrFloat_.load() ? 2 : 0);
    }
    // 离开应用立即缓存(渲染线程序列化+后台写盘); 快照写入带 fsync+原子 rename
    void requestSaveSnapshot(std::string path, bool wait);
    // 状态是否有未落盘变化(自动保存轮询用)
    bool snapshotDirty() const {
        return canvas_.revision() != lastSavedRev_.load() ||
               frameRev_.load() != lastSavedFrameRev_ ||
               selStateRev_ != lastSavedSelStateRev_ ||
               layerMetaRev_ != lastSavedLayerMetaRev_;
    }
    bool buttonDownNow() const { return buttonDown_.load(); }
    // 预览中(形状/渐变/多边形)不能自动存盘, 否则会把预览像素写进快照
    bool previewActiveNow() const { return gradActive_ || gradDragging_ || shActive_ || polyActive_; }
    bool inputIdleMs(int ms) const {
        const auto now = std::chrono::steady_clock::now();
        return std::chrono::duration_cast<std::chrono::milliseconds>(now - lastInputT_).count() >= ms;
    }
    void requestSaveSnapshotForce(std::string path) {        // 加载外部项目后强制落盘(绕过同 revision 跳过)
        lastSavedRev_.store(0);
        lastSavedFrameRev_ = 0;
        requestSaveSnapshot(std::move(path), false);
    }
    void requestLoadSnapshot(std::string path);              // 启动恢复
    void requestUndo();     // UI 线程: 入队到渲染线程执行
    void requestRedo();
    void requestOverlay(bool checker, bool grid);   // 棋盘格/网格开关
    void requestDeselect();                          // 结束当前选区(操作上下文切换)
    void requestSelectAll();
    void requestZoomFit();
    void requestPasteLastCut();
    void requestCopySel();
    void requestDuplicateSel();
    void requestSetStretch(bool on);
    void requestSetPerspective(bool on);
    void requestFrameOp(int op, int arg);
    void requestSetRecord(bool on, std::string dir);
    void requestResumeRecord(std::string dir, int startCount, int elapsedMs);
    void recordUiAction(int kind, const std::string& label);   // 录制中: 追加 ui.csv 操作记录
    bool frameThumbApi(int frame, int layer, uint32_t* out, int n) { return frameThumb(frame, layer, out, n); }
    bool layerThumbApi(int frame, int layer, uint32_t* out, int n) { return layerThumb(frame, layer, out, n); }
    bool compositeThumbApi(uint32_t* out, int n) { return compositeThumb(out, n); }
    bool minimapThumbApi(uint32_t* out, int n) { return minimapThumb(out, n); }
    // 调整画布预览: 现生成 n×n 可见层合成缩略图(盒平均采样, 比 48² 缓存清晰)
    void makeCompositeThumbN(int n, std::vector<uint32_t>& out);
    int thumbRevision() const { return thumbRev_.load(std::memory_order_relaxed); }
    // 小地图面板开关: 打开时生成 192 采样, 关闭时不产生额外开销
    void requestSetMinimapActive(bool on) {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({55, on ? 1.0f : 0.0f, 0.0f, 0.0f});
    }
    void requestSelReset();
    void selectionUiRect(int out[18]);
    // 形状渐变预览状态: [0]active [1]shape [2]preset [3]range [4]tooLarge [5]angle [6]pos
    void gradUi(int out[7]);
    // 测距 UI 轻量数据(60Hz 轮询): on, x0,y0,x1,y1(画布), dragging, fixed, pending, sx0,sy0,sx1,sy1(屏幕)
    void measureUi(int out[12]);
    void requestDeleteSel();
    void requestConfirmSel();
    // 抽屉: 颜色调整/像素描边(基于选区浮层, 可取消还原)
    void requestSelAdjust(float h, float s, float b, float a, float blur, bool commit);
    void requestSelStroke(int mode, int size, int dirMask, bool outer, float depth, uint32_t color, bool commit);
    void requestSelRestore();
    void requestSelCancelPreview();
    void requestFloatToViewCenter();
    // 图层模式/平铺参数(正常/平铺/剪切蒙版) + 2.5D 叠层
    void requestSetLayerProps(int layer, int mode, int tw, int th, int ox, int oy,
                              bool showGrid, uint32_t gridColor, bool indep, int lockFrame, int blend);
    std::vector<int> layerPropsCopy();      // UI 线程: 每层 9 个值(mode,w,h,ox,oy,grid,color,indep,lock)
    void requestSet25D(bool on, int depth, int dir);
    void requestReset25DView();             // 旋转视角: yaw 归零
    void mode25DState(int out[3]);          // UI 线程: on,depth,dir
    void requestCancelSel();
    void requestRotateSel(int quarter);      // 1=顺时针 3=逆时针
    void requestFlipSel(bool horizontal);
    void setImportImage(int w, int h, const std::vector<uint32_t>& px);
    void setImportTextImage(int w, int h, const std::vector<uint32_t>& px);
    void setImportRegion(int x, int y, int w, int h, const std::vector<uint32_t>& px);
    void requestFlip(bool horizontal);
    void requestGrayscale();
    // 灰度观看(只改屏幕显示, 不动画布像素)
    void setGrayView(bool on);
    // 回放用: 当前画布屏幕外接矩形 [x0,y0,x1,y1]
    void canvasScreenRect(int* r);
    void requestApplyLevels(int channel, int black, int white, float gamma, int outBlack, int outWhite);
    void levelsHistogram(int channel, int out[256]);   // UI 线程调用: 等待渲染线程算当前层通道直方图
    // 导出 PNG(包围盒 + 流式 deflate, 在渲染线程编码; 阻塞调用方直到完成/超时)
    std::string exportPngFile(const std::string& path, int scale);
    // 多帧/多模式导出: kind 2=精灵图 3=逐帧(PNG写进 path 目录) 4=GIF动画 5=叠层图(层×帧)
    std::string exportModeFile(const std::string& path, int kind, int scale, int cols, int delayMs);
    void exportInfo(int out[6]);   // w,h,frameCount,loopStart,loopEnd,layers
    std::string exportPsdFile(const std::string& path);
    std::string stats();

private:
    void loop();
    void drainInput();
    void flushDirty();        // 落定收尾: 立即把画布脏区刷到屏幕
    void flushScreenDirty();  // 画布脏 tile -> mip 更新 + 屏幕脏矩形
    void markScreenRect(int cx0, int cy0, int cx1, int cy1);
    void renderScreen();      // 视口合成: 按 zoom 选 LOD 级, 采样金字塔写屏幕 staging
    // 图层元数据快照(可见/锁定/名称/不透明度/模式/平铺/混合/2.5D): 轻量, 只覆盖元数据
    struct MetaState {
        std::vector<uint8_t> vis, lock, lmode, tlGrid, tlIndep, blend;
        std::vector<std::string> names;
        std::vector<float> opacity;
        std::vector<int> tlW, tlH, tlOX, tlOY, tlLock;
        std::vector<uint32_t> tlGridCol;
        bool m25on = false;
        int m25depth = 5, m25dir = 0;
    };
    // 整文档快照(画布尺寸/全部帧/图层元数据): 图层增删/重排、帧增删/重排、调整画布尺寸用
    struct DocState {
        int w = 0, h = 0, active = 0, layers = 0, frame = 0;
        int playFps = 12, loopStart = 0, loopEnd = -1;
        std::vector<std::vector<uint8_t>> frames;   // frameData_(当前帧已 storeFrameData)
        MetaState meta;
    };
    // 撤销栈(画笔一笔 = 一条记录, tile 级 COW; 总字节封顶)
    struct Hist {
        int kind = 0;    // 0=tile 1=fill 2=meta 3=doc
        int frame = 0;   // tile/fill 记录所属帧(切帧后撤销先切回该帧)
        Canvas::UndoRecord rec;
        std::vector<Canvas::Span> spans;   // 填充: 被改写的平坦行段(旧色=cBefore)
        uint32_t cBefore = 0, cAfter = 0;
        int fillLayer = 0;
        bool isFill = false;
        std::unique_ptr<MetaState> meta;
        std::unique_ptr<DocState> doc;
        size_t bytes = 0;
    };
    std::vector<Hist> undo_, redo_;
    size_t undoBytes_ = 0, redoBytes_ = 0;
    int selEditDepth_ = 0;      // 选区内部 edit-guard 嵌套深度
    bool selEditOwn_ = false;   // 当前这条记录是选区 guard 自己开的
    bool selMoveOpen_ = false;  // 异步落定(tickCommit)正在用一条撤销记录
    // 滑条类元数据(不透明度/平铺/2.5D)连续事件合并成一条撤销, 避免拖动一次几十步
    int metaCoalesceKey_ = -1;
    int64_t metaCoalesceMs_ = 0;
    bool metaCoalesce(int key);
    // 按标签保存撤销/重做栈: 切标签时把当前栈停放到 parkedHist_, 切回再挂回(只在内存, 上限64MB)
    struct ParkedHist { std::vector<Hist> undo, redo; size_t undoBytes = 0, redoBytes = 0; };
    std::map<int, ParkedHist> parkedHist_;
    std::deque<int> parkedOrder_;
    int histTabKey_ = -1;
    void parkTabHistory();
    void setTabHistoryKey(int id);
    void dropTabHistory(int id);
    void beginStrokeRecord();
    void endStrokeRecord();
    void discardOpenStrokeRecord();   // 快照存取前丢弃异常未收尾的记录, 防止恢复后合并成幽灵撤销步
    // 选区工具内部落像素的撤销边界(可嵌套); 异步落定 tickCommit 的跨帧记录由 selMoveOpen_ 管
    void selEditBegin();
    void selEditEnd();
    void closeSelMoveRecordIfDone();
    void pushHist(Hist&& h, bool recordStep);
    void trimUndo();
    void pushMetaUndo();
    void pushDocUndo();
    std::unique_ptr<MetaState> captureMeta();
    std::unique_ptr<DocState> captureDoc();
    void applyMetaState(const MetaState& m);
    void applyDocState(DocState&& d);
    void switchFrameForHistory(int f);
    size_t histBytes(const Hist& h) const;
    void recordReplayFrame();   // 录制开启时: 当前画布合成 PNG + 元数据一行
    void clearHistory();
    void serviceExport();
    std::string doMultiExport(const std::string& path, int kind, int scale, int cols, int delayMs);
    bool frameCanvasForExport(int f, Canvas& tmp);   // 把指定帧解到 tmp(当前帧直接用 canvas_ 的调用方处理)
    void composeFrameCell(int f, int scale, std::vector<uint32_t>& cell, int& cellW, int& cellH);
    void exportLoopRange(int& lo, int& hi);
    std::mutex exMu_;
    std::condition_variable exCv_;
    bool exPending_ = false, exDone_ = false;
    std::unique_ptr<PngExporter> pngJob_;   // 增量 PNG 导出(渲染线程每帧切一段)
    std::atomic<bool> lvPending_{false};
    std::chrono::steady_clock::time_point measDownT_{};
    bool measMagShown_ = false;
    std::atomic<int> lvChannel_{0};
    std::mutex lvMu_;
    std::condition_variable lvCv_;
    bool lvDone_ = false;
    int lvHist_[256] = {};
    std::atomic<bool> lvPrevOn_{false};
    std::atomic<bool> lvPrevDirty_{false};
    std::atomic<int> lvPrevIdx_{-1};
    uint8_t lvPrevBuf_[2][3][256] = {};
    uint8_t lvAllLut_[4][256] = {};
    std::atomic<bool> lvAllPending_{false};
    std::mutex lvAllMu_;
    int exScale_ = 1;
    int exCols_ = 4;
    int exDelay_ = 100;   // GIF 帧间隔 ms
    int exKind_ = 0;   // 0=PNG 1=PSD 2=精灵图 3=逐帧 4=GIF 5=叠层图
    std::string exPath_, exResult_;

    Canvas canvas_;
    std::mutex layerMetaMu_;
    std::vector<std::pair<std::string, int>> layerMetaCache_;
    std::mutex layerNameMu_;
    std::string layerNamePending_;
    GlDisplay display_;
    ComposePool pool_;
    std::thread th_;
    std::atomic<bool> running_{false};
    std::mutex mu_;
    struct Ev { int action; float x, y, z; int64_t t = 0; };
    std::vector<Ev> queue_;
    std::vector<int> pending_;   // 旧分帧合成队列(视口渲染后不再使用)
    MipPyramid mip_;
    bool screenFull_ = true;
    int fullBurst_ = 0;   // 尺寸/旋转后的强制整屏重绘帧数(防首帧上传失败留下空白缓冲)
    bool plainPathOn_ = true;   // 调试 A/B: 纯画布快路径
    std::atomic<bool> redrawReq_{false};
    std::atomic<bool> grayView_{false};   // 灰度观看(屏幕 shader), 不改像素
    // 小地图点击: 视角平滑动画(旧 PanToCanvasPixelAnimated, SmoothDamp 0.15s)
    bool viewAnimActive_ = false;
    bool viewAnimZoom_ = false;
    float viewAnimTX_ = 0.f, viewAnimTY_ = 0.f, viewAnimTZ_ = 1.f;
    float viewAnimTPX_ = 0.f, viewAnimTPY_ = 0.f;
    float viewAnimTau_ = 0.07f;   // 时间常数: 小地图点击 0.07s, 时间轴开合 0.25s
    std::chrono::steady_clock::time_point viewAnimLastT_{};
    std::atomic<bool> playBrush_{false};
    int sdX0_ = 0, sdY0_ = 0, sdX1_ = -1, sdY1_ = -1;
    bool lastCut_ = false;
    int lastCutX0_ = -1, lastCutY0_ = -1, lastCutX1_ = -1, lastCutY1_ = -1;
    int lastCutLayer_ = -1;

    std::atomic<int> fps_{0};
    std::atomic<int> lastFrameUs_{0}, maxFrameUs_{0};
    std::atomic<int> jank_{0}, over_{0};   // 上一秒超 8.3ms 的帧数
    std::atomic<int> thumbRev_{0};          // 缩略图缓存版本(小地图只在内容变化时重建位图)
    std::atomic<int> fillCaps_{0};          // 油漆桶超预算被回滚的次数(UI 提示用)
    // 油漆桶延迟到抬手再填: 第一指按下后若第二指加入(双指缩放)则直接丢弃,
    // 不会先填一块再回滚, 也不会在撤销栈里留下误触记录
    bool bucketPending_ = false;
    int bucketX_ = 0, bucketY_ = 0;
    void bucketFillAt(int cx, int cy, int64_t evt);
    std::atomic<int> lastCutSkip_{0};       // 上次裁剪因超限被跳过的次数(UI 提示用)
    std::atomic<bool> pickMode_{false};
    std::atomic<int> pickRemain_{0};        // 取色器剩余次数(两色混合=2)
    std::atomic<bool> pickMulti_{false};    // 多色取色(两色混合): 点画布外不取消
    std::atomic<bool> eraseMode_{false};
    std::atomic<int> fillTol_{0};
    std::atomic<int> shapeType_{0};      // 0直线 1矩形 2椭圆 3套索填充 4柳叶 6测距 7多边形套索
    std::atomic<bool> shapeFill_{false};
    bool shActive_ = false;              // 正在拖图形(屏幕预览)
    int shX0_ = 0, shY0_ = 0, shX1_ = 0, shY1_ = 0, shRad_ = 1;
    // 完美线条(Aseprite 三点 L 判定 + 最后一点字节回滚); 仅 1 画布像素笔宽启用
    std::atomic<bool> ppLine_{false};
    bool ppPrevOk_ = false, ppTailOk_ = false;
    int ppPrevX_ = 0, ppPrevY_ = 0, ppTailX_ = 0, ppTailY_ = 0;
    uint32_t ppPatch_ = 0;
    int ppPatchX_ = 0, ppPatchY_ = 0;
    bool ppPatchOk_ = false;
    void ppReset();
    void ppFeed(int x, int y, uint32_t color);
    void ppFeedSegment(int x0, int y0, int x1, int y1, uint32_t color);
    // 宽笔刷平滑: 延迟一点提交(Drawpile/Krita 式), 任意笔宽都削 L 角, 无需回滚
    bool ppPendOk_ = false;
    int ppPendX_ = 0, ppPendY_ = 0;
    int ppLastStampX_ = 0, ppLastStampY_ = 0;
    void ppFeedSmooth(int x, int y, uint32_t color, int radius);
    void ppStampAt(int x, int y, uint32_t color, int radius);
    void ppFlushSmooth(uint32_t color, int radius);
    // 套索 / 多边形套索 / 柳叶 / 测距 状态
    std::vector<int> lasso_;
    std::vector<int> poly_;
    bool polyActive_ = false;
    bool polyHeld_ = false;            // 手指按住(显示橡皮筋)
    int polyPressX_ = 0, polyPressY_ = 0, polyCurX_ = 0, polyCurY_ = 0;
    long long polyTapMs_ = 0;
    int polyTapX_ = 0, polyTapY_ = 0;
    long long polyLastUpMs_ = 0;       // 上一次落点时间(双击闭合判定)
    // 形状画布像素预览(旧项目 DrawShapePreview/Backup): 预览=画布像素, 与落层同源
    std::vector<int> shPrevIdx_;
    std::vector<uint32_t> shPrevOld_;
    int shPrevLayer_ = -1;
    void shapePrevBegin();
    void shapePrevRestore();
    void shapePrevCommit();
    void shapePrevPaint(const std::vector<int>& xy, uint32_t color, int layer);
    void cancelShapePreview();   // 恢复预览像素并丢弃本次形状(切工具/切标签/取消)
    // 形状渐变(自带 alpha 蒙版): 拖出方形/圆形 → 裁剪状态预览 → 应用/取消
    bool gradActive_ = false;    // 预览中(已写入画布, 等待应用)
    bool gradDragging_ = false;  // 正在拖形状
    int gradShape_ = 0;          // 0=方形 1=圆形
    int gradPreset_ = 0;         // 方形0..3(上下/下上/左右/右左, -1=自定义角度) 圆形0..1(中心实/边缘实)
    int gradX0_ = 0, gradY0_ = 0, gradX1_ = 0, gradY1_ = 0;
    int gradLayer_ = -1;
    int gradCw_ = 0, gradCh_ = 0;
    std::atomic<int> gradRangeA_{100};
    int gradRange_ = 100;
    std::atomic<int> gradAngleA_{90};
    int gradAngle_ = 90;         // 方形渐变方向(0..359°)
    std::atomic<int> gradPosA_{0};
    int gradPos_ = 0;            // 透明位置(淡出起点, 0..100%)
    Canvas::UndoRecord gradRec_;   // 渐变前的 tile 快照(预览/取消时还原)
    void gradApplyPreview();     // 恢复旧像素后按当前预设重画(实时预览)
    void gradCommit();           // 应用: 保留预览像素, 落一条撤销
    void gradCancel();           // 取消: 恢复旧像素, 丢弃记录
    void gradRefreshAnts();      // 更新蚂蚁线(方形=bbox, 圆形=椭圆轮廓)
    void gradPaintPixels();      // 按形状/预设把 alpha 渐变写进当前层(记录已开)
    bool leafActive_ = false;
    int leafX0_ = 0, leafY0_ = 0, leafX1_ = 0, leafY1_ = 0;
    bool measOn_ = false;
    bool measFixed_ = false;          // 固定起点模式
    bool measSetStartPending_ = false; // 等待下一击设置固定起点
    int measX0_ = 0, measY0_ = 0, measX1_ = 0, measY1_ = 0;
    // 换标签/新文档/载入快照: 测距浮层必须清掉, 否则会残留在新画布上
    void resetMeasureState() {
        measOn_ = false; measMagShown_ = false;
        measFixed_ = false; measSetStartPending_ = false;
    }
    std::atomic<float> leafW_{0.33f};
    std::atomic<bool> shapeRatio_{false};
    std::atomic<float> shapePx_{6.0f};
    std::atomic<bool> selAllLayers_{false};
    // 回放帧: UI 线程原子换 shared_ptr, 渲染线程每帧 load 一次, 不阻塞画布编辑状态
    std::shared_ptr<const ReplayImage> replayImg_;
    std::atomic<bool> stretchModeReq_{false};   // UI 线程写, 渲染线程 drain 时应用
    std::atomic<bool> perspectiveReq_{false};   // 透视变形模式; 与 stretch 互斥
    std::atomic<bool> alphaLock_{false};
    // 调试: 画笔事件分支统计
    std::atomic<int> dbgKind_{0}, dbgDownLock_{-1}, dbgAxisSeg_{0}, dbgGenSeg_{0},
        dbgLastDx_{0}, dbgLastDy_{0};
    std::atomic<int> selShape_{0};
    int appliedSelShape_ = -1;
    std::atomic<int> wandSkip_{0};
    std::atomic<uint32_t> frameRev_{0};
    std::atomic<bool> onionOn_{false};
    std::atomic<bool> magnifierOn_{true};
    std::atomic<int> pngCompress_{1};
    // 放大镜取样用画布风格(与主画布一致): 棋盘格/画布外背景(ABGR)
    float magChkTile_ = 10.f;
    uint32_t magChkA_ = 0xFF808080u, magChkB_ = 0xFFFFFFFFu, magBg_ = 0xFF0D1117u;
    std::atomic<uint32_t> onionGen_{1};
    std::vector<uint32_t> onionPrev_, onionNext_;
    int onionW_ = 0, onionH_ = 0;
    uint32_t onionBuiltGen_ = 0;
    void rebuildOnion();
    static void compositeFrameBlob(const std::vector<uint8_t>& blob, std::vector<uint32_t>& out,
                                   int w, int h, const Canvas& canvas);
    void makeThumbFromBlob(const std::vector<uint8_t>& blob, std::vector<uint32_t>& out);
    uint32_t lastSavedFrameRev_ = 0;
    // 选区/浮层/变换没有改画布 revision, 需要单独计数才能让快照保存它们
    uint64_t selStateRev_ = 0;
    uint64_t lastSavedSelStateRev_ = 0;
    uint32_t layerMetaRev_ = 0;
    uint32_t lastSavedLayerMetaRev_ = 0;
    void runDebugScript();
    int dbgId_ = 0;
    std::mutex dbgMu_;
    std::string dbgText_, dbgLog_;
    std::atomic<bool> dbgPending_{false};
    std::mutex importMu_;
    std::vector<uint32_t> importPx_;
    int importW_ = 0, importH_ = 0;
    bool importPending_ = false;
    bool importText_ = false;   // 文字: 走"裁剪中(复制浮层)"路径, 不是普通汇入
    // 大图分块导入(不经过浮层/裁剪): 直接写入当前层
    std::vector<uint32_t> impRegPx_;
    int impRegX_ = 0, impRegY_ = 0, impRegW_ = 0, impRegH_ = 0;
    bool impRegPending_ = false;
    // 抽屉颜色调整/像素描边参数
    std::mutex selOpMu_;
    bool selAdjPending_ = false;
    float selAdjH_ = 0, selAdjS_ = 1, selAdjB_ = 1, selAdjA_ = 1, selAdjBlur_ = 0;
    bool selAdjCommit_ = false;
    bool selStrokePending_ = false;
    int selStMode_ = 0, selStSize_ = 2, selStDir_ = 15;
    bool selStOuter_ = false;
    float selStDepth_ = 0.3f;
    uint32_t selStColor_ = 0xFF000000u;
    bool selStCommit_ = false;
    bool selRestorePending_ = false;
    // 图层属性/2.5D 待执行参数
    std::mutex layerOpMu_;
    bool lypPending_ = false;
    int lypLayer_ = 0, lypMode_ = 0, lypW_ = 16, lypH_ = 16, lypOX_ = 0, lypOY_ = 0, lypLock_ = 0;
    int lypBlend_ = 0;
    bool lypShowGrid_ = false, lypIndep_ = false;
    uint32_t lypColor_ = 0x66FFFFFFu;
    bool m25Pending_ = false;
    bool m25On_ = false;
    int m25Depth_ = 5, m25Dir_ = 0;
    std::atomic<bool> m25OnA_{false};
    std::atomic<int> m25DepthA_{5}, m25DirA_{0};
    bool yawDrag25_ = false;          // 2.5D 单指拖动旋转视角中
    float yawLastX25_ = 0.f;
    int64_t yawLastT25_ = 0;          // 33ms 节流
    void applyYaw25D(float deg);      // 更新 raster/display/mip/屏幕
    std::mutex layerPropsMu_;
    std::vector<int> layerPropsCache_;
    void refreshLayerPropsCache();
    void selectionRectOp(int op);            // 0=90°CW 1=90°CCW 2=左右翻 3=上下翻
    // 画布快照(持久化): 只存已分配 tile(像素/纯色), 文件=EPXSNP1
    std::mutex snapMu_;
    std::condition_variable snapCv_;
    bool snapDone_ = true;
    std::string snapPath_;
    bool snapWait_ = false;
    std::atomic<uint64_t> lastSavedRev_{0};
    void applyLevelsImpl(int channel, int black, int white, float gamma, int outBlack, int outWhite);
    void applyLevelsAllImpl(const uint8_t luts[4][256]);
    void saveSnapshotNow(const std::string& path, bool wait);
    void saveSnapshotForce(const std::string& path) {
        lastSavedRev_.store(0);
        lastSavedFrameRev_ = 0;
        saveSnapshotNow(path, false);
    }
    void loadSnapshotNow(const std::string& path);
    std::atomic<int> selScrX0_{0}, selScrY0_{0}, selScrX1_{0}, selScrY1_{0};
    std::atomic<bool> selScrActive_{false}, selScrFloat_{false};
    std::vector<std::vector<uint8_t>> frameData_;
    int frame_ = 0;
    int frameLayerCount_ = 1;   // 旧项目: 图层是跨帧共享的行, 帧只是每层的像素
    void rewriteFramesDropLayer(int idx);
    void rewriteFramesMoveLayer(int from, int to);
    std::atomic<int> frameLimit_{0};
    std::atomic<int> frameCount_{1}, frameIdx_{0}, playing_{0};
    std::atomic<int> playFps_{12};
    std::atomic<int> loopStart_{0}, loopEnd_{-1};
    std::atomic<bool> recordOn_{false};
    std::atomic<int> recordN_{0};
    std::mutex recMu_;
    std::string recordDir_;
    std::chrono::steady_clock::time_point recordT0_{};
    int recordLastMs_ = 0;
    // 逐笔轨迹: x,y,dt 三元组(画布坐标, dt=距上一输入点ms); 与 meta.csv 一起落盘
    std::vector<int> recPts_;
    int64_t recPtsPrevMs_ = 0;
    void recPtsBegin(int64_t eventMs);                 // 落笔: 清空并记起点时间
    void recPtsAdd(int x, int y, int64_t eventMs);     // 输入点: 去重连续同像素, 上限 3000 点
    std::string recPtsCsvLocked();                     // 已持 recMu_ 时生成 x:y:dt;... 
    std::chrono::steady_clock::time_point playNext_{};
    std::mutex thumbMu_;
    std::vector<std::vector<std::vector<uint32_t>>> frameThumbs_;   // [frame][layer][48*48]
    std::vector<uint32_t> minimapThumb_;                            // 小地图 192*192(全可见层合成)
    std::vector<uint32_t> compositeThumb_;                          // 当前帧合成 48*48(设置预览/小地图兜底)
    std::atomic<bool> minimapActive_{false};
    void makeThumb(int frame, int layer, std::vector<uint32_t>& out);
    void makeCompositeThumb(std::vector<uint32_t>& out);
    void refreshFrameThumbs();
    void refreshMinimapThumb();
    bool frameThumb(int frame, int layer, uint32_t* out, int n);
    bool layerThumb(int frame, int layer, uint32_t* out, int n);
    bool compositeThumb(uint32_t* out, int n);
    bool minimapThumb(uint32_t* out, int n);
    void serializeCanvas(std::vector<uint8_t>& out);
    bool deserializeCanvas(const uint8_t* d, size_t n);
    void storeFrameData();
    void loadFrameData(int idx);
    void applyFrame(int idx);
    std::mutex selUiMu_;
    int selUi_[18] = {0};   // 屏幕矩形 + 四角 + 旋转柄 + flags + 画布选区宽/高/旋转角
    std::mutex gradUiMu_;
    int gradUi_[7] = {0};   // 形状渐变状态缓存(active,shape,preset,range,tooLarge,angle,pos)
    std::atomic<int> pickColor_{0};
    std::atomic<int> pickPrev_{0};   // 上一笔取色(两次吸色间隔很短时供两色混合取第一色)
    std::atomic<int> pickSeq_{0};
    std::atomic<int> lastDirty_{0};
    std::atomic<int> pendingN_{0};   // 待合成 tile 数(分帧队列)
    std::atomic<int> drainMs_{0};    // 上一轮脏区从入队到排空的耗时
    bool draining_ = false;
    std::chrono::steady_clock::time_point drainT0_;
    std::atomic<int> compUs_{0};
    std::atomic<int> lastRawX_{0}, lastRawY_{0}, lastCvX_{0}, lastCvY_{0}, lastAct_{-1};
    std::atomic<int> cntDown_{0}, cntMove_{0}, cntUp_{0}, cntStamp_{0};
    std::atomic<int> prevX_{-1}, prevY_{-1}, curX_{-1}, curY_{-1}, prevDelta_{-1};
    std::atomic<int> inkPixels_{0};
    std::atomic<int> pendingW_{0}, pendingH_{0};
    std::atomic<int> insL_{0}, insT_{0}, insR_{0}, insB_{0};
    std::atomic<bool> insetsDirty_{false};

    int lastX_ = -1, lastY_ = -1;
    int colorIdx_ = 0;
    std::atomic<int> tool_{0};
    std::atomic<uint32_t> brushColor_{0xFFFFFFFFu};
    std::atomic<float> brushPx_{6.0f};   // 画布像素直径(不随缩放变化)
    std::atomic<bool> buildup_{false};
    std::atomic<int> brushShape_{0}, brushHardness_{100}, brushSpacing_{10};
    std::atomic<int> brushScatter_{0}, brushAspect_{100}, brushAngle_{0}, brushFlow_{100};
    std::atomic<int> layerCount_{0}, activeLayer_{0};
    std::atomic<bool> buttonDown_{false};    std::chrono::steady_clock::time_point lastInputT_{};   // 画笔输入时间(空闲时允许后台搬移)
    std::atomic<bool> stress_{false};
    int stressPhase_ = 0;
    std::atomic<int> status_{0};   // 0=初始化中 1=就绪 2=失败
    // 选区 / 浮动选区
    SelectionTool sel_;
    bool selActive_ = false;
    int selX0_ = 0, selY0_ = 0, selX1_ = 0, selY1_ = 0;
    bool dragSel_ = false, dragMove_ = false;
    int dragStartX_ = 0, dragStartY_ = 0;
    bool floating_ = false;
    int fx_ = 0, fy_ = 0, fw_ = 0, fh_ = 0;
    std::vector<uint32_t> floatBuf_;
    std::atomic<int> selUs_{0}, moveUs_{0}, cropUs_{0};
};

} // namespace endpix
