#include "selection.h"
#include "../raster/raster.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <climits>
#include <cmath>
#include <android/log.h>
#define LOGI_SEL(...) __android_log_print(ANDROID_LOG_INFO, "endpix", __VA_ARGS__)
// 方法内落像素的撤销边界(嵌套只由最外层开/收记录)
#define SEL_EDIT_GUARD SelectionTool::EditScope _selEditScope_(this);

namespace endpix {

using Clk = std::chrono::steady_clock;
static double ms(Clk::time_point t) {
    return std::chrono::duration<double, std::milli>(Clk::now() - t).count();
}

// 单位方块(0,0)(1,0)(0,1)(1,1) -> 目标四边形 q(TL,TR,BL,BR) 的 3x3 矩阵及其逆
// q 为画布坐标; 输出 out 为目标点 -> (u,v) 的逆矩阵(行主序, 齐次坐标)
static bool buildPerspInverse(const float* q, float* out) {
    const double x0 = q[0], y0 = q[1];   // TL
    const double x1 = q[2], y1 = q[3];   // TR
    const double x2 = q[4], y2 = q[5];   // BL
    const double x3 = q[6], y3 = q[7];   // BR
    double a, b, c, d, e, f, g, h;
    const double dx1 = x1 - x3, dx2 = x2 - x3;
    const double dx3 = x0 - x1 + x3 - x2;
    const double dy1 = y1 - y3, dy2 = y2 - y3;
    const double dy3 = y0 - y1 + y3 - y2;
    const double den = dx1 * dy2 - dx2 * dy1;
    if (std::abs(dx3) < 1e-9 && std::abs(dy3) < 1e-9) {
        // 平行四边形
        a = x1 - x0; b = x2 - x0; c = x0;
        d = y1 - y0; e = y2 - y0; f = y0;
        g = 0.0; h = 0.0;
    } else {
        if (std::abs(den) < 1e-9) return false;
        g = (dx3 * dy2 - dx2 * dy3) / den;
        h = (dx1 * dy3 - dx3 * dy1) / den;
        a = x1 - x0 + g * x1;
        b = x2 - x0 + h * x2;
        c = x0;
        d = y1 - y0 + g * y1;
        e = y2 - y0 + h * y2;
        f = y0;
    }
    // H = [a b c; d e f; g h 1]; 逆矩阵用伴随矩阵
    const double A = e - f * h;
    const double B = c * h - b;
    const double C = b * f - c * e;
    const double D = f * g - d;
    const double E = a - c * g;
    const double F = c * d - a * f;
    const double G = d * h - e * g;
    const double H = b * g - a * h;
    const double I = a * e - b * d;
    const double det = a * A + b * D + c * G;
    if (std::abs(det) < 1e-12) return false;
    const double inv = 1.0 / det;
    // 直接按伴随矩阵行主序: [A B C; D E F; G H I] / det
    const double m[9] = {A * inv, B * inv, C * inv,
                         D * inv, E * inv, F * inv,
                         G * inv, H * inv, I * inv};
    for (int i = 0; i < 9; i++) out[i] = (float)m[i];
    return true;
}

// 透视四角必须是凸四边形且面积足够, 否则逆映射会翻面/出现自交
static bool quadConvex(const float* q) {
    // 多边形顺序 TL,TR,BR,BL = 0,1,3,2
    const int order[4] = {0, 1, 3, 2};
    double area = 0.0;
    for (int i = 0; i < 4; i++) {
        const int a = order[i], b = order[(i + 1) & 3];
        area += (double)q[a * 2] * q[b * 2 + 1] - (double)q[b * 2] * q[a * 2 + 1];
    }
    if (std::abs(area) < 8.0) return false;
    const double sign = area > 0 ? 1.0 : -1.0;
    for (int i = 0; i < 4; i++) {
        const int a = order[i], b = order[(i + 1) & 3], c = order[(i + 2) & 3];
        const double cross =
            ((double)q[b * 2] - q[a * 2]) * ((double)q[c * 2 + 1] - q[b * 2 + 1]) -
            ((double)q[b * 2 + 1] - q[a * 2 + 1]) * ((double)q[c * 2] - q[b * 2]);
        if (cross * sign <= 0.5) return false;
    }
    return true;
}

static bool perspMap(const SelectionTool::MapPre& m, float cx, float cy, float* u, float* v) {
    if (!m.persp) return false;
    const float X = m.h00 * cx + m.h01 * cy + m.h02;
    const float Y = m.h10 * cx + m.h11 * cy + m.h12;
    const float W = m.h20 * cx + m.h21 * cy + m.h22;
    if (std::abs(W) < 1e-8f) return false;
    *u = X / W; *v = Y / W;
    return *u >= 0.f && *u < 1.f && *v >= 0.f && *v < 1.f;
}

void SelectionTool::handle(int action, int cx, int cy) {
    SEL_EDIT_GUARD
    if (!canvas_) return;
    const auto now = Clk::now();
    const float zoom = display_ ? std::max(0.0001f, display_->zoom()) : 1.f;
    const int cw = canvas_->width(), ch = canvas_->height();

    // 变换手柄: 拉伸旋转=四角+四边中点+旋转; 透视=四角自由拖
    if (action == 0 && floating_ && (stretchMode_ || perspective_)) {
        int kind = -1, idx = 0;
        if (hitHandle((float)cx, (float)cy, &kind, &idx)) {
            beginHandleDrag(kind, idx, (float)cx, (float)cy);
            handlePressX_ = cx; handlePressY_ = cy; handleMoved_ = false;
            lastX_ = cx; lastY_ = cy;
            return;
        }
    }
    if (action == 1 && dragKind_ >= 0) {
        if (!handleMoved_) {
            const float gx = (float)(cx - handlePressX_), gy = (float)(cy - handlePressY_);
            const float gate = 2.f / zoom;
            if (gx * gx + gy * gy <= gate * gate) return;
            handleMoved_ = true;
        }
        updateHandleDrag((float)cx, (float)cy);
        lastX_ = cx; lastY_ = cy;
        return;
    }
    if ((action == 2 || action == 3) && dragKind_ >= 0) {
        endHandleDrag();
        handleMoved_ = false;
        return;
    }

    // 魔棒: 按下即按颜色连通域生成掩码
    if (selShape_ == 2 && !floating_) {
        if (action == 0) wandSelect(cx, cy);
        return;
    }
    // 套索: 空白处起笔走折线; 已有选区内部仍是拖动
    if (selShape_ == 1 && !floating_) {
        const bool inSel = active_ && cx >= x0_ && cx <= x1_ && cy >= y0_ && cy <= y1_ && maskAt(cx, cy);
        if (!inSel) {
            if (action == 0) { hold_ = Hold::None; lassoBegin(cx, cy); return; }
            if (action == 1 && !lassoPts_.empty()) { lassoAdd(cx, cy); return; }
            if ((action == 2 || action == 3) && !lassoPts_.empty()) { lassoFinish(); return; }
        }
    }

    if (action == 0) {
        lastX_ = cx; lastY_ = cy;
        pressX_ = cx; pressY_ = cy;
        holdAborted_ = false;
        floatGate_ = false;
        const bool inside = active_ && cx >= x0_ && cx <= x1_ && cy >= y0_ && cy <= y1_ && maskAt(cx, cy);
        if (floating_) {
            if (inside) {
                dragMove_ = true;
                hold_ = Hold::None;
                floatGate_ = true;
                floatGateX_ = cx; floatGateY_ = cy;
                lastX_ = cx; lastY_ = cy;
            } else {
                // 旧项目: 选区外长按1秒把浮起内容瞬移到按住处
                hold_ = Hold::Outside;
                holdT_ = now; holdX_ = cx; holdY_ = cy;
                dragMove_ = false;
            }
            return;
        }
        if (inside) {
            // 旧项目: 选区内长按1秒才裁剪浮起; 单击/轻扫不动内容
            hold_ = Hold::Inside;
            holdT_ = now; holdX_ = cx; holdY_ = cy;
            dragMove_ = false;
            return;
        }
        // 选区外: 清空旧选区, 立即开始新框选
        resetTransform();
        if (pendingMove_) applyAllowed_ = true;
        pendingFloatCurrent_ = false;
        dragMove_ = false;
        hold_ = Hold::None;
        dragSel_ = true;
        clearMask();
        active_ = true;
        x0_ = x1_ = std::max(0, std::min(cx, cw - 1));
        y0_ = y1_ = std::max(0, std::min(cy, ch - 1));
        display_->setSelection(x0_, y0_, x1_, y1_, true);
        return;
    }

    if (action == 1) {
        // 选区外长按: 1秒瞬移浮起内容; 移动超过3画布px取消
        if (hold_ == Hold::Outside && floating_) {
            const int moved = std::abs(cx - holdX_) + std::abs(cy - holdY_);
            if (moved > 3) { hold_ = Hold::None; return; }
            if (std::chrono::duration<double>(now - holdT_).count() >= 1.0) {
                int nx = holdX_ - fw_ / 2, ny = holdY_ - fh_ / 2;
                nx = std::max(0, std::min(nx, std::max(0, cw - fw_)));
                ny = std::max(0, std::min(ny, std::max(0, ch - fh_)));
                markMoveScreen(fx_, fy_, nx, ny);
                fx_ = nx; fy_ = ny;
                x0_ = fx_; y0_ = fy_; x1_ = fx_ + fw_ - 1; y1_ = fy_ + fh_ - 1;
                display_->setSelection(x0_, y0_, x1_, y1_, true);
                hold_ = Hold::None;
                dragMove_ = true;
                lastX_ = cx; lastY_ = cy;   // 瞬移后继续跟手到松手
            }
            return;
        }
        // 选区内长按: 时长可调(默认1秒)裁剪; 12px迟滞; 0.35s内大幅移动=重新框选
        if (hold_ == Hold::Inside && !floating_) {
            const float dx = (float)(cx - holdX_), dy = (float)(cy - holdY_);
            const float dist = std::sqrt(dx * dx + dy * dy);
            const double held = std::chrono::duration<double>(now - holdT_).count();
            const double holdSec = std::max(0.1, holdMs_.load() / 1000.0);
            const float gate = std::max(14.f, 12.f / zoom);
            if (held >= holdSec) {
                captureRect();          // 长按裁剪也写入全局"上次裁剪"
                extractToFloat();
                hold_ = Hold::None;
                dragMove_ = true;
                floatGate_ = true;
                floatGateX_ = cx; floatGateY_ = cy;
                // 以当前手指位置重锚: 裁剪瞬间不跳, 之后持续跟随到松手
                lastX_ = cx; lastY_ = cy;
            } else if (dist > gate) {
                if (held < 0.35) {
                    // 快速拖动 = 重新框选
                    hold_ = Hold::None;
                    dragMove_ = false;
                    dragSel_ = true;
                    clearMask();
                    resetTransform();
                    active_ = true;
                    x0_ = x1_ = std::max(0, std::min(holdX_, cw - 1));
                    y0_ = y1_ = std::max(0, std::min(holdY_, ch - 1));
                    display_->setSelection(x0_, y0_, x1_, y1_, true);
                    x1_ = std::max(0, std::min(cx, cw - 1));
                    y1_ = std::max(0, std::min(cy, ch - 1));
                    display_->setSelection(std::min(x0_, x1_), std::min(y0_, y1_),
                                           std::max(x0_, x1_), std::max(y0_, y1_), true);
                } else {
                    hold_ = Hold::None;
                    holdAborted_ = true;
                    dragMove_ = false;
                }
                return;
            } else {
                return;
            }
        }
        if (dragMove_ && floating_) {
            if (floatGate_) {
                const float gx = (float)(cx - floatGateX_), gy = (float)(cy - floatGateY_);
                const float gate = 28.f / zoom;   // 旧项目: 28 屏幕px 内视为点按, 不动内容
                if (gx * gx + gy * gy <= gate * gate) return;
                floatGate_ = false;
                lastX_ = cx; lastY_ = cy;
                return;
            }
            const auto t0 = Clk::now();
            const int nx = fx_ + (cx - lastX_), ny = fy_ + (cy - lastY_);
            if (nx != fx_ || ny != fy_) {
                // 预览是"变换后"的形状: 只标旧/新包围盒的并集, 否则旋转/放大后拖拽会拖出残影
                markMoveScreen(fx_, fy_, nx, ny);
                if (extActive_) fullDirty_ = true;
                fx_ = nx; fy_ = ny;
                if (perspective_ && pqValid_) {
                    int bx0, by0, bx1, by1;
                    transformedBBox(fx_, fy_, &bx0, &by0, &bx1, &by1);
                    x0_ = std::max(0, bx0); y0_ = std::max(0, by0);
                    x1_ = std::min(cw - 1, bx1); y1_ = std::min(ch - 1, by1);
                } else {
                    x0_ = fx_; y0_ = fy_; x1_ = fx_ + fw_ - 1; y1_ = fy_ + fh_ - 1;
                }
                display_->setSelection(x0_, y0_, x1_, y1_, true);
            }
            lastX_ = cx; lastY_ = cy;
            moveMs_ = ms(t0);
            return;
        }
        if (dragSel_) {
            x1_ = std::max(0, std::min(cx, cw - 1));
            y1_ = std::max(0, std::min(cy, ch - 1));
            const int ax = std::min(x0_, x1_), bx = std::max(x0_, x1_);
            const int ay = std::min(y0_, y1_), by = std::max(y0_, y1_);
            display_->setSelection(ax, ay, bx, by, true);
        }
        return;
    }

    if (action == 2 || action == 3) {
        hold_ = Hold::None;
        floatGate_ = false;
        if (dragSel_) {
            dragSel_ = false;
            const int ax = std::min(x0_, x1_), bx = std::max(x0_, x1_);
            const int ay = std::min(y0_, y1_), by = std::max(y0_, y1_);
            x0_ = std::max(0, ax); y0_ = std::max(0, ay);
            x1_ = std::min(cw - 1, bx);
            y1_ = std::min(ch - 1, by);
            if (x1_ - x0_ < 1 || y1_ - y0_ < 1) {   // 旧项目: 宽/高都 >=2 才建选区
                active_ = false;
                display_->setSelection(0, 0, 0, 0, false);
            } else {
                display_->setSelection(x0_, y0_, x1_, y1_, true);
            }
        }
        dragMove_ = false;
    }
}

void SelectionTool::tick() {
    SEL_EDIT_GUARD
    if (!canvas_) return;
    const auto now = Clk::now();
    const int cw = canvas_->width(), ch = canvas_->height();
    if (hold_ == Hold::Inside && !floating_) {
        const double holdSec = std::max(0.1, holdMs_.load() / 1000.0);
        if (std::chrono::duration<double>(now - holdT_).count() >= holdSec) {
            captureRect();              // 长按裁剪同时写入全局上次裁剪
            extractToFloat();
            hold_ = Hold::None;
            dragMove_ = true;
            floatGate_ = true;
            floatGateX_ = holdX_; floatGateY_ = holdY_;
            lastX_ = holdX_; lastY_ = holdY_;
        }
    } else if (hold_ == Hold::Outside && floating_) {
        if (std::chrono::duration<double>(now - holdT_).count() >= 1.0) {
            int nx = holdX_ - fw_ / 2, ny = holdY_ - fh_ / 2;
            nx = std::max(0, std::min(nx, std::max(0, cw - fw_)));
            ny = std::max(0, std::min(ny, std::max(0, ch - fh_)));
            markMoveScreen(fx_, fy_, nx, ny);
            fx_ = nx; fy_ = ny;
            x0_ = fx_; y0_ = fy_; x1_ = fx_ + fw_ - 1; y1_ = fy_ + fh_ - 1;
            display_->setSelection(x0_, y0_, x1_, y1_, true);
            hold_ = Hold::None;
            dragMove_ = true;
            lastX_ = holdX_; lastY_ = holdY_;
        }
    }
}

void SelectionTool::cancelGesture() {
    hold_ = Hold::None;
    floatGate_ = false;
    dragMove_ = false;
    handleMoved_ = false;
    if (dragSel_) {
        dragSel_ = false;
        active_ = false;
        if (display_) display_->setSelection(0, 0, 0, 0, false);
    }
    if (!lassoPts_.empty()) {
        lassoPts_.clear();
        active_ = false;
        if (display_) display_->setSelection(0, 0, 0, 0, false);
    }
}

void SelectionTool::captureRect() {
    if (!canvas_ || !active_) return;
    finishCommit();
    const int w = x1_ - x0_ + 1, h = y1_ - y0_ + 1;
    if (w <= 0 || h <= 0) return;
    // 3% 缩放下全屏选区 ~1 亿画布像素 → 4B/px 会爆内存; 上限 32M(128MB) 并记录"被跳过"
    lastCutSkipped_ = false;
    if ((long long)w * h > 32ll * 1024 * 1024) {
        lastCut_.clear(); lastW_ = lastH_ = 0;
        lastCutSkipped_ = true;
        LOGI_SEL("lastCut skip %dx%d (>32M px)", w, h);
        return;
    }
    lastCut_.assign((size_t)w * h, 0u);
    const int layer = canvas_->active();
    const bool mk = !mask_.empty();
    for (int y = 0; y < h; y++) {
        uint32_t* row = lastCut_.data() + (size_t)y * w;
        if (mk) {
            const uint8_t* mr = mask_.data() + (size_t)y * maskW_;
            for (int x = 0; x < w; x++) if (x < maskW_ && mr[x]) row[x] = canvas_->getPixelL(layer, x0_ + x, y0_ + y);
        } else {
            for (int x = 0; x < w; x++) row[x] = canvas_->getPixelL(layer, x0_ + x, y0_ + y);
        }
    }
    lastW_ = w; lastH_ = h;
}

void SelectionTool::cancelFloat() {
    SEL_EDIT_GUARD
    if (!canvas_) return;
    if (bufFloat_) {
        // 蒙版/复制浮层还没落点: 剪切时像素原样写回源位置; 复制副本直接丢弃(源从未被清空)
        if (!copyFloat_) {
            const int layer = canvas_->active();
            for (int y = 0; y < fh_; y++)
                for (int x = 0; x < fw_; x++) {
                    const uint32_t v = buf_[(size_t)y * fw_ + x];
                    if (v) canvas_->setPixelL(layer, srcX_ + x, srcY_ + y, v);
                }
            canvas_->markDirtyRect(srcX_, srcY_, srcX_ + fw_ - 1, srcY_ + fh_ - 1);
            if (invalidate_) invalidate_(srcX_, srcY_, srcX_ + fw_ - 1, srcY_ + fh_ - 1);
        }
        // 取消时预览消失: 先标当前变换包围盒, 否则旋转/放大后的预览会留在屏幕上
        markMoveScreen(fx_, fy_, fx_, fy_);
        buf_.clear();
        bufFloat_ = false;
        copyFloat_ = false;
        textFloat_ = false;
        floating_ = false;
        display_->clearFloat();
        canvas_->clearCutRect();
        resetTransform();
        return;
    }
    ext_.clear();
    extW_ = extH_ = 0;
    extActive_ = false;
    pendingMove_ = false;
    if (floating_) {
        // 预览消失: 当前变换包围盒也要重绘(不只是基础矩形)
        markMoveScreen(fx_, fy_, fx_, fy_);
        floating_ = false;
        gpuFloat_ = false;
        holdFloat_ = false;
        display_->clearFloat();
        canvas_->clearCutRect();
        // 取消后源区域重新显示原来的像素(浮层只是遮住, 并未真删)
        const int sx1 = std::min(canvas_->width() - 1, srcX_ + fw_ - 1);
        const int sy1 = std::min(canvas_->height() - 1, srcY_ + fh_ - 1);
        canvas_->markDirtyAllocated(srcX_, srcY_, sx1, sy1);
        if (invalidate_) invalidate_(srcX_, srcY_, sx1, sy1);
    }
    resetTransform();
}

// 后台保存前: 已经确认的落定/粘贴先实体化; 未确认的浮层/选区保持原样
void SelectionTool::clearState() {
    active_ = false;
    floating_ = false;
    bufFloat_ = false;
    gpuFloat_ = false;
    copyFloat_ = false;
    textFloat_ = false;
    extActive_ = false;
    holdFloat_ = false;
    pendingMove_ = false;
    moveDstMask_.clear();
    pendingFloatCurrent_ = false;
    commitPending_ = false;
    dragSel_ = dragMove_ = false;
    dragKind_ = -1;
    hold_ = Hold::None;
    floatGate_ = false;
    handleMoved_ = false;
    mask_.clear(); maskW_ = maskH_ = 0; maskOutline_.clear();
    buf_.clear();
    cropTarget_.clear(); cropTargetW_ = cropTargetH_ = 0;
    ext_.clear(); extW_ = extH_ = 0;
    pendingPaste_.clear();
    resetTransform();
    if (display_) { display_->clearFloat(); display_->setSelection(0, 0, 0, 0, false); }
    if (canvas_) canvas_->clearCutRect();
    fullDirty_ = true;
}

void SelectionTool::prepareForSnapshot() {
    SEL_EDIT_GUARD
    if (!canvas_) return;
    if (!pendingPaste_.empty()) applyPendingPaste();
    if (pendingMove_) finishPending();
    if (!floating_) {
        holdFloat_ = false;
        commitPending_ = false;
    }
}

void SelectionTool::saveState(std::vector<uint8_t>& out) const {
    auto put32 = [&](uint32_t v) {
        out.push_back((uint8_t)(v & 255)); out.push_back((uint8_t)((v >> 8) & 255));
        out.push_back((uint8_t)((v >> 16) & 255)); out.push_back((uint8_t)((v >> 24) & 255));
    };
    auto puti = [&](int v) { put32((uint32_t)v); };
    auto putf = [&](float f) { uint32_t u = 0; memcpy(&u, &f, 4); put32(u); };
    put32(0x314C4553u);   // 'S E L 1'
    uint32_t flags = 0;
    if (active_) flags |= 1u << 0;
    if (floating_) flags |= 1u << 1;
    if (bufFloat_) flags |= 1u << 2;
    if (gpuFloat_) flags |= 1u << 3;
    if (copyFloat_) flags |= 1u << 4;
    if (extActive_) flags |= 1u << 5;
    if (stretchMode_) flags |= 1u << 6;
    if (allLayers_) flags |= 1u << 7;
    if (flipH_) flags |= 1u << 8;
    if (flipV_) flags |= 1u << 9;
    if (!mask_.empty()) flags |= 1u << 10;
    if (holdFloat_) flags |= 1u << 11;
    if (canvas_ && canvas_->cutActive()) flags |= 1u << 12;
    if (perspective_) flags |= 1u << 13;
    if (pqValid_) flags |= 1u << 14;
    put32(flags);
    puti(x0_); puti(y0_); puti(x1_); puti(y1_);
    puti(fx_); puti(fy_); puti(fw_); puti(fh_); puti(srcX_); puti(srcY_);
    putf(tScaleX_); putf(tScaleY_); putf(tRot_);
    if (!mask_.empty()) {
        puti(maskW_); puti(maskH_); put32((uint32_t)mask_.size());
        out.insert(out.end(), mask_.begin(), mask_.end());
    } else {
        puti(0); puti(0); put32(0);
    }
    if (bufFloat_) {
        put32((uint32_t)buf_.size());
        const size_t off = out.size();
        out.resize(off + buf_.size() * 4);
        if (!buf_.empty()) memcpy(out.data() + off, buf_.data(), buf_.size() * 4);
    } else {
        put32(0);
    }
    if (extActive_) {
        puti(extW_); puti(extH_); put32((uint32_t)ext_.size());
        const size_t off = out.size();
        out.resize(off + ext_.size() * 4);
        if (!ext_.empty()) memcpy(out.data() + off, ext_.data(), ext_.size() * 4);
    } else {
        puti(0); puti(0); put32(0);
    }
    if (canvas_ && canvas_->cutActive()) {
        puti(canvas_->cutX0()); puti(canvas_->cutY0());
        puti(canvas_->cutX1()); puti(canvas_->cutY1());
    } else {
        puti(0); puti(0); puti(0); puti(0);
    }
    if (pqValid_) for (int i = 0; i < 8; i++) putf(pq_[i]);
}

bool SelectionTool::loadState(const uint8_t* data, size_t len, size_t& off) {
    if (!canvas_ || !data) return false;
    auto rd = [&](uint32_t& v) {
        if (off + 4 > len) return false;
        v = (uint32_t)data[off] | ((uint32_t)data[off + 1] << 8) |
            ((uint32_t)data[off + 2] << 16) | ((uint32_t)data[off + 3] << 24);
        off += 4;
        return true;
    };
    auto rdi = [&](int& v) { uint32_t u = 0; if (!rd(u)) return false; v = (int)u; return true; };
    auto rdf = [&](float& f) { uint32_t u = 0; if (!rd(u)) return false; memcpy(&f, &u, 4); return true; };
    uint32_t magic = 0, flags = 0;
    if (!rd(magic) || magic != 0x314C4553u || !rd(flags)) return false;
    int nx0 = 0, ny0 = 0, nx1 = 0, ny1 = 0;
    int nfx = 0, nfy = 0, nfw = 0, nfh = 0, nsx = 0, nsy = 0;
    float nsx2 = 1.f, nsy2 = 1.f, nrot = 0.f;
    if (!rdi(nx0) || !rdi(ny0) || !rdi(nx1) || !rdi(ny1)) return false;
    if (!rdi(nfx) || !rdi(nfy) || !rdi(nfw) || !rdi(nfh) || !rdi(nsx) || !rdi(nsy)) return false;
    if (!rdf(nsx2) || !rdf(nsy2) || !rdf(nrot)) return false;
    const bool hasMask = (flags >> 10) & 1u;
    const bool hasBuf = (flags >> 2) & 1u;
    const bool hasExt = (flags >> 5) & 1u;
    const bool hasCut = (flags >> 12) & 1u;
    int mw = 0, mh = 0; uint32_t msz = 0;
    if (!rdi(mw) || !rdi(mh) || !rd(msz)) return false;
    if (hasMask && (mw <= 0 || mh <= 0 || (long long)mw * mh > 32ll * 1024 * 1024 ||
                    msz != (uint32_t)((size_t)mw * mh) || off + msz > len)) return false;
    std::vector<uint8_t> nmask;
    if (hasMask) {
        nmask.assign(data + off, data + off + msz);
        off += msz;
    }
    uint32_t bsz = 0;
    if (!rd(bsz)) return false;
    std::vector<uint32_t> nbuf;
    if (hasBuf) {
        if (bsz == 0 || bsz > 32u * 1024 * 1024 || off + (size_t)bsz * 4 > len) return false;
        nbuf.resize(bsz);
        memcpy(nbuf.data(), data + off, (size_t)bsz * 4);
        off += (size_t)bsz * 4;
    }
    int ew = 0, eh = 0; uint32_t esz = 0;
    if (!rdi(ew) || !rdi(eh) || !rd(esz)) return false;
    std::vector<uint32_t> next;
    if (hasExt) {
        if (ew <= 0 || eh <= 0 || (long long)ew * eh > 32ll * 1024 * 1024 ||
            esz != (uint32_t)((size_t)ew * eh) || off + (size_t)esz * 4 > len) return false;
        next.resize(esz);
        memcpy(next.data(), data + off, (size_t)esz * 4);
        off += (size_t)esz * 4;
    }
    int cx0 = 0, cy0 = 0, cx1 = 0, cy1 = 0;
    if (!rdi(cx0) || !rdi(cy0) || !rdi(cx1) || !rdi(cy1)) return false;
    const bool hasPq = (flags >> 14) & 1u;
    float npq[8] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
    if (hasPq) {
        for (int i = 0; i < 8; i++) if (!rdf(npq[i])) return false;
    }

    // 校验通过后再覆盖当前状态
    active_ = (flags >> 0) & 1u;
    floating_ = ((flags >> 1) & 1u) && active_;
    bufFloat_ = (flags >> 2) & 1u;
    gpuFloat_ = (flags >> 3) & 1u;
    copyFloat_ = (flags >> 4) & 1u;
    extActive_ = (flags >> 5) & 1u;
    stretchMode_ = (flags >> 6) & 1u;
    allLayers_ = (flags >> 7) & 1u;
    flipH_ = (flags >> 8) & 1u;
    flipV_ = (flags >> 9) & 1u;
    holdFloat_ = false;
    x0_ = nx0; y0_ = ny0; x1_ = nx1; y1_ = ny1;
    fx_ = nfx; fy_ = nfy; fw_ = nfw; fh_ = nfh;
    srcX_ = nsx; srcY_ = nsy;
    tScaleX_ = std::max(0.05f, std::min(20.f, nsx2));
    tScaleY_ = std::max(0.05f, std::min(20.f, nsy2));
    tRot_ = nrot;
    perspective_ = (flags >> 13) & 1u;
    pqValid_ = hasPq;
    if (perspective_ && stretchMode_) stretchMode_ = false;
    for (int i = 0; i < 8; i++) pq_[i] = npq[i];
    mask_ = std::move(nmask); maskW_ = hasMask ? mw : 0; maskH_ = hasMask ? mh : 0;
    buf_ = std::move(nbuf);
    ext_ = std::move(next); extW_ = hasExt ? ew : 0; extH_ = hasExt ? eh : 0;
    if (hasMask) rebuildMaskOutline(); else maskOutline_.clear();
    if (!(bufFloat_ || extActive_ || gpuFloat_)) floating_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    commitPending_ = false;
    dragSel_ = dragMove_ = false;
    dragKind_ = -1;
    hold_ = Hold::None;
    floatGate_ = false;
    handleMoved_ = false;
    pendingPaste_.clear();
    if (hasCut) canvas_->setCutRect(cx0, cy0, cx1, cy1,
                                   allLayers_ ? -1 : canvas_->active());
    else canvas_->clearCutRect();
    display_->clearFloat();
    display_->setSelection(x0_, y0_, x1_, y1_, active_);
    fullDirty_ = true;
    LOGI_SEL("restore selection active=%d float=%d buf=%d ext=%d mask=%dx%d xf=%.2f,%.2f,%.1f",
             (int)active_, (int)floating_, (int)bufFloat_, (int)extActive_, maskW_, maskH_,
             tScaleX_, tScaleY_, tRot_);
    return true;
}

void SelectionTool::copyRect() {
    if (!canvas_ || !active_) return;
    if (floating_) return;          // 浮动中先落定再复制
    captureRect();
    if (!lastCutSkipped_) LOGI_SEL("copy %dx%d", lastW_, lastH_);
}

// 复制按钮: 把当前已选像素做成原地浮层副本(不擦源), 用户可直接拖动/变换/确认.
// [主人令] 复制也要写入"上次裁剪"缓存: 复制过的内容之后可以直接"粘贴上次裁剪".
void SelectionTool::duplicateSelection() {
    if (!canvas_ || !active_ || floating_) return;
    captureRect();                  // 先写 lastCut_(与裁剪同源), 再生成浮层副本
    finishCommit();
    resetTransform();
    const int w = x1_ - x0_ + 1, h = y1_ - y0_ + 1;
    if (w <= 0 || h <= 0) return;
    if ((long long)w * h > 32ll * 1024 * 1024) {
        LOGI_SEL("duplicate skip %dx%d (>32M px)", w, h);
        return;
    }
    const int layer = canvas_->active();
    fw_ = w; fh_ = h;
    fx_ = x0_; fy_ = y0_;
    srcX_ = x0_; srcY_ = y0_;
    buf_.assign((size_t)w * h, 0u);
    if (!mask_.empty()) {
        for (int y = 0; y < h; y++) {
            const uint8_t* mr = mask_.data() + (size_t)y * maskW_;
            for (int x = 0; x < w; x++)
                if (x < maskW_ && mr[x]) buf_[(size_t)y * w + x] = canvas_->getPixelL(layer, x0_ + x, y0_ + y);
        }
    } else {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                buf_[(size_t)y * w + x] = canvas_->getPixelL(layer, x0_ + x, y0_ + y);
    }
    floating_ = true;
    gpuFloat_ = false;
    bufFloat_ = true;
    copyFloat_ = true;
    holdFloat_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    commitPending_ = false;
    dragMove_ = false;
    display_->clearFloat();
    canvas_->clearCutRect();          // 复制不擦源, 不进入"挖空"裁剪态
    fullDirty_ = true;
    canvas_->markDirtyRect(x0_, y0_, x1_, y1_);
    if (invalidate_) invalidate_(x0_, y0_, x1_, y1_);
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    LOGI_SEL("duplicate %dx%d at %d,%d mask=%d", w, h, x0_, y0_, (int)mask_.size());
}

void SelectionTool::deleteRect() {
    SEL_EDIT_GUARD
    if (!canvas_ || !active_) return;
    cancelFloat();
    finishCommit();
    const int layer = canvas_->active();
    if (!mask_.empty()) {
        for (int y = 0; y < maskH_; y++)
            for (int x = 0; x < maskW_; x++)
                if (mask_[(size_t)y * maskW_ + x]) canvas_->setPixelL(layer, x0_ + x, y0_ + y, 0u);
    } else {
        for (int y = y0_; y <= y1_; y++)
            for (int x = x0_; x <= x1_; x++) canvas_->setPixelL(layer, x, y, 0u);
    }
    canvas_->markDirtyRect(x0_, y0_, x1_, y1_);
    if (invalidate_) invalidate_(x0_, y0_, x1_, y1_);
    deselect();
}

void SelectionTool::affineCornersAt(int fx, int fy, float* xy8) const {
    const float ccx = fx + fw_ * 0.5f, ccy = fy + fh_ * 0.5f;
    const float hw = fw_ * tScaleX_ * 0.5f, hh = fh_ * tScaleY_ * 0.5f;
    const float rad = tRot_ * 3.14159265f / 180.f;
    const float c = std::cos(rad), s = std::sin(rad);
    const float lx[4] = {-hw, hw, -hw, hw};
    const float ly[4] = {-hh, -hh, hh, hh};
    for (int i = 0; i < 4; i++) {
        xy8[i * 2] = ccx + lx[i] * c - ly[i] * s;
        xy8[i * 2 + 1] = ccy + lx[i] * s + ly[i] * c;
    }
}

void SelectionTool::cornersAt(int fx, int fy, float* xy8) const {
    if (perspective_ && pqValid_) {
        for (int i = 0; i < 4; i++) {
            xy8[i * 2] = fx + pq_[i * 2];
            xy8[i * 2 + 1] = fy + pq_[i * 2 + 1];
        }
        return;
    }
    affineCornersAt(fx, fy, xy8);
}

void SelectionTool::corners(float* xy8) const {
    cornersAt(fx_, fy_, xy8);
}

bool SelectionTool::quadIdentity() const {
    if (!pqValid_ || fw_ <= 0 || fh_ <= 0) return true;
    const float ident[8] = {0.f, 0.f, (float)fw_, 0.f, 0.f, (float)fh_, (float)fw_, (float)fh_};
    for (int i = 0; i < 8; i++)
        if (std::abs(pq_[i] - ident[i]) > 0.5f) return false;
    return true;
}

bool SelectionTool::xformActive() const {
    if (!floating_) return false;
    if (perspective_) return pqValid_ && !quadIdentity();
    return tScaleX_ != 1.f || tScaleY_ != 1.f || tRot_ != 0.f || flipH_ || flipV_;
}

void SelectionTool::resetTransform() {
    tScaleX_ = tScaleY_ = 1.f;
    tRot_ = 0.f;
    flipH_ = flipV_ = false;
    pqValid_ = false;
    dragKind_ = -1;
}

void SelectionTool::setPerspectiveMode(bool on) {
    if (!on) {
        if (!perspective_) return;
        perspective_ = false;
        dragKind_ = -1;
        if (floating_) {
            float q[8]; corners(q);
            int bx0 = (int)std::floor(q[0]), by0 = (int)std::floor(q[1]);
            int bx1 = bx0, by1 = by0;
            for (int i = 1; i < 4; i++) {
                bx0 = std::min(bx0, (int)std::floor(q[i * 2]));
                by0 = std::min(by0, (int)std::floor(q[i * 2 + 1]));
                bx1 = std::max(bx1, (int)std::ceil(q[i * 2]));
                by1 = std::max(by1, (int)std::ceil(q[i * 2 + 1]));
            }
            x0_ = std::max(0, bx0); y0_ = std::max(0, by0);
            x1_ = std::min(canvas_->width() - 1, bx1);
            y1_ = std::min(canvas_->height() - 1, by1);
            display_->setSelection(x0_, y0_, x1_, y1_, true);
            fullDirty_ = true;
        }
        return;
    }
    // 开启: 用当前仿射四角初始化透视四边形(视觉不跳), 再切到四角拖拽
    if (!pqValid_) {
        float q[8];
        affineCornersAt(fx_, fy_, q);
        for (int i = 0; i < 4; i++) {
            pq_[i * 2] = q[i * 2] - fx_;
            pq_[i * 2 + 1] = q[i * 2 + 1] - fy_;
        }
        pqValid_ = true;
    }
    perspective_ = true;
    stretchMode_ = false;
    dragKind_ = -1;
    fullDirty_ = true;
}

void SelectionTool::rotateHandle(float* hx, float* hy) const {
    if (perspective_) { *hx = 0.f; *hy = 0.f; return; }
    float q[8];
    corners(q);
    const float topMidX = (q[0] + q[2]) * 0.5f, topMidY = (q[1] + q[3]) * 0.5f;
    const float ccx = fx_ + fw_ * 0.5f, ccy = fy_ + fh_ * 0.5f;
    float nx = -(q[3] - q[1]), ny = q[2] - q[0];
    const float len = std::sqrt(nx * nx + ny * ny);
    if (len > 1e-4f) { nx /= len; ny /= len; }
    if (nx * (ccx - topMidX) + ny * (ccy - topMidY) > 0.f) { nx = -nx; ny = -ny; }
    const float dist = 300.f / std::max(0.0001f, display_ ? display_->zoom() : 1.f);
    *hx = topMidX + nx * dist;
    *hy = topMidY + ny * dist;
}

bool SelectionTool::hitHandle(float cx, float cy, int* kind, int* idx) const {
    if (!floating_ || (!stretchMode_ && !perspective_)) return false;
    const float rad = 18.f / std::max(0.0001f, display_ ? display_->zoom() : 1.f);
    float q[8];
    corners(q);
    if (perspective_) {
        for (int i = 0; i < 4; i++) {
            if (std::abs(cx - q[i * 2]) <= rad && std::abs(cy - q[i * 2 + 1]) <= rad) {
                *kind = 0; *idx = i; return true;
            }
        }
        return false;
    }
    // 旋转柄命中区放大到 48 屏幕px: 手指落在蓝点附近一律算旋转, 按钮不能抢
    const float radRot = 48.f / std::max(0.0001f, display_ ? display_->zoom() : 1.f);
    float hx, hy;
    rotateHandle(&hx, &hy);
    if (std::abs(cx - hx) <= radRot && std::abs(cy - hy) <= radRot) { *kind = 1; *idx = 0; return true; }
    for (int i = 0; i < 4; i++) {
        if (std::abs(cx - q[i * 2]) <= rad && std::abs(cy - q[i * 2 + 1]) <= rad) {
            *kind = 0; *idx = i; return true;
        }
    }
    // 四条边中点: 0=上 1=右 2=下 3=左, 只做单轴拉伸
    const float midX[4] = {(q[0] + q[2]) * 0.5f, (q[2] + q[6]) * 0.5f,
                           (q[4] + q[6]) * 0.5f, (q[0] + q[4]) * 0.5f};
    const float midY[4] = {(q[1] + q[3]) * 0.5f, (q[3] + q[7]) * 0.5f,
                           (q[5] + q[7]) * 0.5f, (q[1] + q[5]) * 0.5f};
    for (int i = 0; i < 4; i++) {
        if (std::abs(cx - midX[i]) <= rad && std::abs(cy - midY[i]) <= rad) {
            *kind = 2; *idx = i; return true;
        }
    }
    return false;
}

void SelectionTool::beginHandleDrag(int kind, int idx, float cx, float cy) {
    dragKind_ = kind; dragIdx_ = idx;
    dragStartCX_ = cx; dragStartCY_ = cy;
    dragBaseScaleX_ = tScaleX_; dragBaseScaleY_ = tScaleY_;
    if (perspective_) {
        if (!pqValid_) {
            float aq[8];
            affineCornersAt(fx_, fy_, aq);
            for (int i = 0; i < 4; i++) {
                pq_[i * 2] = aq[i * 2] - fx_;
                pq_[i * 2 + 1] = aq[i * 2 + 1] - fy_;
            }
            pqValid_ = true;
        }
        dragGrabX_ = (fx_ + pq_[idx * 2]) - cx;
        dragGrabY_ = (fy_ + pq_[idx * 2 + 1]) - cy;
        dragAnchorX_ = dragAnchorY_ = 0.f;
        dragStartAngle_ = 0.f; dragBaseRot_ = tRot_;
        return;
    }
    const float ccx = fx_ + fw_ * 0.5f, ccy = fy_ + fh_ * 0.5f;
    // 旧项目语义: 角拉锚对角、边拉锚对边, 锚点在世界坐标恒定; 记录抓取偏移, 首帧不跳
    float q[8]; corners(q);
    float handleX, handleY;
    if (kind == 0) {
        const int opp = 3 - idx;
        dragAnchorX_ = q[opp * 2]; dragAnchorY_ = q[opp * 2 + 1];
        handleX = q[idx * 2]; handleY = q[idx * 2 + 1];
    } else {
        const float midX[4] = {(q[0] + q[2]) * 0.5f, (q[2] + q[6]) * 0.5f,
                               (q[4] + q[6]) * 0.5f, (q[0] + q[4]) * 0.5f};
        const float midY[4] = {(q[1] + q[3]) * 0.5f, (q[3] + q[7]) * 0.5f,
                               (q[5] + q[7]) * 0.5f, (q[1] + q[5]) * 0.5f};
        const int opp = (idx + 2) & 3;
        dragAnchorX_ = midX[opp]; dragAnchorY_ = midY[opp];
        handleX = midX[idx]; handleY = midY[idx];
    }
    dragGrabX_ = handleX - cx; dragGrabY_ = handleY - cy;
    dragStartAngle_ = std::atan2(cy - ccy, cx - ccx) * 57.29578f;
    dragBaseRot_ = tRot_;
}

void SelectionTool::updateHandleDrag(float cx, float cy) {
    if (dragKind_ < 0 || !canvas_ || !display_) return;
    int ox0, oy0, ox1, oy1;
    transformedBBox(fx_, fy_, &ox0, &oy0, &ox1, &oy1);
    if (perspective_) {
        if (!pqValid_) return;
        const float old[8] = {pq_[0], pq_[1], pq_[2], pq_[3], pq_[4], pq_[5], pq_[6], pq_[7]};
        float nx = cx + dragGrabX_ - fx_;
        float ny = cy + dragGrabY_ - fy_;
        const float lim = (float)canvas_->width();
        nx = std::max(-lim, std::min(nx, lim * 2.f));
        ny = std::max(-lim, std::min(ny, lim * 2.f));
        pq_[dragIdx_ * 2] = nx;
        pq_[dragIdx_ * 2 + 1] = ny;
        if (!quadConvex(pq_)) {
            memcpy(pq_, old, sizeof(pq_));
            return;
        }
        float q[8];
        corners(q);
        float minX = q[0], maxX = q[0], minY = q[1], maxY = q[1];
        for (int i = 1; i < 4; i++) {
            minX = std::min(minX, q[i * 2]); maxX = std::max(maxX, q[i * 2]);
            minY = std::min(minY, q[i * 2 + 1]); maxY = std::max(maxY, q[i * 2 + 1]);
        }
        x0_ = std::max(0, (int)std::floor(minX)); y0_ = std::max(0, (int)std::floor(minY));
        x1_ = std::min(canvas_->width() - 1, (int)std::ceil(maxX));
        y1_ = std::min(canvas_->height() - 1, (int)std::ceil(maxY));
        display_->setSelection(x0_, y0_, x1_, y1_, true);
        int nx0, ny0, nx1, ny1;
        transformedBBox(fx_, fy_, &nx0, &ny0, &nx1, &ny1);
        if (markScreen_) {
            markScreen_(std::min(ox0, nx0), std::min(oy0, ny0),
                        std::max(ox1, nx1), std::max(oy1, ny1));
        } else {
            fullDirty_ = true;
        }
        return;
    }
    const float ccx = fx_ + fw_ * 0.5f, ccy = fy_ + fh_ * 0.5f;
    if (dragKind_ == 1) {
        const float ang = std::atan2(cy - ccy, cx - ccx) * 57.29578f;
        tRot_ = std::round(dragBaseRot_ + (ang - dragStartAngle_));
    } else {
        // 旧项目: 角拉锚对角/边拉锚对边。用"手指+抓取偏移"到锚点的轴向距离算新尺寸,
        // 再反解中心位置, 保证锚点世界坐标不动(与旧 StretchDragUpdate/EdgeUpdate 同语义)。
        const float rad = tRot_ * 3.14159265f / 180.f;
        const float c = std::cos(rad), s = std::sin(rad);
        const float fx = cx + dragGrabX_ - dragAnchorX_;
        const float fy = cy + dragGrabY_ - dragAnchorY_;
        const float dx = fx * c + fy * s;        // 沿旋转后 x 轴
        const float dy = -fx * s + fy * c;       // 沿旋转后 y 轴
        if (dragKind_ == 2) {
            if (dragIdx_ == 0 || dragIdx_ == 2) {
                tScaleY_ = std::max(0.05f, std::min(20.f, std::abs(dy) / std::max(1.f, (float)fh_)));
                tScaleX_ = dragBaseScaleX_;
            } else {
                tScaleX_ = std::max(0.05f, std::min(20.f, std::abs(dx) / std::max(1.f, (float)fw_)));
                tScaleY_ = dragBaseScaleY_;
            }
        } else {
            tScaleX_ = std::max(0.05f, std::min(20.f, std::abs(dx) / std::max(1.f, (float)fw_)));
            tScaleY_ = std::max(0.05f, std::min(20.f, std::abs(dy) / std::max(1.f, (float)fh_)));
        }
        const float nw = fw_ * tScaleX_, nh = fh_ * tScaleY_;
        float ox = 0.f, oy = 0.f;
        if (dragKind_ == 0) {
            const int opp = 3 - dragIdx_;
            const float signX = (opp == 1 || opp == 3) ? -1.f : 1.f;
            const float signY = (opp >= 2) ? -1.f : 1.f;
            ox = signX * nw * 0.5f; oy = signY * nh * 0.5f;
        } else if (dragIdx_ == 0) { ox = 0.f; oy = -nh * 0.5f;       // 拉上边: 锚下边
        } else if (dragIdx_ == 2) { ox = 0.f; oy = nh * 0.5f;        // 拉下边: 锚上边
        } else if (dragIdx_ == 3) { ox = -nw * 0.5f; oy = 0.f;       // 拉左边: 锚右边
        } else { ox = nw * 0.5f; oy = 0.f; }                          // 拉右边: 锚左边
        const float ccx = dragAnchorX_ + ox * c - oy * s;
        const float ccy = dragAnchorY_ + ox * s + oy * c;
        fx_ = (int)std::lround(ccx - fw_ * 0.5f);
        fy_ = (int)std::lround(ccy - fh_ * 0.5f);
        pqValid_ = false;
    }
    float q[8];
    corners(q);
    float minX = q[0], maxX = q[0], minY = q[1], maxY = q[1];
    for (int i = 1; i < 4; i++) {
        minX = std::min(minX, q[i * 2]); maxX = std::max(maxX, q[i * 2]);
        minY = std::min(minY, q[i * 2 + 1]); maxY = std::max(maxY, q[i * 2 + 1]);
    }
    x0_ = std::max(0, (int)std::floor(minX)); y0_ = std::max(0, (int)std::floor(minY));
    x1_ = std::min(canvas_->width() - 1, (int)std::ceil(maxX));
    y1_ = std::min(canvas_->height() - 1, (int)std::ceil(maxY));
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    // 只标旧/新变换包围盒的并集: 不再整屏重绘, 但旧预览也会被擦掉
    int nx0, ny0, nx1, ny1;
    transformedBBox(fx_, fy_, &nx0, &ny0, &nx1, &ny1);
    if (markScreen_) {
        markScreen_(std::min(ox0, nx0), std::min(oy0, ny0),
                    std::max(ox1, nx1), std::max(oy1, ny1));
    } else {
        fullDirty_ = true;
    }
}

void SelectionTool::endHandleDrag() { dragKind_ = -1; }

void SelectionTool::rotate90() {
    if (perspective_ && pqValid_) {
        float cx = 0.f, cy = 0.f;
        for (int i = 0; i < 4; i++) { cx += pq_[i * 2]; cy += pq_[i * 2 + 1]; }
        cx *= 0.25f; cy *= 0.25f;
        for (int i = 0; i < 4; i++) {
            const float x = pq_[i * 2], y = pq_[i * 2 + 1];
            pq_[i * 2] = cx - (y - cy);
            pq_[i * 2 + 1] = cy + (x - cx);
        }
        float q[8]; corners(q);
        float minX = q[0], maxX = q[0], minY = q[1], maxY = q[1];
        for (int i = 1; i < 4; i++) {
            minX = std::min(minX, q[i * 2]); maxX = std::max(maxX, q[i * 2]);
            minY = std::min(minY, q[i * 2 + 1]); maxY = std::max(maxY, q[i * 2 + 1]);
        }
        x0_ = std::max(0, (int)std::floor(minX)); y0_ = std::max(0, (int)std::floor(minY));
        x1_ = std::min(canvas_->width() - 1, (int)std::ceil(maxX));
        y1_ = std::min(canvas_->height() - 1, (int)std::ceil(maxY));
        display_->setSelection(x0_, y0_, x1_, y1_, true);
        if (markScreen_) markScreen_(x0_, y0_, x1_, y1_);
        fullDirty_ = true;
        return;
    }
    if (!xformActive()) resetTransform();
    tRot_ = std::fmod(tRot_ + 90.f, 360.f);
    float q[8]; corners(q);
    float minX = q[0], maxX = q[0], minY = q[1], maxY = q[1];
    for (int i = 1; i < 4; i++) {
        minX = std::min(minX, q[i * 2]); maxX = std::max(maxX, q[i * 2]);
        minY = std::min(minY, q[i * 2 + 1]); maxY = std::max(maxY, q[i * 2 + 1]);
    }
    x0_ = std::max(0, (int)std::floor(minX)); y0_ = std::max(0, (int)std::floor(minY));
    x1_ = std::min(canvas_->width() - 1, (int)std::ceil(maxX));
    y1_ = std::min(canvas_->height() - 1, (int)std::ceil(maxY));
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    if (markScreen_) markScreen_(x0_, y0_, x1_, y1_);
    fullDirty_ = true;
}

void SelectionTool::flipSelH() {
    if (perspective_ && pqValid_) {
        float cx = 0.f;
        for (int i = 0; i < 4; i++) cx += pq_[i * 2];
        cx *= 0.25f;
        for (int i = 0; i < 4; i++) pq_[i * 2] = 2.f * cx - pq_[i * 2];
    } else {
        flipH_ = !flipH_;
    }
    if (markScreen_) markScreen_(x0_, y0_, x1_, y1_);
    fullDirty_ = true;
}

void SelectionTool::flipSelV() {
    if (perspective_ && pqValid_) {
        float cy = 0.f;
        for (int i = 0; i < 4; i++) cy += pq_[i * 2 + 1];
        cy *= 0.25f;
        for (int i = 0; i < 4; i++) pq_[i * 2 + 1] = 2.f * cy - pq_[i * 2 + 1];
    } else {
        flipV_ = !flipV_;
    }
    if (markScreen_) markScreen_(x0_, y0_, x1_, y1_);
    fullDirty_ = true;
}

bool SelectionTool::mapToSource(float cx, float cy, int* sx, int* sy) const {
    if (!floating_) return false;
    MapPre m;
    prepareMap(&m);
    if (m.ext) return false;
    return mapToSourcePre(m, cx, cy, sx, sy);
}

void SelectionTool::transformedBBox(int fx, int fy, int* bx0, int* by0, int* bx1, int* by1) const {
    float q[8];
    cornersAt(fx, fy, q);
    float minX = q[0], maxX = q[0], minY = q[1], maxY = q[1];
    for (int i = 1; i < 4; i++) {
        minX = std::min(minX, q[i * 2]); maxX = std::max(maxX, q[i * 2]);
        minY = std::min(minY, q[i * 2 + 1]); maxY = std::max(maxY, q[i * 2 + 1]);
    }
    // 外扩 1 画布像素, 覆盖旋转/缩放/透视边缘的采样半像素
    *bx0 = (int)std::floor(minX) - 1; *by0 = (int)std::floor(minY) - 1;
    *bx1 = (int)std::ceil(maxX) + 1;  *by1 = (int)std::ceil(maxY) + 1;
}

void SelectionTool::prepareMap(MapPre* m) const {
    const bool ext = extActive_;
    const float w = ext ? (float)extW_ : (float)fw_;
    const float h = ext ? (float)extH_ : (float)fh_;
    m->ext = ext;
    m->persp = false;
    if (perspective_ && pqValid_) {
        float q[8];
        cornersAt(fx_, fy_, q);
        float inv[9];
        if (buildPerspInverse(q, inv)) {
            m->persp = true;
            m->h00 = inv[0]; m->h01 = inv[1]; m->h02 = inv[2];
            m->h10 = inv[3]; m->h11 = inv[4]; m->h12 = inv[5];
            m->h20 = inv[6]; m->h21 = inv[7]; m->h22 = inv[8];
            return;
        }
    }
    m->ccx = fx_ + w * 0.5f;
    m->ccy = fy_ + h * 0.5f;
    const float rad = -tRot_ * 3.14159265f / 180.f;
    m->c = std::cos(rad);
    m->s = std::sin(rad);
    const float sxs = tScaleX_ * (flipH_ ? -1.f : 1.f);
    const float sys = tScaleY_ * (flipV_ ? -1.f : 1.f);
    m->invSx = std::abs(sxs) < 1e-4f ? 0.f : 1.f / sxs;
    m->invSy = std::abs(sys) < 1e-4f ? 0.f : 1.f / sys;
    m->srcCx = ext ? w * 0.5f : (float)srcX_ + fw_ * 0.5f;
    m->srcCy = ext ? h * 0.5f : (float)srcY_ + fh_ * 0.5f;
}

bool SelectionTool::mapToSourcePre(const MapPre& m, float cx, float cy, int* sx, int* sy) const {
    if (!floating_) return false;
    if (m.persp) {
        float u = 0.f, v = 0.f;
        if (!perspMap(m, cx, cy, &u, &v) || fw_ <= 0 || fh_ <= 0) return false;
        const int ix = srcX_ + (int)std::floor(u * fw_);
        const int iy = srcY_ + (int)std::floor(v * fh_);
        if (ix < srcX_ || iy < srcY_ || ix >= srcX_ + fw_ || iy >= srcY_ + fh_) return false;
        *sx = ix; *sy = iy;
        return true;
    }
    if (m.ext || m.invSx == 0.f || m.invSy == 0.f) return false;
    const float dx = cx - m.ccx, dy = cy - m.ccy;
    const float rx = dx * m.c - dy * m.s, ry = dx * m.s + dy * m.c;
    const float px = m.srcCx + rx * m.invSx;
    const float py = m.srcCy + ry * m.invSy;
    const int ix = (int)std::floor(px), iy = (int)std::floor(py);
    if (ix < srcX_ || iy < srcY_ || ix >= srcX_ + fw_ || iy >= srcY_ + fh_) return false;
    *sx = ix; *sy = iy;
    return true;
}

uint32_t SelectionTool::extAtPre(const MapPre& m, int cx, int cy) const {    if (!extActive_) return 0u;
    if (m.persp) {
        float u = 0.f, v = 0.f;
        if (!perspMap(m, (float)cx, (float)cy, &u, &v) || extW_ <= 0 || extH_ <= 0) return 0u;
        const int ix = (int)std::floor(u * extW_);
        const int iy = (int)std::floor(v * extH_);
        if (ix < 0 || iy < 0 || ix >= extW_ || iy >= extH_) return 0u;
        return ext_[(size_t)iy * extW_ + ix];
    }
    if (!m.ext || m.invSx == 0.f || m.invSy == 0.f) return 0u;
    const float dx = (float)cx - m.ccx, dy = (float)cy - m.ccy;
    const float rx = dx * m.c - dy * m.s, ry = dx * m.s + dy * m.c;
    const float px = m.srcCx + rx * m.invSx;
    const float py = m.srcCy + ry * m.invSy;
    const int ix = (int)std::floor(px), iy = (int)std::floor(py);
    if (ix < 0 || iy < 0 || ix >= extW_ || iy >= extH_) return 0u;
    return ext_[(size_t)iy * extW_ + ix];
}

void SelectionTool::markMoveScreen(int oldFx, int oldFy, int newFx, int newFy) {
    if (!markScreen_) { fullDirty_ = true; return; }
    int ax0, ay0, ax1, ay1, bx0, by0, bx1, by1;
    transformedBBox(oldFx, oldFy, &ax0, &ay0, &ax1, &ay1);
    transformedBBox(newFx, newFy, &bx0, &by0, &bx1, &by1);
    markScreen_(std::min(ax0, bx0), std::min(ay0, by0),
                std::max(ax1, bx1), std::max(ay1, by1));
}

void SelectionTool::commitTransform() {
    SEL_EDIT_GUARD
    if (bufFloat_) { commitMaskedTransform(); return; }
    if (extActive_) {
        const int layer = canvas_->active();
        const int cw = canvas_->width(), ch = canvas_->height();
        const int cwx = canvas_->tilesX();
        // 目标包围盒按变换后四角(旋转/缩放后可能超出画布, 取交集)
        float q[8]; corners(q);
        int bx0 = (int)std::floor(q[0]), by0 = (int)std::floor(q[1]);
        int bx1 = bx0, by1 = by0;
        for (int i = 1; i < 4; i++) {
            bx0 = std::min(bx0, (int)std::floor(q[i*2])); by0 = std::min(by0, (int)std::floor(q[i*2+1]));
            bx1 = std::max(bx1, (int)std::ceil(q[i*2])); by1 = std::max(by1, (int)std::ceil(q[i*2+1]));
        }
        bx0 = std::max(0, bx0); by0 = std::max(0, by0);
        bx1 = std::min(cw - 1, bx1); by1 = std::min(ch - 1, by1);
        // 选区内容浮层落定: 逐像素写回. 不再先整块清空——
        // 清空后再判断本层蒙版会把 alpha 看成 0, 导致整块被跳过(蚂蚁线内像素全丢);
        // 透明处(v==0)也保持原像素不动, 避免把选区里的透明像素/下层内容擦掉.
        MapPre mp;
        prepareMap(&mp);
        for (int cy = by0; cy <= by1; cy++) {
            for (int cx = bx0; cx <= bx1; cx++) {
                if (!mask_.empty()) {
                    const int mx = cx - x0_, my = cy - y0_;
                    if (mx < 0 || my < 0 || mx >= maskW_ || my >= maskH_) continue;
                    if (!mask_[(size_t)my * maskW_ + mx]) continue;
                }
                const uint32_t v = extAtPre(mp, cx, cy);        // 预计算逆变换
                if (!v) continue;
                const int ti = (cy >> Canvas::TILE_SHIFT) * cwx + (cx >> Canvas::TILE_SHIFT);
                uint32_t* t = canvas_->tileForWrite(layer, ti);
                uint32_t& d = t[((size_t)(cy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) + (cx & (Canvas::TILE - 1))];
                // 本层蒙版: 用清空/写入前的目标 alpha 判断, 而不是被清成 0 的 alpha
                if (canvas_->alphaLock() && !(d >> 24)) continue;
                d = v;
            }
        }
        if (bx1 >= bx0 && by1 >= by0) {
            canvas_->markDirtyRect(bx0, by0, bx1, by1);
            if (invalidate_) invalidate_(bx0, by0, bx1, by1);
        }
        ext_.clear(); extW_ = extH_ = 0; extActive_ = false;
        matExt_ = false;
        // 落定后选区矩形跟到内容的新包围盒, 后续抽屉取材才不会读旧位置
        if (bx1 >= bx0 && by1 >= by0) {
            x0_ = bx0; y0_ = by0; x1_ = bx1; y1_ = by1;
            display_->setSelection(x0_, y0_, x1_, y1_, true);
        }
        origSaved_ = false;
        origExt_.clear();
        origExtW_ = origExtH_ = 0;
        floating_ = false; holdFloat_ = false; gpuFloat_ = false;
        display_->clearFloat();
        resetTransform();
        return;
    }
    LOGI_SEL("commitTransform xf=%d float=%d s=%.2f,%.2f rot=%.1f allL=%d",
             (int)xformActive(), (int)floating_, tScaleX_, tScaleY_, tRot_, (int)allLayers_);
    if (!xformActive()) { commitFloat(); finishCommit(); return; }
    float q[8];
    corners(q);
    int bx0 = (int)std::floor(q[0]), by0 = (int)std::floor(q[1]);
    int bx1 = bx0, by1 = by0;
    for (int i = 1; i < 4; i++) {
        bx0 = std::min(bx0, (int)std::floor(q[i * 2]));
        by0 = std::min(by0, (int)std::floor(q[i * 2 + 1]));
        bx1 = std::max(bx1, (int)std::ceil(q[i * 2]));
        by1 = std::max(by1, (int)std::ceil(q[i * 2 + 1]));
    }
    bx0 = std::max(0, bx0); by0 = std::max(0, by0);
    bx1 = std::min(canvas_->width() - 1, bx1); by1 = std::min(canvas_->height() - 1, by1);
    const int firstLayer = allLayers_ ? 0 : canvas_->active();
    const int lastLayer = allLayers_ ? canvas_->layers() - 1 : canvas_->active();
    const int cwx = canvas_->tilesX();
    int ux0 = bx0, uy0 = by0, ux1 = bx1, uy1 = by1;   // 脏区并集
    bool any = false;
    MapPre mp;
    prepareMap(&mp);
    for (int layer = firstLayer; layer <= lastLayer; layer++) {
        int cx0 = INT_MAX, cy0 = INT_MAX, cx1 = INT_MIN, cy1 = INT_MIN;
        for (int ty = srcY_ >> Canvas::TILE_SHIFT; ty <= (srcY_ + fh_ - 1) >> Canvas::TILE_SHIFT; ty++)
            for (int tx = srcX_ >> Canvas::TILE_SHIFT; tx <= (srcX_ + fw_ - 1) >> Canvas::TILE_SHIFT; tx++) {
                const uint32_t* t = canvas_->tile(layer, ty * cwx + tx);
                if (!t) continue;
                for (int yy = 0; yy < Canvas::TILE; yy++) {
                    const int y = (ty << Canvas::TILE_SHIFT) + yy;
                    if (y < srcY_ || y >= srcY_ + fh_) continue;
                    const uint32_t* row = t + ((size_t)yy << Canvas::TILE_SHIFT);
                    for (int xx = 0; xx < Canvas::TILE; xx++) {
                        const int x = (tx << Canvas::TILE_SHIFT) + xx;
                        if (x < srcX_ || x >= srcX_ + fw_) continue;
                        if (!row[xx]) continue;
                        if (x < cx0) cx0 = x; if (x > cx1) cx1 = x;
                        if (y < cy0) cy0 = y; if (y > cy1) cy1 = y;
                    }
                }
            }
        if (cx0 > cx1) continue;   // 该层选区为空
        const int cw2 = cx1 - cx0 + 1, ch2 = cy1 - cy0 + 1;
        if ((long long)cw2 * ch2 > 32ll * 1024 * 1024) {
            LOGI_SEL("commitTransform layer %d content too large %dx%d", layer, cw2, ch2);
            continue;
        }
        std::vector<uint32_t> src((size_t)cw2 * ch2, 0u);
        for (int y = 0; y < ch2; y++)
            for (int x = 0; x < cw2; x++)
                src[(size_t)y * cw2 + x] = canvas_->getPixelL(layer, cx0 + x, cy0 + y);
        for (int ty = cy0 >> Canvas::TILE_SHIFT; ty <= cy1 >> Canvas::TILE_SHIFT; ty++)
            for (int tx = cx0 >> Canvas::TILE_SHIFT; tx <= cx1 >> Canvas::TILE_SHIFT; tx++) {
                const int ti = ty * cwx + tx;
                if (!canvas_->tile(layer, ti)) continue;
                uint32_t* t = canvas_->tileForWrite(layer, ti);
                for (int yy = 0; yy < Canvas::TILE; yy++) {
                    const int y = (ty << Canvas::TILE_SHIFT) + yy;
                    if (y < cy0 || y > cy1) continue;
                    uint32_t* row = t + ((size_t)yy << Canvas::TILE_SHIFT);
                    for (int xx = 0; xx < Canvas::TILE; xx++) {
                        const int x = (tx << Canvas::TILE_SHIFT) + xx;
                        if (x < cx0 || x > cx1) continue;
                        row[xx] = 0;
                    }
                }
            }
        for (int dy = by0; dy <= by1; dy++) {
            for (int dx = bx0; dx <= bx1; dx++) {
                int sx, sy;
                if (!mapToSourcePre(mp, (float)dx + 0.5f, (float)dy + 0.5f, &sx, &sy)) continue;
                if (sx < cx0 || sy < cy0 || sx > cx1 || sy > cy1) continue;
                const uint32_t v = src[(size_t)(sy - cy0) * cw2 + (sx - cx0)];
                if (!v) continue;
                // 本层蒙版: 落点本层透明不贴(源区已先清空, 与旧项目一致; 全部层模式不启用)
                if (!allLayers_ && canvas_->alphaLock() &&
                    !(canvas_->getPixelL(layer, dx, dy) >> 24)) continue;
                const int ti = (dy >> Canvas::TILE_SHIFT) * cwx + (dx >> Canvas::TILE_SHIFT);
                uint32_t* t = canvas_->tileForWrite(layer, ti);
                t[((size_t)(dy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) + (dx & (Canvas::TILE - 1))] = v;
            }
        }
        ux0 = std::min(ux0, cx0); uy0 = std::min(uy0, cy0);
        ux1 = std::max(ux1, cx1); uy1 = std::max(uy1, cy1);
        any = true;
    }
    if (!any) {
        resetTransform();
        cancelFloat();
        return;
    }
    canvas_->markDirtyRect(ux0, uy0, ux1, uy1);
    if (invalidate_) invalidate_(ux0, uy0, ux1, uy1);
    floating_ = false;
    holdFloat_ = false;
    pendingMove_ = false;
    gpuFloat_ = false;
    display_->clearFloat();
    canvas_->clearCutRect();
    x0_ = bx0; y0_ = by0; x1_ = bx1; y1_ = by1;
    resetTransform();
}

void SelectionTool::pasteLastCut() {
    if (!hasLastCut()) return;
    resetTransform();
    pendingPaste_.swap(lastCut_);   // 不复制(大选区会翻倍内存)
    pasteW_ = lastW_; pasteH_ = lastH_;
}

void SelectionTool::pasteImage(int w, int h, const uint32_t* px) {
    if (w <= 0 || h <= 0 || !px) return;
    pendingPaste_.assign(px, px + (size_t)w * h);
    pasteW_ = w; pasteH_ = h;
    lastCut_ = pendingPaste_;
    lastW_ = w; lastH_ = h;
}

void SelectionTool::importImage(int w, int h, const uint32_t* px) {
    if (w <= 0 || h <= 0 || !px || !canvas_) return;
    finishCommit();
    ext_.assign(px, px + (size_t)w * h);
    extW_ = w; extH_ = h;
    extActive_ = true;
    resetTransform();
    fullDirty_ = true;
    floating_ = true;
    gpuFloat_ = true;
    bufFloat_ = false;
    copyFloat_ = false;
    textFloat_ = false;
    pendingMove_ = false;
    clearMask();
    fx_ = (canvas_->width() - w) / 2;
    fy_ = (canvas_->height() - h) / 2;
    srcX_ = fx_; srcY_ = fy_;
    fw_ = w; fh_ = h;
    x0_ = std::max(0, fx_); y0_ = std::max(0, fy_);
    x1_ = std::min(canvas_->width() - 1, fx_ + w - 1);
    y1_ = std::min(canvas_->height() - 1, fy_ + h - 1);
    active_ = true;
    canvas_->clearCutRect();
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    if (markScreen_) markScreen_(0, 0, canvas_->width() - 1, canvas_->height() - 1);
}

uint32_t SelectionTool::extAt(int cx, int cy) const {
    if (!extActive_) return 0u;
    MapPre m;
    prepareMap(&m);
    return extAtPre(m, cx, cy);
}

uint32_t SelectionTool::previewPixel(int sx, int sy, bool compositeAll) const {
    if (!canvas_) return 0;
    if (bufFloat_) {
        if (sx < srcX_ || sy < srcY_ || sx >= srcX_ + fw_ || sy >= srcY_ + fh_) return 0;
        return buf_[(size_t)(sy - srcY_) * fw_ + (sx - srcX_)];
    }
    if (sx < 0 || sy < 0 || sx >= canvas_->width() || sy >= canvas_->height()) return 0;
    if (compositeAll) {
        uint32_t out = 0;
        for (int l = 0; l < canvas_->layers(); l++) {
            if (!canvas_->layerVisible(l)) continue;
            const uint32_t v = raster::layerPixel(*canvas_, l, sx, sy);
            if (v) raster::overPixel(out, v);
        }
        return out;
    }
    return canvas_->getPixelL(canvas_->active(), sx, sy);
}

void SelectionTool::applyPendingPaste() {
    SEL_EDIT_GUARD
    if (pendingPaste_.empty() || !canvas_) return;
    finishCommit();
    const int cw = canvas_->width(), ch = canvas_->height();
    const int w = pasteW_, h = pasteH_;
    const int dx = std::max(0, (cw - w) / 2), dy = std::max(0, (ch - h) / 2);
    const int layer = canvas_->active();
    for (int y = 0; y < h; y++) {
        const int ty = dy + y;
        if (ty >= ch) break;
        const uint32_t* src = pendingPaste_.data() + (size_t)y * w;
        for (int x = 0; x < w; x++) {
            const int tx = dx + x;
            if (tx >= cw) break;
            const uint32_t v = src[x];
            if (!(v >> 24)) continue;                    // 透明像素跳过
            uint32_t d = canvas_->getPixelL(layer, tx, ty);
            raster::overPixel(d, v);
            canvas_->setPixelL(layer, tx, ty, d);
        }
    }
    canvas_->markDirtyRect(dx, dy, std::min(cw - 1, dx + w - 1), std::min(ch - 1, dy + h - 1));
    // 粘贴完进入可拖动选区(旧: 剪贴板进 CutEditing)
    x0_ = dx; y0_ = dy;
    x1_ = std::min(cw - 1, dx + w - 1); y1_ = std::min(ch - 1, dy + h - 1);
    active_ = true;
    floating_ = false;
    dragSel_ = dragMove_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    canvas_->clearCutRect();
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    lastCut_.swap(pendingPaste_);   // 换回去, 下次还能粘
    pendingPaste_.clear();
    LOGI_SEL("paste %dx%d at %d,%d", w, h, dx, dy);
    if (invalidate_) invalidate_(x0_, y0_, x1_, y1_);
}

void SelectionTool::pasteLastCutAtViewCenter(float viewCx, float viewCy) {
    SEL_EDIT_GUARD
    if (!canvas_ || lastCut_.empty() || lastW_ <= 0 || lastH_ <= 0) return;
    // [主人令] 粘贴上次裁剪 = 进入"被裁剪中、等待确认放置"的浮层态:
    // 先预览在视口中心, 可拖动/拉伸旋转, 面板确认才真正写层; 取消直接丢弃(源内容不受影响)。
    if (floating_) commitFloat();
    finishCommit();
    deselect();

    const int cw = canvas_->width(), ch = canvas_->height();
    const int w = lastW_, h = lastH_;
    int dx = (int)std::lround(viewCx - w * 0.5f);
    int dy = (int)std::lround(viewCy - h * 0.5f);
    // 内容比画布大时从左上摆放; 否则夹在画布内, 保证目标完整可见
    dx = (w <= cw) ? std::max(0, std::min(dx, cw - w)) : 0;
    dy = (h <= ch) ? std::max(0, std::min(dy, ch - h)) : 0;

    // 复制型浮层: buf_ 持有像素, copyFloat_=true 保证取消时不写回任何源区域
    buf_ = lastCut_;
    bufFloat_ = true;
    copyFloat_ = true;
    floating_ = true;
    gpuFloat_ = false;
    holdFloat_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    fw_ = w; fh_ = h;
    srcX_ = dx; srcY_ = dy;      // 没有源区被清空, 仅用于取消/提交的脏区
    fx_ = dx; fy_ = dy;
    x0_ = std::max(0, dx); y0_ = std::max(0, dy);
    x1_ = std::min(cw - 1, dx + w - 1);
    y1_ = std::min(ch - 1, dy + h - 1);
    active_ = true;
    resetTransform();
    canvas_->clearCutRect();
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    fullDirty_ = true;
    if (markScreen_) markScreen_(0, 0, cw - 1, ch - 1);
    LOGI_SEL("paste lastcut float %dx%d at %d,%d view=%.1f,%.1f", w, h, dx, dy, viewCx, viewCy);
}

void SelectionTool::setRectDirect(int a, int b, int c, int d) {
    if (!canvas_ || !display_) return;
    const int cw = canvas_->width(), ch = canvas_->height();
    x0_ = std::max(0, std::min(a, cw - 1));
    y0_ = std::max(0, std::min(b, ch - 1));
    x1_ = std::max(x0_, std::min(c, cw - 1));
    y1_ = std::max(y0_, std::min(d, ch - 1));
    active_ = true;
    floating_ = false;
    dragSel_ = dragMove_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    clearMask();
    canvas_->clearCutRect();
    display_->setSelection(x0_, y0_, x1_, y1_, true);
}

void SelectionTool::selectAll() {
    SEL_EDIT_GUARD
    if (!canvas_) return;
    finishCommit();
    x0_ = 0; y0_ = 0;
    x1_ = canvas_->width() - 1; y1_ = canvas_->height() - 1;
    active_ = true;
    floating_ = false;
    dragSel_ = false; dragMove_ = false;
    pendingMove_ = false; pendingFloatCurrent_ = false;
    clearMask();
    canvas_->clearCutRect();
    display_->setSelection(x0_, y0_, x1_, y1_, true);
}

void SelectionTool::deselect() {
    SEL_EDIT_GUARD
    if (!canvas_) return;
    if (floating_ || pendingMove_) finishCommit();
    if (!active_ && !canvas_->cutActive()) {
        display_->setSelection(0, 0, 0, 0, false);
        return;
    }
    canvas_->clearCutRect();   // 引擎检测到蒙版变化会整屏重绘
    active_ = false;
    floating_ = false;
    bufFloat_ = false;
    copyFloat_ = false;
    textFloat_ = false;
    pendingFloatCurrent_ = false;
    pendingMove_ = false;
    // 选区没了, 抽屉保存的原图也作废, 防止下一次选区复用旧尺寸
    origSaved_ = false;
    origExt_.clear();
    origExtW_ = origExtH_ = 0;
    resetTransform();
    clearMask();
    lassoPts_.clear();
    display_->setSelection(0, 0, 0, 0, false);
}

void SelectionTool::extractToFloat() {
    SEL_EDIT_GUARD
    if (!canvas_ || floating_) return;
    if (!mask_.empty()) { extractMasked(); return; }
    finishCommit();   // 上一次落定还没搬完/还在合成, 先收尾
    const auto t0 = Clk::now();
    fw_ = x1_ - x0_ + 1; fh_ = y1_ - y0_ + 1;
    fx_ = x0_; fy_ = y0_;
    srcX_ = x0_; srcY_ = y0_;
    canvas_->setCutRect(x0_, y0_, x1_, y1_,
                        allLayers_ ? -1 : canvas_->active());   // 合成时只挖空被剪的层

    // 预览不再拷贝/降采样: 引擎合成时按当前 zoom 从 mip 直接采样(屏幕分辨率, 任意缩放 1:1)
    gpuFloat_ = true;
    copyFloat_ = false;
    floatNonZero_ = 1;
    // 源区域重合成(蒙版生效): 只有真有内容/下层内容的块才会看出空洞, 空白块不用刷
    canvas_->markDirtyAllocated(x0_, y0_, x1_, y1_);
    floating_ = true;
    selMs_ = ms(t0);
}

void SelectionTool::cut() {
    SEL_EDIT_GUARD
    if (!canvas_ || !active_) return;
    if (floating_) { commitFloat(); return; }   // 再按一次=放下
    if (pendingMove_) {
        if (cRow_ == 0 && gpuFloat_) {
            // 还没实体化: 把落点贴图再提起来, 零成本
            floating_ = true;
            fx_ = cX_ + cDx_; fy_ = cY_ + cDy_;
            srcX_ = cX_; srcY_ = cY_;
            x0_ = fx_; y0_ = fy_; x1_ = fx_ + cW_ - 1; y1_ = fy_ + cH_ - 1;
            display_->setSelection(x0_, y0_, x1_, y1_, true);
            pendingFloatCurrent_ = true;
            return;
        }
        finishPending();
    }
    captureRect();                  // 裁剪前先记录内容, 供跨画布/跨层粘贴
    // 记录"屏幕上看到的"裁剪目标(可见层合成), 供「裁剪导出」「新标签打开」使用
    {
        const int tw = x1_ - x0_ + 1, th = y1_ - y0_ + 1;
        cropTargetW_ = tw; cropTargetH_ = th;
        cropTarget_.assign((size_t)tw * th, 0u);
        if (!mask_.empty()) {
            for (int y = 0; y < th; y++)
                for (int x = 0; x < tw; x++)
                    if (maskAt(x0_ + x, y0_ + y))
                        cropTarget_[(size_t)y * tw + x] =
                            raster::composeLayersPixel(*canvas_, x0_ + x, y0_ + y);
        } else {
            for (int y = 0; y < th; y++)
                for (int x = 0; x < tw; x++)
                    cropTarget_[(size_t)y * tw + x] =
                        raster::composeLayersPixel(*canvas_, x0_ + x, y0_ + y);
        }
    }
    resetTransform();               // 旧 CutSelection: 新裁剪复位缩放/旋转
    extractToFloat();
    selMs_ = 0;
}

void SelectionTool::commitFloat() {
    SEL_EDIT_GUARD
    if (extActive_) { commitTransform(); return; }
    if (bufFloat_) { commitMaskedFloat(); return; }
    if (!floating_) return;
    const auto t0 = Clk::now();
    const int dx = fx_ - srcX_, dy = fy_ - srcY_;
    floating_ = false;
    buf_.clear();
    if (gpuFloat_) {
        holdFloat_ = true;
    } else {
        display_->clearFloat();
        holdFloat_ = false;
        commitPending_ = false;
    }
    if (pendingMove_ && cRow_ == 0) {
        // 上一次逻辑落定还没开始搬: 改一下目标位置就行, 零成本
        cDx_ = dx; cDy_ = dy;
        commitMs_ = 0;
        buildMoveMask();   // 落点变了, 本层蒙版掩码重建
        return;
    }
    // 旧项目"全部层": 确认时同一偏移应用到所有层(原生普通移动只搬活动层)
    if (allLayers_ && (dx != 0 || dy != 0)) {
        moveDstMask_.clear();   // 全部层模式不走本层蒙版掩码
        const size_t budget = (size_t)std::max(1, fw_) * (size_t)std::max(1, fh_);
        for (int l = 0; l < canvas_->layers(); l++) {
            int row = 0;
            while (row < fh_)
                row = canvas_->moveRectBlendStep(l, srcX_, srcY_, fw_, fh_, dx, dy,
                                                row, budget, cScratch_,
                                                nullptr, alphaOverlay_.load());
        }
        pendingMove_ = false;
        pendingFloatCurrent_ = false;
        canvas_->clearCutRect();
        const int cx0 = std::min(srcX_, srcX_ + dx), cy0 = std::min(srcY_, srcY_ + dy);
        const int cx1 = std::max(srcX_ + fw_ - 1, srcX_ + dx + fw_ - 1);
        const int cy1 = std::max(srcY_ + fh_ - 1, srcY_ + dy + fh_ - 1);
        if (invalidate_) invalidate_(cx0, cy0, cx1, cy1);
        canvas_->markDirtyRect(cx0, cy0, cx1, cy1);
        display_->clearFloat();
        gpuFloat_ = false;
        holdFloat_ = false;
        commitPending_ = false;
        commitMs_ = ms(t0);
        return;
    }
    if (pendingMove_) finishPending();   // 已经搬了一部分, 只能先收尾(极少见)
    cX_ = srcX_; cY_ = srcY_; cW_ = fw_; cH_ = fh_;
    cDx_ = dx; cDy_ = dy; cRow_ = 0;
    if (dx == 0 && dy == 0) {
        // 原地放下: 没有搬移, 清蒙版后把选区重合成一遍即可
        pendingMove_ = false;
        pendingFloatCurrent_ = false;
        moveDstMask_.clear();
        canvas_->clearCutRect();
        if (gpuFloat_) canvas_->markDirtyAllocated(cX_, cY_, cX_ + cW_ - 1, cY_ + cH_ - 1);
        else canvas_->markDirtyRect(cX_, cY_, cX_ + cW_ - 1, cY_ + cH_ - 1);
    } else {
        // 先只记录落定; 像素等空闲或真正需要画布时再搬 → 落定瞬间零耗时
        pendingMove_ = true;
        pendingFloatCurrent_ = true;
        buildMoveMask();
    }
    commitPending_ = gpuFloat_;
    commitMs_ = ms(t0);
}

// 旧项目"本层蒙版": 预览先把源矩形清空, 落定时本层透明处不贴。
// 掩码按落点矩形 cW_×cH_ 建位图(1=落点本层有像素); 源矩形内视为已清空(不可贴)。
void SelectionTool::buildMoveMask() {
    moveDstMask_.clear();
    if (!canvas_ || !canvas_->alphaLock() || allLayers_ || !pendingMove_) return;
    const int mw = cW_, mh = cH_;
    if (mw <= 0 || mh <= 0) return;
    if ((long long)mw * mh > 64ll * 1024 * 1024) return;   // 超大选区退回不裁剪
    moveDstMask_.assign(((size_t)mw * (size_t)mh + 63) / 64, 0);
    const int layer = canvas_->active();
    const int cwv = canvas_->width(), chv = canvas_->height();
    for (int my = 0; my < mh; my++) {
        const int ty = cY_ + cDy_ + my;
        if (ty < 0 || ty >= chv) continue;
        for (int mx = 0; mx < mw; mx++) {
            const int tx = cX_ + cDx_ + mx;
            if (tx < 0 || tx >= cwv) continue;
            // 源矩形在本层已被清空(旧项目预览首帧就清), 落回源区也算透明
            if (tx >= cX_ && tx < cX_ + cW_ && ty >= cY_ && ty < cY_ + cH_) continue;
            if (!(canvas_->getPixelL(layer, tx, ty) >> 24)) continue;
            const size_t bi = (size_t)my * (size_t)mw + (size_t)mx;
            moveDstMask_[bi >> 6] |= 1ULL << (bi & 63);
        }
    }
}

// 落定分帧搬运: 每帧只搬 budget 像素, 全部搬完后再统一标脏重合成。
// 关键: 目标位置是 over 叠加(透明像素跳过), 不会把那边原有的像素擦掉。
void SelectionTool::tickCommit(size_t pixelBudget) {
    if (!pendingMove_ || cRow_ >= cH_) return;
    const auto t0 = Clk::now();
    cRow_ = canvas_->moveRectBlendStep(canvas_->active(), cX_, cY_, cW_, cH_, cDx_, cDy_,
                                       cRow_, pixelBudget, cScratch_,
                                       moveDstMask_.empty() ? nullptr : moveDstMask_.data(),
                                       alphaOverlay_.load());
    commitMs_ += ms(t0);
    if (cRow_ >= cH_) {
        pendingMove_ = false;
        moveDstMask_.clear();
        canvas_->clearCutRect();
        // 源块已被清空: canvas 不再标脏(单层时空洞即最终画面), 但 LOD 缓存里还是旧内容,
        // 必须显式失效源矩形, 否则落定后源区域会"复活"出残影
        if (invalidate_) invalidate_(cX_, cY_, cX_ + cW_ - 1, cY_ + cH_ - 1);
        if (gpuFloat_) {
            // 源区域: 搬完后只剩其它层的合成结果, 单层画布下空洞本身就是最终画面 → 不重画
            canvas_->markDirtyOtherLayers(cX_, cY_, cX_ + cW_ - 1, cY_ + cH_ - 1, canvas_->active());
            // 目标区域: 只刷有内容的块
            canvas_->markDirtyAllocated(cX_ + cDx_, cY_ + cDy_, cX_ + cDx_ + cW_ - 1, cY_ + cDy_ + cH_ - 1);
        } else {
            canvas_->markDirtyRect(cX_, cY_, cX_ + cW_ - 1, cY_ + cH_ - 1);
            canvas_->markDirtyRect(cX_ + cDx_, cY_ + cDy_, cX_ + cDx_ + cW_ - 1, cY_ + cDy_ + cH_ - 1);
        }
    }
}

void SelectionTool::finishPending() {
    if (!pendingMove_) return;
    const size_t remaining = (size_t)std::max(0, cH_ - cRow_) * (size_t)std::max(1, cW_);
    while (pendingMove_) tickCommit(remaining);
    moveDstMask_.clear();
}

void SelectionTool::finishCommit() {
    SEL_EDIT_GUARD
    finishPending();
    if (!holdFloat_ && !commitPending_) return;
    if (markScreen_ && fw_ > 0) markScreen_(fx_, fy_, fx_ + fw_ - 1, fy_ + fh_ - 1);
    // 撤贴图前先让底图(staging/AHB)变成最终态: 否则贴图一撤就露出旧内容/空洞, 看着像残影
    if (flushCb_) flushCb_();
    display_->clearFloat();
    gpuFloat_ = false;
    holdFloat_ = false;
    commitPending_ = false;
}

void SelectionTool::endHold() {
    if (pendingMove_ || floating_) return;   // 逻辑落定还没实体化: 浮起图必须留着
    if (!holdFloat_ && !commitPending_) return;
    if (markScreen_ && fw_ > 0) markScreen_(fx_, fy_, fx_ + fw_ - 1, fy_ + fh_ - 1);
    display_->clearFloat();
    gpuFloat_ = false;
    holdFloat_ = false;
    commitPending_ = false;
}

void SelectionTool::crop() {
    SEL_EDIT_GUARD
    finishCommit();      // 落定没搬完就裁画布会让 tile 索引失效
    if (floating_) commitFloat();
    if (!active_) return;
    const auto t0 = Clk::now();
    const int w = x1_ - x0_ + 1, h = y1_ - y0_ + 1;
    if (w > 2 && h > 2 && canvas_->crop(x0_, y0_, w, h)) {
        display_->resizeCanvas(canvas_->width(), canvas_->height());
        active_ = false;
        display_->setSelection(0, 0, 0, 0, false);
        // 裁剪后旧选区/蒙版/取材全部失效, 清干净, 防止下一次抽屉读到旧尺寸/旧掩码
        mask_.clear(); maskW_ = maskH_ = 0;
        origSaved_ = false; origExt_.clear(); origExtW_ = origExtH_ = 0;
        ext_.clear(); extW_ = extH_ = 0; extActive_ = false; matExt_ = false;
    }
    selMs_ = ms(t0);
}

void SelectionTool::overlay(uint32_t* dst, int stride, int tx0, int ty0, int tw, int th) const {
    if (gpuFloat_ || !floating_) return;
    if (bufFloat_) {
        const int ax = std::max(tx0, fx_), bx = std::min(tx0 + tw, fx_ + fw_);
        const int ay = std::max(ty0, fy_), by = std::min(ty0 + th, fy_ + fh_);
        if (ax >= bx || ay >= by) return;
        for (int y = ay; y < by; y++) {
            uint32_t* d = dst + (size_t)(y - ty0) * stride + (ax - tx0);
            const uint32_t* srow = buf_.data() + (size_t)(y - fy_) * fw_;
            for (int x = ax; x < bx; x++) {
                const uint32_t v = srow[x - fx_];
                if (!(v >> 24)) continue;
                if (((v >> 24) & 255) == 255) d[x - ax] = v;
                else raster::overPixel(d[x - ax], v);
            }
        }
        return;
    }
    // 直接按源矩形从画布读像素, 贴到浮起位置(不复制整块, 只在脏 tile 内逐像素)
    const int sx0 = srcX_, sy0 = srcY_;
    const int ax = std::max(tx0, fx_), bx = std::min(tx0 + tw, fx_ + fw_);
    const int ay = std::max(ty0, fy_), by = std::min(ty0 + th, fy_ + fh_);
    if (ax >= bx || ay >= by) return;
    const int layer = canvas_->active();
    for (int y = ay; y < by; y++) {
        uint32_t* d = dst + (size_t)(y - ty0) * stride + (ax - tx0);
        const int sy = sy0 + (y - fy_);
        for (int x = ax; x < bx; x++) {
            const int sx = sx0 + (x - fx_);
            const uint32_t v = canvas_->getPixelL(layer, sx, sy);
            if (!v) continue;
            if (((v >> 24) & 255) == 255) d[x - ax] = v;
            else raster::overPixel(d[x - ax], v);
        }
    }
}

void SelectionTool::debugMove(int dx, int dy) {
    if (!floating_ || !display_ || !canvas_) return;
    const int ox = fx_, oy = fy_;
    fx_ += dx; fy_ += dy;
    x0_ = fx_; y0_ = fy_;
    x1_ = fx_ + fw_ - 1; y1_ = fy_ + fh_ - 1;
    // 只标屏幕: 画布/源像素都没变, 变换预览的旧位置由旧包围盒覆盖
    markMoveScreen(ox, oy, fx_, fy_);
    display_->setSelection(x0_, y0_, x1_, y1_, true);
}

int SelectionTool::maskPixelCount() const {
    int n = 0;
    for (uint8_t v : mask_) if (v) n++;
    return n;
}

void SelectionTool::lassoBegin(int cx, int cy) {
    SEL_EDIT_GUARD
    if (!canvas_ || !display_) return;
    if (floating_) commitFloat();
    finishCommit();
    if (pendingMove_) applyAllowed_ = true;
    clearMask();
    resetTransform();
    lassoPts_.clear();
    const int x = std::max(0, std::min(cx, canvas_->width() - 1));
    const int y = std::max(0, std::min(cy, canvas_->height() - 1));
    lassoPts_.push_back(x); lassoPts_.push_back(y);
    active_ = true;
    dragSel_ = false; dragMove_ = false;
    pendingFloatCurrent_ = false;
    x0_ = x1_ = x; y0_ = y1_ = y;
    display_->setSelection(x, y, x, y, true);
}

void SelectionTool::lassoAdd(int cx, int cy) {
    if (!canvas_ || lassoPts_.empty()) return;
    const int x = std::max(0, std::min(cx, canvas_->width() - 1));
    const int y = std::max(0, std::min(cy, canvas_->height() - 1));
    if (lassoPts_.size() >= 2) {
        const int lx = lassoPts_[lassoPts_.size() - 2], ly = lassoPts_[lassoPts_.size() - 1];
        if (lx == x && ly == y) return;
    }
    lassoPts_.push_back(x); lassoPts_.push_back(y);
    if (lassoPts_.size() > 8192) {
        std::vector<int> th;
        th.reserve(lassoPts_.size() / 2 + 2);
        for (size_t i = 0; i < lassoPts_.size(); i += 4) { th.push_back(lassoPts_[i]); th.push_back(lassoPts_[i + 1]); }
        lassoPts_.swap(th);
    }
    int bx0 = lassoPts_[0], bx1 = bx0, by0 = lassoPts_[1], by1 = by0;
    for (size_t i = 2; i + 1 < lassoPts_.size(); i += 2) {
        bx0 = std::min(bx0, lassoPts_[i]); bx1 = std::max(bx1, lassoPts_[i]);
        by0 = std::min(by0, lassoPts_[i + 1]); by1 = std::max(by1, lassoPts_[i + 1]);
    }
    x0_ = bx0; y0_ = by0; x1_ = bx1; y1_ = by1;
    display_->setSelection(bx0, by0, bx1, by1, true);
    // 拖拽中直接画套索折线(含回到起点的闭合预览), 不再只显示包围框
    std::vector<int> prev;
    prev.reserve(lassoPts_.size() + 2);
    for (size_t i = 0; i + 3 < lassoPts_.size(); i += 2) {
        prev.push_back(lassoPts_[i]); prev.push_back(lassoPts_[i + 1]);
        prev.push_back(lassoPts_[i + 2]); prev.push_back(lassoPts_[i + 3]);
    }
    if (lassoPts_.size() >= 4) {
        prev.push_back(lassoPts_[lassoPts_.size() - 2]);
        prev.push_back(lassoPts_[lassoPts_.size() - 1]);
        prev.push_back(lassoPts_[0]);
        prev.push_back(lassoPts_[1]);
    }
    display_->setSelectionOutline(prev);
}

void SelectionTool::rebuildMaskOutline() {
    maskOutline_.clear();
    if (mask_.empty()) return;
    auto in = [&](int x, int y) {
        return x >= 0 && y >= 0 && x < maskW_ && y < maskH_ && mask_[(size_t)y * maskW_ + x] != 0;
    };
    const size_t cap = 200000;   // 线段数上限; 超过则放弃轮廓, 退回 bbox 蚂蚁线
    for (int y = 0; y < maskH_; y++) {
        for (int x = 0; x < maskW_; x++) {
            if (!in(x, y)) continue;
            const int cx = x0_ + x, cy = y0_ + y;
            if (!in(x - 1, y)) { maskOutline_.push_back(cx); maskOutline_.push_back(cy); maskOutline_.push_back(cx); maskOutline_.push_back(cy + 1); }
            if (!in(x + 1, y)) { maskOutline_.push_back(cx + 1); maskOutline_.push_back(cy); maskOutline_.push_back(cx + 1); maskOutline_.push_back(cy + 1); }
            if (!in(x, y - 1)) { maskOutline_.push_back(cx); maskOutline_.push_back(cy); maskOutline_.push_back(cx + 1); maskOutline_.push_back(cy); }
            if (!in(x, y + 1)) { maskOutline_.push_back(cx); maskOutline_.push_back(cy + 1); maskOutline_.push_back(cx + 1); maskOutline_.push_back(cy + 1); }
            if (maskOutline_.size() / 4 > cap) { maskOutline_.clear(); return; }
        }
    }
}

void SelectionTool::lassoFinish() {
    if (!canvas_ || !display_) return;
    if (lassoPts_.size() < 6) {
        lassoPts_.clear();
        active_ = false;
        display_->setSelection(0, 0, 0, 0, false);
        return;
    }
    const auto t0 = Clk::now();
    int bx0 = lassoPts_[0], bx1 = bx0, by0 = lassoPts_[1], by1 = by0;
    for (size_t i = 2; i + 1 < lassoPts_.size(); i += 2) {
        bx0 = std::min(bx0, lassoPts_[i]); bx1 = std::max(bx1, lassoPts_[i]);
        by0 = std::min(by0, lassoPts_[i + 1]); by1 = std::max(by1, lassoPts_[i + 1]);
    }
    bx0 = std::max(0, bx0); by0 = std::max(0, by0);
    bx1 = std::min(canvas_->width() - 1, bx1); by1 = std::min(canvas_->height() - 1, by1);
    const int w = bx1 - bx0 + 1, h = by1 - by0 + 1;
    if (w < 2 || h < 2 || (long long)w * h > 32ll * 1024 * 1024) {
        if ((long long)w * h > 32ll * 1024 * 1024) maskSkipped_ = true;
        lassoPts_.clear();
        active_ = false;
        display_->setSelection(0, 0, 0, 0, false);
        return;
    }
    maskW_ = w; maskH_ = h;
    mask_.assign((size_t)w * h, 0);
    const int n = (int)lassoPts_.size();
    std::vector<float> xs;
    int cnt = 0;
    for (int yy = 0; yy < h; yy++) {
        const float sy = by0 + yy + 0.5f;
        xs.clear();
        for (int i = 0; i + 1 < n; i += 2) {
            const int j = (i + 2) % n;
            const float ax = (float)lassoPts_[i], ay = (float)lassoPts_[i + 1];
            const float bx = (float)lassoPts_[j], by = (float)lassoPts_[j + 1];
            if ((ay <= sy && by > sy) || (by <= sy && ay > sy))
                xs.push_back(ax + (sy - ay) * (bx - ax) / (by - ay));
        }
        std::sort(xs.begin(), xs.end());
        for (size_t k = 0; k + 1 < xs.size(); k += 2) {
            int sx = (int)std::ceil(xs[k] - 0.5f) - bx0;
            int ex = (int)std::floor(xs[k + 1] - 0.5f) - bx0;
            sx = std::max(0, sx); ex = std::min(w - 1, ex);
            for (int xx = sx; xx <= ex; xx++) { mask_[(size_t)yy * w + xx] = 255; cnt++; }
        }
    }
    lassoPts_.clear();
    if (cnt < 4) {
        clearMask();
        active_ = false;
        display_->setSelection(0, 0, 0, 0, false);
        return;
    }
    x0_ = bx0; y0_ = by0; x1_ = bx1; y1_ = by1;
    active_ = true;
    floating_ = false;
    dragSel_ = dragMove_ = false;
    pendingMove_ = false; pendingFloatCurrent_ = false;
    canvas_->clearCutRect();
    rebuildMaskOutline();
    fullDirty_ = true;
    // 旧项目: 套索/魔棒显示精确蒙版轮廓, 不画 bbox 蚂蚁线
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    display_->setSelectionOutline(maskOutline_);
    selMs_ = ms(t0);
    LOGI_SEL("lasso %dx%d mask=%d %.2fms", w, h, cnt, selMs_);
}

void SelectionTool::wandSelect(int cx, int cy) {
    SEL_EDIT_GUARD
    if (!canvas_ || !display_) return;
    if (floating_) commitFloat();
    finishCommit();
    if (pendingMove_) applyAllowed_ = true;
    clearMask();
    resetTransform();
    lassoPts_.clear();
    const int cw = canvas_->width(), ch = canvas_->height();
    cx = std::max(0, std::min(cx, cw - 1));
    cy = std::max(0, std::min(cy, ch - 1));
    const int layer = canvas_->active();
    const uint32_t target = canvas_->getPixelL(layer, cx, cy);
    // 旧项目允许点透明区域做连通选择(常用于选背景); 透明种子直接扫全画布
    const bool transparentSeed = !(target >> 24);
    int ix0 = 0, iy0 = 0, ix1 = cw - 1, iy1 = ch - 1;
    if (!transparentSeed) {
        if (!canvas_->inkBounds(&ix0, &iy0, &ix1, &iy1)) {
            active_ = false;
            display_->setSelection(0, 0, 0, 0, false);
            return;
        }
        ix0 = std::max(0, ix0 - 1); iy0 = std::max(0, iy0 - 1);
        ix1 = std::min(cw - 1, ix1 + 1); iy1 = std::min(ch - 1, iy1 + 1);
    }
    const int W = ix1 - ix0 + 1, H = iy1 - iy0 + 1;
    if ((long long)W * H > 32ll * 1024 * 1024) {
        maskSkipped_ = true;
        active_ = false;
        display_->setSelection(0, 0, 0, 0, false);
        return;
    }
    const auto t0 = Clk::now();
    std::vector<uint8_t> vis((size_t)W * H, 0);
    auto nearColor = [](uint32_t a, uint32_t b) {
        for (int sh = 0; sh < 32; sh += 8)
            if (std::abs((int)((a >> sh) & 255) - (int)((b >> sh) & 255)) > 10) return false;
        return true;
    };
    auto match = [&](int x, int y) { return nearColor(canvas_->getPixelL(layer, ix0 + x, iy0 + y), target); };
    std::vector<int> st;
    st.reserve(1024);
    st.push_back((cy - iy0) * W + (cx - ix0));
    long long count = 0;
    bool over = false;
    const long long cap = 8ll * 1024 * 1024;
    while (!st.empty()) {
        const int p = st.back(); st.pop_back();
        const int y = p / W, x = p % W;
        const size_t row = (size_t)y * W;
        if (vis[row + x] || !match(x, y)) continue;
        int lx = x, rx = x;
        while (lx > 0 && !vis[row + lx - 1] && match(lx - 1, y)) lx--;
        while (rx < W - 1 && !vis[row + rx + 1] && match(rx + 1, y)) rx++;
        for (int xx = lx; xx <= rx; xx++) vis[row + xx] = 1;
        count += rx - lx + 1;
        if (count > cap) { over = true; break; }
        for (int d = -1; d <= 1; d += 2) {
            const int yy = y + d;
            if (yy < 0 || yy >= H) continue;
            const size_t nrow = (size_t)yy * W;
            int xx = lx;
            while (xx <= rx) {
                while (xx <= rx && (vis[nrow + xx] || !match(xx, yy))) xx++;
                if (xx > rx) break;
                st.push_back(yy * W + xx);
                while (xx <= rx && !vis[nrow + xx] && match(xx, yy)) xx++;
            }
        }
    }
    if (over || count < 4) {
        if (over) maskSkipped_ = true;
        active_ = false;
        display_->setSelection(0, 0, 0, 0, false);
        return;
    }
    int fx0 = INT_MAX, fy0 = INT_MAX, fx1 = -1, fy1 = -1;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++)
            if (vis[(size_t)y * W + x]) {
                if (x < fx0) fx0 = x; if (x > fx1) fx1 = x;
                if (y < fy0) fy0 = y; if (y > fy1) fy1 = y;
            }
    if (fx1 < fx0) { active_ = false; display_->setSelection(0, 0, 0, 0, false); return; }
    maskW_ = fx1 - fx0 + 1; maskH_ = fy1 - fy0 + 1;
    mask_.assign((size_t)maskW_ * maskH_, 0);
    int n = 0;
    for (int y = fy0; y <= fy1; y++)
        for (int x = fx0; x <= fx1; x++)
            if (vis[(size_t)y * W + x]) { mask_[(size_t)(y - fy0) * maskW_ + (x - fx0)] = 255; n++; }
    x0_ = ix0 + fx0; y0_ = iy0 + fy0;
    x1_ = ix0 + fx1; y1_ = iy0 + fy1;
    active_ = true;
    floating_ = false;
    dragSel_ = dragMove_ = false;
    pendingMove_ = false; pendingFloatCurrent_ = false;
    canvas_->clearCutRect();
    rebuildMaskOutline();
    fullDirty_ = true;
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    display_->setSelectionOutline(maskOutline_);
    selMs_ = ms(t0);
    LOGI_SEL("wand %dx%d mask=%d %.2fms", maskW_, maskH_, n, selMs_);
}

void SelectionTool::extractMasked() {
    SEL_EDIT_GUARD
    if (!canvas_ || floating_ || mask_.empty()) return;
    finishCommit();
    const auto t0 = Clk::now();
    fw_ = maskW_; fh_ = maskH_;
    fx_ = x0_; fy_ = y0_;
    srcX_ = x0_; srcY_ = y0_;
    buf_.assign((size_t)fw_ * fh_, 0u);
    const int layer = canvas_->active();
    for (int y = 0; y < fh_; y++) {
        for (int x = 0; x < fw_; x++) {
            if (!mask_[(size_t)y * fw_ + x]) continue;
            const uint32_t v = canvas_->getPixelL(layer, x0_ + x, y0_ + y);
            buf_[(size_t)y * fw_ + x] = v;
            if (v) canvas_->setPixelL(layer, x0_ + x, y0_ + y, 0u);
        }
    }
    floating_ = true;
    gpuFloat_ = false;
    bufFloat_ = true;
    copyFloat_ = false;
    holdFloat_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    commitPending_ = false;
    dragMove_ = false;
    display_->clearFloat();
    canvas_->clearCutRect();
    canvas_->markDirtyRect(x0_, y0_, x1_, y1_);
    if (invalidate_) invalidate_(x0_, y0_, x1_, y1_);
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    selMs_ = ms(t0);
    LOGI_SEL("extractMasked %dx%d %.2fms", fw_, fh_, selMs_);
}

void SelectionTool::commitMaskedFloat() {
    SEL_EDIT_GUARD
    if (!canvas_ || !bufFloat_) return;
    const auto t0 = Clk::now();
    const int layer = canvas_->active();
    const int cw = canvas_->width(), ch = canvas_->height();
    for (int y = 0; y < fh_; y++) {
        for (int x = 0; x < fw_; x++) {
            const uint32_t v = buf_[(size_t)y * fw_ + x];
            if (!v) continue;
            const int tx = fx_ + x, ty = fy_ + y;
            if ((unsigned)tx >= (unsigned)cw || (unsigned)ty >= (unsigned)ch) continue;
            // 本层蒙版: 落点本层透明不贴(旧项目语义)
            if (canvas_->alphaLock() && !(canvas_->getPixelL(layer, tx, ty) >> 24)) continue;
            if (alphaOverlay_.load()) {
                uint32_t d = canvas_->getPixelL(layer, tx, ty);
                raster::overPixel(d, v);
                canvas_->setPixelL(layer, tx, ty, d);
            } else {
                // 透明叠加关闭(默认): 半透明像素直接覆盖, 保留源 alpha
                canvas_->setPixelL(layer, tx, ty, v);
            }
        }
    }
    const int ux0 = std::max(0, std::min(srcX_, fx_)), uy0 = std::max(0, std::min(srcY_, fy_));
    const int ux1 = std::min(cw - 1, std::max(srcX_ + fw_ - 1, fx_ + fw_ - 1));
    const int uy1 = std::min(ch - 1, std::max(srcY_ + fh_ - 1, fy_ + fh_ - 1));
    if (ux1 >= ux0 && uy1 >= uy0) {
        canvas_->markDirtyRect(ux0, uy0, ux1, uy1);
        if (invalidate_) invalidate_(ux0, uy0, ux1, uy1);
    }
    floating_ = false;
    bufFloat_ = false;
    copyFloat_ = false;
    textFloat_ = false;
    buf_.clear();
    holdFloat_ = false;
    commitPending_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    display_->clearFloat();
    canvas_->clearCutRect();
    x0_ = fx_; y0_ = fy_;
    x1_ = std::min(cw - 1, fx_ + fw_ - 1); y1_ = std::min(ch - 1, fy_ + fh_ - 1);
    resetTransform();
    clearMask();
    display_->setSelection(std::max(0, x0_), std::max(0, y0_), x1_, y1_, true);
    selMs_ = ms(t0);
    LOGI_SEL("commitMaskedFloat %dx%d %.2fms", fw_, fh_, selMs_);
}

void SelectionTool::commitMaskedTransform() {
    SEL_EDIT_GUARD
    if (!canvas_ || !bufFloat_) return;
    if (!xformActive()) { commitMaskedFloat(); return; }
    const auto t0 = Clk::now();
    float q[8];
    corners(q);
    int bx0 = (int)std::floor(q[0]), by0 = (int)std::floor(q[1]);
    int bx1 = bx0, by1 = by0;
    for (int i = 1; i < 4; i++) {
        bx0 = std::min(bx0, (int)std::floor(q[i * 2]));
        by0 = std::min(by0, (int)std::floor(q[i * 2 + 1]));
        bx1 = std::max(bx1, (int)std::ceil(q[i * 2]));
        by1 = std::max(by1, (int)std::ceil(q[i * 2 + 1]));
    }
    const int cw = canvas_->width(), ch = canvas_->height();
    bx0 = std::max(0, bx0); by0 = std::max(0, by0);
    bx1 = std::min(cw - 1, bx1); by1 = std::min(ch - 1, by1);
    const int layer = canvas_->active();
    MapPre mp;
    prepareMap(&mp);
    for (int dy = by0; dy <= by1; dy++) {
        for (int dx = bx0; dx <= bx1; dx++) {
            int sx, sy;
            if (!mapToSourcePre(mp, (float)dx + 0.5f, (float)dy + 0.5f, &sx, &sy)) continue;
            if (sx < srcX_ || sy < srcY_ || sx >= srcX_ + fw_ || sy >= srcY_ + fh_) continue;
            const uint32_t v = buf_[(size_t)(sy - srcY_) * fw_ + (sx - srcX_)];
            if (!v) continue;
            // 本层蒙版: 落点本层透明不贴(旧项目语义)
            if (canvas_->alphaLock() && !(canvas_->getPixelL(layer, dx, dy) >> 24)) continue;
            uint32_t d = canvas_->getPixelL(layer, dx, dy);
            raster::overPixel(d, v);
            canvas_->setPixelL(layer, dx, dy, d);
        }
    }
    if (bx1 >= bx0 && by1 >= by0) {
        canvas_->markDirtyRect(bx0, by0, bx1, by1);
        if (invalidate_) invalidate_(bx0, by0, bx1, by1);
    }
    floating_ = false;
    bufFloat_ = false;
    copyFloat_ = false;
    textFloat_ = false;
    buf_.clear();
    holdFloat_ = false;
    commitPending_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    display_->clearFloat();
    canvas_->clearCutRect();
    x0_ = bx0; y0_ = by0; x1_ = bx1; y1_ = by1;
    resetTransform();
    clearMask();
    display_->setSelection(bx0, by0, bx1, by1, true);
    selMs_ = ms(t0);
    LOGI_SEL("commitMaskedTransform %dx%d rot=%.1f %.2fms", fw_, fh_, tRot_, selMs_);
}

std::string SelectionTool::describe() const {
    char b[160];
    if (active_) {
        const char* hold = pendingMove_ ? (cRow_ > 0 ? "M" : "D") : (holdFloat_ ? "H" : "");
        snprintf(b, sizeof b, "sel %d,%d %dx%d%s%s %s nz=%d %.2f/%.2f/%.2fms",
                 x0_, y0_, x1_ - x0_ + 1, y1_ - y0_ + 1,
                 floating_ ? "*" : "", hold,
                 gpuFloat_ ? "gpu" : "cpu", floatNonZero_, selMs_, moveMs_, commitMs_);
    } else
        snprintf(b, sizeof b, "sel -");
    return b;
}


// ===== 抽屉：颜色调整 / 像素描边（基于浮层原图预览，可取消还原） =====
static inline void rgb2hsv(float r, float g, float b, float& h, float& s, float& v) {
    float mx = std::max(r, std::max(g, b));
    float mn = std::min(r, std::min(g, b));
    v = mx;
    float d = mx - mn;
    s = (mx <= 0.f) ? 0.f : d / mx;
    if (d <= 1e-6f) { h = 0.f; return; }
    if (mx == r) h = (g - b) / d + (g < b ? 6.f : 0.f);
    else if (mx == g) h = (b - r) / d + 2.f;
    else h = (r - g) / d + 4.f;
    h *= 60.f;
}

static inline void hsv2rgb(float h, float s, float v, float& r, float& g, float& b) {
    h = std::fmod(h, 360.f);
    if (h < 0) h += 360.f;
    float c = v * s;
    float x = c * (1.f - std::fabs(std::fmod(h / 60.f, 2.f) - 1.f));
    float m = v - c;
    float rr = 0, gg = 0, bb = 0;
    if (h < 60) { rr = c; gg = x; }
    else if (h < 120) { rr = x; gg = c; }
    else if (h < 180) { gg = c; bb = x; }
    else if (h < 240) { gg = x; bb = c; }
    else if (h < 300) { rr = x; bb = c; }
    else { rr = c; bb = x; }
    r = rr + m; g = gg + m; b = bb + m;
}

bool SelectionTool::materializeExt() {
    SEL_EDIT_GUARD
    if (extActive_) return true;
    if (!canvas_ || !active_) return false;
    // 浮层(剪切/移动/变换中)还没落定: 画布源区被 cutRect 抠空, 浮层内容只在贴图缓冲里,
    // 直接按 x0.. 读画布会拿到透明像素并丢掉浮层。必须先落定并完成分帧搬运再取材。
    if (floating_ || pendingMove_) {
        commitFloat();
        finishCommit();
    }
    // 每次重新取材都要丢掉上一次抽屉留下的"原图": 否则后续预览会拿旧尺寸/旧内容,
    // 出现花屏且调整无效。
    origSaved_ = false;
    origExt_.clear();
    origExtW_ = origExtH_ = 0;
    const int w = x1_ - x0_ + 1, h = y1_ - y0_ + 1;
    if (w <= 0 || h <= 0) return false;
    const int layer = canvas_->active();
    const int cwx = canvas_->tilesX();
    ext_.assign((size_t)w * h, 0u);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const int cx = x0_ + x, cy = y0_ + y;
            if (!mask_.empty()) {
                const int mx = cx - x0_, my = cy - y0_;
                if (mx < 0 || my < 0 || mx >= maskW_ || my >= maskH_) continue;
                if (!mask_[(size_t)my * maskW_ + mx]) continue;
            }
            ext_[(size_t)y * w + x] = canvas_->getPixelL(layer, cx, cy);
        }
    }
    canvas_->setCutRect(x0_, y0_, x1_, y1_,
                        allLayers_ ? -1 : canvas_->active());   // 合成时只挖空被剪的层(像素先不动, 提交时再清)
    extW_ = w; extH_ = h; extActive_ = true;
    matExt_ = true;
    floating_ = false; bufFloat_ = false; gpuFloat_ = false;
    resetTransform();
    fx_ = x0_; fy_ = y0_; srcX_ = x0_; srcY_ = y0_;
    fw_ = w; fh_ = h;
    refreshFloatRect();
    return true;
}

