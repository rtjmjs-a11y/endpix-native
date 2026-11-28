#pragma once
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <vector>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>

namespace endpix {

// 显示: 画布纹理 = AHardwareBuffer(EGLImage 零拷贝) 或 普通纹理(PBO/SubImage 兜底)。
// 每帧只锁一次脏区矩形, 合成结果直接写进纹理内存。
class GlDisplay {
public:
    bool init(ANativeWindow* win, int canvasW, int canvasH);
    void shutdown();
    void onSize(int w, int h);
    // 画布视口内缩(左右工具栏/上下栏占位), 单位屏幕像素
    void setInsets(int l, int t, int r, int b);

    // 返回脏区左上角可写指针 + 步长(像素)。begin/end 必须成对, 同一帧一次。
    uint32_t* beginUpdate(int x, int y, int w, int h, int* stridePx);
    bool endUpdate();   // true=像素已真正写进屏幕缓冲(AHB/纹理); false=锁失败需要重试
    // 只上传本批真正合成过的 tile(避免外接矩形里的其它 tile 用旧 staging 覆盖屏幕)

    void draw();
    bool screenToCanvas(float sx, float sy, int* cx, int* cy) const;
    // 2.5D 视角旋转(度): 内容围绕画布中心旋转, 屏幕/画布映射同步
    void setRotation25(float deg) {
        rot25Deg_ = deg;
        const float r = deg * 3.14159265358979f / 180.0f;
        rot25Cos_ = std::cos(r);
        rot25Sin_ = std::sin(r);
    }
    float rotation25Deg() const { return rot25Deg_; }
    bool rotation25Active() const { return rot25Deg_ != 0.f; }
    float rot25Cos() const { return rot25Cos_; }
    float rot25Sin() const { return rot25Sin_; }
    // 屏幕↔画布浮点变换(视口合成用), 2.5D 时围绕画布中心旋转
    void screenToCanvasF(float sx, float sy, float* cx, float* cy) const {
        if (!rotation25Active()) {
            *cx = (sx - inL_) / zoom_ + panX_;
            *cy = (sy - inT_) / zoom_ + panY_;
            return;
        }
        const float ccx = (cw_ * 0.5f - panX_) * zoom_ + inL_;
        const float ccy = (ch_ * 0.5f - panY_) * zoom_ + inT_;
        const float qx = sx - ccx, qy = sy - ccy;
        const float rx = rot25Cos_ * qx + rot25Sin_ * qy;
        const float ry = -rot25Sin_ * qx + rot25Cos_ * qy;
        *cx = (ccx + rx - inL_) / zoom_ + panX_;
        *cy = (ccy + ry - inT_) / zoom_ + panY_;
    }
    void canvasToScreenF(float cx, float cy, float* sx, float* sy) const {
        if (!rotation25Active()) {
            *sx = (cx - panX_) * zoom_ + inL_;
            *sy = (cy - panY_) * zoom_ + inT_;
            return;
        }
        const float ccx = (cw_ * 0.5f - panX_) * zoom_ + inL_;
        const float ccy = (ch_ * 0.5f - panY_) * zoom_ + inT_;
        const float ux = (cx - panX_) * zoom_ + inL_ - ccx;
        const float uy = (cy - panY_) * zoom_ + inT_ - ccy;
        *sx = ccx + rot25Cos_ * ux - rot25Sin_ * uy;
        *sy = ccy + rot25Sin_ * ux + rot25Cos_ * uy;
    }
    // 当前屏幕视口中心对应的画布坐标(上次裁剪粘贴定位用)
    void viewCenterCanvas(float* cx, float* cy) const {
        screenToCanvasF(vw_ * 0.5f, vh_ * 0.5f, cx, cy);
    }
    // 画布矩形→屏幕矩形(外接, 已不裁剪)
    void canvasRectToScreen(int x0, int y0, int x1, int y1,
                            int* sx0, int* sy0, int* sx1, int* sy1) const;
    // 当前画布在屏幕上的外接矩形(2.5D 旋转时也会包含旋转后的 bbox)
    void canvasScreenRect(int* r) const {
        canvasRectToScreen(0, 0, cw_, ch_, &r[0], &r[1], &r[2], &r[3]);
    }

