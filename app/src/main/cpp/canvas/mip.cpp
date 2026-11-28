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
    const bool fastCompose = raster::composeLayersFastEligible(*canvas_, true, true, false);
    if (fastCompose) {
        std::vector<int> vis;
        vis.reserve(nl);
        for (int l = 0; l < nl; l++) if (canvas_->layerVisible(l)) vis.push_back(l);
        const int nv = (int)vis.size();
        const int ctx = canvas_->tilesX();
        if (nv == 1) {
            const int l = vis[0];
            for (int y = 0; y < TS; y++) {
                const int cy = y0 + y * stride;
                uint32_t* drow = buf.get() + (size_t)y * TS;
                if (cy >= ch) { memset(drow, 0, (size_t)TS * 4); continue; }
                const int tyi = cy >> Canvas::TILE_SHIFT, ly = cy & (Canvas::TILE - 1);
                int lastTx = -1;
                const uint32_t* pt = nullptr;
                bool solid = false;
                uint32_t scol = 0;
                for (int x = 0; x < TS; x++) {
                    const int cx = x0 + x * stride;
                    if (cx >= cw) { drow[x] = 0; continue; }
                    const int tx = cx >> Canvas::TILE_SHIFT;
                    if (tx != lastTx) {
                        const int ti = tyi * ctx + tx;
                        solid = canvas_->isSolidTile(l, ti);
                        scol = solid ? canvas_->solidTileColor(l, ti) : 0;
                        pt = solid ? nullptr : canvas_->tile(l, ti);
                        lastTx = tx;
                    }
                    drow[x] = solid ? scol : (pt ? pt[((size_t)ly << Canvas::TILE_SHIFT) +
                                                   (cx & (Canvas::TILE - 1))] : 0);
                }
            }
        } else {
            std::vector<const uint32_t*> ptrs(nv);
            std::vector<uint32_t> scols(nv);
            std::vector<uint8_t> solids(nv);
            for (int y = 0; y < TS; y++) {
                const int cy = y0 + y * stride;
                uint32_t* drow = buf.get() + (size_t)y * TS;
                if (cy >= ch) { memset(drow, 0, (size_t)TS * 4); continue; }
                const int tyi = cy >> Canvas::TILE_SHIFT, ly = cy & (Canvas::TILE - 1);
                int lastTx = -1;
                for (int x = 0; x < TS; x++) {
                    const int cx = x0 + x * stride;
                    if (cx >= cw) { drow[x] = 0; continue; }
                    const int tx = cx >> Canvas::TILE_SHIFT;
                    if (tx != lastTx) {
                        const int ti = tyi * ctx + tx;
                        for (int i = 0; i < nv; i++) {
                            const int l = vis[i];
                            const bool s = canvas_->isSolidTile(l, ti);
                            solids[i] = s ? 1 : 0;
                            scols[i] = s ? canvas_->solidTileColor(l, ti) : 0;
                            ptrs[i] = s ? nullptr : canvas_->tile(l, ti);
                        }
                        lastTx = tx;
                    }
                    const int off = (ly << Canvas::TILE_SHIFT) + (cx & (Canvas::TILE - 1));
                    uint32_t acc = 0;
                    for (int i = 0; i < nv; i++) {
                        const uint32_t v = solids[i] ? scols[i] : (ptrs[i] ? ptrs[i][off] : 0);
                        if (v) raster::overPixel(acc, v);
                    }
                    drow[x] = acc;
                }
            }
        }
    } else {
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

void MipPyramid::updateRect(int x0, int y0, int x1, int y1, int visibleLevel) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!canvas_) return;
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(canvas_->width() - 1, x1);
    y1 = std::min(canvas_->height() - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int cw = canvas_->width(), ch = canvas_->height();
    const int nl = canvas_->layers();
    const bool fastCompose = raster::composeLayersFastEligible(*canvas_, true, true, false);
    int singleLayer = -2;
    if (fastCompose) {
        int cnt = 0;
        for (int l = 0; l < nl; l++) if (canvas_->layerVisible(l)) { singleLayer = l; cnt++; }
        if (cnt != 1) singleLayer = -2;
    }
    for (int level = 0; level < 6; level++) {
        const bool recompute = (visibleLevel < 0 || visibleLevel == level);
        const int span = TS << level;
        const int tx0 = x0 / span, tx1 = x1 / span;
        const int ty0 = y0 / span, ty1 = y1 / span;
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                auto it = tiles_.find(key(level, tx, ty));
                if (it == tiles_.end()) continue;
                if (!recompute) {
                    // 非可见级别不重算, 直接失效; 下次缩放/平移按需重建
                    tiles_.erase(it);
                    continue;
                }
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
                        if (cx < cw && cy < ch)
                            acc = singleLayer >= 0 ? canvas_->getPixelL(singleLayer, cx, cy)
                                  : (fastCompose ? raster::composeLayersPixelFast(*canvas_, cx, cy)
                                                 : raster::composeLayersPixelEx(*canvas_, cx, cy, true, true, false));
                        t[(size_t)ly * TS + lx] = acc;
                    }
                }
            }
        }
    }
}

void MipPyramid::trim(int visibleLevel, int tx0, int ty0, int tx1, int ty1,
                      size_t maxTiles) {
    std::lock_guard<std::mutex> lk(mu_);
    if (tiles_.size() <= maxTiles) return;
    for (auto it = tiles_.begin(); it != tiles_.end() && tiles_.size() > maxTiles;) {
        const uint64_t k = it->first;
        const int lv = (int)(k >> 42);
        const int tx = (int)((k >> 21) & ((1u << 21) - 1));
        const int ty = (int)(k & ((1u << 21) - 1));
        const bool pinned = lv == visibleLevel &&
                            tx >= tx0 && tx <= tx1 && ty >= ty0 && ty <= ty1;
        if (pinned) { ++it; continue; }
        it = tiles_.erase(it);
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