void SelectionTool::cancelExtPreview() {
    if (!canvas_ || !extActive_) return;
    // 只丢弃抽屉预览: ext_ 是画布内容的副本, 源像素从未被改, 清掉 cutRect 即可恢复原样
    ext_.clear();
    extW_ = extH_ = 0;
    extActive_ = false;
    matExt_ = false;
    origSaved_ = false;
    origExt_.clear();
    origExtW_ = origExtH_ = 0;
    floating_ = false;
    bufFloat_ = false;
    gpuFloat_ = false;
    holdFloat_ = false;
    pendingMove_ = false;
    canvas_->clearCutRect();
    display_->clearFloat();
    resetTransform();
    if (markScreen_) markScreen_(0, 0, canvas_->width() - 1, canvas_->height() - 1);
}

void SelectionTool::centerFloatAtViewCenter() {
    if (!extActive_ || !canvas_ || !display_) return;
    float vcx = 0.f, vcy = 0.f;
    display_->viewCenterCanvas(&vcx, &vcy);
    const int nfx = (int)std::lround(vcx - extW_ * 0.5f);
    const int nfy = (int)std::lround(vcy - extH_ * 0.5f);
    const int oldFx = fx_, oldFy = fy_;
    fx_ = nfx; fy_ = nfy;
    srcX_ = nfx; srcY_ = nfy;
    fw_ = extW_; fh_ = extH_;
    x0_ = std::max(0, nfx); y0_ = std::max(0, nfy);
    x1_ = std::min(canvas_->width() - 1, nfx + extW_ - 1);
    y1_ = std::min(canvas_->height() - 1, nfy + extH_ - 1);
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    markMoveScreen(oldFx, oldFy, nfx, nfy);
    fullDirty_ = true;
}

