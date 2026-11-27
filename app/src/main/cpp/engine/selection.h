#pragma once
#include <cstdint>
#include <atomic>
#include <chrono>
#include <functional>
#include <string>
#include <vector>
#include "../canvas/canvas.h"
#include "../display/gl_display.h"

namespace endpix {

// 矩形选区 + 浮动移动 + 裁剪到选区
class SelectionTool {
public:
    void init(Canvas* c, GlDisplay* d) { canvas_ = c; display_ = d; }
    void setMode(int m) { mode_ = m; if (m == 0) { /* 保留选区 */ } }
    int mode() const { return mode_; }
    // 裁剪工具栏「透明叠加」: false(默认)=半透明像素落定时直接覆盖目标; true=按 over 混合
    void setAlphaOverlay(bool on) { alphaOverlay_.store(on); }
    bool alphaOverlay() const { return alphaOverlay_.load(); }
    // 选区内长按裁剪的触发时长(ms), 设置里可调; 选区外瞬移仍固定 1s
    void setHoldMs(int ms) {
        int v = ms < 100 ? 100 : (ms > 10000 ? 10000 : ms);
        holdMs_.store(v);
    }
    int holdMs() const { return holdMs_.load(); }
    // 选择形状: 0=方框 1=套索 2=魔棒(掩码)
    void setSelShape(int s) { selShape_ = s; if (s != 1) lassoPts_.clear(); if (s == 0) clearMask(); }
    // 调试/回归接口(画布坐标)
    void debugLasso(const std::vector<int>& pts) { lassoPts_ = pts; lassoFinish(); }
    void debugWand(int x, int y) { wandSelect(x, y); }
    void debugMove(int dx, int dy);
    void debugRect(int a, int b, int c, int d) { setRectDirect(a, b, c, d); }
    int selShape() const { return selShape_; }
    bool maskedSelection() const { return !mask_.empty(); }
    int maskPixelCount() const;
    bool takeMaskSkipped() { const bool b = maskSkipped_; maskSkipped_ = false; return b; }
    // 画笔/选区命中: 有蒙版时只认蒙版内像素
    bool maskAt(int x, int y) const {
        if (mask_.empty()) return true;
        const int mx = x - x0_, my = y - y0_;
        return mx >= 0 && my >= 0 && mx < maskW_ && my < maskH_ && mask_[(size_t)my * maskW_ + mx] != 0;
    }
    bool active() const { return active_; }
    bool floating() const { return floating_; }
    bool gpuFloat() const { return gpuFloat_; }