    // 选区(蚂蚁线), 画布坐标; active=false 隐藏
    void setSelection(int x0, int y0, int x1, int y1, bool active);
    // 套索/魔棒精确轮廓: 线段 x0,y0,x1,y1...(画布坐标); 空=回到 bbox 蚂蚁线
    void setSelectionOutline(const std::vector<int>& segs);
    void setOverlay(bool checker, bool grid);   // 棋盘格/网格开关
    // 捏合期间把棋盘格/网格线直接烘进屏幕缓冲: 与画布同一帧, 不会出现"跟不上画布/线条丢失"
    void setBakeChecker(bool on) {
        bakeChecker_ = on;
        if (!on) return;
        // 烘帧期间每像素都要算棋盘/网格: 提前把除数换成倒数, 颜色换成 8 位整数,
        // 热循环里不再做除法/浮点混合/ lround(三次 libm 调用)。
        // 棋盘格 cell=chkTile_*zoom, 屏幕坐标 sx=cx*zoom ⇒ sx/cell == cx/chkTile_, zoom 直接约掉。
        const float gx = gridW_ * zoom_, gy = gridH_ * zoom_;
        bakeGridOn_ = gridOn_ && gridA_ > 0.001f &&
                      gx >= 6.0f && gy >= 6.0f && gridW_ > 0.001f && gridH_ > 0.001f;
        bakeChkFast_ = chkTile_ * zoom_ >= 4.0f && chkTile_ > 0.001f;
        bakeInvChk_ = bakeChkFast_ ? 1.0f / chkTile_ : 1.0f;
        bakeInvGw_ = gridW_ > 0.001f ? 1.0f / gridW_ : 1.0f;
        bakeInvGh_ = gridH_ > 0.001f ? 1.0f / gridH_ : 1.0f;
        // 线宽按屏幕 px 定义, 换算到画布单位比较
        bakeLwC_ = std::max(gridLineW_, 0.5f) / std::max(zoom_, 1e-4f);
        auto to8 = [](float v) -> int {
            const int x = (int)(v * 255.0f + (v >= 0.0f ? 0.5f : -0.5f));
            return x < 0 ? 0 : (x > 255 ? 255 : x);
        };
        bakeBg8_ = to8(bgR_) | (to8(bgG_) << 8) | (to8(bgB_) << 16);
        bakeChkA8_ = to8(chkAR_) | (to8(chkAG_) << 8) | (to8(chkAB_) << 16);
        bakeChkB8_ = to8(chkBR_) | (to8(chkBG_) << 8) | (to8(chkBB_) << 16);
        bakeGrid8_ = to8(gridR_) | (to8(gridG_) << 8) | (to8(gridB_) << 16);
        bakeGridA8_ = std::max(0, std::min(255, to8(gridA_)));
    }
    // 只算棋盘/画布外底色(不叠网格): 供"同一画布像素整段填充"的快路径复用
    inline uint32_t bakeBaseColor(float cx, float cy, bool inside, uint32_t v) const {
        const int a = (int)((v >> 24) & 255u);
        const int r = (int)(v & 255u), g = (int)((v >> 8) & 255u), b = (int)((v >> 16) & 255u);
        uint32_t base = bakeBg8_;
        if (inside && chkOn_ && a < 255) {
            int chk;
            if (bakeChkFast_) {
                chk = ((int)(cx * bakeInvChk_) + (int)(cy * bakeInvChk_)) & 1;
            } else {
                const float cell = std::max(chkTile_ * zoom_, 4.0f);
                chk = ((int)std::floor(cx * zoom_ / cell) +
                       (int)std::floor(cy * zoom_ / cell)) & 1;
            }
            base = chk ? bakeChkB8_ : bakeChkA8_;
        }
        int rr, gg, bb;
        if (a == 0) {
            rr = (int)(base & 255u); gg = (int)((base >> 8) & 255u); bb = (int)((base >> 16) & 255u);
        } else if (a == 255) {
            rr = r; gg = g; bb = b;
        } else {
            const int inv = 255 - a;
            rr = (r * a + (int)(base & 255u) * inv + 127) / 255;
            gg = (g * a + (int)((base >> 8) & 255u) * inv + 127) / 255;
            bb = (b * a + (int)((base >> 16) & 255u) * inv + 127) / 255;
        }
        return 0xFF000000u | ((uint32_t)bb << 16) | ((uint32_t)gg << 8) | (uint32_t)rr;
    }
    // 网格色叠到已有颜色上(8 位整数混合)
    inline uint32_t bakeGridOver(uint32_t c) const {
        const int ga = bakeGridA8_, inv = 255 - ga;
        const int r = (int)(c & 255u), g = (int)((c >> 8) & 255u), b = (int)((c >> 16) & 255u);
        const int rr = (r * inv + (int)(bakeGrid8_ & 255u) * ga + 127) / 255;
        const int gg = (g * inv + (int)((bakeGrid8_ >> 8) & 255u) * ga + 127) / 255;
        const int bb = (b * inv + (int)((bakeGrid8_ >> 16) & 255u) * ga + 127) / 255;
        return 0xFF000000u | ((uint32_t)bb << 16) | ((uint32_t)gg << 8) | (uint32_t)rr;
    }
    inline uint32_t bakeCheckerPixel(float cx, float cy, bool inside, uint32_t v) const {
        if (!bakeChecker_) return v;
        uint32_t c = bakeBaseColor(cx, cy, inside, v);
        // 捏合期间网格线也烘进同一帧: 与画布/棋盘格同步, 不再靠 shader 用上一帧变换画线
        if (inside && bakeGridOn_) {
            // 网格周期(gridW_/gridH_)也是画布单位, 同样不用除法
            const float fxC = cx - (float)(int)(cx * bakeInvGw_) * gridW_;
            const float fyC = cy - (float)(int)(cy * bakeInvGh_) * gridH_;
            if (fxC <= bakeLwC_ || fyC <= bakeLwC_) c = bakeGridOver(c);
        }
        return c;
    }
    // 烘帧快路径用: 网格是否在烘、格子尺寸/线宽/倒数、画布外底色
    bool bakeActive() const { return bakeChecker_; }
    bool bakeGridActive() const { return bakeChecker_ && bakeGridOn_; }
    // 棋盘格单元 >=1 画布像素: 一段屏幕像素(同一画布像素)内格子不会变, 可整段填充
    bool bakeCheckStable() const { return chkTile_ >= 1.0f; }
    float bakeGridWC() const { return gridW_; }
    float bakeGridHC() const { return gridH_; }
    float bakeGridInvW() const { return bakeInvGw_; }
    float bakeGridInvH() const { return bakeInvGh_; }
    float bakeGridLwC() const { return bakeLwC_; }
    uint32_t bakeBgColor() const { return 0xFF000000u | bakeBg8_; }
    // 某一画布 x 的网格竖线相位(画布单位, 0=线中心)
    inline float bakeGridFracX(float cx) const {
        return cx - (float)(int)(cx * bakeInvGw_) * gridW_;
    }
    inline float bakeGridFracY(float cy) const {
        return cy - (float)(int)(cy * bakeInvGh_) * gridH_;
    }
    // 放大镜窗口(屏幕像素, 含边框): 网格不再盖到镜面上, 镜内由 CPU 画放大后的网格
    void setLoupeRect(int x0, int y0, int x1, int y1) {
        loupeOn_ = true; loupeX0_ = x0; loupeY0_ = y0; loupeX1_ = x1; loupeY1_ = y1;
    }
    void clearLoupeRect() { loupeOn_ = false; }
    bool gridEnabled() const { return gridOn_; }
    bool checkerEnabled() const { return chkOn_; }
    float gridCellW() const { return gridW_; }
    float gridCellH() const { return gridH_; }
    float gridAlphaVal() const { return gridA_; }
    float gridRVal() const { return gridR_; }
    float gridGVal() const { return gridG_; }
    float gridBVal() const { return gridB_; }
    // 画布外观(旧调整设置): 网格横向/纵向间距、网格色+淡化、线宽(屏幕px)、棋盘格大小/双色、画布外背景色
    void setStyle(float gridW, float gridH, float gr, float gg, float gb, float ga,
                  float lineW, float chkTile, float ar, float ag, float ab,
                  float br, float bg, float bb, float bgr, float bgg, float bgb);
    // 灰度观看: 只影响屏幕显示, 不改画布像素
    void setGrayView(bool on) { grayView_ = on; }
    bool grayView() const { return grayView_; }
    // 浮起选区: 独立贴图叠加, 移动只改变换(不触发 CPU 重合成)
    bool setFloat(const uint32_t* px, int w, int h, int x, int y);
    // px 已是降采样后的图; logicalW/H 是它在画布上的实际尺寸
    bool setFloatScaled(const uint32_t* px, int w, int h, int logicalW, int logicalH, int dx, int dy);
    void moveFloat(int x, int y);
    // 双指缩放/平移: 以手势起点为基准绝对计算, 连续浮点(不取整), 避免逐帧增量误差
    void beginZoomGesture(float sx, float sy);
    void zoomGestureTo(float totalFactor, float sx, float sy);
    void endZoomGesture();
    bool zoomGestureActive() const { return zoomGesture_; }
    void clearFloat();
    // 裁剪后重建画布尺寸相关的缓冲
    bool resizeCanvas(int w, int h);