void SelectionTool::importTextAtViewCenter(int w, int h, const uint32_t* px) {
    if (!canvas_ || !display_ || w <= 0 || h <= 0 || !px) return;
    finishCommit();
    deselect();   // 先把已有选区/浮层收尾, 再进入文字"裁剪中"状态
    buf_.assign(px, px + (size_t)w * h);
    fw_ = w; fh_ = h;
    float vcx = 0.f, vcy = 0.f;
    display_->viewCenterCanvas(&vcx, &vcy);
    fx_ = (int)std::lround(vcx - w * 0.5f);
    fy_ = (int)std::lround(vcy - h * 0.5f);
    srcX_ = fx_; srcY_ = fy_;
    active_ = true;
    floating_ = true;
    bufFloat_ = true;
    copyFloat_ = true;       // 复制型: 取消时直接丢弃, 不写回
    textFloat_ = true;       // 文字不受本层蒙版限制
    extActive_ = false;
    gpuFloat_ = false;
    holdFloat_ = false;
    pendingMove_ = false;
    pendingFloatCurrent_ = false;
    x0_ = std::max(0, fx_); y0_ = std::max(0, fy_);
    x1_ = std::min(canvas_->width() - 1, fx_ + w - 1);
    y1_ = std::min(canvas_->height() - 1, fy_ + h - 1);
    canvas_->clearCutRect();
    display_->setSelection(x0_, y0_, x1_, y1_, true);
    fullDirty_ = true;
    if (markScreen_) markScreen_(0, 0, canvas_->width() - 1, canvas_->height() - 1);
}

