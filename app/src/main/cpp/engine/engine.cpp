#include "engine.h"
#include "png_export.h"
#include "psd_export.h"
#include "gif_writer.h"
#include "parallel.h"
#include "../raster/raster.h"
#include <android/log.h>
#include <chrono>
#include <cstdio>
#include <zlib.h>
#include <climits>
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <algorithm>
#include <fstream>
#include <sstream>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "endpix", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "endpix", __VA_ARGS__)

namespace endpix {

std::string runCanvasBench();   // bench/canvas_bench.cpp (debug only)

constexpr int THUMB = 48;      // 时间轴缩略图边长
constexpr int MMTHUMB = 192;   // 小地图缩略图边长(更高采样; 仅在面板打开时生成)

// 录制帧/日志落盘: 数据 fsync 后再原子 rename, 立即杀进程/掉电不会留下半个文件
static void fsyncPath(const std::string& p) {
    const int fd = open(p.c_str(), O_RDONLY);
    if (fd >= 0) { fsync(fd); close(fd); }
}
static void fsyncStream(FILE* f) {
    if (!f) return;
    fflush(f);
    const int fd = fileno(f);
    if (fd >= 0) fsync(fd);
}

// ---------- ADPF (PerformanceHintManager, API 31+, dlsym 规避 minSdk 26 头) ----------
struct APerformanceHintManager;
struct APerformanceHintSession;
typedef APerformanceHintManager* (*PFN_getHintManager)();
typedef APerformanceHintSession* (*PFN_createHintSession)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
typedef int (*PFN_reportWork)(APerformanceHintSession*, int64_t);
typedef void (*PFN_closeHintSession)(APerformanceHintSession*);

static PFN_getHintManager pGetHintMgr = nullptr;
static PFN_createHintSession pCreateHint = nullptr;
static PFN_reportWork pReportWork = nullptr;
static PFN_closeHintSession pCloseHint = nullptr;
static APerformanceHintSession* gHintSession = nullptr;
static bool gHintTried = false;

static void hintInit(int64_t targetNanos) {
    if (gHintTried) return;
    gHintTried = true;
    pGetHintMgr = (PFN_getHintManager)dlsym(RTLD_DEFAULT, "APerformanceHint_getManager");
    pCreateHint = (PFN_createHintSession)dlsym(RTLD_DEFAULT, "APerformanceHint_createSession");
    pReportWork = (PFN_reportWork)dlsym(RTLD_DEFAULT, "APerformanceHint_reportActualWorkDuration");
    pCloseHint = (PFN_closeHintSession)dlsym(RTLD_DEFAULT, "APerformanceHint_closeSession");
    if (!pGetHintMgr || !pCreateHint || !pReportWork) { LOGI("ADPF unavailable"); return; }
    auto* mgr = pGetHintMgr();
    if (!mgr) { LOGI("ADPF no manager"); return; }
    int32_t tid = (int32_t)gettid();
    gHintSession = pCreateHint(mgr, &tid, 1, targetNanos);
    LOGI("ADPF session=%p target=%lldns", (void*)gHintSession, (long long)targetNanos);
}

static const uint32_t kPalette[] = {
    0xFFFFFFFFu, 0xFF000000u, 0xFF0000FFu, 0xFFFFC200u, 0xFF00C2FFu, 0xFF00FF00u, 0xFFFF00B4u
};

// ---------- ComposePool ----------
void ComposePool::start(int workers) {
    quit_.store(false);
    for (int i = 0; i < workers; i++) th_.emplace_back([this]() { worker(); });
}

void ComposePool::stop() {
    quit_.store(true);
    cv_.notify_all();
    for (auto& t : th_) if (t.joinable()) t.join();
    th_.clear();
}

void ComposePool::worker() {
    int seen = 0;
    for (;;) {
        int g;
        {
            std::unique_lock<std::mutex> lk(mu_);
            cv_.wait(lk, [&]() { return quit_.load() || gen_.load() != seen; });
            if (quit_.load()) return;
            g = gen_.load();
        }
        seen = g;
        const auto* job = job_;
        const int n = n_;
        // 原子取号分活, 每个 tile 只合成一次(之前每个 worker 都跑全量, 4 份重复)
        for (;;) {
            const int i = next_.fetch_add(1);
            if (i >= n) break;
            (*const_cast<std::function<void(int)>*>(job))(i);
        }
        done_.fetch_add(1);
    }
}

void ComposePool::run(int n, const std::function<void(int)>& fn) {
    if (th_.empty() || n < 4) {           // 太小就别付线程开销
        for (int i = 0; i < n; i++) fn(i);
        return;
    }
    {
        std::lock_guard<std::mutex> lk(mu_);
        job_ = &fn; n_ = n; done_.store(0); next_.store(0);
        gen_.fetch_add(1);
    }
    cv_.notify_all();
    for (;;) {                            // 主线程也参与分活
        const int i = next_.fetch_add(1);
        if (i >= n) break;
        fn(i);
    }
    while (done_.load() < (int)th_.size()) std::this_thread::yield();
    std::lock_guard<std::mutex> lk(mu_);
    job_ = nullptr;
}

static size_t recBytes(const Canvas::UndoRecord& r);

bool Engine::start(ANativeWindow* win, int vw, int vh, int canvasW, int canvasH, int layers) {
    if (running_.load()) return true;
    running_.store(true);
    // 分配/初始化全部在渲染线程做, 不阻塞 UI (16383²×4 约 3s)
    th_ = std::thread([this, win, vw, vh, canvasW, canvasH, layers]() {
        using Clk2 = std::chrono::steady_clock;
        const auto tInit = Clk2::now();
        auto elapsed = [&]() {
            return (double)std::chrono::duration_cast<std::chrono::microseconds>(Clk2::now() - tInit).count() / 1000.0;
        };
        if (!canvas_.create(canvasW, canvasH, layers)) { LOGE("canvas create fail"); status_.store(2); running_.store(false); return; }
        layerCount_.store(canvas_.layers()); activeLayer_.store(canvas_.active());
        mip_.reset(&canvas_);
        refreshLayerMetaCache();
        refreshLayerPropsCache();
        screenFull_ = true;
        LOGI("init canvas %.1fms", elapsed());
        pool_.start(3);   // 主线程 + 3 worker = 4 线程
        if (!display_.init(win, canvas_.width(), canvas_.height())) { LOGE("display init fail"); status_.store(2); running_.store(false); return; }
        LOGI("init display %.1fms", elapsed());
        display_.onSize(vw, vh);
        sel_.init(&canvas_, &display_);
        sel_.setFlush([this]() { flushDirty(); });
        sel_.setInvalidate([this](int x0, int y0, int x1, int y1) {
            mip_.invalidateRect(x0, y0, x1, y1);
            markScreenRect(x0, y0, x1, y1);
        });
        sel_.setMarkScreen([this](int x0, int y0, int x1, int y1) {
            markScreenRect(x0, y0, x1, y1);   // 浮层移动: 只重绘屏幕, mip 内容没变
        });
        // 选区内部落像素(移动落定/删除/变换/粘贴/长按裁剪)也进撤销栈与回放步骤
        sel_.setEditGuard([this]() { selEditBegin(); }, [this]() { selEditEnd(); });
        hintInit(1000000000LL / 120);
        status_.store(1);
        LOGI("engine start %dx%d L%d total %.1fms", canvas_.width(), canvas_.height(), canvas_.layers(), elapsed());
        loop();
        if (gHintSession && pCloseHint) pCloseHint(gHintSession);
        gHintSession = nullptr;
        display_.shutdown();
        pool_.stop();
    });
    return true;
}

void Engine::joinExportJobs() {
    exportAbort_.store(true, std::memory_order_relaxed);
    if (frameTh_.joinable()) frameTh_.join();
    if (exportTh_.joinable()) exportTh_.join();
    if (dbgExportTh_.joinable()) dbgExportTh_.join();
    frameReady_.store(false);
    frameBusy_.store(false);
    exportBusy_.store(false);
    canvasBusy_.store(false);
}

void Engine::stop() {
    {
        std::lock_guard<std::mutex> lk(exMu_);
        if (pngJob_) { pngJob_->abort(); pngJob_.reset(); }
        // 唤醒所有在 exportPngFile/exportPsdFile/exportModeFile 上等待的调用线程
        exResult_ = "err stopped";
        exDone_ = true;
    }
    exCv_.notify_all();
    running_.store(false);
    if (th_.joinable()) th_.join();
    joinExportJobs();
    pool_.stop();
    canvas_.destroy();
}

void Engine::resize(int vw, int vh) {
    // 只在渲染线程改 EGL viewport, 这里仅登记
    pendingW_.store(vw);
    pendingH_.store(vh);
}

void Engine::setInsets(int l, int t, int r, int b) {
    insL_.store(l); insT_.store(t); insR_.store(r); insB_.store(b);
    insetsDirty_.store(true);
}

static int64_t steadyEventMs() {
    return (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void Engine::touch(int action, float x, float y, int64_t timeMs) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({action, x, y, 0.0f, timeMs});
}

void Engine::zoom(float factor, float fx, float fy) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({4, factor, fx, fy});
}

void Engine::setTool(int t) {
    tool_.store(t);
    sel_.setMode(t == 1 ? 1 : 0);
}

void Engine::setCanvasStyle(int gridW, int gridH, int gridColor, int gridAlphaPct, int lineW100,
                            int chkTile, int chkA, int chkB, int bgColor) {
    auto rgb = [](int c, float& r, float& g, float& b) {
        r = (float)((c >> 16) & 255) / 255.0f;
        g = (float)((c >> 8) & 255) / 255.0f;
        b = (float)(c & 255) / 255.0f;
    };
    float gr, gg, gb, ar, ag, ab, br, bg, bb, xr, xg, xb;
    rgb(gridColor, gr, gg, gb); rgb(chkA, ar, ag, ab); rgb(chkB, br, bg, bb); rgb(bgColor, xr, xg, xb);
    display_.setStyle((float)std::max(1, gridW), (float)std::max(1, gridH), gr, gg, gb,
                      std::max(0, std::min(100, gridAlphaPct)) / 100.0f,
                      std::max(25, std::min(2000, lineW100)) / 100.0f,
                      (float)std::max(1, std::min(64, chkTile)),
                      ar, ag, ab, br, bg, bb, xr, xg, xb);
    // 放大镜(旧项目: 透明像素画棋盘格/画布外画布外背景)用同一套风格
    auto abgr = [](int c) -> uint32_t {
        return 0xFF000000u | ((uint32_t)(c & 255) << 16) |
               ((uint32_t)((c >> 8) & 255) << 8) | (uint32_t)((c >> 16) & 255);
    };
    magChkA_ = abgr(chkA);
    magChkB_ = abgr(chkB);
    magBg_ = abgr(bgColor);
    magChkTile_ = (float)std::max(1, std::min(64, chkTile));
    screenFull_ = true;
}

void Engine::setBrush(float canvasPx, uint32_t argb) {
    // 画笔大小 = 画布像素直径: 缩放画布不改变实际落笔宽度
    brushPx_.store(std::max(1.0f, std::min(canvasPx, 512.0f)));
    brushColor_.store(argb);
}

void Engine::layerOp(int op, int arg) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({10, (float)op, (float)arg, 0.0f});
}

void Engine::requestSetLayerName(int idx, std::string name) {
    {
        std::lock_guard<std::mutex> lk(layerNameMu_);
        layerNamePending_ = std::move(name);
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({63, (float)idx, 0.0f, 0.0f});
}

void Engine::requestSetLayerOpacity(int idx, float o) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({64, (float)idx, o, 0.0f});
}

void Engine::requestMeasureFixed(bool on) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({70, on ? 1.0f : 0.0f, 0.0f, 0.0f});
}

void Engine::requestMeasureCenterStart() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({71, 0.0f, 0.0f, 0.0f});
}

void Engine::requestMeasureSetStartPending() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({72, 0.0f, 0.0f, 0.0f});
}

void Engine::requestPolyOp(int op) {
    std::lock_guard<std::mutex> lk(mu_);
    const int a = 75 + std::max(0, std::min(3, op));
    if (queue_.size() < 4096) queue_.push_back({a, 0.0f, 0.0f, 0.0f});
}

void Engine::refreshLayerMetaCache() {
    std::vector<std::pair<std::string, int>> out;
    const int n = canvas_.layers();
    out.reserve((size_t)n);
    for (int l = 0; l < n; l++) {
        int op = (int)std::lround(canvas_.layerOpacity(l) * 1000.f);
        out.emplace_back(canvas_.layerName(l), std::max(0, std::min(1000, op)));
    }
    std::lock_guard<std::mutex> lk(layerMetaMu_);
    layerMetaCache_.swap(out);
}

std::string Engine::layerMetaJoined() {
    std::lock_guard<std::mutex> lk(layerMetaMu_);
    std::string out;
    for (size_t i = 0; i < layerMetaCache_.size(); i++) {
        if (i) out.push_back('');
        out += layerMetaCache_[i].first;
        out.push_back('');
        out += std::to_string(layerMetaCache_[i].second);
    }
    return out;
}

void Engine::beginZoomGesture(float fx, float fy) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({44, fx, fy, 0.0f});
}
void Engine::zoomGesture(float totalFactor, float fx, float fy) {
    std::lock_guard<std::mutex> lk(mu_);
    // 捏合事件可能 120Hz: 只保留最新一次, 避免渲染线程积压
    if (!queue_.empty() && queue_.back().action == 45) {
        queue_.back() = {45, totalFactor, fx, fy};
        return;
    }
    if (queue_.size() < 4096) queue_.push_back({45, totalFactor, fx, fy});
}
void Engine::endZoomGesture() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({46, 0.0f, 0.0f, 0.0f});
}

void Engine::requestCancelFirstStroke() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({47, 0.0f, 0.0f, 0.0f});
}

void Engine::pan(float dx, float dy) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({5, dx, dy, 0.0f});
}

// ---------- 完美线条(Aseprite 语义) ----------
void Engine::ppReset() {
    ppPrevOk_ = ppTailOk_ = ppPatchOk_ = false;
}

void Engine::ppFeed(int x, int y, uint32_t color) {
    const int layer = canvas_.active();
    if (!ppTailOk_) {
        raster::stampCircle(canvas_, x, y, 0, color, false, buildup_.load());
        ppTailX_ = x; ppTailY_ = y; ppTailOk_ = true;
        return;
    }
    if (!ppPrevOk_) {
        ppPatchX_ = x; ppPatchY_ = y;
        ppPatch_ = canvas_.getPixelL(layer, x, y); ppPatchOk_ = true;
        raster::stampCircle(canvas_, x, y, 0, color, false, buildup_.load());
        ppPrevX_ = ppTailX_; ppPrevY_ = ppTailY_; ppPrevOk_ = true;
        ppTailX_ = x; ppTailY_ = y;
        return;
    }
    // A-B-C 三点: B 是肘部(两边都与 B 轴对齐, A/C 彼此对角) → 删 B
    const bool corner = (ppPrevX_ == ppTailX_ || ppPrevY_ == ppTailY_) &&
                        (x == ppTailX_ || y == ppTailY_) &&
                        (ppPrevX_ != x) && (ppPrevY_ != y);
    if (corner) {
        if (ppPatchOk_) raster::setPixelRaw(canvas_, layer, ppPatchX_, ppPatchY_, ppPatch_);
        ppPatchX_ = x; ppPatchY_ = y;
        ppPatch_ = canvas_.getPixelL(layer, x, y); ppPatchOk_ = true;
        raster::stampCircle(canvas_, x, y, 0, color, false, buildup_.load());
        ppTailX_ = x; ppTailY_ = y;
    } else {
        ppPatchX_ = x; ppPatchY_ = y;
        ppPatch_ = canvas_.getPixelL(layer, x, y); ppPatchOk_ = true;
        raster::stampCircle(canvas_, x, y, 0, color, false, buildup_.load());
        ppPrevX_ = ppTailX_; ppPrevY_ = ppTailY_;
        ppTailX_ = x; ppTailY_ = y;
    }
}

void Engine::ppStampAt(int x, int y, uint32_t color, int radius) {
    raster::stampCircle(canvas_, x, y, radius, color, false, buildup_.load());
    ppLastStampX_ = x; ppLastStampY_ = y;
}

// 宽笔刷: 只有"确定不会成为肘部"的点才落笔; 落笔按 radius/2 间距抽稀, 开销与笔宽同阶
void Engine::ppFeedSmooth(int x, int y, uint32_t color, int radius) {
    if (!ppPrevOk_) {                 // 第一个点立即落笔(笔画起点)
        ppPrevX_ = x; ppPrevY_ = y; ppPrevOk_ = true;
        ppStampAt(x, y, color, radius);
        return;
    }
    if (!ppPendOk_) { ppPendX_ = x; ppPendY_ = y; ppPendOk_ = true; return; }
    const bool corner = (ppPrevX_ == ppPendX_ || ppPrevY_ == ppPendY_) &&
                        (x == ppPendX_ || y == ppPendY_) &&
                        (ppPrevX_ != x) && (ppPrevY_ != y);
    if (corner) {
        ppPendX_ = x; ppPendY_ = y;   // 肘部丢弃, 上一已提交点不变
        return;
    }
    const int step = std::max(1, radius / 2);
    if (std::abs(ppPendX_ - ppLastStampX_) + std::abs(ppPendY_ - ppLastStampY_) >= step) {
        ppStampAt(ppPendX_, ppPendY_, color, radius);
    }
    ppPrevX_ = ppPendX_; ppPrevY_ = ppPendY_;
    ppPendX_ = x; ppPendY_ = y;
}

void Engine::ppFlushSmooth(uint32_t color, int radius) {
    if (ppPendOk_) ppStampAt(ppPendX_, ppPendY_, color, radius);
    ppPendOk_ = false;
}

void Engine::ppFeedSegment(int x0, int y0, int x1, int y1, uint32_t color) {
    // Bresenham 8 连通; 起点已在上一次喂过, 只喂新像素
    int dx = std::abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
    int dy = -std::abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    int x = x0, y = y0;
    for (;;) {
        if (!(x == x0 && y == y0)) ppFeed(x, y, color);
        if (x == x1 && y == y1) break;
        const int e2 = 2 * err;
        if (e2 >= dy) { err += dy; x += sx; }
        if (e2 <= dx) { err += dx; y += sy; }
    }
}

void Engine::requestNewDocument(int w, int h) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({41, (float)w, (float)h, 0.0f});
}

void Engine::requestResizeCanvas(int w, int h, int ax, int ay) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096)
        queue_.push_back({43, (float)w, (float)h, (float)(std::max(0, std::min(ax, 2)) * 3 + std::max(0, std::min(ay, 2)))});
}

void Engine::requestScaleSel(int pctW, int pctH) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({27, (float)pctW, (float)pctH, 0.0f});
}

void Engine::scaleSelection(int pctW, int pctH) {
    if (!sel_.active()) return;
    sel_.finishCommit();
    if (sel_.floating()) sel_.commitFloat();
    sel_.finishCommit();
    int rx0, ry0, rx1, ry1;
    sel_.rect(&rx0, &ry0, &rx1, &ry1);
    const int cw = canvas_.width(), ch = canvas_.height();
    rx0 = std::max(0, rx0); ry0 = std::max(0, ry0);
    rx1 = std::min(cw - 1, rx1); ry1 = std::min(ch - 1, ry1);
    const int w = rx1 - rx0 + 1, h = ry1 - ry0 + 1;
    if (w <= 0 || h <= 0) return;
    pctW = std::max(5, std::min(pctW, 800));
    pctH = std::max(5, std::min(pctH, 800));
    const int nw = std::max(1, (int)((long long)w * pctW / 100));
    const int nh = std::max(1, (int)((long long)h * pctH / 100));
    const int firstLayer = selAllLayers_.load() ? 0 : canvas_.active();
    const int lastLayer = selAllLayers_.load() ? canvas_.layers() - 1 : canvas_.active();
    std::vector<uint32_t> src((size_t)w * h);
    beginStrokeRecord();
    for (int layer = firstLayer; layer <= lastLayer; layer++) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                src[(size_t)y * w + x] = canvas_.getPixelL(layer, rx0 + x, ry0 + y);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) canvas_.setPixelL(layer, rx0 + x, ry0 + y, 0u);
        for (int dy = 0; dy < nh; dy++) {
            const int ty = ry0 + dy;
            if (ty >= ch) break;
            const int sy = (int)((long long)dy * h / nh);
            for (int dx = 0; dx < nw; dx++) {
                const int tx = rx0 + dx;
                if (tx >= cw) break;
                const int sx = (int)((long long)dx * w / nw);
                const uint32_t v = src[(size_t)sy * w + sx];
                if (!v) continue;
                uint32_t d = canvas_.getPixelL(layer, tx, ty);
                raster::overPixel(d, v);
                canvas_.setPixelL(layer, tx, ty, d);
            }
        }
    }
    endStrokeRecord();
    mip_.invalidateRect(rx0, ry0, std::min(cw - 1, rx0 + nw - 1), std::min(ch - 1, ry0 + nh - 1));
    screenFull_ = true;
    sel_.deselect();
    sel_.setRectDirect(rx0, ry0, std::min(cw - 1, rx0 + nw - 1), std::min(ch - 1, ry0 + nh - 1));
    LOGI("scaleSel %dx%d -> %dx%d", w, h, nw, nh);
}

void Engine::requestSaveSnapshot(std::string path, bool wait) {
    {
        std::lock_guard<std::mutex> lk(snapMu_);
        snapPath_ = std::move(path);
        snapWait_ = wait;
        snapDone_ = false;
    }
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (queue_.size() < 4096) queue_.push_back({30, 0.0f, 0.0f, 0.0f});
    }
    if (wait) {
        std::unique_lock<std::mutex> lk(snapMu_);
        snapCv_.wait_for(lk, std::chrono::seconds(5), [&] { return snapDone_; });
    }
}

void Engine::requestLoadSnapshot(std::string path) {
    {
        std::lock_guard<std::mutex> lk(snapMu_);
        snapPath_ = std::move(path);
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({31, 0.0f, 0.0f, 0.0f});
}

// 渲染线程: 序列化 -> 后台线程写临时文件再改名
// wait=false 的自动保存切成 ~2MB/帧 的作业: 不再一次性复制整个画布卡住渲染线程;
// 期间只要有新编辑(rev 变化)就放弃本次, 下次空闲重新来, 保证像素/撤销栈一致。
void Engine::saveSnapshotNow(const std::string& path, bool wait) {
    if (wait) {
        snapJob_.reset();   // 同步保存(切标签/暂停)优先, 丢掉半成品异步作业
    } else if (snapJob_) {
        if (snapJob_->path == path) return;   // 同一标签已在后台打包
        snapJob_.reset();                      // 换标签了, 旧作业作废
    }
    // 已确认但还没搬完的落定先实体化; 未确认的裁剪浮层保持原样一起存进 v3
    sel_.prepareForSnapshot();
    // 后台打断在笔画中途时可能留下未收尾的记录: 落盘前丢弃, 避免恢复后和新笔画合并成多出来的一步
    discardOpenStrokeRecord();
    LOGI("snapshot save undo=%zu redo=%zu async=%d", undo_.size(), redo_.size(), wait ? 0 : 1);
    const uint64_t rev = canvas_.revision();
    // onPause 和 onStop 会连着触发: 内容没变就不重复序列化(40MB 原始数据复制+压缩不便宜)
    if (rev == lastSavedRev_.load() && frameRev_.load() == lastSavedFrameRev_ &&
        selStateRev_ == lastSavedSelStateRev_ && layerMetaRev_ == lastSavedLayerMetaRev_) {
        std::lock_guard<std::mutex> lk(snapMu_);
        snapDone_ = true;
        snapCv_.notify_all();
        return;
    }
    // 当前帧先落进帧数组, 保证快照里的帧内容是最新的
    if (frame_ >= 0 && frame_ < (int)frameData_.size()) storeFrameData();
    const int count = canvas_.liveTileCount();
    if (path.empty() || count > 8192) {
        LOGI("snapshot skip: tiles=%d path=%s", count, path.c_str());
        std::lock_guard<std::mutex> lk(snapMu_);
        snapDone_ = true;
        snapCv_.notify_all();
        return;
    }
    auto j = std::make_unique<SnapJob>();
    j->path = path;
    j->wait = wait;
    j->lc = canvas_.layers();
    j->count = count;
    j->buf.reserve((size_t)32 + (size_t)count * (Canvas::TILE_PIX * 4 + 16));
    j->rev = canvas_.revision();
    j->frameRev = frameRev_.load();
    j->selRev = selStateRev_;
    j->layerRev = layerMetaRev_;
    if (wait) {
        while (!snapshotStep(*j, (size_t)-1)) {}
        snapshotComplete(*j);
    } else {
        snapJob_ = std::move(j);
        tickSnapshot();   // 立刻推进第一片
    }
}

// 序列化一片; budgetBytes=本帧最多追加的字节数
bool Engine::snapshotStep(SnapJob& j, size_t budgetBytes) {
    auto& buf = j.buf;
    auto put32 = [&](uint32_t v) {
        buf.push_back((uint8_t)(v & 255)); buf.push_back((uint8_t)((v >> 8) & 255));
        buf.push_back((uint8_t)((v >> 16) & 255)); buf.push_back((uint8_t)((v >> 24) & 255));
    };
    const size_t mark = buf.size();
    auto overBudget = [&]() { return buf.size() - mark >= budgetBytes; };
    for (;;) {
        switch (j.phase) {
        case SnapJob::START:
            put32(0x534E5058u);   // 'X P N S'
            put32(8u);   // v8: 撤销记录带类型/所属帧, 支持图层元数据记录
            put32((uint32_t)canvas_.width()); put32((uint32_t)canvas_.height());
            put32((uint32_t)j.lc); put32((uint32_t)canvas_.active());
            put32((uint32_t)j.count);
            j.phase = SnapJob::TILES;
            break;
        case SnapJob::TILES: {
            const int total = canvas_.tilesX() * canvas_.tilesY();
            while (j.l < j.lc) {
                while (j.ti < total) {
                    const int l = j.l, ti = j.ti;
                    const bool solid = canvas_.isSolidTile(l, ti);
                    const uint32_t* px = canvas_.tile(l, ti);
                    if (solid || px) {
                        put32((uint32_t)l); put32((uint32_t)ti);
                        if (solid) {
                            put32(1u); put32(canvas_.solidTileColor(l, ti));
                        } else {
                            put32(0u);
                            const size_t off = buf.size();
                            buf.resize(off + Canvas::TILE_PIX * 4);
                            memcpy(buf.data() + off, px, Canvas::TILE_PIX * 4);
                        }
                    }
                    j.ti++;
                    if (overBudget()) return false;
                }
                j.l++; j.ti = 0;
            }
            // v2: 图层可见/锁定 + 全部帧 + 播放设置 + 上次裁剪
            unsigned visBits = 0, lckBits = 0;
            for (int l = 0; l < j.lc && l < 32; l++) {
                if (canvas_.layerVisible(l)) visBits |= 1u << l;
                if (canvas_.isLayerLocked(l)) lckBits |= 1u << l;
            }
            put32(visBits); put32(lckBits);
            put32((uint32_t)frameData_.size());
            put32((uint32_t)frame_);
            put32((uint32_t)playFps_.load());
            put32((uint32_t)loopStart_.load());
            put32((uint32_t)loopEnd_.load());
            j.frameIdx = 0;
            j.phase = SnapJob::FRAMES;
            break;
        }
        case SnapJob::FRAMES: {
            while (j.frameIdx < frameData_.size()) {
                const auto& fd = frameData_[j.frameIdx];
                put32((uint32_t)fd.size());
                buf.insert(buf.end(), fd.begin(), fd.end());
                j.frameIdx++;
                if (overBudget()) return false;
            }
            const bool lcOk = sel_.lastW() > 0 && sel_.lastH() > 0 &&
                              (long long)sel_.lastW() * sel_.lastH() <= 4ll * 1024 * 1024 &&
                              sel_.lastCutPixels().size() >= (size_t)sel_.lastW() * sel_.lastH();
            put32(lcOk ? (uint32_t)sel_.lastW() : 0u);
            put32(lcOk ? (uint32_t)sel_.lastH() : 0u);
            if (lcOk) {
                const size_t off = buf.size();
                buf.resize(off + (size_t)sel_.lastW() * sel_.lastH() * 4);
                memcpy(buf.data() + off, sel_.lastCutPixels().data(), (size_t)sel_.lastW() * sel_.lastH() * 4);
            }
            j.phase = SnapJob::SELSTATE;
            break;
        }
        case SnapJob::SELSTATE:
            // v3: 未落定的选区/裁剪浮层/拉伸旋转状态
            sel_.saveState(buf);
            j.phase = SnapJob::NAMES;
            break;
        case SnapJob::NAMES:
            // v4: 图层名称/不透明度
            put32((uint32_t)j.lc);
            for (int l = 0; l < j.lc; l++) {
                const std::string& nm = canvas_.layerName(l);
                put32((uint32_t)nm.size());
                buf.insert(buf.end(), nm.begin(), nm.end());
                put32((uint32_t)std::lround(canvas_.layerOpacity(l) * 1000.f));
            }
            // v5: 撤销/重做栈(后台返回/导出的项目再打开还能继续撤销); 总量上限 24MB
            // v8: 每条记录带 kind/frame; 有 doc 时不持久化历史
            {
                bool hasDoc = false;
                for (const auto& h : undo_) if (h.doc) hasDoc = true;
                for (const auto& h : redo_) if (h.doc) hasDoc = true;
                j.histOk = !hasDoc && (undoBytes_ + redoBytes_) <= 24u * 1024 * 1024;
                j.histRedo = false;
                j.histIdx = 0;
                put32(j.histOk ? (uint32_t)undo_.size() : 0u);
            }
            j.phase = SnapJob::HIST;
            break;
        case SnapJob::HIST: {
            const std::vector<Hist>& hist = j.histRedo ? redo_ : undo_;
            if (j.histOk && j.histIdx < hist.size()) {
                const Hist& h = hist[j.histIdx];
                put32((uint32_t)h.kind);
                put32((uint32_t)h.frame);
                if (h.kind == 2 && h.meta) {   // 图层元数据记录
                    const MetaState& m = *h.meta;
                    put32((uint32_t)m.vis.size());
                    for (size_t l = 0; l < m.vis.size(); l++) {
                        put32(m.vis[l]); put32(m.lock[l]); put32(m.lmode[l]);
                        put32(m.tlGrid[l]); put32(m.tlIndep[l]); put32(m.blend[l]);
                        put32((uint32_t)std::lround(m.opacity[l] * 1000.f));
                        put32((uint32_t)m.tlW[l]); put32((uint32_t)m.tlH[l]);
                        put32((uint32_t)m.tlOX[l]); put32((uint32_t)m.tlOY[l]);
                        put32((uint32_t)m.tlLock[l]); put32(m.tlGridCol[l]);
                        put32((uint32_t)m.names[l].size());
                        buf.insert(buf.end(), m.names[l].begin(), m.names[l].end());
                    }
                    put32(m.m25on ? 1u : 0u); put32((uint32_t)m.m25depth); put32((uint32_t)m.m25dir);
                } else {
                    put32(h.isFill ? 1u : 0u);
                    put32(h.cBefore); put32(h.cAfter);
                    put32((uint32_t)h.fillLayer);
                    put32((uint32_t)h.rec.size());
                    for (const auto& t : h.rec) {
                        put32((uint32_t)t.layer); put32((uint32_t)t.ti);
                        put32((uint32_t)t.kind); put32(t.color);
                        if (t.kind == 1 && t.px.size() >= (size_t)Canvas::TILE_PIX) {
                            const size_t off = buf.size();
                            buf.resize(off + Canvas::TILE_PIX * 4);
                            memcpy(buf.data() + off, t.px.data(), Canvas::TILE_PIX * 4);
                        }
                    }
                    put32((uint32_t)h.spans.size());
                    for (const auto& sp : h.spans) {
                        put32((uint32_t)sp.yy); put32((uint32_t)sp.x0); put32((uint32_t)sp.x1);
                    }
                }
                j.histIdx++;
                if (overBudget()) return false;
            } else if (!j.histRedo) {
                j.histRedo = true;
                j.histIdx = 0;
                put32(j.histOk ? (uint32_t)redo_.size() : 0u);
            } else {
                j.phase = SnapJob::ENDMETA;
            }
            break;
        }
        case SnapJob::ENDMETA:
            // v6: 图层模式/平铺参数(每层9项) + 2.5D 全局状态
            put32((uint32_t)j.lc);
            for (int l = 0; l < j.lc; l++) {
                put32((uint32_t)canvas_.layerMode(l));
                put32((uint32_t)canvas_.layerTileW(l));
                put32((uint32_t)canvas_.layerTileH(l));
                put32((uint32_t)canvas_.layerTileOffX(l));
                put32((uint32_t)canvas_.layerTileOffY(l));
                put32(canvas_.layerShowGrid(l) ? 1u : 0u);
                put32(canvas_.layerGridColor(l));
                put32(canvas_.layerTileIndep(l) ? 1u : 0u);
                put32((uint32_t)canvas_.layerTileLockFrame(l));
            }
            {
                const auto& g = raster::mode25D();
                put32(g.on ? 1u : 0u);
                put32((uint32_t)g.depth);
                put32((uint32_t)g.dir);
            }
            // v7: 每层混合模式(正片叠底/滤色/叠加/...)
            put32((uint32_t)j.lc);
            for (int l = 0; l < j.lc; l++) put32((uint32_t)canvas_.layerBlend(l));
            // 2.5D 视角角(尾段追加, 仍标 v8: 旧版本读到混合模式就停, 忽略尾部字节不会报错)
            {
                const auto& g = raster::mode25D();
                float yv = g.yawDeg;
                uint32_t yb = 0;
                memcpy(&yb, &yv, 4);
                put32(yb);
            }
            j.phase = SnapJob::THUMB;
            break;
        case SnapJob::THUMB: {
            // storeFrameData() 时已经算过当前帧合成缩略图, 直接复用, 不再全画布采样一遍
            bool got = false;
            {
                std::lock_guard<std::mutex> lk(thumbMu_);
                if (!compositeThumb_.empty()) { j.thumb = compositeThumb_; got = true; }
            }
            if (!got) makeCompositeThumb(j.thumb);
            j.phase = SnapJob::DONE;
            return true;
        }
        case SnapJob::DONE:
            return true;
        }
    }
}

void Engine::snapshotComplete(SnapJob& j) {
    lastSavedRev_.store(j.rev);
    lastSavedFrameRev_ = j.frameRev;
    lastSavedSelStateRev_ = j.selRev;
    lastSavedLayerMetaRev_ = j.layerRev;
    std::vector<uint8_t> thumbBytes;
    if (!j.thumb.empty()) {
        thumbBytes.resize(j.thumb.size() * 4);
        memcpy(thumbBytes.data(), j.thumb.data(), thumbBytes.size());
    }
    const std::string path = j.path;
    const std::string tmp = path + ".tmp";
    const bool wait = j.wait;
    std::thread([this, path, tmp, buf = std::move(j.buf), thumbBytes = std::move(thumbBytes), wait]() {
        // zlib 压缩(画布大面积透明, 通常能压到几百分之一); 失败则写原始数据
        uLongf csz = compressBound((uLong)buf.size());
        std::vector<uint8_t> cbuf((size_t)csz);
        std::vector<uint8_t> out;
        if (compress2(cbuf.data(), &csz, buf.data(), (uLong)buf.size(), 1) == Z_OK) {
            out.reserve(12 + (size_t)csz);
            const uint32_t magic = 0x32534E50u;   // 'P N S 2'
            const uint32_t raw = (uint32_t)buf.size();
            auto put = [&](uint32_t v) {
                out.push_back((uint8_t)(v & 255)); out.push_back((uint8_t)((v >> 8) & 255));
                out.push_back((uint8_t)((v >> 16) & 255)); out.push_back((uint8_t)((v >> 24) & 255));
            };
            put(magic); put(raw); put((uint32_t)csz);
            out.insert(out.end(), cbuf.begin(), cbuf.begin() + (size_t)csz);
        } else {
            out = buf;
        }
        bool ok = false;
        FILE* f = fopen(tmp.c_str(), "wb");
        if (f) {
            ok = fwrite(out.data(), 1, out.size(), f) == out.size();
            if (ok) {
                fflush(f);
                const int fd = fileno(f);
                if (fd >= 0) fsync(fd);   // 掉电/立即杀进程: 数据先落盘再 rename
            }
            fclose(f);
            // POSIX rename 覆盖已有文件是原子的; 不要先 remove(中间掉电会丢旧快照)
            if (ok) ok = rename(tmp.c_str(), path.c_str()) == 0;
            if (ok) {
                const size_t slash = path.find_last_of('/');
                const std::string dir = slash == std::string::npos ? "." : path.substr(0, slash);
                const int dfd = open(dir.c_str(), O_RDONLY);
                if (dfd >= 0) { fsync(dfd); close(dfd); }
            }
        }
        if (!ok) lastSavedRev_.store(0);
        if (ok && !thumbBytes.empty()) {
            const std::string thp = path + ".thumb";
            const std::string tht = thp + ".tmp";
            FILE* tf = fopen(tht.c_str(), "wb");
            if (tf) {
                const bool tw = fwrite(thumbBytes.data(), 1, thumbBytes.size(), tf) == thumbBytes.size();
                if (tw) {
                    fflush(tf);
                    const int fd = fileno(tf);
                    if (fd >= 0) fsync(fd);
                }
                fclose(tf);
                if (tw) rename(tht.c_str(), thp.c_str());
                else remove(tht.c_str());
            }
        }
        LOGI("snapshot save %s raw=%zu file=%zu ok=%d", path.c_str(), buf.size(), out.size(), (int)ok);
        if (wait) {
            std::lock_guard<std::mutex> lk(snapMu_);
            snapDone_ = true;
            snapCv_.notify_all();
        }
    }).detach();
}

void Engine::tickSnapshot() {
    if (!snapJob_) return;
    SnapJob& j = *snapJob_;
    // 打包期间又编辑/切帧/改图层: 这一份已经不一致, 直接丢弃; 空闲后会重新请求
    if (canvas_.revision() != j.rev || frameRev_.load() != j.frameRev ||
        selStateRev_ != j.selRev || layerMetaRev_ != j.layerRev) {
        LOGI("snapshot async abort: content changed");
        snapJob_.reset();
        return;
    }
    if (snapshotStep(j, 2u * 1024 * 1024)) {
        snapshotComplete(j);
        snapJob_.reset();
    }
}

void Engine::loadSnapshotNow(const std::string& path) {
    discardOpenStrokeRecord();
    viewAnimActive_ = false;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) { LOGI("snapshot load: no file"); return; }
    fseek(f, 0, SEEK_END);
    const long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> buf;
    if (sz > 0 && sz < (long)(1024ll * 1024 * 1024)) {
        buf.resize((size_t)sz);
        if (fread(buf.data(), 1, buf.size(), f) != buf.size()) buf.clear();
    }
    fclose(f);
    auto rd = [&](size_t& p, uint32_t& v) {
        if (p + 4 > buf.size()) return false;
        v = (uint32_t)buf[p] | ((uint32_t)buf[p + 1] << 8) | ((uint32_t)buf[p + 2] << 16) | ((uint32_t)buf[p + 3] << 24);
        p += 4;
        return true;
    };
    size_t p = 0;
    uint32_t magic = 0, ver = 0, w = 0, h = 0, lc = 0, active = 0, count = 0;
    if (!rd(p, magic)) return;
    if (magic == 0x32534E50u) {   // 压缩快照: 解压后按原始格式解析
        uint32_t raw = 0, csz = 0;
        if (!rd(p, raw) || !rd(p, csz) || p + csz > buf.size() || raw > 512u * 1024 * 1024) {
            LOGI("snapshot load: bad zlib header"); return;
        }
        std::vector<uint8_t> dec(raw);
        uLongf dn = raw;
        if (uncompress(dec.data(), &dn, buf.data() + p, csz) != Z_OK || dn != raw) {
            LOGI("snapshot load: uncompress failed"); return;
        }
        buf.swap(dec);
        p = 0;
        if (!rd(p, magic)) return;
    }
    if (magic != 0x534E5058u) { LOGI("snapshot load: bad magic"); return; }
    if (!rd(p, ver) || (ver < 1 || ver > 8)) { LOGI("snapshot load: bad ver"); return; }
    uint32_t loadedFidx = 0;
    resetMeasureState();   // 换标签/载入项目: 测距浮层不跟随
    if (!rd(p, w) || !rd(p, h) || !rd(p, lc) || !rd(p, active) || !rd(p, count)) return;
    if (count > 8192 || w < 1 || h < 1 || w > 32767 || h > 32767) {
        LOGI("snapshot load: bad size %ux%u", w, h);
        return;
    }
    // 层数是工程自己的属性: 切到层数更少的标签时必须收回到快照的层数,
    // 否则 frameLayerCount_(上一标签/导入工程的最大层数)会把这个标签补出一堆空层。
    if ((int)w != canvas_.width() || (int)h != canvas_.height() ||
        canvas_.layers() != std::max(1, (int)lc)) {
        sel_.clearState();
        canvas_.create((int)w, (int)h, std::max(1, (int)lc));
        display_.resizeCanvas((int)w, (int)h);
        mip_.reset(&canvas_);
    }
    const int targetLc = std::max(1, (int)lc);
    while (canvas_.layers() < targetLc) canvas_.addLayer();
    frameLayerCount_ = targetLc;
    for (int l = 0; l < canvas_.layers(); l++)
        for (int ti = 0; ti < canvas_.tilesX() * canvas_.tilesY(); ti++)
            canvas_.releaseTile(l, ti);
    for (uint32_t i = 0; i < count; i++) {
        uint32_t layer = 0, ti = 0, kind = 0;
        if (!rd(p, layer) || !rd(p, ti) || !rd(p, kind)) break;
        if (kind == 1) {
            uint32_t col = 0;
            if (!rd(p, col)) break;
            canvas_.installTile((int)layer, (int)ti, nullptr, col, true);
        } else {
            if (p + Canvas::TILE_PIX * 4 > buf.size()) break;
            canvas_.installTile((int)layer, (int)ti, (const uint32_t*)(buf.data() + p), 0, false);
            p += Canvas::TILE_PIX * 4;
        }
    }
    canvas_.setActive((int)active);
    if (ver >= 2) {
        uint32_t vis = 0, lck = 0;
        if (!rd(p, vis) || !rd(p, lck)) return;
        for (int l = 0; l < canvas_.layers() && l < 32; l++) {
            canvas_.setLayerVisible(l, (vis >> l) & 1u);
            canvas_.setLayerLocked(l, (lck >> l) & 1u);
        }
        uint32_t fcount = 0, fidx = 0, fps = 12, ls = 0, le = 0;
        if (!rd(p, fcount) || !rd(p, fidx) || !rd(p, fps) || !rd(p, ls) || !rd(p, le)) return;
        loadedFidx = fidx;
        // 无论快照里有没有帧, 都要先把帧状态复位, 否则切到空白标签会继承上一个标签的帧数
        frameData_.clear();
        frame_ = 0; frameIdx_.store(0); frameCount_.store(1);
        playing_.store(0); loopStart_.store(0); loopEnd_.store(-1);
        if (fcount > 0 && fcount <= 512) {
            std::vector<std::vector<uint8_t>> fr;
            fr.reserve(fcount);
            bool okFrames = true;
            for (uint32_t i = 0; i < fcount; i++) {
                uint32_t len = 0;
                if (!rd(p, len) || len > 512u * 1024 * 1024 || p + len > buf.size()) { okFrames = false; break; }
                fr.emplace_back(buf.begin() + p, buf.begin() + p + len);
                p += len;
            }
            if (okFrames) {
                frameData_.swap(fr);
                frame_ = std::max(0, std::min((int)fidx, (int)frameData_.size() - 1));
                frameIdx_.store(frame_);
                frameCount_.store((int)frameData_.size());
                playFps_.store(std::max(1, std::min((int)fps, 60)));
                loopStart_.store(std::max(0, std::min((int)ls, (int)frameData_.size() - 1)));
                const int leSigned = (int)le;
                loopEnd_.store(leSigned < 0 ? -1
                    : std::max(0, std::min(leSigned, (int)frameData_.size() - 1)));
            }
        }
        uint32_t lw = 0, lh = 0;
        if (rd(p, lw) && rd(p, lh) && lw > 0 && lh > 0 &&
            (long long)lw * lh <= 4ll * 1024 * 1024 && p + (size_t)lw * lh * 4 <= buf.size()) {
            // 剪贴板是全局的: 读档不覆盖当前"上次裁剪"内容
            p += (size_t)lw * lh * 4;
        }
    }
    bool selOk = true;
    if (ver >= 3) {
        // 后台回来继续之前的裁剪/选区/拉伸旋转(快照里带未落定浮层)
        if (!sel_.loadState(buf.data(), buf.size(), p)) {
            LOGI("snapshot load: selection state bad, skip");
            sel_.clearState();
            selOk = false;
        }
    } else {
        sel_.clearState();
    }
    // 图层名称/不透明度: 旧快照回落默认名
    for (int l = 0; l < canvas_.layers(); l++) {
        canvas_.setLayerName(l, "图层 " + std::to_string(l + 1));
        canvas_.setLayerOpacity(l, 1.f);
    }
    if (ver >= 4 && selOk) {
        uint32_t nameCount = 0;
        if (rd(p, nameCount) && nameCount <= 32) {
            for (uint32_t l = 0; l < nameCount && p + 4 <= buf.size(); l++) {
                uint32_t nlen = 0;
                if (!rd(p, nlen) || nlen > 512 || p + nlen + 4 > buf.size()) break;
                std::string nm((const char*)buf.data() + p, nlen);
                p += nlen;
                uint32_t op = 0;
                if (!rd(p, op)) break;
                if ((int)l < canvas_.layers()) {
                    canvas_.setLayerName((int)l, nm);
                    canvas_.setLayerOpacity((int)l, (float)(op > 1000 ? 1000 : op) / 1000.f);
                }
            }
        }
    }
    // v5: 撤销/重做栈
    std::vector<Hist> histUndo, histRedo;
    if (ver >= 5 && selOk) {
        auto rdHist = [&](std::vector<Hist>& out) -> bool {
            uint32_t n = 0;
            if (!rd(p, n) || n > 4096) return false;
            out.reserve(n);
            for (uint32_t i = 0; i < n; i++) {
                Hist h;
                if (ver >= 8) {
                    uint32_t hk = 0, hf = 0;
                    if (!rd(p, hk) || !rd(p, hf)) return false;
                    h.kind = (int)hk; h.frame = (int)hf;
                    if (hk == 2) {   // 图层元数据记录
                        uint32_t mn = 0;
                        if (!rd(p, mn) || mn > 64) return false;
                        auto m = std::make_unique<MetaState>();
                        m->vis.resize(mn); m->lock.resize(mn); m->lmode.resize(mn);
                        m->tlGrid.resize(mn); m->tlIndep.resize(mn); m->blend.resize(mn);
                        m->opacity.resize(mn); m->tlW.resize(mn); m->tlH.resize(mn);
                        m->tlOX.resize(mn); m->tlOY.resize(mn); m->tlLock.resize(mn);
                        m->tlGridCol.resize(mn); m->names.resize(mn);
                        for (uint32_t l = 0; l < mn; l++) {
                            uint32_t vis = 0, lk = 0, lm = 0, tg = 0, ti2 = 0, bl = 0;
                            uint32_t op = 0, tw = 16, th = 16, ox = 0, oy = 0, lf = 0, gc = 0;
                            if (!rd(p, vis) || !rd(p, lk) || !rd(p, lm) || !rd(p, tg) ||
                                !rd(p, ti2) || !rd(p, bl) || !rd(p, op) || !rd(p, tw) ||
                                !rd(p, th) || !rd(p, ox) || !rd(p, oy) || !rd(p, lf) || !rd(p, gc))
                                return false;
                            uint32_t nlen = 0;
                            if (!rd(p, nlen) || nlen > 512 || p + nlen > buf.size()) return false;
                            m->vis[l] = (uint8_t)vis; m->lock[l] = (uint8_t)lk;
                            m->lmode[l] = (uint8_t)lm; m->tlGrid[l] = (uint8_t)tg;
                            m->tlIndep[l] = (uint8_t)ti2; m->blend[l] = (uint8_t)bl;
                            m->opacity[l] = (float)std::min(1000u, op) / 1000.f;
                            m->tlW[l] = (int)tw; m->tlH[l] = (int)th;
                            m->tlOX[l] = (int)ox; m->tlOY[l] = (int)oy;
                            m->tlLock[l] = (int)lf; m->tlGridCol[l] = gc;
                            m->names[l].assign((const char*)buf.data() + p, nlen);
                            p += nlen;
                        }
                        uint32_t on = 0, dep = 5, dir = 0;
                        if (!rd(p, on) || !rd(p, dep) || !rd(p, dir)) return false;
                        m->m25on = on != 0; m->m25depth = (int)dep; m->m25dir = (int)dir;
                        h.meta = std::move(m);
                        h.bytes = histBytes(h);
                        out.push_back(std::move(h));
                        continue;
                    }
                    if (hk != 0 && hk != 1) return false;   // doc 记录不持久化
                } else {
                    h.frame = (int)loadedFidx;
                }
                uint32_t fill = 0, cb = 0, ca = 0, fl = 0;
                if (!rd(p, fill) || !rd(p, cb) || !rd(p, ca) || !rd(p, fl)) return false;
                h.isFill = fill != 0; h.cBefore = cb; h.cAfter = ca; h.fillLayer = (int)fl;
                uint32_t rn = 0;
                if (!rd(p, rn) || rn > 200000) return false;
                h.rec.reserve(rn);
                for (uint32_t k = 0; k < rn; k++) {
                    uint32_t ly = 0, ti = 0, kind = 0, col = 0;
                    if (!rd(p, ly) || !rd(p, ti) || !rd(p, kind) || !rd(p, col)) return false;
                    Canvas::TileSnap t;
                    t.layer = (int)ly; t.ti = (int)ti; t.kind = (uint8_t)kind; t.color = col;
                    if (kind == 1) {
                        if (p + (size_t)Canvas::TILE_PIX * 4 > buf.size()) return false;
                        t.px.assign((const uint32_t*)(buf.data() + p),
                                    (const uint32_t*)(buf.data() + p) + Canvas::TILE_PIX);
                        p += (size_t)Canvas::TILE_PIX * 4;
                    }
                    h.rec.push_back(std::move(t));
                }
                uint32_t sn = 0;
                if (!rd(p, sn) || sn > 2000000) return false;
                h.spans.reserve(sn);
                for (uint32_t k = 0; k < sn; k++) {
                    uint32_t yy = 0, x0 = 0, x1 = 0;
                    if (!rd(p, yy) || !rd(p, x0) || !rd(p, x1)) return false;
                    h.spans.push_back({(int)yy, (int)x0, (int)x1});
                }
                if (ver < 8 && h.isFill) h.kind = 1;
                h.bytes = recBytes(h.rec) + h.spans.size() * sizeof(Canvas::Span);
                out.push_back(std::move(h));
            }
            return true;
        };
        if (!rdHist(histUndo) || !rdHist(histRedo)) {
            histUndo.clear(); histRedo.clear();
        }
        size_t tb = 0;
        for (auto& h : histUndo) tb += h.bytes;
        for (auto& h : histRedo) tb += h.bytes;
        if (tb > 48u * 1024 * 1024) { histUndo.clear(); histRedo.clear(); }   // 损坏文件防守
    }
    // v6: 图层模式/平铺参数 + 2.5D 全局状态(旧存档无此项 → 全部回到正常/关闭)
    if (ver >= 6) {
        uint32_t n6 = 0;
        if (!rd(p, n6)) return;
        for (uint32_t i = 0; i < n6 && i < (uint32_t)canvas_.layers(); i++) {
            uint32_t md = 0, tw = 16, th = 16, ox = 0, oy = 0, sg = 0, gc = 0x66FFFFFFu, idp = 0, lk = 0;
            if (!rd(p, md) || !rd(p, tw) || !rd(p, th) || !rd(p, ox) || !rd(p, oy) ||
                !rd(p, sg) || !rd(p, gc) || !rd(p, idp) || !rd(p, lk)) return;
            canvas_.setLayerMode((int)i, (int)md);
            canvas_.setLayerTile((int)i, (int)tw, (int)th, (int)ox, (int)oy,
                                 sg != 0, gc, idp != 0, (int)lk);
        }
        uint32_t on = 0, dep = 5, dir = 0;
        if (!rd(p, on) || !rd(p, dep) || !rd(p, dir)) return;
        auto& g = raster::mode25D();
        g.on = on != 0;
        g.depth = std::max(1, std::min(20, (int)dep));
        g.dir = (int)dir & 3;
        raster::setYaw25D(0);          // 视角角不落盘, 读档回正面
        display_.setRotation25(0);
        m25OnA_.store(g.on, std::memory_order_relaxed);
        m25DepthA_.store(g.depth, std::memory_order_relaxed);
        m25DirA_.store(g.dir, std::memory_order_relaxed);
    } else {
        for (int l = 0; l < canvas_.layers(); l++) {
            canvas_.setLayerMode(l, 0);
            canvas_.setLayerTile(l, 16, 16, 0, 0, false, 0x66FFFFFFu, false, 0);
        }
        auto& g = raster::mode25D();
        g.on = false; g.depth = 5; g.dir = 0;
        raster::setYaw25D(0);
        display_.setRotation25(0);
        m25OnA_.store(false, std::memory_order_relaxed);
        m25DepthA_.store(5, std::memory_order_relaxed);
        m25DirA_.store(0, std::memory_order_relaxed);
    }
    // v7: 每层混合模式(旧存档默认正常)
    if (ver >= 7) {
        uint32_t n7 = 0;
        if (!rd(p, n7)) return;
        for (uint32_t i = 0; i < n7 && i < (uint32_t)canvas_.layers(); i++) {
            uint32_t bm = 0;
            if (!rd(p, bm)) return;
            canvas_.setLayerBlend((int)i, (int)bm);
        }
    } else {
        for (int l = 0; l < canvas_.layers(); l++) canvas_.setLayerBlend(l, 0);
    }
    // 2.5D 视角角(尾段): 有就读, 老存档没有则回正面。视角属于"查看状态", 2.5D 关闭时忽略。
    {
        float yv = 0.f;
        if (p + 4 <= buf.size()) {
            uint32_t yb = 0;
            if (rd(p, yb)) memcpy(&yv, &yb, 4);
        }
        if (!(yv > -360.f && yv < 360.f)) yv = 0.f;
        const auto& g = raster::mode25D();
        const float use = g.on ? yv : 0.f;
        raster::setYaw25D(use);
        display_.setRotation25(use);
    }
    if (ver >= 2 && (long long)canvas_.width() * canvas_.height() <= 4ll * 1024 * 1024) {
        std::lock_guard<std::mutex> lk(thumbMu_);
        frameThumbs_.assign(frameData_.empty() ? 1 : frameData_.size(),
            std::vector<std::vector<uint32_t>>((size_t)canvas_.layers(),
                std::vector<uint32_t>((size_t)THUMB * THUMB, 0u)));
        if (frameData_.empty()) {
            for (int l = 0; l < canvas_.layers(); l++)
                makeThumb(frame_, l, frameThumbs_[0][(size_t)l]);
        } else {
            std::vector<uint32_t> th((size_t)THUMB * THUMB, 0u);
            for (size_t fi = 0; fi < frameData_.size(); fi++) {
                for (int l = 0; l < canvas_.layers(); l++) {
                    if (layerThumb((int)fi, l, th.data(), (int)th.size()))
                        frameThumbs_[fi][(size_t)l] = th;
                }
            }
        }
    }
    {
        std::vector<uint32_t> comp;
        makeCompositeThumb(comp);
        std::lock_guard<std::mutex> lk(thumbMu_);
        compositeThumb_.swap(comp);
    }
    // 切换标签/读档后小地图必须换成新画布(旧实现只重建了时间轴缩略图)
    if (minimapActive_.load(std::memory_order_relaxed)) refreshMinimapThumb();
    thumbRev_.fetch_add(1, std::memory_order_relaxed);
    lastSavedFrameRev_ = frameRev_.load();
    onionGen_.fetch_add(1);
    canvas_.markDirtyRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
    clearHistory();
    if (!histUndo.empty() || !histRedo.empty()) {
        undo_ = std::move(histUndo);
        redo_ = std::move(histRedo);
        for (const auto& h : undo_) undoBytes_ += h.bytes;
        for (const auto& h : redo_) redoBytes_ += h.bytes;
        LOGI("snapshot history restored undo=%zu redo=%zu", undo_.size(), redo_.size());
    }
    lastSavedRev_.store(canvas_.revision());
    lastSavedSelStateRev_ = selStateRev_;
    lastSavedLayerMetaRev_ = layerMetaRev_;
    refreshLayerMetaCache();
    refreshLayerPropsCache();
    layerCount_.store(canvas_.layers());
    activeLayer_.store(canvas_.active());
    LOGI("snapshot load ok: %u tiles %ux%u layers=%u", count, w, h, lc);
}

void Engine::requestCopySel() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({34, 0.0f, 0.0f, 0.0f});
}
void Engine::requestDuplicateSel() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({54, 0.0f, 0.0f, 0.0f});
}
void Engine::rewriteFramesDropLayer(int idx) {
    auto put32 = [](std::vector<uint8_t>& v, uint32_t x) {
        v.push_back((uint8_t)(x & 255)); v.push_back((uint8_t)((x >> 8) & 255));
        v.push_back((uint8_t)((x >> 16) & 255)); v.push_back((uint8_t)((x >> 24) & 255));
    };
    for (int fi = 0; fi < (int)frameData_.size(); fi++) {
        if (fi == frame_) continue;
        const std::vector<uint8_t>& d = frameData_[(size_t)fi];
        if (d.size() < 12) continue;
        size_t p = 0;
        auto rd = [&](uint32_t& v) {
            if (p + 4 > d.size()) return false;
            v = (uint32_t)d[p] | ((uint32_t)d[p + 1] << 8) | ((uint32_t)d[p + 2] << 16) | ((uint32_t)d[p + 3] << 24);
            p += 4;
            return true;
        };
        uint32_t lc = 0, active = 0, count = 0;
        if (!rd(lc) || !rd(active) || !rd(count)) continue;
        std::vector<uint8_t> payload;
        uint32_t kept = 0;
        for (uint32_t i = 0; i < count; i++) {
            uint32_t layer = 0, ti = 0, kind = 0;
            if (!rd(layer) || !rd(ti) || !rd(kind)) break;
            if (kind == 1) {
                uint32_t col = 0;
                if (!rd(col)) break;
                if ((int)layer == idx) continue;
                const uint32_t nl = (int)layer > idx ? layer - 1 : layer;
                put32(payload, nl); put32(payload, ti); put32(payload, 1u); put32(payload, col);
            } else {
                if (p + Canvas::TILE_PIX * 4 > d.size()) break;
                if ((int)layer == idx) { p += Canvas::TILE_PIX * 4; continue; }
                const uint32_t nl = (int)layer > idx ? layer - 1 : layer;
                put32(payload, nl); put32(payload, ti); put32(payload, 0u);
                payload.insert(payload.end(), d.begin() + p, d.begin() + p + Canvas::TILE_PIX * 4);
                p += Canvas::TILE_PIX * 4;
            }
            kept++;
        }
        std::vector<uint8_t> out;
        put32(out, lc > 0 ? lc - 1 : 0);
        put32(out, active > (uint32_t)idx ? active - 1 : (active == (uint32_t)idx ? 0u : active));
        put32(out, kept);
        out.insert(out.end(), payload.begin(), payload.end());
        frameData_[(size_t)fi] = std::move(out);
    }
}

namespace {
}

void Engine::makeThumb(int frame, int layer, std::vector<uint32_t>& out) {
    out.assign((size_t)THUMB * THUMB, 0u);
    if (!canvas_.layers()) return;
    if (layer < 0 || layer >= canvas_.layers()) return;
    const int w = canvas_.width(), h = canvas_.height();
    for (int ty = 0; ty < THUMB; ty++) {
        const int sy0 = (int)((long long)ty * h / THUMB);
        int sy1 = (int)((long long)(ty + 1) * h / THUMB);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        const int syStep = std::max(1, (sy1 - sy0 + 5) / 6);
        for (int tx = 0; tx < THUMB; tx++) {
            const int sx0 = (int)((long long)tx * w / THUMB);
            int sx1 = (int)((long long)(tx + 1) * w / THUMB);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            const int sxStep = std::max(1, (sx1 - sx0 + 5) / 6);
            // 时间轴矩阵缩略图: 只取指定图层(旧 BuildTlThumb984 的层缩略图语义)
            uint32_t best = 0;
            int bestA = 0;
            for (int yy = sy0; yy < sy1; yy += syStep) {
                for (int xx = sx0; xx < sx1; xx += sxStep) {
                    const uint32_t v = canvas_.getPixelL(layer, xx, yy);
                    const int a = (int)(v >> 24);
                    if (a > bestA) { bestA = a; best = v; }
                }
            }
            out[(size_t)ty * THUMB + tx] = best;
        }
    }
}

// 当前帧合成缩略图(设置面板预览/小地图兜底用)
void Engine::makeCompositeThumb(std::vector<uint32_t>& out) {
    makeCompositeThumbN(THUMB, out);
}

// n×n 可见层合成缩略图: 每个格子内多点采样后做「按 alpha 加权平均」(盒平均),
// 不再取"最大 alpha 的那一个点" —— 48² 时细线只剩零星点、放大预览全是块。
void Engine::makeCompositeThumbN(int n, std::vector<uint32_t>& out) {
    n = std::max(8, std::min(n, 512));
    out.assign((size_t)n * n, 0u);
    if (!canvas_.layers()) return;
    const int w = canvas_.width(), h = canvas_.height();
    const int nl = canvas_.layers();
    for (int ty = 0; ty < n; ty++) {
        const int sy0 = (int)((long long)ty * h / n);
        int sy1 = (int)((long long)(ty + 1) * h / n);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        const int syStep = std::max(1, (sy1 - sy0 + 7) / 8);
        for (int tx = 0; tx < n; tx++) {
            const int sx0 = (int)((long long)tx * w / n);
            int sx1 = (int)((long long)(tx + 1) * w / n);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            const int sxStep = std::max(1, (sx1 - sx0 + 7) / 8);
            double sa = 0, sr = 0, sg = 0, sb = 0;
            int cnt = 0;
            for (int yy = sy0; yy < sy1; yy += syStep) {
                for (int xx = sx0; xx < sx1; xx += sxStep) {
                    cnt++;
                    uint32_t acc = 0;
                    for (int l = 0; l < nl; l++) {
                        if (!canvas_.layerVisible(l)) continue;
                        const uint32_t v = raster::layerPixel(canvas_, l, xx, yy);
                        if (v) raster::overPixel(acc, v);
                    }
                    const uint32_t a = acc >> 24;
                    if (!a) continue;
                    sa += a;
                    sr += (double)(acc & 255u) * a;
                    sg += (double)((acc >> 8) & 255u) * a;
                    sb += (double)((acc >> 16) & 255u) * a;
                }
            }
            if (sa <= 0 || cnt <= 0) continue;
            const uint32_t A = (uint32_t)std::lround(sa / cnt);
            const uint32_t R = (uint32_t)std::lround(sr / sa);
            const uint32_t G = (uint32_t)std::lround(sg / sa);
            const uint32_t B = (uint32_t)std::lround(sb / sa);
            out[(size_t)ty * n + tx] = (A << 24) | (B << 16) | (G << 8) | R;
        }
    }
}

void Engine::makeThumbFromBlob(const std::vector<uint8_t>& blob, std::vector<uint32_t>& out) {
    out.assign((size_t)THUMB * THUMB, 0u);
    const int w = canvas_.width(), h = canvas_.height();
    if ((long long)w * h > 4ll * 1024 * 1024) return;
    std::vector<uint32_t> comp;
    compositeFrameBlob(blob, comp, w, h, canvas_);
    for (int ty = 0; ty < THUMB; ty++) {
        const int sy0 = (int)((long long)ty * h / THUMB);
        int sy1 = (int)((long long)(ty + 1) * h / THUMB);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        const int syStep = std::max(1, (sy1 - sy0 + 5) / 6);
        for (int tx = 0; tx < THUMB; tx++) {
            const int sx0 = (int)((long long)tx * w / THUMB);
            int sx1 = (int)((long long)(tx + 1) * w / THUMB);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            const int sxStep = std::max(1, (sx1 - sx0 + 5) / 6);
            uint32_t best = 0;
            int bestA = 0;
            for (int yy = sy0; yy < sy1; yy += syStep)
                for (int xx = sx0; xx < sx1; xx += sxStep) {
                    const uint32_t c = comp[(size_t)yy * w + xx];
                    const int a = (int)(c >> 24);
                    if (a > bestA) { bestA = a; best = c; }
                }
            out[(size_t)ty * THUMB + tx] = best;
        }
    }
}

void Engine::minimapViewportNormalized(float* out) {
    if (!out) return;
    int sx0 = 0, sy0 = 0, sx1 = 0, sy1 = 0;
    display_.canvasRectToScreen(0, 0, std::max(0, canvas_.width() - 1),
                                std::max(0, canvas_.height() - 1), &sx0, &sy0, &sx1, &sy1);
    const float cw = (float)(sx1 - sx0);
    const float ch = (float)(sy1 - sy0);
    if (cw <= 0.f || ch <= 0.f) {
        out[0] = 0.f; out[1] = 0.f; out[2] = 1.f; out[3] = 1.f;
        return;
    }
    const float vw = (float)display_.viewW(), vh = (float)display_.viewH();
    const float x0 = (0.f - sx0) / cw;
    const float y0 = (0.f - sy0) / ch;
    const float x1 = (vw - sx0) / cw;
    const float y1 = (vh - sy0) / ch;
    out[0] = std::max(0.f, std::min(1.f, x0));
    out[1] = std::max(0.f, std::min(1.f, y0));
    out[2] = std::max(0.f, std::min(1.f, x1 - x0));
    out[3] = std::max(0.f, std::min(1.f, y1 - y0));
}

void Engine::refreshAllFrameThumbs() {
    const int F = std::max(1, (int)frameData_.size());
    const int N = canvas_.layers();
    if (N <= 0) return;
    // 极多格子(如 512 帧×32 层)不一次全建, 只刷当前帧, 其余点开时间轴再按需
    if ((long long)F * N > 2048) {
        if ((int)frameThumbs_.size() <= frame_) frameThumbs_.resize((size_t)frame_ + 1);
        auto& ft = frameThumbs_[(size_t)frame_];
        ft.assign((size_t)N, std::vector<uint32_t>((size_t)THUMB * THUMB, 0u));
        for (int l = 0; l < N; l++) makeThumb(frame_, l, ft[(size_t)l]);
    } else {
        frameThumbs_.assign((size_t)F, std::vector<std::vector<uint32_t>>(
            (size_t)N, std::vector<uint32_t>((size_t)THUMB * THUMB, 0u)));
        for (int f = 0; f < F; f++)
            for (int l = 0; l < N; l++)
                layerThumb(f, l, frameThumbs_[(size_t)f][(size_t)l].data(), THUMB * THUMB);
    }
    {
        std::vector<uint32_t> comp;
        makeCompositeThumb(comp);
        std::lock_guard<std::mutex> lk(thumbMu_);
        compositeThumb_.swap(comp);
    }
    if (minimapActive_.load(std::memory_order_relaxed)) refreshMinimapThumb();
    thumbRev_.fetch_add(1, std::memory_order_relaxed);
}

void Engine::refreshFrameThumbs() {
    if (frame_ < 0) return;
    // 帧数/层数变化(导入、加删帧、加删层)后不能只刷当前帧: 时间轴其它帧格会全空
    const int wantF = std::max(1, (int)frameData_.size());
    const int wantN = canvas_.layers();
    if ((int)frameThumbs_.size() != wantF ||
        (frame_ < (int)frameThumbs_.size() &&
         (int)frameThumbs_[(size_t)frame_].size() != wantN)) {
        refreshAllFrameThumbs();
        return;
    }
    if ((int)frameThumbs_.size() <= frame_) frameThumbs_.resize((size_t)frame_ + 1);
    auto& ft = frameThumbs_[(size_t)frame_];
    ft.resize((size_t)canvas_.layers());
    for (int l = 0; l < canvas_.layers(); l++) makeThumb(frame_, l, ft[(size_t)l]);
    {
        std::vector<uint32_t> comp;
        makeCompositeThumb(comp);
        std::lock_guard<std::mutex> lk(thumbMu_);
        compositeThumb_.swap(comp);
    }
    if (minimapActive_.load(std::memory_order_relaxed)) refreshMinimapThumb();
    thumbRev_.fetch_add(1, std::memory_order_relaxed);
}

// 小地图缩略图: 全可见层合成, 192×192 采样(块内最多 4×4 个采样点, 大画布也不爆)
// 2.5D 开启时输出"旋转后的画布 bbox 铺满格子"的缩略图(含层错位), 与主画布一致;
// 红框分数基于同一个旋转 bbox, 所以不用改 UI 也保持对齐。
void Engine::refreshMinimapThumb() {
    std::vector<uint32_t> tmp((size_t)MMTHUMB * MMTHUMB, 0u);
    const raster::Mode25D& g25 = raster::mode25D();
    const bool use25 = g25.on && (g25.depth > 0 || std::fabs(g25.yawDeg) > 0.01f);
    if (canvas_.layers() > 0) {
        const int w = canvas_.width(), h = canvas_.height();
        if (use25) {
            const float rad = g25.yawDeg * 3.14159265358979f / 180.f;
            const float rc = std::cos(rad), rs = std::sin(rad);
            const float bbox = std::fabs(rc) + std::fabs(rs);   // 旋转后 bbox 占归一化空间的边长
            for (int ty = 0; ty < MMTHUMB; ty++) {
                for (int tx = 0; tx < MMTHUMB; tx++) {
                    // 每格 1 个中心采样: 2.5D 拖动时 30Hz 重建, 控制耗时(~0.5ms/6层)
                    const float nx = (tx + 0.5f) / MMTHUMB - 0.5f;
                    const float ny = (ty + 0.5f) / MMTHUMB - 0.5f;
                    const float u = nx * bbox, v = ny * bbox;
                    // 逆旋转(与 GlDisplay::screenToCanvasF 同向): 从旋转后的 bbox 回到画布
                    const float px = rc * u + rs * v;
                    const float py = -rs * u + rc * v;
                    const int ix = (int)std::floor((px + 0.5f) * w);
                    const int iy = (int)std::floor((py + 0.5f) * h);
                    if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
                    tmp[(size_t)ty * MMTHUMB + tx] =
                        raster::composeLayersPixelEx(canvas_, ix, iy, true, true, false);
                }
            }
        } else {
            for (int ty = 0; ty < MMTHUMB; ty++) {
                const int sy0 = (int)((long long)ty * h / MMTHUMB);
                int sy1 = (int)((long long)(ty + 1) * h / MMTHUMB);
                if (sy1 <= sy0) sy1 = sy0 + 1;
                const int syStep = std::max(1, (sy1 - sy0 + 3) / 4);
                for (int tx = 0; tx < MMTHUMB; tx++) {
                    const int sx0 = (int)((long long)tx * w / MMTHUMB);
                    int sx1 = (int)((long long)(tx + 1) * w / MMTHUMB);
                    if (sx1 <= sx0) sx1 = sx0 + 1;
                    const int sxStep = std::max(1, (sx1 - sx0 + 3) / 4);
                    uint32_t best = 0;
                    int bestA = 0;
                    for (int yy = sy0; yy < sy1; yy += syStep) {
                        for (int xx = sx0; xx < sx1; xx += sxStep) {
                            uint32_t acc = 0;
                            const int nl = canvas_.layers();
                            for (int l = 0; l < nl; l++) {
                                if (!canvas_.layerVisible(l)) continue;
                                const uint32_t v = raster::layerPixel(canvas_, l, xx, yy);
                                if (v) raster::overPixel(acc, v);
                            }
                            const int a = (int)(acc >> 24);
                            if (a > bestA) { bestA = a; best = acc; }
                        }
                    }
                    tmp[(size_t)ty * MMTHUMB + tx] = best;
                }
            }
        }
    }
    std::lock_guard<std::mutex> lk(thumbMu_);
    minimapThumb_.swap(tmp);
}

// 2.5D: 视口四角映射到"旋转后小地图 bbox"的归一化坐标(8 float: x0,y0,x1,y1,...)
// 小地图红线按这四点画四边形, 才能跟着旋转方向走, 而不是一个轴对齐外接框。
void Engine::minimapViewportQuad(float* out) {
    if (!out) return;
    const float vw = (float)display_.viewW(), vh = (float)display_.viewH();
    const float w = (float)std::max(1, canvas_.width());
    const float h = (float)std::max(1, canvas_.height());
    const raster::Mode25D& g25 = raster::mode25D();
    const bool use25 = g25.on && (g25.depth > 0 || std::fabs(g25.yawDeg) > 0.01f);
    float rc = 1.f, rs = 0.f, bbox = 1.f;
    if (use25) {
        const float rad = g25.yawDeg * 3.14159265358979f / 180.f;
        rc = std::cos(rad); rs = std::sin(rad);
        bbox = std::fabs(rc) + std::fabs(rs);
    }
    const float sx[4] = {0.f, vw, vw, 0.f};
    const float sy[4] = {0.f, 0.f, vh, vh};
    for (int i = 0; i < 4; i++) {
        float cx = 0.f, cy = 0.f;
        display_.screenToCanvasF(sx[i], sy[i], &cx, &cy);
        float nx, ny;
        if (!use25) {
            nx = cx / w;
            ny = cy / h;
        } else {
            const float px = cx / w - 0.5f, py = cy / h - 0.5f;
            const float u = rc * px - rs * py;
            const float v = rs * px + rc * py;
            nx = u / bbox + 0.5f;
            ny = v / bbox + 0.5f;
        }
        out[i * 2] = nx;
        out[i * 2 + 1] = ny;
    }
}

// 小地图点击 → 画布坐标(2.5D 时做同样的逆旋转; UI 拖动/点击都走这里)
void Engine::minimapPointToCanvas(float nx, float ny, float* out) {
    if (!out) return;
    const float w = (float)std::max(1, canvas_.width());
    const float h = (float)std::max(1, canvas_.height());
    const raster::Mode25D& g25 = raster::mode25D();
    const bool use25 = g25.on && (g25.depth > 0 || std::fabs(g25.yawDeg) > 0.01f);
    if (!use25) {
        out[0] = nx * w;
        out[1] = ny * h;
        return;
    }
    const float rad = g25.yawDeg * 3.14159265358979f / 180.f;
    const float rc = std::cos(rad), rs = std::sin(rad);
    const float bbox = std::fabs(rc) + std::fabs(rs);
    const float u = (nx - 0.5f) * bbox, v = (ny - 0.5f) * bbox;
    const float px = rc * u + rs * v;
    const float py = -rs * u + rc * v;
    out[0] = (px + 0.5f) * w;
    out[1] = (py + 0.5f) * h;
}

bool Engine::minimapThumb(uint32_t* out, int n) {
    if (!out || n < MMTHUMB * MMTHUMB) return false;
    std::lock_guard<std::mutex> lk(thumbMu_);
    if ((int)minimapThumb_.size() < MMTHUMB * MMTHUMB) return false;
    memcpy(out, minimapThumb_.data(), sizeof(uint32_t) * (size_t)(MMTHUMB * MMTHUMB));
    return true;
}

// 时间轴矩阵: 单图层缩略图(48×48), 当前帧直接读画布, 其它帧解析帧 blob
bool Engine::layerThumb(int frame, int layer, uint32_t* out, int n) {
    if (!out || n < THUMB * THUMB) return false;
    if (layer < 0 || layer >= canvas_.layers()) return false;
    const int w = canvas_.width(), h = canvas_.height();
    const int txN = canvas_.tilesX(), tyN = canvas_.tilesY();
    struct TileRef { const uint8_t* px = nullptr; uint32_t col = 0; bool solid = false; bool has = false; };
    std::vector<TileRef> refs;
    const bool live = (frame == frame_);
    const std::vector<uint8_t>* blob = nullptr;
    if (!live) {
        if (frame < 0 || frame >= (int)frameData_.size()) return false;
        blob = &frameData_[(size_t)frame];
        if (blob->size() < 12) return false;
        refs.resize((size_t)txN * tyN);
        size_t p = 0;
        auto rd = [&](uint32_t& v) {
            if (p + 4 > blob->size()) return false;
            v = (uint32_t)(*blob)[p] | ((uint32_t)(*blob)[p + 1] << 8) |
                ((uint32_t)(*blob)[p + 2] << 16) | ((uint32_t)(*blob)[p + 3] << 24);
            p += 4;
            return true;
        };
        uint32_t lc = 0, act = 0, count = 0;
        if (!rd(lc) || !rd(act) || !rd(count)) return false;
        for (uint32_t i = 0; i < count; i++) {
            uint32_t ly = 0, ti = 0, kind = 0;
            if (!rd(ly) || !rd(ti) || !rd(kind)) break;
            if ((int)ti < 0 || (int)ti >= txN * tyN) break;
            if (kind == 1) {
                uint32_t col = 0;
                if (!rd(col)) break;
                if ((int)ly == layer) refs[(size_t)ti] = TileRef{nullptr, col, true, true};
            } else {
                if (p + Canvas::TILE_PIX * 4 > blob->size()) break;
                const uint8_t* px = blob->data() + p;
                p += Canvas::TILE_PIX * 4;
                if ((int)ly == layer) refs[(size_t)ti] = TileRef{px, 0, false, true};
            }
        }
    }
    auto sample = [&](int x, int y) -> uint32_t {
        if (live) return canvas_.getPixelL(layer, x, y);
        const int ti = (y >> Canvas::TILE_SHIFT) * txN + (x >> Canvas::TILE_SHIFT);
        if (ti < 0 || ti >= (int)refs.size()) return 0;
        const TileRef& r = refs[(size_t)ti];
        if (!r.has) return 0;
        if (r.solid) return r.col;
        const int lx = x & (Canvas::TILE - 1), ly = y & (Canvas::TILE - 1);
        return ((const uint32_t*)r.px)[((size_t)ly << Canvas::TILE_SHIFT) + lx];
    };
    for (int ty = 0; ty < THUMB; ty++) {
        const int sy0 = (int)((long long)ty * h / THUMB);
        int sy1 = (int)((long long)(ty + 1) * h / THUMB);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        const int syStep = std::max(1, (sy1 - sy0 + 5) / 6);
        for (int tx = 0; tx < THUMB; tx++) {
            const int sx0 = (int)((long long)tx * w / THUMB);
            int sx1 = (int)((long long)(tx + 1) * w / THUMB);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            const int sxStep = std::max(1, (sx1 - sx0 + 5) / 6);
            uint32_t best = 0;
            int bestA = 0;
            for (int yy = sy0; yy < sy1; yy += syStep) {
                for (int xx = sx0; xx < sx1; xx += sxStep) {
                    const uint32_t v = sample(xx, yy);
                    const int a = (int)(v >> 24);
                    if (a > bestA) { bestA = a; best = v; }
                }
            }
            out[(size_t)ty * THUMB + tx] = best;
        }
    }
    return true;
}

bool Engine::compositeThumb(uint32_t* out, int n) {
    if (!out || n < THUMB * THUMB) return false;
    std::lock_guard<std::mutex> lk(thumbMu_);
    if ((int)compositeThumb_.size() < THUMB * THUMB) return false;
    memcpy(out, compositeThumb_.data(), sizeof(uint32_t) * (size_t)(THUMB * THUMB));
    return true;
}

bool Engine::frameThumb(int frame, int layer, uint32_t* out, int n) {
    if (!out || n < THUMB * THUMB) return false;
    std::lock_guard<std::mutex> lk(thumbMu_);   // UI 线程只读缓存, 不在 UI 线程碰 canvas
    if (frame < 0 || frame >= (int)frameThumbs_.size()) return false;
    auto& ft = frameThumbs_[(size_t)frame];
    if (layer < 0 || layer >= (int)ft.size()) return false;
    memcpy(out, ft[(size_t)layer].data(), (size_t)THUMB * THUMB * 4);
    return true;
}

void Engine::rewriteFramesMoveLayer(int from, int to) {
    auto put32 = [](std::vector<uint8_t>& v, uint32_t x) {
        v.push_back((uint8_t)(x & 255)); v.push_back((uint8_t)((x >> 8) & 255));
        v.push_back((uint8_t)((x >> 16) & 255)); v.push_back((uint8_t)((x >> 24) & 255));
    };
    auto remap = [&](uint32_t l) -> uint32_t {
        if ((int)l == from) return (uint32_t)to;
        if (from < to && (int)l > from && (int)l <= to) return l - 1;
        if (to < from && (int)l >= to && (int)l < from) return l + 1;
        return l;
    };
    for (int fi = 0; fi < (int)frameData_.size(); fi++) {
        if (fi == frame_) continue;
        const std::vector<uint8_t>& d = frameData_[(size_t)fi];
        if (d.size() < 12) continue;
        size_t p = 0;
        auto rd = [&](uint32_t& v) {
            if (p + 4 > d.size()) return false;
            v = (uint32_t)d[p] | ((uint32_t)d[p + 1] << 8) | ((uint32_t)d[p + 2] << 16) | ((uint32_t)d[p + 3] << 24);
            p += 4;
            return true;
        };
        uint32_t lc = 0, active = 0, count = 0;
        if (!rd(lc) || !rd(active) || !rd(count)) continue;
        std::vector<uint8_t> out;
        put32(out, lc);
        put32(out, remap(active));
        put32(out, count);
        for (uint32_t i = 0; i < count; i++) {
            uint32_t layer = 0, ti = 0, kind = 0;
            if (!rd(layer) || !rd(ti) || !rd(kind)) break;
            put32(out, remap(layer)); put32(out, ti); put32(out, kind);
            if (kind == 1) {
                uint32_t col = 0;
                if (!rd(col)) break;
                put32(out, col);
            } else {
                if (p + Canvas::TILE_PIX * 4 > d.size()) break;
                out.insert(out.end(), d.begin() + p, d.begin() + p + Canvas::TILE_PIX * 4);
                p += Canvas::TILE_PIX * 4;
            }
        }
        frameData_[(size_t)fi] = std::move(out);
    }
}

// 帧 blob 序列化(格式不变): 先算精确总长一次分配, 再顺序 memcpy 写。
// 旧实现每个 tile 调一次 resize, vector 增长会把已写内容整体搬移多次;
// 大画布切帧/自动保存时这一下能省掉几十毫秒。
static void serializeCanvasToBuf(const Canvas& c, std::vector<uint8_t>& out) {
    const int lc = c.layers();
    const int total = c.tilesX() * c.tilesY();
    size_t need = 12;
    int count = 0;
    for (int l = 0; l < lc; l++) {
        for (int ti = 0; ti < total; ti++) {
            const bool solid = c.isSolidTile(l, ti);
            const uint32_t* px = c.tile(l, ti);
            if (!solid && !px) continue;
            count++;
            need += solid ? 16 : (12 + Canvas::TILE_PIX * 4);
        }
    }
    out.resize(need);
    uint8_t* w = out.data();
    auto put = [&](uint32_t v) { memcpy(w, &v, 4); w += 4; };
    put((uint32_t)lc); put((uint32_t)c.active()); put((uint32_t)count);
    for (int l = 0; l < lc; l++) {
        for (int ti = 0; ti < total; ti++) {
            const bool solid = c.isSolidTile(l, ti);
            const uint32_t* px = c.tile(l, ti);
            if (!solid && !px) continue;
            put((uint32_t)l); put((uint32_t)ti);
            if (solid) {
                put(1u); put(c.solidTileColor(l, ti));
            } else {
                put(0u);
                memcpy(w, px, (size_t)Canvas::TILE_PIX * 4);
                w += (size_t)Canvas::TILE_PIX * 4;
            }
        }
    }
}

void Engine::serializeCanvas(std::vector<uint8_t>& out) {
    serializeCanvasToBuf(canvas_, out);
}

static bool deserializeCanvasInto(Canvas& c, const uint8_t* d, size_t n) {
    auto rd = [&](size_t& p, uint32_t& v) {
        if (p + 4 > n) return false;
        v = (uint32_t)d[p] | ((uint32_t)d[p + 1] << 8) | ((uint32_t)d[p + 2] << 16) | ((uint32_t)d[p + 3] << 24);
        p += 4;
        return true;
    };
    size_t p = 0;
    uint32_t lc = 0, active = 0, count = 0;
    if (!rd(p, lc) || !rd(p, active) || !rd(p, count)) return false;
    while (c.layers() < (int)lc) c.addLayer();
    for (int l = 0; l < c.layers(); l++)
        for (int ti = 0; ti < c.tilesX() * c.tilesY(); ti++) c.releaseTile(l, ti);
    for (uint32_t i = 0; i < count; i++) {
        uint32_t layer = 0, ti = 0, kind = 0;
        if (!rd(p, layer) || !rd(p, ti) || !rd(p, kind)) break;
        if (kind == 1) {
            uint32_t col = 0;
            if (!rd(p, col)) break;
            c.installTile((int)layer, (int)ti, nullptr, col, true);
        } else {
            if (p + Canvas::TILE_PIX * 4 > n) break;
            c.installTile((int)layer, (int)ti, (const uint32_t*)(d + p), 0, false);
            p += Canvas::TILE_PIX * 4;
        }
    }
    c.setActive((int)active);
    return true;
}

bool Engine::deserializeCanvas(const uint8_t* d, size_t n) {
    if (!deserializeCanvasInto(canvas_, d, n)) return false;
    layerCount_.store(canvas_.layers());
    activeLayer_.store(canvas_.active());
    return true;
}

void Engine::storeFrameData() {
    if (gradActive_ || gradDragging_) gradCancel();   // 帧数据不能把渐变预览当成已应用
    if (frame_ < 0 || frame_ >= (int)frameData_.size()) return;
    frameData_[(size_t)frame_].clear();
    serializeCanvas(frameData_[(size_t)frame_]);
    refreshFrameThumbs();
}

void Engine::loadFrameData(int idx) {
    if (idx < 0 || idx >= (int)frameData_.size()) return;
    // 选区/浮层属于上一帧的画布内容, 切帧必须丢掉, 否则会被落进新帧
    sel_.clearState();
    canvas_.clearCutRect();
    const auto& d = frameData_[(size_t)idx];
    if (d.empty()) {
        for (int l = 0; l < canvas_.layers(); l++)
            for (int ti = 0; ti < canvas_.tilesX() * canvas_.tilesY(); ti++) canvas_.releaseTile(l, ti);
        layerCount_.store(canvas_.layers());
        activeLayer_.store(canvas_.active());
    } else {
        deserializeCanvas(d.data(), d.size());
    }
    canvas_.markDirtyRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
    // 不再清空撤销栈: Hist 带 frame, 切帧后撤销会先切回记录所属帧
    lastSavedRev_.store(canvas_.revision());
}

void Engine::applyFrame(int idx) {
    if (idx < 0 || idx >= (int)frameData_.size() || idx == frame_) return;
    storeFrameData();
    frame_ = idx;
    frameIdx_.store(frame_);
    loadFrameData(frame_);
    sel_.deselect();
}

// 异步切帧: 大画布 store+load 是几十毫秒的同步拷贝, 会卡住渲染线程。
// 工作线程只读画布: 序列化旧帧 + 把目标帧解到临时 Canvas; 渲染线程最后只做一次
// tile 指针表 swap(微秒级)。任务期间画布写入/输入由 canvasBusy_ 冻结, 画面照常渲染。
void Engine::startFrameSwitch(int idx) {
    if (idx < 0 || idx >= (int)frameData_.size() || idx == frame_) return;
    if (canvasBusy_.load()) return;
    if (frameTh_.joinable()) frameTh_.join();
    frameOld_ = frame_;
    frameTarget_ = idx;
    {
        std::lock_guard<std::mutex> lk(frameMu_);
        frameOut_.clear();
        frameTmp_.reset();
    }
    frameReady_.store(false);
    frameBusy_.store(true);
    canvasBusy_.store(true);
    const int w = canvas_.width(), h = canvas_.height(), lc = canvas_.layers();
    frameTh_ = std::thread([this, w, h, lc] {
        const auto t0 = std::chrono::steady_clock::now();
        const long long tk0 = loopTicks_.load(std::memory_order_relaxed);
        std::vector<uint8_t> out;
        serializeCanvasToBuf(canvas_, out);
        std::unique_ptr<Canvas> tmp;
        {
            auto t = std::make_unique<Canvas>();
            if (t->create(w, h, lc)) {
                const auto& d = frameData_[(size_t)frameTarget_];
                if (!d.empty()) deserializeCanvasInto(*t, d.data(), d.size());
                tmp = std::move(t);
            }
        }
        {
            std::lock_guard<std::mutex> lk(frameMu_);
            frameOut_ = std::move(out);
            frameTmp_ = std::move(tmp);
        }
        frameReady_.store(true);
        const long long dtMs = (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                                   std::chrono::steady_clock::now() - t0).count();
        const long long dtk = loopTicks_.load(std::memory_order_relaxed) - tk0;
        lastFrameSwitchMs_.store((int)dtMs);
        lastFrameSwitchTicks_.store((int)dtk);
        LOGI("frame switch prep %d->%d %lldms (loop ticks %lld)", frameOld_, frameTarget_, dtMs, dtk);
    });
}

void Engine::finishFrameSwitch() {
    if (frameTh_.joinable()) frameTh_.join();
    std::vector<uint8_t> out;
    std::unique_ptr<Canvas> tmp;
    {
        std::lock_guard<std::mutex> lk(frameMu_);
        out = std::move(frameOut_);
        tmp = std::move(frameTmp_);
    }
    // 旧帧缩略图在交换前刷新(与同步 storeFrameData 的顺序一致)
    refreshFrameThumbs();
    if (frameOld_ >= 0 && frameOld_ < (int)frameData_.size() && !out.empty())
        frameData_[(size_t)frameOld_] = std::move(out);
    sel_.clearState();
    canvas_.clearCutRect();
    if (tmp) {
        canvas_.swapTileData(*tmp);
        canvas_.setActive(tmp->active());
    }
    frame_ = std::max(0, std::min(frameTarget_, (int)frameData_.size() - 1));
    frameIdx_.store(frame_);
    frameCount_.store((int)frameData_.size());
    canvas_.markDirtyRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
    layerCount_.store(canvas_.layers());
    activeLayer_.store(canvas_.active());
    sel_.deselect();
    lastSavedRev_.store(canvas_.revision());
    frameOld_ = -1;
    frameTarget_ = -1;
    frameBusy_.store(false);
    if (!exportBusy_.load()) canvasBusy_.store(false);
}

void Engine::requestSetRecord(bool on, std::string dir) {
    {
        std::lock_guard<std::mutex> lk(recMu_);
        recordDir_ = std::move(dir);
    }
    recordOn_.store(on);
    if (on) {
        recordN_.store(0);
        recordT0_ = std::chrono::steady_clock::now();
        recordLastMs_ = 0;
        std::string d;
        {
            std::lock_guard<std::mutex> lk(recMu_);
            d = recordDir_;
            recPts_.clear();
            recPtsPrevMs_ = 0;
        }
        if (!d.empty()) {
            FILE* m = fopen((d + "/meta.csv").c_str(), "w");
            if (m) {
                // 第9列 pts: x:y:dt;x:y:dt;... (画布坐标, dt=距上一点ms)
                fprintf(m, "seq,t,dur,tool,color,brush,layer,docframe,pts\n");
                fclose(m);
            }
            FILE* u = fopen((d + "/ui.csv").c_str(), "w");
            if (u) fclose(u);   // 新录制清空操作记录
        }
    }
    LOGI("record %s dir ok", on ? "on" : "off");
}

void Engine::requestResumeRecord(std::string dir, int startCount, int elapsedMs) {
    {
        std::lock_guard<std::mutex> lk(recMu_);
        recordDir_ = std::move(dir);
    }
    recordOn_.store(true);
    recordN_.store(std::max(0, startCount));
    recordLastMs_ = std::max(0, elapsedMs);
    {
        std::lock_guard<std::mutex> lk(recMu_);
        recPts_.clear();
        recPtsPrevMs_ = 0;
    }
    recordT0_ = std::chrono::steady_clock::now() - std::chrono::milliseconds(recordLastMs_);
    LOGI("record resume at %d frames / %dms", startCount, elapsedMs);
}

// 渲染线程: 录制帧 = 当前可见层合成 PNG + 一行元数据(旧 .replay 的帧序列)
void Engine::recordReplayFrame() {
    if (!recordOn_.load()) return;
    std::string dir;
    { std::lock_guard<std::mutex> lk(recMu_); dir = recordDir_; }
    if (dir.empty()) return;
    const int n = recordN_.fetch_add(1) + 1;
    // 先快照本笔轨迹: PNG 导出期间下一笔可能已经开始并清空 recPts_,
    // 放到导出后再读会把快速连笔的轨迹记成空.
    std::string ptsCsv;
    {
        std::lock_guard<std::mutex> lk(recMu_);
        ptsCsv = recPtsCsvLocked();
        recPts_.clear();
        recPtsPrevMs_ = 0;
    }
    char png[512], tmp[520];
    snprintf(png, sizeof png, "%s/r_%05d.png", dir.c_str(), n);
    snprintf(tmp, sizeof tmp, "%s.tmp", png);
    ExportResult er = exportPng(canvas_, tmp, 1, 0, true);
    if (!er.ok) {
        remove(tmp);
        recordN_.fetch_sub(1);
        LOGI("record frame %d png failed: %s", n, er.err.c_str());
        return;
    }
    fsyncPath(tmp);
    if (rename(tmp, png) != 0) {
        remove(tmp);
        recordN_.fetch_sub(1);
        LOGI("record frame %d rename failed", n);
        return;
    }
    auto now = std::chrono::steady_clock::now();
    int t = (int)std::chrono::duration_cast<std::chrono::milliseconds>(now - recordT0_).count();
    int dur = std::max(0, t - recordLastMs_);
    recordLastMs_ = t;
    FILE* m = fopen((dir + "/meta.csv").c_str(), "a");
    if (m) {
        fprintf(m, "%d,%d,%d,%d,%08X,%d,%d,%d,%s\n", n, t, dur, tool_.load(),
                brushColor_.load(), (int)std::lround(brushPx_.load()),
                canvas_.active(), frame_, ptsCsv.c_str());
        fsyncStream(m);
        fclose(m);
    }
}

// ---- 过程录制: 逐笔轨迹采集 ----
void Engine::recPtsBegin(int64_t eventMs) {
    std::lock_guard<std::mutex> lk(recMu_);
    recPts_.clear();
    recPtsPrevMs_ = eventMs;
}

void Engine::recPtsAdd(int x, int y, int64_t eventMs) {
    std::lock_guard<std::mutex> lk(recMu_);
    if (recPts_.size() >= 120000) return;   // 40000 点上限(长笔/大回放)
    const size_t n = recPts_.size();
    if (n >= 2 && recPts_[n - 2] == x && recPts_[n - 1] == y) return;   // 连续同像素去重
    int64_t dt = 0;
    if (recPtsPrevMs_ > 0 && eventMs > 0) dt = eventMs - recPtsPrevMs_;
    dt = std::max<int64_t>(0, std::min<int64_t>(dt, 60000));
    if (eventMs > 0) recPtsPrevMs_ = eventMs;
    recPts_.push_back(x);
    recPts_.push_back(y);
    recPts_.push_back((int)dt);
}

std::string Engine::recPtsCsvLocked() {
    std::string out;
    out.reserve(recPts_.size() * 4);
    char buf[64];
    for (size_t i = 0; i + 2 < recPts_.size(); i += 3) {
        if (!out.empty()) out.push_back(';');
        snprintf(buf, sizeof buf, "%d:%d:%d", recPts_[i], recPts_[i + 1], recPts_[i + 2]);
        out += buf;
    }
    return out;
}

// 录制中: 追加一条工具/面板操作记录(ui.csv: t,kind,label)
void Engine::recordUiAction(int kind, const std::string& label) {
    if (!recordOn_.load()) return;
    std::string dir;
    int t = 0;
    {
        std::lock_guard<std::mutex> lk(recMu_);
        dir = recordDir_;
        if (dir.empty()) return;
        auto now = std::chrono::steady_clock::now();
        t = (int)std::chrono::duration_cast<std::chrono::milliseconds>(now - recordT0_).count();
    }
    std::string safe;
    safe.reserve(label.size());
    for (char c : label) safe.push_back((c == ',' || c == '\n' || c == '\r') ? ' ' : c);
    FILE* u = fopen((dir + "/ui.csv").c_str(), "a");
    if (u) {
        fprintf(u, "%d,%d,%s\n", std::max(0, t), kind, safe.c_str());
        fsyncStream(u);
        fclose(u);
    }
}

void Engine::requestFrameOp(int op, int arg) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({40, (float)op, (float)arg, 0.0f});
}

void Engine::requestSetStretch(bool on) {
    // sel_ 是渲染线程独占对象: UI 线程只写原子请求, 下一帧 drainInput 里应用
    stretchModeReq_.store(on);
    if (on) perspectiveReq_.store(false);   // 与透视变形互斥
    screenFull_ = true;
}
void Engine::requestSetPerspective(bool on) {
    perspectiveReq_.store(on);
    if (on) stretchModeReq_.store(false);
    screenFull_ = true;
}
void Engine::requestSelReset() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({38, 0.0f, 0.0f, 0.0f});
}
void Engine::selectionUiRect(int out[18]) {
    std::lock_guard<std::mutex> lk(selUiMu_);
    for (int i = 0; i < 18; i++) out[i] = selUi_[i];
}

void Engine::gradUi(int out[7]) {
    std::lock_guard<std::mutex> lk(gradUiMu_);
    for (int i = 0; i < 7; i++) out[i] = gradUi_[i];
}

void Engine::measureUi(int out[12]) {
    out[0] = measOn_ ? 1 : 0;
    out[1] = measX0_; out[2] = measY0_; out[3] = measX1_; out[4] = measY1_;
    out[5] = (lastAct_.load() == 0 || lastAct_.load() == 1) ? 1 : 0;
    out[6] = measFixed_ ? 1 : 0;
    out[7] = measSetStartPending_ ? 1 : 0;
    float fx0 = 0.f, fy0 = 0.f, fx1 = 0.f, fy1 = 0.f;
    if (measOn_) {
        display_.canvasToScreenF((float)measX0_, (float)measY0_, &fx0, &fy0);
        display_.canvasToScreenF((float)measX1_, (float)measY1_, &fx1, &fy1);
    }
    out[8] = (int)std::lround(fx0); out[9] = (int)std::lround(fy0);
    out[10] = (int)std::lround(fx1); out[11] = (int)std::lround(fy1);
}

void Engine::requestDeleteSel() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({35, 0.0f, 0.0f, 0.0f});
}
void Engine::requestConfirmSel() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({36, 0.0f, 0.0f, 0.0f});
}
void Engine::requestSelAdjust(float h, float s, float b, float a, float blur, bool commit) {
    {
        std::lock_guard<std::mutex> lk(selOpMu_);
        selAdjH_ = h; selAdjS_ = s; selAdjB_ = b; selAdjA_ = a; selAdjBlur_ = blur;
        selAdjCommit_ = commit; selAdjPending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({86, 0, 0, 0});
}
void Engine::requestSelStroke(int mode, int size, int dirMask, bool outer, float depth, uint32_t color, bool commit) {
    {
        std::lock_guard<std::mutex> lk(selOpMu_);
        selStMode_ = mode; selStSize_ = size; selStDir_ = dirMask;
        selStOuter_ = outer; selStDepth_ = depth; selStColor_ = color; selStCommit_ = commit;
        selStrokePending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({87, 0, 0, 0});
}
void Engine::requestSelRestore() {
    {
        std::lock_guard<std::mutex> lk(selOpMu_);
        selRestorePending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({88, 0, 0, 0});
}

void Engine::requestSelCancelPreview() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({95, 0, 0, 0});   // 95: 避开形状渐变的 90-92
}

void Engine::requestFloatToViewCenter() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({96, 0, 0, 0});   // 96: 避开形状渐变的 90-92
}

void Engine::requestSetLayerProps(int layer, int mode, int tw, int th, int ox, int oy,
                                  bool showGrid, uint32_t gridColor, bool indep, int lockFrame,
                                  int blend) {
    {
        std::lock_guard<std::mutex> lk(layerOpMu_);
        lypLayer_ = layer; lypMode_ = mode; lypW_ = tw; lypH_ = th;
        lypOX_ = ox; lypOY_ = oy; lypShowGrid_ = showGrid;
        lypColor_ = gridColor; lypIndep_ = indep; lypLock_ = lockFrame;
        lypBlend_ = blend;
        lypPending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({89, 0, 0, 0});
}

std::vector<int> Engine::layerPropsCopy() {
    std::lock_guard<std::mutex> lk(layerPropsMu_);
    return layerPropsCache_;
}

void Engine::refreshLayerPropsCache() {
    std::vector<int> out;
    const int nl = canvas_.layers();
    out.reserve((size_t)nl * 9);
    for (int l = 0; l < nl; l++) {
        out.push_back(canvas_.layerMode(l));
        out.push_back(canvas_.layerTileW(l));
        out.push_back(canvas_.layerTileH(l));
        out.push_back(canvas_.layerTileOffX(l));
        out.push_back(canvas_.layerTileOffY(l));
        out.push_back(canvas_.layerShowGrid(l) ? 1 : 0);
        out.push_back((int)canvas_.layerGridColor(l));
        out.push_back(canvas_.layerTileIndep(l) ? 1 : 0);
        out.push_back(canvas_.layerTileLockFrame(l));
        out.push_back(canvas_.layerBlend(l));
    }
    std::lock_guard<std::mutex> lk(layerPropsMu_);
    layerPropsCache_.swap(out);
}

void Engine::requestSet25D(bool on, int depth, int dir) {
    {
        std::lock_guard<std::mutex> lk(layerOpMu_);
        m25On_ = on; m25Depth_ = std::max(1, std::min(20, depth)); m25Dir_ = dir & 3;
        m25Pending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({97, 0, 0, 0});   // 97: 避开形状渐变的 90-92
}

void Engine::requestReset25DView() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({98, 0, 0, 0});
}

// 小地图旋转按钮: 视角角增量(度)
void Engine::requestRotate25D(float deltaDeg) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({99, deltaDeg, 0, 0});
}

// 渲染线程: 更新2.5D视角(yaw), 同步 shader 旋转 + mip(层错位)+整屏
void Engine::applyYaw25D(float deg) {
    raster::setYaw25D(deg);
    display_.setRotation25(deg);
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    // 小地图跟着旋转: 缩略图是"旋转后的画布 bbox 铺满格子", 红框分数不变仍对齐
    if (minimapActive_.load(std::memory_order_relaxed)) {
        refreshMinimapThumb();
        thumbRev_.fetch_add(1, std::memory_order_relaxed);
    }
    screenFull_ = true;
}

void Engine::mode25DState(int out[3]) {
    out[0] = m25OnA_.load(std::memory_order_relaxed) ? 1 : 0;
    out[1] = m25DepthA_.load(std::memory_order_relaxed);
    out[2] = m25DirA_.load(std::memory_order_relaxed);
}

void Engine::requestCancelSel() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({37, 0.0f, 0.0f, 0.0f});
}

void Engine::requestPasteLastCut() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({23, 0.0f, 0.0f, 0.0f});
}

void Engine::requestRotateSel(int quarter) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({25, (float)quarter, 0.0f, 0.0f});
}

void Engine::requestFlipSel(bool horizontal) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({26, horizontal ? 1.0f : 0.0f, 0.0f, 0.0f});
}

void Engine::setImportImage(int w, int h, const std::vector<uint32_t>& px) {
    if (w <= 0 || h <= 0 || px.size() < (size_t)w * h) return;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        importPx_ = px;
        importW_ = w; importH_ = h;
        importPending_ = true;
        importText_ = false;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({24, 0.0f, 0.0f, 0.0f});
}

// 叠层切片: 每张图一层(按文件/zip 顺序), 图内纵向按 frameH 切帧。
// 层帧数不足时保持最后一帧, 这样 1 帧的静态层能在所有帧里显示。
void Engine::requestSliceImport(int canvasW, int frameH, int layers, int frames,
                                std::vector<std::string> names,
                                std::vector<int> layerW, std::vector<int> layerH,
                                std::vector<uint32_t> px) {
    if (canvasW < 1 || canvasW > 16834 || frameH < 1 || frameH > 16834) return;
    if (layers < 1 || layers > 32 || frames < 1 || frames > 512) return;
    if ((int)names.size() < layers || (int)layerW.size() < layers || (int)layerH.size() < layers) return;
    size_t total = 0;
    for (int l = 0; l < layers; l++) {
        if (layerW[(size_t)l] < 1 || layerH[(size_t)l] < 1) return;
        total += (size_t)layerW[(size_t)l] * (size_t)layerH[(size_t)l];
    }
    if (total > 16u * 1024 * 1024 || px.size() < total) return;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        sliceImp_.w = canvasW;
        sliceImp_.frameH = frameH;
        sliceImp_.layers = layers;
        sliceImp_.frames = frames;
        sliceImp_.names = std::move(names);
        sliceImp_.lw = std::move(layerW);
        sliceImp_.lh = std::move(layerH);
        sliceImp_.px = std::move(px);
        sliceImpPending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({86, 0.0f, 0.0f, 0.0f});
}

void Engine::requestSheetImport(int sheetW, int sheetH, int cellW, int cellH, int cols, int rows,
                                int offX, int offY,
                                bool cropFrames, bool framesAcross, bool layered, int depth,
                                std::vector<uint32_t> px) {
    if (sheetW < 1 || sheetH < 1 || cellW < 1 || cellH < 1 || cols < 1 || rows < 1) return;
    if (sheetW > 16834 || sheetH > 16834 || cellW > 16834 || cellH > 16834) return;
    if (cols > 64 || rows > 64) return;
    if (offX < 0 || offY < 0) return;
    if ((long long)offX + (long long)cellW * cols > sheetW ||
        (long long)offY + (long long)cellH * rows > sheetH) return;
    if ((long long)sheetW * sheetH > 16ll * 1024 * 1024) return;
    if (px.size() < (size_t)sheetW * sheetH) return;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        sheetImp_.sheetW = sheetW; sheetImp_.sheetH = sheetH;
        sheetImp_.cellW = cellW; sheetImp_.cellH = cellH;
        sheetImp_.cols = cols; sheetImp_.rows = rows;
        sheetImp_.offX = offX; sheetImp_.offY = offY;
        sheetImp_.cropFrames = cropFrames;
        sheetImp_.framesAcross = framesAcross;
        sheetImp_.layered = layered;
        sheetImp_.depth = std::max(0, std::min(20, depth));
        sheetImp_.px = std::move(px);
        sheetImpPending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({87, 0.0f, 0.0f, 0.0f});
}

void Engine::setImportTextImage(int w, int h, const std::vector<uint32_t>& px) {
    if (w <= 0 || h <= 0 || px.size() < (size_t)w * h) return;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        importPx_ = px;
        importW_ = w; importH_ = h;
        importPending_ = true;
        importText_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({24, 0.0f, 0.0f, 0.0f});
}

std::vector<uint32_t> Engine::readRegion(int x, int y, int w, int h) {
    std::vector<uint32_t> out;
    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return out;
    out.resize((size_t)w * h);
    for (int yy = 0; yy < h; yy++)
        for (int xx = 0; xx < w; xx++)
            out[(size_t)yy * w + xx] = canvas_.getPixel(x + xx, y + yy);
    return out;
}

bool Engine::lastCutPixels(std::vector<uint32_t>& out, int& w, int& h) {
    // 优先用裁剪目标(可见层合成, 与屏幕一致); 老状态没有目标时退回活动层 lastCut
    if (sel_.hasCropTarget()) {
        w = sel_.cropTargetW(); h = sel_.cropTargetH();
        out = sel_.cropTargetPixels();
        return out.size() >= (size_t)w * h;
    }
    // 读档恢复的浮层: cutRect 让源区域"透明", 但层像素还在; 临时取消遮挡读一次合成
    if (sel_.floating()) {
        const int sx = sel_.fSrcX(), sy = sel_.fSrcY();
        const int fw = sel_.fW(), fh = sel_.fH();
        if (fw > 0 && fh > 0) {
            const bool hadCut = canvas_.cutActive();
            canvas_.clearCutRect();
            out.resize((size_t)fw * fh);
            for (int y = 0; y < fh; y++)
                for (int x = 0; x < fw; x++)
                    out[(size_t)y * fw + x] = raster::composeLayersPixel(canvas_, sx + x, sy + y);
            if (hadCut) canvas_.setCutRect(sx, sy, sx + fw - 1, sy + fh - 1);
            w = fw; h = fh;
            return true;
        }
    }
    if (!sel_.hasLastCut()) return false;
    w = sel_.lastW(); h = sel_.lastH();
    if (w <= 0 || h <= 0) return false;
    out = sel_.lastCutPixels();
    return out.size() >= (size_t)w * h;
}

void Engine::setImportRegion(int x, int y, int w, int h, const std::vector<uint32_t>& px) {
    if (w <= 0 || h <= 0 || px.size() < (size_t)w * h) return;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        impRegPx_ = px;
        impRegX_ = x; impRegY_ = y; impRegW_ = w; impRegH_ = h;
        impRegPending_ = true;
    }
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({85, 0.0f, 0.0f, 0.0f});
}

namespace {
// 把临时 Canvas 序列化成帧 blob(与 Engine::serializeCanvas 同格式)
void serializeCanvasLocal(const Canvas& c, std::vector<uint8_t>& out) {
    serializeCanvasToBuf(c, out);
}
} // namespace

// 叠层切片导入(渲染线程): 每张图一层, 图内纵向切帧; 层帧数不足时保持最后一帧。
void Engine::doSliceImport() {
    SliceImportData d;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        if (!sliceImpPending_) return;
        d = std::move(sliceImp_);
        sliceImp_ = SliceImportData();
        sliceImpPending_ = false;
    }
    const int W = d.w, H = d.frameH, N = d.layers, F = d.frames;
    if (W < 1 || H < 1 || N < 1 || F < 1) return;
    if ((int)d.lw.size() < N || (int)d.lh.size() < N) return;
    std::vector<size_t> off((size_t)N, 0);
    size_t total = 0;
    for (int l = 0; l < N; l++) {
        off[(size_t)l] = total;
        total += (size_t)d.lw[(size_t)l] * (size_t)d.lh[(size_t)l];
    }
    if (d.px.size() < total) return;
    std::vector<int> layerFrames((size_t)N, 1);
    for (int l = 0; l < N; l++) layerFrames[(size_t)l] = std::max(1, d.lh[(size_t)l] / H);

    Canvas tmp;
    if (!tmp.create(W, H, N)) return;
    const int tts = tmp.tilesX() * tmp.tilesY();
    std::vector<std::vector<uint8_t>> frames((size_t)F);
    for (int f = 0; f < F; f++) {
        for (int l = 0; l < N; l++)
            for (int ti = 0; ti < tts; ti++) tmp.releaseTile(l, ti);
        for (int l = 0; l < N; l++) {
            const int sw = d.lw[(size_t)l], sh = d.lh[(size_t)l];
            const int sf = std::min(f, layerFrames[(size_t)l] - 1);
            const int srcY = sf * H;
            const int copyW = std::min(W, sw);
            const int copyH = std::min(H, sh - srcY);
            if (copyW <= 0 || copyH <= 0) continue;
            const uint32_t* base = d.px.data() + off[(size_t)l];
            for (int y = 0; y < copyH; y++) {
                const uint32_t* row = base + (size_t)(srcY + y) * sw;
                int x = 0;
                while (x < copyW) {
                    if (!row[x]) { x++; continue; }
                    const int run = std::min(copyW - x, Canvas::TILE - (x & (Canvas::TILE - 1)));
                    uint32_t* t = tmp.tileForWrite(l, (y >> Canvas::TILE_SHIFT) * tmp.tilesX() +
                                                       (x >> Canvas::TILE_SHIFT));
                    memcpy(t + ((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) +
                               (x & (Canvas::TILE - 1)), row + x, (size_t)run * 4);
                    x += run;
                }
            }
        }
        frames[(size_t)f].reserve((size_t)32 + (size_t)N * (Canvas::TILE_PIX * 4 + 16));
        serializeCanvasLocal(tmp, frames[(size_t)f]);
    }

    installImportedProject(W, H, N, F, frames, d.names);
    LOGI("slice import %dx%d layers=%d frames=%d frameH=%d", W, H, N, F, H);
}

// 把已经生成的帧 blob 一次性装成当前工程(切片/单 sheet 导入共用)
void Engine::installImportedProject(int W, int H, int N, int frameCount,
                                    std::vector<std::vector<uint8_t>>& frames,
                                    const std::vector<std::string>& names) {
    const int F = frameCount;
    if (W < 1 || H < 1 || N < 1 || F < 1 || (int)frames.size() < F) return;
    sel_.clearState();
    sel_.deselect();
    canvas_.clearCutRect();
    canvas_.create(W, H, N);
    deserializeCanvas(frames[0].data(), frames[0].size());
    frameData_.swap(frames);
    frame_ = 0;
    frameIdx_.store(0);
    frameCount_.store(F);
    playing_.store(0);
    playFps_.store(8);
    loopStart_.store(0);
    loopEnd_.store(F - 1);
    frameLayerCount_ = N;
    layerCount_.store(N);
    activeLayer_.store(0);
    for (int l = 0; l < N; l++) {
        if (l < (int)names.size() && !names[(size_t)l].empty())
            canvas_.setLayerName(l, names[(size_t)l]);
        canvas_.setLayerVisible(l, true);
        canvas_.setLayerLocked(l, false);
        canvas_.setLayerMode(l, 0);
        canvas_.setLayerBlend(l, 0);
        canvas_.setLayerOpacity(l, 1.f);
    }
    clearHistory();
    display_.resizeCanvas(W, H);
    display_.resetView();
    mip_.reset(&canvas_);
    mip_.invalidateRect(0, 0, W - 1, H - 1);
    layerMetaRev_++;
    refreshLayerMetaCache();
    refreshLayerPropsCache();
    lastSavedRev_.store(0);
    frameRev_.fetch_add(1);
    thumbRev_.fetch_add(1);
    screenFull_ = true;
    refreshAllFrameThumbs();   // 整个工程换掉: 每个帧格都必须重建(旧工程同尺寸同层数时不能走增量)
}

// 单张 sheet 导入(渲染线程): 网格 cellW×cellH; framesAcross=横排是帧/竖排是层。
// layered=true: 每个交叉格成为对应层在该帧的内容; false: 交叉轴各格按顺序压成一帧(单层)。
void Engine::doSheetImport() {
    SheetImportData d;
    {
        std::lock_guard<std::mutex> lk(importMu_);
        if (!sheetImpPending_) return;
        d = std::move(sheetImp_);
        sheetImp_ = SheetImportData();
        sheetImpPending_ = false;
    }
    const int sw = d.sheetW, sh = d.sheetH, cw = d.cellW, ch = d.cellH;
    if (sw < 1 || sh < 1 || cw < 1 || ch < 1 || d.cols < 1 || d.rows < 1) return;
    if ((long long)sw * sh > 16ll * 1024 * 1024) return;
    if (d.px.size() < (size_t)sw * sh) return;
    const int offX = std::max(0, std::min(d.offX, sw - 1));
    const int offY = std::max(0, std::min(d.offY, sh - 1));
    const int cols = d.cols, rows = d.rows;
    int F, N;
    if (d.cropFrames) {
        F = cols * rows; N = 1;
    } else if (d.layered) {
        if (d.framesAcross) { F = cols; N = rows; }
        else { F = rows; N = cols; }
    } else {
        F = d.framesAcross ? cols : rows;
        N = 1;
    }
    if (F < 1 || F > 512 || N < 1 || N > 32) return;
    std::vector<std::string> names((size_t)N);
    for (int l = 0; l < N; l++) names[(size_t)l] = "层" + std::to_string(l + 1);

    Canvas tmp;
    if (!tmp.create(cw, ch, N)) return;
    const int tts = tmp.tilesX() * tmp.tilesY();
    std::vector<std::vector<uint8_t>> frames((size_t)F);
    std::vector<uint32_t> acc;
    if (!d.layered) acc.assign((size_t)cw * ch, 0u);
    for (int f = 0; f < F; f++) {
        for (int l = 0; l < N; l++)
            for (int ti = 0; ti < tts; ti++) tmp.releaseTile(l, ti);
        if (d.cropFrames) {
            // 裁剪帧: 所有格子按行优先各成一帧(单层)
            const int col = f % cols, row = f / cols;
            const int sx0 = offX + col * cw, sy0 = offY + row * ch;
            if (sx0 + cw <= sw && sy0 + ch <= sh) {
                for (int y = 0; y < ch; y++) {
                    const uint32_t* rowp = d.px.data() + (size_t)(sy0 + y) * sw + sx0;
                    int x = 0;
                    while (x < cw) {
                        if (!rowp[x]) { x++; continue; }
                        const int run = std::min(cw - x, Canvas::TILE - (x & (Canvas::TILE - 1)));
                        uint32_t* t = tmp.tileForWrite(0, (y >> Canvas::TILE_SHIFT) * tmp.tilesX() +
                                                          (x >> Canvas::TILE_SHIFT));
                        memcpy(t + ((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) +
                                   (x & (Canvas::TILE - 1)), rowp + x, (size_t)run * 4);
                        x += run;
                    }
                }
            }
        } else if (d.layered) {
            for (int l = 0; l < N; l++) {
                const int col = d.framesAcross ? f : l;
                const int row = d.framesAcross ? l : f;
                const int sx0 = offX + col * cw, sy0 = offY + row * ch;
                if (sx0 + cw > sw || sy0 + ch > sh) continue;
                for (int y = 0; y < ch; y++) {
                    const uint32_t* rowp = d.px.data() + (size_t)(sy0 + y) * sw + sx0;
                    int x = 0;
                    while (x < cw) {
                        if (!rowp[x]) { x++; continue; }
                        const int run = std::min(cw - x, Canvas::TILE - (x & (Canvas::TILE - 1)));
                        uint32_t* t = tmp.tileForWrite(l, (y >> Canvas::TILE_SHIFT) * tmp.tilesX() +
                                                           (x >> Canvas::TILE_SHIFT));
                        memcpy(t + ((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) +
                                   (x & (Canvas::TILE - 1)), rowp + x, (size_t)run * 4);
                        x += run;
                    }
                }
            }
        } else {
            std::fill(acc.begin(), acc.end(), 0u);
            const int cross = d.framesAcross ? rows : cols;
            for (int c = 0; c < cross; c++) {
                const int col = d.framesAcross ? f : c;
                const int row = d.framesAcross ? c : f;
                const int sx0 = offX + col * cw, sy0 = offY + row * ch;
                if (sx0 + cw > sw || sy0 + ch > sh) continue;
                for (int y = 0; y < ch; y++) {
                    const uint32_t* rowp = d.px.data() + (size_t)(sy0 + y) * sw + sx0;
                    for (int x = 0; x < cw; x++) {
                        const uint32_t v = rowp[x];
                        if (v) raster::overPixel(acc[(size_t)y * cw + x], v);
                    }
                }
            }
            for (int y = 0; y < ch; y++) {
                const uint32_t* rowp = acc.data() + (size_t)y * cw;
                int x = 0;
                while (x < cw) {
                    if (!rowp[x]) { x++; continue; }
                    const int run = std::min(cw - x, Canvas::TILE - (x & (Canvas::TILE - 1)));
                    uint32_t* t = tmp.tileForWrite(0, (y >> Canvas::TILE_SHIFT) * tmp.tilesX() +
                                                      (x >> Canvas::TILE_SHIFT));
                    memcpy(t + ((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) +
                               (x & (Canvas::TILE - 1)), rowp + x, (size_t)run * 4);
                    x += run;
                }
            }
        }
        frames[(size_t)f].reserve((size_t)32 + (size_t)N * (Canvas::TILE_PIX * 4 + 16));
        serializeCanvasLocal(tmp, frames[(size_t)f]);
    }
    installImportedProject(cw, ch, N, F, frames, names);
    if (d.layered && d.depth > 0) {
        // 叠层导入: 同步开启 2.5D(时间轴图层设置里看到同样的开/高度)
        auto& g = raster::mode25D();
        g.on = true;
        g.depth = std::max(1, std::min(20, d.depth));
        g.dir = 0;
        raster::setYaw25D(0);
        display_.setRotation25(0);
        m25OnA_.store(true);
        m25DepthA_.store(g.depth);
        m25DirA_.store(0);
        layerMetaRev_++;
        refreshLayerPropsCache();
        mip_.invalidateRect(0, 0, cw - 1, ch - 1);
        screenFull_ = true;
    }
    LOGI("sheet import %dx%d cell=%dx%d cols=%d rows=%d across=%d layered=%d depth=%d",
         sw, sh, cw, ch, cols, rows, d.framesAcross ? 1 : 0, d.layered ? 1 : 0, d.depth);
}

// 选区内容整体旋转/翻转(即时提交, 走撤销记录)
void Engine::selectionRectOp(int op) {
    if (!sel_.active()) return;
    const auto selOpT0 = std::chrono::steady_clock::now();
    if (sel_.floating()) sel_.commitFloat();   // 浮层先按当前位置落定(零位移不搬)
    sel_.finishCommit();
    int rx0, ry0, rx1, ry1;
    sel_.rect(&rx0, &ry0, &rx1, &ry1);
    const int cw = canvas_.width(), ch = canvas_.height();
    rx0 = std::max(0, rx0); ry0 = std::max(0, ry0);
    rx1 = std::min(cw - 1, rx1); ry1 = std::min(ch - 1, ry1);
    const int w = rx1 - rx0 + 1, h = ry1 - ry0 + 1;
    if (w <= 0 || h <= 0) return;
    const int nw = (op <= 1) ? h : w, nh = (op <= 1) ? w : h;
    const int firstLayer = selAllLayers_.load() ? 0 : canvas_.active();
    const int lastLayer = selAllLayers_.load() ? canvas_.layers() - 1 : canvas_.active();
    const int tx0 = rx0 >> Canvas::TILE_SHIFT, tx1 = rx1 >> Canvas::TILE_SHIFT;
    const int ty0 = ry0 >> Canvas::TILE_SHIFT, ty1 = ry1 >> Canvas::TILE_SHIFT;
    const int cwx = canvas_.tilesX();
    struct Pix { int x, y; uint32_t c; };
    std::vector<Pix> content;   // 只搬非透明像素: 3% 缩放下整矩形 4000 万点逐像素写会卡死
    content.reserve(4096);
    beginStrokeRecord();
    for (int layer = firstLayer; layer <= lastLayer; layer++) {
        content.clear();
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                const int ti = ty * cwx + tx;
                const int bx = tx << Canvas::TILE_SHIFT, by = ty << Canvas::TILE_SHIFT;
                if (canvas_.isSolidTile(layer, ti)) {
                    const uint32_t col = canvas_.solidTileColor(layer, ti);
                    for (int yy = 0; yy < Canvas::TILE; yy++) {
                        const int cy = by + yy; if (cy < ry0 || cy > ry1) continue;
                        for (int xx = 0; xx < Canvas::TILE; xx++) {
                            const int cx = bx + xx; if (cx < rx0 || cx > rx1) continue;
                            if (content.size() < 2000000) content.push_back({cx, cy, col});
                        }
                    }
                    canvas_.setSolidTile(layer, ti, 0u);
                    continue;
                }
                // 空 tile 不要 tileForWrite(会分配+撤销快照整块): 只读判断, 有内容才写
                const uint32_t* tro = canvas_.tile(layer, ti);
                if (!tro) continue;
                uint32_t* t = canvas_.tileForWrite(layer, ti);
                for (int yy = 0; yy < Canvas::TILE; yy++) {
                    const int cy = by + yy; if (cy < ry0 || cy > ry1) continue;
                    uint32_t* row = t + ((size_t)yy << Canvas::TILE_SHIFT);
                    for (int xx = 0; xx < Canvas::TILE; xx++) {
                        const int cx = bx + xx; if (cx < rx0 || cx > rx1) continue;
                        const uint32_t v = row[xx];
                        if (!v) continue;
                        if (content.size() < 2000000) content.push_back({cx, cy, v});
                        row[xx] = 0;
                    }
                }
            }
        }
        for (const auto& pv : content) {
            int dx = pv.x - rx0, dy = pv.y - ry0;
            switch (op) {
                case 0: { const int t2 = dx; dx = h - 1 - dy; dy = t2; break; }   // 90°CW
                case 1: { const int t2 = dx; dx = dy; dy = w - 1 - t2; break; }   // 90°CCW
                case 2: dx = w - 1 - dx; break;
                case 3: dy = h - 1 - dy; break;
            }
            const int tx = rx0 + dx, ty = ry0 + dy;
            if ((unsigned)tx >= (unsigned)cw || (unsigned)ty >= (unsigned)ch) continue;
            const int ti = (ty >> Canvas::TILE_SHIFT) * cwx + (tx >> Canvas::TILE_SHIFT);
            uint32_t* t = canvas_.tileForWrite(layer, ti);
            // 目标矩形已清空(同层), 变换是双射: 直接赋值, 省掉逐像素 over 混合
            t[((size_t)(ty & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) + (tx & (Canvas::TILE - 1))] = pv.c;
        }
    }
    endStrokeRecord();
    canvas_.markDirtyRect(rx0, ry0, rx0 + nw - 1, ry0 + nh - 1);
    screenFull_ = true;
    // 保持裁剪编辑态: 旋转后仍可拖动/确认(旧 RotateSelection90 作用于剪贴板)
    sel_.setRectDirect(rx0, ry0, rx0 + nw - 1, ry0 + nh - 1);
    sel_.cut();
    const long sems = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - selOpT0).count();
    LOGI("selop op=%d rect=%d,%d %dx%d content=%zu %ldms", op, rx0, ry0, w, h, content.size(), sems);
}

void Engine::debugScript(const std::string& text, const std::string& logPath) {
    static std::atomic<int> nextId{1};
    { std::lock_guard<std::mutex> lk(dbgMu_); dbgText_ = text; dbgLog_ = logPath; dbgId_ = nextId.fetch_add(1); }
    {
        std::ofstream out(logPath, std::ios::app);
        if (out) out << "queued id=" << dbgId_ << " bytes=" << text.size() << std::endl;
    }
    dbgPending_.store(true);
}

void Engine::runDebugScript() {
    std::string text, logPath;
    { std::lock_guard<std::mutex> lk(dbgMu_); text.swap(dbgText_); logPath = dbgLog_; }
    std::ofstream out(logPath, std::ios::app);
    if (out) out << "run id=" << dbgId_ << std::endl;
    auto logline = [&](const std::string& s) { if (out) { out << s; out << std::endl; } };
    std::istringstream iss(text);
    std::string line;
    auto hexu = [](const std::string& s) { return (uint32_t)std::strtoul(s.c_str(), nullptr, 16); };
    while (std::getline(iss, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::istringstream ls(line);
        std::string cmd; ls >> cmd;
        if (cmd == "canvas") {
            logline("canvas " + std::to_string(canvas_.width()) + "x" + std::to_string(canvas_.height()) +
                    " layers=" + std::to_string(canvas_.layers()) + " frame=" + std::to_string(frame_) +
                    "/" + std::to_string(frameData_.size()));
        } else if (cmd == "gradinfo") {
            char gb[256];
            snprintf(gb, sizeof gb,
                     "grad act=%d drag=%d shape=%d preset=%d range=%d rangeA=%d rect=%d,%d-%d,%d layer=%d rec=%zu",
                     (int)gradActive_, (int)gradDragging_, gradShape_, gradPreset_, gradRange_,
                     gradRangeA_.load(), gradX0_, gradY0_, gradX1_, gradY1_, gradLayer_, gradRec_.size());
            logline(gb);
        } else if (cmd == "setlayer") {
            int l = 0; ls >> l;
            canvas_.setActive(l);
            activeLayer_.store(canvas_.active());
            layerMetaRev_++;
            logline("setlayer " + std::to_string(canvas_.active()));
        } else if (cmd == "layervis") {
            int l, v; ls >> l >> v;
            canvas_.setLayerVisible(l, v != 0);
            layerMetaRev_++;
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            refreshLayerMetaCache();
            logline("layervis " + std::to_string(l) + "=" + std::to_string(v));
        } else if (cmd == "layerlock") {
            int l, v; ls >> l >> v;
            canvas_.setLayerLocked(l, v != 0);
            layerMetaRev_++;
            refreshLayerMetaCache();
            logline("layerlock " + std::to_string(l) + "=" + std::to_string(v));
        } else if (cmd == "layertile") {   // 层 模式 宽 高 偏X 偏Y 显网格 颜色 不跟随 锁帧 [混合]
            int l, m, tw, th, ox, oy, sg, indep, lk, blend = 0; std::string col;
            ls >> l >> m >> tw >> th >> ox >> oy >> sg >> col >> indep >> lk >> blend;
            canvas_.setLayerMode(l, m);
            canvas_.setLayerTile(l, tw, th, ox, oy, sg != 0, hexu(col), indep != 0, lk);
            canvas_.setLayerBlend(l, blend);
            layerMetaRev_++;
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            refreshLayerPropsCache();
            logline("layertile ok");
        } else if (cmd == "layerblend") {
            int l, m; ls >> l >> m;
            canvas_.setLayerBlend(l, m);
            layerMetaRev_++;
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            refreshLayerPropsCache();
            logline("layerblend " + std::to_string(l) + "=" + std::to_string(m));
        } else if (cmd == "mode25d") {
            int on = 0, dep = 5, dir = 0;
            ls >> on >> dep >> dir;
            auto& g = raster::mode25D();
            g.on = on != 0; g.depth = std::max(1, std::min(20, dep)); g.dir = dir & 3;
            raster::setYaw25D(0);        // 元数据撤销不保留视角角, 避免和快照状态不一致
            display_.setRotation25(0);
            m25OnA_.store(g.on); m25DepthA_.store(g.depth); m25DirA_.store(g.dir);
            layerMetaRev_++;
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            logline("mode25d " + std::to_string(on) + "/" + std::to_string(dep) + "/" + std::to_string(dir));
        } else if (cmd == "yaw25d") {
            float yd = 0.f; ls >> yd;
            applyYaw25D(yd);
            logline("yaw25d " + std::to_string(yd));
        } else if (cmd == "layerinfo") {
            logline("active " + std::to_string(canvas_.active()) + " layers=" +
                    std::to_string(canvas_.layers()));
            for (int l = 0; l < canvas_.layers(); l++) {
                logline("layer " + std::to_string(l) + " vis=" + std::to_string(canvas_.layerVisible(l) ? 1 : 0) +
                        " lock=" + std::to_string(canvas_.isLayerLocked(l) ? 1 : 0) +
                        " mode=" + std::to_string(canvas_.layerMode(l)) +
                        " tile=" + std::to_string(canvas_.layerTileW(l)) + "x" + std::to_string(canvas_.layerTileH(l)) +
                        " off=" + std::to_string(canvas_.layerTileOffX(l)) + "," + std::to_string(canvas_.layerTileOffY(l)) +
                        " grid=" + std::to_string(canvas_.layerShowGrid(l) ? 1 : 0) +
                        " blend=" + std::to_string(canvas_.layerBlend(l)) +
                        " op=" + std::to_string((int)std::lround(canvas_.layerOpacity(l) * 1000.f)));
            }
            const auto& g = raster::mode25D();
            logline("mode25d state " + std::to_string(g.on ? 1 : 0) + "/" + std::to_string(g.depth) + "/" + std::to_string(g.dir) +
                    " yaw=" + std::to_string((int)std::lround(g.yawDeg)));
        } else if (cmd == "exportinfo") {
            int o[6]; exportInfo(o);
            logline("exportinfo " + std::to_string(o[0]) + "x" + std::to_string(o[1]) +
                    " frames=" + std::to_string(o[2]) + " loop=" + std::to_string(o[3]) + "-" +
                    std::to_string(o[4]) + " layers=" + std::to_string(o[5]));
        } else if (cmd == "exportmode") {   // 路径 类型(2精灵3逐帧4GIF5叠层) 倍率 每行 帧速ms
            std::string p; int k = 2, s = 1, c = 4, d = 100;
            ls >> p >> k >> s >> c >> d;
            logline(doMultiExport(p, k, s, c, d));
        } else if (cmd == "exportasync") {   // 走真实异步通道(Kotlin IO 协程同一入口)
            std::string p; int k = 2, s = 1, c = 4, d = 100;
            ls >> p >> k >> s >> c >> d;
            if (dbgExportTh_.joinable()) dbgExportTh_.join();
            dbgExportTh_ = std::thread([this, p, k, s, c, d] {
                std::string r = exportModeFile(p, k, s, c, d);
                LOGI("exportasync result: %s", r.c_str());
            });
            logline("exportasync queued");
        } else if (cmd == "psdasync") {
            std::string p; ls >> p;
            if (dbgExportTh_.joinable()) dbgExportTh_.join();
            dbgExportTh_ = std::thread([this, p] {
                std::string r = exportPsdFile(p);
                LOGI("psdasync result: %s", r.c_str());
            });
            logline("psdasync queued");
        } else if (cmd == "shapeprevtest") {
            // 形状预览批量写入/还原自测: 在同位置涂一小块再还原, 对比原像素
            const int l = canvas_.active();
            const int ax = 16, ay = 16, bx = 24, by = 24;
            std::vector<uint32_t> before;
            for (int y = ay; y <= by; y++)
                for (int x = ax; x <= bx; x++) before.push_back(canvas_.getPixelL(l, x, y));
            shapePrevBegin();
            std::vector<int> xy;
            for (int y = ay; y <= by; y++)
                for (int x = ax; x <= bx; x++) { xy.push_back(x); xy.push_back(y); }
            shapePrevPaint(xy, 0x80FF00FFu, l);
            const uint32_t painted = canvas_.getPixelL(l, (ax + bx) / 2, (ay + by) / 2);
            shapePrevRestore();
            int diff = 0;
            size_t k = 0;
            for (int y = ay; y <= by; y++)
                for (int x = ax; x <= bx; x++, k++)
                    if (canvas_.getPixelL(l, x, y) != before[k]) diff++;
            char sb[160];
            snprintf(sb, sizeof sb, "shapeprevtest n=%zu diff=%d painted=%08X", before.size(), diff, painted);
            logline(sb);
        } else if (cmd == "compose") {
            int x, y; ls >> x >> y;
            char b[128];
            snprintf(b, sizeof b, "compose %d,%d %08X active=%d", x, y,
                     raster::composeLayersPixel(canvas_, x, y), canvas_.active());
            logline(b);
            for (int l = 0; l < canvas_.layers(); l++) {
                snprintf(b, sizeof b, "  c%d vis=%d mode=%d px=%08X", l,
                         canvas_.layerVisible(l) ? 1 : 0, canvas_.layerMode(l),
                         canvas_.getPixelL(l, x, y));
                logline(b);
            }
        } else if (cmd == "lpixel") {
            int l, x, y; ls >> l >> x >> y;
            char b[96];
            snprintf(b, sizeof b, "lpixel %d %d,%d %08X mode=%d vis=%d", l, x, y,
                     canvas_.getPixelL(l, x, y), canvas_.layerMode(l), canvas_.layerVisible(l) ? 1 : 0);
            logline(b);
        } else if (cmd == "mipreset") {
            mip_.reset(&canvas_);
            screenFull_ = true;
            logline("mipreset");
        } else if (cmd == "updateverify") {
            // 校验 MipPyramid::updateRect 行级缓存路径: 改一块后逐像素对比原合成
            // 8 层里只有 0/3/7 有内容 → 顺带验证"空层剔除"不改变结果
            Canvas tc;
            tc.create(512, 512, 8);
            for (int l : {0, 3, 7}) {
                tc.setActive(l);
                raster::stampCircle(tc, 100 + l * 20, 120 + l * 15, 36, 0x80FF0000u | ((uint32_t)l * 0x0000FF00u));
            }
            MipPyramid tm;
            tm.reset(&tc);
            for (int ty = 0; ty < 2; ty++)
                for (int tx = 0; tx < 2; tx++) tm.tile(0, tx, ty);
            raster::stampCircle(tc, 200, 200, 27, 0xFF00FF00u);
            raster::fillRect(tc, 130, 260, 90, 70, 0xD93366FFu);
            tm.updateRect(120, 130, 280, 340, 0);
            int bad = 0, checked = 0, badTile = 0;
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    const uint32_t want = raster::composeLayersPixelEx(tc, x, y, true, true, false);
                    const uint32_t* t = tm.tile(0, x >> 8, y >> 8);
                    const uint32_t got = t ? t[((size_t)(y & 255) << 8) + (x & 255)] : 0;
                    checked++;
                    if (got != want) bad++;
                }
            }
            // 再整块重建一遍(走 tile 的空层剔除+单层直拷贝), 与逐像素合成对比
            MipPyramid tm2;
            tm2.reset(&tc);
            for (int ty = 0; ty < 2; ty++)
                for (int tx = 0; tx < 2; tx++) {
                    const uint32_t* t = tm2.tile(0, tx, ty);
                    for (int y = 0; y < 256; y++)
                        for (int x = 0; x < 256; x++) {
                            const int cx = tx * 256 + x, cy = ty * 256 + y;
                            const uint32_t want = raster::composeLayersPixelEx(tc, cx, cy, true, true, false);
                            const uint32_t got = t ? t[((size_t)y << 8) + x] : 0;
                            if (got != want) badTile++;
                        }
                }
            // L1(2x2 采样)也走同一套空层剔除: 与逐像素合成对比
            int badL1 = 0;
            {
                MipPyramid tm3;
                tm3.reset(&tc);
                const uint32_t* t1 = tm3.tile(1, 0, 0);
                for (int y = 0; y < 256; y++)
                    for (int x = 0; x < 256; x++) {
                        const uint32_t want = raster::composeLayersPixelEx(tc, x * 2, y * 2, true, true, false);
                        const uint32_t got = t1 ? t1[((size_t)y << 8) + x] : 0;
                        if (got != want) badL1++;
                    }
            }
            // 2.5D 叠层: 每层有位移时的空层剔除/行缓存, 与逐像素合成对比
            int bad25 = 0, bad25u = 0;
            {
                auto& g25 = raster::mode25D();
                const bool sOn = g25.on;
                const int sDepth = g25.depth;
                const float sYaw = g25.yawDeg;
                g25.on = true;
                g25.depth = 6;
                raster::setYaw25D(0.f);
                MipPyramid tm4;
                tm4.reset(&tc);
                for (int ty = 0; ty < 2; ty++)
                    for (int tx = 0; tx < 2; tx++) {
                        const uint32_t* t = tm4.tile(0, tx, ty);
                        for (int y = 0; y < 256; y++)
                            for (int x = 0; x < 256; x++) {
                                const int cx = tx * 256 + x, cy = ty * 256 + y;
                                const uint32_t want = raster::composeLayersPixelEx(tc, cx, cy, true, true, false);
                                const uint32_t got = t ? t[((size_t)y << 8) + x] : 0;
                                if (got != want) bad25++;
                            }
                    }
                // updateRect 的 2.5D 行缓存分支: 改一块后再次逐像素对比(按生产路径扩 pad)
                raster::stampCircle(tc, 300, 300, 24, 0xFF00FFFFu);
                const int pad25 = (tc.layers() - 1) * 6;
                tm4.updateRect(260 - pad25, 260 - pad25, 340 + pad25, 340 + pad25, 0);
                for (int ty = 0; ty < 2; ty++)
                    for (int tx = 0; tx < 2; tx++) {
                        const uint32_t* t = tm4.tile(0, tx, ty);
                        for (int y = 0; y < 256; y++)
                            for (int x = 0; x < 256; x++) {
                                const int cx = tx * 256 + x, cy = ty * 256 + y;
                                const uint32_t want = raster::composeLayersPixelEx(tc, cx, cy, true, true, false);
                                const uint32_t got = t ? t[((size_t)y << 8) + x] : 0;
                                if (got != want) bad25u++;
                            }
                    }
                g25.on = sOn;
                g25.depth = sDepth;
                raster::setYaw25D(sYaw);
            }
            logline("updateverify checked=" + std::to_string(checked) + " bad=" + std::to_string(bad) +
                    " tilebad=" + std::to_string(badTile) + " l1bad=" + std::to_string(badL1) +
                    " d25bad=" + std::to_string(bad25) + " d25ubad=" + std::to_string(bad25u));
        } else if (cmd == "mipsparse") {
            std::string v; ls >> v;
            const bool on = !(v == "0" || v == "off" || v == "false");
            raster::setMipSparseSkipEnabled(on);
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            logline(std::string("mipsparse ") + (on ? "on" : "off"));
        } else if (cmd == "perf") {
            char pb[256];
            snprintf(pb, sizeof pb, "perf fps=%d ft=%.2f maxft=%.2f comp=%.2f band=%.2f up=%.2f jk=%d dirty=%zu miptiles=%zu mipMB=%.1f busy=%d exp=%d frm=%d expms=%d exptk=%d frmms=%d frmtk=%d",
                     fps_.load(), lastFrameUs_.load() / 1000.0, maxFrameUs_.load() / 1000.0,
                     compUs_.load() / 1000.0, bandUs_.load() / 1000.0, upUs_.load() / 1000.0,
                     jank_.load(), canvas_.dirtyTiles().size(),
                     mip_.tileCount(), mip_.bytes() / 1048576.0,
                     canvasBusy_.load() ? 1 : 0, exportBusy_.load() ? 1 : 0, frameBusy_.load() ? 1 : 0,
                     lastExportMs_.load(), lastExportTicks_.load(),
                     lastFrameSwitchMs_.load(), lastFrameSwitchTicks_.load());
            logline(pb);
        } else if (cmd == "fastcompose") {
            std::string v; ls >> v;
            const bool on = !(v == "0" || v == "off" || v == "false");
            raster::setFastComposeEnabled(on);
            logline(std::string("fastcompose ") + (on ? "on" : "off"));
        } else if (cmd == "plainpath") {
            std::string v; ls >> v;
            plainPathOn_ = !(v == "0" || v == "off" || v == "false");
            screenFull_ = true;
            logline(std::string("plainpath ") + (plainPathOn_ ? "on" : "off"));
        } else if (cmd == "runfill") {
            std::string v; ls >> v;
            runFillOn_ = !(v == "0" || v == "off" || v == "false");
            screenFull_ = true;
            logline(std::string("runfill ") + (runFillOn_ ? "on" : "off"));
        } else if (cmd == "idlesleep") {
            std::string v; ls >> v;
            idleSleepOn_ = !(v == "0" || v == "off" || v == "false");
            screenFull_ = true;
            logline(std::string("idlesleep ") + (idleSleepOn_ ? "on" : "off"));
        } else if (cmd == "griddbg") {
            // 只改显示层网格(不写 prefs); 测试后 griddbg off 还原
            std::string v; ls >> v;
            const bool on = !(v == "0" || v == "off" || v == "false");
            display_.setOverlay(display_.checkerEnabled(), on);
            screenFull_ = true;
            logline(std::string("griddbg ") + (on ? "on" : "off"));
        } else if (cmd == "bench") {
            logline(runCanvasBench());
        } else if (cmd == "mipbench") {
            // 稀疏工程合成基准: 8 可见层里只有 3 层有内容, 对比空层剔除开/关
            Canvas bc;
            bc.create(1024, 1024, 8);
            for (int l : {0, 2, 5}) {
                bc.setActive(l);
                for (int i = 0; i < 260; i++) {
                    const int x = (i * 37 + l * 11) % 1000 + 12;
                    const int y = (i * 53 + l * 17) % 1000 + 12;
                    raster::stampCircle(bc, x, y, 7, 0xFF40A0FFu | ((uint32_t)l << 24));
                }
            }
            const auto t0 = std::chrono::steady_clock::now();
            auto buildAll = [&](bool sparse, int level) {
                raster::setMipSparseSkipEnabled(sparse);
                MipPyramid mp;
                mp.reset(&bc);
                const int nx = mp.tilesX(level), ny = mp.tilesY(level);
                for (int ty = 0; ty < ny; ty++)
                    for (int tx = 0; tx < nx; tx++) mp.tile(level, tx, ty);
            };
            buildAll(true, 0);   // 预热
            buildAll(true, 1);
            auto ms = [&](auto fn) {
                const auto a = std::chrono::steady_clock::now();
                fn();
                return std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - a).count();
            };
            const double l0on = ms([&] { buildAll(true, 0); });
            const double l0off = ms([&] { buildAll(false, 0); });
            const double l1on = ms([&] { buildAll(true, 1); });
            const double l1off = ms([&] { buildAll(false, 1); });
            const double l2on = ms([&] { buildAll(true, 2); });
            const double l2off = ms([&] { buildAll(false, 2); });
            raster::setMipSparseSkipEnabled(true);
            char mb[320];
            snprintf(mb, sizeof mb, "mipbench 1024 L8/3: L0 on=%.1f/off=%.1f (%.2fx) L1 on=%.1f/off=%.1f (%.2fx) L2 on=%.1f/off=%.1f (%.2fx)",
                     l0on, l0off, l0off / std::max(l0on, 0.001), l1on, l1off, l1off / std::max(l1on, 0.001),
                     l2on, l2off, l2off / std::max(l2on, 0.001));
            (void)t0;
            logline(mb);
        } else if (cmd == "storebench") {
            // 帧 blob 序列化/反序列化成本: 当前工程 + 1024²/8层满内容合成
            auto ms = [&](auto fn) {
                const auto a = std::chrono::steady_clock::now();
                fn();
                return std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - a).count();
            };
            std::vector<uint8_t> liveBuf;
            const double liveMs = ms([&] { serializeCanvasToBuf(canvas_, liveBuf); });
            const double liveMB = liveBuf.size() / 1048576.0;
            Canvas tc;
            tc.create(1024, 1024, 8);
            for (int l = 0; l < 8; l++) {
                tc.setActive(l);
                for (int i = 0; i < 300; i++) {
                    const int x = (i * 37 + l * 11) % 1000 + 12;
                    const int y = (i * 53 + l * 17) % 1000 + 12;
                    raster::stampCircle(tc, x, y, 8, 0xFF4080C0u | ((uint32_t)l << 24));
                }
            }
            std::vector<uint8_t> tb;
            const double serMs = ms([&] { serializeCanvasToBuf(tc, tb); });
            const double serMB = tb.size() / 1048576.0;
            Canvas t2;
            t2.create(1024, 1024, 8);
            const double desMs = ms([&] { deserializeCanvasInto(t2, tb.data(), tb.size()); });
            char sb[256];
            snprintf(sb, sizeof sb, "storebench live=%.2fms/%.2fMB ser1024L8=%.2fms/%.2fMB des1024L8=%.2fms",
                     liveMs, liveMB, serMs, serMB, desMs);
            logline(sb);
        } else if (cmd == "pinchbench") {
            // 无触摸模拟捏合帧: 进入 zoomGesture 后强制整屏重绘, 让 perf 读到 comp
            float z = 2.0f; ls >> z;
            display_.beginZoomGesture(540.0f, 1100.0f);
            display_.zoomGestureTo(std::max(0.05f, z), 540.0f, 1100.0f);
            screenFull_ = true;
            fullBurst_ = std::max(fullBurst_, 10);
            char pb2[128];
            snprintf(pb2, sizeof pb2, "pinchbench z=%.2f", display_.zoom());
            logline(pb2);
        } else if (cmd == "pinchend") {
            display_.endZoomGesture();
            screenFull_ = true;
            logline("pinchend");
        } else if (cmd == "bakebench") {
            // 捏合烘帧逐像素成本: 60 万像素棋盘+网格, 测新整数快路
            display_.setBakeChecker(true);
            const int N = 600000;
            volatile uint32_t sink = 0;
            const auto b0 = std::chrono::steady_clock::now();
            for (int i = 0; i < N; i++) {
                const float cx = (float)(i & 511) + 0.37f;
                const float cy = (float)((i * 7) & 511) + 0.71f;
                const uint32_t v = (i & 3) ? 0x00000000u : 0xFF204060u;
                sink = sink ^ display_.bakeCheckerPixel(cx, cy, true, v);
            }
            const double bms = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - b0).count();
            display_.setBakeChecker(false);
            // 旧浮点实现(和 pn163 同一套公式), 同输入对比
            const float z4 = display_.zoom();
            const float gw4 = display_.gridCellW(), gh4 = display_.gridCellH();
            const float ga4 = display_.gridAlphaVal();
            const float gr4 = display_.gridRVal(), gg4 = display_.gridGVal(), gb4 = display_.gridBVal();
            auto oldFn = [&](float cx, float cy, uint32_t v) -> uint32_t {
                float br = 0.05f, bg = 0.067f, bb = 0.09f;
                if (true) {
                    const float cell = std::max(10.0f * z4, 4.0f);
                    const int chk = ((int)std::floor(cx * z4 / cell) +
                                     (int)std::floor(cy * z4 / cell)) & 1;
                    br = chk ? 1.0f : 0.8f; bg = chk ? 1.0f : 0.8f; bb = chk ? 1.0f : 0.8f;
                }
                const int a8 = (int)((v >> 24) & 255u);
                const float a = (float)a8 / 255.0f;
                const float r = (float)(v & 255u) / 255.0f;
                const float g = (float)((v >> 8) & 255u) / 255.0f;
                const float b = (float)((v >> 16) & 255u) / 255.0f;
                float rr = r * a + br * (1.0f - a);
                float gg2 = g * a + bg * (1.0f - a);
                float bb2 = b * a + bb * (1.0f - a);
                const float gx4 = gw4 * z4, gy4 = gh4 * z4;
                if (gx4 >= 6.0f && gy4 >= 6.0f) {
                    const float sx4 = cx * z4, sy4 = cy * z4;
                    const float fx4 = sx4 - std::floor(sx4 / gx4) * gx4;
                    const float fy4 = sy4 - std::floor(sy4 / gy4) * gy4;
                    if (fx4 <= 0.75f || fy4 <= 0.75f) {
                        rr = rr * (1.0f - ga4) + gr4 * ga4;
                        gg2 = gg2 * (1.0f - ga4) + gg4 * ga4;
                        bb2 = bb2 * (1.0f - ga4) + gb4 * ga4;
                    }
                }
                return 0xFF000000u | ((uint32_t)std::lround(bb2 * 255.0f) << 16) |
                       ((uint32_t)std::lround(gg2 * 255.0f) << 8) | (uint32_t)std::lround(rr * 255.0f);
            };
            const auto b1 = std::chrono::steady_clock::now();
            for (int i = 0; i < N; i++) {
                const float cx = (float)(i & 511) + 0.37f;
                const float cy = (float)((i * 7) & 511) + 0.71f;
                const uint32_t v = (i & 3) ? 0x00000000u : 0xFF204060u;
                sink = sink ^ oldFn(cx, cy, v);
            }
            const double oms = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - b1).count();
            char bb2[220];
            snprintf(bb2, sizeof bb2, "bakebench N=%d new=%.1fms(%.1fns) old=%.1fms(%.1fns) speed=%.2fx sink=%08X",
                     N, bms, bms * 1000000.0 / N, oms, oms * 1000000.0 / N,
                     oms / std::max(bms, 0.001), (unsigned)sink);
            logline(bb2);
        } else if (cmd == "clear") {
            for (int l = 0; l < canvas_.layers(); l++) canvas_.clearRect(l, 0, 0, canvas_.width() - 1, canvas_.height() - 1);
            logline("clear ok");
        } else if (cmd == "fill") {
            std::string c; ls >> c;
            raster::fillRect(canvas_, 0, 0, canvas_.width(), canvas_.height(), hexu(c));
            logline("fill " + c);
        } else if (cmd == "rect") {
            int x0, y0, x1, y1; std::string c; ls >> x0 >> y0 >> x1 >> y1 >> c;
            raster::fillRect(canvas_, x0, y0, x1 - x0 + 1, y1 - y0 + 1, hexu(c));
            logline("rect");
        } else if (cmd == "line") {
            int x0, y0, x1, y1, rad; std::string c; ls >> x0 >> y0 >> x1 >> y1 >> rad >> c;
            raster::stampLine(canvas_, x0, y0, x1, y1, rad, hexu(c));
            logline("line");
        } else if (cmd == "drawshape") {
            int ty, x0, y0, x1, y1, th; std::string c;
            ls >> ty >> x0 >> y0 >> x1 >> y1 >> th >> c;
            raster::drawShape(canvas_, ty, false, x0, y0, x1, y1, th, hexu(c));
            logline("drawshape " + std::to_string(ty));
        } else if (cmd == "shape") {
            int s; ls >> s; selShape_.store(s); sel_.setSelShape(s); appliedSelShape_ = s;
            logline("shape " + std::to_string(s));
        } else if (cmd == "rectsel") {
            int a, b, c, d; ls >> a >> b >> c >> d; sel_.debugRect(a, b, c, d);
            logline("rectsel");
        } else if (cmd == "lasso") {
            std::vector<int> pts; int v; while (ls >> v) pts.push_back(v);
            selShape_.store(1); sel_.setSelShape(1); appliedSelShape_ = 1;
            sel_.debugLasso(pts);
            logline("lasso pts=" + std::to_string(pts.size() / 2) + " msk=" + std::to_string(sel_.maskPixelCount()));
        } else if (cmd == "wand") {
            int x, y; ls >> x >> y;
            selShape_.store(2); sel_.setSelShape(2); appliedSelShape_ = 2;
            sel_.debugWand(x, y);
            logline("wand msk=" + std::to_string(sel_.maskPixelCount()));
        } else if (cmd == "selall") {
            sel_.selectAll(); logline("selall");
        } else if (cmd == "deselect") {
            sel_.deselect(); logline("deselect");
        } else if (cmd == "del") {
            sel_.deleteRect(); logline("del");
        } else if (cmd == "copy") {
            sel_.copyRect(); logline("copy skipped=" + std::to_string(sel_.lastCutSkipped() ? 1 : 0) +
                                     " last=" + std::to_string(sel_.lastW()) + "x" + std::to_string(sel_.lastH()));
        } else if (cmd == "cut") {
            sel_.cut(); logline("cut float=" + std::to_string(sel_.floating() ? 1 : 0));
        } else if (cmd == "paste") {
            sel_.pasteLastCut(); sel_.applyPendingPaste();
            logline("paste lastcut=" + std::to_string(sel_.lastW()) + "x" + std::to_string(sel_.lastH()));
        } else if (cmd == "pastefloat") {   // 上次裁剪 → 浮层待确认(视口中心)
            sel_.pasteLastCutAtViewCenter(canvas_.width() * 0.5f, canvas_.height() * 0.5f);
            logline("pastefloat float=" + std::to_string(sel_.floating() ? 1 : 0) +
                    " last=" + std::to_string(sel_.lastW()) + "x" + std::to_string(sel_.lastH()));
        } else if (cmd == "move") {
            int dx, dy; ls >> dx >> dy; sel_.debugMove(dx, dy); logline("move");
        } else if (cmd == "commit") {
            sel_.commitFloat(); sel_.finishCommit(); logline("commit float=" + std::to_string(sel_.floating() ? 1 : 0));
        } else if (cmd == "confirm") {
            sel_.commitTransform(); sel_.finishCommit(); logline("confirm");
        } else if (cmd == "cancel") {
            sel_.cancelFloat(); sel_.deselect(); logline("cancel");
        } else if (cmd == "selinfo") {
            logline("selinfo active=" + std::to_string(sel_.active() ? 1 : 0) +
                    " float=" + std::to_string(sel_.floating() ? 1 : 0) +
                    " outline=" + std::to_string(sel_.maskOutline().size() / 4) +
                    " alphaoverlay=" + std::to_string(sel_.alphaOverlay() ? 1 : 0) +
                    " hold=" + std::to_string(sel_.holdMs()));
        } else if (cmd == "selhold") {
            int ms = 1000; ls >> ms;
            setSelectionHoldMs(ms);
            logline("selhold " + std::to_string(sel_.holdMs()));
        } else if (cmd == "alphaoverlay") {
            int on = 0; ls >> on;
            sel_.setAlphaOverlay(on != 0);
            logline("alphaoverlay " + std::to_string(sel_.alphaOverlay() ? 1 : 0));
        } else if (cmd == "cuttarget") {
            std::vector<uint32_t> px; int w = 0, h = 0;
            const bool ok = lastCutPixels(px, w, h);
            int nz = 0;
            for (uint32_t v : px) if (v) nz++;
            logline("cuttarget ok=" + std::to_string(ok ? 1 : 0) + " " +
                    std::to_string(w) + "x" + std::to_string(h) +
                    " nz=" + std::to_string(nz) + " hasTarget=" +
                    std::to_string(sel_.hasCropTarget() ? 1 : 0) +
                    " last=" + std::to_string(sel_.lastW()) + "x" + std::to_string(sel_.lastH()));
        } else if (cmd == "lvprev") {
            float g = 1.5f; ls >> g;
            int luts[1024];
            for (int v = 0; v < 256; v++) {
                float t = v / 255.0f;
                int o = (int)std::lround(std::pow(t, 1.0f / g) * 255.0f);
                for (int ch = 0; ch < 4; ch++) luts[ch * 256 + v] = std::max(0, std::min(255, o));
            }
            setLevelsPreview(true, luts);
            logline("lvprev g=" + std::to_string(g));
        } else if (cmd == "lvprevoff") {
            setLevelsPreview(false, nullptr);
            logline("lvprevoff");
        } else if (cmd == "selxf") {
            float sx = 1, sy = 1, rot = 0; ls >> sx >> sy >> rot;
            sel_.debugSetTransform(sx, sy, rot);
            screenFull_ = true;
            logline("selxf");
        } else if (cmd == "selpersp") {
            float q[8] = {0};
            for (int i = 0; i < 8; i++) ls >> q[i];
            requestSetPerspective(true);
            sel_.debugSetPerspective(q);
            screenFull_ = true;
            logline("selpersp");
        } else if (cmd == "selperspcommit") {
            sel_.commitTransform();
            sel_.finishCommit();
            screenFull_ = true;
            logline("selperspcommit");
        } else if (cmd == "selxfcommit") {
            sel_.commitTransform();
            screenFull_ = true;
            logline("selxfcommit");
        } else if (cmd == "selxfcancel") {
            sel_.cancelFloat();
            screenFull_ = true;
            logline("selxfcancel");
        } else if (cmd == "rot90") {
            sel_.rotate90(); logline("rot90");
        } else if (cmd == "fliph") {
            sel_.flipSelH(); logline("fliph");
        } else if (cmd == "flipv") {
            sel_.flipSelV(); logline("flipv");
        } else if (cmd == "scale") {
            int w, h; ls >> w >> h; scaleSelection(w, h); logline("scale");
        } else if (cmd == "req25d") {
            int on = 0, dep = 5, dir = 0; ls >> on >> dep >> dir;
            requestSet25D(on != 0, dep, dir);
            logline("req25d queued");
        } else if (cmd == "recstart") {
            std::string d; ls >> d;
            requestSetRecord(true, d);
            logline("recstart " + d);
        } else if (cmd == "recstop") {
            requestSetRecord(false, "");
            logline("recstop");
        } else if (cmd == "reccount") {
            logline("reccount on=" + std::to_string(recordOn_.load() ? 1 : 0) +
                    " n=" + std::to_string(recordN_.load()));
        } else if (cmd == "levels") {
            int ch = 0, bl = 0, wh = 255, ob = 0, ow = 255; float gm = 1.f;
            ls >> ch >> bl >> wh >> gm >> ob >> ow;
            applyLevelsImpl(ch, bl, wh, gm, ob, ow);
            logline("levels ok");
        } else if (cmd == "allflip" || cmd == "allgray") {
            int h = 1; if (cmd == "allflip") ls >> h;
            beginStrokeRecord();
            canvas_.snapshotAllTiles();
            if (cmd == "allgray") canvas_.grayscaleAllLayers();
            else canvas_.flipAllLayers(h != 0);
            endStrokeRecord();
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            logline(cmd + " ok");
        } else if (cmd == "frameop") {
            int op = 0, arg = 0; ls >> op >> arg;
            requestFrameOp(op, arg);
            logline("frameop " + std::to_string(op) + " " + std::to_string(arg));
        } else if (cmd == "layerop") {
            int op = 0, arg = 0; ls >> op >> arg;
            layerOp(op, arg);
            logline("layerop " + std::to_string(op) + " " + std::to_string(arg));
        } else if (cmd == "droplayer") {   // 调试清理: 直接删层, 不产生撤销/步骤
            int l = -1; ls >> l;
            if (l >= 0 && l < canvas_.layers()) {
                canvas_.removeLayer(l);
                frameLayerCount_ = canvas_.layers();
                rewriteFramesDropLayer(l);
                layerCount_.store(canvas_.layers());
                activeLayer_.store(canvas_.active());
                layerMetaRev_++;
                refreshLayerMetaCache();
                refreshLayerPropsCache();
                mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
                screenFull_ = true;
                logline("droplayer " + std::to_string(l) + " lc=" + std::to_string(canvas_.layers()));
            } else {
                logline("droplayer bad " + std::to_string(l));
            }
        } else if (cmd == "hist") {
            logline("hist undo=" + std::to_string(undo_.size()) + " redo=" + std::to_string(redo_.size()) +
                    " ub=" + std::to_string(undoBytes_) + " rb=" + std::to_string(redoBytes_) +
                    " frame=" + std::to_string(frame_));
            auto dumpTop = [&](const char* tag, const std::vector<Hist>& v) {
                const int n = std::min<int>(8, (int)v.size());
                for (int i = 0; i < n; i++) {
                    const Hist& h = v[v.size() - 1 - (size_t)i];
                    logline(std::string(tag) + " " + std::to_string(i) + " kind=" + std::to_string(h.kind) +
                            " frame=" + std::to_string(h.frame) + " rec=" + std::to_string(h.rec.size()) +
                            " spans=" + std::to_string(h.spans.size()) + " doc=" + std::to_string(h.doc ? 1 : 0) +
                            " meta=" + std::to_string(h.meta ? 1 : 0) + " bytes=" + std::to_string(h.bytes));
                }
            };
            dumpTop("u", undo_);
            dumpTop("r", redo_);
        } else if (cmd == "undo") {
            undo(); logline("undo");
        } else if (cmd == "redo") {
            redo(); logline("redo");
        } else if (cmd == "pixel") {
            int x, y; ls >> x >> y;
            char b[96];
            snprintf(b, sizeof b, "pixel %d,%d %08X", x, y, canvas_.getPixel(x, y));
            logline(b);
        } else if (cmd == "count") {
            int x0, y0, x1, y1; std::string c; ls >> x0 >> y0 >> x1 >> y1 >> c;
            const uint32_t want = hexu(c);
            long long n = 0;
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) if (canvas_.getPixel(x, y) == want) n++;
            logline("count " + std::to_string(n));
        } else if (cmd == "stat") {
            int rx0 = 0, ry0 = 0, rx1 = 0, ry1 = 0;
            sel_.rect(&rx0, &ry0, &rx1, &ry1);
            char b[192];
            snprintf(b, sizeof b, "stat active=%d float=%d msk=%d rect=%d,%d-%d,%d lastcut=%d,%d bufF=%d extF=%d gpu=%d copy=%d text=%d bufN=%zu",
                     sel_.active() ? 1 : 0, sel_.floating() ? 1 : 0, sel_.maskPixelCount(),
                     rx0, ry0, rx1, ry1, sel_.lastW(), sel_.lastH(),
                     sel_.dbgBufFloat() ? 1 : 0, sel_.extActive() ? 1 : 0,
                     sel_.dbgGpuFloat() ? 1 : 0, sel_.dbgCopyFloat() ? 1 : 0,
                     sel_.dbgTextFloat() ? 1 : 0, sel_.dbgBufSize());
            logline(b);
        } else if (cmd == "onion") {
            int on = 1; ls >> on;
            onionOn_.store(on != 0); onionGen_.fetch_add(1);
            logline("onion " + std::to_string(on));
        } else if (cmd == "resize") {
            int rw = 0, rh = 0, rax = 1, ray = 1;
            ls >> rw >> rh >> rax >> ray;
            requestResizeCanvas(rw, rh, rax, ray);
            logline("resize queued");
        } else if (cmd == "cancelstroke") {
            requestCancelFirstStroke();
            logline("cancelstroke queued");
        } else if (cmd == "s2c") {
            float sx = 0, sy = 0; int ccx = 0, ccy = 0; ls >> sx >> sy;
            const bool in = display_.screenToCanvas(sx, sy, &ccx, &ccy);
            char bb[128];
            snprintf(bb, sizeof bb, "s2c %g,%g -> %d,%d inside=%d zoom=%.3f", sx, sy, ccx, ccy, in ? 1 : 0, display_.zoom());
            logline(bb);
        } else if (cmd == "view") {
            const auto& g25 = raster::mode25D();
            char bb[192];
            snprintf(bb, sizeof bb, "view zoom=%.4f pan=%.1f,%.1f yaw=%.1f on=%d depth=%d mm=%d",
                     display_.zoom(), display_.panX(), display_.panY(), g25.yawDeg,
                     g25.on ? 1 : 0, g25.depth, minimapActive_.load() ? 1 : 0);
            logline(bb);
        } else if (cmd == "setview") {
            float z = 1.f, pxx = 0.f, pyy = 0.f;
            ls >> z >> pxx >> pyy;
            viewAnimActive_ = false;
            display_.setView(z, pxx, pyy);
            screenFull_ = true;
            logline("setview " + std::to_string(z) + " " + std::to_string(pxx) + " " + std::to_string(pyy));
        } else if (cmd == "clearrect") {
            int ax = 0, ay = 0, bx = 0, by = 0; ls >> ax >> ay >> bx >> by;
            canvas_.clearRect(canvas_.active(), ax, ay, bx, by);
            logline("clearrect");
        } else if (cmd == "alphahist") {
            int ax = 0, ay = 0, bx = 0, by = 0; ls >> ax >> ay >> bx >> by;
            ax = std::max(0, ax); ay = std::max(0, ay);
            bx = std::min(canvas_.width() - 1, bx); by = std::min(canvas_.height() - 1, by);
            int hist[256] = {0};
            for (int y = ay; y <= by; y++)
                for (int x = ax; x <= bx; x++) hist[(canvas_.getPixelL(canvas_.active(), x, y) >> 24) & 255]++;
            std::string h;
            for (int a = 1; a < 256; a++) if (hist[a]) h += " " + std::to_string(a) + ":" + std::to_string(hist[a]);
            logline("alphahist" + (h.empty() ? std::string(" none") : h));
        } else if (cmd == "alphatest") {
            auto one = [&](bool bup, int strokes, const char* name) {
                Canvas tc; tc.create(64, 64, 1);
                const uint32_t src = 0x80FF0000u;
                for (int k = 0; k < strokes; k++) {
                    if (bup) tc.beginStampMask(); else tc.beginStrokeMask();
                    raster::stampCircle(tc, 28, 32, 10, src, false, bup);
                    raster::stampCircle(tc, 32, 32, 10, src, false, bup);
                    raster::stampCircle(tc, 36, 32, 10, src, false, bup);
                    tc.endStrokeMask();
                }
                const uint32_t px = tc.getPixelL(0, 32, 32);
                char bb[128];
                snprintf(bb, sizeof bb, "alphatest %s a=%u rgb=%06X", name,
                         (px >> 24) & 255u, px & 0x00FFFFFFu);
                logline(bb);
            };
            one(false, 1, "off1"); one(false, 2, "off2");
            one(true, 1, "on1"); one(true, 2, "on2");
            auto mix = [&](bool bup, const char* name) {
                Canvas tc; tc.create(64, 64, 1);
                if (bup) tc.beginStampMask(); else tc.beginStrokeMask();
                raster::stampCircle(tc, 32, 32, 10, 0x80FF0000u, false, bup);
                tc.endStrokeMask();
                if (bup) tc.beginStampMask(); else tc.beginStrokeMask();
                raster::stampCircle(tc, 32, 32, 10, 0x800000FFu, false, bup);
                tc.endStrokeMask();
                const uint32_t px = tc.getPixelL(0, 32, 32);
                char bb[128];
                snprintf(bb, sizeof bb, "alphalet %s a=%u rgb=%06X", name,
                         (px >> 24) & 255u, px & 0x00FFFFFFu);
                logline(bb);
            };
            mix(false, "off"); mix(true, "on");
            {
                // 自由曲线抖动: 相邻 stamp 边缘短暂离开又覆盖, 不应产生叠色点
                Canvas tc; tc.create(256, 128, 1);
                const uint32_t src = 0x91808080u;   // 57%
                const uint32_t baseA = (src >> 24) & 255u;
                tc.beginStampMask();
                for (int i = 0; i < 60; i++) {
                    const int y = 64 + ((i % 2 == 0) ? 0 : 5);
                    raster::stampCircle(tc, 12 + i * 4, y, 12, src, false, true);
                }
                tc.endStrokeMask();
                int maxA = 0, over = 0;
                for (int y = 0; y < 128; y++)
                    for (int x = 0; x < 256; x++) {
                        const uint32_t a = (tc.getPixelL(0, x, y) >> 24) & 255u;
                        if ((int)a > maxA) maxA = (int)a;
                        if (a > baseA) over++;
                    }
                char bb[160];
                snprintf(bb, sizeof bb, "alphajitter base=%u max=%d over=%d", baseA, maxA, over);
                logline(bb);
            }
            {
                // 单笔自交: 两条交叉斜线在同一笔内, 交点应叠加一次
                Canvas tc; tc.create(96, 96, 1);
                const uint32_t src = 0x80FF0000u;
                tc.beginStampMask();
                for (int x = 12; x <= 84; x += 4)
                    raster::stampCircle(tc, x, 24 + (x - 12) / 2, 8, src, false, true);
                for (int x = 12; x <= 84; x += 4)
                    raster::stampCircle(tc, x, 72 - (x - 12) / 2, 8, src, false, true);
                tc.endStrokeMask();
                const uint32_t center = tc.getPixelL(0, 48, 48);
                const uint32_t side = tc.getPixelL(0, 12, 24);
                char bb[160];
                snprintf(bb, sizeof bb, "alphacross oneStroke center=%u side=%u",
                         (center >> 24) & 255u, (side >> 24) & 255u);
                logline(bb);
            }
        } else if (cmd == "resetview") {
            std::lock_guard<std::mutex> lk(mu_);
            if (queue_.size() < 4096) queue_.push_back({18, 0.0f, 0.0f, 0.0f});
            logline("resetview queued");
        } else if (cmd == "zfac") {
            float zf = 1.f, fx = 540.f, fy = 1080.f;
            ls >> zf >> fx >> fy;
            zoom(zf, fx, fy);
            logline("zfac " + std::to_string(zf));
        } else if (cmd == "bsim3") {
            // 单笔边缘自叠色复现: 曲线/抖动直线/贴边, 统计 alpha>128 的像素位置
            auto run3 = [&](const char* name, const std::vector<int>& xs, const std::vector<int>& ys,
                            int r, bool capsuleHint) {
                Canvas tc; tc.create(64, 64, 1);
                tc.beginStampMask();
                auto seg = [&](int x0, int y0, int x1, int y1) {
                    const float dx = (float)(x1 - x0), dy = (float)(y1 - y0);
                    const float len = std::sqrt(dx * dx + dy * dy);
                    tc.setStampDir(dx, dy);
                    tc.setStampWindow(len + 1.0f, std::max(2.0f, (float)r * 2.0f + 1.0f));
                    raster::stampLine(tc, x0, y0, x1, y1, r, 0x80FF0000u, false, true);
                };
                for (size_t i = 1; i < xs.size(); i++) seg(xs[i-1], ys[i-1], xs[i], ys[i]);
                tc.endStrokeMask();
                int over = 0, painted = 0, borderOver = 0;
                int ox0 = 999, oy0 = 999, ox1 = -1, oy1 = -1;
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++) {
                        const int a = (int)((tc.getPixelL(0, x, y) >> 24) & 255u);
                        if (!a) continue;
                        painted++;
                        if (a > 128) {
                            over++;
                            if (x < ox0) ox0 = x;
                            if (y < oy0) oy0 = y;
                            if (x > ox1) ox1 = x;
                            if (y > oy1) oy1 = y;
                            if (x <= 1 || y <= 1 || x >= 62 || y >= 62) borderOver++;
                        }
                    }
                char b[256];
                snprintf(b, sizeof b, "b3 %s r=%d n=%d painted=%d dark=%d borderDark=%d bbox=%d,%d-%d,%d",
                         name, r, (int)xs.size(), painted, over, borderOver,
                         over ? ox0 : -1, over ? oy0 : -1, over ? ox1 : -1, over ? oy1 : -1);
                logline(b);
            };
            // 1) 近似圆曲线(每步 1.5px, 方向逐步变化)
            {
                std::vector<int> xs, ys;
                for (int i = 0; i <= 40; i++) {
                    const double a = -1.2 + i * 0.06;
                    xs.push_back((int)std::lround(32 + 18 * std::cos(a)));
                    ys.push_back((int)std::lround(32 + 18 * std::sin(a)));
                }
                run3("arc", xs, ys, 3, false);
            }
            // 2) 直线 + 小方向抖动(手抖)
            {
                std::vector<int> xs, ys;
                for (int i = 0; i <= 40; i++) {
                    xs.push_back(6 + i);
                    ys.push_back(32 + ((i % 3 == 0) ? 1 : 0));
                }
                run3("jitter", xs, ys, 3, false);
            }
            // 3) 贴画布上边缘的直线
            {
                std::vector<int> xs, ys;
                for (int i = 0; i <= 40; i++) { xs.push_back(6 + i); ys.push_back(1); }
                run3("edgeTop", xs, ys, 4, true);
            }
            // 4) 1px 曲线
            {
                std::vector<int> xs, ys;
                for (int i = 0; i <= 40; i++) {
                    const double a = -1.2 + i * 0.06;
                    xs.push_back((int)std::lround(32 + 18 * std::cos(a)));
                    ys.push_back((int)std::lround(32 + 18 * std::sin(a)));
                }
                run3("arc1px", xs, ys, 0, false);
            }
            logline("b3 done");
        } else if (cmd == "bsim2") {
            // 临时画布上用与引擎相同的方向/笔程窗口模拟单笔, 统计 alpha 分档
            auto run = [&](const char* name, int segs, const int* xs, const int* ys,
                           int r, bool roundTrip) {
                Canvas tc; tc.create(64, 64, 1);
                tc.beginStampMask();
                auto seg = [&](int x0, int y0, int x1, int y1) {
                    const float dx = (float)(x1 - x0), dy = (float)(y1 - y0);
                    const float len = std::sqrt(dx * dx + dy * dy);
                    tc.setStampDir(dx, dy);
                    tc.setStampWindow(len + 1.0f, std::max(2.0f, (float)r * 2.0f + 1.0f));
                    raster::stampLine(tc, x0, y0, x1, y1, r, 0x80FF0000u, false, true);
                };
                for (int i = 1; i < segs; i++) seg(xs[i-1], ys[i-1], xs[i], ys[i]);
                if (roundTrip)
                    for (int i = segs - 2; i >= 0; i--) seg(xs[i+1], ys[i+1], xs[i], ys[i]);
                tc.endStrokeMask();
                int mn = 255, mx = 0, n128 = 0, over = 0, zero = 0;
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++) {
                        const int a = (int)((tc.getPixelL(0, x, y) >> 24) & 255u);
                        if (!a) { zero++; continue; }
                        if (a < mn) mn = a;
                        if (a > mx) mx = a;
                        if (a == 128) n128++; else over++;
                    }
                logline(std::string("b2 ") + name + " min=" + std::to_string(mn) +
                        " max=" + std::to_string(mx) + " a128=" + std::to_string(n128) +
                        " dark=" + std::to_string(over));
            };
            {
                const int xs[5] = {4, 18, 32, 46, 60};
                const int ys[5] = {10, 10, 10, 10, 10};
                run("straight1px", 5, xs, ys, 0, false);
                run("roundtrip1px", 5, xs, ys, 0, true);
            }
            {
                const int xs[5] = {8, 20, 32, 44, 56};
                const int ys[5] = {30, 30, 30, 30, 30};
                run("straightThick", 5, xs, ys, 4, false);
                run("roundtripThick", 5, xs, ys, 4, true);
            }
            {
                const int xs[4] = {10, 30, 30, 50};
                const int ys[4] = {50, 50, 20, 20};
                run("corner", 4, xs, ys, 3, false);
            }
            logline("b2 done");
        } else if (cmd == "alphalock") {
            int on = 0;
            if (ls >> on) {
                alphaLock_.store(on != 0);
                canvas_.setAlphaLock(on != 0);
                logline("alphalock " + std::to_string(on));
            } else {
                logline("alphalock engine=" + std::to_string(alphaLock_.load() ? 1 : 0) +
                        " canvas=" + std::to_string(canvas_.alphaLock() ? 1 : 0));
            }
        } else if (cmd == "redraw") {
            redrawReq_.store(true);
            logline("redraw queued");
        } else if (cmd == "dump") {
            int x0 = 0, y0 = 0, x1 = 0, y1 = 0; ls >> x0 >> y0 >> x1 >> y1;
            x0 = std::max(0, x0); y0 = std::max(0, y0);
            x1 = std::min(canvas_.width() - 1, x1); y1 = std::min(canvas_.height() - 1, y1);
            for (int y = y0; y <= y1; y++) {
                std::string row = "d ";
                for (int x = x0; x <= x1; x++) {
                    const uint32_t v = canvas_.getPixelL(canvas_.active(), x, y);
                    const int a = (int)(v >> 24) & 255;
                    if (!a) row.push_back('.');
                    else if (a < 85) row.push_back('a');
                    else if (a < 171) row.push_back('b');
                    else if (a < 255) row.push_back('c');
                    else row.push_back((v & 255u) > 128 ? 'R' : ((v >> 16) & 255u) > 128 ? 'B' : '#');
                }
                logline(row);
            }
        } else if (cmd == "dropredo") {
            redo_.clear();
            redoBytes_ = 0;
            logline("dropredo");
        } else if (cmd == "state") {
            char sb[256];            snprintf(sb, sizeof sb, "state tool=%d brushPx=%.2f alphaLock=%d canvasLock=%d pp=%d buildup=%d eraser=%d down=%d mv=%d stamp=%d up=%d kind=%d downLock=%d axis=%d gen=%d dx=%d dy=%d",
                     tool_.load(), (double)brushPx_.load(), alphaLock_.load() ? 1 : 0,
                     canvas_.alphaLock() ? 1 : 0, ppLine_.load() ? 1 : 0,
                     buildup_.load() ? 1 : 0, eraseMode_.load() ? 1 : 0,
                     cntDown_.load(), cntMove_.load(), cntStamp_.load(), cntUp_.load(),
                     dbgKind_.load(), dbgDownLock_.load(), dbgAxisSeg_.load(), dbgGenSeg_.load(),
                     dbgLastDx_.load(), dbgLastDy_.load());
            logline(sb);
        } else if (cmd == "brushsim") {
            auto blit = [&](Canvas& tc, int x0, int y0, int x1, int y1, int r,
                           uint32_t col, bool bup) {
                if (bup) tc.beginStampMask(); else tc.beginStrokeMask();
                raster::stampLine(tc, x0, y0, x1, y1, r, col, false, bup);
                tc.endStrokeMask();
            };
            auto alphaAt = [&](Canvas& tc, int x, int y) {
                return (int)((tc.getPixelL(0, x, y) >> 24) & 255u);
            };
            auto walk = [](int x0, int y0, int x1, int y1, auto&& cb) {
                int dx = std::abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
                int dy = -std::abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
                int err = dx + dy;
                for (;;) {
                    cb(x0, y0);
                    if (x0 == x1 && y0 == y1) break;
                    const int e2 = 2 * err;
                    if (e2 >= dy) { err += dy; x0 += sx; }
                    if (e2 <= dx) { err += dx; y0 += sy; }
                }
            };
            // A) 本层蒙版 + 水平不透明笔: 只应写已有像素
            {
                Canvas tc; tc.create(64, 64, 1);
                raster::fillRect(tc, 0, 0, 32, 64, 0xFFFF0000u);
                tc.setAlphaLock(true);
                raster::stampLine(tc, 0, 32, 63, 32, 0, 0xFF0000FFu, false, false);
                int right = 0, left = 0;
                for (int x = 0; x < 64; x++) {
                    const uint32_t v = tc.getPixelL(0, x, 32);
                    if ((v & 255u) == 255u) { if (x < 32) left++; else right++; }
                }
                logline("bs lockH rightBlue=" + std::to_string(right) + " leftBlue=" + std::to_string(left));
            }
            // B) 本层蒙版 + 垂直不透明笔
            {
                Canvas tc; tc.create(64, 64, 1);
                raster::fillRect(tc, 0, 0, 64, 32, 0xFFFF0000u);
                tc.setAlphaLock(true);
                raster::stampLine(tc, 32, 0, 32, 63, 0, 0xFF0000FFu, false, false);
                int below = 0, above = 0;
                for (int y = 0; y < 64; y++) {
                    const uint32_t v = tc.getPixelL(0, 32, y);
                    if ((v & 255u) == 255u) { if (y < 32) above++; else below++; }
                }
                logline("bs lockV belowBlue=" + std::to_string(below) + " aboveBlue=" + std::to_string(above));
            }
            // C) 本层蒙版 + 斜线不透明笔
            {
                Canvas tc; tc.create(64, 64, 1);
                raster::fillRect(tc, 0, 0, 32, 64, 0xFFFF0000u);
                tc.setAlphaLock(true);
                raster::stampLine(tc, 0, 0, 63, 63, 0, 0xFF0000FFu, false, false);
                int right = 0, left = 0;
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++) {
                        const uint32_t v = tc.getPixelL(0, x, y);
                        if ((v & 255u) == 255u) { if (x < 32) left++; else right++; }
                    }
                logline("bs lockD rightBlue=" + std::to_string(right) + " leftBlue=" + std::to_string(left));
            }
            // D) 叠色 + 半透明 1px, 沿画布上边缘水平一笔: alpha 应恒等于 128
            {
                Canvas tc; tc.create(64, 64, 1);
                blit(tc, 0, 0, 63, 0, 0, 0x80FF0000u, true);
                int mn = 255, mx = 0, n128 = 0, other = 0, zero = 0;
                for (int x = 0; x < 64; x++) {
                    const int a = alphaAt(tc, x, 0);
                    if (a < mn) mn = a; if (a > mx) mx = a;
                    if (a == 128) n128++; else if (a == 0) zero++; else other++;
                }
                logline("bs bupEdgeH min=" + std::to_string(mn) + " max=" + std::to_string(mx) +
                        " a128=" + std::to_string(n128) + " other=" + std::to_string(other) +
                        " zero=" + std::to_string(zero));
            }
            // E) 叠色 + 半透明 1px 斜线(内部): 沿路径统计 alpha
            {
                Canvas tc; tc.create(64, 64, 1);
                blit(tc, 0, 0, 50, 30, 0, 0x80FF0000u, true);
                int mn = 255, mx = 0, a128 = 0, other = 0, zero = 0;
                walk(0, 0, 50, 30, [&](int x, int y) {
                    const int a = alphaAt(tc, x, y);
                    if (a < mn) mn = a; if (a > mx) mx = a;
                    if (a == 128) a128++; else if (a == 0) zero++; else other++;
                });
                logline("bs bupDiag min=" + std::to_string(mn) + " max=" + std::to_string(mx) +
                        " a128=" + std::to_string(a128) + " other=" + std::to_string(other) +
                        " zero=" + std::to_string(zero));
            }
            // F) 叠色 + 半透明 1px, 模拟快速拖动(稀疏事件补线)
            {
                Canvas tc; tc.create(64, 64, 1);
                tc.beginStampMask();
                const int px[5] = {0, 14, 27, 40, 55};
                const int py[5] = {2, 9, 14, 22, 28};
                for (int i = 1; i < 5; i++)
                    raster::stampLine(tc, px[i-1], py[i-1], px[i], py[i], 0, 0x80FF0000u, false, true);
                tc.endStrokeMask();
                int mn = 255, mx = 0, a128 = 0, other = 0, zero = 0;
                for (int i = 1; i < 5; i++)
                    walk(px[i-1], py[i-1], px[i], py[i], [&](int x, int y) {
                        const int a = alphaAt(tc, x, y);
                        if (a < mn) mn = a; if (a > mx) mx = a;
                        if (a == 128) a128++; else if (a == 0) zero++; else other++;
                    });
                logline("bs bupSeg min=" + std::to_string(mn) + " max=" + std::to_string(mx) +
                        " a128=" + std::to_string(a128) + " other=" + std::to_string(other) +
                        " zero=" + std::to_string(zero));
            }
            // G) 本层蒙版 + 叠色 + 半透明: 右侧应无像素, 左侧 alpha 稳定
            {
                Canvas tc; tc.create(64, 64, 1);
                raster::fillRect(tc, 0, 0, 32, 64, 0xFFFF0000u);
                tc.setAlphaLock(true);
                blit(tc, 0, 32, 63, 32, 0, 0x800000FFu, true);
                int right = 0, mn = 255, mx = 0;
                for (int x = 0; x < 32; x++) {
                    const int a = alphaAt(tc, x, 32);
                    if (a < mn) mn = a; if (a > mx) mx = a;
                }
                for (int x = 32; x < 64; x++) if (alphaAt(tc, x, 32)) right++;
                logline("bs bupLock rightPx=" + std::to_string(right) +
                        " leftMin=" + std::to_string(mn) + " leftMax=" + std::to_string(mx));
            }
            // H) 本层蒙版 + 平涂(叠色关) + 半透明
            {
                Canvas tc; tc.create(64, 64, 1);
                raster::fillRect(tc, 0, 0, 32, 64, 0xFFFF0000u);
                tc.setAlphaLock(true);
                blit(tc, 0, 32, 63, 32, 0, 0x800000FFu, false);
                int right = 0, mn = 255, mx = 0;
                for (int x = 0; x < 32; x++) {
                    const int a = alphaAt(tc, x, 32);
                    if (a < mn) mn = a; if (a > mx) mx = a;
                }
                for (int x = 32; x < 64; x++) if (alphaAt(tc, x, 32)) right++;
                logline("bs flatLock rightPx=" + std::to_string(right) +
                        " leftMin=" + std::to_string(mn) + " leftMax=" + std::to_string(mx));
            }
            // I) 叠色 + 半透明 1px 竖线贴左边缘
            {
                Canvas tc; tc.create(64, 64, 1);
                blit(tc, 0, 0, 0, 63, 0, 0x80FF0000u, true);
                int mn = 255, mx = 0, a128 = 0, other = 0, zero = 0;
                for (int y = 0; y < 64; y++) {
                    const int a = alphaAt(tc, 0, y);
                    if (a < mn) mn = a; if (a > mx) mx = a;
                    if (a == 128) a128++; else if (a == 0) zero++; else other++;
                }
                logline("bs bupEdgeV min=" + std::to_string(mn) + " max=" + std::to_string(mx) +
                        " a128=" + std::to_string(a128) + " other=" + std::to_string(other) +
                        " zero=" + std::to_string(zero));
            }
            logline("bs done");
        } else if (cmd == "brush") {
            int bp = 1; std::string bc; ls >> bp >> bc;
            setBrush((float)bp, hexu(bc));
            logline("brush " + std::to_string(bp) + " " + bc);
        } else if (cmd == "perfect") {
            int on = 0; ls >> on;
            ppLine_.store(on != 0);
            logline("perfect " + std::to_string(on));
        } else if (cmd == "buildup") {
            int bo = 0; ls >> bo;
            setBrushBuildup(bo != 0);
            logline("buildup " + std::to_string(bo));
        } else if (cmd == "touch") {
            int ta = 0; float tx = 0, ty = 0; ls >> ta >> tx >> ty;
            touch(ta, tx, ty);
            logline("touch " + std::to_string(ta));
        } else if (cmd == "touchup") {
            int tx = 0, ty = 0; ls >> tx >> ty;
            touch(2, (float)tx, (float)ty);
            logline("touchup queued");
        } else if (cmd == "fop") {
            int op2 = 0, arg2 = 0; ls >> op2 >> arg2;
            requestFrameOp(op2, arg2);
            logline("fop " + std::to_string(op2) + " " + std::to_string(arg2));
        } else if (cmd == "save") {
            std::string p2; ls >> p2;
            saveSnapshotNow(p2, false);
            usleep(900000);
            long fsz = 0;
            FILE* pf = fopen(p2.c_str(), "rb");
            if (pf) { fseek(pf, 0, SEEK_END); fsz = ftell(pf); fclose(pf); }
            logline("save bytes=" + std::to_string(fsz));
        } else if (cmd == "saveforce") {
            std::string p2; ls >> p2;
            lastSavedRev_.store(0);
            lastSavedFrameRev_ = 0;
            saveSnapshotNow(p2, false);
            usleep(900000);
            long fsz = 0;
            FILE* pf = fopen(p2.c_str(), "rb");
            if (pf) { fseek(pf, 0, SEEK_END); fsz = ftell(pf); fclose(pf); }
            logline("saveforce bytes=" + std::to_string(fsz));
        } else if (cmd == "load") {
            std::string p2; ls >> p2;
            loadSnapshotNow(p2);
            logline("load frame=" + std::to_string(frame_) + "/" + std::to_string(frameData_.size()) +
                    " fps=" + std::to_string(playFps_.load()) +
                    " lastcut=" + std::to_string(sel_.lastW()) + "x" + std::to_string(sel_.lastH()));
        } else if (cmd == "frames") {
            logline("frames n=" + std::to_string(frameData_.size()) + " idx=" + std::to_string(frame_) +
                    " fps=" + std::to_string(playFps_.load()) +
                    " loop=" + std::to_string(loopStart_.load()) + "-" + std::to_string(loopEnd_.load()) +
                    " layervis=" + std::to_string(canvas_.layerVisible(canvas_.active()) ? 1 : 0));
        } else if (cmd == "levelshist") {
            int ch = 0; ls >> ch;
            int h[256];
            canvas_.levelsHistogram(canvas_.active(), ch, h);
            long long sum = 0; int mx = 0, mi = 0;
            for (int i = 0; i < 256; i++) { sum += h[i]; if (h[i] > mx) { mx = h[i]; mi = i; } }
            logline("levelshist ch=" + std::to_string(ch) + " sum=" + std::to_string(sum) +
                    " max=" + std::to_string(mx) + "@" + std::to_string(mi));
        } else if (cmd == "levelsapply") {
            int ch = 0, bl = 0, wh = 255, ob = 0, ow = 255; float gm = 1.0f;
            ls >> ch >> bl >> wh >> gm >> ob >> ow;
            applyLevelsImpl(ch, bl, wh, gm, ob, ow);
            logline("levelsapply ch=" + std::to_string(ch));
        } else if (cmd == "exportpsd") {
            std::string p2; ls >> p2;
            PsdResult r = exportPsd(canvas_, p2);
            if (r.ok) logline("exportpsd ok bytes=" + std::to_string(r.bytes) + " ms=" + std::to_string(r.ms));
            else logline("exportpsd err " + r.err);
        } else if (cmd == "export") {
            std::string p2; int sc = 1; ls >> p2 >> sc;
            // 同步导出(调试脚本运行在渲染线程, 不能走 exportPngFile 的异步等待)
            ExportResult r = exportPng(canvas_, p2, sc);
            char b[192];
            if (r.ok) snprintf(b, sizeof b, "export ok %dx%d bytes=%lld ms=%ld", r.w, r.h, r.bytes, r.ms);
            else snprintf(b, sizeof b, "export err %s", r.err.c_str());
            logline(b);
        } else {
            logline("unknown " + cmd);
        }
    }
    logline("done");
    refreshFrameThumbs();
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
}

void Engine::drainInput() {
    if (dbgPending_.exchange(false)) runDebugScript();
    // 形状渐变预览中: 切工具/换形状/换层/画布尺寸变了 → 先还原, 避免预览像素被后续操作混进撤销
    if (gradActive_ && (tool_.load() != 4 ||
                        (shapeType_.load() != 9 && shapeType_.load() != 10) ||
                        canvas_.active() != gradLayer_ ||
                        canvas_.width() != gradCw_ || canvas_.height() != gradCh_)) {
        gradCancel();
    }
    // 渐变范围/角度/透明位置滑条: 值变了每帧最多重画一次预览
    if (gradActive_) {
        const int nr = gradRangeA_.load();
        const int na = gradAngleA_.load();
        const int np = gradPosA_.load();
        bool repaint = false;
        if (nr != gradRange_) { gradRange_ = nr; repaint = true; }
        if (na != gradAngle_) {
            gradAngle_ = na;
            if (gradShape_ == 0) gradPreset_ = -1;   // 角度滑条 = 自定义方向, 预设取消高亮
            repaint = true;
        }
        if (np != gradPos_) { gradPos_ = np; repaint = true; }
        if (repaint) gradApplyPreview();
    }
    sel_.tick();
    closeSelMoveRecordIfDone();
    if (measOn_ && (tool_.load() != 4 || shapeType_.load() != 6)) {
        measOn_ = false;
        measMagShown_ = false;
        measFixed_ = false;
        measSetStartPending_ = false;
        screenFull_ = true;
    }
    if (tool_.load() == 4 && shapeType_.load() == 6 && measOn_ &&
        (lastAct_.load() == 0 || lastAct_.load() == 1)) {
        if (!measMagShown_ &&
            std::chrono::duration<double>(std::chrono::steady_clock::now() - measDownT_).count() >= 0.3) {
            measMagShown_ = true;
            screenFull_ = true;
        }
    } else if (measMagShown_) {
        // 放大镜消失必须整屏重画一次, 否则镜头圆残留在屏幕缓冲
        measMagShown_ = false;
        screenFull_ = true;
    }
    if (lvPending_.exchange(false)) {
        int h[256];
        canvas_.levelsHistogram(canvas_.active(), lvChannel_.load(), h);
        {
            std::lock_guard<std::mutex> lk(lvMu_);
            memcpy(lvHist_, h, sizeof(h));
            lvDone_ = true;
        }
        lvCv_.notify_all();
    }
    {
        std::lock_guard<std::mutex> lk(thumbMu_);
        if (frameThumbs_.empty()) { /* 首帧缩略图在锁外生成 */ }
    }
    if (frameThumbs_.empty()) refreshFrameThumbs();
    canvas_.setAlphaLock(alphaLock_.load());
    {
        const bool sm = stretchModeReq_.load();
        if (sel_.stretchMode() != sm) { sel_.setStretchMode(sm); screenFull_ = true; }
    }
    {
        const bool pm = perspectiveReq_.load();
        if (sel_.perspectiveMode() != pm) { sel_.setPerspectiveMode(pm); screenFull_ = true; }
    }
    {
        const int ssh = selShape_.load();
        if (ssh != appliedSelShape_) { sel_.setSelShape(ssh); appliedSelShape_ = ssh; screenFull_ = true; }
    }
    if (tool_.load() != 4) {
        if (measOn_) {
            markScreenRect(std::min(measX0_, measX1_) - 3, std::min(measY0_, measY1_) - 3,
                           std::max(measX0_, measX1_) + 3, std::max(measY0_, measY1_) + 3);
            measOn_ = false;
            measFixed_ = false;
            measSetStartPending_ = false;
        }
        if (!lasso_.empty() || polyActive_ || leafActive_ || shActive_ || !shPrevIdx_.empty()) {
            cancelShapePreview();   // 预览是画布像素: 切走工具先恢复掉, 不留半成品
        }
    }
    std::vector<Ev> evs;
    {
        std::lock_guard<std::mutex> lk(mu_);
        evs.swap(queue_);
    }
    for (size_t ei = 0; ei < evs.size(); ei++) {
        auto& e = evs[ei];
        // 后台导出/切帧已让画布进入只读: 剩余事件原序放回队列, 任务结束后再处理
        if (canvasBusy_.load()) {
            std::lock_guard<std::mutex> lk(mu_);
            queue_.insert(queue_.begin(), evs.begin() + (long)ei, evs.end());
            break;
        }
        selStateRev_++;   // 任何输入都可能改选区/浮层/变换; 让快照知道要存 v3 状态
        if (e.action == 52) {
            uint8_t lbs[4][256];
            if (lvAllPending_.exchange(false)) {
                { std::lock_guard<std::mutex> lk(lvAllMu_); memcpy(lbs, lvAllLut_, sizeof(lbs)); }
                applyLevelsAllImpl(lbs);
            }
            continue;
        }
        if (e.action == 51) {   // 后台返回: 恢复缩放/平移(排在 snapshot load 之后)
            viewAnimActive_ = false;
            display_.setView(e.x, e.y, e.z);
            screenFull_ = true;
            continue;
        }
        if (e.action == 50) {   // 双指开始: 取消未完成框选/套索/长按, 保留已完成选区
            sel_.cancelGesture();
            continue;
        }
        if (e.action == 49) {   // 色阶: 当前层(可撤销)
            const int p1 = (int)std::lround(e.x), p2 = (int)std::lround(e.y);
            const int ch = (p1 >> 16) & 255;
            applyLevelsImpl(ch, p1 & 255, (p1 >> 8) & 255, e.z, p2 & 255, (p2 >> 8) & 255);
            continue;
        }
        // 2.5D 查看模式: 单指拖动 = 旋转视角(旧 _yaw25D += dx*0.5, 33ms节流), 不绘画不框选
        {
            const auto& g25 = raster::mode25D();
            if (g25.on && e.action >= 0 && e.action <= 3) {
                if (e.action == 0) {
                    yawDrag25_ = true;
                    yawLastX25_ = e.x;
                    yawLastT25_ = 0;
                    continue;
                }
                if (e.action == 1 && yawDrag25_) {
                    const float dx = e.x - yawLastX25_;
                    yawLastX25_ = e.x;
                    const int64_t now = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count();
                    if (now - yawLastT25_ >= 33) {
                        yawLastT25_ = now;
                        // 手往右拖 = 画布逆时针转(与手指方向一致); 之前是 +dx 导致反向
                        float ny = g25.yawDeg - dx * 0.5f;
                        ny = std::fmod(ny, 360.f);
                        if (ny < 0.f) ny += 360.f;
                        applyYaw25D(ny);
                    }
                    continue;
                }
                if (e.action == 2 || e.action == 3) {
                    yawDrag25_ = false;
                    continue;
                }
            }
        }
        if (e.action == 4) {
            viewAnimActive_ = false;
            sel_.noteViewChange();
            display_.zoomAt(e.x, e.y, e.z);
            screenFull_ = true;
            continue;
        }
        if (e.action == 5) { viewAnimActive_ = false; sel_.noteViewChange(); display_.panBy(e.x, e.y); screenFull_ = true; continue; }
        if (e.action == 44) { viewAnimActive_ = false; display_.beginZoomGesture(e.x, e.y); continue; }
        if (e.action == 45) { viewAnimActive_ = false; sel_.noteViewChange(); display_.zoomGestureTo(e.x, e.y, e.z); screenFull_ = true; continue; }
        if (e.action == 46) { display_.endZoomGesture(); screenFull_ = true; continue; }
        // 测距模式按钮(旧 Measure.cs: 主动设置起点/中心设置起点/回到手指起点)
        if (e.action == 70) { measFixed_ = e.x > 0.5f; if (!measFixed_) measSetStartPending_ = false; continue; }
        if (e.action == 71) {
            if (measOn_) {
                measX0_ = (measX0_ + measX1_) / 2;
                measY0_ = (measY0_ + measY1_) / 2;
                measFixed_ = true;
                measSetStartPending_ = false;
                screenFull_ = true;   // 线/方框/十字/标签整体重画
            }
            continue;
        }
        if (e.action == 72) { measSetStartPending_ = true; continue; }
        // 多边形套索浮条(旧 pm615): 撤销点/重置点/完成/取消
        if (e.action == 75 && polyActive_) {   // 撤销点
            shapePrevRestore();
            const int n = (int)poly_.size() / 2;
            if (n <= 1) {
                poly_.clear();
                polyActive_ = false;
                polyHeld_ = false;
                endStrokeRecord();
            } else {
                poly_.resize((size_t)(n - 1) * 2);
                std::vector<int> xy;
                raster::polylinePixels(poly_, false, xy);
                if (xy.empty() && !poly_.empty()) { xy.push_back(poly_[0]); xy.push_back(poly_[1]); }
                if (!xy.empty()) shapePrevPaint(xy, brushColor_.load(), canvas_.active());
            }
            continue;
        }
        if (e.action == 76) {   // 重置点: 清空全部顶点, 工具保持
            if (polyActive_) { shapePrevRestore(); canvas_.endRecord(); }
            polyActive_ = false;
            polyHeld_ = false;
            poly_.clear();
            continue;
        }
        if (e.action == 77) {   // 完成: ≥3顶点扫描线填充
            if (polyActive_) {
                const int n = (int)poly_.size() / 2;
                shapePrevRestore();
                if (n >= 3)
                    raster::fillPolygon(canvas_, poly_, eraseMode_.load() ? 0u : brushColor_.load(),
                                        eraseMode_.load());
                endStrokeRecord();
                polyActive_ = false;
                polyHeld_ = false;
                poly_.clear();
            }
            continue;
        }
        if (e.action == 79) { cancelShapePreview(); continue; }   // 切标签/保存前取消未完成形状
        if (e.action == 90) {   // 形状渐变: 换预设(实时预览)
            if (gradActive_) {
                const int maxP = gradShape_ == 1 ? 1 : 3;
                gradPreset_ = std::max(0, std::min((int)std::lround(e.x), maxP));
                if (gradShape_ == 0) {
                    // 方形预设 = 4 个默认角度(0=上下 1=下上 2=左右 3=右左)
                    static const int kAngle[4] = {90, 270, 0, 180};
                    gradAngle_ = kAngle[gradPreset_];
                    gradAngleA_.store(gradAngle_);
                }
                gradApplyPreview();
            }
            continue;
        }
        if (e.action == 91) { if (gradActive_) gradCommit(); continue; }   // 形状渐变: 应用
        if (e.action == 92) { gradCancel(); continue; }                    // 形状渐变: 取消
        if (e.action == 80) { parkTabHistory(); continue; }        // 切标签前: 停放当前标签撤销栈
        if (e.action == 81) { setTabHistoryKey((int)std::lround(e.x)); continue; }  // 载入后: 挂回目标任务栈
        if (e.action == 82) { dropTabHistory((int)std::lround(e.x)); continue; }    // 关闭标签: 丢弃该标签栈
        if (e.action == 78) {   // 取消
            if (polyActive_) { shapePrevRestore(); canvas_.endRecord(); }
            polyActive_ = false;
            polyHeld_ = false;
            poly_.clear();
            continue;
        }
        if (e.action == 47) {   // 双指确认: 回滚第一指刚落下的误触笔画(不进撤销栈, 不动 redo)
            if (bucketPending_) { bucketPending_ = false; continue; }   // 油漆桶还没落, 双指直接丢弃
            if (canvas_.recording()) {
                auto rec = canvas_.endRecord();
                if (!rec.empty()) {
                    auto back = canvas_.applyRecord(rec);   // 应用原快照=回到落笔前
                    (void)back;
                    refreshFrameThumbs();
                    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
                    screenFull_ = true;
                    LOGI("pinch: rollback first-touch stroke");
                }
            }
            continue;
        }
        if (e.action == 6) {
            lastInputT_ = std::chrono::steady_clock::now();   // 落定瞬间: 给用户 400ms 反悔期(可再拖)
            if (sel_.active() && !sel_.floating()) { sel_.captureRect(); lastCutSkip_.store(sel_.lastCutSkipped()); }
            sel_.cut();   // 浮起/再按放下; 落像素由选区 edit-guard 记入撤销, 不再清空历史
            continue;
        }
        if (e.action == 11) { if (gradActive_) gradCancel(); undo(); continue; }
        if (e.action == 12) { if (gradActive_) gradCancel(); redo(); continue; }
        if (e.action == 13) { display_.setOverlay(e.x > 0.5f, e.y > 0.5f); continue; }
        if (e.action == 16) { sel_.deselect(); continue; }
        if (e.action == 17) { sel_.selectAll(); continue; }
        if (e.action == 18) { viewAnimActive_ = false; display_.resetView(); screenFull_ = true; continue; }
        if (e.action == 19 || e.action == 20 || e.action == 21) {
            // 整画布操作: 连空块一起快照(翻转会把内容搬到原本空的块), 完成后落一条撤销+回放步骤
            beginStrokeRecord();
            canvas_.snapshotAllTiles();
            if (e.action == 19) canvas_.flipAllLayers(true);
            else if (e.action == 20) canvas_.flipAllLayers(false);
            else canvas_.grayscaleAllLayers();
            endStrokeRecord();
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            continue;
        }
        if (e.action == 22) { sel_.captureRect(); continue; }
        if (e.action == 23) {
            float vcx = 0.f, vcy = 0.f;
            display_.viewCenterCanvas(&vcx, &vcy);
            beginStrokeRecord();
            sel_.pasteLastCutAtViewCenter(vcx, vcy);
            endStrokeRecord();
            screenFull_ = true;
            continue;
        }
        if (e.action == 24) {
            std::vector<uint32_t> px; int w = 0, h = 0; bool asText = false;
            {
                std::lock_guard<std::mutex> lk(importMu_);
                if (importPending_) {
                    px.swap(importPx_); w = importW_; h = importH_;
                    asText = importText_;
                    importPending_ = false; importText_ = false;
                }
            }
            if (w > 0 && h > 0) {
                if (asText) {
                    // 文字: 直接进入"裁剪中"的复制浮层, 面板立刻有确认/取消
                    sel_.importTextAtViewCenter(w, h, px.data());
                } else {
                    beginStrokeRecord();
                    sel_.importImage(w, h, px.data());   // 原尺寸浮层: 超出画布部分保留不显示
                    endStrokeRecord();
                }
            }
            continue;
        }
        if (e.action == 85) {   // 大图分块导入: 直接写当前层(替换), 不生成浮层/裁剪框
            std::vector<uint32_t> px; int ix = 0, iy = 0, iw = 0, ih = 0;
            {
                std::lock_guard<std::mutex> lk(importMu_);
                if (impRegPending_) {
                    px.swap(impRegPx_);
                    ix = impRegX_; iy = impRegY_; iw = impRegW_; ih = impRegH_;
                    impRegPending_ = false;
                }
            }
            if (iw > 0 && ih > 0) {
                const int cw = canvas_.width(), ch = canvas_.height();
                const int x0 = std::max(0, ix), y0 = std::max(0, iy);
                const int x1 = std::min(cw, ix + iw), y1 = std::min(ch, iy + ih);
                if (x1 > x0 && y1 > y0) {
                    const int l = canvas_.active();
                    const int tx = canvas_.tilesX();
                    for (int yy = y0; yy < y1; yy++) {
                        const int trow = (yy >> Canvas::TILE_SHIFT) * tx;
                        const uint32_t* src = px.data() + (size_t)(yy - iy) * iw;
                        for (int xx = x0; xx < x1; ) {
                            const int run = std::min(x1 - xx, Canvas::TILE - (xx & (Canvas::TILE - 1)));
                            uint32_t* t = canvas_.tileForWrite(l, trow + (xx >> Canvas::TILE_SHIFT));
                            uint32_t* dst = t + ((size_t)(yy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT)
                                              + (xx & (Canvas::TILE - 1));
                            memcpy(dst, src + (xx - ix), (size_t)run * 4);
                            xx += run;
                        }
                    }
                    canvas_.markDirtyRect(x0, y0, x1 - 1, y1 - 1);
                }
            }
            continue;
        }
        if (e.action == 86) {   // 叠层切片导入: 一次性替换为多图层/多帧工程
            doSliceImport();
            continue;
        }
        if (e.action == 87) {   // 单张 sheet 导入: 裁剪帧/并列帧/叠层
            doSheetImport();
            continue;
        }
        if (e.action == 34) { sel_.copyRect(); lastCutSkip_.store(sel_.lastCutSkipped()); continue; }
        if (e.action == 54) {   // 复制按钮: 当前选区原地生成可拖动副本(不擦源)
            sel_.duplicateSelection();
            screenFull_ = true;
            continue;
        }
        if (e.action == 55) {   // 小地图面板开关: 打开时立即生成 192 采样缩略图
            const bool on = e.x > 0.5f;
            minimapActive_.store(on, std::memory_order_relaxed);
            if (on) {
                refreshMinimapThumb();
                thumbRev_.fetch_add(1, std::memory_order_relaxed);
            }
            continue;
        }
        if (e.action == 56) {   // 小地图导航: 点击=平滑动画, 拖动=即时跟手
            const float z = display_.zoom();
            if (z > 0.f) {
                const float tx = e.x - (float)display_.viewW() * 0.5f / z;
                const float ty = e.y - (float)display_.viewH() * 0.5f / z;
                if (e.z > 0.5f) {
                    viewAnimTX_ = tx;
                    viewAnimTY_ = ty;
                    viewAnimZoom_ = false;
                    viewAnimTau_ = 0.07f;
                    viewAnimLastT_ = std::chrono::steady_clock::now();
                    viewAnimActive_ = true;
                } else {
                    viewAnimActive_ = false;
                    display_.setView(z, tx, ty);
                    screenFull_ = true;
                }
            }
            continue;
        }
        if (e.action == 62) {   // 任意帧重排(from→to, 插入式)
            const int from = (int)std::lround(e.x), to = (int)std::lround(e.y);
            if (from != to && from >= 0 && from < (int)frameData_.size()) {
                pushDocUndo();
                storeFrameData();
                const int dst = std::max(0, std::min(to, (int)frameData_.size() - 1));
                auto d = std::move(frameData_[(size_t)from]);
                frameData_.erase(frameData_.begin() + from);
                frameData_.insert(frameData_.begin() + dst, std::move(d));
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    if (from < (int)frameThumbs_.size() && dst < (int)frameThumbs_.size()) {
                        auto th = std::move(frameThumbs_[(size_t)from]);
                        frameThumbs_.erase(frameThumbs_.begin() + from);
                        frameThumbs_.insert(frameThumbs_.begin() + dst, std::move(th));
                    }
                }
                if (frame_ == from) frame_ = dst;
                else {
                    int n = frame_ > from ? frame_ - 1 : frame_;
                    if (n >= dst) n++;
                    frame_ = n;
                }
                frame_ = std::max(0, std::min(frame_, (int)frameData_.size() - 1));
                frameIdx_.store(frame_);
                frameCount_.store((int)frameData_.size());
                loadFrameData(frame_);
                frameRev_.fetch_add(1);
                onionGen_.fetch_add(1);
                thumbRev_.fetch_add(1);
                screenFull_ = true;
                recordReplayFrame();
            }
            continue;
        }
        if (e.action == 57) {   // 时间轴循环区间(DAW): 直接设起点/终点
            const int fc = std::max(1, frameCount_.load());
            int a = std::max(0, std::min((int)std::lround(e.x), fc - 1));
            int b = std::max(0, std::min((int)std::lround(e.y), fc - 1));
            loopStart_.store(std::min(a, b));
            loopEnd_.store(std::max(a, b));
            continue;
        }
        if (e.action == 58) {   // 帧数据互换
            const int a = (int)std::lround(e.x), b = (int)std::lround(e.y);
            if (a != b && a >= 0 && b >= 0 && a < (int)frameData_.size() && b < (int)frameData_.size()) {
                pushDocUndo();
                storeFrameData();
                std::swap(frameData_[(size_t)a], frameData_[(size_t)b]);
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    if (a < (int)frameThumbs_.size() && b < (int)frameThumbs_.size())
                        std::swap(frameThumbs_[(size_t)a], frameThumbs_[(size_t)b]);
                }
                loadFrameData(frame_);
                frameRev_.fetch_add(1);
                onionGen_.fetch_add(1);
                thumbRev_.fetch_add(1);
                screenFull_ = true;
                recordReplayFrame();
            }
            continue;
        }
        if (e.action == 59) {   // 图层重排到任意位置
            const int from = (int)std::lround(e.x), to = (int)std::lround(e.y);
            if (from != to && from >= 0 && to >= 0 && from < canvas_.layers() && to < canvas_.layers()) {
                pushDocUndo();
                canvas_.moveLayer(from, to);
                rewriteFramesMoveLayer(from, to);
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    for (auto& ft : frameThumbs_) {
                        if (from < (int)ft.size() && to < (int)ft.size()) {
                            auto row = std::move(ft[(size_t)from]);
                            ft.erase(ft.begin() + from);
                            ft.insert(ft.begin() + to, std::move(row));
                        }
                    }
                }
                layerCount_.store(canvas_.layers());
                activeLayer_.store(canvas_.active());
                mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
                onionGen_.fetch_add(1);
                thumbRev_.fetch_add(1);
                screenFull_ = true;
                recordReplayFrame();
            }
            continue;
        }
        if (e.action == 60) {   // 播放画笔: 播放中允许绘画并保存当前帧
            playBrush_.store(e.x > 0.5f);
            continue;
        }
        if (e.action == 61) {   // 时间轴开合: 同时动画 zoom + pan
            viewAnimTZ_ = e.x;
            viewAnimTPX_ = e.y;
            viewAnimTPY_ = e.z;
            viewAnimZoom_ = true;
            viewAnimTau_ = 0.25f;
            viewAnimLastT_ = std::chrono::steady_clock::now();
            viewAnimActive_ = true;
            continue;
        }
        if (e.action == 35) {
            beginStrokeRecord();
            sel_.deleteRect();
            endStrokeRecord();
            continue;
        }
        if (e.action == 36) {
            beginStrokeRecord();
            sel_.commitTransform();
            endStrokeRecord();
            sel_.finishCommit();
            sel_.deselect();
            screenFull_ = true;
            continue;
        }
        if (e.action == 37) {
            sel_.resetTransform();
            sel_.cancelFloat();
            sel_.deselect();
            screenFull_ = true;
            continue;
        }
        if (e.action == 86) {   // 抽屉: 颜色调整(可预览, commit=应用)
            float h, sa, b, a, blur; bool commit, pending;
            {
                std::lock_guard<std::mutex> lk(selOpMu_);
                h = selAdjH_; sa = selAdjS_; b = selAdjB_; a = selAdjA_; blur = selAdjBlur_;
                commit = selAdjCommit_; pending = selAdjPending_; selAdjPending_ = false;
            }
            // 拖动时 JNI 每个指针事件都会入队; 参数是"最新值覆盖"语义, 重复请求直接跳过
            if (!pending) continue;
            if (sel_.active()) {
                beginStrokeRecord();
                sel_.materializeExt();
                sel_.adjustHSBA(h, sa, b, a, blur);
                if (commit) { sel_.commitFloat(); sel_.deselect(); }
                endStrokeRecord();
            }
            if (commit) screenFull_ = true;
            continue;
        }
        if (e.action == 87) {   // 抽屉: 像素描边(外围线条)
            int mode, size, dir; bool outer, commit, pending; float depth; uint32_t color;
            {
                std::lock_guard<std::mutex> lk(selOpMu_);
                mode = selStMode_; size = selStSize_; dir = selStDir_;
                outer = selStOuter_; depth = selStDepth_; color = selStColor_;
                commit = selStCommit_; pending = selStrokePending_; selStrokePending_ = false;
            }
            if (!pending) continue;
            if (sel_.active() && mode > 0) {
                beginStrokeRecord();
                sel_.materializeExt();
                sel_.applyStroke(mode, size, dir, outer, depth, color);
                if (commit) { sel_.commitFloat(); sel_.deselect(); }
                endStrokeRecord();
            } else if (sel_.active() && mode == 0) {
                // “无描边”只是取消描边预览: 有原图才还原, 否则不要无谓地取材/抠空画布
                if (sel_.extActive() || commit) {
                    beginStrokeRecord();
                    sel_.restoreOrig();
                    if (commit) { sel_.commitFloat(); sel_.deselect(); }
                    endStrokeRecord();
                }
            }
            if (commit) screenFull_ = true;
            continue;
        }
        if (e.action == 88) {   // 取消还原
            {
                std::lock_guard<std::mutex> lk(selOpMu_);
                selRestorePending_ = false;
            }
            if (sel_.active()) {
                beginStrokeRecord();
                sel_.materializeExt();
                sel_.restoreOrig();
                sel_.commitFloat();
                sel_.deselect();
                endStrokeRecord();
            }
            screenFull_ = true;
            continue;
        }
        if (e.action == 95) {   // 切换抽屉页: 丢弃当前页预览, 保留选区
            if (sel_.active()) sel_.cancelExtPreview();
            screenFull_ = true;
            continue;
        }
        if (e.action == 96) {   // 汇入文字/图像: 浮层放到视口中心
            if (sel_.active()) sel_.centerFloatAtViewCenter();
            screenFull_ = true;
            continue;
        }
        if (e.action == 89) {   // 图层模式/平铺参数/混合模式
            int layer = 0, mode = 0, tw = 16, th = 16, ox = 0, oy = 0, lock = 0, blend = 0;
            bool sg = false, indep = false;
            uint32_t col = 0x66FFFFFFu;
            {
                std::lock_guard<std::mutex> lk(layerOpMu_);
                layer = lypLayer_; mode = lypMode_; tw = lypW_; th = lypH_;
                ox = lypOX_; oy = lypOY_; sg = lypShowGrid_; col = lypColor_;
                indep = lypIndep_; lock = lypLock_; blend = lypBlend_; lypPending_ = false;
            }
            if (layer >= 0 && layer < canvas_.layers()) {
                const bool merged = metaCoalesce(2000 + layer);   // 平铺参数拖动合并成一步
                if (!merged) pushMetaUndo();
                canvas_.setLayerMode(layer, mode);
                canvas_.setLayerTile(layer, tw, th, ox, oy, sg, col, indep, lock);
                canvas_.setLayerBlend(layer, blend);
                mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
                screenFull_ = true;
                layerMetaRev_++;          // 让自动保存/退出保存能落盘
                refreshLayerPropsCache();
                if (!merged) recordReplayFrame();
            }
            continue;
        }
        if (e.action == 97) {   // 2.5D 叠层开关/深度/方向(97: 91/90 已被形状渐变占用)
            bool on = false;
            int depth = 5, dir = 0;
            {
                std::lock_guard<std::mutex> lk(layerOpMu_);
                on = m25On_; depth = m25Depth_; dir = m25Dir_; m25Pending_ = false;
            }
            const bool merged = metaCoalesce(3000);   // 2.5D 深度拖动合并成一步
            if (!merged) pushMetaUndo();
            auto& g = raster::mode25D();
            const bool wasOn = g.on;
            g.on = on; g.depth = depth; g.dir = dir;
            if (on && !wasOn) sel_.deselect();     // 2.5D 是查看模式, 清掉选区避免浮层与旋转错位
            if (!on) {
                raster::setYaw25D(0);              // 关闭时视角归零(旧 Reset25DAngle)
                display_.setRotation25(0);
            } else {
                display_.setRotation25(g.yawDeg);
            }
            m25OnA_.store(on, std::memory_order_relaxed);
            m25DepthA_.store(depth, std::memory_order_relaxed);
            m25DirA_.store(dir, std::memory_order_relaxed);
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            if (minimapActive_.load(std::memory_order_relaxed)) {
                refreshMinimapThumb();
                thumbRev_.fetch_add(1, std::memory_order_relaxed);
            }
            screenFull_ = true;
            layerMetaRev_++;
            refreshLayerPropsCache();
            if (!merged) recordReplayFrame();
            continue;
        }
        if (e.action == 98) {   // 2.5D 旋转视角: yaw 归零
            applyYaw25D(0);
            continue;
        }
        if (e.action == 99) {   // 2.5D 视角增量(小地图 ⟲/⟳ 按钮)
            const auto& g = raster::mode25D();
            if (g.on) {
                float ny = std::fmod(g.yawDeg + e.x, 360.f);
                if (ny < 0.f) ny += 360.f;
                applyYaw25D(ny);
            }
            continue;
        }
        if (e.action == 40) {
            const int op = (int)e.x, arg = (int)e.y;
            // 只有真正改帧内容的操作才标脏; 速度/循环/播放不碰缓存与洋葱皮
            const bool contentOp = (op == 0 || op == 1 || op == 2 || op == 3 || op == 9 || op == 10);
            if (contentOp) {
                frameRev_.fetch_add(1);
                onionGen_.fetch_add(1);
                thumbRev_.fetch_add(1);
            }
            if (frameData_.empty()) { frameData_.resize(1); frame_ = 0; }
            if (op == 0) {
                if (canvas_.width() > 2000 || canvas_.height() > 2000) {
                    frameLimit_.fetch_add(1);
                    continue;
                }
                pushDocUndo();
                storeFrameData();
                // 空白帧: 带共享层数的头(lc/active/0)。注意 insert(pos,{}) 会选 initializer_list 插 0 个元素!
                std::vector<uint8_t> blank(12, 0);
                const uint32_t lc = (uint32_t)canvas_.layers(), act = (uint32_t)canvas_.active();
                for (int i = 0; i < 4; i++) {
                    blank[i] = (uint8_t)((lc >> (8 * i)) & 255);
                    blank[4 + i] = (uint8_t)((act >> (8 * i)) & 255);
                    blank[8 + i] = 0;
                }
                frameData_.insert(frameData_.begin() + frame_ + 1, blank);
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    std::vector<std::vector<uint32_t>> blankThumbs((size_t)canvas_.layers(),
                        std::vector<uint32_t>(48 * 48, 0u));
                    frameThumbs_.insert(frameThumbs_.begin() + frame_ + 1, blankThumbs);
                }
                frame_++;
            } else if (op == 1) {
                if (canvas_.width() > 2000 || canvas_.height() > 2000) {
                    frameLimit_.fetch_add(1);
                    continue;
                }
                pushDocUndo();
                storeFrameData();
                frameData_.insert(frameData_.begin() + frame_ + 1, frameData_[(size_t)frame_]);
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    auto copy = frameThumbs_.size() > (size_t)frame_ ? frameThumbs_[(size_t)frame_]
                              : std::vector<std::vector<uint32_t>>((size_t)canvas_.layers(), std::vector<uint32_t>(48 * 48, 0u));
                    frameThumbs_.insert(frameThumbs_.begin() + frame_ + 1, copy);
                }
                frame_++;
            } else if (op == 2) {
                if (frameData_.size() > 1) {
                    pushDocUndo();
                    frameData_.erase(frameData_.begin() + frame_);
                    {
                        std::lock_guard<std::mutex> lk(thumbMu_);
                        if (frame_ < (int)frameThumbs_.size()) frameThumbs_.erase(frameThumbs_.begin() + frame_);
                    }
                    if (frame_ >= (int)frameData_.size()) frame_ = (int)frameData_.size() - 1;
                    const int last = (int)frameData_.size() - 1;
                    loopStart_.store(std::max(0, std::min(loopStart_.load(), last)));
                    if (loopEnd_.load() >= 0)
                        loopEnd_.store(std::max(loopStart_.load(), std::min(loopEnd_.load(), last)));
                }
            } else if (op == 3) {
                if (arg >= 0 && arg < (int)frameData_.size() && arg != frame_) {
                    // 异步切帧: 大画布不再同步 store+load 卡渲染线程; 完成时 loop 里 swap
                    startFrameSwitch(arg);
                }
                frameIdx_.store(frame_);
                frameCount_.store((int)frameData_.size());
                continue;
            } else if (op == 4) {
                const bool on = playing_.load() == 0;
                if (on || playBrush_.load()) storeFrameData();
                playing_.store(on ? 1 : 0);
                playNext_ = std::chrono::steady_clock::now();
                frameCount_.store((int)frameData_.size());
                continue;
            } else if (op == 5) {   // 播放速度
                playFps_.store(std::max(1, std::min(arg, 60)));
                continue;
            } else if (op == 6) {   // 循环端点A=当前帧; A/B 只是两个端点, 播放时统一按升序
                loopStart_.store(std::max(0, std::min(frame_, (int)frameData_.size() - 1)));
                if (loopEnd_.load() < 0) loopEnd_.store((int)frameData_.size() - 1);
                continue;
            } else if (op == 7) {   // 循环端点B=当前帧
                loopEnd_.store(std::max(0, std::min(frame_, (int)frameData_.size() - 1)));
                continue;
            } else if (op == 8) {   // 清除循环
                loopStart_.store(0);
                loopEnd_.store(-1);
                continue;
            } else if (op == 10) {  // 帧拖拽: 当前帧移到 arg
                const int to = std::max(0, std::min(arg, (int)frameData_.size() - 1));
                if (to != frame_) {
                    pushDocUndo();
                    storeFrameData();
                    auto d = std::move(frameData_[(size_t)frame_]);
                    frameData_.erase(frameData_.begin() + frame_);
                    frameData_.insert(frameData_.begin() + to, std::move(d));
                    {
                        std::lock_guard<std::mutex> lk(thumbMu_);
                        if (frame_ < (int)frameThumbs_.size() && to < (int)frameThumbs_.size()) {
                            auto th = std::move(frameThumbs_[(size_t)frame_]);
                            frameThumbs_.erase(frameThumbs_.begin() + frame_);
                            frameThumbs_.insert(frameThumbs_.begin() + to, std::move(th));
                        }
                    }
                    frame_ = to;
                    frameIdx_.store(frame_);
                    recordReplayFrame();
                }
                frameCount_.store((int)frameData_.size());
                continue;
            } else if (op == 9) {   // 帧左右移动 arg=-1/+1
                const int to = frame_ + arg;
                if (to >= 0 && to < (int)frameData_.size() && to != frame_) {
                    pushDocUndo();
                    storeFrameData();
                    auto d = std::move(frameData_[(size_t)frame_]);
                    frameData_.erase(frameData_.begin() + frame_);
                    frameData_.insert(frameData_.begin() + to, std::move(d));
                    {
                        std::lock_guard<std::mutex> lk(thumbMu_);
                        if (frame_ < (int)frameThumbs_.size() && to < (int)frameThumbs_.size()) {
                            auto th = std::move(frameThumbs_[(size_t)frame_]);
                            frameThumbs_.erase(frameThumbs_.begin() + frame_);
                            frameThumbs_.insert(frameThumbs_.begin() + to, std::move(th));
                        }
                    }
                    frame_ = to;
                    frameIdx_.store(frame_);
                    recordReplayFrame();
                }
                frameCount_.store((int)frameData_.size());
                continue;
            }
            frameIdx_.store(frame_);
            frameCount_.store((int)frameData_.size());
            loadFrameData(frame_);
            if (op == 0 || op == 1 || op == 2) recordReplayFrame();
            continue;
        }
        if (e.action == 63) {
            std::string nm;
            { std::lock_guard<std::mutex> lk(layerNameMu_); nm = layerNamePending_; }
            if (!nm.empty()) {
                pushMetaUndo();
                canvas_.setLayerName((int)e.x, nm);
            }
            layerMetaRev_++;
            refreshLayerMetaCache();
        refreshLayerPropsCache();
            continue;
        }
        if (e.action == 64) {
            const bool merged = metaCoalesce(1000 + (int)e.x);   // 拖动滑条合并成一步
            if (!merged) pushMetaUndo();
            canvas_.setLayerOpacity((int)e.x, e.y);
            layerMetaRev_++;
            refreshLayerMetaCache();
        refreshLayerPropsCache();
            mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
            screenFull_ = true;
            if (!merged) recordReplayFrame();
            continue;
        }
        if (e.action == 30) {
            // 不再落定选区: 快照 v3 会保存未落定的裁剪浮层/变换/选区
            std::string p; bool w = false;
            { std::lock_guard<std::mutex> lk(snapMu_); p = snapPath_; w = snapWait_; }
            saveSnapshotNow(p, w);
            continue;
        }
        if (e.action == 31) {
            std::string p;
            { std::lock_guard<std::mutex> lk(snapMu_); p = snapPath_; }
            loadSnapshotNow(p);
            continue;
        }
        if (e.action == 41) {   // 新文档(标签栏): 空画布, 帧/历史/蒙版复位
            sel_.deselect();
            resetMeasureState();
            int w = (int)e.x, h = (int)e.y;
            if (w < 1 || h < 1) { w = canvas_.width(); h = canvas_.height(); }
            w = std::max(1, std::min(w, 32767));
            h = std::max(1, std::min(h, 32767));
            const int lc = std::max(1, canvas_.layers());
            canvas_.create(w, h, lc);
            display_.resizeCanvas(w, h);
            mip_.reset(&canvas_);
            frameData_.clear();
            frame_ = 0; frameIdx_.store(0); frameCount_.store(1);
            playing_.store(0); loopStart_.store(0); loopEnd_.store(-1); playFps_.store(12);
            clearHistory();
            layerCount_.store(lc); activeLayer_.store(0);
            lastSavedRev_.store(0);
            frameRev_.fetch_add(1);
            onionGen_.fetch_add(1);
            {
                std::lock_guard<std::mutex> lk(thumbMu_);
                frameThumbs_.clear();
            }
            mip_.invalidateRect(0, 0, w - 1, h - 1);
            refreshLayerMetaCache();
        refreshLayerPropsCache();
            screenFull_ = true;
            continue;
        }
        if (e.action == 43) {   // 调整画布尺寸(保留内容, 锚点对齐; 所有帧一起调整)
            int nw = (int)e.x, nh = (int)e.y;
            nw = std::max(4, std::min(nw, 32767));
            nh = std::max(4, std::min(nh, 32767));
            const int packed = (int)e.z;
            const int ax = std::max(0, std::min(packed / 3, 2));
            const int ay = std::max(0, std::min(packed % 3, 2));
            if (nw != canvas_.width() || nh != canvas_.height()) {
                pushDocUndo();   // 调整画布尺寸也可撤销(恢复到旧尺寸/旧帧内容)
                sel_.deselect();
                canvas_.clearCutRect();
                const int lc = std::max(1, canvas_.layers());
                const int ow = canvas_.width(), oh = canvas_.height();
                if (!frameData_.empty()) {
                    if (frame_ >= 0 && frame_ < (int)frameData_.size()) storeFrameData();
                    for (size_t fi = 0; fi < frameData_.size(); fi++) {
                        auto& blob = frameData_[fi];
                        if (blob.empty()) continue;
                        canvas_.create(ow, oh, lc);
                        if (!deserializeCanvas(blob.data(), blob.size())) { blob.clear(); continue; }
                        canvas_.resizeTo(nw, nh, ax, ay);
                        blob.clear();
                        serializeCanvas(blob);
                    }
                    canvas_.create(nw, nh, lc);
                    if (frame_ >= 0 && frame_ < (int)frameData_.size() && !frameData_[frame_].empty())
                        deserializeCanvas(frameData_[(size_t)frame_].data(), frameData_[(size_t)frame_].size());
                } else {
                    canvas_.resizeTo(nw, nh, ax, ay);
                }
                display_.resizeCanvas(nw, nh);
                mip_.reset(&canvas_);
                layerCount_.store(canvas_.layers());
                activeLayer_.store(canvas_.active());
                frameLayerCount_ = canvas_.layers();
                lastSavedRev_.store(0);
                frameRev_.fetch_add(1);
                onionGen_.fetch_add(1);
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    frameThumbs_.clear();
                }
                if ((long long)nw * nh <= 4ll * 1024 * 1024) {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    frameThumbs_.assign(frameData_.empty() ? 1 : frameData_.size(),
                        std::vector<std::vector<uint32_t>>((size_t)canvas_.layers(),
                            std::vector<uint32_t>((size_t)THUMB * THUMB, 0u)));
                    if (!frameData_.empty()) {
                        std::vector<uint32_t> th((size_t)THUMB * THUMB, 0u);
                        for (size_t fi = 0; fi < frameData_.size(); fi++) {
                            for (int l = 0; l < canvas_.layers(); l++) {
                                if (layerThumb((int)fi, l, th.data(), (int)th.size()))
                                    frameThumbs_[fi][(size_t)l] = th;
                            }
                        }
                    } else {
                        for (int l = 0; l < canvas_.layers(); l++)
                            makeThumb(frame_, l, frameThumbs_[0][(size_t)l]);
                    }
                }
                screenFull_ = true;
                refreshLayerMetaCache();
        refreshLayerPropsCache();
                recordReplayFrame();
                LOGI("resize canvas %dx%d -> %dx%d anchor=%d,%d frames=%zu", ow, oh, nw, nh, ax, ay, frameData_.size());
            }
            continue;
        }
        if (e.action == 27) { scaleSelection((int)e.x, (int)e.y); continue; }
        if (e.action == 25) {
            if (sel_.floating()) {
                const int q = (int)e.x;
                if (q == 1) sel_.rotate90();
                else if (q == 3) { sel_.rotate90(); sel_.rotate90(); sel_.rotate90(); }
                screenFull_ = true;
            }
            continue;
        }
        if (e.action == 26) {
            if (sel_.floating()) {
                if (e.x > 0.5f) sel_.flipSelH(); else sel_.flipSelV();
                screenFull_ = true;
            }
            continue;
        }
        if (e.action == 38) { sel_.resetTransform(); screenFull_ = true; continue; }
        if (e.action == 10) {   // 图层操作(必须在渲染线程)
            const int op = (int)e.x, arg = (int)e.y;
            if (op == 4) {
                pushMetaUndo();
                canvas_.setLayerVisible(arg, !canvas_.layerVisible(arg));
                layerMetaRev_++;
                onionGen_.fetch_add(1);
                mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
                screenFull_ = true;
                recordReplayFrame();
                continue;
            }
            if (op == 5) {
                pushMetaUndo();
                canvas_.setLayerLocked(arg, !canvas_.isLayerLocked(arg));
                layerMetaRev_++;
                recordReplayFrame();
                continue;
            }
            if (op == 6 || op == 7) {
                const int from = arg;
                const int to = op == 6 ? arg + 1 : arg - 1;
                if (from >= 0 && from < canvas_.layers() && to >= 0 && to < canvas_.layers() && from != to) {
                    pushDocUndo();
                    canvas_.moveLayer(from, to);
                    rewriteFramesMoveLayer(from, to);
                    {
                        std::lock_guard<std::mutex> lk(thumbMu_);
                        for (auto& ft : frameThumbs_) {
                            if (from < (int)ft.size() && to < (int)ft.size()) {
                                auto row = std::move(ft[(size_t)from]);
                                ft.erase(ft.begin() + from);
                                ft.insert(ft.begin() + to, std::move(row));
                            }
                        }
                    }
                    layerCount_.store(canvas_.layers());
                    activeLayer_.store(canvas_.active());
                    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
                    thumbRev_.fetch_add(1);
                    screenFull_ = true;
                    recordReplayFrame();
                }
                layerMetaRev_++;
                refreshLayerMetaCache();
        refreshLayerPropsCache();
                continue;
            }
            if (op == 0) {
                pushDocUndo();
                canvas_.addLayer();
                frameLayerCount_ = canvas_.layers();   // 新层对所有帧生效(其它帧缺的层加载时补空层)
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    for (auto& ft : frameThumbs_) ft.emplace_back(48 * 48, 0u);
                }
            } else if (op == 1) {
                pushDocUndo();
                const int del = canvas_.active();
                canvas_.removeLayer(del);
                frameLayerCount_ = canvas_.layers();
                rewriteFramesDropLayer(del);
                {
                    std::lock_guard<std::mutex> lk(thumbMu_);
                    for (auto& ft : frameThumbs_)
                        if (del >= 0 && del < (int)ft.size()) ft.erase(ft.begin() + del);
                }
            }
            else if (op == 2) canvas_.setActive(arg);
            layerCount_.store(canvas_.layers());
            activeLayer_.store(canvas_.active());
            layerMetaRev_++;
            refreshLayerMetaCache();
        refreshLayerPropsCache();
            if (op == 0 || op == 1) { thumbRev_.fetch_add(1); recordReplayFrame(); }
            continue;
        }
        int cx = 0, cy = 0;
        bool inside = display_.screenToCanvas(e.x, e.y, &cx, &cy);
        // 取色器优先于选区手势: 两色混合/取色器进入后, 即使用户当前是框选工具也能吸色
        // 取色器: 接下来 n 次点击画布拾取所有层合成后的可见色(两色混合 n=2)
        if (pickMode_.load()) {
            if (e.action != 0) continue;
            if (inside) {
                uint32_t acc = 0;
                const int nl = canvas_.layers();
                for (int l = 0; l < nl; l++) {
                    if (!canvas_.layerVisible(l)) continue;
                    raster::overPixel(acc, raster::layerPixel(canvas_, l, cx, cy));
                }
                pickPrev_.store(pickColor_.load());
                pickColor_.store((int)acc);
                pickSeq_.fetch_add(1);
                const int rem = pickRemain_.fetch_sub(1) - 1;
                if (rem <= 0) {
                    pickRemain_.store(0);
                    pickMulti_.store(false);
                    pickMode_.store(false);
                }
            } else if (!pickMulti_.load()) {
                // 普通取色点画布外=取消; 两色混合忽略画布外点击, 继续等用户吸色
                pickRemain_.store(0);
                pickMode_.store(false);
            }
            continue;
        }
        if (sel_.mode() == 1) {
            lastInputT_ = std::chrono::steady_clock::now();   // 选区操作也算输入: 期间暂停后台搬移
            sel_.handle(e.action, cx, cy);
            if (sel_.takeMaskSkipped()) wandSkip_.fetch_add(1);
            continue;
        }
        // 油漆桶: 按下只记落点, 抬手才填充。
        // 这样第一指落下后第二指加入(双指缩放/平移)时直接丢弃, 不会先填一块再回滚,
        // 也不会在撤销栈里留下误触记录; 单指点按行为不变。
        if (tool_.load() == 3) {
            if (e.action == 0) {
                if (canvas_.isLayerLocked(canvas_.active()) || !inside) {
                    bucketPending_ = false;
                    continue;
                }
                bucketPending_ = true;
                bucketX_ = cx;
                bucketY_ = cy;
                continue;
            }
            if (e.action == 1) continue;   // 单指小抖动不取消, 抬手仍按落点填充
            if (e.action == 2) {
                if (bucketPending_) {
                    const int bx = bucketX_, by = bucketY_;
                    bucketPending_ = false;
                    bucketFillAt(bx, by, e.t);
                }
                continue;
            }
            // 3=取消(双指确认), 47=已由上面的双指分支处理; 其余动作忽略
            if (e.action == 3 || e.action == 47) bucketPending_ = false;
            continue;
        }
        // 图形工具: 预览直接用画布像素画(旧项目 DrawShapePreview/Backup 机制), 与落层完全同源;
        // 线/矩/椭/叶抬手保留这些像素, 套索/多边形抬手恢复路径后扫描线填充(先画笔再执行)
        if (tool_.load() == 4) {
            if (canvas_.isLayerLocked(canvas_.active())) continue;
            // 图形工具也记录最后输入(测距放大镜要用 lastRaw/lastAct)
            lastRawX_.store((int)e.x); lastRawY_.store((int)e.y);
            lastCvX_.store(cx); lastCvY_.store(cy); lastAct_.store(e.action);
            const int st = shapeType_.load();
            if (st == 9 || st == 10) {
                // 方形渐变/圆形渐变: 拖出形状 → 裁剪状态预览(蚂蚁线) → UI 换预设/应用/取消
                if (e.action == 0) {
                    if (gradActive_ || gradDragging_) gradCancel();
                    if (sel_.floating()) sel_.commitFloat();
                    sel_.finishCommit();
                    if (!inside) continue;
                    const int ns = (st == 10) ? 1 : 0;
                    if (ns != gradShape_) gradPreset_ = 0;
                    gradShape_ = ns;
                    gradDragging_ = true;
                    gradActive_ = false;
                    gradX0_ = gradX1_ = cx;
                    gradY0_ = gradY1_ = cy;
                    gradLayer_ = canvas_.active();
                    gradCw_ = canvas_.width();
                    gradCh_ = canvas_.height();
                    gradRange_ = gradRangeA_.load();
                    gradAngle_ = gradAngleA_.load();
                    gradPos_ = gradPosA_.load();
                    gradRefreshAnts();
                } else if (e.action == 1 && gradDragging_) {
                    gradX1_ = cx; gradY1_ = cy;
                    gradRefreshAnts();
                } else if (e.action == 2 && gradDragging_) {
                    gradDragging_ = false;
                    const int gw = std::abs(gradX1_ - gradX0_) + 1;
                    const int gh = std::abs(gradY1_ - gradY0_) + 1;
                    if (gw < 2 || gh < 2) { gradCancel(); continue; }
                    gradApplyPreview();
                    if (gradRec_.empty()) {   // 全透明区域没有可淡出的像素
                        gradActive_ = false;
                        gradLayer_ = -1;
                        display_.setSelection(0, 0, 0, 0, false);
                    } else {
                        gradActive_ = true;
                    }
                } else if (e.action == 3) {
                    gradCancel();
                }
                continue;
            }
            if (st == 0 || st == 1 || st == 2 || st == 3) {
                const int64_t evt = e.t > 0 ? e.t : steadyEventMs();
                if (e.action == 0) { recPtsBegin(evt); recPtsAdd(cx, cy, evt); }
                else if (e.action == 1) recPtsAdd(cx, cy, evt);
            }
            const int rad = std::max(1, (int)std::lround(shapePx_.load()));
            const int brushR = std::max(0, rad / 2);      // 线/矩/椭按圆形笔刷半径加粗, 1px 时 r=0
            const bool shErase = eraseMode_.load();
            const uint32_t shColor = shErase ? 0u : brushColor_.load();
            const uint32_t prevColor = brushColor_.load(); // 预览用画笔色: 和笔刷刷出来的一模一样
            const int cw = canvas_.width(), ch = canvas_.height();
            auto repaintPreview = [&](const std::vector<int>& xy) {
                if (xy.size() / 2 > 8000000) return;       // 超大形状不做逐像素预览
                shapePrevRestore();
                if (!xy.empty()) shapePrevPaint(xy, prevColor, canvas_.active());
            };
            auto buildGeneric = [&](std::vector<int>& xy) {
                xy.clear();
                if (st == 0) {
                    // 粗直线 = 两条平行边之间的平行四边形带(butt 端头, 逐行精确 span):
                    // 边缘平直、端头止于起终点, 不鼓出拖拽框外
                    raster::thickLinePixels(shX0_, shY0_, shX1_, shY1_, rad, xy);
                } else if (st == 1) {
                    const int ax0 = std::min(shX0_, shX1_), ax1 = std::max(shX0_, shX1_);
                    const int ay0 = std::min(shY0_, shY1_), ay1 = std::max(shY0_, shY1_);
                    if (shapeFill_.load()) {
                        for (int yy = ay0; yy <= ay1; yy++)
                            for (int xx = ax0; xx <= ax1; xx++) { xy.push_back(xx); xy.push_back(yy); }
                    } else {
                        // 四条轴对齐边带(在矩形内, 厚度向内), 方角; 和旧项目 GetRectPixels 同构
                        raster::rectOutlinePixels(ax0, ay0, ax1, ay1, rad, xy);
                    }
                } else {
                    // 椭圆: 填充=整行 span, 空心=按线宽的行内环带(不会自交出中空/乱线)
                    if (shapeFill_.load())
                        raster::ellipseFillPixels(shX0_, shY0_, shX1_, shY1_, xy);
                    else
                        raster::ellipseOutlinePixels(shX0_, shY0_, shX1_, shY1_, rad, xy);
                }
            };
            if (st == 3) {   // 套索填充: 路径先用画笔色画在画布上, 抬手恢复路径后扫描线填充
                if (e.action == 0) {
                    if (sel_.floating()) sel_.commitFloat();
                    sel_.finishCommit();
                    if (!inside) continue;
                    beginStrokeRecord();
                    shapePrevBegin();
                    lasso_.clear();
                    lasso_.push_back(cx); lasso_.push_back(cy);
                    std::vector<int> xy;
                    xy.push_back(cx); xy.push_back(cy);
                    repaintPreview(xy);
                } else if (e.action == 1) {
                    if (lasso_.size() >= 2) {
                        const int lx = lasso_[lasso_.size() - 2], ly = lasso_[lasso_.size() - 1];
                        if (lx != cx || ly != cy) {
                            lasso_.push_back(cx); lasso_.push_back(cy);
                            if (lasso_.size() > 8192) {
                                std::vector<int> th;
                                th.reserve(lasso_.size() / 2 + 2);
                                for (size_t i = 0; i < lasso_.size(); i += 4) {
                                    th.push_back(lasso_[i]); th.push_back(lasso_[i + 1]);
                                }
                                lasso_.swap(th);
                            }
                            std::vector<int> xy;
                            raster::polylinePixels(lasso_, false, xy);
                            repaintPreview(xy);
                        }
                    }
                } else {
                    shapePrevRestore();
                    if (e.action == 2) {
                        if (lasso_.size() >= 6)
                            raster::fillPolygon(canvas_, lasso_, shColor, shErase);
                        endStrokeRecord();
                    } else {
                        canvas_.endRecord();   // 取消: 丢弃记录
                    }
                    lasso_.clear();
                }
                continue;
            }
            if (st == 7) {   // 多边形套索: 点击加点(路径先画笔), 起点10画布px/双击20画布px闭合, 抬手填充
                auto buildPoly = [&](std::vector<int>& xy) {
                    std::vector<int> pts = poly_;
                    if (polyHeld_ && poly_.size() >= 2) {
                        const int lx = poly_[poly_.size() - 2], ly = poly_[poly_.size() - 1];
                        if (lx != polyCurX_ || ly != polyCurY_) {
                            pts.push_back(polyCurX_); pts.push_back(polyCurY_);
                        }
                    }
                    raster::polylinePixels(pts, false, xy);
                    if (xy.empty() && !pts.empty()) { xy.push_back(pts[0]); xy.push_back(pts[1]); }
                };
                if (e.action == 0) {
                    if (sel_.floating()) sel_.commitFloat();
                    sel_.finishCommit();
                    if (!inside) continue;
                    if (!polyActive_) {
                        beginStrokeRecord();
                        shapePrevBegin();
                        polyActive_ = true;
                        poly_.clear();
                        poly_.push_back(cx); poly_.push_back(cy);
                        polyLastUpMs_ = 0;
                    }
                    polyHeld_ = true;
                    polyCurX_ = cx; polyCurY_ = cy;
                } else if (e.action == 1 && polyActive_) {
                    polyCurX_ = cx; polyCurY_ = cy;
                } else if (e.action == 2 && polyActive_) {
                    polyHeld_ = false;
                    const int n = (int)poly_.size() / 2;
                    const int sx0 = poly_.size() >= 2 ? poly_[0] : cx;
                    const int sy0 = poly_.size() >= 2 ? poly_[1] : cy;
                    const long long now = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count();
                    const long long ddx = cx - sx0, ddy = cy - sy0;
                    const long long d2 = ddx * ddx + ddy * ddy;
                    // 旧 pm615/pm663: 起点 10 画布px 内 或 0.45s 内双击且落点距起点 20 画布px 内
                    const bool nearStart = n >= 3 && d2 <= 100;
                    const bool dbl = n >= 3 && polyLastUpMs_ > 0 &&
                                     (now - polyLastUpMs_) < 450 && d2 <= 400;
                    if (nearStart || dbl) {
                        shapePrevRestore();
                        if (n >= 3) raster::fillPolygon(canvas_, poly_, shColor, shErase);
                        endStrokeRecord();
                        polyActive_ = false;
                        poly_.clear();
                        continue;
                    }
                    if (n >= 1) {
                        const int lx = poly_[poly_.size() - 2], ly = poly_[poly_.size() - 1];
                        if (lx != cx || ly != cy) { poly_.push_back(cx); poly_.push_back(cy); }
                    } else {
                        poly_.push_back(cx); poly_.push_back(cy);
                    }
                    polyLastUpMs_ = now;
                } else if (e.action == 3 && polyActive_) {
                    shapePrevRestore();
                    canvas_.endRecord();
                    polyActive_ = false;
                    polyHeld_ = false;
                    poly_.clear();
                    continue;
                }
                if (polyActive_) {
                    std::vector<int> xy;
                    buildPoly(xy);
                    repaintPreview(xy);
                }
                continue;
            }
            if (st == 4) {   // 柳叶: 1画布px 轮廓/填充, 预览=落层
                auto buildLeaf = [&](std::vector<int>& xy) {
                    xy.clear();
                    std::vector<int> lp;
                    raster::leafPath(leafX0_, leafY0_, leafX1_, leafY1_, leafW_.load(), lp);
                    if (lp.size() < 6) return;
                    if (shapeFill_.load()) raster::polygonFillPixels(lp, cw, ch, xy);
                    else raster::polylinePixels(lp, true, xy);
                };
                if (e.action == 0) {
                    if (sel_.floating()) sel_.commitFloat();
                    sel_.finishCommit();
                    if (!inside) continue;
                    beginStrokeRecord();
                    shapePrevBegin();
                    leafActive_ = true;
                    leafX0_ = leafX1_ = cx; leafY0_ = leafY1_ = cy;
                } else if (e.action == 1 && leafActive_) {
                    leafX1_ = cx; leafY1_ = cy;
                } else if ((e.action == 2 || e.action == 3) && leafActive_) {
                    leafActive_ = false;
                    if (e.action == 2) {
                        shapePrevCommit();
                        endStrokeRecord();
                    } else {
                        shapePrevRestore();
                        canvas_.endRecord();
                    }
                    continue;
                }
                if (leafActive_) {
                    std::vector<int> xy;
                    buildLeaf(xy);
                    repaintPreview(xy);
                }
                continue;
            }
            if (st == 6) {   // 测距: 屏幕矢量预览(不是画布像素), 抬手保留, 切走工具才清
                if (e.action == 0) {
                    if (!inside) continue;
                    if (measOn_ || measMagShown_) screenFull_ = true;
                    beginStrokeRecord();
                    measOn_ = true;
                    measDownT_ = std::chrono::steady_clock::now();
                    measMagShown_ = false;
                    if (measSetStartPending_) {
                        measX0_ = cx; measY0_ = cy;
                        measX1_ = cx; measY1_ = cy;
                        measSetStartPending_ = false;
                        measFixed_ = true;
                    } else if (measFixed_) {
                        measX1_ = cx; measY1_ = cy;
                    } else {
                        measX0_ = measX1_ = cx; measY0_ = measY1_ = cy;
                    }
                    markScreenRect(cx - 2, cy - 2, cx + 2, cy + 2);
                } else if (e.action == 1 && measOn_) {
                    markScreenRect(std::min(measX0_, measX1_) - 3, std::min(measY0_, measY1_) - 3,
                                   std::max(measX0_, measX1_) + 3, std::max(measY0_, measY1_) + 3);
                    measX1_ = cx; measY1_ = cy;
                    markScreenRect(std::min(measX0_, measX1_) - 3, std::min(measY0_, measY1_) - 3,
                                   std::max(measX0_, measX1_) + 3, std::max(measY0_, measY1_) + 3);
                } else if (e.action == 2) {
                    if (measMagShown_) screenFull_ = true;
                    measMagShown_ = false;
                    endStrokeRecord();
                } else if (e.action == 3) {
                    measOn_ = false;
                    measMagShown_ = false;
                    endStrokeRecord();
                    screenFull_ = true;
                }
                continue;
            }
            // 直线/矩形/椭圆: 预览就是画布像素(线按圆形笔刷加粗), 抬手保留
            if (e.action == 0) {
                if (sel_.floating()) sel_.commitFloat();
                sel_.finishCommit();
                if (!inside) continue;
                beginStrokeRecord();
                shapePrevBegin();
                shActive_ = true;
                shX0_ = shX1_ = cx; shY0_ = shY1_ = cy; shRad_ = rad;
                std::vector<int> xy;
                buildGeneric(xy);
                repaintPreview(xy);
                continue;
            }
            if (e.action == 1 && shActive_) {
                int nx = cx, ny = cy;
                if (shapeRatio_.load() && (st == 1 || st == 2)) {
                    const int dx = nx - shX0_, dy = ny - shY0_;
                    const int m = std::max(std::abs(dx), std::abs(dy));
                    nx = shX0_ + (dx < 0 ? -m : m);
                    ny = shY0_ + (dy < 0 ? -m : m);
                }
                shX1_ = std::max(0, std::min(nx, canvas_.width() - 1));
                shY1_ = std::max(0, std::min(ny, canvas_.height() - 1));
                shRad_ = rad;
                std::vector<int> xy;
                buildGeneric(xy);
                repaintPreview(xy);
                continue;
            }
            if ((e.action == 2 || e.action == 3) && shActive_) {
                shActive_ = false;
                if (e.action == 2) {
                    shapePrevCommit();
                    endStrokeRecord();
                } else {
                    shapePrevRestore();
                    canvas_.endRecord();
                }
                continue;
            }
            continue;
        }
        // 画笔模式: 落笔前先把未完成的落定收尾(画布必须是最终态), 免得笔画被旧内容/浮起图挡住
        if (e.action == 0) {
            if (sel_.floating()) sel_.commitFloat();
            sel_.finishCommit();
        }
        lastInputT_ = std::chrono::steady_clock::now();
        lastRawX_.store((int)e.x); lastRawY_.store((int)e.y);
        lastCvX_.store(cx); lastCvY_.store(cy); lastAct_.store(e.action);
        const int tool = tool_.load();
        if ((tool == 0 || tool == 2) && canvas_.isLayerLocked(canvas_.active())) continue;   // 图层锁定: 不落笔
        const bool erasing = (tool == 2) || eraseMode_.load();
        const uint32_t color = erasing ? 0u : brushColor_.load();
        // 画笔按屏幕像素换算: UI 给的是屏幕像素, 巨画布上才不会细成一条线
        // UI 的“大小”是直径: 半径 = (直径/zoom - 1)/2, 允许 0 = 真 1 画布像素
        // (旧版 min 1 会把“1px”画成 3 画布像素, 完美线条的精确路径永远进不去)
        int brush = (int)std::lround((brushPx_.load() - 1.0f) * 0.5f);   // 画布像素, 与 zoom 无关
        brush = std::max(0, std::min(brush, 4096));
        // 笔刷预设: 默认(像素硬笔)走原快速路径; 其它形状/软边/散射走笔刷系统
        const int bShape = brushShape_.load(), bHard = brushHardness_.load();
        const int bSpacing = brushSpacing_.load(), bScatter = brushScatter_.load();
        const int bAspect = brushAspect_.load(), bAngle = brushAngle_.load(), bFlow = brushFlow_.load();
        // 橡皮擦固定像素硬笔: 不继承画笔预设(软边/间距/散射/流量会把擦除变得几乎不可见)
        const bool bcustom = !erasing &&
                             (bShape != 0 || bHard < 100 || bSpacing != 10 || bScatter > 0 ||
                              bAspect < 100 || bAngle != 0 || bFlow < 100);
        // 完美线条: 1 画布像素笔宽走精确回滚路径; 更宽的笔走延迟提交平滑路径
        const bool ppOn = ppLine_.load() && !erasing && brush <= 1 && !bcustom;
        const bool ppSmooth = ppLine_.load() && !erasing && brush > 1 && !bcustom;
        if (e.action == 0) {
            // 半透明画笔: 平涂 OFF = 整笔共用一个覆盖掩码(每像素一笔只混一次);
            // 叠色 ON = 按图元历史 + 方向/笔程去重: 直线继续不叠, 回涂/自交才再混。
            const bool semi = !erasing && tool == 0 &&
                              ((color >> 24) & 255) > 0 && ((color >> 24) & 255) < 255;
            if (semi) {
                if (buildup_.load()) canvas_.beginStampMask();
                else canvas_.beginStrokeMask();
            } else canvas_.endStrokeMask();
            canvas_.setStampDir(0.f, 0.f);
            canvas_.setStampWindow(2.0f, std::max(2.0f, (float)brush * 2.0f + 1.0f));
            cntDown_.fetch_add(1);
            buttonDown_.store(true);
            beginStrokeRecord();
            {
                const int64_t evt = e.t > 0 ? e.t : steadyEventMs();
                recPtsBegin(evt);
                recPtsAdd(cx, cy, evt);
            }
            dbgDownLock_.store(canvas_.alphaLock() ? 1 : 0);
            dbgAxisSeg_.store(0); dbgGenSeg_.store(0);
            LOGI("brush down lock=%d brush=%d inside=%d pp=%d/%d", canvas_.alphaLock() ? 1 : 0,
                 brush, inside ? 1 : 0, ppOn ? 1 : 0, ppSmooth ? 1 : 0);
            lastX_ = cx; lastY_ = cy;
            ppReset();
            ppPendOk_ = false;
            if (inside) {
                if (ppOn) ppFeed(cx, cy, color);
                else if (ppSmooth) ppFeedSmooth(cx, cy, color, brush);
                else if (bcustom) raster::stampBrushDab(canvas_, (float)cx, (float)cy, (float)brush,
                    color, bShape, bHard, bAspect, bAngle, bFlow, (float)bScatter,
                    erasing, buildup_.load());
                else raster::stampCircle(canvas_, cx, cy, brush, color, erasing, buildup_.load());
                cntStamp_.fetch_add(1);
            }
        } else if (e.action == 1 && buttonDown_.load()) {
            recPtsAdd(cx, cy, e.t > 0 ? e.t : steadyEventMs());
            {
                const float mdx = (float)(cx - lastX_), mdy = (float)(cy - lastY_);
                const float mlen = std::sqrt(mdx * mdx + mdy * mdy);
                canvas_.setStampDir(mdx, mdy);
                canvas_.setStampWindow(mlen + 1.0f, std::max(2.0f, (float)brush * 2.0f + 1.0f));
            }
            cntMove_.fetch_add(1);
            prevX_.store(lastX_); prevY_.store(lastY_);
            curX_.store(cx); curY_.store(cy);
            prevDelta_.store(abs(cx - lastX_) + abs(cy - lastY_));
            if (ppOn && lastX_ >= 0) ppFeedSegment(lastX_, lastY_, cx, cy, color);
            else if (ppSmooth && lastX_ >= 0) {
                // 先在事件之间补点(间距 radius/2), 每个补点走延迟判定
                const int dx = cx - lastX_, dy = cy - lastY_;
                const float len = std::sqrt((float)dx * dx + (float)dy * dy);
                const int step = std::max(1, brush / 2);
                const int n = std::max(1, (int)(len / step));
                for (int i = 1; i <= n; i++) {
                    const int px = lastX_ + (int)std::lround(dx * (float)i / n);
                    const int py = lastY_ + (int)std::lround(dy * (float)i / n);
                    ppFeedSmooth(px, py, color, brush);
                }
            }
            else if (lastX_ >= 0) {
                dbgKind_.store(3);
                dbgLastDx_.store(cx - lastX_); dbgLastDy_.store(cy - lastY_);
                if (cx == lastX_ || cy == lastY_) dbgAxisSeg_.fetch_add(1);
                else dbgGenSeg_.fetch_add(1);
                if (bcustom) raster::stampBrushLine(canvas_, (float)lastX_, (float)lastY_,
                    (float)cx, (float)cy, (float)brush, color, bShape, bHard, bSpacing,
                    (float)bScatter, bAspect, bAngle, bFlow, erasing, buildup_.load());
                else raster::stampLine(canvas_, lastX_, lastY_, cx, cy, brush, color, erasing, buildup_.load());
            }
            else if (inside) {
                if (bcustom) raster::stampBrushDab(canvas_, (float)cx, (float)cy, (float)brush,
                    color, bShape, bHard, bAspect, bAngle, bFlow, (float)bScatter,
                    erasing, buildup_.load());
                else raster::stampCircle(canvas_, cx, cy, brush, color, erasing, buildup_.load());
            }
            cntStamp_.fetch_add(1);
            lastX_ = cx; lastY_ = cy;
        } else if (e.action == 2 || e.action == 3) {
            cntUp_.fetch_add(1);
            buttonDown_.store(false);
            lastX_ = lastY_ = -1;
            if (ppSmooth) ppFlushSmooth(color, brush);
            ppReset();
            ppPendOk_ = false;
            canvas_.endStrokeMask();
            endStrokeRecord();
            inkPixels_.store((int)canvas_.countInk(0));
        }
    }
}

// 油漆桶实际填充(抬手时调用), 整次填充作为一条撤销记录
void Engine::bucketFillAt(int cx, int cy, int64_t evt) {
    if (canvas_.isLayerLocked(canvas_.active())) return;
    if (sel_.floating()) sel_.commitFloat();
    sel_.finishCommit();
    const int fillLayer = canvas_.active();
    const uint32_t seed = canvas_.getPixelL(fillLayer, cx, cy);
    const int tol = fillTol_.load();
    // 移除像素: 填充成透明
    const uint32_t fillColor = eraseMode_.load() ? 0u : brushColor_.load();
    {
        const int64_t t = evt > 0 ? evt : steadyEventMs();
        recPtsBegin(t);
        recPtsAdd(cx, cy, t);
    }
    beginStrokeRecord();
    // 容差>0 时被改写的旧像素不全是种子色, 行段撤销会错 → 退回整块快照;
    // 平铺图层会把结果复制到其它格, 行段撤销覆盖不到 → 同样整块快照
    const bool runUndo = (tol <= 0 && canvas_.layerMode(fillLayer) != 1);
    canvas_.setFillRecord(runUndo);
    std::vector<Canvas::Span> spans;
    const auto ft0 = std::chrono::steady_clock::now();
    auto fr = raster::floodFill(canvas_, fillLayer, cx, cy, fillColor,
                                256ll * 1024 * 1024, &spans, tol);
    canvas_.setFillRecord(false);
    const double fms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - ft0).count();
    // 平铺图层: 填充结果同步到所有格子(仍在本条撤销记录内)
    if (!fr.capped && canvas_.layerMode(fillLayer) == 1)
        canvas_.replicateRecordedPixels(fillLayer, canvas_.layerTileW(fillLayer),
                                        canvas_.layerTileH(fillLayer),
                                        canvas_.layerTileOffX(fillLayer),
                                        canvas_.layerTileOffY(fillLayer));
    auto rec = canvas_.endRecord();    // 只有 solid 转换的快照
    bool fillDone = false;
    if (fr.capped) {
        canvas_.applyRecord(rec);
        canvas_.paintSpans(fillLayer, spans, seed);   // 平坦行段恢复旧色
        fillCaps_.fetch_add(1);
        LOGI("bucket capped at %lld px in %.1fms, rolled back", (long long)fr.pixels, fms);
    } else if (rec.empty() && spans.empty()) {
        // 同色/空操作
    } else {
        Hist h;
        h.kind = 1;
        h.frame = frame_;
        h.isFill = runUndo;   // 容差填充不做行段撤销(旧块内容已整块快照)
        h.fillLayer = fillLayer;
        h.cBefore = seed;
        h.cAfter = fillColor;
        if (!runUndo) spans.clear();
        h.rec = std::move(rec);
        h.spans = std::move(spans);
        h.bytes = recBytes(h.rec) + h.spans.size() * sizeof(Canvas::Span);
        fillDone = true;
        pushHist(std::move(h), false);   // 下面统一落回放帧
        LOGI("bucket fill %lld px in %.1fms (runs=%zu)", (long long)fr.pixels, fms, undo_.back().spans.size());
    }
    // 填充成功才落回放帧(capped/无变化/同色不录)
    if (fillDone) recordReplayFrame();
    else { std::lock_guard<std::mutex> lk(recMu_); recPts_.clear(); recPtsPrevMs_ = 0; }
}

void Engine::loop() {
    using Clock = std::chrono::steady_clock;
    auto frameStart = Clock::now();
    lastInputT_ = Clock::now();
    lastPresentT_ = Clock::now();
    int frames = 0;
    auto fpsT0 = Clock::now();
    while (running_.load()) {
        loopTicks_.fetch_add(1, std::memory_order_relaxed);
        auto workT0 = Clock::now();
        if (redrawReq_.exchange(false)) { screenFull_ = true; fullBurst_ = std::max(fullBurst_, 30); }
        int pw = pendingW_.exchange(0), ph = pendingH_.exchange(0);
        if (pw > 0 && ph > 0) { display_.onSize(pw, ph); screenFull_ = true; fullBurst_ = 60; }
        if (insetsDirty_.exchange(false)) display_.setInsets(insL_.load(), insT_.load(), insR_.load(), insB_.load());
        if (lvPrevDirty_.exchange(false)) screenFull_ = true;
        // 导出/切帧后台任务期间画布只读: 不处理输入(事件保留在队列), 画面照常渲染
        bool canvasFreeze = canvasBusy_.load(std::memory_order_relaxed);
        if (!canvasFreeze) drainInput();
        // drainInput 里可能刚启动异步切帧: 同一帧后续步骤也不能再写画布
        canvasFreeze = canvasBusy_.load(std::memory_order_relaxed);
        // 小地图点击的视角平滑动画: 指数逼近, 到像素级停
        if (viewAnimActive_) {
            const auto nowA = Clock::now();
            const float dt = std::min(0.05f,
                std::chrono::duration<float>(nowA - viewAnimLastT_).count());
            viewAnimLastT_ = nowA;
            const float k = 1.f - std::exp(-std::max(0.001f, dt) / std::max(0.02f, viewAnimTau_));
            const float z = display_.zoom();
            const float bx = display_.panX(), by = display_.panY();
            if (z <= 0.f) {
                viewAnimActive_ = false;
            } else if (viewAnimZoom_) {
                const float nz = z + (viewAnimTZ_ - z) * k;
                display_.setView(nz, bx + (viewAnimTPX_ - bx) * k,
                                 by + (viewAnimTPY_ - by) * k);
                screenFull_ = true;
                if (std::fabs(nz - z) < 0.0004f &&
                    std::fabs(display_.panX() - bx) < 0.05f &&
                    std::fabs(display_.panY() - by) < 0.05f) {
                    viewAnimActive_ = false;
                }
            } else {
                display_.setView(z, bx + (viewAnimTX_ - bx) * k,
                                 by + (viewAnimTY_ - by) * k);
                screenFull_ = true;
                if (std::fabs(display_.panX() - bx) < 0.05f &&
                    std::fabs(display_.panY() - by) < 0.05f) {
                    viewAnimActive_ = false;
                }
            }
        }
        // 选区屏幕矩形(供 UI 面板每帧跟手): 只在有选区时换算
        if (sel_.active()) {
            int rx0, ry0, rx1, ry1;
            sel_.rect(&rx0, &ry0, &rx1, &ry1);
            int a, b, c, d;
            display_.canvasRectToScreen(rx0, ry0, rx1, ry1, &a, &b, &c, &d);
            selScrX0_.store(a); selScrY0_.store(b); selScrX1_.store(c); selScrY1_.store(d);
            selScrActive_.store(true);
            selScrFloat_.store(sel_.floating() || sel_.holdFloat());
            int ui[18] = {a, b, c, d, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            ui[4] = 1 | ((sel_.floating() || sel_.holdFloat()) ? 2 : 0) |
                    (sel_.stretchMode() ? 4 : 0) | (sel_.xformActive() ? 8 : 0) |
                    ((sel_.hasCropTarget() && (sel_.floating() || sel_.holdFloat())) ? 16 : 0) |
                    (sel_.perspectiveMode() ? 32 : 0);
            // 尺寸标签显示选区原始宽高(浮起时=裁剪内容大小); 旋转期间不跟着包围盒跳数字
            ui[15] = sel_.labelW();
            ui[16] = sel_.labelH();
            ui[17] = (int)std::lround(sel_.rotationDeg());
            if (sel_.floating()) {
                float q[8]; sel_.corners(q);
                for (int i = 0; i < 4; i++) {
                    float fx2, fy2;
                    display_.canvasToScreenF(q[i*2], q[i*2+1], &fx2, &fy2);
                    ui[5 + i*2] = (int)std::lround(fx2); ui[6 + i*2] = (int)std::lround(fy2);
                }
                float hx, hy;
                sel_.rotateHandle(&hx, &hy);
                float fx2, fy2;
                display_.canvasToScreenF(hx, hy, &fx2, &fy2);
                ui[13] = (int)std::lround(fx2); ui[14] = (int)std::lround(fy2);
            }
            { std::lock_guard<std::mutex> lk(selUiMu_); for (int i = 0; i < 18; i++) selUi_[i] = ui[i]; }
        } else {
            selScrActive_.store(false);
            selScrFloat_.store(false);
            std::lock_guard<std::mutex> lk(selUiMu_); for (int i = 0; i < 18; i++) selUi_[i] = 0;
        }
        {
            std::lock_guard<std::mutex> lk(gradUiMu_);
            gradUi_[0] = gradActive_ ? 1 : 0;
            gradUi_[1] = gradShape_;
            gradUi_[2] = gradPreset_;
            gradUi_[3] = gradRange_;
            gradUi_[4] = 0;
            gradUi_[5] = gradAngle_;
            gradUi_[6] = gradPos_;
        }
        if (sel_.consumeFullDirty()) screenFull_ = true;
        if (!canvasFreeze && playing_.load() && frameData_.size() > 1) {
            const auto now = std::chrono::steady_clock::now();
            const int interval = std::max(20, 1000 / std::max(1, playFps_.load()));
            if (std::chrono::duration_cast<std::chrono::milliseconds>(now - playNext_).count() >= interval) {
                playNext_ = now;
                const int last = (int)frameData_.size() - 1;
                int a = std::max(0, std::min(loopStart_.load(), last));
                int b = loopEnd_.load() < 0 ? last : std::max(0, std::min(loopEnd_.load(), last));
                int lo = std::min(a, b), hi = std::max(a, b);
                if (frame_ < 0 || frame_ > last) frame_ = lo;
                int next = frame_ + 1;
                if (next < lo || next > hi) next = lo;
                if (playBrush_.load()) storeFrameData();
                // 播放动画时保持用户当前选中的图层: 帧数据里记录的 active layer 不参与播放
                const int keepLayer = canvas_.active();
                frame_ = next;
                frameIdx_.store(frame_);
                loadFrameData(frame_);
                if (canvas_.layers() > 0) {
                    const int nl = std::max(0, std::min(keepLayer, canvas_.layers() - 1));
                    canvas_.setActive(nl);
                    activeLayer_.store(nl);
                }
            }
        }
        if (!canvasFreeze) serviceExport();
        // 自动保存快照分帧打包(每帧 ~2MB); 有编辑会自动中止, 不阻塞渲染/输入
        if (!canvasFreeze) tickSnapshot();
        // 增量 PNG 导出: 每帧最多 6ms, 大画布导出期间画面/输入保持响应
        if (!canvasFreeze) {
            std::unique_ptr<PngExporter> job;
            {
                std::lock_guard<std::mutex> lk(exMu_);
                job = std::move(pngJob_);
            }
            if (job) {
                // 每帧最多 6ms(旧值 20ms 会让导出期间掉到 ~50fps 并触发 jank)
                if (job->step(canvas_, 6)) {
                    ExportResult er = job->result();
                    char eb[256];
                    if (er.ok) snprintf(eb, sizeof eb, "ok %dx%d bytes=%lld ms=%ld", er.w, er.h, er.bytes, er.ms);
                    else snprintf(eb, sizeof eb, "err %s", er.err.c_str());
                    {
                        std::lock_guard<std::mutex> lk(exMu_);
                        exResult_ = eb;
                        exDone_ = true;
                    }
                    exCv_.notify_all();
                    LOGI("png export done: %s", eb);
                } else {
                    std::lock_guard<std::mutex> lk(exMu_);
                    pngJob_ = std::move(job);
                }
            }
        }
        // 落定像素后台分帧搬: 只在空闲/已离开当前选区/切到画笔时推进, 绝不阻塞用户操作
        if (!canvasFreeze && sel_.pendingMove() && !sel_.floating()) {
            const bool idle = (Clock::now() - lastInputT_) > std::chrono::milliseconds(1200);
            if (idle || sel_.applyAllowed() || sel_.mode() != 1) {
                if (!canvas_.recording()) { canvas_.beginRecord(); selMoveOpen_ = true; }
                sel_.tickCommit(400 * 1024);
                closeSelMoveRecordIfDone();
            }
        }
        if (!canvasFreeze && stress_.load()) {
            stressPhase_++;
            float w = (float)canvas_.width(), h = (float)canvas_.height();
            int sx = (int)(w * 0.5f + cosf(stressPhase_ * 0.045f) * w * 0.30f);
            int sy = (int)(h * 0.5f + sinf(stressPhase_ * 0.063f) * h * 0.30f);
            raster::stampCircle(canvas_, sx, sy, 8, 0xFFFFFFFFu);
        }
        // 异步切帧就绪: 渲染线程只做一次 tile 指针表 swap
        if (frameReady_.exchange(false)) finishFrameSwitch();
        // 画布脏区 → 金字塔局部更新 + 屏幕脏矩形
        flushScreenDirty();
        // 剪切蒙版变化(裁剪/落定) → 整屏重绘
        {
            const bool cut = canvas_.cutActive();
            const int cx0 = canvas_.cutX0(), cy0 = canvas_.cutY0();
            const int cx1 = canvas_.cutX1(), cy1 = canvas_.cutY1();
            const int cl = canvas_.cutLayer();
            if (cut != lastCut_ || cx0 != lastCutX0_ || cy0 != lastCutY0_ ||
                cx1 != lastCutX1_ || cy1 != lastCutY1_ || cl != lastCutLayer_) {
                // 注意: mip 必须保留被剪层的像素(浮层按源坐标从 mip 采样), 所以这里只重绘屏幕;
                // 剪切矩形内的背景由 renderScreen 用 composeLayersPixelEx(applyCut=true) 现算。
                screenFull_ = true;
                lastCut_ = cut; lastCutX0_ = cx0; lastCutY0_ = cy0;
                lastCutX1_ = cx1; lastCutY1_ = cy1; lastCutLayer_ = cl;
            }
        }
        pendingN_.store(0);
        if (sel_.holdFloat() && !sel_.pendingMove() && !sel_.floating() &&
            canvas_.dirtyTiles().empty() && sdX1_ < sdX0_) sel_.endHold();
        display_.setGrayView(grayView_.load(std::memory_order_relaxed));
        // 空闲(无脏区/无动画/无选区蚂蚁线)时不再每 vsync 重画同一帧:
        // 跳过 draw 并小睡, 输入来了下一轮立刻醒; 省掉常驻 120fps 的空转 GPU/CPU。
        // 停手后再热 400ms 再睡: 避免刚停下就降频/丢缓存, 下一笔/下一次平移的第一帧变慢。
        const auto nowP = Clock::now();
        const bool activeWork = screenFull_ || sdX1_ >= sdX0_ || fullBurst_ > 0 ||
                                sel_.active() || playing_.load() || viewAnimActive_ ||
                                measMagShown_ || gradActive_;
        const bool warm = (nowP - lastPresentT_) < std::chrono::milliseconds(400);
        const bool presenting = activeWork || warm;
        if (presenting) {
            if (activeWork) lastPresentT_ = nowP;
            if (screenFull_ || sdX1_ >= sdX0_ || fullBurst_ > 0) renderScreen();
            display_.draw();
            if (frames == 0) {   // 首帧落屏(用于量应用启动)
                static bool firstLogged = false;
                if (!firstLogged) { firstLogged = true; LOGI("first frame drawn"); }
            }
            {
                auto now2 = Clock::now();
                int us = (int)std::chrono::duration_cast<std::chrono::microseconds>(now2 - workT0).count();
                lastFrameUs_.store(us);
                int mx = maxFrameUs_.load();
                if (us > mx) maxFrameUs_.store(us);
                if (us > 8333) over_.fetch_add(1);
            }
            if (gHintSession && pReportWork) {
                auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - workT0).count();
                pReportWork(gHintSession, (int64_t)ns);
            }
            frames++;
        } else {
            // 空闲: 保留上次 fps 读数(从没量到过就给标称 120, 电量/性能栏别显示 0), 重新开窗; 小睡降低空转
            if (fps_.load() == 0) fps_.store(120);
            frames = 0;
            fpsT0 = Clock::now();
            if (idleSleepOn_) std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }
        auto now = Clock::now();
        if (presenting &&
            std::chrono::duration_cast<std::chrono::milliseconds>(now - fpsT0).count() >= 1000) {
            fps_.store(frames);
            frames = 0;
            fpsT0 = now;
            maxFrameUs_.store(0);
            jank_.store(over_.exchange(0));
        }
        frameStart = now;
        (void)frameStart;
    }
}

void Engine::markScreenRect(int cx0, int cy0, int cx1, int cy1) {
    int x0, y0, x1, y1;
    display_.canvasRectToScreen(cx0, cy0, cx1, cy1, &x0, &y0, &x1, &y1);
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(display_.viewW() - 1, x1);
    y1 = std::min(display_.viewH() - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    if (sdX1_ < sdX0_) { sdX0_ = x0; sdY0_ = y0; sdX1_ = x1; sdY1_ = y1; }
    else {
        sdX0_ = std::min(sdX0_, x0); sdY0_ = std::min(sdY0_, y0);
        sdX1_ = std::max(sdX1_, x1); sdY1_ = std::max(sdY1_, y1);
    }
}

static size_t recBytes(const Canvas::UndoRecord& r) {
    size_t b = sizeof(Canvas::TileSnap) * r.size();
    for (const auto& s : r) b += s.px.size() * sizeof(uint32_t);
    return b;
}

// ---- 形状画布像素预览: 和落层完全同一批像素(旧项目 DrawShapePreview/Backup 机制) ----
void Engine::shapePrevBegin() {
    shapePrevRestore();
    shPrevLayer_ = -1;
}

void Engine::shapePrevRestore() {
    if (shPrevIdx_.empty()) { shPrevLayer_ = -1; return; }
    const int layer = shPrevLayer_ >= 0 ? shPrevLayer_ : canvas_.active();
    const int cw = canvas_.width(), ch = canvas_.height();
    const int ctx = canvas_.tilesX();
    int lastTi = -1;
    uint32_t* tp = nullptr;
    int bx0 = INT_MAX, by0 = INT_MAX, bx1 = INT_MIN, by1 = INT_MIN;
    for (size_t i = 0; i < shPrevIdx_.size(); i++) {
        const int id = shPrevIdx_[i];
        const int x = id % cw, y = id / cw;
        if ((unsigned)x >= (unsigned)cw || (unsigned)y >= (unsigned)ch) continue;
        // 按 tile 缓存写入指针: 逐像素 setPixelL 会重复做 tile 查找
        const int ti = (y >> Canvas::TILE_SHIFT) * ctx + (x >> Canvas::TILE_SHIFT);
        if (ti != lastTi) { tp = canvas_.tileForWrite(layer, ti); lastTi = ti; }
        tp[((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) +
           (x & (Canvas::TILE - 1))] = shPrevOld_[i];
        bx0 = std::min(bx0, x); bx1 = std::max(bx1, x);
        by0 = std::min(by0, y); by1 = std::max(by1, y);
    }
    if (bx1 >= bx0) {
        canvas_.markDirtyRect(bx0, by0, bx1, by1);
    }
    shPrevIdx_.clear(); shPrevOld_.clear();
    shPrevLayer_ = -1;
}

void Engine::shapePrevCommit() {
    // 预览像素就是最终结果: 清备份但不恢复
    shPrevIdx_.clear(); shPrevOld_.clear();
    shPrevLayer_ = -1;
}

void Engine::shapePrevPaint(const std::vector<int>& xy, uint32_t color, int layer) {
    const int cw = canvas_.width(), ch = canvas_.height();
    std::vector<int> idx;
    idx.reserve(xy.size() / 2);
    for (size_t i = 0; i + 1 < xy.size(); i += 2) {
        const int x = xy[i], y = xy[i + 1];
        if ((unsigned)x >= (unsigned)cw || (unsigned)y >= (unsigned)ch) continue;
        idx.push_back(y * cw + x);
    }
    if (idx.empty()) return;
    std::sort(idx.begin(), idx.end());
    idx.erase(std::unique(idx.begin(), idx.end()), idx.end());
    shPrevLayer_ = layer;
    const int ctx = canvas_.tilesX();
    const bool lock = canvas_.alphaLock();
    int lastTi = -1;
    uint32_t* tp = nullptr;
    int bx0 = INT_MAX, by0 = INT_MAX, bx1 = INT_MIN, by1 = INT_MIN;
    for (int id : idx) {
        const int x = id % cw, y = id / cw;
        const int ti = (y >> Canvas::TILE_SHIFT) * ctx + (x >> Canvas::TILE_SHIFT);
        if (ti != lastTi) { tp = canvas_.tileForWrite(layer, ti); lastTi = ti; }
        const size_t off = ((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) +
                           (x & (Canvas::TILE - 1));
        const uint32_t old = tp[off];
        if (lock && !(old >> 24)) continue;   // 与 paintPixel 的本层蒙版语义一致
        shPrevIdx_.push_back(id);
        shPrevOld_.push_back(old);
        raster::overPixel(tp[off], color);
        bx0 = std::min(bx0, x); bx1 = std::max(bx1, x);
        by0 = std::min(by0, y); by1 = std::max(by1, y);
    }
    if (bx1 >= bx0) {
        canvas_.markDirtyRect(bx0, by0, bx1, by1);
    }
}

void Engine::cancelShapePreview() {
    if (gradActive_ || gradDragging_) gradCancel();
    if (!shPrevIdx_.empty()) shapePrevRestore();
    if (canvas_.recording()) canvas_.endRecord();
    polyActive_ = false;
    polyHeld_ = false;
    poly_.clear();
    leafActive_ = false;
    shActive_ = false;
    lasso_.clear();
    screenFull_ = true;
}

// ===== 形状渐变(方形/圆形, 自带 alpha 蒙版) =====
// 预览靠画布 UndoRecord 保存"渐变前"的 tile 快照, 不占每像素内存;
// 预览期间图层像素就是应用后的样子(和形状工具预览同一套路), 应用时再还原+重画落一条撤销。
void Engine::gradRefreshAnts() {
    const int ax0 = std::min(gradX0_, gradX1_), ax1 = std::max(gradX0_, gradX1_);
    const int ay0 = std::min(gradY0_, gradY1_), ay1 = std::max(gradY0_, gradY1_);
    display_.setSelection(ax0, ay0, ax1, ay1, true);
    if (gradShape_ == 1) {
        std::vector<int> segs;
        const int N = 96;
        const double ccx = (ax0 + ax1 + 1) * 0.5, ccy = (ay0 + ay1 + 1) * 0.5;
        const double rx = std::max(1.0, (ax1 - ax0 + 1) * 0.5);
        const double ry = std::max(1.0, (ay1 - ay0 + 1) * 0.5);
        double px = ccx + rx, py = ccy;
        for (int i = 1; i <= N; i++) {
            const double a = 6.283185307179586 * i / N;
            const double nx = ccx + rx * std::cos(a), ny = ccy + ry * std::sin(a);
            segs.push_back((int)std::lround(px)); segs.push_back((int)std::lround(py));
            segs.push_back((int)std::lround(nx)); segs.push_back((int)std::lround(ny));
            px = nx; py = ny;
        }
        display_.setSelectionOutline(segs);
    }
}

void Engine::gradPaintPixels() {
    const int cw = canvas_.width(), ch = canvas_.height();
    const int ax0 = std::max(0, std::min(gradX0_, gradX1_));
    const int ay0 = std::max(0, std::min(gradY0_, gradY1_));
    const int ax1 = std::min(cw - 1, std::max(gradX0_, gradX1_));
    const int ay1 = std::min(ch - 1, std::max(gradY0_, gradY1_));
    if (ax0 > ax1 || ay0 > ay1 || gradLayer_ < 0) return;
    const bool circle = gradShape_ == 1;
    const int preset = gradPreset_;
    const float ccx = (float)(ax0 + ax1 + 1) * 0.5f, ccy = (float)(ay0 + ay1 + 1) * 0.5f;
    const float rx = std::max(0.5f, (float)(ax1 - ax0 + 1) * 0.5f);
    const float ry = std::max(0.5f, (float)(ay1 - ay0 + 1) * 0.5f);
    // 方形渐变: 任意角度(0=→ 90=↓), 像素中心投影到渐变轴, u: 0=实色端 1=远端
    const float ang = (float)gradAngle_ * 3.14159265358979f / 180.0f;
    const float dirX = std::cos(ang), dirY = std::sin(ang);
    // 矩形在该方向上的半宽 = |cos|*rx + |sin|*ry(支撑函数), 让 u 恰好覆盖 0..1
    const float halfExt = std::max(0.5f, std::fabs(dirX) * rx + std::fabs(dirY) * ry);
    const float invSpan = 1.0f / (2.0f * halfExt);
    // 范围: 淡出带占剩余路径的比例; 位置: 淡出起点(0=实色端, 50=中间)
    const float rng = std::max(0.05f, std::min(1.0f, (float)gradRange_ / 100.0f));
    const float pos = std::max(0.0f, std::min(0.95f, (float)gradPos_ / 100.0f));
    // 淡出带 [pos, pos+(1-pos)*rng]: 带前保持实色, 带后完全透明
    const float band = std::max(0.0001f, (1.0f - pos) * rng);
    const float invBand = 1.0f / band;
    for (int y = ay0; y <= ay1; y++) {
        for (int x = ax0; x <= ax1; x++) {
            float u;
            if (circle) {
                const float nx = ((float)x + 0.5f - ccx) / rx;
                const float ny = ((float)y + 0.5f - ccy) / ry;
                const float d = std::sqrt(nx * nx + ny * ny);
                if (d > 1.0f) continue;
                // 0: 中心实→边缘透; 1: 边缘实→中心透
                u = (preset == 1) ? (1.0f - d) : d;
            } else {
                const float v = ((float)x + 0.5f - ccx) * dirX +
                                ((float)y + 0.5f - ccy) * dirY;
                u = (v + halfExt) * invSpan;
            }
            // 透明位置之前完全不动; 之后 alpha 线性淡到 0(覆盖色→无, 不掺白)
            if (u <= pos) continue;
            float factor = 1.0f - (u - pos) * invBand;
            if (factor <= 0.0f) factor = 0.0f;
            if (factor >= 1.0f) continue;
            const int l = gradLayer_;
            const uint32_t oldv = canvas_.getPixelL(l, x, y);
            const uint32_t a = oldv >> 24;
            if (!a) continue;
            const uint32_t na = (uint32_t)std::lround((float)a * factor);
            if (na >= a) continue;
            if (na == 0) { canvas_.setPixelL(l, x, y, 0u); continue; }
            // 画布是预乘 alpha: RGB 与 alpha 按同一系数缩放, 颜色保持原色, 不会偏白
            const uint32_t nr = (uint32_t)std::lround((float)(oldv & 255u) * factor);
            const uint32_t ng = (uint32_t)std::lround((float)((oldv >> 8) & 255u) * factor);
            const uint32_t nb = (uint32_t)std::lround((float)((oldv >> 16) & 255u) * factor);
            canvas_.setPixelL(l, x, y, (na << 24) | (nb << 16) | (ng << 8) | nr);
        }
    }
    canvas_.markDirtyRect(ax0, ay0, ax1, ay1);
}

void Engine::gradApplyPreview() {
    // 先还原上一版预览, 再按当前预设重画(换预设=实时预览)
    if (!gradRec_.empty()) {
        auto back = canvas_.applyRecord(gradRec_);
        (void)back;
        gradRec_.clear();
    }
    // 大画布保护: 整片渐变会快照太多 tile(每块 256KB), 超预算就不做预览
    {
        const int cw = canvas_.width(), ch = canvas_.height();
        const int ax0 = std::max(0, std::min(gradX0_, gradX1_));
        const int ay0 = std::max(0, std::min(gradY0_, gradY1_));
        const int ax1 = std::min(cw - 1, std::max(gradX0_, gradX1_));
        const int ay1 = std::min(ch - 1, std::max(gradY0_, gradY1_));
        if (ax0 > ax1 || ay0 > ay1) return;
        const long long area = (long long)(ax1 - ax0 + 1) * (long long)(ay1 - ay0 + 1);
        const long long tiles = (long long)((ax1 >> Canvas::TILE_SHIFT) - (ax0 >> Canvas::TILE_SHIFT) + 1) *
                                (long long)((ay1 >> Canvas::TILE_SHIFT) - (ay0 >> Canvas::TILE_SHIFT) + 1);
        if (area > 16ll * 1000 * 1000 || tiles > 160) return;
    }
    canvas_.beginRecord();
    gradPaintPixels();
    gradRec_ = canvas_.endRecord();
    if (gradRec_.size() > 160) {
        auto back = canvas_.applyRecord(gradRec_);   // 超预算: 丢弃预览
        (void)back;
        gradRec_.clear();
    }
    gradRefreshAnts();
}

void Engine::gradCommit() {
    if (!gradActive_ || gradRec_.empty() ||
        canvas_.width() != gradCw_ || canvas_.height() != gradCh_) { gradCancel(); return; }
    auto back = canvas_.applyRecord(gradRec_);   // 回到渐变前
    (void)back;
    gradRec_.clear();
    beginStrokeRecord();
    gradPaintPixels();
    endStrokeRecord();                           // 一条正常撤销记录 + 缩略图/回放
    gradActive_ = false;
    gradDragging_ = false;
    gradLayer_ = -1;
    display_.setSelection(0, 0, 0, 0, false);
}

void Engine::gradCancel() {
    if (gradActive_ && !gradRec_.empty() &&
        canvas_.width() == gradCw_ && canvas_.height() == gradCh_) {
        auto back = canvas_.applyRecord(gradRec_);   // 恢复原图
        (void)back;
    }
    gradRec_.clear();
    gradActive_ = false;
    gradDragging_ = false;
    gradLayer_ = -1;
    display_.setSelection(0, 0, 0, 0, false);
    screenFull_ = true;
}

void Engine::beginStrokeRecord() {
    if (canvas_.recording()) return;   // 上一笔没正常收尾(极少见): 继续用同一条记录
    canvas_.beginRecord();
}

// 选区工具内部落像素(移动落定/删除/变换/粘贴/长按裁剪)的撤销边界。
// 引擎自己在某些动作里已经开了记录时只记嵌套深度, 不重复开/收。
void Engine::selEditBegin() {
    if (canvas_.recording()) { selEditDepth_++; return; }
    canvas_.beginRecord();
    selEditDepth_ = 1;
    selEditOwn_ = true;
}

void Engine::selEditEnd() {
    if (selEditDepth_ <= 0) return;
    selEditDepth_--;
    if (selEditDepth_ == 0 && selEditOwn_) {
        selEditOwn_ = false;
        endStrokeRecord();
    }
    closeSelMoveRecordIfDone();
}

// 异步落定(tickCommit)可能跨多帧搬像素: 从第一帧开始一直用同一条记录, 搬完再收
void Engine::closeSelMoveRecordIfDone() {
    if (!selMoveOpen_ || sel_.pendingMove()) return;
    selMoveOpen_ = false;
    endStrokeRecord();
}

void Engine::discardOpenStrokeRecord() {
    if (!canvas_.recording()) return;
    auto rec = canvas_.endRecord();
    selMoveOpen_ = false;
    selEditOwn_ = false;
    selEditDepth_ = 0;
    if (!rec.empty()) LOGI("discard open stroke record (%zu tiles)", rec.size());
}

size_t Engine::histBytes(const Hist& h) const {
    size_t b = recBytes(h.rec) + h.spans.size() * sizeof(Canvas::Span);
    if (h.doc) {
        for (const auto& f : h.doc->frames) b += f.size();
    }
    if (h.meta) b += 64 + h.meta->names.size() * 24;
    return b;
}

void Engine::trimUndo() {
    while (undoBytes_ > 48u * 1024 * 1024 && undo_.size() > 1) {
        undoBytes_ -= undo_.front().bytes;
        undo_.erase(undo_.begin());
    }
}

void Engine::pushHist(Hist&& h, bool recordStep) {
    if (h.bytes == 0) h.bytes = histBytes(h);
    if (h.rec.empty() && h.spans.empty() && !h.meta && !h.doc) return;
    undoBytes_ += h.bytes;
    undo_.push_back(std::move(h));
    trimUndo();
    redo_.clear();
    redoBytes_ = 0;
    refreshFrameThumbs();
    if (recordStep) recordReplayFrame();
}

void Engine::endStrokeRecord() {
    if (!canvas_.recording()) return;
    // 平铺图层: 本笔改到的像素同步到所有格子(旧 tileMode 行为); 写入仍记在本条撤销记录里
    {
        const int al = canvas_.active();
        if (al >= 0 && al < canvas_.layers() && canvas_.layerMode(al) == 1)
            canvas_.replicateRecordedPixels(al, canvas_.layerTileW(al), canvas_.layerTileH(al),
                                            canvas_.layerTileOffX(al), canvas_.layerTileOffY(al));
    }
    auto rec = canvas_.endRecord();
    selEditOwn_ = false;
    if (rec.empty()) return;
    Hist h; h.kind = 0; h.frame = frame_;
    h.rec = std::move(rec);
    h.bytes = histBytes(h);
    pushHist(std::move(h), true);
}

void Engine::clearHistory() {
    undo_.clear(); redo_.clear();
    undoBytes_ = redoBytes_ = 0;
    selMoveOpen_ = false;
    selEditOwn_ = false;
    selEditDepth_ = 0;
}

// 切标签前: 把当前标签的撤销/重做栈停放到 parkedHist_(按标签 id)
void Engine::parkTabHistory() {
    if (histTabKey_ < 0) return;
    ParkedHist ph;
    ph.undo = std::move(undo_);
    ph.redo = std::move(redo_);
    ph.undoBytes = undoBytes_;
    ph.redoBytes = redoBytes_;
    undo_.clear(); redo_.clear();
    undoBytes_ = redoBytes_ = 0;
    // 同一标签重新停放时替换旧数据
    auto old = parkedHist_.find(histTabKey_);
    if (old != parkedHist_.end()) parkedHist_.erase(old);
    for (auto it = parkedOrder_.begin(); it != parkedOrder_.end(); ++it)
        if (*it == histTabKey_) { parkedOrder_.erase(it); break; }
    parkedHist_[histTabKey_] = std::move(ph);
    parkedOrder_.push_back(histTabKey_);
    histTabKey_ = -1;
    // 总量上限 64MB: 超了从最旧的标签开始丢
    size_t total = 0;
    for (auto& kv : parkedHist_) total += kv.second.undoBytes + kv.second.redoBytes;
    while (total > 64u * 1024 * 1024 && parkedOrder_.size() > 1) {
        const int victim = parkedOrder_.front();
        parkedOrder_.pop_front();
        auto v = parkedHist_.find(victim);
        if (v != parkedHist_.end()) {
            total -= v->second.undoBytes + v->second.redoBytes;
            parkedHist_.erase(v);
        }
    }
    LOGI("history park tab=%d undo=%zu redo=%zu total=%zuMB", histTabKey_, ph.undoBytes, ph.redoBytes, total / (1024 * 1024));
}

// 载入标签后: 挂回该标签的撤销/重做栈(没有就空)
void Engine::setTabHistoryKey(int id) {
    if (id == histTabKey_) return;
    histTabKey_ = id;
    auto it = parkedHist_.find(id);
    if (it == parkedHist_.end()) {
        // 没有停放记录: 当前栈就是刚载入快照里的该标签历史, 保留不动
        return;
    }
    undo_ = std::move(it->second.undo);
    redo_ = std::move(it->second.redo);
    undoBytes_ = it->second.undoBytes;
    redoBytes_ = it->second.redoBytes;
    parkedHist_.erase(it);
    for (auto o = parkedOrder_.begin(); o != parkedOrder_.end(); ++o)
        if (*o == id) { parkedOrder_.erase(o); break; }
    LOGI("history restore tab=%d undo=%zu redo=%zu", id, undoBytes_, redoBytes_);
}

void Engine::dropTabHistory(int id) {
    parkedHist_.erase(id);
    for (auto o = parkedOrder_.begin(); o != parkedOrder_.end(); ++o)
        if (*o == id) { parkedOrder_.erase(o); break; }
}

std::unique_ptr<Engine::MetaState> Engine::captureMeta() {
    auto m = std::make_unique<MetaState>();
    const int n = canvas_.layers();
    m->vis.resize((size_t)n); m->lock.resize((size_t)n);
    m->lmode.resize((size_t)n); m->tlGrid.resize((size_t)n);
    m->tlIndep.resize((size_t)n); m->blend.resize((size_t)n);
    m->names.resize((size_t)n);
    m->opacity.resize((size_t)n);
    m->tlW.resize((size_t)n); m->tlH.resize((size_t)n);
    m->tlOX.resize((size_t)n); m->tlOY.resize((size_t)n); m->tlLock.resize((size_t)n);
    m->tlGridCol.resize((size_t)n);
    for (int l = 0; l < n; l++) {
        m->vis[(size_t)l] = canvas_.layerVisible(l) ? 1 : 0;
        m->lock[(size_t)l] = canvas_.isLayerLocked(l) ? 1 : 0;
        m->lmode[(size_t)l] = (uint8_t)canvas_.layerMode(l);
        m->tlGrid[(size_t)l] = canvas_.layerShowGrid(l) ? 1 : 0;
        m->tlIndep[(size_t)l] = canvas_.layerTileIndep(l) ? 1 : 0;
        m->blend[(size_t)l] = (uint8_t)canvas_.layerBlend(l);
        m->names[(size_t)l] = canvas_.layerName(l);
        m->opacity[(size_t)l] = canvas_.layerOpacity(l);
        m->tlW[(size_t)l] = canvas_.layerTileW(l);
        m->tlH[(size_t)l] = canvas_.layerTileH(l);
        m->tlOX[(size_t)l] = canvas_.layerTileOffX(l);
        m->tlOY[(size_t)l] = canvas_.layerTileOffY(l);
        m->tlLock[(size_t)l] = canvas_.layerTileLockFrame(l);
        m->tlGridCol[(size_t)l] = canvas_.layerGridColor(l);
    }
    const auto& g = raster::mode25D();
    m->m25on = g.on; m->m25depth = g.depth; m->m25dir = g.dir;
    return m;
}

void Engine::applyMetaState(const MetaState& m) {
    const int n = std::min((int)m.vis.size(), canvas_.layers());
    for (int l = 0; l < n; l++) {
        canvas_.setLayerVisible(l, m.vis[(size_t)l] != 0);
        canvas_.setLayerLocked(l, m.lock[(size_t)l] != 0);
        canvas_.setLayerName(l, m.names[(size_t)l]);
        canvas_.setLayerOpacity(l, m.opacity[(size_t)l]);
        canvas_.setLayerMode(l, m.lmode[(size_t)l]);
        canvas_.setLayerBlend(l, m.blend[(size_t)l]);
        canvas_.setLayerTile(l, m.tlW[(size_t)l], m.tlH[(size_t)l],
                             m.tlOX[(size_t)l], m.tlOY[(size_t)l],
                             m.tlGrid[(size_t)l] != 0, m.tlGridCol[(size_t)l],
                             m.tlIndep[(size_t)l] != 0, m.tlLock[(size_t)l]);
    }
    auto& g = raster::mode25D();
    g.on = m.m25on;
    g.depth = std::max(1, std::min(20, m.m25depth));
    g.dir = m.m25dir & 3;
    raster::setYaw25D(0);        // 元数据撤销不保留视角角, 避免和快照状态不一致
    display_.setRotation25(0);
    m25OnA_.store(g.on, std::memory_order_relaxed);
    m25DepthA_.store(g.depth, std::memory_order_relaxed);
    m25DirA_.store(g.dir, std::memory_order_relaxed);
    layerMetaRev_++;
    refreshLayerMetaCache();
    refreshLayerPropsCache();
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
}

std::unique_ptr<Engine::DocState> Engine::captureDoc() {
    auto d = std::make_unique<DocState>();
    if (gradActive_ || gradDragging_) gradCancel();
    if (frame_ < 0) frame_ = 0;
    if (frameData_.empty()) frameData_.resize(1);
    if (frame_ >= (int)frameData_.size()) frameData_.resize((size_t)frame_ + 1);
    storeFrameData();
    d->w = canvas_.width(); d->h = canvas_.height();
    d->active = canvas_.active(); d->layers = canvas_.layers();
    d->frame = frame_;
    d->playFps = playFps_.load(); d->loopStart = loopStart_.load(); d->loopEnd = loopEnd_.load();
    d->frames = frameData_;
    d->meta = *captureMeta();
    return d;
}

void Engine::applyDocState(DocState&& d) {
    sel_.clearState();
    canvas_.clearCutRect();
    const bool dimsChanged = canvas_.width() != d.w || canvas_.height() != d.h;
    canvas_.create(d.w, d.h, std::max(1, d.layers));
    if (dimsChanged) {
        display_.resizeCanvas(d.w, d.h);
    }
    mip_.reset(&canvas_);
    frameData_ = std::move(d.frames);
    frame_ = std::max(0, std::min(d.frame, (int)frameData_.size() - 1));
    playing_.store(0);
    playFps_.store(std::max(1, std::min(d.playFps, 60)));
    loopStart_.store(d.loopStart);
    loopEnd_.store(d.loopEnd);
    if (!frameData_.empty() && frame_ < (int)frameData_.size() && !frameData_[(size_t)frame_].empty())
        deserializeCanvas(frameData_[(size_t)frame_].data(), frameData_[(size_t)frame_].size());
    else
        canvas_.setActive(std::max(0, std::min(d.active, canvas_.layers() - 1)));
    applyMetaState(d.meta);
    frameIdx_.store(frame_);
    frameCount_.store(std::max(1, (int)frameData_.size()));
    frameLayerCount_ = canvas_.layers();
    layerCount_.store(canvas_.layers());
    activeLayer_.store(canvas_.active());
    lastSavedRev_.store(0);
    lastSavedFrameRev_ = 0;
    lastSavedSelStateRev_ = selStateRev_;
    frameRev_.fetch_add(1);
    onionGen_.fetch_add(1);
    thumbRev_.fetch_add(1);
    {
        std::lock_guard<std::mutex> lk(thumbMu_);
        frameThumbs_.clear();
    }
    if ((long long)canvas_.width() * canvas_.height() <= 4ll * 1024 * 1024) {
        std::lock_guard<std::mutex> lk(thumbMu_);
        frameThumbs_.assign(frameData_.empty() ? 1 : frameData_.size(),
            std::vector<std::vector<uint32_t>>((size_t)canvas_.layers(),
                std::vector<uint32_t>((size_t)THUMB * THUMB, 0u)));
    }
    refreshFrameThumbs();
    canvas_.markDirtyRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
}

void Engine::switchFrameForHistory(int f) {
    if (f < 0 || f >= (int)frameData_.size() || f == frame_) return;
    sel_.clearState();
    storeFrameData();
    frame_ = f;
    frameIdx_.store(f);
    frameCount_.store((int)frameData_.size());
    loadFrameData(f);
    screenFull_ = true;
}

bool Engine::metaCoalesce(int key) {
    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    const bool same = key == metaCoalesceKey_ && (now - metaCoalesceMs_) < 700;
    metaCoalesceKey_ = key;
    metaCoalesceMs_ = now;
    return same;
}

void Engine::pushMetaUndo() {
    Hist h;
    h.kind = 2;
    h.frame = frame_;
    h.meta = captureMeta();
    h.bytes = histBytes(h);
    pushHist(std::move(h), false);
}

void Engine::pushDocUndo() {
    // 浮层先落定/收尾, 否则文档快照拿到的是被抠空的源区域
    if (sel_.floating() || sel_.pendingMove()) {
        sel_.commitFloat();
        sel_.finishCommit();
    }
    // 文档快照 = 全部帧的一份拷贝; 太大就退回"结构操作清空历史", 避免 OOM
    if (frame_ >= 0 && frame_ < (int)frameData_.size()) storeFrameData();
    size_t est = 0;
    for (const auto& f : frameData_) est += f.size();
    if (est > 32u * 1024 * 1024) {
        LOGI("doc undo skipped: frames=%zu bytes=%zu", frameData_.size(), est);
        clearHistory();
        return;
    }
    Hist h;
    h.kind = 3;
    h.frame = frame_;
    h.doc = captureDoc();
    h.bytes = histBytes(h);
    pushHist(std::move(h), false);
}

void Engine::undo() {
    // 浮层/异步落定先收尾成一条记录, 再撤销它(用户按撤销通常就是想撤回这次移动)
    if (sel_.floating() || sel_.pendingMove()) {
        sel_.commitFloat();
        sel_.finishCommit();
    }
    closeSelMoveRecordIfDone();
    if (canvas_.recording() || undo_.empty()) return;
    Hist h = std::move(undo_.back());
    undo_.pop_back();
    undoBytes_ -= h.bytes;
    Hist r;
    if (h.kind == 3 && h.doc) {                 // 文档快照: 图层/帧/画布尺寸结构操作
        r.kind = 3;
        r.doc = captureDoc();
        r.frame = frame_;
        applyDocState(std::move(*h.doc));
    } else if (h.kind == 2 && h.meta) {         // 图层元数据
        r.kind = 2;
        r.frame = h.frame;
        r.meta = captureMeta();
        applyMetaState(*h.meta);
    } else {
        if ((h.kind == 0 || h.kind == 1) && h.frame != frame_ &&
            h.frame >= 0 && h.frame < (int)frameData_.size())
            switchFrameForHistory(h.frame);      // 记录属于别的帧: 先切回去再撤销
        auto back = canvas_.applyRecord(h.rec);
        if (h.isFill) canvas_.paintSpans(h.fillLayer, h.spans, h.cBefore);
        r.kind = h.kind; r.frame = h.frame;
        r.rec = std::move(back);
        r.spans = std::move(h.spans);
        r.cBefore = h.cBefore; r.cAfter = h.cAfter;
        r.fillLayer = h.fillLayer; r.isFill = h.isFill;
        r.bytes = histBytes(r);
        screenFull_ = true;
        mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    }
    if (r.bytes == 0) r.bytes = histBytes(r);
    redoBytes_ += r.bytes;
    redo_.push_back(std::move(r));
    { std::lock_guard<std::mutex> lk(recMu_); recPts_.clear(); recPtsPrevMs_ = 0; }
    recordReplayFrame();
}

void Engine::redo() {
    if (sel_.floating() || sel_.pendingMove()) {
        sel_.commitFloat();
        sel_.finishCommit();
    }
    closeSelMoveRecordIfDone();
    if (canvas_.recording() || redo_.empty()) return;
    Hist h = std::move(redo_.back());
    redo_.pop_back();
    redoBytes_ -= h.bytes;
    Hist u;
    if (h.kind == 3 && h.doc) {
        u.kind = 3;
        u.doc = captureDoc();
        u.frame = frame_;
        applyDocState(std::move(*h.doc));
    } else if (h.kind == 2 && h.meta) {
        u.kind = 2;
        u.frame = h.frame;
        u.meta = captureMeta();
        applyMetaState(*h.meta);
    } else {
        if ((h.kind == 0 || h.kind == 1) && h.frame != frame_ &&
            h.frame >= 0 && h.frame < (int)frameData_.size())
            switchFrameForHistory(h.frame);
        auto back = canvas_.applyRecord(h.rec);
        if (h.isFill) canvas_.paintSpans(h.fillLayer, h.spans, h.cAfter);
        u.kind = h.kind; u.frame = h.frame;
        u.rec = std::move(back);
        u.spans = std::move(h.spans);
        u.cBefore = h.cBefore; u.cAfter = h.cAfter;
        u.fillLayer = h.fillLayer; u.isFill = h.isFill;
        u.bytes = histBytes(u);
        screenFull_ = true;
        mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    }
    if (u.bytes == 0) u.bytes = histBytes(u);
    undoBytes_ += u.bytes;
    undo_.push_back(std::move(u));
    trimUndo();
    { std::lock_guard<std::mutex> lk(recMu_); recPts_.clear(); recPtsPrevMs_ = 0; }
    recordReplayFrame();
}

void Engine::serviceExport() {
    std::string path;
    int kind = 0, scale = 1, cols = 4, delay = 100;
    {
        std::lock_guard<std::mutex> lk(exMu_);
        if (!exPending_) return;
        exPending_ = false;
        path = exPath_;
        kind = exKind_;
        scale = exScale_;
        cols = exCols_;
        delay = exDelay_;
    }
    if (kind >= 2 && kind <= 5) {   // 精灵图/逐帧/GIF/叠层图: 工作线程跑, 渲染线程继续出画面
        if (exportTh_.joinable()) exportTh_.join();
        exportAbort_.store(false);
        exportBusy_.store(true);
        canvasBusy_.store(true);
        exportTh_ = std::thread([this, path, kind, scale, cols, delay] {
            const auto t0 = std::chrono::steady_clock::now();
            const long long tk0 = loopTicks_.load(std::memory_order_relaxed);
            std::string r = doMultiExport(path, kind, scale, cols, delay);
            const long long dtMs = (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                                       std::chrono::steady_clock::now() - t0).count();
            const long long dtk = loopTicks_.load(std::memory_order_relaxed) - tk0;
            lastExportMs_.store((int)dtMs);
            lastExportTicks_.store((int)dtk);
            {
                std::lock_guard<std::mutex> lk(exMu_);
                exResult_ = r;
                exDone_ = true;
            }
            exCv_.notify_all();
            LOGI("multi export done: %s (loop ticks %lld in %lldms)", r.c_str(), dtk, dtMs);
            exportBusy_.store(false);
            if (!frameBusy_.load()) canvasBusy_.store(false);
        });
        return;
    }
    // kind=0: 在渲染线程创建 PNG 增量任务(保证在快照载入/改尺寸之后, 画布尺寸正确)
    if (kind == 0) {
        auto job = std::make_unique<PngExporter>();
        ExportResult br = job->begin(canvas_, path, exScale_, pngCompress_.load(), true);
        if (!br.ok) {
            char eb[192];
            snprintf(eb, sizeof eb, "err %s", br.err.empty() ? "export" : br.err.c_str());
            std::lock_guard<std::mutex> lk(exMu_);
            exResult_ = eb;
            exDone_ = true;
        } else {
            std::lock_guard<std::mutex> lk(exMu_);
            pngJob_ = std::move(job);
        }
        exCv_.notify_all();
        return;
    }
    if (kind != 1) return;
    // PSD: 同样工作线程跑(画布冻结只读), 不再卡渲染线程
    if (exportTh_.joinable()) exportTh_.join();
    exportAbort_.store(false);
    exportBusy_.store(true);
    canvasBusy_.store(true);
    exportTh_ = std::thread([this, path] {
        const auto t0 = std::chrono::steady_clock::now();
        const long long tk0 = loopTicks_.load(std::memory_order_relaxed);
        char b[256];
        PsdResult r = exportPsd(canvas_, path, &exportAbort_);
        if (r.ok) snprintf(b, sizeof b, "ok bytes=%lld", r.bytes);
        else snprintf(b, sizeof b, "err %s", r.err.c_str());
        const long long dtMs = (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                                   std::chrono::steady_clock::now() - t0).count();
        const long long dtk = loopTicks_.load(std::memory_order_relaxed) - tk0;
        lastExportMs_.store((int)dtMs);
        lastExportTicks_.store((int)dtk);
        {
            std::lock_guard<std::mutex> lk(exMu_);
            exResult_ = b;
            exDone_ = true;
        }
        exCv_.notify_all();
        LOGI("psd export done: %s (loop ticks %lld in %lldms)", b, dtk, dtMs);
        exportBusy_.store(false);
        if (!frameBusy_.load()) canvasBusy_.store(false);
    });
}

std::string Engine::exportPngFile(const std::string& path, int scale) {
    // 完整画布、实际尺寸; 请求交给渲染线程处理(排在待载入快照之后), 分帧写 PNG
    {
        std::lock_guard<std::mutex> lk(exMu_);
        exPath_ = path;
        exScale_ = scale;
        exKind_ = 0;
        exPending_ = true;
        exDone_ = false;
        exResult_.clear();
    }
    std::unique_lock<std::mutex> lk(exMu_);
    if (!exCv_.wait_for(lk, std::chrono::seconds(3600), [&] { return exDone_; })) {
        auto j = std::move(pngJob_);
        lk.unlock();
        if (j) j->abort();
        return "err timeout";
    }
    return exResult_;
}

std::string Engine::exportPsdFile(const std::string& path) {
    {
        std::lock_guard<std::mutex> lk(exMu_);
        exPath_ = path;
        exKind_ = 1;
        exPending_ = true;
        exDone_ = false;
    }
    std::unique_lock<std::mutex> lk(exMu_);
    if (!exCv_.wait_for(lk, std::chrono::seconds(120), [&] { return exDone_; }))
        return "err timeout";
    return exResult_;
}

std::string Engine::exportModeFile(const std::string& path, int kind, int scale, int cols, int delayMs) {
    {
        std::lock_guard<std::mutex> lk(exMu_);
        exPath_ = path;
        exKind_ = kind;
        exScale_ = std::max(1, std::min(10, scale));
        exCols_ = std::max(1, std::min(10, cols));
        exDelay_ = std::max(20, std::min(1000, delayMs));
        exPending_ = true;
        exDone_ = false;
        exResult_.clear();
    }
    std::unique_lock<std::mutex> lk(exMu_);
    if (!exCv_.wait_for(lk, std::chrono::seconds(1800), [&] { return exDone_; }))
        return "err timeout";
    return exResult_;
}

void Engine::exportInfo(int out[6]) {
    const int last = std::max(0, (int)frameData_.size() - 1);
    int a = std::max(0, std::min(loopStart_.load(), last));
    int bRaw = loopEnd_.load();
    int b = bRaw < 0 ? last : std::max(0, std::min(bRaw, last));
    out[0] = canvas_.width();
    out[1] = canvas_.height();
    out[2] = (int)frameData_.size();
    out[3] = std::min(a, b);
    out[4] = std::max(a, b);
    out[5] = canvas_.layers();
}

void Engine::exportLoopRange(int& lo, int& hi) {
    const int last = std::max(0, (int)frameData_.size() - 1);
    const int a = std::max(0, std::min(loopStart_.load(), last));
    const int bRaw = loopEnd_.load();
    const int b = bRaw < 0 ? last : std::max(0, std::min(bRaw, last));
    lo = std::min(a, b);
    hi = std::max(a, b);
}

// 把非当前帧的 blob 解到 tmp(供多帧导出合成); 层属性(模式/混合/不透明度/可见)沿用当前工程设置
bool Engine::frameCanvasForExport(int f, Canvas& tmp) {
    if (f < 0 || f >= (int)frameData_.size()) return false;
    const int w = canvas_.width(), h = canvas_.height();
    const int lc = std::max(1, canvas_.layers());
    if (!tmp.create(w, h, lc)) return false;
    const auto& d = frameData_[(size_t)f];
    if (!d.empty()) {
        size_t p = 0;
        auto rd = [&](uint32_t& v) {
            if (p + 4 > d.size()) return false;
            v = (uint32_t)d[p] | ((uint32_t)d[p + 1] << 8) | ((uint32_t)d[p + 2] << 16) | ((uint32_t)d[p + 3] << 24);
            p += 4;
            return true;
        };
        uint32_t flc = 0, active = 0, count = 0;
        if (!rd(flc) || !rd(active) || !rd(count)) return false;
        if (count > 2000000) return false;
        while (tmp.layers() < (int)flc) tmp.addLayer();
        for (uint32_t i = 0; i < count; i++) {
            uint32_t layer = 0, ti = 0, kind = 0;
            if (!rd(layer) || !rd(ti) || !rd(kind)) break;
            if ((int)layer >= tmp.layers() || (int)ti >= tmp.tilesX() * tmp.tilesY()) {
                if (kind == 1) { uint32_t col; rd(col); }
                else p += Canvas::TILE_PIX * 4;
                continue;
            }
            if (kind == 1) {
                uint32_t col = 0;
                if (!rd(col)) break;
                tmp.installTile((int)layer, (int)ti, nullptr, col, true);
            } else {
                if (p + Canvas::TILE_PIX * 4 > d.size()) break;
                tmp.installTile((int)layer, (int)ti, (const uint32_t*)(d.data() + p), 0, false);
                p += Canvas::TILE_PIX * 4;
            }
        }
    }
    const int n = std::min(canvas_.layers(), tmp.layers());
    for (int l = 0; l < n; l++) {
        tmp.setLayerVisible(l, canvas_.layerVisible(l));
        tmp.setLayerMode(l, canvas_.layerMode(l));
        tmp.setLayerBlend(l, canvas_.layerBlend(l));
        tmp.setLayerOpacity(l, canvas_.layerOpacity(l));
    }
    return true;
}

void Engine::composeFrameCell(int f, int scale, std::vector<uint32_t>& cell, int& cellW, int& cellH) {
    const int cw = canvas_.width(), ch = canvas_.height();
    scale = std::max(1, std::min(10, scale));
    cellW = cw * scale;
    cellH = ch * scale;
    cell.assign((size_t)cellW * cellH, 0u);
    Canvas tmp;
    Canvas* src = &canvas_;
    if (f != frame_) {
        if (frameCanvasForExport(f, tmp)) src = &tmp;
    }
    // 导出合成走快路径(普通层/不透明度100%/正常混合/无裁剪): 每像素省掉蒙版/图层属性/混合模式判断
    const bool fast = raster::composeLayersFastEligible(*src, false, false, true);
    auto pixel = [&](int x, int y) -> uint32_t {
        return fast ? raster::composeLayersPixelFast(*src, x, y)
                    : raster::composeLayersPixelEx(*src, x, y, false, false);
    };
    if (scale == 1) {
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++)
                cell[(size_t)y * cw + x] = pixel(x, y);
    } else {
        std::vector<uint32_t> row((size_t)cw);
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) row[(size_t)x] = pixel(x, y);
            for (int oy = 0; oy < scale; oy++) {
                uint32_t* drow = cell.data() + (size_t)(y * scale + oy) * cellW;
                for (int x = 0; x < cw; x++) {
                    const uint32_t v = row[(size_t)x];
                    uint32_t* d = drow + (size_t)x * scale;
                    for (int ox = 0; ox < scale; ox++) d[ox] = v;
                }
            }
        }
    }
}

// 多帧/多模式导出(在渲染线程执行; 大尺寸有像素上限, 超限直接报错避免 OOM)
// 帧/图层互相独立的部分用 parallelFor 并行(最多 4 线程), 导出期间界面依旧会被渲染线程阻塞,
// 但同样的工作用 4 核完成, 大画布/多帧导出的卡顿时间明显缩短。
std::string Engine::doMultiExport(const std::string& path, int kind, int scale, int cols, int delayMs) {
    if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
    const auto t0 = std::chrono::steady_clock::now();
    auto elapsedMs = [&]() {
        return (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                   std::chrono::steady_clock::now() - t0).count();
    };
    const int cw = canvas_.width(), ch = canvas_.height();
    if (cw <= 0 || ch <= 0) return "err empty canvas";
    scale = std::max(1, std::min(10, scale));
    const int cellW = cw * scale, cellH = ch * scale;
    if ((long long)cellW * cellH > 64ll * 1024 * 1024) return "err frame too large";
    int lo = 0, hi = 0;
    exportLoopRange(lo, hi);
    const int frames = hi - lo + 1;
    if (frames <= 0) return "err no frames";
    const int compress = pngCompress_.load();
    // 每个并行任务一份 cell 内存; 大画布降低并行度, 防止 4 份 cell 把内存打爆
    const long long cellPx = (long long)cellW * cellH;
    const int compThreads = (int)std::max(1ll, std::min(4ll, (96ll * 1024 * 1024) / std::max(1ll, cellPx * 4)));
    char msg[224];
    if (kind == 2) {   // 精灵图
        const int c = std::max(1, std::min(10, cols));
        const int rows = (frames + c - 1) / c;
        const long long sw = (long long)cellW * c, sh = (long long)cellH * rows;
        if (sw <= 0 || sh <= 0 || sw > 65535 || sh > 65535 || sw * sh > 64ll * 1024 * 1024)
            return "err sheet too large";
        std::vector<uint32_t> sheet((size_t)(sw * sh), 0u);
        // 每帧写互不重叠的矩形区域, 可并行
        parallelFor(frames, [&](int fi) {
            if (exportAbort_.load(std::memory_order_relaxed)) return;
            std::vector<uint32_t> cell;
            int cw2 = 0, ch2 = 0;
            composeFrameCell(lo + fi, scale, cell, cw2, ch2);
            const int baseX = (fi % c) * cellW;
            const int baseY = (fi / c) * cellH;
            for (int y = 0; y < cellH; y++) {
                const uint32_t* srow = cell.data() + (size_t)y * cellW;
                uint32_t* drow = sheet.data() + (size_t)(baseY + y) * sw + baseX;
                memcpy(drow, srow, (size_t)cellW * 4);
            }
        }, compThreads);
        if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
        ExportResult r = writePngPixels(sheet.data(), (int)sw, (int)sh, path, compress);
        if (!r.ok) return "err " + r.err;
        snprintf(msg, sizeof msg, "ok %lldx%lld frames=%d bytes=%lld ms=%ld",
                 sw, sh, frames, r.bytes, elapsedMs());
        return msg;
    }
    if (kind == 3) {   // 逐帧 PNG 写进目录
        std::mutex emu;
        std::string firstErr;
        std::atomic<bool> failed{false};
        parallelFor(frames, [&](int fi) {
            if (failed.load(std::memory_order_relaxed)) return;
            if (exportAbort_.load(std::memory_order_relaxed)) { failed.store(true, std::memory_order_relaxed); return; }
            std::vector<uint32_t> cell;
            int cw2 = 0, ch2 = 0;
            composeFrameCell(lo + fi, scale, cell, cw2, ch2);
            char fn[64];
            snprintf(fn, sizeof fn, "/frame_%04d.png", fi + 1);
            ExportResult r = writePngPixels(cell.data(), cellW, cellH, path + fn, compress);
            if (!r.ok) {
                std::lock_guard<std::mutex> lk(emu);
                if (firstErr.empty())
                    firstErr = "frame " + std::to_string(fi + 1) + ": " + r.err;
                failed.store(true, std::memory_order_relaxed);
            }
        }, compThreads);
        if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
        if (failed.load()) return "err " + firstErr;
        snprintf(msg, sizeof msg, "ok %d frames %dx%d ms=%ld", frames, cellW, cellH, elapsedMs());
        return msg;
    }
    if (kind == 4) {   // GIF 动画
        GifWriter gif;
        if (!gif.begin(path, cellW, cellH, std::max(2, delayMs / 10))) return "err gif open";
        // 帧总数不大时先并行合成好全部帧, 再顺序 LZW 写文件(按内存上限决定)
        const long long totalPx = (long long)frames * cellW * cellH;
        if (totalPx * 4 <= 96ll * 1024 * 1024) {
            std::vector<std::vector<uint32_t>> cells((size_t)frames);
            parallelFor(frames, [&](int fi) {
                if (exportAbort_.load(std::memory_order_relaxed)) return;
                int cw2 = 0, ch2 = 0;
                composeFrameCell(lo + fi, scale, cells[(size_t)fi], cw2, ch2);
            }, compThreads);
            if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
            for (int fi = 0; fi < frames; fi++) {
                if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
                if (!gif.addFrame(cells[(size_t)fi].data(), cellW, cellH)) return "err gif frame";
                cells[(size_t)fi].clear();
                cells[(size_t)fi].shrink_to_fit();
            }
        } else {
            std::vector<uint32_t> cell;
            int cw2 = 0, ch2 = 0;
            for (int fi = 0; fi < frames; fi++) {
                if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
                composeFrameCell(lo + fi, scale, cell, cw2, ch2);
                if (!gif.addFrame(cell.data(), cellW, cellH)) return "err gif frame";
            }
        }
        if (!gif.finish()) return "err gif write";
        FILE* f = fopen(path.c_str(), "rb");
        long long bytes = 0;
        if (f) { fseek(f, 0, SEEK_END); bytes = ftell(f); fclose(f); }
        snprintf(msg, sizeof msg, "ok %dx%d frames=%d delay=%dms bytes=%lld ms=%ld",
                 cellW, cellH, frames, std::max(20, delayMs), bytes, elapsedMs());
        return msg;
    }
    if (kind == 5) {   // 叠层图像: 左右=图层, 上下=帧(第1帧在顶), 背景 #141414; 2.5D开时按显示偏移
        const int layers = std::max(1, canvas_.layers());
        // 与 raster::composeLayersPixelEx 的 2.5D 语义一致: 底层不动, 第 l 层错开 l*depth(按当前视角)
        const auto& g25 = raster::mode25D();
        const bool d25 = g25.on && g25.depth > 0 && layers > 1;
        const long long sw = (long long)cellW * layers, sh = (long long)cellH * frames;
        if (sw <= 0 || sh <= 0 || sw > 65535 || sh > 65535 || sw * sh > 64ll * 1024 * 1024)
            return "err overlay too large";
        std::vector<uint32_t> sheet((size_t)(sw * sh), 0xFF141414u);
        // 每帧写自己的横向条带(上下不重叠), 每线程自己的临时 Canvas
        parallelFor(frames, [&](int fi) {
            if (exportAbort_.load(std::memory_order_relaxed)) return;
            const int f = lo + fi;
            Canvas tmp;
            Canvas* src = &canvas_;
            if (f != frame_) { if (!frameCanvasForExport(f, tmp)) return; src = &tmp; }
            const int baseY = fi * cellH;
            for (int li = 0; li < layers; li++) {
                const int baseX = li * cellW;
                const int off = d25 ? li * g25.depth : 0;
                const int ox25 = (int)std::lround(g25.offX * off);
                const int oy25 = (int)std::lround(g25.offY * off);
                for (int y = 0; y < ch; y++) {
                    const int sy = y - oy25;
                    if (sy < 0 || sy >= ch) continue;
                    for (int x = 0; x < cw; x++) {
                        const int sx = x - ox25;
                        if (sx < 0 || sx >= cw) continue;
                        const uint32_t v = src->getPixelL(li, sx, sy);
                        if (!v) continue;   // 空像素保留 #141414 底板, 叠层图才看得出层/帧网格
                        for (int oy = 0; oy < scale; oy++) {
                            uint32_t* drow = sheet.data() + (size_t)(baseY + y * scale + oy) * sw + baseX + (size_t)x * scale;
                            for (int ox = 0; ox < scale; ox++) drow[ox] = v;
                        }
                    }
                }
            }
        }, compThreads);
        if (exportAbort_.load(std::memory_order_relaxed)) return "err aborted";
        ExportResult r = writePngPixels(sheet.data(), (int)sw, (int)sh, path, compress);
        if (!r.ok) return "err " + r.err;
        snprintf(msg, sizeof msg, "ok %lldx%lld %dx%d%s bytes=%lld ms=%ld", sw, sh, layers, frames,
                 d25 ? " 2.5d" : "", r.bytes, elapsedMs());
        return msg;
    }
    return "err bad mode";
}

void Engine::requestSelectAll() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({17, 0.0f, 0.0f, 0.0f});
}

void Engine::requestZoomFit() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({18, 0.0f, 0.0f, 0.0f});
}

void Engine::requestFlip(bool horizontal) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({horizontal ? 19 : 20, 0.0f, 0.0f, 0.0f});
}

void Engine::requestGrayscale() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({21, 0.0f, 0.0f, 0.0f});
}

void Engine::setGrayView(bool on) {
    grayView_.store(on, std::memory_order_relaxed);
    redrawReq_.store(true, std::memory_order_relaxed);
}

void Engine::canvasScreenRect(int* r) {
    display_.canvasScreenRect(r);
}

void Engine::requestApplyLevels(int channel, int black, int white, float gamma, int outBlack, int outWhite) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() >= 4096) return;
    channel = std::max(0, std::min(channel, 3));
    const int p1 = std::max(0, std::min(black, 255)) |
                   (std::max(0, std::min(white, 255)) << 8) | (channel << 16);
    const int p2 = std::max(0, std::min(outBlack, 255)) | (std::max(0, std::min(outWhite, 255)) << 8);
    queue_.push_back({49, (float)p1, (float)p2, gamma});
}

void Engine::levelsHistogram(int channel, int out[256]) {
    {
        std::lock_guard<std::mutex> lk(lvMu_);
        lvDone_ = false;
        lvChannel_.store(std::max(0, std::min(channel, 3)));
        lvPending_.store(true);
    }
    std::unique_lock<std::mutex> lk(lvMu_);
    lvCv_.wait_for(lk, std::chrono::seconds(2), [&] { return lvDone_; });
    memcpy(out, lvHist_, sizeof(lvHist_));
}

void Engine::setLevelsPreview(bool on, const int* luts) {
    if (!on || !luts) {
        lvPrevOn_.store(false);
        lvPrevDirty_.store(true);
        return;
    }
    const int cur = lvPrevIdx_.load();
    const int wr = (cur == 0) ? 1 : 0;
    // 合成 4 通道 LUT: RGB(0) -> R(1) -> G(2) -> B(3)
    for (int v = 0; v < 256; v++) {
        int r = luts[0 * 256 + v]; r = luts[1 * 256 + r]; r = luts[2 * 256 + r]; r = luts[3 * 256 + r];
        int g = luts[0 * 256 + v]; g = luts[1 * 256 + g]; g = luts[2 * 256 + g]; g = luts[3 * 256 + g];
        int b = luts[0 * 256 + v]; b = luts[1 * 256 + b]; b = luts[2 * 256 + b]; b = luts[3 * 256 + b];
        lvPrevBuf_[wr][0][v] = (uint8_t)std::max(0, std::min(255, r));
        lvPrevBuf_[wr][1][v] = (uint8_t)std::max(0, std::min(255, g));
        lvPrevBuf_[wr][2][v] = (uint8_t)std::max(0, std::min(255, b));
    }
    lvPrevIdx_.store(wr);
    lvPrevOn_.store(true);
    lvPrevDirty_.store(true);
}

void Engine::requestApplyLevelsAll(const uint8_t luts[4][256]) {
    {
        std::lock_guard<std::mutex> lk(lvAllMu_);
        memcpy(lvAllLut_, luts, sizeof(lvAllLut_));
    }
    lvAllPending_.store(true);
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({52, 0.0f, 0.0f, 0.0f});
}

void Engine::applyLevelsAllImpl(const uint8_t luts[4][256]) {
    canvas_.beginRecord();
    for (int ch = 0; ch < 4; ch++) canvas_.applyLevelsLut(canvas_.active(), ch, luts[ch]);
    auto rec = canvas_.endRecord();
    if (!rec.empty()) {
        Hist h; h.kind = 0; h.frame = frame_;
        h.rec = std::move(rec);
        h.bytes = histBytes(h);
        pushHist(std::move(h), true);   // 色阶也算一步回放
    }
    lvPrevOn_.store(false);
    lvPrevDirty_.store(true);
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
    inkPixels_.store((int)canvas_.countInk(0));
}

void Engine::applyLevelsImpl(int channel, int black, int white, float gamma, int outBlack, int outWhite) {
    if (black < 0) black = 0; if (black > 254) black = 254;
    if (white <= black) white = black + 1; if (white > 255) white = 255;
    if (gamma < 0.05f) gamma = 0.05f; if (gamma > 10.0f) gamma = 10.0f;
    if (outBlack < 0) outBlack = 0; if (outBlack > 254) outBlack = 254;
    if (outWhite <= outBlack) outWhite = outBlack + 1; if (outWhite > 255) outWhite = 255;
    uint8_t lut[256];
    const float den = (float)(white - black);
    const float invG = 1.0f / gamma;
    for (int i = 0; i < 256; i++) {
        float t = ((float)i - black) / den;
        if (t < 0.0f) t = 0.0f; else if (t > 1.0f) t = 1.0f;
        const float v = std::pow(t, invG);
        int o = outBlack + (int)std::lround(v * (outWhite - outBlack));
        if (o < 0) o = 0; else if (o > 255) o = 255;
        lut[i] = (uint8_t)o;
    }
    canvas_.beginRecord();
    canvas_.applyLevelsLut(canvas_.active(), channel, lut);
    auto rec = canvas_.endRecord();
    if (!rec.empty()) {
        Hist h; h.kind = 0; h.frame = frame_;
        h.rec = std::move(rec);
        h.bytes = histBytes(h);
        pushHist(std::move(h), true);   // 色阶也算一步回放
    }
    mip_.invalidateRect(0, 0, canvas_.width() - 1, canvas_.height() - 1);
    screenFull_ = true;
    inkPixels_.store((int)canvas_.countInk(0));
}

void Engine::requestDeselect() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({16, 0.0f, 0.0f, 0.0f});
}

void Engine::requestUndo() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({11, 0.0f, 0.0f, 0.0f});
}

void Engine::requestOverlay(bool checker, bool grid) {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({13, checker ? 1.0f : 0.0f, grid ? 1.0f : 0.0f, 0.0f});
}

void Engine::requestRedo() {
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({12, 0.0f, 0.0f, 0.0f});
}

void Engine::flushScreenDirty() {
    const auto& nd = canvas_.dirtyTiles();
    if (nd.empty()) return;
    const int tx = canvas_.tilesX();
    const int cw = canvas_.width(), ch = canvas_.height();
    const float z = display_.zoom();
    // 2.5D: 源像素会按层错位到 dest = src - off, 影响范围最多 (层数-1)*depth。
    // 不扩边的话, 错位到相邻 mip tile/屏幕区域的改动不会被重算, 表现为 tile 边界残影。
    int pad = 0;
    {
        const auto& g = raster::mode25D();
        if (g.on && g.depth > 0 && canvas_.layers() > 1)
            pad = (canvas_.layers() - 1) * g.depth;
    }
    int visLevel = 0;
    if (z < 1.0f) {
        visLevel = (int)std::floor(std::log2(1.0f / std::max(z, 1e-6f)));
        visLevel = std::max(0, std::min(visLevel, 5));
    }
    if (nd.size() > 64) {
        // 大范围改动(整片填充等): 逐块 updateRect 会把同一批 mip 块重复重建几十次,
        // 改成一次 invalidateRect + 一个屏幕脏矩形, 下一帧按需重建可见块
        int x0 = cw, y0 = ch, x1 = -1, y1 = -1;
        for (int ti : nd) {
            const int x = (ti % tx) * Canvas::TILE, y = (ti / tx) * Canvas::TILE;
            const int w = std::min(Canvas::TILE, cw - x), h = std::min(Canvas::TILE, ch - y);
            if (x < x0) x0 = x;
            if (y < y0) y0 = y;
            if (x + w - 1 > x1) x1 = x + w - 1;
            if (y + h - 1 > y1) y1 = y + h - 1;
        }
        mip_.invalidateRect(x0 - pad, y0 - pad, x1 + pad, y1 + pad);
        markScreenRect(x0 - pad, y0 - pad, x1 + pad, y1 + pad);
    } else {
        for (int ti : nd) {
            const int x = (ti % tx) * Canvas::TILE, y = (ti / tx) * Canvas::TILE;
            const int w = std::min(Canvas::TILE, cw - x), h = std::min(Canvas::TILE, ch - y);
            mip_.updateRect(x - pad, y - pad, x + w - 1 + pad, y + h - 1 + pad, visLevel);
            markScreenRect(x - pad, y - pad, x + w - 1 + pad, y + h - 1 + pad);
        }
    }
    canvas_.clearDirty();
}

void Engine::compositeFrameBlob(const std::vector<uint8_t>& blob, std::vector<uint32_t>& out,
                                int w, int h, const Canvas& canvas) {
    out.assign((size_t)w * h, 0u);
    if (blob.size() < 12) return;
    size_t p = 0;
    auto rd = [&](uint32_t& v) {
        if (p + 4 > blob.size()) return false;
        v = (uint32_t)blob[p] | ((uint32_t)blob[p + 1] << 8) |
            ((uint32_t)blob[p + 2] << 16) | ((uint32_t)blob[p + 3] << 24);
        p += 4;
        return true;
    };
    uint32_t lc = 0, active = 0, count = 0;
    if (!rd(lc) || !rd(active) || !rd(count)) return;
    const int tx_n = canvas.tilesX();
    for (uint32_t i = 0; i < count; i++) {
        uint32_t layer = 0, ti = 0, kind = 0;
        if (!rd(layer) || !rd(ti) || !rd(kind)) break;
        const uint32_t* px = nullptr;
        uint32_t solidCol = 0;
        if (kind == 1) {
            if (!rd(solidCol)) break;
        } else {
            if (p + Canvas::TILE_PIX * 4 > blob.size()) break;
            px = (const uint32_t*)(blob.data() + p);
            p += Canvas::TILE_PIX * 4;
        }
        if ((int)layer >= canvas.layers() || !canvas.layerVisible((int)layer)) continue;
        if ((int)ti < 0 || (int)ti >= tx_n * canvas.tilesY()) continue;
        const int tbx = (ti % tx_n) << Canvas::TILE_SHIFT;
        const int tby = (ti / tx_n) << Canvas::TILE_SHIFT;
        for (int yy = 0; yy < Canvas::TILE; yy++) {
            const int y = tby + yy;
            if (y >= h) break;
            uint32_t* drow = out.data() + (size_t)y * w;
            for (int xx = 0; xx < Canvas::TILE; xx++) {
                const int x = tbx + xx;
                if (x >= w) break;
                uint32_t v = px ? px[((size_t)yy << Canvas::TILE_SHIFT) + xx] : solidCol;
                v = raster::applyLayerOpacity(v, canvas.layerOpacity((int)layer));
                if (v) raster::overPixel(drow[x], v);
            }
        }
    }
}

static void tintOnionBuffer(std::vector<uint32_t>& b, unsigned rm, unsigned gm, unsigned bm) {
    for (auto& o : b) {
        if (!(o >> 24)) continue;
        const uint32_t a = ((o >> 24) * 178u) / 255u;
        o = (a << 24) | ((((o >> 16) & 255) * bm / 255u) << 16) |
            ((((o >> 8) & 255) * gm / 255u) << 8) | (((o & 255) * rm) / 255u);
    }
}

void Engine::rebuildOnion() {
    onionW_ = canvas_.width(); onionH_ = canvas_.height();
    onionPrev_.clear(); onionNext_.clear();
    onionGen_.store(onionGen_.load());
    if (!onionOn_.load()) return;
    if ((long long)onionW_ * onionH_ > 16ll * 1024 * 1024) return;   // 大画布不建(内存)
    if (frameData_.size() <= 1) return;
    const uint32_t gen = onionGen_.load();
    if (frame_ > 0 && frame_ < (int)frameData_.size()) {
        compositeFrameBlob(frameData_[(size_t)frame_ - 1], onionPrev_, onionW_, onionH_, canvas_);
        tintOnionBuffer(onionPrev_, 255, 120, 120);   // 前帧偏红
    }
    if (frame_ + 1 < (int)frameData_.size()) {
        compositeFrameBlob(frameData_[(size_t)frame_ + 1], onionNext_, onionW_, onionH_, canvas_);
        tintOnionBuffer(onionNext_, 120, 140, 255);   // 后帧偏蓝
    }
    onionBuiltGen_ = gen;
}

void Engine::setReplayFrame(int w, int h, const uint32_t* px) {
    if (w <= 0 || h <= 0 || !px) return;
    auto im = std::make_shared<ReplayImage>();
    im->w = w; im->h = h;
    im->px.assign(px, px + (size_t)w * h);
    std::atomic_store(&replayImg_, std::shared_ptr<const ReplayImage>(im));
    screenFull_ = true;
}

void Engine::clearReplayFrame() {
    std::atomic_store(&replayImg_, std::shared_ptr<const ReplayImage>());
    screenFull_ = true;
}

void Engine::renderScreen() {
    const int vw = display_.viewW(), vh = display_.viewH();
    if (vw <= 0 || vh <= 0) return;
    int x0 = 0, y0 = 0, x1 = vw - 1, y1 = vh - 1;
    if (!screenFull_ && fullBurst_ <= 0) {
        x0 = std::max(0, sdX0_); y0 = std::max(0, sdY0_);
        x1 = std::min(vw - 1, sdX1_); y1 = std::min(vh - 1, sdY1_);
        if (x0 > x1 || y0 > y1) { sdX1_ = sdX0_ - 1; return; }
    }
    const bool measLoupe = measMagShown_ && measOn_ &&
                           (lastAct_.load() == 0 || lastAct_.load() == 1) &&
                           tool_.load() == 4 && shapeType_.load() == 6;
    if (measLoupe && magnifierOn_.load()) { x0 = 0; y0 = 0; x1 = vw - 1; y1 = vh - 1; }
    // 屏幕网格不再盖到放大镜窗口上(镜内网格由下面 CPU 按放大后的位置画)
    display_.clearLoupeRect();
    const float zoom = display_.zoom();
    int level = 0;
    if (zoom < 1.0f) {
        // 取偏细一级(floor): 宁可多采样, 也不要让 mip texel 比屏幕像素更粗
        const int l = (int)std::floor(std::log2(1.0f / std::max(zoom, 1e-6f)));
        level = std::max(0, std::min(l, 5));
    }
    const int cw = canvas_.width(), ch = canvas_.height();
    // 回放: 项目画布视口不变, 只把合成源换成回放帧; 项目像素/选区/撤销完全不动
    const auto replayImg = std::atomic_load(&replayImg_);
    const bool replayOn = replayImg && replayImg->w > 0 && replayImg->h > 0 && cw > 0 && ch > 0;
    // 捏合帧把棋盘格直接烘进屏幕缓冲, 与画布同一纹理/同一帧, 不会相对滑动。
    const bool bakeChk = display_.zoomGestureActive() && !replayOn;
    display_.setBakeChecker(bakeChk);
    // 捏合/平移/整屏重绘: 先把本帧可见且缺失的 tile 用 ComposePool 并行构建好
    // (每块只建一次, 后续帧命中缓存), 避免同步逐块构建卡顿。
    bool prefetchVisible = !replayOn &&
        (display_.zoomGestureActive() || screenFull_ ||
         (long long)(x1 - x0 + 1) * (y1 - y0 + 1) > 200000);
    int pfTx0 = 0, pfTy0 = 0, pfTx1 = -1, pfTy1 = -1;
    if (prefetchVisible) {
        float mnx = 1e9f, mny = 1e9f, mxx = -1e9f, mxy = -1e9f;
        const float sx[4] = {(float)x0, (float)x1, (float)x0, (float)x1};
        const float sy[4] = {(float)y0, (float)y0, (float)y1, (float)y1};
        for (int i = 0; i < 4; i++) {
            float ccx = 0.f, ccy = 0.f;
            display_.screenToCanvasF(sx[i], sy[i], &ccx, &ccy);
            mnx = std::min(mnx, ccx); mxx = std::max(mxx, ccx);
            mny = std::min(mny, ccy); mxy = std::max(mxy, ccy);
        }
        const int span = MipPyramid::TS << level;
        pfTx0 = std::max(0, (int)std::floor(mnx / span));
        pfTy0 = std::max(0, (int)std::floor(mny / span));
        pfTx1 = std::min(mip_.tilesX(level) - 1, (int)std::floor(mxx / span));
        pfTy1 = std::min(mip_.tilesY(level) - 1, (int)std::floor(mxy / span));
        std::vector<std::pair<int, int>> missing;
        for (int ty = pfTy0; ty <= pfTy1; ty++)
            for (int tx = pfTx0; tx <= pfTx1; tx++)
                if (!mip_.hasTile(level, tx, ty)) missing.emplace_back(tx, ty);
        if (!missing.empty()) {
            pool_.run((int)missing.size(), [&](int i) {
                mip_.tile(level, missing[i].first, missing[i].second);
            });
        }
    }
    auto t0 = std::chrono::steady_clock::now();
    int stride = 0;
    uint32_t* p = display_.beginUpdate(x0, y0, x1 - x0 + 1, y1 - y0 + 1, &stride);
    if (!p) return;
    const bool cut = canvas_.cutActive();
    // 层感知剪切: 只挖空被剪层(cutLayer>=0)时 mip 已按层合成好, 屏幕不要再整块清零;
    // 旧"全部层"模式(cutLayer<0)仍整块隐藏(各层都进了浮层)。
    const bool cutHideAll = cut && canvas_.cutLayer() < 0;
    const int kx0 = canvas_.cutX0(), ky0 = canvas_.cutY0();
    const int kx1 = canvas_.cutX1(), ky1 = canvas_.cutY1();
    // 旧项目本层蒙版实时预览: 浮层落到"本层透明"的位置时半透明(45%)显示, 表示确认后不贴。
    // 普通裁剪浮层的源矩形在旧项目预览里先被清空, 源区也算透明。
    const bool maskPrev = canvas_.alphaLock();
    auto dimByLayerMask = [&](uint32_t f, int tx, int ty) -> uint32_t {
        if (!f || !maskPrev) return f;
        bool dstHas = false;
        if (tx >= 0 && ty >= 0 && tx < cw && ty < ch) {
            dstHas = (canvas_.getPixelL(canvas_.active(), tx, ty) >> 24) != 0;
            if (cut && tx >= kx0 && tx <= kx1 && ty >= ky0 && ty <= ky1) dstHas = false;
        }
        if (dstHas) return f;
        return (f & 0x00FFFFFFu) | ((((f >> 24) * 45u / 100u) & 0xFFu) << 24);
    };
    // 屏幕分辨率浮层: 目标矩形内按"源坐标"从 mip 采样(不拷贝、不降采样)
    const bool fv = sel_.floatVisible();
    const int fSrcX = sel_.fSrcX(), fSrcY = sel_.fSrcY();
    const int fDstX = sel_.fDstX(), fDstY = sel_.fDstY();
    const int fW = sel_.fW(), fH = sel_.fH();
    // 浮层预览逆映射常量每帧算一次: 热循环里不再逐像素 sin/cos(xform/导入浮层共用)
    const bool selExt = sel_.extActive();
    const bool selXf = sel_.xformActive();
    const bool selXfAll = selXf && sel_.allLayers();
    SelectionTool::MapPre selMap;
    sel_.prepareMap(&selMap);
    // 浮层只覆盖屏幕一小块时, 先算它的屏幕包围盒, 让每像素热循环快速跳过
    int fsx0 = 0, fsx1 = -1, fsy0 = 0, fsy1 = -1;
    if (fv && !selXf && !selExt && fW > 0 && fH > 0) {
        // 未变换浮层: 直接用目标矩形算屏幕 bbox, 框外像素完全跳过
        float mnx = 1e9f, mny = 1e9f, mxx = -1e9f, mxy = -1e9f;
        const float px[4] = {(float)fDstX, (float)(fDstX + fW), (float)fDstX, (float)(fDstX + fW)};
        const float py[4] = {(float)fDstY, (float)fDstY, (float)(fDstY + fH), (float)(fDstY + fH)};
        for (int i = 0; i < 4; i++) {
            float sxf = 0.f, syf = 0.f;
            display_.canvasToScreenF(px[i], py[i], &sxf, &syf);
            mnx = std::min(mnx, sxf); mxx = std::max(mxx, sxf);
            mny = std::min(mny, syf); mxy = std::max(mxy, syf);
        }
        fsx0 = (int)std::floor(mnx) - 1; fsx1 = (int)std::ceil(mxx) + 1;
        fsy0 = (int)std::floor(mny) - 1; fsy1 = (int)std::ceil(mxy) + 1;
    }
    if ((selXf || selExt) && fv) {
        float q[8];
        sel_.corners(q);
        float mnx = 1e9f, mny = 1e9f, mxx = -1e9f, mxy = -1e9f;
        for (int i = 0; i < 4; i++) {
            float sxf = 0.f, syf = 0.f;
            display_.canvasToScreenF(q[i * 2], q[i * 2 + 1], &sxf, &syf);
            mnx = std::min(mnx, sxf); mxx = std::max(mxx, sxf);
            mny = std::min(mny, syf); mxy = std::max(mxy, syf);
        }
        fsx0 = (int)std::floor(mnx) - 1; fsx1 = (int)std::ceil(mxx) + 1;
        fsy0 = (int)std::floor(mny) - 1; fsy1 = (int)std::ceil(mxy) + 1;
    }
    const float invz = 1.0f / zoom;
    const bool lvOn = lvPrevOn_.load();
    const int lvIdx = lvPrevIdx_.load();
    const uint8_t* lvR = (lvOn && lvIdx >= 0) ? lvPrevBuf_[lvIdx][0] : nullptr;
    const uint8_t* lvG = (lvOn && lvIdx >= 0) ? lvPrevBuf_[lvIdx][1] : nullptr;
    const uint8_t* lvB = (lvOn && lvIdx >= 0) ? lvPrevBuf_[lvIdx][2] : nullptr;
    const bool onionActive = onionOn_.load() && !playing_.load() && frameData_.size() > 1 &&
                             (long long)cw * ch <= 16ll * 1024 * 1024;
    if (onionActive && onionBuiltGen_ != onionGen_.load()) rebuildOnion();
    const bool onPrev = onionActive && !onionPrev_.empty();
    const bool onNext = onionActive && !onionNext_.empty();
    const int rectW = x1 - x0 + 1;
    const int rectH = y1 - y0 + 1;
    // 2.5D: 上层沿视角方向错位 (层数-1)*depth, 越出画布的部分也要显示(否则被画布边界
    // 外的底色/棋盘"吃掉")。pad 用画布像素, 屏幕包围盒按 zoom 放大。
    int pad25 = 0;
    {
        const auto& g25 = raster::mode25D();
        if (!replayOn && g25.on && g25.depth > 0 && canvas_.layers() > 1)
            pad25 = (canvas_.layers() - 1) * g25.depth;
    }
    const bool pad25on = pad25 > 0;
    const bool plainCanvas = plainPathOn_ && !replayOn && !cut && !fv && !selExt && !selXf &&
                             !lvOn && !onPrev && !onNext && !measLoupe && !pad25on;
    // 2.5D 视角旋转时, 屏幕 x 方向对应画布 (cos, -sin) 方向; 非旋转时退化为 (1,0)
    const float dCx = display_.rot25Cos() * invz;
    const float dCy = -display_.rot25Sin() * invz;
    // 画布在屏幕上的保守包围盒: 缩小/平移后画布只占视口一小块, 框外像素直接清零,
    // 不再逐像素做 screenToCanvas 反算 + mip 查询(捏合烘棋盘时需要框外棋盘, 不裁)。
    const bool clipOutside = !bakeChk && !replayOn;
    int cbx0 = x0, cbx1 = x1, cby0 = y0, cby1 = y1;
    if (clipOutside) {
        const float cornerX[4] = {0.f, (float)cw, 0.f, (float)cw};
        const float cornerY[4] = {0.f, 0.f, (float)ch, (float)ch};
        float mnx = 1e9f, mny = 1e9f, mxx = -1e9f, mxy = -1e9f;
        for (int i = 0; i < 4; i++) {
            float sxf = 0.f, syf = 0.f;
            display_.canvasToScreenF(cornerX[i], cornerY[i], &sxf, &syf);
            mnx = std::min(mnx, sxf); mxx = std::max(mxx, sxf);
            mny = std::min(mny, syf); mxy = std::max(mxy, syf);
        }
        cbx0 = std::max(x0, (int)std::floor(mnx) - 2 - (pad25on ? (int)std::ceil(pad25 * zoom) : 0));
        cbx1 = std::min(x1, (int)std::ceil(mxx) + 2 + (pad25on ? (int)std::ceil(pad25 * zoom) : 0));
        cby0 = std::max(y0, (int)std::floor(mny) - 2 - (pad25on ? (int)std::ceil(pad25 * zoom) : 0));
        cby1 = std::min(y1, (int)std::ceil(mxy) + 2 + (pad25on ? (int)std::ceil(pad25 * zoom) : 0));
    }
    auto renderBand = [&](int band, int bands) {
        const int syA = y0 + (rectH * band) / bands;
        const int syB = y0 + (rectH * (band + 1)) / bands - 1;
        // 捏合烘帧 + 纯画布 + 放大(z>=1): 屏幕上一段连续像素往往对应同一个画布像素,
        // 按"画布像素→屏幕段"整段填充, 省掉逐屏幕像素的浮点反算/瓦片查表 + 逐像素烘棋盘。
        // 棋盘/网格用同一套烘帧颜色(段内格子/底色不变), 像素结果与逐像素路径一致。
        // 只在烘帧(捏合)时启用: 不烘帧时逐像素路径已经很紧, 分段反而多付边界计算。
        // 2.5D 旋转时屏幕横排=画布斜线, 按"x/y 先到整数边界"切段, 段内仍是同一个画布像素。
        if (plainCanvas && runFillOn_ && bakeChk && display_.bakeActive() &&
            level == 0 && zoom >= 1.0f && display_.bakeCheckStable()) {
            const bool bakeOn = bakeChk && display_.bakeActive();
            const bool bakeGrid = bakeOn && display_.bakeGridActive();
            const uint32_t bgCol = display_.bakeBgColor();
            const float lwC = display_.bakeGridLwC();
            if (display_.rotation25Active()) {
                for (int sy = syA; sy <= syB; sy++) {
                    uint32_t* drow = p + (size_t)(sy - y0) * stride;
                    float cxf = 0.f, cyf = 0.f;
                    display_.screenToCanvasF((float)x0, (float)sy, &cxf, &cyf);
                    const uint32_t* tile = nullptr;
                    int lastTx = INT_MIN, lastTy = INT_MIN;
                    int sx = x0;
                    while (sx <= x1) {
                        const float cx = cxf + (sx - x0) * dCx;
                        const float cy = cyf + (sx - x0) * dCy;
                        if (!(cx >= 0.f && cy >= 0.f && cx < (float)cw && cy < (float)ch)) {
                            drow[sx - x0] = bakeOn ? bgCol : 0;
                            sx++;
                            continue;
                        }
                        const int p2 = (int)cx, q2 = (int)cy;
                        float txl = 1e9f, tyl = 1e9f;
                        if (dCx > 0.f) txl = ((float)(p2 + 1) - cx) / dCx;
                        else if (dCx < 0.f) txl = ((float)p2 - cx) / dCx;
                        if (dCy > 0.f) tyl = ((float)(q2 + 1) - cy) / dCy;
                        else if (dCy < 0.f) tyl = ((float)q2 - cy) / dCy;
                        const float tmin = std::min(txl, tyl);
                        int len = (tmin >= 1e8f) ? (x1 - sx + 1) : (int)std::ceil(tmin);
                        if (len < 1) len = 1;
                        int ex = std::min(x1, sx + len - 1);
                        while (ex > sx && ((int)(cxf + (ex - x0) * dCx) != p2 ||
                                           (int)(cyf + (ex - x0) * dCy) != q2)) ex--;
                        while (ex < x1 && (int)(cxf + (ex + 1 - x0) * dCx) == p2 &&
                               (int)(cyf + (ex + 1 - x0) * dCy) == q2) ex++;
                        const int txT = p2 >> 8, tyT = q2 >> 8;
                        if (txT != lastTx || tyT != lastTy) {
                            tile = mip_.tile(0, txT, tyT);
                            lastTx = txT; lastTy = tyT;
                        }
                        const uint32_t v = tile ? tile[((size_t)(q2 & 255) << 8) + (p2 & 255)] : 0;
                        const uint32_t base = display_.bakeBaseColor(cx, cy, true, v);
                        const int n = ex - sx + 1;
                        uint32_t* dst = drow + (sx - x0);
                        if (bakeGrid) {
                            const float gw = display_.bakeGridWC(), gh = display_.bakeGridHC();
                            float fx = display_.bakeGridFracX(cx);
                            float fy = display_.bakeGridFracY(cy);
                            for (int k = 0; k < n; k++) {
                                dst[k] = (fx <= lwC || fy <= lwC)
                                    ? display_.bakeGridOver(base) : base;
                                fx += dCx;
                                if (fx < 0.f) fx += gw; else if (fx >= gw) fx -= gw;
                                fy += dCy;
                                if (fy < 0.f) fy += gh; else if (fy >= gh) fy -= gh;
                            }
                        } else {
                            std::fill(dst, dst + n, base);
                        }
                        sx = ex + 1;
                    }
                }
                return;
            }
            for (int sy = syA; sy <= syB; sy++) {
                uint32_t* drow = p + (size_t)(sy - y0) * stride;
                float cxf = 0.f, cyf = 0.f;
                display_.screenToCanvasF((float)x0, (float)sy, &cxf, &cyf);
                const bool rowIn = cyf >= 0.f && cyf < (float)ch;
                const int icy = (int)cyf;
                if (!rowIn) {
                    if (bakeOn) std::fill(drow, drow + rectW, bgCol);
                    else memset(drow, 0, (size_t)rectW * 4);
                    continue;
                }
                const bool rowGrid = bakeGrid && display_.bakeGridFracY(cyf) <= lwC;
                const uint32_t* tile = nullptr;
                int lastTx = INT_MIN;
                int sx = x0;
                while (sx <= x1) {
                    const float cxs = cxf + (sx - x0) * dCx;
                    if (cxs < 0.f) {
                        // 画布左外侧: 整段底色
                        int ex = std::min(x1, sx + (int)std::ceil(-cxs / dCx) - 1);
                        if (ex < sx) ex = sx;
                        const int n = ex - sx + 1;
                        if (bakeOn) std::fill(drow + (sx - x0), drow + (sx - x0) + n, bgCol);
                        else memset(drow + (sx - x0), 0, (size_t)n * 4);
                        sx = ex + 1;
                        continue;
                    }
                    const int p2 = (int)cxs;
                    if (p2 >= cw) {
                        const int n = x1 - sx + 1;
                        if (bakeOn) std::fill(drow + (sx - x0), drow + (sx - x0) + n, bgCol);
                        else memset(drow + (sx - x0), 0, (size_t)n * 4);
                        break;
                    }
                    // 本画布像素在屏幕上的结束列: 先估再按逐像素同一表达式校正, 保证边界一致
                    int ex = std::min(x1, sx + (int)std::ceil(((float)(p2 + 1) - cxs) / dCx) - 1);
                    if (ex < sx) ex = sx;
                    while (ex > sx && (int)(cxf + (ex - x0) * dCx) != p2) ex--;
                    while (ex < x1 && (int)(cxf + (ex + 1 - x0) * dCx) == p2) ex++;
                    const int tx = p2 >> 8;
                    if (tx != lastTx) { tile = mip_.tile(0, tx, icy >> 8); lastTx = tx; }
                    const uint32_t v = tile ? tile[((size_t)(icy & 255) << 8) + (p2 & 255)] : 0;
                    const uint32_t base = bakeOn ? display_.bakeBaseColor(cxs, cyf, true, v) : v;
                    const int n = ex - sx + 1;
                    uint32_t* dst = drow + (sx - x0);
                    if (rowGrid) {
                        std::fill(dst, dst + n, display_.bakeGridOver(base));
                    } else if (bakeGrid) {
                        float fx = display_.bakeGridFracX(cxs);
                        for (int k = 0; k < n; k++, fx += dCx)
                            dst[k] = (fx <= lwC) ? display_.bakeGridOver(base) : base;
                    } else {
                        std::fill(dst, dst + n, base);
                    }
                    sx = ex + 1;
                }
            }
            return;
        }
        if (plainCanvas) {
            for (int sy = syA; sy <= syB; sy++) {
                uint32_t* drow = p + (size_t)(sy - y0) * stride;
                if (clipOutside && (sy < cby0 || sy > cby1)) {
                    memset(drow, 0, (size_t)rectW * 4);
                    continue;
                }
                int lx0 = x0, lx1 = x1;
                if (clipOutside) {
                    lx0 = std::max(x0, cbx0);
                    lx1 = std::min(x1, cbx1);
                    if (lx0 > x0) memset(drow, 0, (size_t)(lx0 - x0) * 4);
                    if (lx1 < x1) memset(drow + (lx1 - x0 + 1), 0, (size_t)(x1 - lx1) * 4);
                    if (lx0 > lx1) continue;
                }
                float cxf = 0, cyf = 0;
                display_.screenToCanvasF((float)x0, (float)sy, &cxf, &cyf);
                int lastTx = INT_MIN, lastTy = INT_MIN;
                const uint32_t* tile = nullptr;
                for (int sx = lx0; sx <= lx1; sx++) {
                    const float cx = cxf + (sx - x0) * dCx;
                    const float cy = cyf + (sx - x0) * dCy;
                    uint32_t v = 0;
                    if (cx >= 0.f && cy >= 0.f && cx < (float)cw && cy < (float)ch) {
                        const int icx = (int)cx, icy = (int)cy;
                        const int lx = icx >> level, ly = icy >> level;
                        const int tx = lx >> 8, ty = ly >> 8;
                        if (tx != lastTx || ty != lastTy) {
                            tile = mip_.tile(level, tx, ty); lastTx = tx; lastTy = ty;
                        }
                        if (tile) v = tile[((size_t)(ly & 255) << 8) + (lx & 255)];
                    }
                    if (bakeChk) {
                        v = display_.bakeCheckerPixel(cx, cy,
                            cx >= 0.f && cy >= 0.f && cx < (float)cw && cy < (float)ch, v);
                    }
                    drow[sx - x0] = v;
                }
            }
            return;
        }
        for (int sy = syA; sy <= syB; sy++) {
            uint32_t* drow = p + (size_t)(sy - y0) * stride;
            if (clipOutside && (sy < cby0 || sy > cby1)) {
                memset(drow, 0, (size_t)rectW * 4);
                continue;
            }
            int lx0 = x0, lx1 = x1;
            if (clipOutside) {
                lx0 = std::max(x0, cbx0);
                lx1 = std::min(x1, cbx1);
                if (lx0 > x0) memset(drow, 0, (size_t)(lx0 - x0) * 4);
                if (lx1 < x1) memset(drow + (lx1 - x0 + 1), 0, (size_t)(x1 - lx1) * 4);
                if (lx0 > lx1) continue;
            }
            float cxf = 0, cyf = 0;
            display_.screenToCanvasF((float)x0, (float)sy, &cxf, &cyf);
            int lastTx = INT_MIN, lastTy = INT_MIN;
            const uint32_t* tile = nullptr;
            for (int sx = lx0; sx <= lx1; sx++) {
                const float cx = cxf + (sx - x0) * dCx;
                const float cy = cyf + (sx - x0) * dCy;
                if (replayOn) {
                    uint32_t rv = 0;
                    if (cx >= 0.f && cy >= 0.f && cx < (float)cw && cy < (float)ch) {
                        int rx = (int)(cx * (float)replayImg->w / (float)cw);
                        int ry = (int)(cy * (float)replayImg->h / (float)ch);
                        rx = std::max(0, std::min(rx, replayImg->w - 1));
                        ry = std::max(0, std::min(ry, replayImg->h - 1));
                        rv = replayImg->px[(size_t)ry * replayImg->w + rx];
                    }
                    drow[sx - x0] = rv;
                    continue;
                }
                uint32_t v = 0;
                if (cx >= 0 && cy >= 0 && cx < cw && cy < ch) {
                    const int icx = (int)cx, icy = (int)cy;
                    const bool inCut = cut && icx >= kx0 && icx <= kx1 && icy >= ky0 && icy <= ky1;
                    if (inCut && canvas_.cutLayer() >= 0) {
                        // 层感知剪切: mip 里保留着被剪层的源像素(浮层要采样), 背景这里现算:
                        // 只挖空被剪层, 其它层照常合成, 避免"隔几个图层也被裁掉"。
                        v = raster::composeLayersPixelEx(canvas_, icx, icy, true, true, true);
                    } else if (!(cutHideAll && inCut)) {
                        const int lx = icx >> level, ly = icy >> level;
                        const int tx = lx >> 8, ty = ly >> 8;
                        if (tx != lastTx || ty != lastTy) { tile = mip_.tile(level, tx, ty); lastTx = tx; lastTy = ty; }
                        if (tile) v = tile[((size_t)(ly & 255) << 8) + (lx & 255)];
                    }
                    // 色阶实时预览: 对合成后的画布像素做 LUT(与 applyLevelsLut 同 pre-multiply 语义)
                    if (lvR && v) {
                        const uint32_t a = v >> 24;
                        if (a) {
                            auto un = [&](uint32_t c) { return std::min(255u, (c * 255u + a / 2) / a); };
                            const uint32_t r = lvR[un(v & 255)], g = lvG[un((v >> 8) & 255)], b = lvB[un((v >> 16) & 255)];
                            v = (a << 24) | ((b * a / 255) << 16) | ((g * a / 255) << 8) | (r * a / 255);
                        }
                    }
                    // 洋葱皮: 前后帧淡色垫在当前帧下面(播放下不显示)
                    if (onPrev || onNext) {
                        if ((v >> 24) < 255) {
                            const size_t oi = (size_t)icy * cw + icx;
                            uint32_t base = 0;
                            if (onPrev && oi < onionPrev_.size()) base = onionPrev_[oi];
                            if (onNext && oi < onionNext_.size()) {
                                uint32_t nb = onionNext_[oi];
                                if (nb) { if (base) raster::overPixel(nb, base); base = nb; }
                            }
                            if (base) { raster::overPixel(base, v); v = base; }
                        }
                    }
                } else if (pad25on) {
                    // 画布外: 2.5D 上层错位溢出画布的部分(只画在 pad 范围内)
                    const int icx = (int)std::floor(cx), icy = (int)std::floor(cy);
                    if (icx >= -pad25 && icy >= -pad25 && icx < cw + pad25 && icy < ch + pad25)
                        v = raster::composeLayersPixelExOut(canvas_, icx, icy, true);
                }
                // 图形工具预览已经直接画进画布像素(和落层同源), 这里不再叠加屏幕预览
                // bbox 未算(普通浮层移动/未变换)时不做提前跳过
                const bool inFloatBox = fsx1 < fsx0 ||
                    (sx >= fsx0 && sx <= fsx1 && sy >= fsy0 && sy <= fsy1);
                if (inFloatBox && selExt && cx >= 0 && cy >= 0 && cx < cw && cy < ch) {
                    // 导入浮层只显示画布范围内的部分(出画布不显示, 数据仍保留)
                    const uint32_t e = dimByLayerMask(sel_.extAtPre(selMap, (int)cx, (int)cy),
                                                      (int)cx, (int)cy);
                    if (e) raster::overPixel(v, e);
                }
                if (inFloatBox && !selExt && fv && fW > 0 && fH > 0) {
                    // 预览必须和落层同源同采样: 都以"目标画布像素中心"逆映射, 直接读画布像素,
                    // 不再从 LOD/mip 采样(降采样和图层合成会让预览与最终结果不一致)。
                    const int dcx = (int)cx, dcy = (int)cy;
                    int scx = -1, scy = -1;
                    if (selXf) {
                        sel_.mapToSourcePre(selMap, (float)dcx + 0.5f, (float)dcy + 0.5f, &scx, &scy);
                    } else {
                        if (dcx >= 0 && dcy >= 0 && dcx < cw && dcy < ch &&
                            dcx >= fDstX && dcx < fDstX + fW && dcy >= fDstY && dcy < fDstY + fH) {
                            const int tx = fSrcX + (dcx - fDstX), ty = fSrcY + (dcy - fDstY);
                            if (tx >= 0 && ty >= 0 && tx < cw && ty < ch) { scx = tx; scy = ty; }
                        }
                    }
                    if (scx >= 0 && scy >= 0) {
                        const uint32_t f = dimByLayerMask(sel_.previewPixel(scx, scy, selXfAll),
                                                          dcx, dcy);
                        if (f) raster::overPixel(v, f);
                    }
                }
                if (bakeChk) {
                    v = display_.bakeCheckerPixel(cx, cy,
                        cx >= 0.f && cy >= 0.f && cx < (float)cw && cy < (float)ch, v);
                }
                drow[sx - x0] = v;
            }
        }
    };
    {
        const auto tb0 = std::chrono::steady_clock::now();
        if ((long)rectW * rectH > 200000) pool_.run(4, [&](int i) { renderBand(i, 4); });
        else renderBand(0, 1);
        bandUs_.store((int)std::chrono::duration_cast<std::chrono::microseconds>(
                          std::chrono::steady_clock::now() - tb0).count());
    }
    if (pfTx1 >= pfTx0 && pfTy1 >= pfTy0) {
        mip_.trim(level, pfTx0, pfTy0, pfTx1, pfTy1);
    }
    if (replayOn) {
        // 回放只画项目画布视口内的帧, 不叠加选区/网格/测距/Loupe 等编辑预览
        const auto tu0 = std::chrono::steady_clock::now();
        const bool uploaded = display_.endUpdate();
        upUs_.store((int)std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - tu0).count());
        const int us = (int)std::chrono::duration_cast<std::chrono::microseconds>(
                           std::chrono::steady_clock::now() - t0).count();
        compUs_.store(us);
        drainMs_.store(us / 1000);
        if (uploaded) {
            screenFull_ = false;
            sdX1_ = sdX0_ - 1;
            if (fullBurst_ > 0) fullBurst_--;
        }
        return;
    }
    // 预览层: 套索/多边形套索/柳叶/测距 —— 直接写 AHB staging(屏幕空间 1px 线)
    {
        auto drawSeg = [&](int ax0, int ay0, int ax1, int ay1, uint32_t col, int thick) {
            float fx0, fy0, fx1, fy1;
            // 旧项目预览线走像素中心(CanvasPixelToScreen 的 +0.5), 不然线心和像素标记错半格
            display_.canvasToScreenF((float)ax0 + 0.5f, (float)ay0 + 0.5f, &fx0, &fy0);
            display_.canvasToScreenF((float)ax1 + 0.5f, (float)ay1 + 0.5f, &fx1, &fy1);
            int px0 = (int)std::lround(fx0), py0 = (int)std::lround(fy0);
            const int px1 = (int)std::lround(fx1), py1 = (int)std::lround(fy1);
            int dx = std::abs(px1 - px0), sx = px0 < px1 ? 1 : -1;
            int dy = -std::abs(py1 - py0), sy = py0 < py1 ? 1 : -1;
            int err = dx + dy;
            const int th = std::max(1, thick);
            const int h0 = (th - 1) / 2, h1 = th / 2;
            for (;;) {
                const int bx = px0 - h0, by = py0 - h0;
                for (int yy = 0; yy < th; yy++) {
                    const int py = by + yy;
                    if (py < std::max(y0, 0) || py > std::min(y1, vh - 1)) continue;
                    uint32_t* row = p + (size_t)(py - y0) * stride;
                    for (int xx = 0; xx < th; xx++) {
                        const int px = bx + xx;
                        if (px < std::max(x0, 0) || px > std::min(x1, vw - 1)) continue;
                        raster::overPixel(row[px - x0], col);
                    }
                }
                if (px0 == px1 && py0 == py1) break;
                const int e2 = 2 * err;
                if (e2 >= dy) { err += dy; px0 += sx; }
                if (e2 <= dx) { err += dx; py0 += sy; }
            }
        };
        const int stPrev = shapeType_.load();
        // 测距红线按主人指定: 2 屏幕px; 方框/十字按旧项目 2UI≈4px
        const float measUi = (float)vh / 1167.0f;
        const int measThick = 2;
        const int measFrameThick = std::max(2, (int)std::lround(2.0f * measUi));
        // 屏幕空间粗线带(平行四边形带, 平切端头): 比 6×6 方章干净, 不显粗/不带锯齿
        auto drawBand = [&](int ax0, int ay0, int ax1, int ay1, uint32_t col, int thick) {
            std::vector<int> xy;
            raster::thickLinePixels(ax0, ay0, ax1, ay1, thick, xy);
            for (size_t i = 0; i + 1 < xy.size(); i += 2) {
                const int xx = xy[i], yy = xy[i + 1];
                if (xx < std::max(x0, 0) || xx > std::min(x1, vw - 1) ||
                    yy < std::max(y0, 0) || yy > std::min(y1, vh - 1)) continue;
                raster::overPixel(p[(size_t)(yy - y0) * stride + (xx - x0)], col);
            }
        };
        auto drawBandCanvas = [&](int ax0, int ay0, int ax1, int ay1, uint32_t col, int thick) {
            float fx0, fy0, fx1, fy1;
            display_.canvasToScreenF((float)ax0 + 0.5f, (float)ay0 + 0.5f, &fx0, &fy0);
            display_.canvasToScreenF((float)ax1 + 0.5f, (float)ay1 + 0.5f, &fx1, &fy1);
            drawBand((int)std::lround(fx0), (int)std::lround(fy0),
                     (int)std::lround(fx1), (int)std::lround(fy1), col, thick);
        };
        if (stPrev == 6 && measOn_) {
            // 旧 Measure.cs: 红线 2.5UI 常显, 松手后黑方框+贯穿十字 + 起/中/终三个 1 画布px 空心方块
            drawBandCanvas(measX0_, measY0_, measX1_, measY1_, 0xF24D40F2, measThick);
            const int mact = lastAct_.load();
            if (mact != 0 && mact != 1) {
                const int mx0 = std::min(measX0_, measX1_), mx1 = std::max(measX0_, measX1_);
                const int my0 = std::min(measY0_, measY1_), my1 = std::max(measY0_, measY1_);
                drawSeg(mx0, my0, mx1, my0, 0xD9000000, measFrameThick);
                drawSeg(mx1, my0, mx1, my1, 0xD9000000, measFrameThick);
                drawSeg(mx1, my1, mx0, my1, 0xD9000000, measFrameThick);
                drawSeg(mx0, my1, mx0, my0, 0xD9000000, measFrameThick);
                const int midY = (my0 + my1) / 2, midX = (mx0 + mx1) / 2;
                // 固定起点模式: 旧项目不画贯穿十字/中心标记(中心没有意义)
                if (!measFixed_) {
                    drawSeg(mx0, midY, mx1, midY, 0xD9000000, measFrameThick);
                    drawSeg(midX, my0, midX, my1, 0xD9000000, measFrameThick);
                }
                // 旧 pm144: 三点 1 画布px 空心方块 + 黑白渐变闪烁(蚂蚁线式)
                const float nowS = std::chrono::duration<float>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
                const float glow = (std::sin(nowS * 3.14159265f) + 1.0f) * 0.5f;
                const uint32_t gv = (uint32_t)(255.0f * (1.0f - glow));
                const uint32_t mc = (0xD9u << 24) | (gv << 16) | (gv << 8) | gv;
                // 旧 pm144: 三点标记=该画布像素单元的"边缘"(四条边), 线宽 1 屏幕px。
                // 🚨 之前用 half=zoom/2 当画布偏移 → 方块有 zoom 个画布px 那么大(663% 下 7px=46屏幕px),
                // 看起来就是一个大白框; 正确做法是画 (cx..cx+1) 这一格的四个屏幕边。
                auto putPx = [&](int xx, int yy) {
                    if (xx < std::max(x0, 0) || xx > std::min(x1, vw - 1) ||
                        yy < std::max(y0, 0) || yy > std::min(y1, vh - 1)) return;
                    raster::overPixel(p[(size_t)(yy - y0) * stride + (xx - x0)], mc);
                };
                auto mark = [&](int cx, int cy) {
                    float fx0, fy0, fx1, fy1;
                    display_.canvasToScreenF((float)cx, (float)cy, &fx0, &fy0);
                    display_.canvasToScreenF((float)cx + 1.0f, (float)cy + 1.0f, &fx1, &fy1);
                    const int xa = (int)std::lround(fx0), ya = (int)std::lround(fy0);
                    const int xb = (int)std::lround(fx1), yb = (int)std::lround(fy1);
                    for (int xx = xa; xx <= xb; xx++) { putPx(xx, ya); putPx(xx, yb); }
                    for (int yy = ya; yy <= yb; yy++) { putPx(xa, yy); putPx(xb, yy); }
                };
                mark(measX0_, measY0_);
                mark(measX1_, measY1_);
                if (!measFixed_) mark(midX, midY);
            }
        }
    }
    // 测距放大镜(旧 Measure.cs): 方形窗, 9×9 画布像素, 透明像素画棋盘格、画布外画布外背景,
    // 中心 1 画布像素空心标记(黑白渐变闪烁); 位置在手指上方, 到顶则放下方。
    if (measLoupe) {
        const int cxp = lastRawX_.load(), cyp = lastRawY_.load();
        int pcx = 0, pcy = 0;
        display_.screenToCanvas((float)cxp, (float)cyp, &pcx, &pcy);
        constexpr int SN = 9;               // 9×9 画布像素
        uint32_t samples[SN * SN];
        for (int sy = -4; sy <= 4; sy++) {
            for (int sx = -4; sx <= 4; sx++) {
                const int px = pcx + sx, py = pcy + sy;
                uint32_t col;
                if (px < 0 || py < 0 || px >= cw || py >= ch) {
                    col = magBg_;           // 画布外=画布外背景色
                } else {
                    // 旧项目放大镜取 CanvasData(画布合成像素), 不取屏幕缓冲;
                    // 镜面上的测距红线在放大镜画完后用"测距直线"(2px)补画(见下方)
                    uint32_t v = 0;
                    if (const uint32_t* mt = mip_.tile(0, px >> 8, py >> 8))
                        v = mt[((size_t)(py & 255) << 8) + (px & 255)];
                    if (!(v >> 24)) {
                        // 透明像素=与主画布一致的棋盘格(旧 am2)
                        const int tile = std::max(1, (int)std::lround(magChkTile_));
                        col = (((px / tile + py / tile) & 1) == 0) ? magChkA_ : magChkB_;
                    } else {
                        col = v;
                    }
                }
                samples[(sy + 4) * SN + (sx + 4)] = col;
            }
        }
        const float ui = (float)vh / 1167.0f;               // 旧项目 UI 单位
        const int W = std::max(120, (int)std::lround(120.f * ui));
        const int pad = std::max(3, (int)std::lround(4.f * ui));
        const int off = std::max(60, (int)std::lround(90.f * ui));
        int wx = cxp - W / 2;
        int wy = cyp - off - W / 2;
        if (wy < 8) wy = cyp + off - W / 2;
        wx = std::max(8, std::min(wx, vw - 1 - W - 8));
        wy = std::max(8, std::min(wy, vh - 1 - W - 8));
        // 窗底(画布外背景色, 全不透明) + 白色细边
        for (int yy = wy; yy < wy + W; yy++) {
            if (yy < y0 || yy > y1) continue;
            uint32_t* row = p + (size_t)(yy - y0) * stride;
            for (int xx = wx; xx < wx + W; xx++) {
                if (xx < x0 || xx > x1) continue;
                const bool edge = (xx == wx || xx == wx + W - 1 || yy == wy || yy == wy + W - 1);
                row[xx - x0] = edge ? 0xD9FFFFFFu : magBg_;
            }
        }
        // 9×9 画布像素 → 窗内纹理
        const int innerX = wx + pad, innerY = wy + pad;
        const int innerW = W - 2 * pad;
        // 告诉 GL: 这块窗口内不要画屏幕网格(镜内网格下面按放大位置画)
        display_.setLoupeRect(wx, wy, wx + W - 1, wy + W - 1);
        if (innerW > 4) {
            // 镜内测距红线: 只画"落在放大区域里的那段红线", 并且按镜面放大率拉伸成直线
            // (不是屏幕矢量线盖在镜面上, 也不是逐画布像素的画笔线)。
            // 线在画布坐标里的半厚 = (2屏幕px/2)/zoom; 输出像素的坐标按样本块换算回画布。
            const double mAx = (double)measX0_ + 0.5, mAy = (double)measY0_ + 0.5;
            const double mBx = (double)measX1_ + 0.5, mBy = (double)measY1_ + 0.5;
            const double mDx = mBx - mAx, mDy = mBy - mAy;
            const double mL2 = mDx * mDx + mDy * mDy;
            const bool mLine = measOn_ && mL2 > 1e-9;
            const float sampPerOut = (float)SN / (float)innerW;         // 输出像素→画布px
            // 2px 红线的一半(画布px); 镜内至少 2 输出px 宽, 保证"必须看得见"
            const double mHalf = std::max((double)sampPerOut,
                                          1.0 / (double)std::max(1e-3f, zoom));
            for (int yy = innerY; yy < innerY + innerW; yy++) {
                if (yy < y0 || yy > y1) continue;
                const int by = std::min(SN - 1, (yy - innerY) * SN / innerW);
                uint32_t* row = p + (size_t)(yy - y0) * stride;
                for (int xx = innerX; xx < innerX + innerW; xx++) {
                    if (xx < x0 || xx > x1) continue;
                    const int bx = std::min(SN - 1, (xx - innerX) * SN / innerW);
                    uint32_t col = samples[by * SN + bx];
                    if (mLine) {
                        const double cxL = (double)(pcx - 4) + ((double)(xx - innerX) + 0.5) * sampPerOut;
                        const double cyL = (double)(pcy - 4) + ((double)(yy - innerY) + 0.5) * sampPerOut;
                        double u = (((cxL - mAx) * mDx) + ((cyL - mAy) * mDy)) / mL2;
                        u = std::max(0.0, std::min(1.0, u));
                        const double ex = mAx + mDx * u - cxL, ey = mAy + mDy * u - cyL;
                        if (ex * ex + ey * ey <= mHalf * mHalf) raster::overPixel(col, 0xF24D40F2);
                    }
                    row[xx - x0] = col;
                }
            }
            // 镜内网格线: 与主画布同一套网格(间距/颜色/淡化), 1 屏幕px,
            // 位置=放大后的真实画布网格线(样本块边界), 画布外不画。
            const float gridCellPx = std::min(display_.gridCellW(), display_.gridCellH()) * display_.zoom();
            if (display_.gridEnabled() && gridCellPx >= 6.0f) {   // 与主画布同一可见性规则(格<6px 主网格隐藏)
                const int gw = std::max(1, (int)std::lround(display_.gridCellW()));
                const int gh = std::max(1, (int)std::lround(display_.gridCellH()));
                const float ga = std::max(0.0f, std::min(1.0f, display_.gridAlphaVal()));
                const float gr = display_.gridRVal(), gg = display_.gridGVal(), gb = display_.gridBVal();
                auto gridMix = [&](uint32_t c) -> uint32_t {
                    auto ch = [&](uint32_t v, float target) -> uint32_t {
                        const uint32_t m = (uint32_t)std::lround((float)v * (1.f - ga) + target * 255.f * ga);
                        return std::min(255u, m);
                    };
                    const uint32_t r = ch(c & 255u, gr), g = ch((c >> 8) & 255u, gg), b = ch((c >> 16) & 255u, gb);
                    return 0xFF000000u | (b << 16) | (g << 8) | r;
                };
                auto gput = [&](int xx, int yy) {
                    if (xx < x0 || xx > x1 || yy < y0 || yy > y1) return;
                    uint32_t* row = p + (size_t)(yy - y0) * stride;
                    row[xx - x0] = gridMix(row[xx - x0]);
                };
                // 竖向网格线(样本块左缘)
                for (int bx = 0; bx < SN; bx++) {
                    const int px = pcx - 4 + bx;
                    if (((px % gw) + gw) % gw != 0) continue;
                    const int xx = innerX + (bx * innerW + SN - 1) / SN;   // 该样本块第一个屏幕像素
                    for (int yy = innerY; yy < innerY + innerW; yy++) {
                        const int by = std::min(SN - 1, (yy - innerY) * SN / innerW);
                        const int py = pcy - 4 + by;
                        if (px < 0 || px >= cw || py < 0 || py >= ch) continue;
                        gput(xx, yy);
                    }
                }
                // 横向网格线(样本块上缘)
                for (int by = 0; by < SN; by++) {
                    const int py = pcy - 4 + by;
                    if (((py % gh) + gh) % gh != 0) continue;
                    const int yy = innerY + (by * innerW + SN - 1) / SN;   // 该样本块第一个屏幕像素
                    for (int xx = innerX; xx < innerX + innerW; xx++) {
                        const int bx = std::min(SN - 1, (xx - innerX) * SN / innerW);
                        const int px = pcx - 4 + bx;
                        if (px < 0 || px >= cw || py < 0 || py >= ch) continue;
                        gput(xx, yy);
                    }
                }
            }
            // 中心 1 画布像素空心标记(黑白渐变闪烁)
            const float nowS = std::chrono::duration<float>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            const float glow = (std::sin(nowS * 3.14159265f) + 1.0f) * 0.5f;
            const uint32_t gv = (uint32_t)(255.0f * (1.0f - glow));
            const uint32_t mcol = (0xD9u << 24) | (gv << 16) | (gv << 8) | gv;
            // 中心标记=一个放大像素块的"边缘大小"(正好一个块, 不再向外扩 1px)
            const int c0x = innerX + (4 * innerW + SN - 1) / SN;
            const int c1x = innerX + (5 * innerW + SN - 1) / SN - 1;
            const int c0y = innerY + (4 * innerW + SN - 1) / SN;
            const int c1y = innerY + (5 * innerW + SN - 1) / SN - 1;
            auto put = [&](int xx, int yy) {
                if (xx < x0 || xx > x1 || yy < y0 || yy > y1) return;
                p[(size_t)(yy - y0) * stride + (xx - x0)] = mcol;
            };
            for (int xx = c0x; xx <= c1x; xx++) { put(xx, c0y); put(xx, c1y); }
            for (int yy = c0y; yy <= c1y; yy++) { put(c0x, yy); put(c1x, yy); }
        }
    }
    const auto tu1 = std::chrono::steady_clock::now();
    const bool uploaded = display_.endUpdate();
    upUs_.store((int)std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - tu1).count());
    const int us = (int)std::chrono::duration_cast<std::chrono::microseconds>(
                       std::chrono::steady_clock::now() - t0).count();
    compUs_.store(us);
    drainMs_.store(us / 1000);
    // 只有像素真正写进屏幕缓冲才清脏标记; AHB 锁失败时保留, 下一帧接着重绘
    if (uploaded) {
        screenFull_ = false;
        sdX1_ = sdX0_ - 1;
        if (fullBurst_ > 0) fullBurst_--;
    }
}

void Engine::flushDirty() {
    flushScreenDirty();
    if (screenFull_ || sdX1_ >= sdX0_ || fullBurst_ > 0) renderScreen();
}

static long procKb(const char* key) {    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    size_t kl = strlen(key);
    long val = 0;
    while (fgets(line, sizeof line, f)) {
        if (strncmp(line, key, kl) == 0) { val = atol(line + kl + 1); break; }
    }
    fclose(f);
    return val;
}

void Engine::cropToSelection() {
    // 必须回到渲染线程执行(要用 EGL 上下文建纹理), 这里只入队
    std::lock_guard<std::mutex> lk(mu_);
    if (queue_.size() < 4096) queue_.push_back({6, 0.0f, 0.0f, 0.0f});
}

std::string Engine::stats() {
    char buf[1024];   // stats 行变长(selp/meas/fr/u/r), 360 会把尾部截断导致 UI 读不到帧/选区
    const char* st = status_.load() == 1 ? "run" : (status_.load() == 2 ? "FAIL" : "init");
    snprintf(buf, sizeof buf, "fps=%d ft=%.1f/%.1f comp=%.1f pend=%d drn=%dms jk=%d tool=%d mode=%d polyv=%d polyon=%d | %dx%d L%d A%d ink=%d | z=%.3f | %s | %s",
             fps_.load(), lastFrameUs_.load() / 1000.0, maxFrameUs_.load() / 1000.0,
             compUs_.load() / 1000.0, pendingN_.load(), drainMs_.load(), jank_.load(), tool_.load(), sel_.mode(),
             polyActive_ ? (int)poly_.size() / 2 : 0,
             (tool_.load() == 4 && shapeType_.load() == 7) ? 1 : 0,
             canvas_.width(), canvas_.height(), layerCount_.load(), activeLayer_.load(), inkPixels_.load(),
             display_.zoom(), sel_.describe().c_str(),
             display_.usingAhb() ? "AHB" : "texSub");
    {
        unsigned visBits = 0, lckBits = 0;
        for (int l = 0; l < canvas_.layers() && l < 32; l++) {
            if (canvas_.layerVisible(l)) visBits |= 1u << l;
            if (canvas_.isLayerLocked(l)) lckBits |= 1u << l;
        }
        snprintf(buf + strlen(buf), sizeof buf - strlen(buf),
                 " cap=%d lcskip=%d pick=%08X pickprev=%08X pseq=%d fr=%d/%d play=%d spd=%d loop=%d-%d vis=%X lck=%X lc=%d msk=%d wskip=%d",
                 fillCaps_.load(), lastCutSkip_.load(), pickColor_.load() & 0xFFFFFFFF,
                 pickPrev_.load() & 0xFFFFFFFF, pickSeq_.load(),
                 frameIdx_.load() + 1, std::max(1, frameCount_.load()), playing_.load(),
                 playFps_.load(), loopStart_.load() + 1, loopEnd_.load() + 1, visBits, lckBits, sel_.hasLastCut() ? 1 : 0,
                 sel_.maskPixelCount(), wandSkip_.load());
        snprintf(buf + strlen(buf), sizeof buf - strlen(buf), " rec=%d/%d onion=%d u=%d r=%d",
                 recordOn_.load() ? 1 : 0, recordN_.load(), onionOn_.load() ? 1 : 0,
                 (int)undo_.size(), (int)redo_.size());
        {
            int sx0 = 0, sy0 = 0, sx1 = 0, sy1 = 0;
            display_.canvasRectToScreen(0, 0, canvas_.width() - 1, canvas_.height() - 1,
                                        &sx0, &sy0, &sx1, &sy1);
            snprintf(buf + strlen(buf), sizeof buf - strlen(buf), " cv=%d,%d,%d,%d", sx0, sy0, sx1, sy1);
        }
    }
    snprintf(buf + strlen(buf), sizeof buf - strlen(buf), " measf=%d meassp=%d",
             measFixed_ ? 1 : 0, measSetStartPending_ ? 1 : 0);
    if (measOn_) {
        snprintf(buf + strlen(buf), sizeof buf - strlen(buf), " meas %d %d %d %d",
                 measX0_, measY0_, measX1_, measY1_);
        float fx0 = 0, fy0 = 0, fx1 = 0, fy1 = 0;
        display_.canvasToScreenF((float)measX0_, (float)measY0_, &fx0, &fy0);
        display_.canvasToScreenF((float)measX1_, (float)measY1_, &fx1, &fy1);
        snprintf(buf + strlen(buf), sizeof buf - strlen(buf), " meass %d,%d,%d,%d measd %d",
                 (int)std::lround(fx0), (int)std::lround(fy0),
                 (int)std::lround(fx1), (int)std::lround(fy1),
                 (lastAct_.load() == 0 || lastAct_.load() == 1) ? 1 : 0);
    }
    if (sel_.active()) {
        int rx0, ry0, rx1, ry1;
        sel_.rect(&rx0, &ry0, &rx1, &ry1);
        int sx0, sy0, sx1, sy1;
        display_.canvasRectToScreen(rx0, ry0, rx1, ry1, &sx0, &sy0, &sx1, &sy1);
        snprintf(buf + strlen(buf), sizeof buf - strlen(buf), " selp %d %d %d %d",
                 sx0, sy0, sx1, sy1);
    }
    std::string out(buf);
    out.push_back('\n');
    out += " rss=";
    out += std::to_string(procKb("VmRSS:") / 1024);
    out += "MB";
    return out;
}

} // namespace endpix
