#include "mip.h"
#include "../raster/raster.h"
#include <cstring>

namespace endpix {

const uint32_t* MipPyramid::tile(int level, int tx, int ty) {
    if (!canvas_ || level < 0 || tx < 0 || ty < 0) return nullptr;
    if (tx >= tilesX(level) || ty >= tilesY(level)) return nullptr;
    const uint64_t k = key(level, tx, ty);
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = tiles_.find(k);
        if (it != tiles_.end()) return it->second.get();
    }

    // 锁外构建(允许并行构建不同块); 重复构建时以先插入者为准
    auto buf = std::make_unique<uint32_t[]>((size_t)TS * TS);
    const int stride = 1 << level;
    const int x0 = tx * TS * stride, y0 = ty * TS * stride;
    const int cw = canvas_->width(), ch = canvas_->height();
    const int nl = canvas_->layers();
    for (int y = 0; y < TS; y++) {
        const int cy = y0 + y * stride;
        uint32_t* drow = buf.get() + (size_t)y * TS;
        if (cy >= ch) { memset(drow, 0, (size_t)TS * 4); continue; }
        for (int x = 0; x < TS; x++) {
            const int cx = x0 + x * stride;
            if (cx >= cw) { drow[x] = 0; continue; }
            drow[x] = raster::composeLayersPixelEx(*canvas_, cx, cy, true, true, false);
        }
    }
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = tiles_.find(k);
        if (it != tiles_.end()) return it->second.get();
        uint32_t* p = buf.get();
        tiles_.emplace(k, std::move(buf));
        return p;
    }
}

void MipPyramid::updateRect(int x0, int y0, int x1, int y1) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!canvas_) return;
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(canvas_->width() - 1, x1);
    y1 = std::min(canvas_->height() - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int cw = canvas_->width(), ch = canvas_->height();
    const int nl = canvas_->layers();
    for (int level = 0; level < 6; level++) {
        const int span = TS << level;
        const int tx0 = x0 / span, tx1 = x1 / span;
        const int ty0 = y0 / span, ty1 = y1 / span;
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                auto it = tiles_.find(key(level, tx, ty));
                if (it == tiles_.end()) continue;
                uint32_t* t = it->second.get();
                const int bx = tx * span, by = ty * span;
                const int lx0 = std::max(0, (x0 - bx) >> level);
                const int lx1 = std::min(TS - 1, (x1 - bx) >> level);
                const int ly0 = std::max(0, (y0 - by) >> level);
                const int ly1 = std::min(TS - 1, (y1 - by) >> level);
                for (int ly = ly0; ly <= ly1; ly++) {
                    const int cy = by + (ly << level);
                    for (int lx = lx0; lx <= lx1; lx++) {
                        const int cx = bx + (lx << level);
                        uint32_t acc = 0;
                        if (cx < cw && cy < ch) acc = raster::composeLayersPixelEx(*canvas_, cx, cy, true, true, false);
                        t[(size_t)ly * TS + lx] = acc;
                    }
                }
            }
        }
    }
}

void MipPyramid::invalidateRect(int x0, int y0, int x1, int y1) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!canvas_) return;
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(canvas_->width() - 1, x1);
    y1 = std::min(canvas_->height() - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    for (int level = 0; level < 8; level++) {
        const int span = TS << level;
        const int tx0 = x0 / span, tx1 = x1 / span;
        const int ty0 = y0 / span, ty1 = y1 / span;
        for (int ty = ty0; ty <= ty1; ty++)
            for (int tx = tx0; tx <= tx1; tx++)
                tiles_.erase(key(level, tx, ty));
    }
}

} // namespace endpix