void SelectionTool::saveOrigIfNeeded() {
    if (origSaved_) return;
    origExt_ = ext_;
    origExtW_ = extW_; origExtH_ = extH_;
    origSaved_ = true;
}

void SelectionTool::restoreOrig() {
    if (!origSaved_) return;
    ext_ = origExt_;
    extW_ = origExtW_; extH_ = origExtH_;
    extActive_ = !ext_.empty();
    floating_ = extActive_;
    markExtDirty();
}

void SelectionTool::markExtDirty() {
    // 抽屉预览只改 ext_ 覆盖层, 画布/mip 没变: 标屏幕矩形就够, 不要整屏重合成
    if (!display_) return;
    int bx0 = 0, by0 = 0, bx1 = 0, by1 = 0;
    transformedBBox(fx_, fy_, &bx0, &by0, &bx1, &by1);
    bx0 = std::max(0, bx0); by0 = std::max(0, by0);
    bx1 = std::min(canvas_->width() - 1, bx1);
    by1 = std::min(canvas_->height() - 1, by1);
    if (bx0 <= bx1 && by0 <= by1 && markScreen_) markScreen_(bx0, by0, bx1, by1);
}

void SelectionTool::refreshFloatRect() {
    fullDirty_ = true;
    if (markScreen_) markScreen_(x0_, y0_, x1_, y1_);
    if (invalidate_) invalidate_(x0_, y0_, x1_, y1_);
}