    // 视图变换: screen = (canvas - pan) * zoom
    void zoomAt(float factor, float sx, float sy);
    void panBy(float dxPx, float dyPx);
    void clampPan();
    float zoom() const { return zoom_; }
    float panX() const { return panX_; }
    float panY() const { return panY_; }
    void resetView();
    void setView(float zoom, float panX, float panY);   // 后台返回: 恢复缩放/平移

    bool usingAhb() const { return useAhb_; }
    int viewW() const { return vw_; }
    int viewH() const { return vh_; }
    // 画布->屏幕的缩放(适应窗口)
    float fitScale() const {
        if (vw_ <= 0 || vh_ <= 0 || cw_ <= 0 || ch_ <= 0) return 1.0f;
        float sx = (float)vw_ / cw_, sy = (float)vh_ / ch_;
        return sx < sy ? sx : sy;
    }

private:
    bool createTexture();
    void computeFit(float* scaleX, float* offsetX, float* scaleY, float* offsetY) const;

    EGLDisplay dpy_ = EGL_NO_DISPLAY;
    EGLContext ctx_ = EGL_NO_CONTEXT;
    EGLSurface surf_ = EGL_NO_SURFACE;
    EGLConfig cfg_ = nullptr;
    ANativeWindow* win_ = nullptr;

