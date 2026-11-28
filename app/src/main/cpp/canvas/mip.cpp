#include "mip.h"
#include "../raster/raster.h"
#include <cmath>
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
    const raster::Mode25D& g = raster::mode25D();
    const bool d25 = g.on && g.depth > 0 && nl > 1;
    // 固定数组(最多 64 可见层): 每个 tile 构建不再分配 6~10 个 vector;
    // 超过 64 层(极端工程)回退逐像素合成, 语义不变。
    int vis[64];
    int nv = 0;
    bool overflow = false;
    for (int l = 0; l < nl; l++) {
        if (!canvas_->layerVisible(l)) continue;
        if (nv < 64) vis[nv++] = l;
        else { overflow = true; break; }
    }
    auto fillSlow = [&]() {
        for (int y = 0; y < TS; y++) {
            const int cy = y0 + y * stride;
            uint32_t* drow = buf.get() + (size_t)y * TS;
            if (cy >= ch) { memset(drow, 0, (size_t)TS * 4); continue; }
            for (int x = 0; x < TS; x++) {
                const int cx = x0 + x * stride;
                drow[x] = (cx < cw)
                    ? raster::composeLayersPixelEx(*canvas_, cx, cy, true, true, false)
                    : 0;
            }
        }
    };
    // 快路径的空 tile 快速判断(只对 L0: 每块只查 1~4 个画布 tile, 收益稳定;
    // 更高级别一块覆盖很多画布 tile, 密集内容时检查反而拖慢, 实测回退)。
    if (fastCompose && !overflow && level == 0) {
        bool any = false;
        const int ctx = canvas_->tilesX();
        for (int i = 0; i < nv && !any; i++) {
            const int l = vis[i];
            int ox = 0, oy = 0;
            if (d25) {
                const int off = l * g.depth;
                ox = (int)std::lround(g.offX * off);
                oy = (int)std::lround(g.offY * off);
            }
            const int sx0 = x0 - ox, sy0 = y0 - oy;
            const int sx1 = sx0 + (TS - 1) * stride;
            const int sy1 = sy0 + (TS - 1) * stride;
            if (sx1 < 0 || sy1 < 0 || sx0 >= cw || sy0 >= ch) continue;
            const int txA = std::max(0, sx0) >> Canvas::TILE_SHIFT;
            const int txB = std::min(cw - 1, sx1) >> Canvas::TILE_SHIFT;
            const int tyA = std::max(0, sy0) >> Canvas::TILE_SHIFT;
            const int tyB = std::min(ch - 1, sy1) >> Canvas::TILE_SHIFT;
            for (int ty2 = tyA; ty2 <= tyB && !any; ty2++) {
                for (int tx2 = txA; tx2 <= txB; tx2++) {
                    const int ti = ty2 * ctx + tx2;
                    if (canvas_->isSolidTile(l, ti) || canvas_->tile(l, ti)) { any = true; break; }
                }
            }
        }
        if (!any) {
            memset(buf.get(), 0, (size_t)TS * TS * 4);
            std::lock_guard<std::mutex> lk(mu_);
            auto it = tiles_.find(k);
            if (it != tiles_.end()) return it->second.get();
            uint32_t* p = buf.get();
            tiles_.emplace(k, std::move(buf));
            return p;
        }
    }
    if (overflow || !fastCompose) {
        fillSlow();
    } else if (d25) {
        const int ctx = canvas_->tilesX();
        int oxs[64], oys[64];
        for (int i = 0; i < nv; i++) {
            const int off = vis[i] * g.depth;
            oxs[i] = (int)std::lround(g.offX * off);
            oys[i] = (int)std::lround(g.offY * off);
        }
        const uint32_t* ptrs[64];
        uint32_t scols[64];
        uint8_t solids[64], rowOk[64];
        int lastTx[64], tyi[64], lyv[64];
        for (int y = 0; y < TS; y++) {
            const int cy = y0 + y * stride;
            uint32_t* drow = buf.get() + (size_t)y * TS;
            for (int i = 0; i < nv; i++) {
                const int sy = cy - oys[i];
                rowOk[i] = (sy >= 0 && sy < ch && cy < ch) ? 1 : 0;
                if (rowOk[i]) {
                    tyi[i] = sy >> Canvas::TILE_SHIFT;
                    lyv[i] = sy & (Canvas::TILE - 1);
                    lastTx[i] = -1;
                } else {
                    lastTx[i] = -2;
                }
            }
            for (int x = 0; x < TS; x++) {
                const int cx = x0 + x * stride;
                if (cx >= cw) { drow[x] = 0; continue; }
                uint32_t acc = 0;
                for (int i = 0; i < nv; i++) {
                    if (!rowOk[i]) continue;
                    const int sx = cx - oxs[i];
                    if (sx < 0 || sx >= cw) continue;
                    const int tx2 = sx >> Canvas::TILE_SHIFT;
                    if (tx2 != lastTx[i]) {
                        const int ti = tyi[i] * ctx + tx2;
                        const int l = vis[i];
                        const bool s = canvas_->isSolidTile(l, ti);
                        solids[i] = s ? 1 : 0;
                        scols[i] = s ? canvas_->solidTileColor(l, ti) : 0;
                        ptrs[i] = s ? nullptr : canvas_->tile(l, ti);
                        lastTx[i] = tx2;
                    }
                    const uint32_t v = solids[i] ? scols[i]
                        : (ptrs[i] ? ptrs[i][((size_t)lyv[i] << Canvas::TILE_SHIFT) +
                                            (sx & (Canvas::TILE - 1))] : 0);
                    if (v) raster::overPixel(acc, v);
                }
                drow[x] = acc;
            }
        }
    } else if (nv == 1) {
        const int l = vis[0];
        const int ctx = canvas_->tilesX();
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
                const int tx2 = cx >> Canvas::TILE_SHIFT;
                if (tx2 != lastTx) {
                    const int ti = tyi * ctx + tx2;
                    solid = canvas_->isSolidTile(l, ti);
                    scol = solid ? canvas_->solidTileColor(l, ti) : 0;
                    pt = solid ? nullptr : canvas_->tile(l, ti);
                    lastTx = tx2;
                }
                drow[x] = solid ? scol : (pt ? pt[((size_t)ly << Canvas::TILE_SHIFT) +
                                               (cx & (Canvas::TILE - 1))] : 0);
            }
        }
    } else {
        const int ctx = canvas_->tilesX();
        const uint32_t* ptrs[64];
        uint32_t scols[64];
        uint8_t solids[64];
        for (int y = 0; y < TS; y++) {
            const int cy = y0 + y * stride;
            uint32_t* drow = buf.get() + (size_t)y * TS;
            if (cy >= ch) { memset(drow, 0, (size_t)TS * 4); continue; }
            const int tyi = cy >> Canvas::TILE_SHIFT, ly = cy & (Canvas::TILE - 1);
            int lastTx = -1;
            for (int x = 0; x < TS; x++) {
                const int cx = x0 + x * stride;
                if (cx >= cw) { drow[x] = 0; continue; }
                const int tx2 = cx >> Canvas::TILE_SHIFT;
                if (tx2 != lastTx) {
                    const int ti = tyi * ctx + tx2;
                    for (int i = 0; i < nv; i++) {
                        const int l = vis[i];
                        const bool s = canvas_->isSolidTile(l, ti);
                        solids[i] = s ? 1 : 0;
                        scols[i] = s ? canvas_->solidTileColor(l, ti) : 0;
                        ptrs[i] = s ? nullptr : canvas_->tile(l, ti);
                    }
                    lastTx = tx2;
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
    int vis[64];
    int nv = 0;
    bool visOverflow = false;
    if (fastCompose) {
        int cnt = 0;
        for (int l = 0; l < nl; l++) {
            if (canvas_->layerVisible(l)) {
                singleLayer = l;
                cnt++;
                if (nv < 64) vis[nv++] = l;
                else visOverflow = true;
            }
        }
        if (cnt != 1) singleLayer = -2;
    }
    // 2.5D 时每层有位移, 逐像素现算(与 composeLayersPixelFast 一致); 其余走行级 tile 指针缓存
    const raster::Mode25D& g25 = raster::mode25D();
    const bool d25 = g25.on && g25.depth > 0 && nl > 1;
    const bool rowFast = fastCompose && !d25 && nv > 0 && !visOverflow;
    const int ctx = canvas_->tilesX();
    const uint32_t* ptrs[64];
    uint32_t scols[64];
    uint8_t solids[64];
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
                if (rowFast) {
                    // 每行缓存各层 tile 指针/纯色, 避免逐像素 getPixelL 的查表;
                    // 与 MipPyramid::tile 的快路径同语义(正常混合/不透明度100%)
                    for (int ly = ly0; ly <= ly1; ly++) {
                        const int cy = by + (ly << level);
                        uint32_t* trow = t + (size_t)ly * TS;
                        if (cy >= ch) {
                            for (int lx = lx0; lx <= lx1; lx++) trow[lx] = 0;
                            continue;
                        }
                        const int tyi = cy >> Canvas::TILE_SHIFT;
                        const int lyv = cy & (Canvas::TILE - 1);
                        int lastTx = -1;
                        for (int lx = lx0; lx <= lx1; lx++) {
                            const int cx = bx + (lx << level);
                            if (cx >= cw) { trow[lx] = 0; continue; }
                            const int xt = cx >> Canvas::TILE_SHIFT;
                            if (xt != lastTx) {
                                const int ti = tyi * ctx + xt;
                                for (int i = 0; i < nv; i++) {
                                    const int l = vis[(size_t)i];
                                    const bool s = canvas_->isSolidTile(l, ti);
                                    solids[(size_t)i] = s ? 1 : 0;
                                    scols[(size_t)i] = s ? canvas_->solidTileColor(l, ti) : 0;
                                    ptrs[(size_t)i] = s ? nullptr : canvas_->tile(l, ti);
                                }
                                lastTx = xt;
                            }
                            const int off = (lyv << Canvas::TILE_SHIFT) + (cx & (Canvas::TILE - 1));
                            uint32_t acc = 0;
                            for (int i = 0; i < nv; i++) {
                                const uint32_t v = solids[(size_t)i] ? scols[(size_t)i]
                                    : (ptrs[(size_t)i] ? ptrs[(size_t)i][off] : 0);
                                if (v) raster::overPixel(acc, v);
                            }
                            trow[lx] = acc;
                        }
                    }
                } else {
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