void SelectionTool::adjustHSBA(float hueDeg, float satMul, float briMul, float alphaMul, float blurAmt) {
    if (!extActive_ || extW_ <= 0 || extH_ <= 0) return;
    // 原图尺寸和当前取材不一致(旧选区残留)就丢弃, 否则按新尺寸读旧缓冲会越界/花屏
    if (origSaved_ && (origExtW_ != extW_ || origExtH_ != extH_ ||
                       origExt_.size() != ext_.size())) {
        origSaved_ = false;
        origExt_.clear();
    }
    saveOrigIfNeeded();
    ext_ = origExt_;
    const int w = extW_, h = extH_;
    // 透明度/颜色
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            uint32_t p = ext_[(size_t)y * w + x];
            float a = (p >> 24) / 255.f;
            if (a <= 0.f) continue;
            float r = (p & 0xFF) / 255.f / a;
            float g = ((p >> 8) & 0xFF) / 255.f / a;
            float b = ((p >> 16) & 0xFF) / 255.f / a;
            float hh, ss, vv;
            rgb2hsv(r, g, b, hh, ss, vv);
            hh += hueDeg;
            ss = std::min(1.f, std::max(0.f, ss * satMul));
            vv = std::min(1.f, std::max(0.f, vv * briMul));
            hsv2rgb(hh, ss, vv, r, g, b);
            a = std::min(1.f, std::max(0.f, a * alphaMul));
            uint32_t aa = (uint32_t)std::lround(a * 255.f);
            uint32_t rr = (uint32_t)std::lround(r * a * 255.f);
            uint32_t gg = (uint32_t)std::lround(g * a * 255.f);
            uint32_t bb = (uint32_t)std::lround(b * a * 255.f);
            ext_[(size_t)y * w + x] = (aa << 24) | (bb << 16) | (gg << 8) | rr;
        }
    }
    // 模糊：对整块做盒式模糊(含 alpha)，半径=blur*8
    int rad = (int)std::lround(blurAmt * 8.f);
    if (rad > 0) {
        std::vector<uint32_t> tmp = ext_;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    uint32_t sa = 0, sr = 0, sg = 0, sb = 0; int n = 0;
                    for (int k = -rad; k <= rad; k++) {
                        int xx = pass == 0 ? x + k : x;
                        int yy = pass == 0 ? y : y + k;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        uint32_t q = tmp[(size_t)yy * w + xx];
                        sa += (q >> 24) & 0xFF; sb += (q >> 16) & 0xFF;
                        sg += (q >> 8) & 0xFF; sr += q & 0xFF; n++;
                    }
                    if (!n) continue;
                    uint32_t q = ((sa / n) << 24) | ((sb / n) << 16) | ((sg / n) << 8) | (sr / n);
                    (pass == 0 ? tmp : ext_)[(size_t)y * w + x] = q;
                }
            }
            if (pass == 0) ext_ = tmp;
        }
    }
    markExtDirty();
}