    void handle(int action, int cx, int cy);   // 画布坐标
    void tick();                               // 每帧: 长按计时(手指不动也能在1秒时裁剪/瞬移)
    void cancelGesture();                       // 双指按下: 取消未完成的框选/套索/长按, 保留已完成选区
    void commitFloat();                    // 逻辑落定: 先不搬像素, 浮起图先顶着
    void tickCommit(size_t pixelBudget);   // 空闲时后台分帧搬
    void finishPending();                  // 必须画布最终态时: 立即把剩余搬完(可能卡一下)
    void finishCommit();                   // 搬完 + 撤掉落点贴图
    void endHold();                        // 合成排空后撤掉落点贴图(逻辑落定未完成时保留)
    bool pendingMove() const { return pendingMove_; }
    bool applyAllowed() const { return applyAllowed_; }
    bool holdFloat() const { return holdFloat_; }
    void noteViewChange() { if (pendingMove_) applyAllowed_ = true; }   // 缩放/平移=已开始别的操作
    void setFlush(std::function<void()> f) { flushCb_ = std::move(f); } // 撤贴图前把底图刷成最终态
    // 浮层移动时只标屏幕矩形(不失效 mip, 内容没变)
    void setMarkScreen(std::function<void(int,int,int,int)> f) { markScreen_ = std::move(f); }
    // 屏幕分辨率浮层: 引擎在 renderScreen 里按当前 zoom 从 mip 采样
    bool floatVisible() const { return floating_ || holdFloat_; }
    int fSrcX() const { return srcX_; } int fSrcY() const { return srcY_; }
    int fDstX() const { return fx_; } int fDstY() const { return fy_; }
    int fW() const { return fw_; } int fH() const { return fh_; }
    // 显示缓存失效(画布块被清空但不必重合成时也要通知, 否则 LOD 金字塔留下源区域残影)
    void setInvalidate(std::function<void(int,int,int,int)> f) { invalidate_ = std::move(f); }
    // 选区内部落像素的撤销边界: 引擎收到 begin 开一条撤销记录, end 收尾(可嵌套, 外层只触发一次)
    void setEditGuard(std::function<void()> begin, std::function<void()> end) {
        editBegin_ = std::move(begin); editEnd_ = std::move(end);
    }
    void cut();          // 剪切选区->浮起(原位置清空), 与 endpix 的"裁剪"一致
    // ---- 裁剪态变换(适配视口架构): 预览逆映射采样源画布, 确认时一次落层 ----
    void setStretchMode(bool on) {
        stretchMode_ = on;
        if (on) { perspective_ = false; dragKind_ = -1; }
    }
    // 透视变形: 四角自由拖拽; 与拉伸旋转互斥(开启时自动关闭拉伸旋转)
    void setPerspectiveMode(bool on);
    bool perspectiveMode() const { return perspective_; }
    void setAllLayers(bool on) { allLayers_ = on; }
    bool allLayers() const { return allLayers_; }
    bool stretchMode() const { return stretchMode_; }
    float rotationDeg() const { return tRot_; }
    bool xformActive() const;
    void resetTransform();
    // 预览逆映射每帧预计算: 渲染热循环里逐像素调用, 不再算 sin/cos
    struct MapPre {
        float ccx = 0.f, ccy = 0.f;
        float c = 1.f, s = 0.f;
        float invSx = 1.f, invSy = 1.f;
        float srcCx = 0.f, srcCy = 0.f;
        bool ext = false;
        bool persp = false;
        // 透视: 目标画布坐标 -> 单位方块(u,v) 的逆矩阵
        float h00 = 1.f, h01 = 0.f, h02 = 0.f;
        float h10 = 0.f, h11 = 1.f, h12 = 0.f;
        float h20 = 0.f, h21 = 0.f, h22 = 1.f;
    };
    void prepareMap(MapPre* m) const;
    bool mapToSourcePre(const MapPre& m, float cx, float cy, int* sx, int* sy) const;
    uint32_t extAtPre(const MapPre& m, int cx, int cy) const;
    // 后台/快照持久化: 把未落定的裁剪浮层/选区/变换一起存盘, 回来继续操作
    void prepareForSnapshot();
    void clearState();   // 读档/换画布: 直接丢弃选区状态(不提交、不写像素)
    void saveState(std::vector<uint8_t>& out) const;
    bool loadState(const uint8_t* data, size_t len, size_t& off);
    // 尺寸标签: 浮层显示原始选区大小(旧项目 Selection.width/height), 不是旋转后的包围盒
    int labelW() const { return floating_ ? fw_ : (x1_ - x0_ + 1); }
    int labelH() const { return floating_ ? fh_ : (y1_ - y0_ + 1); }
    // 调试/回归: 直接设定变换(不经过手柄), 用于验证预览与落层一致
    void debugSetTransform(float sx, float sy, float rot) {
        if (!floating_) return;
        tScaleX_ = std::max(0.05f, std::min(20.f, sx));
        tScaleY_ = std::max(0.05f, std::min(20.f, sy));
        tRot_ = rot;
        pqValid_ = false;
        fullDirty_ = true;
    }
    // 调试/回归: 直接设定透视四角(画布坐标 TL/TR/BL/BR), 用于验证预览与落层
    void debugSetPerspective(const float* q8) {
        if (!floating_ || !q8) return;
        for (int i = 0; i < 4; i++) {
            pq_[i * 2] = q8[i * 2] - fx_;
            pq_[i * 2 + 1] = q8[i * 2 + 1] - fy_;
        }
        pqValid_ = true;
        perspective_ = true;
        stretchMode_ = false;
        dragKind_ = -1;
        fullDirty_ = true;
    }
    void rotate90();          // 只改变换状态
    void flipSelH(); void flipSelV();
    void commitTransform();   // 确认: 逆映射写回 + 清源(一次性)
    // 预览: 画布坐标 -> 源像素(含缩放/旋转/翻转); 返回 false=不在源矩形内
    bool mapToSource(float cx, float cy, int* sx, int* sy) const;
    // 变换后预览在画布上的包围盒(以 fx/fy 为基准, 不改状态); 用于拖动时标脏
    void transformedBBox(int fx, int fy, int* bx0, int* by0, int* bx1, int* by1) const;
    // 浮层拖动: 只标屏幕脏矩形(旧/新变换包围盒的并集), 不失效 mip
    void markMoveScreen(int oldFx, int oldFy, int newFx, int newFy);
    // 手柄(画布坐标): 四角 0..3(左上/右上/左下/右下) + 旋转柄
    void corners(float* xy8) const;
    void rotateHandle(float* hx, float* hy) const;
    bool hitHandle(float cx, float cy, int* kind, int* idx) const;  // kind 0=角 1=旋转
    void beginHandleDrag(int kind, int idx, float cx, float cy);
    void updateHandleDrag(float cx, float cy);
    void endHandleDrag();
    void cancelFloat();  // 取消裁剪: 丢弃浮层, 源像素保持原样
    void copyRect();     // 复制: 把选区像素抓进上次裁剪缓存(不擦除)
    void duplicateSelection(); // 复制按钮: 原地生成可拖动的副本浮层, 不改源像素
    void deleteRect();   // 移除: 清空选区像素(活动层/全部层)
    void deselect();     // 结束选区(有浮起/未搬完先落定), 撤蒙版+蚂蚁线
    void selectAll();    // 全选画布
    // 上次裁剪 / 汇入图像: 像素缓存在选区工具里, 粘贴=立即写到活动层中心并选中该矩形
    void captureRect();                                  // 把当前选区矩形从活动层抓进 lastCut_
    void rect(int* x0, int* y0, int* x1, int* y1) const { *x0 = x0_; *y0 = y0_; *x1 = x1_; *y1 = y1_; }
    void setRectDirect(int a, int b, int c, int d);
    bool hasLastCut() const { return lastW_ > 0 && lastH_ > 0; }
    bool lastCutSkipped() const { return lastCutSkipped_; }
    const std::vector<uint32_t>& lastCutPixels() const { return lastCut_; }
    // 裁剪目标像素(活动层裁剪 + 可见合成): 有目标时优先用这个
    const std::vector<uint32_t>& cropTargetPixels() const { return cropTarget_; }
    int cropTargetW() const { return cropTargetW_; }
    int cropTargetH() const { return cropTargetH_; }
    bool hasCropTarget() const {
        return cropTargetW_ > 0 && cropTargetH_ > 0 &&
               cropTarget_.size() >= (size_t)cropTargetW_ * cropTargetH_;
    }
    void clearLastCut() { lastCut_.clear(); lastW_ = lastH_ = 0; }
    void setLastCut(int w, int h, const std::vector<uint32_t>& px) {
        if (w <= 0 || h <= 0 || px.size() < (size_t)w * h) return;
        lastCut_ = px; lastW_ = w; lastH_ = h;
    }
    void pasteLastCut();
    // 上次裁剪直接落层: 以当前屏幕视角中心为基准, 不进入裁剪/浮起状态(仍可撤销)
    void pasteLastCutAtViewCenter(float viewCx, float viewCy);
    void pasteImage(int w, int h, const uint32_t* px);    // 引擎内部 ABGR
    // 导入图像: 保持原尺寸放进浮层缓冲(超出画布部分不显示但保留), 拖动可移回, 确认时写可见部分
    void importImage(int w, int h, const uint32_t* px);
    bool extActive() const { return extActive_; }
    bool dbgBufFloat() const { return bufFloat_; }
    bool dbgGpuFloat() const { return gpuFloat_; }
    bool dbgCopyFloat() const { return copyFloat_; }
    bool dbgTextFloat() const { return textFloat_; }
    size_t dbgBufSize() const { return buf_.size(); }
    // 导入浮层移动时强制整屏重绘(脏矩形边界在某些缩放下会留残影)
    bool consumeFullDirty() { const bool b = fullDirty_; fullDirty_ = false; return b; }
    uint32_t extAt(int cx, int cy) const;
    // 变换/移动预览采样: 与落层用同一源(活动层; compositeAll=true时=可见层合成)
    uint32_t previewPixel(int sx, int sy, bool compositeAll) const;
    // 套索/魔棒精确轮廓(画布坐标线段 x0,y0,x1,y1...), 旧项目用蒙版边缘而非 bbox 蚂蚁线
    const std::vector<int>& maskOutline() const { return maskOutline_; }
    bool pastePending() const { return !pendingPaste_.empty(); }
    void applyPendingPaste();                            // drainInput 里调用(渲染线程)
    int lastW() const { return lastW_; } int lastH() const { return lastH_; }
    void extractToFloat();
    void crop();          // 保留: 真正缩画布(当前按钮不用)
    void overlay(uint32_t* dst, int stride, int tx0, int ty0, int tw, int th) const;
    void overlayLegacy(uint32_t* dst, int stride, int tx0, int ty0, int tw, int th) const;
    std::string describe() const;

