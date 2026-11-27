#pragma once
#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>

namespace endpix {

// 稀疏 tile 画布: 每层只有写过的 tile 才分配内存(256²=256KB)。
// 空白层只占 tile 指针表(16383² 一层约 32KB), 画布尺寸不再被内存卡死。
class Canvas {
public:
    static constexpr int STAMP_HIST = 8;   // 叠色: 当前图元 + 最近 7 个历史图元(覆盖手抖抖动周期, 不重复混色)
    static constexpr int TILE = 256;
    static constexpr int TILE_SHIFT = 8;
    static constexpr size_t TILE_PIX = (size_t)TILE * TILE;

    bool create(int width, int height, int layers);
    void destroy();

    int width() const { return w_; }
    int height() const { return h_; }
    int layers() const { return (int)tiles_.size(); }
    int tilesX() const { return tx_; }
    int tilesY() const { return ty_; }
    int active() const { return active_; }
    void setActive(int i) { if (i >= 0 && i < (int)tiles_.size()) active_ = i; }
    bool layerVisible(int i) const { return i >= 0 && i < (int)vis_.size() ? vis_[(size_t)i] != 0 : true; }
    void setLayerVisible(int i, bool on) { if (i >= 0 && i < (int)vis_.size()) vis_[(size_t)i] = on ? 1 : 0; }
    bool isLayerLocked(int i) const { return i >= 0 && i < (int)lock_.size() ? lock_[(size_t)i] != 0 : false; }
    void setLayerLocked(int i, bool on) { if (i >= 0 && i < (int)lock_.size()) lock_[(size_t)i] = on ? 1 : 0; }
    // 图层名称/不透明度(旧项目 layer.name/opacity): 跨帧共享, 只影响合成不影响原始像素
    const std::string& layerName(int i) const {
        static const std::string empty;
        return i >= 0 && i < (int)names_.size() ? names_[(size_t)i] : empty;
    }
    void setLayerName(int i, const std::string& n) {
        if (i >= 0 && i < (int)names_.size() && !n.empty()) names_[(size_t)i] = n;
    }
    float layerOpacity(int i) const {
        return i >= 0 && i < (int)opacity_.size() ? opacity_[(size_t)i] : 1.f;
    }
    void setLayerOpacity(int i, float o) {
        if (i >= 0 && i < (int)opacity_.size()) opacity_[(size_t)i] = std::max(0.f, std::min(1.f, o));
    }
    void moveLayer(int from, int to);
    // 图层模式(0 正常 / 1 平铺 / 2 剪切蒙版) + 平铺参数
    int layerMode(int i) const { return i>=0 && i<(int)lmode_.size() ? lmode_[(size_t)i] : 0; }
    void setLayerMode(int i, int m) { ensureLayerProps(); if (i>=0 && i<(int)lmode_.size()) lmode_[(size_t)i] = (uint8_t)std::max(0,std::min(2,m)); }
    // 混合模式(0正常 1正片叠底 2滤色 3叠加 4变暗 5变亮 6差值 7相加 8柔光 9强光), 与结构模式(正常/平铺/蒙版)互不冲突
    int layerBlend(int i) const { return i>=0 && i<(int)blend_.size() ? blend_[(size_t)i] : 0; }
    void setLayerBlend(int i, int m) { ensureLayerProps(); if (i>=0 && i<(int)blend_.size()) blend_[(size_t)i] = (uint8_t)std::max(0,std::min(9,m)); }
    int layerTileW(int i) const { return i>=0 && i<(int)tlW_.size() ? tlW_[(size_t)i] : 16; }
    int layerTileH(int i) const { return i>=0 && i<(int)tlH_.size() ? tlH_[(size_t)i] : 16; }
    int layerTileOffX(int i) const { return i>=0 && i<(int)tlOX_.size() ? tlOX_[(size_t)i] : 0; }
    int layerTileOffY(int i) const { return i>=0 && i<(int)tlOY_.size() ? tlOY_[(size_t)i] : 0; }
    int layerTileLockFrame(int i) const { return i>=0 && i<(int)tlLock_.size() ? tlLock_[(size_t)i] : 0; }
    bool layerShowGrid(int i) const { return i>=0 && i<(int)tlGrid_.size() && tlGrid_[(size_t)i]; }
    bool layerTileIndep(int i) const { return i>=0 && i<(int)tlIndep_.size() && tlIndep_[(size_t)i]; }
    uint32_t layerGridColor(int i) const { return i>=0 && i<(int)tlGridCol_.size() ? tlGridCol_[(size_t)i] : 0x66FFFFFFu; }
    void setLayerTile(int i, int w, int h, int ox, int oy, bool showGrid, uint32_t gridColor, bool indep, int lockFrame) {
        ensureLayerProps();
        if (i<0 || i>=(int)tlW_.size()) return;
        tlW_[(size_t)i]=std::max(1,std::min(4096,w)); tlH_[(size_t)i]=std::max(1,std::min(4096,h));
        tlOX_[(size_t)i]=ox; tlOY_[(size_t)i]=oy;
        tlGrid_[(size_t)i]=showGrid?1:0; tlGridCol_[(size_t)i]=gridColor;
        tlIndep_[(size_t)i]=indep?1:0; tlLock_[(size_t)i]=std::max(0,lockFrame);
    }
    // 平铺绘制: 把本条撤销记录里刚改动的像素, 按格(ox,oy,w,h)复制到画布上所有相同局部位置
    // (旧项目 SetLayerPixel 的 tileMode 分支: 画到哪就同步到所有格子)
    void replicateRecordedPixels(int layer, int w, int h, int ox, int oy);
    void ensureLayerProps();
    int addLayer();                 // 末尾加一层(全透明), 返回层数
    bool removeLayer(int i);        // 删层, 被删层有内容的 tile 标脏