    int vw_ = 0, vh_ = 0, cw_ = 0, ch_ = 0;   // cw_/ch_ = 逻辑画布
    int inL_ = 0, inT_ = 0, inR_ = 0, inB_ = 0;  // 画布视口内缩
    int texW_ = 0, texH_ = 0;                  // 屏幕缓冲尺寸(与画布无关)
    int dw_ = 0, dh_ = 0;                      // 显示缓冲(屏幕)尺寸
    float zoom_ = 1.0f, panX_ = 0.0f, panY_ = 0.0f;
    float rot25Deg_ = 0.0f, rot25Cos_ = 1.0f, rot25Sin_ = 0.0f;
    // 棋盘格/网格必须使用"屏幕缓冲里那一帧"的变换, 不能用正在变化的新变换;
    // 否则超快速缩放时 CPU 还没画完新帧, 背景已经先动, 会露出棋盘格。
    bool haveDrawFrame_ = false;
    float drawZoom_ = 1.0f, drawPanX_ = 0.0f, drawPanY_ = 0.0f, drawRot25_ = 0.0f;
    bool bakeChecker_ = false;   // 本帧是否把棋盘格烘进屏幕缓冲
    bool drawBaked_ = false;     // 已上传那一帧是否是烘入棋盘格的; draw() 据此关着色器棋盘格
    // 烘帧热循环缓存(setBakeChecker 时按当前缩放/网格参数算好, 消除逐像素除法)
    bool bakeGridOn_ = false;    // 网格线是否需要烘(开且格子在屏幕上 >=6px)
    bool bakeChkFast_ = false;   // 棋盘格可用"画布单位"算(格子屏幕尺寸 >=4px)
    float bakeInvChk_ = 1.0f, bakeInvGw_ = 1.0f, bakeInvGh_ = 1.0f;
    float bakeLwC_ = 0.0f;       // 网格线宽(换算到画布单位)
    uint32_t bakeBg8_ = 0, bakeChkA8_ = 0, bakeChkB8_ = 0, bakeGrid8_ = 0;
    int bakeGridA8_ = 0;
    bool viewReady_ = false;
    bool zoomGesture_ = false;
    float zgBaseZoom_ = 1.0f, zgCanvasX_ = 0.0f, zgCanvasY_ = 0.0f;
    GLuint tex_ = 0, prog_ = 0, vbo_ = 0;
    GLint uScale_ = -1, uOffset_ = -1, uTex_ = -1, uUvScale_ = -1, uUvOffset_ = -1;
    GLint uCanvasRect_ = -1;
    GLint uOrigin_ = -1, uZoom_ = -1, uChkOn_ = -1, uGridOn_ = -1;
    GLint uRot_ = -1, uCenter_ = -1, uCanvasSize_ = -1;
    GLint uGridW_ = -1, uGridH_ = -1, uGridColor_ = -1, uGridAlpha_ = -1, uGridLineW_ = -1;
    GLint uChkTile_ = -1, uChkA_ = -1, uChkB_ = -1, uBgColor_ = -1, uGray_ = -1;
    GLint uLoupeRect_ = -1, uLoupeOn_ = -1;
    bool loupeOn_ = false;
    int loupeX0_ = 0, loupeY0_ = 0, loupeX1_ = 0, loupeY1_ = 0;
    bool chkOn_ = true, gridOn_ = true;
    bool grayView_ = false;
    float gridW_ = 10.f, gridH_ = 10.f, gridR_ = 1.f, gridG_ = 0.f, gridB_ = 0.f, gridA_ = 0.6f;
    float gridLineW_ = 0.75f, chkTile_ = 10.f;
    float chkAR_ = 0.8f, chkAG_ = 0.8f, chkAB_ = 0.8f;
    float chkBR_ = 1.f, chkBG_ = 1.f, chkBB_ = 1.f;
    float bgR_ = 0.05f, bgG_ = 0.067f, bgB_ = 0.09f;