    void setMetrics(double selMs, double moveMs) { selMs_ = selMs; moveMs_ = moveMs; }

private:
    Canvas* canvas_ = nullptr;
    GlDisplay* display_ = nullptr;
    int mode_ = 0;
    bool active_ = false;
    int x0_ = 0, y0_ = 0, x1_ = 0, y1_ = 0;
    bool dragSel_ = false, dragMove_ = false;
    int lastX_ = 0, lastY_ = 0;
    int pressX_ = 0, pressY_ = 0;   // 按下点(判定拖动阈值, 避免单击就剪切)
    // 旧项目选取手势: 选区内长按1秒裁剪, 选区外长按1秒瞬移, 12px 迟滞/0.35s 重框判定
    enum class Hold { None, Inside, Outside };
    Hold hold_ = Hold::None;
    std::chrono::steady_clock::time_point holdT_{};
    int holdX_ = 0, holdY_ = 0;
    bool holdAborted_ = false;
    bool floatGate_ = false;
    int floatGateX_ = 0, floatGateY_ = 0;
    // 拉伸手柄: 边手柄单轴 + 2px 起步门闩
    float dragBaseScaleX_ = 1.f, dragBaseScaleY_ = 1.f;
    int handlePressX_ = 0, handlePressY_ = 0;
    bool handleMoved_ = false;
    bool floating_ = false;
    std::atomic<bool> alphaOverlay_{false};   // 透明叠加: 落定时半透明像素 over 混合(默认 false=直接覆盖)
    std::atomic<int> holdMs_{1000};           // 选区内长按裁剪阈值(ms)
    int fx_ = 0, fy_ = 0, fw_ = 0, fh_ = 0;
    std::vector<uint32_t> buf_;
    double selMs_ = 0, moveMs_ = 0;
    bool gpuFloat_ = false;
    bool commitPending_ = false;  // 落定的脏区还在后台分块合成
    bool holdFloat_ = false;      // 合成完成前沿用浮起贴图, 落点区域不闪旧内容
    int srcX_ = 0, srcY_ = 0;   // 浮起内容的源矩形左上(画布坐标)
    int floatNonZero_ = 0;      // 诊断: 浮起小图里非零像素数
    double commitMs_ = 0;       // 落定搬移耗时(分帧累计)