    // 可能返回 nullptr(未分配=全透明; 整块同色也返回 nullptr, 用 isSolidTile/solidTileColor 读)
    uint32_t* tileForWrite(int layer, int ti);
    const uint32_t* tile(int layer, int ti) const;
    void releaseTile(int layer, int ti) { tiles_[layer][ti].reset(); solidFlag_[layer][ti] = 0; }

    // solid tile: 整块 256² 同一个颜色, 不分配像素内存(万级画布整片填充用)
    bool isSolidTile(int layer, int ti) const {
        return !solidFlag_.empty() && solidFlag_[(size_t)layer].size() > (size_t)ti &&
               solidFlag_[(size_t)layer][ti] != 0;
    }
    uint32_t solidTileColor(int layer, int ti) const { return solidCol_[(size_t)layer][ti]; }
    // 标记整块为同色(带撤销快照 + 标脏); 已有像素会被释放
    void setSolidTile(int layer, int ti, uint32_t color);
    // solid -> 256KB 像素块(局部写入前调用)
    void materializeTile(int layer, int ti);

    void setPixel(int x, int y, uint32_t argb);
    uint32_t getPixel(int x, int y) const;
    uint32_t getPixelL(int layer, int x, int y) const;
    void setPixelL(int layer, int x, int y, uint32_t argb);
    // 本层蒙版(旧 _clipMaskEnabled): 只写该层已有不透明像素处; 橡皮忽略
    void setAlphaLock(bool on) { alphaLock_ = on; }
    bool alphaLock() const { return alphaLock_; }

    // 裁剪到矩形(所有层), 成功后画布尺寸/瓦片表随之改变
    bool crop(int x, int y, int w, int h);
    // 调整画布尺寸并保留内容; ax/ay: 0=左/上 1=居中 2=右/下
    bool resizeTo(int newW, int newH, int ax, int ay);
    // 只保留矩形内的像素, 矩形外全部清空(画布尺寸不变)
    void clearOutside(int x0, int y0, int x1, int y1);

    // 剪切矩形(浮起选区): 合成时该矩形内视为透明, 像素原样保留供 GPU 直接采样
    // layer >= 0: 只挖空该层(其它层照常合成); -1: 所有层(旧"全部层"语义)
    void setCutRect(int x0, int y0, int x1, int y1, int layer = -1);
    void clearCutRect() { cutActive_ = false; cutLayer_ = -1; }
    bool cutActive() const { return cutActive_; }
    int cutLayer() const { return cutLayer_; }
    int cutX0() const { return cx0_; } int cutY0() const { return cy0_; }
    int cutX1() const { return cx1_; } int cutY1() const { return cy1_; }