void SelectionTool::applyStroke(int mode, int size, int dirMask, bool outer, float depth, uint32_t color) {
    if (mode <= 0 || !extActive_ || extW_ <= 0 || extH_ <= 0) return;
    if (origSaved_ && (origExtW_ != extW_ || origExtH_ != extH_ ||
                       origExt_.size() != ext_.size())) {
        origSaved_ = false;
        origExt_.clear();
    }
    saveOrigIfNeeded();
    const int w = extW_, h = extH_;
    std::vector<uint32_t> src = origExt_;
    std::vector<uint32_t> dst = src;
    size = std::max(1, std::min(size, 10));
    if (dirMask == 0) dirMask = 15;
    struct D { int dx, dy, bit; };
    D dirs[4] = { {0, -1, 8}, {0, 1, 4}, {-1, 0, 1}, {1, 0, 2} };
    auto opaque = [&](int x, int y) -> bool {
        if (x < 0 || y < 0 || x >= w || y >= h) return false;
        return (src[(size_t)y * w + x] >> 24) != 0;
    };
    auto borderColor = [&](int sx, int sy) -> uint32_t {
        if (mode == 2) {
            uint32_t p = src[(size_t)sy * w + sx];
            float a = (p >> 24) / 255.f;
            if (a <= 0) return 0;
            float r = (p & 0xFF) / 255.f / a;
            float g = ((p >> 8) & 0xFF) / 255.f / a;
            float b = ((p >> 16) & 0xFF) / 255.f / a;
            float f = std::min(1.f, std::max(0.f, 1.f - depth));
            r *= f; g *= f; b *= f;
            return (0xFFu << 24) | ((uint32_t)std::lround(b * 255) << 16) |
                   ((uint32_t)std::lround(g * 255) << 8) | (uint32_t)std::lround(r * 255);
        }
        return color;
    };
    if (outer) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (opaque(x, y)) continue;
                for (auto& d : dirs) {
                    if (!(dirMask & d.bit)) continue;
                    for (int k = 1; k <= size; k++) {
                        int sx = x - d.dx * k, sy = y - d.dy * k;
                        if (opaque(sx, sy)) { dst[(size_t)y * w + x] = borderColor(sx, sy); goto nextOuter; }
                    }
                }
            nextOuter:;
            }
        }
    } else {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!opaque(x, y)) continue;
                for (auto& d : dirs) {
                    if (!(dirMask & d.bit)) continue;
                    bool hit = false;
                    for (int k = 1; k <= size; k++) {
                        if (!opaque(x + d.dx * k, y + d.dy * k)) { hit = true; break; }
                    }
                    if (hit) { dst[(size_t)y * w + x] = borderColor(x, y); break; }
                }
            }
        }
    }
    ext_ = dst;
    markExtDirty();
}

} // namespace endpix