    // 蚂蚁线
    bool selActive_ = false;
    int selX0_ = 0, selY0_ = 0, selX1_ = 0, selY1_ = 0;
    GLuint antsProg_ = 0, antsVbo_ = 0;
    GLsizei antsVertCount_ = 8;
    GLint antsScale_ = -1, antsOffset_ = -1, antsPhase_ = -1;
    float phase_ = 0.0f;
    struct FloatTile { GLuint tex; int ox, oy, w, h; };
    std::vector<FloatTile> floatTiles_;   // 浮起内容按 1024² 分块
    GLuint floatProg_ = 0;
    GLint fScale_ = -1, fOffset_ = -1, fUvScale_ = -1, fUvOffset_ = -1, fTex_ = -1;
    int floatW_ = 0, floatH_ = 0, floatX_ = 0, floatY_ = 0;
    bool haveFloat_ = false;
    bool floatFromCanvas_ = false;
    int floatSrcX_ = 0, floatSrcY_ = 0;

    bool useAhb_ = false;
    AHardwareBuffer* ahb_ = nullptr;
    EGLImageKHR img_ = EGL_NO_IMAGE_KHR;
    // 第二块屏幕 AHB: 写后备, 避免和合成器抢同一块 buffer
    AHardwareBuffer* ahbB_ = nullptr;
    EGLImageKHR imgB_ = EGL_NO_IMAGE_KHR;
    GLuint texB_ = 0;
    bool writeB_ = false, readB_ = false;
    int ahbStride_ = 0;
    GLuint screenTex() const { return (useAhb_ && readB_ && texB_) ? texB_ : tex_; }

    std::vector<uint32_t> staging_;  // 兜底路径
    bool updating_ = false;
    int upX_ = 0, upY_ = 0, upW_ = 0, upH_ = 0;
    uint32_t* upPtr_ = nullptr;
};

} // namespace endpix