    // 层内整块搬移(memmove 语义, 处理重叠), 用于浮起内容落定
    void moveRect(int layer, int sx, int sy, int w, int h, int dx, int dy);
    // 落定: 把源矩形中非透明像素写到目标位置(不清目标已有内容), 源位置清除;
    // 只处理有数据的行, 累计超过 budgetPx 像素就返回(供分帧搬运), 返回处理到的行号。
    // dstMask: 可选 w*h 位图(行主序), 0 的落点不写(旧项目"本层蒙版": 本层透明处不贴)
    // alphaOverlay: true=半透明像素 over 混合(旧行为); false=直接覆盖(保留源 alpha)
    int moveRectBlendStep(int layer, int sx, int sy, int w, int h, int dx, int dy,
                          int rowFrom, size_t budgetPx, std::vector<uint32_t>& rowScratch,
                          const uint64_t* dstMask = nullptr, bool alphaOverlay = false);
    void clearRect(int layer, int x0, int y0, int x1, int y1);

    // 半透明笔画覆盖掩码: 同一笔内每个像素只混一次, 避免来回涂抹越来越深(整体透明度)
    void beginStrokeMask() {
        strokeMaskOn_ = true; strokeMask_.clear();
        stampMaskOn_ = false;
        for (auto& sl : stampSlots_) { sl.mask.clear(); sl.tiles.clear(); sl.tileSet.clear(); }
        stampCur_ = -1;
    }
    // 叠色 ON: 每个笔刷图元记录覆盖; 最近 STAMP_HIST-1 个图元覆盖过的像素不重复混色,
    // 离开一段时间后再画回来(自交/回涂)才再混一次, 避免自由曲线边缘闪出叠色点。
    void beginStampMask() {
        stampMaskOn_ = true; strokeMaskOn_ = false; strokeMask_.clear();
        for (auto& sl : stampSlots_) { sl.mask.clear(); sl.tiles.clear(); sl.tileSet.clear(); }
        stampCur_ = -1;
        stampDirX_ = stampDirY_ = 0.f;
        stampTravel_ = 0.f;
        stampHasLast_ = false;
    }
    // 笔画方向(按输入事件下发): 叠色用方向+笔程判断"是不是同一趟"
    void setStampDir(float dx, float dy) { stampDirX_ = dx; stampDirY_ = dy; }
    // 笔程窗口: 拐角判定(相邻图元, 窗口≈本段长度) / 回涂判定(窗口≈笔径)
    void setStampWindow(float corner, float retrace) {
        stampWinCorner_ = corner; stampWinRetrace_ = retrace;
    }
    float stampDirX() const { return stampDirX_; }
    float stampDirY() const { return stampDirY_; }
    float stampWinCorner() const { return stampWinCorner_; }
    float stampWinRetrace() const { return stampWinRetrace_; }
    // 每个笔刷图元(stamp/胶囊)落笔前调用, 记录笔尖位置与笔程
    void rotateStampMaskAt(int x, int y) {
        if (!stampMaskOn_) return;
        if (stampHasLast_) {
            const float ddx = (float)(x - stampLastX_), ddy = (float)(y - stampLastY_);
            stampTravel_ += std::sqrt(ddx * ddx + ddy * ddy);
        }
        stampLastX_ = x; stampLastY_ = y; stampHasLast_ = true;
        stampCur_ = (stampCur_ + 1) % STAMP_HIST;
        auto& sl = stampSlots_[stampCur_];
        for (int ti : sl.tiles) {
            auto it = sl.mask.find(ti);
            if (it != sl.mask.end()) std::fill(it->second.begin(), it->second.end(), 0ull);
        }
        sl.tiles.clear();
        sl.tileSet.clear();
        sl.dx = stampDirX_; sl.dy = stampDirY_;
        sl.travel = stampTravel_;
    }
    void rotateStampMask() { rotateStampMaskAt(stampLastX_, stampLastY_); }
    void endStrokeMask() {
        strokeMaskOn_ = false; strokeMask_.clear();
        stampMaskOn_ = false;
        for (auto& sl : stampSlots_) { sl.mask.clear(); sl.tiles.clear(); sl.tileSet.clear(); }
        stampCur_ = -1;
    }
    bool strokeMaskActive() const { return strokeMaskOn_; }
    bool stampMaskActive() const { return stampMaskOn_; }
    // 叠色: 收集最近历史图元中该 tile 的覆盖位图(不含当前正在写的图元), 附带方向/笔程
    void stampSkipTiles(int ti, const std::vector<uint64_t>* out[STAMP_HIST],
                        float outDx[STAMP_HIST], float outDy[STAMP_HIST],
                        float outTravel[STAMP_HIST], int& n) const {
        n = 0;
        if (!stampMaskOn_) return;
        for (int i = 0; i < STAMP_HIST; i++) {
            if (i == stampCur_) continue;
            auto it = stampSlots_[i].mask.find(ti);
            if (it != stampSlots_[i].mask.end()) {
                out[n] = &it->second;
                outDx[n] = stampSlots_[i].dx;
                outDy[n] = stampSlots_[i].dy;
                outTravel[n] = stampSlots_[i].travel;
                n++;
            }
        }
    }
    float stampTravelNow() const { return stampTravel_; }
    // 叠色: 当前图元的覆盖位图(记录本图元覆盖了哪些像素)
    std::vector<uint64_t>* stampWriteTile(int ti, bool create) {
        if (!stampMaskOn_ || stampCur_ < 0) return nullptr;
        auto& sl = stampSlots_[stampCur_];
        auto it = sl.mask.find(ti);
        if (it != sl.mask.end()) {
            if (sl.tileSet.insert(ti).second) sl.tiles.push_back(ti);
            return &it->second;
        }
        if (!create) return nullptr;
        if (sl.mask.size() >= 2048) return nullptr;   // 2048*8KB ≈ 16MB/槽 上限
        auto& v = sl.mask[ti];
        v.assign(TILE_PIX / 64, 0ull);
        if (sl.tileSet.insert(ti).second) sl.tiles.push_back(ti);
        return &v;
    }
    // 取(必要时建)某 tile 的整笔覆盖位图; 超过预算返回 nullptr(退化为普通叠加)
    std::vector<uint64_t>* strokeMaskTile(int ti, bool create) {
        if (!strokeMaskOn_) return nullptr;
        auto it = strokeMask_.find(ti);
        if (it != strokeMask_.end()) return &it->second;
        if (!create) return nullptr;
        if (strokeMask_.size() >= 8192) return nullptr;   // 8192*8KB ≈ 64MB 上限
        auto& v = strokeMask_[ti];
        v.assign(TILE_PIX / 64, 0ull);
        return &v;
    }

