#include "canvas.h"
#include "../raster/raster.h"
#include <algorithm>
#include <cstring>

namespace endpix {

bool Canvas::create(int width, int height, int layers) {
    if (width <= 0 || height <= 0 || layers <= 0) return false;
    w_ = width; h_ = height;
    tx_ = (width + TILE - 1) / TILE;
    ty_ = (height + TILE - 1) / TILE;
    const size_t nt = (size_t)tx_ * ty_;
    tiles_.clear();
    tiles_.resize((size_t)layers);
    for (auto& v : tiles_) v.resize(nt);
    solidFlag_.assign((size_t)layers, std::vector<uint8_t>(nt, 0));
    solidCol_.assign((size_t)layers, std::vector<uint32_t>(nt, 0));
    vis_.assign((size_t)layers, 1);
    lock_.assign((size_t)layers, 0);
    names_.resize((size_t)layers);
    for (int i = 0; i < layers; i++) names_[(size_t)i] = "图层 " + std::to_string(i + 1);
    opacity_.assign((size_t)layers, 1.f);
    lmode_.assign((size_t)layers, 0);
    blend_.assign((size_t)layers, 0);
    tlW_.assign((size_t)layers, 16); tlH_.assign((size_t)layers, 16);
    tlOX_.assign((size_t)layers, 0); tlOY_.assign((size_t)layers, 0);
    tlLock_.assign((size_t)layers, 0);
    tlGrid_.assign((size_t)layers, 0); tlIndep_.assign((size_t)layers, 0);
    tlGridCol_.assign((size_t)layers, 0x66FFFFFFu);
    dirtyFlags_.assign(nt, 0);
    dirtyList_.clear();
    return true;
}

void Canvas::destroy() {
    tiles_.clear();
    solidFlag_.clear();
    solidCol_.clear();
    names_.clear();
    opacity_.clear();
    lmode_.clear(); blend_.clear(); tlW_.clear(); tlH_.clear(); tlOX_.clear(); tlOY_.clear();
    tlLock_.clear(); tlGrid_.clear(); tlIndep_.clear(); tlGridCol_.clear();
    dirtyFlags_.clear();
    dirtyList_.clear();
    w_ = h_ = tx_ = ty_ = 0;
}

uint32_t* Canvas::tileForWrite(int layer, int ti) {
    if (recActive_ && !fillRecord_) snapshotTile(layer, ti);
    if (isSolidTile(layer, ti)) materializeTile(layer, ti);
    auto& t = tiles_[layer][ti];
    if (!t) {
        t = std::make_unique<uint32_t[]>(TILE_PIX);
        memset(t.get(), 0, TILE_PIX * 4);
    }
    return t.get();
}

const uint32_t* Canvas::tile(int layer, int ti) const {
    return tiles_[layer][ti].get();
}

void Canvas::setSolidTile(int layer, int ti, uint32_t color) {
    if (recActive_) snapshotTile(layer, ti);
    tiles_[(size_t)layer][ti].reset();
    solidFlag_[(size_t)layer][ti] = 1;
    solidCol_[(size_t)layer][ti] = color;
    markDirtyTile(ti);
}

void Canvas::materializeTile(int layer, int ti) {
    if (!isSolidTile(layer, ti)) return;
    auto& t = tiles_[(size_t)layer][ti];
    t = std::make_unique<uint32_t[]>(TILE_PIX);
    const uint32_t c = solidCol_[(size_t)layer][ti];
    for (size_t i = 0; i < TILE_PIX; i++) t[i] = c;
    solidFlag_[(size_t)layer][ti] = 0;
}

void Canvas::setPixel(int x, int y, uint32_t argb) {
    if ((unsigned)x >= (unsigned)w_ || (unsigned)y >= (unsigned)h_) return;
    const int tx = x >> TILE_SHIFT, ty = y >> TILE_SHIFT;
    const int ti = ty * tx_ + tx;
    uint32_t* t = tileForWrite(active_, ti);
    t[((size_t)(y & (TILE - 1)) << TILE_SHIFT) + (x & (TILE - 1))] = argb;
    markDirtyTile(ti);
}

uint32_t Canvas::getPixel(int x, int y) const {
    if ((unsigned)x >= (unsigned)w_ || (unsigned)y >= (unsigned)h_) return 0;
    const int ti = (y >> TILE_SHIFT) * tx_ + (x >> TILE_SHIFT);
    const uint32_t* t = tile(active_, ti);
    if (!t) return 0;
    return t[((size_t)(y & (TILE - 1)) << TILE_SHIFT) + (x & (TILE - 1))];
}

uint32_t Canvas::getPixelL(int layer, int x, int y) const {
    if ((unsigned)x >= (unsigned)w_ || (unsigned)y >= (unsigned)h_) return 0;
    const int ti = (y >> TILE_SHIFT) * tx_ + (x >> TILE_SHIFT);
    if (solidFlag_[(size_t)layer][ti]) return solidCol_[(size_t)layer][ti];
    const uint32_t* t = tile(layer, ti);
    if (!t) return 0;
    return t[((size_t)(y & (TILE - 1)) << TILE_SHIFT) + (x & (TILE - 1))];
}

void Canvas::setPixelL(int layer, int x, int y, uint32_t argb) {
    if ((unsigned)x >= (unsigned)w_ || (unsigned)y >= (unsigned)h_) return;
    const int ti = (y >> TILE_SHIFT) * tx_ + (x >> TILE_SHIFT);
    uint32_t* t = tileForWrite(layer, ti);
    t[((size_t)(y & (TILE - 1)) << TILE_SHIFT) + (x & (TILE - 1))] = argb;
}

bool Canvas::resizeTo(int newW, int newH, int ax, int ay) {
    if (newW < 4 || newH < 4 || newW > 32767 || newH > 32767) return false;
    if (newW == w_ && newH == h_) return true;
    const int ow = w_, oh = h_;
    const int layers = (int)tiles_.size();
    if (layers <= 0) return false;
    ax = std::max(0, std::min(ax, 2));
    ay = std::max(0, std::min(ay, 2));
    const int dx = (newW - ow) * ax / 2;
    const int dy = (newH - oh) * ay / 2;
    Canvas tmp;
    if (!tmp.create(newW, newH, layers)) return false;
    const int otx = tx_, oty = ty_;
    const int ntx = tmp.tx_;
    for (int l = 0; l < layers; l++) {
        for (int oy = 0; oy < oty; oy++) {
            for (int ox = 0; ox < otx; ox++) {
                const int oi = oy * otx + ox;
                const bool solid = solidFlag_[(size_t)l][(size_t)oi] != 0;
                const uint32_t col = solidCol_[(size_t)l][(size_t)oi];
                const uint32_t* src = tiles_[(size_t)l][(size_t)oi].get();
                if (!solid && !src) continue;
                if (solid && col == 0) continue;
                const int bx = ox * TILE + dx, by = oy * TILE + dy;
                const int sx0 = std::max(0, -bx), sx1 = std::min(TILE, newW - bx);
                if (sx1 <= sx0) continue;
                const int sy0 = std::max(0, -by), sy1 = std::min(TILE, newH - by);
                if (sy1 <= sy0) continue;
                const int dtx0 = (bx + sx0) >> TILE_SHIFT;
                const int dtx1 = (bx + sx1 - 1) >> TILE_SHIFT;
                const int dty0 = (by + sy0) >> TILE_SHIFT;
                const int dty1 = (by + sy1 - 1) >> TILE_SHIFT;
                for (int dty = dty0; dty <= dty1; dty++) {
                    for (int dtx = dtx0; dtx <= dtx1; dtx++) {
                        if (dtx < 0 || dty < 0 || dtx >= ntx || dty >= tmp.ty_) continue;
                        const int tbx = dtx << TILE_SHIFT, tby = dty << TILE_SHIFT;
                        const int px0 = std::max(bx + sx0, tbx);
                        const int px1 = std::min(bx + sx1 - 1, tbx + TILE - 1);
                        const int py0 = std::max(by + sy0, tby);
                        const int py1 = std::min(by + sy1 - 1, tby + TILE - 1);
                        if (px1 < px0 || py1 < py0) continue;
                        const int dti = dty * ntx + dtx;
                        if (solid) {
                            const bool full = px0 == tbx && px1 == tbx + TILE - 1 &&
                                              py0 == tby && py1 == tby + TILE - 1;
                            if (full) {
                                tmp.setSolidTile(l, dti, col);
                            } else {
                                uint32_t* d = tmp.tileForWrite(l, dti);
                                for (int y = py0; y <= py1; y++) {
                                    uint32_t* row = d + ((size_t)(y & (TILE - 1)) << TILE_SHIFT);
                                    for (int x = px0; x <= px1; x++)
                                        row[x & (TILE - 1)] = col;
                                }
                            }
                        } else {
                            uint32_t* d = tmp.tileForWrite(l, dti);
                            for (int y = py0; y <= py1; y++) {
                                const int sy = y - by;
                                const uint32_t* srow = src + ((size_t)(sy & (TILE - 1)) << TILE_SHIFT);
                                uint32_t* drow = d + ((size_t)(y & (TILE - 1)) << TILE_SHIFT);
                                for (int x = px0; x <= px1; x++) {
                                    const int sx = x - bx;
                                    const uint32_t v = srow[sx & (TILE - 1)];
                                    if (v) drow[x & (TILE - 1)] = v;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    w_ = newW; h_ = newH; tx_ = tmp.tx_; ty_ = tmp.ty_;
    tiles_ = std::move(tmp.tiles_);
    solidFlag_ = std::move(tmp.solidFlag_);
    solidCol_ = std::move(tmp.solidCol_);
    dirtyFlags_.assign((size_t)tx_ * ty_, 0);
    dirtyList_.clear();
    return true;
}

bool Canvas::crop(int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) return false;
    x = std::max(0, x); y = std::max(0, y);
    w = std::min(w, w_ - x); h = std::min(h, h_ - y);
    if (w <= 0 || h <= 0) return false;
    const int ntx = (w + TILE - 1) / TILE, nty = (h + TILE - 1) / TILE;
    std::vector<std::vector<std::unique_ptr<uint32_t[]>>> nt(tiles_.size());
    for (auto& v : nt) v.resize((size_t)ntx * nty);
    for (size_t l = 0; l < tiles_.size(); l++) {
        for (int ty = 0; ty < nty; ty++) {
            for (int tx = 0; tx < ntx; tx++) {
                const int sx0 = x + (tx << TILE_SHIFT), sy0 = y + (ty << TILE_SHIFT);
                const int cw = std::min(TILE, w - (tx << TILE_SHIFT));
                const int chh = std::min(TILE, h - (ty << TILE_SHIFT));
                uint32_t* dst = nullptr;
                for (int yy = 0; yy < chh; yy++) {
                    const int sy = sy0 + yy;
                    int done = 0;
                    bool rowAny = false;
                    // 先探测本行是否有内容, 全空则跳过(保持稀疏)
                    while (done < cw) {
                        const int sx = sx0 + done;
                        const int oti = (sy >> TILE_SHIFT) * tx_ + (sx >> TILE_SHIFT);
                        const uint32_t* st = tile((int)l, oti);
                        const int run = std::min(cw - done, TILE - (sx & (TILE - 1)));
                        if (st) {
                            const int so = ((sy & (TILE - 1)) << TILE_SHIFT) + (sx & (TILE - 1));
                            for (int i = 0; i < run; i++) if (st[so + i]) { rowAny = true; break; }
                        }
                        done += run;
                        if (rowAny) break;
                    }
                    if (!rowAny) continue;
                    if (!dst) {
                        dst = new uint32_t[TILE_PIX];
                        memset(dst, 0, TILE_PIX * 4);
                        nt[l][(size_t)ty * ntx + tx].reset(dst);
                    }
                    done = 0;
                    while (done < cw) {
                        const int sx = sx0 + done;
                        const int oti = (sy >> TILE_SHIFT) * tx_ + (sx >> TILE_SHIFT);
                        const uint32_t* st = tile((int)l, oti);
                        const int run = std::min(cw - done, TILE - (sx & (TILE - 1)));
                        uint32_t* d = dst + ((size_t)yy << TILE_SHIFT) + done;
                        if (st) {
                            const int so = ((sy & (TILE - 1)) << TILE_SHIFT) + (sx & (TILE - 1));
                            memcpy(d, st + so, (size_t)run * 4);
                        } else {
                            memset(d, 0, (size_t)run * 4);
                        }
                        done += run;
                    }
                }
            }
        }
    }
    // 释放旧层
    tiles_.clear();
    tiles_ = std::move(nt);
    w_ = w; h_ = h; tx_ = ntx; ty_ = nty;
    dirtyFlags_.assign((size_t)tx_ * ty_, 0);
    dirtyList_.clear();
    markDirtyRect(0, 0, w_ - 1, h_ - 1);
    return true;
}

int Canvas::moveRectBlendStep(int layer, int sx, int sy, int w, int h, int dx, int dy,
                              int rowFrom, size_t budgetPx, std::vector<uint32_t>& row,
                              const uint64_t* dstMask, bool alphaOverlay) {
    if (dx == 0 && dy == 0) return h;
    const int T = TILE;
    const bool down = dy > 0;
    row.resize((size_t)w);
    const int tx0 = sx >> TILE_SHIFT, tx1 = (sx + w - 1) >> TILE_SHIFT;
    size_t used = 0;
    int k = rowFrom;
    for (; k < h; k++) {
        const int y = down ? (h - 1 - k) : k;      // dy>0 必须从下往上搬, 避免重叠区被覆盖
        const int syy = sy + y;
        if ((unsigned)syy >= (unsigned)h_) continue;
        // 整行没有任何源 tile: 源不用清、目标也不用写, 直接跳过(大选区里绝大多数是这种行)
        bool anyTile = false;
        const int trow = (syy >> TILE_SHIFT) * tx_;
        for (int tx = tx0; tx <= tx1; tx++) {
            const int ti = trow + tx;
            if (tile(layer, ti) || isSolidTile(layer, ti)) { anyTile = true; break; }
        }
        if (!anyTile) continue;
        if (used > 0 && used + (size_t)w > budgetPx) break;   // 预算用完, 下一帧继续
        used += (size_t)w;
        // 撤销: 这一行要清的源块 + 要写的目标块都先快照(直接写 tile 的路径不走 tileForWrite)
        if (recActive_) {
            const int txA = std::max(0, tx0), txB = std::min(tx_ - 1, tx1);
            for (int tx = txA; tx <= txB; tx++) snapshotTile(layer, trow + tx);
            const int dyy0 = syy + dy;
            const int dsx0 = sx + dx, dsx1 = sx + dx + w - 1;
            if ((unsigned)dyy0 < (unsigned)h_ && dsx1 >= 0 && dsx0 < w_) {
                const int dtxA = std::max(0, dsx0 >> TILE_SHIFT);
                const int dtxB = std::min(tx_ - 1, dsx1 >> TILE_SHIFT);
                const int dtrow = (dyy0 >> TILE_SHIFT) * tx_;
                for (int tx = dtxA; tx <= dtxB; tx++) snapshotTile(layer, dtrow + tx);
            }
        }
        // 源行的整块同色先物化, 后面逐行读/清才一致(选区面积内的块, 量可控)
        for (int tx = tx0; tx <= tx1; tx++) {
            const int ti = trow + tx;
            if (isSolidTile(layer, ti)) materializeTile(layer, ti);
        }

        // 1) 整行读出(搬移前的原值)
        int done = 0;
        while (done < w) {
            const int sxx = sx + done;
            const int run = std::min(w - done, T - ((sxx % T) + T) % T);
            if ((unsigned)sxx < (unsigned)w_) {
                const uint32_t* t = tile(layer, (syy >> TILE_SHIFT) * tx_ + (sxx >> TILE_SHIFT));
                if (t) memcpy(&row[(size_t)done], t + (((size_t)(syy & (T - 1)) << TILE_SHIFT) + (sxx & (T - 1))), (size_t)run * 4);
                else memset(&row[(size_t)done], 0, (size_t)run * 4);
            } else {
                memset(&row[(size_t)done], 0, (size_t)run * 4);
            }
            done += run;
        }

        // 2) 清源(只清已分配的 tile, 不新建空 tile)
        done = 0;
        while (done < w) {
            const int sxx = sx + done;
            const int run = std::min(w - done, T - ((sxx % T) + T) % T);
            if ((unsigned)sxx < (unsigned)w_) {
                uint32_t* t = const_cast<uint32_t*>(tile(layer, (syy >> TILE_SHIFT) * tx_ + (sxx >> TILE_SHIFT)));
                if (t) memset(t + (((size_t)(syy & (T - 1)) << TILE_SHIFT) + (sxx & (T - 1))), 0, (size_t)run * 4);
            }
            done += run;
        }

        // 3) over 到目标: 透明像素跳过, 目标位置原有内容保留
        const int dyy = syy + dy;
        if ((unsigned)dyy >= (unsigned)h_) continue;
        done = 0;
        while (done < w) {
            const int dxx = sx + dx + done;
            const int runMax = std::min(w - done, T - ((dxx % T) + T) % T);
            if ((unsigned)dxx >= (unsigned)w_) { done += runMax; continue; }
            const int run = std::min(runMax, w_ - dxx);
            if (run <= 0) { done += runMax; continue; }
            const int dti = (dyy >> TILE_SHIFT) * tx_ + (dxx >> TILE_SHIFT);
            const int doff = ((size_t)(dyy & (T - 1)) << TILE_SHIFT) + (dxx & (T - 1));
            const uint32_t* s = &row[(size_t)done];   // 行内偏移(done 相对源矩形左端)
            // 全透明 run 对目标没有任何影响: 直接跳过, 不能为它分配目标块
            // (大选区移动时整行宽度会扫过去, 否则几百 MB 空白 tile 被凭空分配)
            int nz0 = -1, nz1 = -1;
            for (int i = 0; i < run; i++) if (s[i]) { if (nz0 < 0) nz0 = i; nz1 = i; }
            if (nz0 < 0) { done += runMax; continue; }
            if (dstMask) {
                // 本层蒙版: 只写掩码位=1 的落点(源已清, 被挡的内容丢弃)
                uint32_t* t = nullptr;
                int i = nz0;
                while (i <= nz1) {
                    const size_t bi = (size_t)y * (size_t)w + (size_t)(done + i);
                    if (!((dstMask[bi >> 6] >> (bi & 63)) & 1ULL)) { i++; continue; }
                    int j = i;
                    while (j <= nz1) {
                        const size_t bj = (size_t)y * (size_t)w + (size_t)(done + j);
                        if (!((dstMask[bj >> 6] >> (bj & 63)) & 1ULL)) break;
                        j++;
                    }
                    bool any = false;
                    for (int k2 = i; k2 < j; k2++) if (s[k2]) { any = true; break; }
                    if (any) {
                        if (!t) t = const_cast<uint32_t*>(tile(layer, dti));
                        if (!t) t = tileForWrite(layer, dti);   // 新 tile 已清零
                        for (int k2 = i; k2 < j; k2++) if (s[k2]) {
                            if (alphaOverlay) raster::overPixel(t[doff + k2], s[k2]);
                            else t[doff + k2] = s[k2];   // 直接覆盖(保留源 alpha)
                        }
                    }
                    i = j;
                }
                done += runMax;
                continue;
            }
            uint32_t* t = const_cast<uint32_t*>(tile(layer, dti));
            if (t) {
                if (alphaOverlay)
                    raster::blitOverRow(t + doff + nz0, s + nz0, nz1 - nz0 + 1);
                else
                    raster::blitReplaceRow(t + doff + nz0, s + nz0, nz1 - nz0 + 1);
            } else {
                t = tileForWrite(layer, dti);      // 新 tile 已清零
                for (int i = nz0; i <= nz1; i++) if (s[i]) t[doff + i] = s[i];
            }
            done += runMax;
        }
    }
    return k;
}

void Canvas::clearOutside(int x0, int y0, int x1, int y1) {
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(w_ - 1, x1); y1 = std::min(h_ - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int T = TILE;
    const int tx0 = x0 >> TILE_SHIFT, tx1 = x1 >> TILE_SHIFT;
    const int ty0 = y0 >> TILE_SHIFT, ty1 = y1 >> TILE_SHIFT;
    for (size_t l = 0; l < tiles_.size(); l++) {
        for (int ty = 0; ty < ty_; ty++) {
            for (int tx = 0; tx < tx_; tx++) {
                const int ti = ty * tx_ + tx;
                uint32_t* t = tiles_[l][ti].get();
                if (!t) continue;
                const bool fullyInside = (tx >= tx0 && tx <= tx1 && ty >= ty0 && ty <= ty1);
                if (fullyInside) continue;
                if (tx < tx0 || tx > tx1 || ty < ty0 || ty > ty1) {
                    // 整块在选区外
                    tiles_[l][ti].reset();
                    continue;
                }
                // 部分相交: 逐行裁剪
                const int py0 = ty * T;
                for (int yy = 0; yy < T; yy++) {
                    const int gy = py0 + yy;
                    const bool rowIn = (gy >= y0 && gy <= y1);
                    uint32_t* row = t + ((size_t)yy << TILE_SHIFT);
                    if (!rowIn) { memset(row, 0, TILE_PIX * 4 / T); continue; }
                    const int px0 = tx * T;
                    const int lx0 = std::max(0, x0 - px0), lx1 = std::min(T - 1, x1 - px0);
                    if (lx0 > 0) memset(row, 0, (size_t)lx0 * 4);
                    if (lx1 < T - 1) memset(row + lx1 + 1, 0, (size_t)(T - 1 - lx1) * 4);
                }
            }
        }
    }
    markDirtyRect(0, 0, w_ - 1, h_ - 1);
}

void Canvas::setCutRect(int x0, int y0, int x1, int y1, int layer) {
    cx0_ = std::max(0, x0); cy0_ = std::max(0, y0);
    cx1_ = std::min(w_ - 1, x1); cy1_ = std::min(h_ - 1, y1);
    cutLayer_ = layer;
    cutActive_ = (cx0_ <= cx1_ && cy0_ <= cy1_);
}

void Canvas::clearRect(int layer, int x0, int y0, int x1, int y1) {
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(w_ - 1, x1); y1 = std::min(h_ - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int T = TILE;
    for (int ty = y0 / T; ty <= y1 / T; ty++) {
        for (int tx = x0 / T; tx <= x1 / T; tx++) {
            const int ti = ty * tx_ + tx;
            uint32_t* t = tileForWrite(layer, ti);
            if (!t) continue;
            const int px0 = tx * T, py0 = ty * T;
            const int lx0 = std::max(0, x0 - px0), lx1 = std::min(T - 1, x1 - px0);
            const int ly0 = std::max(0, y0 - py0), ly1 = std::min(T - 1, y1 - py0);
            for (int yy = ly0; yy <= ly1; yy++)
                memset(t + ((size_t)yy << TILE_SHIFT) + lx0, 0, (size_t)(lx1 - lx0 + 1) * 4);
            markDirtyTile(ti);
        }
    }
}

void Canvas::moveRect(int layer, int sx, int sy, int w, int h, int dx, int dy) {
    if (dx == 0 && dy == 0) return;
    const int T = TILE;
    const bool down = dy > 0;
    std::vector<uint32_t> row;
    for (int i = 0; i < h; i++) {
        const int y = down ? (h - 1 - i) : i;
        const int syy = sy + y, dyy = syy + dy;
        if ((unsigned)syy >= (unsigned)h_ || (unsigned)dyy >= (unsigned)h_) continue;
        const uint32_t* st = tile(layer, (syy >> TILE_SHIFT) * tx_ + (sx >> TILE_SHIFT));
        // 先整行取出(可能横跨多个 tile)
        row.resize((size_t)w);
        int done = 0;
        while (done < w) {
            const int sxx = sx + done;
            const uint32_t* t = tile(layer, (syy >> TILE_SHIFT) * tx_ + (sxx >> TILE_SHIFT));
            const int run = std::min(w - done, T - (sxx & (T - 1)));
            if (t) memcpy(&row[(size_t)done], t + (((size_t)(syy & (T - 1)) << TILE_SHIFT) + (sxx & (T - 1))), (size_t)run * 4);
            else memset(&row[(size_t)done], 0, (size_t)run * 4);
            done += run;
        }
        (void)st;
        // 再整行写回
        done = 0;
        while (done < w) {
            const int dxx = sx + dx + done;
            if ((unsigned)dxx >= (unsigned)w_) { done += std::min(w - done, T - (dxx & (T - 1))); continue; }
            uint32_t* t = tileForWrite(layer, (dyy >> TILE_SHIFT) * tx_ + (dxx >> TILE_SHIFT));
            const int run = std::min(w - done, T - (dxx & (T - 1)));
            memcpy(t + (((size_t)(dyy & (T - 1)) << TILE_SHIFT) + (dxx & (T - 1))), &row[(size_t)done], (size_t)run * 4);
            done += run;
        }
    }
    markDirtyRect(std::min(sx, sx + dx), std::min(sy, sy + dy),
                  std::max(sx + w - 1, sx + dx + w - 1), std::max(sy + h - 1, sy + dy + h - 1));
}

int Canvas::addLayer() {
    const size_t nt = (size_t)tx_ * ty_;
    tiles_.emplace_back(nt);
    solidFlag_.emplace_back(nt, 0);
    solidCol_.emplace_back(nt, 0);
    vis_.push_back(1);
    lock_.push_back(0);
    names_.push_back("图层 " + std::to_string(tiles_.size()));
    opacity_.push_back(1.f);
    lmode_.push_back(0);
    blend_.push_back(0);
    tlW_.push_back(16); tlH_.push_back(16); tlOX_.push_back(0); tlOY_.push_back(0);
    tlLock_.push_back(0); tlGrid_.push_back(0); tlIndep_.push_back(0);
    tlGridCol_.push_back(0x66FFFFFFu);
    return (int)tiles_.size();
}

bool Canvas::removeLayer(int i) {
    if (tiles_.size() <= 1 || i < 0 || i >= (int)tiles_.size()) return false;
    for (int ti = 0; ti < tx_ * ty_; ti++)
        if (tiles_[(size_t)i][ti] || solidFlag_[(size_t)i][ti]) markDirtyTile(ti);
    tiles_.erase(tiles_.begin() + i);
    solidFlag_.erase(solidFlag_.begin() + i);
    solidCol_.erase(solidCol_.begin() + i);
    if (i < (int)vis_.size()) vis_.erase(vis_.begin() + i);
    if (i < (int)lock_.size()) lock_.erase(lock_.begin() + i);
    if (i < (int)names_.size()) names_.erase(names_.begin() + i);
    if (i < (int)opacity_.size()) opacity_.erase(opacity_.begin() + i);
    if (i < (int)lmode_.size()) lmode_.erase(lmode_.begin() + i);
    if (i < (int)blend_.size()) blend_.erase(blend_.begin() + i);
    if (i < (int)tlW_.size()) { tlW_.erase(tlW_.begin()+i); tlH_.erase(tlH_.begin()+i); }
    if (i < (int)tlOX_.size()) { tlOX_.erase(tlOX_.begin()+i); tlOY_.erase(tlOY_.begin()+i); }
    if (i < (int)tlLock_.size()) tlLock_.erase(tlLock_.begin()+i);
    if (i < (int)tlGrid_.size()) { tlGrid_.erase(tlGrid_.begin()+i); tlIndep_.erase(tlIndep_.begin()+i); }
    if (i < (int)tlGridCol_.size()) tlGridCol_.erase(tlGridCol_.begin()+i);
    if (active_ >= (int)tiles_.size()) active_ = (int)tiles_.size() - 1;
    if (active_ > i) active_--;
    return true;
}

void Canvas::snapshotAllTiles() {
    if (!recActive_) return;
    const int n = tx_ * ty_;
    for (int l = 0; l < (int)tiles_.size(); l++)
        for (int ti = 0; ti < n; ti++) snapshotTile(l, ti);
}

void Canvas::flipAllLayers(bool horizontal) {
    const int n = tx_ * ty_;
    for (auto& layer : tiles_) {
        std::vector<std::unique_ptr<uint32_t[]>> dst((size_t)n);
        for (int ti = 0; ti < n; ti++) {
            const uint32_t* src = layer[ti].get();
            if (!src) continue;
            const int tx = ti % tx_, ty = ti / tx_;
            for (int yy = 0; yy < TILE; yy++) {
                const int sy = ty * TILE + yy;
                if (sy >= h_) break;
                const uint32_t* row = src + (size_t)yy * TILE;
                for (int xx = 0; xx < TILE; xx++) {
                    const uint32_t v = row[xx];
                    if (!v) continue;
                    const int sx = tx * TILE + xx;
                    if (sx >= w_) continue;
                    const int dx = horizontal ? (w_ - 1 - sx) : sx;
                    const int dy = horizontal ? sy : (h_ - 1 - sy);
                    const int dti = (dy >> TILE_SHIFT) * tx_ + (dx >> TILE_SHIFT);
                    if (!dst[(size_t)dti]) {
                        dst[(size_t)dti] = std::make_unique<uint32_t[]>(TILE_PIX);
                        memset(dst[(size_t)dti].get(), 0, TILE_PIX * 4);
                    }
                    dst[(size_t)dti][((size_t)(dy & (TILE - 1)) << TILE_SHIFT) + (dx & (TILE - 1))] = v;
                }
            }
        }
        layer.swap(dst);
    }
    markDirtyRect(0, 0, w_ - 1, h_ - 1);
}

void Canvas::grayscaleAllLayers() {
    for (auto& layer : tiles_) {
        for (auto& t : layer) {
            if (!t) continue;
            uint32_t* p = t.get();
            for (size_t i = 0; i < TILE_PIX; i++) {
                const uint32_t v = p[i];
                const uint32_t a = v >> 24;
                if (!a) continue;
                const int r = v & 255, g = (v >> 8) & 255, b = (v >> 16) & 255;
                const int y = (r * 77 + g * 150 + b * 29) >> 8;
                p[i] = (a << 24) | (y << 16) | (y << 8) | y;
            }
        }
    }
    markDirtyRect(0, 0, w_ - 1, h_ - 1);
}

void Canvas::levelsHistogram(int layer, int channel, int out[256]) const {
    for (int i = 0; i < 256; i++) out[i] = 0;
    if (layer < 0 || layer >= (int)tiles_.size()) return;
    const int n = tx_ * ty_;
    auto binOf = [&](uint32_t v) -> int {
        const uint32_t a = v >> 24;
        if (!a) return -1;
        auto un = [&](uint32_t c) { return std::min(255u, (c * 255u + a / 2) / a); };
        const int r = (int)un(v & 255), g = (int)un((v >> 8) & 255), b = (int)un((v >> 16) & 255);
        if (channel == 1) return r;
        if (channel == 2) return g;
        if (channel == 3) return b;
        return (r * 77 + g * 150 + b * 29) >> 8;
    };
    const int cw = w_, chh = h_;
    for (int ti = 0; ti < n; ti++) {
        const int tx = ti % tx_;
        const int ty = ti / tx_;
        // 只统计画布范围内的像素: tile 右侧/底部 padding 不属于画布, 否则 bin 计数会虚高
        const int tw = std::min(TILE, cw - (tx << TILE_SHIFT));
        const int th = std::min(TILE, chh - (ty << TILE_SHIFT));
        if (tw <= 0 || th <= 0) continue;
        if (solidFlag_[(size_t)layer].size() > (size_t)ti && solidFlag_[(size_t)layer][ti]) {
            const int b = binOf(solidCol_[(size_t)layer][ti]);
            if (b >= 0) out[b] += tw * th;
        } else {
            const uint32_t* t = tiles_[(size_t)layer][ti].get();
            if (!t) continue;
            for (int yy = 0; yy < th; yy++) {
                const uint32_t* row = t + ((size_t)yy << TILE_SHIFT);
                for (int xx = 0; xx < tw; xx++) {
                    const int b = binOf(row[xx]);
                    if (b >= 0) out[b]++;
                }
            }
        }
    }
}

void Canvas::applyLevelsLut(int layer, int channel, const uint8_t lut[256]) {
    if (layer < 0 || layer >= (int)tiles_.size()) return;
    const int n = tx_ * ty_;
    auto px = [&](uint32_t v) -> uint32_t {
        const uint32_t a = v >> 24;
        if (!a) return v;
        auto un = [&](uint32_t c) { return std::min(255u, (c * 255u + a / 2) / a); };
        uint32_t r = un(v & 255), g = un((v >> 8) & 255), b = un((v >> 16) & 255);
        if (channel == 1) r = lut[r];
        else if (channel == 2) g = lut[g];
        else if (channel == 3) b = lut[b];
        else { r = lut[r]; g = lut[g]; b = lut[b]; }
        return (a << 24) | ((b * a / 255) << 16) | ((g * a / 255) << 8) | (r * a / 255);
    };
    for (int ti = 0; ti < n; ti++) {
        if (solidFlag_[(size_t)layer].size() > (size_t)ti && solidFlag_[(size_t)layer][ti]) {
            const uint32_t v = solidCol_[(size_t)layer][ti];
            if (!(v >> 24)) continue;
            setSolidTile(layer, ti, px(v));
        } else if (tiles_[(size_t)layer][ti]) {
            uint32_t* t = tileForWrite(layer, ti);
            for (size_t i = 0; i < TILE_PIX; i++) {
                if (t[i] >> 24) t[i] = px(t[i]);
            }
            markDirtyTile(ti);
        }
    }
    markDirtyRect(0, 0, w_ - 1, h_ - 1);
}

bool Canvas::inkBounds(int* ox0, int* oy0, int* ox1, int* oy1) const {
    int x0 = INT_MAX, y0 = INT_MAX, x1 = -1, y1 = -1;
    const int n = tx_ * ty_;
    for (size_t l = 0; l < tiles_.size(); l++) {
        for (int ti = 0; ti < n; ti++) {
            if (solidFlag_[l][ti]) {
                if (solidCol_[l][ti] == 0) continue;
                const int tbx = (ti % tx_) * TILE, tby = (ti / tx_) * TILE;
                const int bx1 = std::min(w_ - 1, tbx + TILE - 1);
                const int by1 = std::min(h_ - 1, tby + TILE - 1);
                if (tbx < x0) x0 = tbx;
                if (tby < y0) y0 = tby;
                if (bx1 > x1) x1 = bx1;
                if (by1 > y1) y1 = by1;
                continue;
            }
            const uint32_t* t = tiles_[l][ti].get();
            if (!t) continue;
            const int tbx = (ti % tx_) * TILE, tby = (ti / tx_) * TILE;
            for (int yy = 0; yy < TILE; yy++) {
                const uint32_t* row = t + (size_t)yy * TILE;
                int first = -1, last = -1;
                for (int xx = 0; xx < TILE; xx++) {
                    if (row[xx]) { if (first < 0) first = xx; last = xx; }
                }
                if (first < 0) continue;
                const int sy = tby + yy;
                if (sy >= h_) continue;
                if (sy < y0) y0 = sy;
                if (sy > y1) y1 = sy;
                const int sx0 = tbx + first, sx1 = tbx + last;
                if (sx0 < x0) x0 = sx0;
                if (sx1 > x1) x1 = sx1;
            }
        }
    }
    if (x1 < 0 || y1 < 0) return false;
    if (x1 >= w_) x1 = w_ - 1;
    if (y1 >= h_) y1 = h_ - 1;
    *ox0 = x0; *oy0 = y0; *ox1 = x1; *oy1 = y1;
    return true;
}

void Canvas::beginRecord() {
    recActive_ = true;
    rec_.clear();
    if (recFlags_.size() != (size_t)tx_ * ty_ * tiles_.size())
        recFlags_.assign((size_t)tx_ * ty_ * tiles_.size(), 0);
    recKeys_.clear();
}

void Canvas::snapshotTile(int layer, int ti) {
    const size_t key = (size_t)layer * tx_ * ty_ + ti;
    if (key >= recFlags_.size() || recFlags_[key]) return;
    recFlags_[key] = 1;
    recKeys_.push_back((int)key);
    TileSnap s; s.layer = layer; s.ti = ti;
    if (solidFlag_[(size_t)layer][ti]) {
        s.kind = 2;
        s.color = solidCol_[(size_t)layer][ti];
    } else {
        const auto& t = tiles_[(size_t)layer][ti];
        if (t) { s.kind = 1; s.px.assign(t.get(), t.get() + TILE_PIX); }
    }
    rec_.push_back(std::move(s));
}

Canvas::UndoRecord Canvas::endRecord() {
    recActive_ = false;
    for (int k : recKeys_) recFlags_[(size_t)k] = 0;
    recKeys_.clear();
    return std::move(rec_);
}

void Canvas::paintSpans(int layer, const std::vector<Span>& spans, uint32_t color) {
    for (const Span& sp : spans) {
        int xx = sp.x0;
        while (xx <= sp.x1) {
            const int tx = xx >> TILE_SHIFT;
            const int te = std::min(sp.x1, std::min(w_ - 1, ((tx + 1) << TILE_SHIFT) - 1));
            const int ti = (sp.yy >> TILE_SHIFT) * tx_ + tx;
            uint32_t* t = tileForWrite(layer, ti);   // 填充写过的块一定是已分配的平坦块
            uint32_t* p = t + ((size_t)(sp.yy & (TILE - 1)) << TILE_SHIFT) + (xx & (TILE - 1));
            const int n = te - xx + 1;
            for (int i = 0; i < n; i++) p[i] = color;
            markDirtyTile(ti);
            xx = te + 1;
        }
    }
}

Canvas::UndoRecord Canvas::applyRecord(const UndoRecord& rec) {
    UndoRecord back;
    back.reserve(rec.size());
    for (const auto& s : rec) {
        if (s.layer < 0 || s.layer >= (int)tiles_.size()) continue;
        auto& t = tiles_[(size_t)s.layer][s.ti];
        TileSnap cur; cur.layer = s.layer; cur.ti = s.ti;
        if (solidFlag_[(size_t)s.layer][s.ti]) {
            cur.kind = 2;
            cur.color = solidCol_[(size_t)s.layer][s.ti];
        } else if (t) {
            cur.kind = 1;
            cur.px.assign(t.get(), t.get() + TILE_PIX);
        }
        back.push_back(std::move(cur));
        if (s.kind == 2) {
            t.reset();
            solidFlag_[(size_t)s.layer][s.ti] = 1;
            solidCol_[(size_t)s.layer][s.ti] = s.color;
        } else if (s.kind == 1) {
            solidFlag_[(size_t)s.layer][s.ti] = 0;
            if (!t) t = std::make_unique<uint32_t[]>(TILE_PIX);
            memcpy(t.get(), s.px.data(), TILE_PIX * sizeof(uint32_t));
        } else {
            solidFlag_[(size_t)s.layer][s.ti] = 0;
            t.reset();
        }
        markDirtyTile(s.ti);
    }
    return back;
}

void Canvas::moveLayer(int from, int to) {
    if (from == to || from < 0 || to < 0) return;
    if (from >= (int)tiles_.size() || to >= (int)tiles_.size()) return;
    auto t = std::move(tiles_[(size_t)from]);
    tiles_.erase(tiles_.begin() + from);
    tiles_.insert(tiles_.begin() + to, std::move(t));
    auto f = std::move(solidFlag_[(size_t)from]);
    solidFlag_.erase(solidFlag_.begin() + from);
    solidFlag_.insert(solidFlag_.begin() + to, std::move(f));
    auto c = std::move(solidCol_[(size_t)from]);
    solidCol_.erase(solidCol_.begin() + from);
    solidCol_.insert(solidCol_.begin() + to, std::move(c));
    const uint8_t vv = vis_[(size_t)from];
    vis_.erase(vis_.begin() + from);
    vis_.insert(vis_.begin() + to, vv);
    const uint8_t lk = lock_[(size_t)from];
    lock_.erase(lock_.begin() + from);
    lock_.insert(lock_.begin() + to, lk);
    if (from < (int)names_.size() && to < (int)names_.size()) {
        auto nm = std::move(names_[(size_t)from]);
        names_.erase(names_.begin() + from);
        names_.insert(names_.begin() + to, std::move(nm));
        const float op = opacity_[(size_t)from];
        opacity_.erase(opacity_.begin() + from);
        opacity_.insert(opacity_.begin() + to, op);
    }
    auto moveVec = [&](auto& v) {
        if (from < (int)v.size() && to < (int)v.size()) {
            auto x = v[(size_t)from];
            v.erase(v.begin() + from);
            v.insert(v.begin() + to, x);
        }
    };
    moveVec(lmode_); moveVec(blend_); moveVec(tlW_); moveVec(tlH_); moveVec(tlOX_); moveVec(tlOY_);
    moveVec(tlLock_); moveVec(tlGrid_); moveVec(tlIndep_); moveVec(tlGridCol_);
    if (active_ == from) active_ = to;
    else if (from < active_ && to >= active_) active_--;
    else if (from > active_ && to <= active_) active_++;
}

void Canvas::ensureLayerProps() {
    const size_t n = tiles_.size();
    if (lmode_.size() != n) lmode_.resize(n, 0);
    if (blend_.size() != n) blend_.resize(n, 0);
    if (tlW_.size() != n) tlW_.resize(n, 16);
    if (tlH_.size() != n) tlH_.resize(n, 16);
    if (tlOX_.size() != n) tlOX_.resize(n, 0);
    if (tlOY_.size() != n) tlOY_.resize(n, 0);
    if (tlLock_.size() != n) tlLock_.resize(n, 0);
    if (tlGrid_.size() != n) tlGrid_.resize(n, 0);
    if (tlIndep_.size() != n) tlIndep_.resize(n, 0);
    if (tlGridCol_.size() != n) tlGridCol_.resize(n, 0x66FFFFFFu);
}

// 平铺绘制: 把本条撤销记录里刚改动的像素, 按格(ox,oy,w,h)复制到所有相同局部位置。
// 只复制"本笔改到的像素", 不清除层上其它内容(与旧项目 tileMode 逐像素同步一致)。
// 目标位置写入同样落在当前撤销记录里, 撤销/重做可完整还原。
void Canvas::replicateRecordedPixels(int layer, int w, int h, int ox, int oy) {
    ensureLayerProps();
    if (!recActive_ || layer < 0 || layer >= (int)tiles_.size()) return;
    if (w <= 0 || h <= 0 || w > w_ || h > h_) return;
    struct Px { int y, x; uint32_t c; };
    std::vector<Px> changed;
    for (const auto& s : rec_) {
        if (s.layer != layer) continue;
        if (s.ti < 0 || s.ti >= tx_ * ty_) continue;
        const int bx = (s.ti % tx_) << TILE_SHIFT, by = (s.ti / tx_) << TILE_SHIFT;
        const int xe = std::min(w_ - 1, bx + TILE - 1), ye = std::min(h_ - 1, by + TILE - 1);
        const bool curSolid = solidFlag_[(size_t)layer][s.ti] != 0;
        const uint32_t curCol = solidCol_[(size_t)layer][s.ti];
        const uint32_t* curTile = tiles_[(size_t)layer][s.ti].get();
        for (int y = by; y <= ye; y++) {
            const int row = (y & (TILE - 1)) << TILE_SHIFT;
            for (int x = bx; x <= xe; x++) {
                const int i = row + (x & (TILE - 1));
                const uint32_t before = s.kind == 1 ? s.px[(size_t)i]
                    : (s.kind == 2 ? s.color : 0u);
                const uint32_t now = curSolid ? curCol : (curTile ? curTile[i] : 0u);
                if (now != before) changed.push_back({y, x, now});
            }
        }
    }
    if (changed.empty()) return;
    std::sort(changed.begin(), changed.end(), [](const Px& a, const Px& b) {
        return a.y != b.y ? a.y < b.y : a.x < b.x;
    });
    // 目标写入上限: 防止大画布+大笔误操作造成秒级卡顿(超出部分不再复制, 已有内容不受影响)
    const long long kMaxWrites = 24ll * 1000 * 1000;
    long long writes = 0;
    bool capped = false;
    for (const Px& p : changed) {
        const int lx = ((p.x - ox) % w + w) % w;
        const int ly = ((p.y - oy) % h + h) % h;
        for (int gy = oy + ly; gy < h_; gy += h) {
            if (gy < 0) continue;
            const int rowTi = (gy >> TILE_SHIFT) * tx_;
            const int rowOff = (gy & (TILE - 1)) << TILE_SHIFT;
            for (int gx = ox + lx; gx < w_; gx += w) {
                if (gx < 0) continue;
                const int ti = rowTi + (gx >> TILE_SHIFT);
                const int o = rowOff + (gx & (TILE - 1));
                if (solidFlag_[(size_t)layer][ti]) {
                    if (solidCol_[(size_t)layer][ti] == p.c) continue;
                } else {
                    const uint32_t* t = tiles_[(size_t)layer][ti].get();
                    if (!t) { if (p.c == 0) continue; }
                    else if (t[o] == p.c) continue;
                }
                uint32_t* t = tileForWrite(layer, ti);
                t[o] = p.c;
                markDirtyTile(ti);
                if (++writes >= kMaxWrites) { capped = true; break; }
            }
            if (capped) break;
        }
        if (capped) break;
    }
    if (capped) markDirtyAllocated(0, 0, w_ - 1, h_ - 1);
}

void Canvas::installTile(int layer, int ti, const uint32_t* px, uint32_t solidColor, bool solid) {
    if (layer < 0 || layer >= (int)tiles_.size() || ti < 0 || ti >= tx_ * ty_) return;
    tiles_[(size_t)layer][ti].reset();
    solidFlag_[(size_t)layer][ti] = solid ? 1 : 0;
    solidCol_[(size_t)layer][ti] = solidColor;
    if (!solid && px) {
        auto t = std::make_unique<uint32_t[]>(TILE_PIX);
        memcpy(t.get(), px, TILE_PIX * sizeof(uint32_t));
        tiles_[(size_t)layer][ti] = std::move(t);
    }
    rev_++;
}

void Canvas::markDirtyTile(int ti) {
    rev_++;
    if (ti < 0 || ti >= (int)dirtyFlags_.size()) return;
    if (dirtyFlags_[ti]) return;
    dirtyFlags_[ti] = 1;
    dirtyList_.push_back(ti);
}

void Canvas::markDirtyRect(int x0, int y0, int x1, int y1) {
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(w_ - 1, x1); y1 = std::min(h_ - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int t0x = x0 >> TILE_SHIFT, t1x = x1 >> TILE_SHIFT;
    const int t0y = y0 >> TILE_SHIFT, t1y = y1 >> TILE_SHIFT;
    for (int ty = t0y; ty <= t1y; ty++)
        for (int tx = t0x; tx <= t1x; tx++)
            markDirtyTile(ty * tx_ + tx);
}

void Canvas::markDirtyAllocated(int x0, int y0, int x1, int y1) {
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(w_ - 1, x1); y1 = std::min(h_ - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int t0x = x0 >> TILE_SHIFT, t1x = x1 >> TILE_SHIFT;
    const int t0y = y0 >> TILE_SHIFT, t1y = y1 >> TILE_SHIFT;
    for (int ty = t0y; ty <= t1y; ty++) {
        for (int tx = t0x; tx <= t1x; tx++) {
            const int ti = ty * tx_ + tx;
            for (auto& layer : tiles_) if (layer[ti]) { markDirtyTile(ti); break; }
        }
    }
}

void Canvas::markDirtyOtherLayers(int x0, int y0, int x1, int y1, int exceptLayer) {
    x0 = std::max(0, x0); y0 = std::max(0, y0);
    x1 = std::min(w_ - 1, x1); y1 = std::min(h_ - 1, y1);
    if (x0 > x1 || y0 > y1) return;
    const int t0x = x0 >> TILE_SHIFT, t1x = x1 >> TILE_SHIFT;
    const int t0y = y0 >> TILE_SHIFT, t1y = y1 >> TILE_SHIFT;
    for (int ty = t0y; ty <= t1y; ty++) {
        for (int tx = t0x; tx <= t1x; tx++) {
            const int ti = ty * tx_ + tx;
            for (int l = 0; l < (int)tiles_.size(); l++) {
                if (l != exceptLayer && tiles_[l][ti]) { markDirtyTile(ti); break; }
            }
        }
    }
}

size_t Canvas::bytes() const {
    size_t n = 0;
    for (auto& layer : tiles_)
        for (auto& t : layer)
            if (t) n += TILE_PIX * 4;
    return n;
}

size_t Canvas::countInk(int layer) const {
    size_t ink = 0;
    const int n = tx_ * ty_;
    for (int ti = 0; ti < n; ti++) {
        if (solidFlag_[(size_t)layer][ti]) {
            if (solidCol_[(size_t)layer][ti]) {
                const int tx = ti % tx_, ty = ti / tx_;
                ink += (size_t)std::min(TILE, w_ - (tx << TILE_SHIFT)) *
                       (size_t)std::min(TILE, h_ - (ty << TILE_SHIFT));
            }
            continue;
        }
        const auto& t = tiles_[(size_t)layer][ti];
        if (!t) continue;
        const uint32_t* p = t.get();
        for (size_t i = 0; i < TILE_PIX; i++) if (p[i]) ink++;
    }
    return ink;
}

} // namespace endpix
