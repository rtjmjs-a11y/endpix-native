#pragma once
#include <cstdint>
#include <map>
#include <mutex>
#include <memory>
#include "canvas.h"

namespace endpix {

// 合成金字塔: 每级 256² 稀疏 tile, 按 2^L 步长从画布各层采样合成(抽取, 不产生新色).
// 视口渲染用: zoom<1 时按级别取样, 避免逐屏像素合成 8 层.
class MipPyramid {
public:
    static constexpr int TS = 256;

    void reset(Canvas* c) { canvas_ = c; tiles_.clear(); }
    Canvas* canvas() const { return canvas_; }

    int tilesX(int level) const {
        if (!canvas_) return 0;
        const int span = TS << level;
        return (canvas_->width() + span - 1) / span;
    }
    int tilesY(int level) const {
        if (!canvas_) return 0;
        const int span = TS << level;
        return (canvas_->height() + span - 1) / span;
    }

    // 取(必要时构建)某级某块; 越界返回 nullptr
    const uint32_t* tile(int level, int tx, int ty);
    bool hasTile(int level, int tx, int ty) {
        if (!canvas_ || level < 0 || tx < 0 || ty < 0) return false;
        if (tx >= tilesX(level) || ty >= tilesY(level)) return false;
        std::lock_guard<std::mutex> lk(mu_);
        return tiles_.find(key(level, tx, ty)) != tiles_.end();
    }

    // 画布矩形变脏: 逐级丢弃受影响的块, 下次按需重建
    void invalidateRect(int x0, int y0, int x1, int y1);
    // 已缓存的块只重算受影响的像素(编辑时用, 不整块重建)
    // visibleLevel>=0: 只重算当前可见级别, 其它级别直接失效, 下次按需重建
    void updateRect(int x0, int y0, int x1, int y1, int visibleLevel = -1);
    // 内存上限: 只保留当前可见级别/视口附近的块, 其余按 map 顺序淘汰
    void trim(int visibleLevel, int tx0, int ty0, int tx1, int ty1,
              size_t maxTiles = 1536);   // 1536×256KB ≈ 384MB

    size_t bytes() const {
        size_t n = 0;
        for (auto& kv : tiles_) if (kv.second) n += (size_t)TS * TS * 4;
        return n;
    }
    size_t tileCount() const { return tiles_.size(); }

private:
    uint64_t key(int level, int tx, int ty) const {
        return ((uint64_t)level << 42) | ((uint64_t)ty << 21) | (uint64_t)tx;
    }
    Canvas* canvas_ = nullptr;
    std::map<uint64_t, std::unique_ptr<uint32_t[]>> tiles_;
    std::mutex mu_;
};

} // namespace endpix