    // 快照: 内容版本号 + 直接安装 tile(读档用, 不产生撤销)
    uint64_t revision() const { return rev_; }
    int liveTileCount() const {
        int n = 0;
        for (int l = 0; l < (int)tiles_.size(); l++)
            for (int ti = 0; ti < tx_ * ty_; ti++)
                if (tiles_[l][ti] || solidFlag_[l][ti]) n++;
        return n;
    }
    void installTile(int layer, int ti, const uint32_t* px, uint32_t solidColor, bool solid);
    void markDirtyTile(int ti);
    void markDirtyRect(int x0, int y0, int x1, int y1);
    // 只标矩形内"有实际 tile 数据"的块: 空白块的重合成结果和现在一样, 没必要刷
    void markDirtyAllocated(int x0, int y0, int x1, int y1);
    // 只标"除指定层以外还有数据"的块(落定搬走后, 源区域只剩其它层的合成结果要重画)
    void markDirtyOtherLayers(int x0, int y0, int x1, int y1, int exceptLayer);
    const std::vector<int>& dirtyTiles() const { return dirtyList_; }
    void clearDirty() {
        for (int ti : dirtyList_) dirtyFlags_[ti] = 0;
        dirtyList_.clear();
    }
    bool isTileDirty(int ti) const { return dirtyFlags_[ti] != 0; }

