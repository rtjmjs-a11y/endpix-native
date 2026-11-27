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

    // 画布矩形变脏: 逐级丢弃受影响的块, 下次按需重建
    void invalidateRect(int x0, int y0, int x1, int y1);
    // 已缓存的块只重算受影响的像素(编辑时用, 不整块重建)
    void updateRect(int x0, int y0, int x1, int y1);

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