    // 落定分帧搬运状态
    bool pendingMove_ = false;    // 逻辑落定已记录, 但像素还没搬完(可能一点没搬)
    bool pendingFloatCurrent_ = false;  // 当前选区框正好是"落点贴图"的框(可直接再拖)
    bool applyAllowed_ = false;   // 用户已开始新选区 → 允许后台开搬
    int cX_ = 0, cY_ = 0, cW_ = 0, cH_ = 0, cDx_ = 0, cDy_ = 0, cRow_ = 0;
    // 旧项目"本层蒙版"落定掩码(cW_×cH_ 行主序, 1=落点本层有像素可贴; 空=未启用)
    std::vector<uint64_t> moveDstMask_;
    void buildMoveMask();
    // 变换状态
    bool stretchMode_ = false;
    bool allLayers_ = false;
    float tScaleX_ = 1.f, tScaleY_ = 1.f, tRot_ = 0.f;
    bool flipH_ = false, flipV_ = false;
    // 透视变形: 四角局部坐标(相对 fx_/fy_, 顺序 TL/TR/BL/BR), 与拉伸旋转互斥
    bool perspective_ = false;
    bool pqValid_ = false;
    float pq_[8] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
    void affineCornersAt(int fx, int fy, float* xy8) const;
    void cornersAt(int fx, int fy, float* xy8) const;
    bool quadIdentity() const;
    int dragKind_ = -1, dragIdx_ = 0;       // -1 无; 0=角; 1=旋转
    float dragStartAngle_ = 0.f, dragBaseRot_ = 0.f;
    float dragStartCX_ = 0.f, dragStartCY_ = 0.f;
    float dragStartLocalX_ = 0.f, dragStartLocalY_ = 0.f;
    float dragAnchorX_ = 0.f, dragAnchorY_ = 0.f;   // 拉伸锚点(对角/对边), 旧项目语义
    float dragGrabX_ = 0.f, dragGrabY_ = 0.f;       // 把手-手指偏移, 首帧不跳
    std::vector<uint32_t> cScratch_;
    std::function<void()> flushCb_;   // 引擎提供: 把现有脏区全部合成/上传
    std::function<void(int,int,int,int)> markScreen_;   // 引擎提供: 只标屏幕脏矩形
    std::function<void(int,int,int,int)> invalidate_;   // 引擎提供: 失效 LOD/屏幕缓存矩形
    std::function<void()> editBegin_, editEnd_;         // 引擎提供: 落像素的撤销记录边界
    int editDepth_ = 0;
    // 作用域守卫: 任何可能写画布像素的选区方法都包一层, 嵌套只由最外层开/收记录
    struct EditScope {
        SelectionTool* t;
        explicit EditScope(SelectionTool* s) : t(s) {
            if (t->editDepth_++ == 0 && t->editBegin_) t->editBegin_();
        }
        ~EditScope() {
            if (--t->editDepth_ == 0 && t->editEnd_) t->editEnd_();
        }
    };
    std::vector<uint32_t> lastCut_;                     // 上次裁剪像素(ABGR)
    int lastW_ = 0, lastH_ = 0;
    bool lastCutSkipped_ = false;
    // 裁剪目标(可见合成像素): 「裁剪导出」「新标签打开」用它, 与屏幕看到的一致
    std::vector<uint32_t> cropTarget_;
    int cropTargetW_ = 0, cropTargetH_ = 0;
    std::vector<uint32_t> ext_;                          // 导入浮层(原尺寸)
    // 抽屉: 颜色调整/像素描边 (基于浮层原图做预览, 取消可还原)
    std::vector<uint32_t> origExt_;
    int origExtW_ = 0, origExtH_ = 0;
    bool origSaved_ = false;
    bool matExt_ = false;      // ext_ 来自选区内容(提交前需先清掉源区域, 避免 over 叠色)
public:
    bool materializeExt();     // 把选区内容搬进 ext_(并清空源区域), 供抽屉预览/描边使用
    void cancelExtPreview();   // 丢弃抽屉预览, 还原画布状态但保留选区(切换抽屉页用)
    void centerFloatAtViewCenter(); // 汇入文字/图像后把浮层放到视口中心
    void importTextAtViewCenter(int w, int h, const uint32_t* px); // 文字: 直接进入裁剪中(复制浮层)
    void saveOrigIfNeeded();
    void restoreOrig();
    void refreshFloatRect();
    void markExtDirty();       // 抽屉预览: 只重绘浮层覆盖的屏幕区域(不失效 mip)
    void adjustHSBA(float hueDeg, float satMul, float briMul, float alphaMul, float blurAmt);
    void applyStroke(int mode, int size, int dirMask, bool outer, float depth, uint32_t color);
    int extW_ = 0, extH_ = 0;
    bool extActive_ = false;
    bool fullDirty_ = false;
    std::vector<uint32_t> pendingPaste_;                // 待粘贴(下个 drainInput 执行)
    int pasteW_ = 0, pasteH_ = 0;
    // ---- 套索/魔棒掩码选区 ----
    void clearMask() { mask_.clear(); maskW_ = maskH_ = 0; maskOutline_.clear(); fullDirty_ = true; }
    void rebuildMaskOutline();
    void lassoBegin(int cx, int cy);
    void lassoAdd(int cx, int cy);
    void lassoFinish();
    void wandSelect(int cx, int cy);
    void extractMasked();          // 蒙版剪切 -> buf_ 浮层
    void commitMaskedFloat();      // 蒙版浮层落点(over)
    void commitMaskedTransform();  // 蒙版浮层按缩放/旋转落点
    int selShape_ = 0;
    std::vector<uint8_t> mask_;    // maskW_*maskH_, 0/255, 原点=(x0_,y0_)
    std::vector<int> maskOutline_; // 蒙版边缘线段(画布坐标)
    int maskW_ = 0, maskH_ = 0;
    bool bufFloat_ = false;        // 浮层来自 buf_(蒙版剪切), 不走 gpu 贴图
    bool copyFloat_ = false;       // 当前浮层是"复制"副本: 取消时丢弃, 不回写源位置
    bool textFloat_ = false;       // 文字放置浮层: 确认时忽略"本层蒙版"限制(任意位置可落字)
    bool maskSkipped_ = false;
    std::vector<int> lassoPts_;    // 套索折线(画布坐标)
};

} // namespace endpix