    // 整画布操作(翻转/灰度): 只遍历已分配 tile; 调用前先 beginRecord + snapshotAllTiles
    void flipAllLayers(bool horizontal);
    void grayscaleAllLayers();
    // 整画布撤销: 连空 tile 一起记录(翻转会把内容搬到原本空的块, 撤销时必须能清掉)
    void snapshotAllTiles();
    // 色阶: 当前层亮度直方图 / 按 LUT 调整当前层(预乘 alpha 反解后查表再预乘)
    void levelsHistogram(int layer, int channel, int out[256]) const;   // channel 0=RGB亮度 1=R 2=G 3=B
    void applyLevelsLut(int layer, int channel, const uint8_t lut[256]);

    // 撤销: 按 tile 快照(只记录被写过的块; applyRecord 返回应用前状态供 redo)
    struct TileSnap {
        int layer = 0; int ti = 0;
        uint8_t kind = 0;        // 0=空 1=像素块 2=整块同色
        uint32_t color = 0;      // kind==2
        std::vector<uint32_t> px; // kind==1
    };
    using UndoRecord = std::vector<TileSnap>;
    void beginRecord();
    UndoRecord endRecord();
    bool recording() const { return recActive_; }
    UndoRecord applyRecord(const UndoRecord& rec);

    // 填充专用: 撤销只记"被改的行段"(旧色=种子色), 不整块快照
    struct Span { int yy; int x0; int x1; };
    void setFillRecord(bool on) { fillRecord_ = on; }
    void paintSpans(int layer, const std::vector<Span>& spans, uint32_t color);

    // 所有层非透明像素的精确包围盒(导出用); 空画布返回 false
    bool inkBounds(int* x0, int* y0, int* x1, int* y1) const;

    size_t bytes() const;          // 实际分配的 tile 字节数
    size_t countInk(int layer) const;

private:
    int w_ = 0, h_ = 0, tx_ = 0, ty_ = 0, active_ = 0;
    std::vector<std::vector<std::unique_ptr<uint32_t[]>>> tiles_;
    std::unordered_map<int, std::vector<uint64_t>> strokeMask_;
    bool strokeMaskOn_ = false;
    // 叠色逐图元覆盖环形历史: 当前图元 + 最近 STAMP_HIST-1 个图元
    struct StampSlot {
        std::unordered_map<int, std::vector<uint64_t>> mask;
        std::vector<int> tiles;
        std::unordered_set<int> tileSet;
        float dx = 0.f, dy = 0.f;   // 该图元的笔画方向
        float travel = 0.f;         // 该图元落笔时的累计笔程
    };
    StampSlot stampSlots_[STAMP_HIST];
    bool stampMaskOn_ = false;
    int stampCur_ = -1;
    float stampDirX_ = 0.f, stampDirY_ = 0.f;
    float stampTravel_ = 0.f;
    int stampLastX_ = 0, stampLastY_ = 0;
    bool stampHasLast_ = false;
    float stampWinCorner_ = 2.f;
    float stampWinRetrace_ = 2.f;
    std::vector<uint8_t> vis_, lock_;
    // 图层模式 + 平铺参数(每层, 与层数同步)
    std::vector<uint8_t> lmode_, tlGrid_, tlIndep_;
    std::vector<uint8_t> blend_;      // 混合模式(每层)
    std::vector<int> tlW_, tlH_, tlOX_, tlOY_, tlLock_;
    std::vector<uint32_t> tlGridCol_;
    std::vector<std::string> names_;
    std::vector<float> opacity_;
    bool cutActive_ = false;
    int cutLayer_ = -1;   // >=0: 只挖空该层; -1: 所有层
    int cx0_ = 0, cy0_ = 0, cx1_ = -1, cy1_ = -1;
    std::vector<uint8_t> dirtyFlags_;
    std::vector<int> dirtyList_;
    // 整块同色状态(每层每块一个 flag + color), 32767² 一层 ~82KB
    std::vector<std::vector<uint8_t>> solidFlag_;
    std::vector<std::vector<uint32_t>> solidCol_;
    // 撤销录制状态
    bool recActive_ = false;
    bool fillRecord_ = false;   // 填充录制中: tileForWrite 不整块快照
    bool alphaLock_ = false;    // 本层蒙版
    uint64_t rev_ = 1;          // 内容版本(快照脏判断)
    UndoRecord rec_;
    std::vector<uint8_t> recFlags_;
    std::vector<int> recKeys_;
    void snapshotTile(int layer, int ti);
};

} // namespace endpix
