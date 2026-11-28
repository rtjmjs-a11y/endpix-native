#include "raster.h"
#include <arm_neon.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <climits>

namespace endpix::raster {

static std::atomic<bool> gFastCompose{true};
void setFastComposeEnabled(bool on) { gFastCompose.store(on, std::memory_order_relaxed); }

static std::atomic<bool> gMipSparseSkip{true};
bool mipSparseSkipEnabled() { return gMipSparseSkip.load(std::memory_order_relaxed); }
void setMipSparseSkipEnabled(bool on) { gMipSparseSkip.store(on, std::memory_order_relaxed); }


void over8(uint8_t* d, const uint8_t* s) {
    uint8x8x4_t sv = vld4_u8(s);
    // 全透明段直接跳过, 不读目标也不写
    if (vmaxv_u8(sv.val[3]) == 0) return;
    // 不透明源(画笔/线稿占绝大多数)直接覆盖, 省掉整段混合运算
    if (vminv_u8(sv.val[3]) == 255) { vst4_u8(d, sv); return; }
    uint8x8x4_t dv = vld4_u8(d);
    uint8x8_t sa = sv.val[3];
    uint8x8_t inv = vmvn_u8(sa);
    const uint16x8_t k127 = vdupq_n_u16(127);
    const uint16x8_t k1 = vdupq_n_u16(1);
    uint8x8x4_t ov;
    for (int c = 0; c < 3; c++) {
        uint16x8_t t = vaddq_u16(vmull_u8(sv.val[c], sa), vmull_u8(dv.val[c], inv));
        t = vaddq_u16(t, k127);
        ov.val[c] = vmovn_u16(vshrq_n_u16(vaddq_u16(vaddq_u16(t, k1), vshrq_n_u16(t, 8)), 8));
    }
    uint16x8_t ta = vaddq_u16(vmull_u8(dv.val[3], inv), k127);
    ov.val[3] = vqadd_u8(vmovn_u16(vshrq_n_u16(vaddq_u16(vaddq_u16(ta, k1), vshrq_n_u16(ta, 8)), 8)), sa);
    vst4_u8(d, ov);
}

void overPixel(uint32_t& dst, uint32_t s) {
    const int sa = (s >> 24) & 255;
    if (sa == 255) { dst = s; return; }
    int inv = 255 - sa;
    int dr = dst & 255, dg = (dst >> 8) & 255, db = (dst >> 16) & 255, da = (dst >> 24) & 255;
    int sr = s & 255, sg = (s >> 8) & 255, sb = (s >> 16) & 255;
    dst = (uint32_t)((sr * sa + dr * inv + 127) / 255)
        | ((uint32_t)((sg * sa + dg * inv + 127) / 255) << 8)
        | ((uint32_t)((sb * sa + db * inv + 127) / 255) << 16)
        | ((uint32_t)std::min(255, sa + (da * inv + 127) / 255) << 24);
}

// 可分离混合函数: cs=上层通道, cd=底色通道 (均为未预乘 0..255)
static inline int blendChannel(int cs, int cd, int mode) {
    switch (mode) {
        case 1: return cs * cd / 255;                                  // 正片叠底
        case 2: return 255 - (255 - cs) * (255 - cd) / 255;            // 滤色
        case 3: return cd < 128 ? (2 * cs * cd) / 255                 // 叠加
                                : 255 - 2 * (255 - cs) * (255 - cd) / 255;
        case 4: return std::min(cs, cd);                               // 变暗
        case 5: return std::max(cs, cd);                               // 变亮
        case 6: return std::abs(cs - cd);                              // 差值
        case 7: return std::min(255, cs + cd);                         // 相加
        case 8: {                                                      // 柔光(pegtop)
            const float s = cs / 255.f, d = cd / 255.f;
            const float r = (1.f - 2.f * s) * d * d + 2.f * s * d;
            return std::max(0, std::min(255, (int)std::lround(r * 255.f)));
        }
        case 9: return cs < 128 ? (2 * cs * cd) / 255                 // 强光
                                : 255 - 2 * (255 - cs) * (255 - cd) / 255;
        default: return cs;                                            // 正常
    }
}

uint32_t blendPixel(uint32_t d, uint32_t s, int mode) {
    if (mode <= 0) { overPixel(d, s); return d; }
    const int sa = (s >> 24) & 255;
    if (!sa) return d;
    const int da = (d >> 24) & 255;
    if (!da) return s;   // 透明底: 任何混合结果都等于上层本身
    if (sa == 255 && da == 255) {   // 不透明像素(像素画绝大多数): 直接算 B
        const int r = blendChannel(s & 255, d & 255, mode);
        const int g = blendChannel((s >> 8) & 255, (d >> 8) & 255, mode);
        const int b = blendChannel((s >> 16) & 255, (d >> 16) & 255, mode);
        return 0xFF000000u | ((uint32_t)b << 16) | ((uint32_t)g << 8) | (uint32_t)r;
    }
    // 半透明: 反预乘 → 混合 → 按 W3C 公式(alpha 加权)重新预乘
    const int sr = (s & 255) * 255 / sa, sg = ((s >> 8) & 255) * 255 / sa, sb = ((s >> 16) & 255) * 255 / sa;
    const int dr = (d & 255) * 255 / da, dg = ((d >> 8) & 255) * 255 / da, db = ((d >> 16) & 255) * 255 / da;
    const int br = blendChannel(sr, dr, mode), bg = blendChannel(sg, dg, mode), bb = blendChannel(sb, db, mode);
    const int na = std::min(255, sa + (da * (255 - sa) + 127) / 255);
    auto comp = [&](int cs, int cd, int cb) -> int {
        const long long v = (long long)sa * (255 - da) * cs
                          + (long long)sa * da * cb
                          + (long long)(255 - sa) * da * cd;
        int out = (int)((v + 32512) / 65025);
        return std::max(0, std::min(255, out));
    };
    return ((uint32_t)na << 24) | ((uint32_t)comp(sb, db, bb) << 16)
         | ((uint32_t)comp(sg, dg, bg) << 8) | (uint32_t)comp(sr, dr, br);
}

Mode25D& mode25D() {
    static Mode25D g;
    return g;
}

void setYaw25D(float deg) {
    Mode25D& g = mode25D();
    g.yawDeg = deg;
    const float r = deg * 3.14159265358979f / 180.0f;
    // 画布/屏幕坐标 y 向下; 内容顺时针旋转 yaw 后, 上层位移取 (-sin, -cos) 保证屏幕上向上堆叠
    g.offX = -std::sin(r);
    g.offY = -std::cos(r);
}

static inline uint32_t scaleAlpha(uint32_t v, int ma) {
    if (ma >= 255) return v;
    if (ma <= 0) return 0;
    const uint32_t a = ((v >> 24) & 255u) * (uint32_t)ma / 255u;
    if (!a) return 0;
    const uint32_t r = (v & 255u) * (uint32_t)ma / 255u;
    const uint32_t g2 = (((v >> 8) & 255u) * (uint32_t)ma) / 255u;
    const uint32_t b = (((v >> 16) & 255u) * (uint32_t)ma) / 255u;
    return (a << 24) | (b << 16) | (g2 << 8) | r;
}

// 剪切蒙版: 紧邻上方(可连续多层)的蒙版 alpha 相乘; 蒙版层自身不显示
// (旧项目 ClipMaskBurst: 蒙版裁剪紧邻下方层, 多层蒙版链式相乘)
static inline int clipMaskAlpha(const Canvas& c, int l, int sx, int sy) {
    const int nl = c.layers();
    if (l + 1 >= nl || !c.layerVisible(l + 1) || c.layerMode(l + 1) != 2) return 255;
    int ma = 255;
    for (int m = l + 1; m < nl; m++) {
        if (!c.layerVisible(m) || c.layerMode(m) != 2) break;
        const int a = (int)((c.getPixelL(m, sx, sy) >> 24) & 255u);
        ma = ma * a / 255;
        if (ma == 0) return 0;
    }
    return ma;
}

// 逐像素合成: 图层顺序 + 不透明度 + 剪切蒙版 + 平铺网格 + 2.5D叠层
// 2.5D(旧 _mode25D/_depth25D): 可见图层沿方向逐层错位 depth 像素, 叠出立体厚度;
// 第 l 层绘制在 (x + dx*l*depth, y + dy*l*depth), 所以取源 (x - dx*l*depth, ...).
uint32_t composeLayersPixel(const Canvas& c, int x, int y) {
    return composeLayersPixelEx(c, x, y, true, true);
}

uint32_t composeLayersPixelEx(const Canvas& c, int x, int y, bool showGrid, bool view25d,
                              bool applyCut) {
    const int nl = c.layers();
    if (x < 0 || y < 0 || x >= c.width() || y >= c.height()) return 0;
    const Mode25D& g = mode25D();
    const bool d25 = view25d && g.on && g.depth > 0 && nl > 1;
    const int cw = c.width(), ch = c.height();
    uint32_t acc = 0;
    for (int l = 0; l < nl; l++) {
        if (!c.layerVisible(l)) continue;
        const int mode = c.layerMode(l);
        if (mode == 2) continue;   // 剪切蒙版层本身不显示, 只裁下方
        // 旧项目真Z轴: 底层(l=0)不动, 第 l 层沿视角方向错开 l*depth
        const int off = d25 ? l * g.depth : 0;
        const int ox = d25 ? (int)std::lround(g.offX * off) : 0;
        const int oy = d25 ? (int)std::lround(g.offY * off) : 0;
        const int sx = x - ox, sy = y - oy;
        if (sx < 0 || sy < 0 || sx >= cw || sy >= ch) continue;
        // 剪切矩形只挖空被剪的那个图层: 该层在矩形内视为透明, 其它层照常合成
        if (applyCut && c.cutActive() && c.cutLayer() >= 0 && l == c.cutLayer() &&
            sx >= c.cutX0() && sx <= c.cutX1() &&
            sy >= c.cutY0() && sy <= c.cutY1()) continue;
        // 蒙版与目标层同一(错位前的)坐标: 旧项目先烘焙蒙版再整体偏移
        const int maskA = clipMaskAlpha(c, l, sx, sy);
        if (maskA == 0) continue;
        uint32_t v = applyLayerOpacity(c.getPixelL(l, sx, sy), c.layerOpacity(l));
        if (v) {
            v = scaleAlpha(v, maskA);
            acc = blendPixel(acc, v, c.layerBlend(l));
        }
        // 网格叠加在屏幕坐标(不随2.5D偏移)
        if (showGrid && mode == 1 && c.layerShowGrid(l)) {
            const int tw = std::max(1, c.layerTileW(l));
            const int th = std::max(1, c.layerTileH(l));
            const int ox = c.layerTileOffX(l), oy = c.layerTileOffY(l);
            const int mx = ((x - ox) % tw + tw) % tw;
            const int my = ((y - oy) % th + th) % th;
            if (mx == 0 || my == 0) {
                uint32_t gc = c.layerGridColor(l);
                if ((gc >> 24) == 0) gc |= 0x66000000u;
                raster::overPixel(acc, gc);
            }
        }
    }
    return acc;
}

// 普通层快路径: 只做可见层 over, 跳过每像素的图层属性/蒙版/2.5D 判断。
// 只在语义与 composeLayersPixelEx 完全一致时使用(否则调用方回退)。
bool composeLayersFastEligible(const Canvas& c, bool showGrid, bool view25d, bool applyCut) {
    if (!gFastCompose.load(std::memory_order_relaxed)) return false;
    const int nl = c.layers();
    if (nl <= 0) return false;
    if (applyCut && c.cutActive()) return false;
    for (int l = 0; l < nl; l++) {
        if (!c.layerVisible(l)) continue;
        if (c.layerMode(l) == 2) return false;
        if (showGrid && c.layerMode(l) == 1) return false;
        if (c.layerOpacity(l) < 0.999f) return false;
        if (c.layerBlend(l) != 0) return false;
    }
    return true;
}

uint32_t composeLayersPixelFast(const Canvas& c, int x, int y) {
    const int nl = c.layers();
    const Mode25D& g = mode25D();
    const bool d25 = g.on && g.depth > 0 && nl > 1;
    uint32_t acc = 0;
    for (int l = 0; l < nl; l++) {
        if (!c.layerVisible(l)) continue;
        int sx = x, sy = y;
        if (d25) {
            const int off = l * g.depth;
            sx -= (int)std::lround(g.offX * off);
            sy -= (int)std::lround(g.offY * off);
            if ((unsigned)sx >= (unsigned)c.width() || (unsigned)sy >= (unsigned)c.height()) continue;
        }
        const uint32_t v = c.getPixelL(l, sx, sy);
        if (v) overPixel(acc, v);
    }
    return acc;
}

void compositeRect(const Canvas& c, int nlayers, uint32_t* dst, int dstStride,
                   int x, int y, int w, int h) {
    if (nlayers > 16) nlayers = 16;
    const int tx0 = x >> Canvas::TILE_SHIFT, ty0 = y >> Canvas::TILE_SHIFT;
    const int tx1 = (x + w - 1) >> Canvas::TILE_SHIFT, ty1 = (y + h - 1) >> Canvas::TILE_SHIFT;
    const int cw = c.tilesX();
    for (int ty = ty0; ty <= ty1; ty++) {
        for (int tx = tx0; tx <= tx1; tx++) {
            const int ti = ty * cw + tx;
            const int px0 = tx << Canvas::TILE_SHIFT, py0 = ty << Canvas::TILE_SHIFT;
            const int cx0 = std::max(x, px0), cx1 = std::min(x + w - 1, px0 + Canvas::TILE - 1);
            const int cy0 = std::max(y, py0), cy1 = std::min(y + h - 1, py0 + Canvas::TILE - 1);
            if (cx0 > cx1 || cy0 > cy1) continue;

            const uint32_t* tiles[16] = {};
            float ops[16] = {};
            int nonNull = 0;
            const uint32_t* only = nullptr;
            float onlyOp = 1.f;
            for (int l = 0; l < nlayers; l++) {
                if (!c.layerVisible(l)) continue;
                const float op = c.layerOpacity(l);
                if (op <= 0.001f) continue;
                tiles[l] = c.tile(l, ti);
                ops[l] = op;
                if (tiles[l]) { nonNull++; only = tiles[l]; onlyOp = op; }
            }
            const int rowLen = cx1 - cx0 + 1;

            for (int yy = cy0; yy <= cy1; yy++) {
                uint32_t* d = dst + (size_t)(yy - y) * dstStride + (cx0 - x);
                const int tileRow = ((yy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) + (cx0 & (Canvas::TILE - 1));
                if (nonNull == 0) {
                    memset(d, 0, (size_t)rowLen * 4);   // 清掉 staging 里的旧内容
                } else if (nonNull == 1 && onlyOp >= 0.999f) {
                    // 只有一层有数据且不透明(绝大多数情况): 一趟写完, 省掉 memset + 逐层循环
                    blitOverZeroRow((uint8_t*)d, (const uint8_t*)(only + tileRow), rowLen);
                } else {
                    memset(d, 0, (size_t)rowLen * 4);
                    for (int l = 0; l < nlayers; l++) {
                        if (!c.layerVisible(l)) continue;
                        if (!tiles[l]) continue;
                        const float op = ops[l];
                        if (op <= 0.001f) continue;
                        const uint32_t* src = tiles[l] + tileRow;
                        if (op >= 0.999f) {
                            const uint8_t* s = (const uint8_t*)src;
                            uint8_t* dd = (uint8_t*)d;
                            int xx = 0;
                            for (; xx + 8 <= rowLen; xx += 8) over8(dd + xx * 4, s + xx * 4);
                            for (; xx < rowLen; xx++) {
                                uint32_t v;
                                memcpy(&v, dd + xx * 4, 4);
                                overPixel(v, src[xx]);
                                memcpy(dd + xx * 4, &v, 4);
                            }
                        } else {
                            for (int xx = 0; xx < rowLen; xx++) {
                                const uint32_t sv = applyLayerOpacity(src[xx], op);
                                if (sv) overPixel(d[xx], sv);
                            }
                        }
                    }
                }
                // 剪切蒙版: 只挖空被剪的层; 其它层在该矩形内重新叠加, 避免隔层内容也被裁掉
                if (c.cutActive() && yy >= c.cutY0() && yy <= c.cutY1()) {
                    const int mx0 = std::max(cx0, c.cutX0()), mx1 = std::min(cx1, c.cutX1());
                    if (mx0 <= mx1) {
                        const int len = mx1 - mx0 + 1, off0 = mx0 - cx0;
                        memset(d + off0, 0, (size_t)len * 4);
                        const int cutL = c.cutLayer();
                        if (cutL >= 0) {
                            for (int l = 0; l < nlayers; l++) {
                                if (l == cutL || !tiles[l]) continue;
                                const float op = ops[l];
                                if (op <= 0.001f) continue;
                                const uint32_t* src = tiles[l] + tileRow + off0;
                                uint32_t* dst2 = d + off0;
                                if (op >= 0.999f) {
                                    for (int xx = 0; xx < len; xx++) {
                                        if (src[xx]) overPixel(dst2[xx], src[xx]);
                                    }
                                } else {
                                    for (int xx = 0; xx < len; xx++) {
                                        const uint32_t sv = applyLayerOpacity(src[xx], op);
                                        if (sv) overPixel(dst2[xx], sv);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

void blitOverZeroRow(uint8_t* d, const uint8_t* s, int n) {
    uint8x8x4_t zero;
    zero.val[0] = vdup_n_u8(0); zero.val[1] = vdup_n_u8(0);
    zero.val[2] = vdup_n_u8(0); zero.val[3] = vdup_n_u8(0);
    int i = 0;
    for (; i + 8 <= n; i += 8) {
        uint8x8x4_t sv = vld4_u8(s + (size_t)i * 4);
        if (vmaxv_u8(sv.val[3]) == 0) { vst4_u8(d + (size_t)i * 4, zero); continue; }
        if (vminv_u8(sv.val[3]) == 255) { vst4_u8(d + (size_t)i * 4, sv); continue; }
        vst4_u8(d + (size_t)i * 4, zero);
        over8(d + (size_t)i * 4, s + (size_t)i * 4);
    }
    for (; i < n; i++) {
        uint32_t v;
        memcpy(&v, s + (size_t)i * 4, 4);
        uint32_t o = 0;
        if (v) overPixel(o, v);
        memcpy(d + (size_t)i * 4, &o, 4);
    }
}

void blitOverRow(uint32_t* dst, const uint32_t* src, int n) {
    int i = 0;
    for (; i + 8 <= n; i += 8) {
        uint8x8x4_t sv = vld4_u8((const uint8_t*)(src + i));
        if (vmaxv_u8(sv.val[3]) == 0) continue;                 // 整段透明: 目标原样保留
        over8((uint8_t*)(dst + i), (const uint8_t*)(src + i));  // 内部含不透明快路径
    }
    for (; i < n; i++) {
        const uint32_t v = src[i];
        if (v) overPixel(dst[i], v);
    }
}

// 直接覆盖(保留源 alpha, 含半透明): 非 0 源像素原样写入, 0 跳过不擦目标。
// 裁剪工具栏「透明叠加」默认关闭时, 落定走这里, 避免半透明内容与目标底色混合。
void blitReplaceRow(uint32_t* dst, const uint32_t* src, int n) {
    for (int i = 0; i < n; i++) {
        const uint32_t v = src[i];
        if (v) dst[i] = v;
    }
}

// 叠色 ON: 普通 over —— 每笔内部由笔画掩码保证只混一次(整笔透明度均匀),
// 不同笔之间照常累积, 重复涂抹会越来越实。
// 叠色 OFF(平涂): 输出 alpha 不超过 max(原目标 alpha, 画笔 alpha), 并同比缩放预乘 RGB,
// 同一区域反复涂保持画笔透明度, 不再叠深。
static inline void brushBlendPixel(uint32_t& p, uint32_t argb, bool buildup) {
    if (buildup) { overPixel(p, argb); return; }
    const uint32_t sa = (argb >> 24) & 255u;
    const uint32_t da = (p >> 24) & 255u;
    overPixel(p, argb);
    const uint32_t accA = (p >> 24) & 255u;
    const uint32_t capA = sa > da ? sa : da;
    if (accA > capA && accA > 0) {
        // 画布是预乘 alpha: 压回透明度时必须同比缩放 RGB, 否则颜色会越叠越亮。
        const uint32_t r = (p & 255u) * capA / accA;
        const uint32_t g = ((p >> 8) & 255u) * capA / accA;
        const uint32_t b = ((p >> 16) & 255u) * capA / accA;
        p = (capA << 24) | (b << 16) | (g << 8) | r;
    } else {
        p = (capA << 24) | (p & 0x00FFFFFFu);
    }
}

// 收集跳过掩码: 叠色 ON 用最近几个图元的历史; 平涂 OFF 用整笔掩码。
struct SkipSet {
    const std::vector<uint64_t>* masks[Canvas::STAMP_HIST] = {};
    float dx[Canvas::STAMP_HIST] = {}, dy[Canvas::STAMP_HIST] = {}, travel[Canvas::STAMP_HIST] = {};
    int n = 0;
    bool stamp = false;
    float curDx = 0.f, curDy = 0.f, curTravel = 0.f, winCorner = 0.f, winRetrace = 0.f;
};
static inline void gatherSkipMasks(Canvas& c, int ti, SkipSet& out) {
    out.n = 0;
    out.stamp = c.stampMaskActive();
    if (out.stamp) {
        c.stampSkipTiles(ti, out.masks, out.dx, out.dy, out.travel, out.n);
        out.curDx = c.stampDirX(); out.curDy = c.stampDirY();
        out.curTravel = c.stampTravelNow();
        out.winCorner = c.stampWinCorner();
        out.winRetrace = c.stampWinRetrace();
        return;
    }
    const auto* sm = c.strokeMaskTile(ti, false);
    if (sm) { out.masks[0] = sm; out.n = 1; }
}
// 当前图元的记录掩码: 叠色 ON 写当前 slot; 平涂 OFF 写整笔掩码。
static inline std::vector<uint64_t>* writeMaskTile(Canvas& c, int ti) {
    if (c.stampMaskActive()) return c.stampWriteTile(ti, true);
    return c.strokeMaskTile(ti, true);
}
// 叠色: 像素是否已被"同一趟"覆盖过。
// 同一趟 = 方向基本一致(直线继续/笔尖密集盖章) 或 笔程很近(拐角/相邻图元);
// 反向回涂或离开一段距离后再画回来(自交) → 不算同一趟, 允许再混一次。
static inline bool seenAny(const SkipSet& ss, int word, uint64_t bit) {
    for (int i = 0; i < ss.n; i++) {
        if (!ss.masks[i] || !((*ss.masks[i])[word] & bit)) continue;
        if (!ss.stamp) return true;   // 平涂: 整笔去重
        const float sl = std::sqrt(ss.dx[i] * ss.dx[i] + ss.dy[i] * ss.dy[i]);
        const float cl = std::sqrt(ss.curDx * ss.curDx + ss.curDy * ss.curDy);
        float dot = 0.f;
        if (sl > 1e-4f && cl > 1e-4f)
            dot = (ss.dx[i] * ss.curDx + ss.dy[i] * ss.curDy) / (sl * cl);
        if (dot >= 0.2f) return true;                                          // 同方向继续: 同一趟
        const float travelDiff = ss.curTravel - ss.travel[i];
        // 反向回涂: 只有离开约一个笔径后回来才再混; 拐角/横向自交: 用本段长度窗口
        const float win = (dot < -0.5f) ? ss.winRetrace : ss.winCorner;
        if (travelDiff <= win) return true;
    }
    return false;
}

void stampCircleLegacy(Canvas& c, int cx, int cy, int radius, uint32_t argb, bool erase, bool buildup) {
    const int r2 = radius * radius;
    const int x0 = cx - radius, x1 = cx + radius, y0 = cy - radius, y1 = cy + radius;
    const bool opaque = erase || ((argb >> 24) & 255) == 255;
    for (int y = y0; y <= y1; y++) {
        if ((unsigned)y >= (unsigned)c.height()) continue;
        const int ty = y >> Canvas::TILE_SHIFT;
        int lastTx = -1;
        uint32_t* trow = nullptr;
        const int tileRowOff = (y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT;
        for (int x = x0; x <= x1; x++) {
            if ((unsigned)x >= (unsigned)c.width()) continue;
            const int dx = x - cx, dy = y - cy;
            if (dx * dx + dy * dy > r2) continue;
            const int tx = x >> Canvas::TILE_SHIFT;
            if (tx != lastTx) {
                lastTx = tx;
                trow = c.tileForWrite(c.active(), ty * c.tilesX() + tx);
            }
            uint32_t& p = trow[tileRowOff + (x & (Canvas::TILE - 1))];
            if (erase) p = 0;
            else if (opaque) p = argb;
            else overPixel(p, argb);
        }
    }
    c.markDirtyRect(std::max(0, x0), std::max(0, y0), std::min(c.width() - 1, x1), std::min(c.height() - 1, y1));
}

void stampCircleSpan(Canvas& c, int cx, int cy, int radius, uint32_t argb, bool erase, bool buildup) {
    const int r2 = radius * radius;
    const int y0 = std::max(0, cy - radius), y1 = std::min(c.height() - 1, cy + radius);
    const int x0 = std::max(0, cx - radius), x1 = std::min(c.width() - 1, cx + radius);
    if (x0 > x1 || y0 > y1) return;
    const bool opaque = erase || ((argb >> 24) & 255) == 255;
    const bool lock = c.alphaLock() && !erase;
    const int T = Canvas::TILE;
    const int cw = c.tilesX();
    const uint32x4_t v4 = vdupq_n_u32(argb);
    const uint32x4_t z4 = vdupq_n_u32(0);
    for (int y = y0; y <= y1; y++) {
        const int dy = y - cy;
        const int dx = (int)std::sqrt((double)(r2 - dy * dy));
        int xs = std::max(x0, cx - dx), xe = std::min(x1, cx + dx);
        if (xs > xe) continue;
        int x = xs;
        while (x <= xe) {
            const int tx = x >> Canvas::TILE_SHIFT;
            const int segEnd = std::min(xe, ((tx + 1) << Canvas::TILE_SHIFT) - 1);
            uint32_t* t = c.tileForWrite(c.active(), (y >> Canvas::TILE_SHIFT) * cw + tx);
            uint32_t* rp = t + (((size_t)(y & (T - 1)) << Canvas::TILE_SHIFT) + (x & (T - 1)));
            const int n = segEnd - x + 1;
            if (erase) {
                memset(rp, 0, (size_t)n * 4);
            } else if (lock) {
                const int ti = (y >> Canvas::TILE_SHIFT) * cw + tx;
                SkipSet ss;
                gatherSkipMasks(c, ti, ss);
                auto* wmask = writeMaskTile(c, ti);
                const int yy = y & (T - 1), bx = x & (T - 1);
                for (int i = 0; i < n; i++) {
                    const int idx = (yy << Canvas::TILE_SHIFT) + bx + i;
                    const uint64_t bit = 1ull << (idx & 63);
                    const bool seen = seenAny(ss, idx >> 6, bit);
                    if (wmask) (*wmask)[idx >> 6] |= bit;
                    if (seen) continue;
                    if (rp[i] >> 24) brushBlendPixel(rp[i], argb, buildup);
                }
            } else if (opaque) {
                int i = 0;
                for (; i + 4 <= n; i += 4) vst1q_u32(rp + i, v4);
                for (; i < n; i++) rp[i] = argb;
            } else {
                const int ti = (y >> Canvas::TILE_SHIFT) * cw + tx;
                SkipSet ss;
                gatherSkipMasks(c, ti, ss);
                auto* wmask = writeMaskTile(c, ti);
                const int yy = y & (T - 1), bx = x & (T - 1);
                for (int i = 0; i < n; i++) {
                    const int idx = (yy << Canvas::TILE_SHIFT) + bx + i;
                    const uint64_t bit = 1ull << (idx & 63);
                    const bool seen = seenAny(ss, idx >> 6, bit);
                    if (wmask) (*wmask)[idx >> 6] |= bit;
                    if (seen) continue;
                    brushBlendPixel(rp[i], argb, buildup);
                }
            }
            x = segEnd + 1;
        }
    }
    c.markDirtyRect(std::max(0, cx - radius), std::max(0, cy - radius),
                    std::min(c.width() - 1, cx + radius), std::min(c.height() - 1, cy + radius));
}

void stampCircle(Canvas& c, int cx, int cy, int radius, uint32_t argb, bool erase, bool buildup) {
    c.rotateStampMaskAt(cx, cy);
    stampCircleSpan(c, cx, cy, radius, argb, erase, buildup);
}

static void paintSpanRow(Canvas& c, int y, int xs, int xe, uint32_t argb, bool erase, bool buildup) {
    if ((unsigned)y >= (unsigned)c.height()) return;
    xs = std::max(0, xs); xe = std::min(c.width() - 1, xe);
    if (xs > xe) return;
    const bool opaque = erase || ((argb >> 24) & 255) == 255;
    const bool lock = c.alphaLock() && !erase;
    const int cw = c.tilesX();
    const int ty = y >> Canvas::TILE_SHIFT;
    const int yy = y & (Canvas::TILE - 1);
    int x = xs;
    while (x <= xe) {
        const int tx = x >> Canvas::TILE_SHIFT;
        const int end = std::min(xe, ((tx + 1) << Canvas::TILE_SHIFT) - 1);
        uint32_t* t = c.tileForWrite(c.active(), ty * cw + tx);
        uint32_t* rp = t + (((size_t)yy << Canvas::TILE_SHIFT) + (x & (Canvas::TILE - 1)));
        const int n = end - x + 1;
        if (erase) {
            memset(rp, 0, (size_t)n * 4);
        } else if (opaque) {
            if (lock) {
                // 本层蒙版: 不透明笔也只能写已有像素(水平/垂直分段同样生效)
                for (int i = 0; i < n; i++) if (rp[i] >> 24) rp[i] = argb;
            } else {
                for (int i = 0; i < n; i++) rp[i] = argb;
            }
        } else {
            const int ti = ty * cw + tx;
            SkipSet ss;
            gatherSkipMasks(c, ti, ss);
            auto* wmask = writeMaskTile(c, ti);
            const int bx = x & (Canvas::TILE - 1);
            for (int i = 0; i < n; i++) {
                const int idx = (yy << Canvas::TILE_SHIFT) + bx + i;
                const uint64_t bit = 1ull << (idx & 63);
                const bool seen = seenAny(ss, idx >> 6, bit);
                if (wmask) (*wmask)[idx >> 6] |= bit;
                if (seen) continue;
                if (lock && !(rp[i] >> 24)) continue;
                brushBlendPixel(rp[i], argb, buildup);
            }
        }
        x = end + 1;
    }
}

void stampLine(Canvas& c, int x0, int y0, int x1, int y1, int radius, uint32_t argb, bool erase, bool buildup) {
    if (radius < 0) radius = 0;
    // 水平/垂直笔画用胶囊逐行填充(而不是稀疏圆点并集), 消除上下边缘每几像素一个的单像素突起
    if (y0 == y1) {
        c.rotateStampMaskAt(x1, y1);
        const int minX = std::min(x0, x1), maxX = std::max(x0, x1);
        const int r = radius;
        for (int dy = -r; dy <= r; dy++) {
            const int yy = y0 + dy;
            if ((unsigned)yy >= (unsigned)c.height()) continue;
            const int dx = (int)std::sqrt((double)(r * r - dy * dy));
            paintSpanRow(c, yy, minX - dx, maxX + dx, argb, erase, buildup);
        }
        c.markDirtyRect(std::max(0, minX - r), std::max(0, y0 - r),
                        std::min(c.width() - 1, maxX + r), std::min(c.height() - 1, y0 + r));
        return;
    }
    if (x0 == x1) {
        c.rotateStampMaskAt(x1, y1);
        const int minY = std::min(y0, y1), maxY = std::max(y0, y1);
        const int r = radius;
        for (int yy = std::max(0, minY - r); yy <= std::min(c.height() - 1, maxY + r); yy++) {
            const int d = yy < minY ? (minY - yy) : (yy > maxY ? (yy - maxY) : 0);
            const int dx = (int)std::sqrt((double)std::max(0, r * r - d * d));
            paintSpanRow(c, yy, x0 - dx, x0 + dx, argb, erase, buildup);
        }
        c.markDirtyRect(std::max(0, x0 - r), std::max(0, minY - r),
                        std::min(c.width() - 1, x0 + r), std::min(c.height() - 1, maxY + r));
        return;
    }

    const int dx = x1 - x0, dy = y1 - y0;
    const float len = std::sqrt((float)dx * dx + (float)dy * dy);
    const int steps = (int)(len / std::max(1.0f, radius * 0.5f)) + 1;
    for (int i = 0; i <= steps; i++) {
        const float t = (float)i / steps;
        stampCircle(c, (int)std::lround(x0 + dx * t), (int)std::lround(y0 + dy * t), radius, argb, erase, buildup);
    }
}

// ---------- 笔刷系统(预设画笔) ----------
static inline uint32_t brushRand() {
    static thread_local uint32_t s = 0x9E3779B9u;
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return s;
}

void stampBrushDab(Canvas& c, float cx, float cy, float radius, uint32_t argb,
                   int shape, int hardness, int aspectPct, int angleDeg, int flowPct,
                   float scatterPct, bool erase, bool buildup) {
    if (radius < 0.5f) radius = 0.5f;
    const bool defaultStyle = shape == 0 && hardness >= 100 && aspectPct >= 100 &&
                              ((angleDeg % 360) + 360) % 360 == 0 && flowPct >= 100 &&
                              scatterPct <= 0.001f;
    if (defaultStyle) {
        stampCircle(c, (int)std::lround(cx), (int)std::lround(cy), (int)radius, argb, erase, buildup);
        return;
    }
    if (scatterPct > 0.001f) {
        const float amp = radius * 2.0f * scatterPct / 100.0f;
        cx += ((float)(brushRand() % 2001) / 1000.0f - 1.0f) * amp;
        cy += ((float)(brushRand() % 2001) / 1000.0f - 1.0f) * amp;
    }
    uint32_t baseA = (argb >> 24) & 255u;
    baseA = baseA * (uint32_t)std::max(0, std::min(100, flowPct)) / 100u;
    if (baseA == 0) return;
    const uint32_t rgb = argb & 0x00FFFFFFu;
    const float aspect = std::max(0.05f, std::min(1.0f, (float)aspectPct / 100.0f));
    const float rx = std::max(0.5f, radius * aspect);
    const float ry = std::max(0.5f, radius);
    const float ang = (float)(((angleDeg % 360) + 360) % 360) * 3.14159265358979f / 180.0f;
    const float ca = std::cos(ang), sa = std::sin(ang);
    const float hard = std::max(0.0f, std::min(100.0f, (float)hardness)) / 100.0f;
    const float R = std::max(rx, ry) + 1.0f;
    const int bx0 = std::max(0, (int)std::floor(cx - R));
    const int bx1 = std::min(c.width() - 1, (int)std::ceil(cx + R));
    const int by0 = std::max(0, (int)std::floor(cy - R));
    const int by1 = std::min(c.height() - 1, (int)std::ceil(cy + R));
    if (bx0 > bx1 || by0 > by1) return;
    const bool lock = c.alphaLock() && !erase;
    const int T = Canvas::TILE;
    const int cw = c.tilesX();
    for (int y = by0; y <= by1; y++) {
        const int ty = y >> Canvas::TILE_SHIFT;
        const int rowOff = (y & (T - 1)) << Canvas::TILE_SHIFT;
        int x = bx0;
        while (x <= bx1) {
            const int tx = x >> Canvas::TILE_SHIFT;
            const int segEnd = std::min(bx1, ((tx + 1) << Canvas::TILE_SHIFT) - 1);
            const int ti = ty * cw + tx;
            uint32_t* t = c.tileForWrite(c.active(), ti);
            uint32_t* rp = t + rowOff;
            SkipSet ss;
            gatherSkipMasks(c, ti, ss);
            auto* wmask = writeMaskTile(c, ti);
            for (int xx = x; xx <= segEnd; xx++) {
                const float lx = (float)xx + 0.5f - cx;
                const float ly = (float)y + 0.5f - cy;
                const float u = (lx * ca + ly * sa) / rx;
                const float v = (-lx * sa + ly * ca) / ry;
                const float d = (shape == 1) ? std::max(std::fabs(u), std::fabs(v))
                                             : std::sqrt(u * u + v * v);
                if (d > 1.0f) continue;
                float f = 1.0f;
                if (hard < 0.999f && d > hard) {
                    const float q = (1.0f - d) / std::max(0.0001f, 1.0f - hard);
                    f = q * q * (3.0f - 2.0f * q);   // smoothstep 软边
                }
                const uint32_t a = (uint32_t)std::lround((float)baseA * f);
                if (a == 0) continue;
                const int idx = rowOff + (xx & (T - 1));
                const uint64_t bit = 1ull << (idx & 63);
                const bool seen = seenAny(ss, idx >> 6, bit);
                if (wmask) (*wmask)[idx >> 6] |= bit;
                if (seen) continue;
                uint32_t& p = rp[xx & (T - 1)];
                if (erase) { p = 0; continue; }
                if (lock && !(p >> 24)) continue;
                brushBlendPixel(p, (a << 24) | rgb, buildup);
            }
            x = segEnd + 1;
        }
    }
    c.markDirtyRect(bx0, by0, bx1, by1);
}

void stampBrushLine(Canvas& c, float x0, float y0, float x1, float y1, float radius, uint32_t argb,
                    int shape, int hardness, int spacingPct, float scatterPct,
                    int aspectPct, int angleDeg, int flowPct, bool erase, bool buildup) {
    if (radius < 0.5f) radius = 0.5f;
    const float dx = x1 - x0, dy = y1 - y0;
    const float len = std::sqrt(dx * dx + dy * dy);
    const float stepPx = std::max(1.0f, radius * 2.0f * (float)std::max(1, spacingPct) / 100.0f);
    const int n = std::max(1, (int)std::ceil(len / stepPx));
    for (int i = 0; i <= n; i++) {
        const float t = (float)i / (float)n;
        stampBrushDab(c, x0 + dx * t, y0 + dy * t, radius, argb,
                      shape, hardness, aspectPct, angleDeg, flowPct, scatterPct, erase, buildup);
    }
}

void fillRect(Canvas& c, int x, int y, int w, int h, uint32_t argb) {
    const int x0 = std::max(0, x), y0 = std::max(0, y);
    const int x1 = std::min(c.width(), x + w), y1 = std::min(c.height(), y + h);
    if (x0 >= x1 || y0 >= y1) return;
    const bool opaque = ((argb >> 24) & 255) == 255;
    const bool lock = c.alphaLock();
    const int cw = c.tilesX();
    for (int yy = y0; yy < y1; yy++) {
        const int ty = yy >> Canvas::TILE_SHIFT;
        const int tileRowOff = (yy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT;
        int xx = x0;
        while (xx < x1) {
            const int tx = xx >> Canvas::TILE_SHIFT;
            const int segEnd = std::min(x1, (tx + 1) << Canvas::TILE_SHIFT);
            uint32_t* t = c.tileForWrite(c.active(), ty * cw + tx);
            uint32_t* row = t + tileRowOff;
            for (int i = xx; i < segEnd; i++) {
                uint32_t& p = row[i & (Canvas::TILE - 1)];
                if (lock && !(p >> 24)) continue;   // 本层蒙版: 跳过透明像素
                if (opaque) p = argb; else overPixel(p, argb);
            }
            xx = segEnd;
        }
    }
    c.markDirtyRect(x0, y0, x1 - 1, y1 - 1);
}

// Aseprite algo_line_perfect 的像素归属判定(1 画布像素直线):
// 长轴每前进 1 像素只产生 1 个点, 副轴偏移 = floor(i*h/w), 与绘制循环完全同源。
bool linePathHit(int x0, int y0, int x1, int y1, int px, int py) {
    bool swap = std::abs(y1 - y0) > std::abs(x1 - x0);
    if (swap) {
        std::swap(x0, y0); std::swap(x1, y1);
        std::swap(px, py);
    }
    const int w = std::abs(x1 - x0) + 1;
    const int h = std::abs(y1 - y0) + 1;
    const int sx = x1 >= x0 ? 1 : -1;
    const int sy = y1 >= y0 ? 1 : -1;
    const int i = (px - x0) * sx;
    if (i < 0 || i >= w) return false;
    const int lineY = y0 + sy * (int)(((long long)i * h) / w);
    return py == lineY;
}

bool shapeHit(int type, bool filled, int x0, int y0, int x1, int y1,
              int px, int py, int thickness) {
    if (type != 0) {   // 直线保留起终点方向, 排序会画出镜像对角线
        if (x0 > x1) std::swap(x0, x1);
        if (y0 > y1) std::swap(y0, y1);
    }
    const int t = std::max(1, thickness);
    if (type == 0) {
        const int r = std::max(0, t / 2);           // 与 thickLinePixels 的线宽映射一致
        if (r == 0) return linePathHit(x0, y0, x1, y1, px, py);
        const double dx = (double)(x1 - x0), dy = (double)(y1 - y0);
        const double len2 = dx * dx + dy * dy;
        if (len2 < 1e-12) return std::abs(px - x0) <= r && std::abs(py - y0) <= r;
        // 平行四边形带: 投影在起终点之间 + 到无限长直线距离 <= r (butt 端头)
        const double u = (px - x0) * dx + (py - y0) * dy;
        if (u < -1e-9 || u > len2 + 1e-9) return false;
        const double cross = (px - x0) * dy - (py - y0) * dx;
        return std::abs(cross) <= (double)r * std::sqrt(len2) + 1e-4;
    }
    if (px < x0 - t || px > x1 + t || py < y0 - t || py > y1 + t) return false;
    if (type == 1) {
        // 先限制在外矩形内: 否则"边框"判定会把 x0-t..x1+t 的整圈外扩区也涂黑,
        // 预览看起来像整块黑框, 而落层只画矩形边 → 预览/结果不一致(实机复现)
        if (px < x0 || px > x1 || py < y0 || py > y1) return false;
        if (filled) return true;
        if (t * 2 >= std::min(x1 - x0 + 1, y1 - y0 + 1)) return true;  // 与 drawShape 同步退化
        return px < x0 + t || px > x1 - t || py < y0 + t || py > y1 - t;
    }
    // 椭圆: 与 drawShape 的逐行 span 完全同一几何(外椭圆减内椭圆)
    const float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
    const float a = std::max(0.5f, (x1 - x0) * 0.5f), b = std::max(0.5f, (y1 - y0) * 0.5f);
    if (py < y0 || py > y1) return false;
    // 用像素行中心到椭圆中心的距离: 顶/底行也有正常宽度, 不再只画中心 1px 凸起
    const float dyo = std::max(0.0f, std::abs((float)py - cy) - 0.5f);
    if (dyo > b) return false;
    const float ny = dyo / b;
    const float half = a * std::sqrt(std::max(0.0f, 1.0f - ny * ny));
    const int xl = (int)std::lround(cx - half), xr = (int)std::lround(cx + half);
    if (px < xl || px > xr) return false;
    if (filled) return true;
    const float ia = a - (float)t, ib = b - (float)t;
    if (ia <= 0.5f || ib <= 0.5f) return true;      // 太细: 整行都是边框
    const float dyi = std::max(0.0f, std::abs((float)py - cy) - 0.5f);
    if (dyi >= ib) return true;                     // 内椭圆够不到的顶/底行
    const float ny2 = dyi / ib;
    const float ihalf = ia * std::sqrt(std::max(0.0f, 1.0f - ny2 * ny2));
    const int il = (int)std::lround(cx - ihalf) + 1, ir = (int)std::lround(cx + ihalf) - 1;
    return !(px >= il && px <= ir);
}

// 1 画布像素直线: Aseprite algo_line_perfect(长轴逐像素, 副轴误差累积)。
// 旧实现按 1px 间距盖单点圆, 浅角度会多出/漏掉像素, 不是像素完美线。
static void drawPerfectLine(Canvas& c, int x0, int y0, int x1, int y1, uint32_t argb) {
    const bool swap = std::abs(y1 - y0) > std::abs(x1 - x0);
    if (swap) { std::swap(x0, y0); std::swap(x1, y1); }
    const int w = std::abs(x1 - x0) + 1;
    const int h = std::abs(y1 - y0) + 1;
    const int sx = x1 >= x0 ? 1 : -1;
    const int sy = y1 >= y0 ? 1 : -1;
    int e = 0, y = y0;
    for (int i = 0; i < w; i++) {
        const int x = x0 + i * sx;
        if (swap) paintPixel(c, c.active(), y, x, argb, false);
        else paintPixel(c, c.active(), x, y, argb, false);
        e += h;
        if (e >= w) { y += sy; e -= w; }
    }
}

void drawShape(Canvas& c, int type, bool filled, int x0, int y0, int x1, int y1,
               int thickness, uint32_t argb) {
    if (type != 0) {   // 直线保留起终点方向
        if (x0 > x1) std::swap(x0, x1);
        if (y0 > y1) std::swap(y0, y1);
    }
    const int t = std::max(1, thickness);
    if (type == 0) {
        // 1px: Aseprite 像素完美线; 粗线: 平行四边形带(预览/落层同一像素集合)
        if (t <= 1) { drawPerfectLine(c, x0, y0, x1, y1, argb); return; }
        std::vector<int> xy;
        thickLinePixels(x0, y0, x1, y1, t, xy);
        for (size_t i = 0; i + 1 < xy.size(); i += 2)
            paintPixel(c, c.active(), xy[i], xy[i + 1], argb, false);
        return;
    }
    if (type == 1) {
        if (filled) { fillRect(c, x0, y0, x1 - x0 + 1, y1 - y0 + 1, argb); return; }
        if (t * 2 >= std::min(x1 - x0 + 1, y1 - y0 + 1)) {
            fillRect(c, x0, y0, x1 - x0 + 1, y1 - y0 + 1, argb);
            return;
        }
        fillRect(c, x0, y0, x1 - x0 + 1, t, argb);
        fillRect(c, x0, y1 - t + 1, x1 - x0 + 1, t, argb);
        const int midY = y0 + t, midH = (y1 - t + 1) - midY;
        if (midH > 0) {
            fillRect(c, x0, midY, t, midH, argb);
            fillRect(c, x1 - t + 1, midY, t, midH, argb);
        }
        return;
    }
    // 椭圆: 逐行 span; 内椭圆按线宽 t 内缩, 与 shapeHit 同一几何
    const float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
    const float a = std::max(0.5f, (x1 - x0) * 0.5f), b = std::max(0.5f, (y1 - y0) * 0.5f);
    const float ia = a - (float)t, ib = b - (float)t;
    for (int yy = y0; yy <= y1; yy++) {
        const float dyo = std::max(0.0f, std::abs((float)yy - cy) - 0.5f);
        if (dyo > b) continue;
        const float ny = dyo / b;
        const float half = a * std::sqrt(std::max(0.0f, 1.0f - ny * ny));
        const int xl = (int)std::lround(cx - half), xr = (int)std::lround(cx + half);
        if (filled || ia <= 0.5f || ib <= 0.5f) {
            if (xr >= xl) fillRect(c, xl, yy, xr - xl + 1, 1, argb);
            continue;
        }
        const float dyi = std::max(0.0f, std::abs((float)yy - cy) - 0.5f);
        if (dyi >= ib) {
            if (xr >= xl) fillRect(c, xl, yy, xr - xl + 1, 1, argb);
            continue;
        }
        const float ny2 = dyi / ib;
        const float ihalf = ia * std::sqrt(std::max(0.0f, 1.0f - ny2 * ny2));
        const int il = (int)std::lround(cx - ihalf) + 1, ir = (int)std::lround(cx + ihalf) - 1;
        if (il > xl) fillRect(c, xl, yy, il - xl, 1, argb);
        if (xr > ir) fillRect(c, ir + 1, yy, xr - ir, 1, argb);
    }
}

namespace {

struct PolyEdge { int y0, y1; float x0, dx; };

// even-odd 扫描线; 像素中心采样; 回调 (y, x0, x1) 闭区间
template <class F>
void polySpans(const std::vector<int>& pts, int W, int H, F&& cb) {
    const int n = (int)pts.size() / 2;
    if (n < 3) return;
    std::vector<PolyEdge> edges;
    edges.reserve((size_t)n);
    int minY = INT_MAX, maxY = INT_MIN;
    for (int i = 0; i < n; i++) {
        const int ax = pts[(size_t)i * 2], ay = pts[(size_t)i * 2 + 1];
        const int bx = pts[(size_t)((i + 1) % n) * 2], by = pts[(size_t)((i + 1) % n) * 2 + 1];
        if (ay == by) continue;
        PolyEdge e;
        if (ay < by) { e.y0 = ay; e.y1 = by; e.x0 = (float)ax; e.dx = (float)(bx - ax) / (float)(by - ay); }
        else { e.y0 = by; e.y1 = ay; e.x0 = (float)bx; e.dx = (float)(ax - bx) / (float)(ay - by); }
        edges.push_back(e);
        minY = std::min(minY, e.y0);
        maxY = std::max(maxY, e.y1);
    }
    if (edges.empty()) return;
    minY = std::max(0, minY);
    maxY = std::min(H - 1, maxY);
    std::vector<float> xs;
    for (int y = minY; y <= maxY; y++) {
        const float sy = (float)y + 0.5f;
        xs.clear();
        for (const auto& e : edges)
            if (sy >= (float)e.y0 && sy < (float)e.y1) xs.push_back(e.x0 + (sy - (float)e.y0) * e.dx);
        if (xs.size() < 2) continue;
        std::sort(xs.begin(), xs.end());
        for (size_t i = 0; i + 1 < xs.size(); i += 2) {
            int x0 = (int)std::ceil(xs[i] - 0.5f);
            int x1 = (int)std::floor(xs[i + 1] - 0.5f);
            x0 = std::max(0, x0);
            x1 = std::min(W - 1, x1);
            if (x1 >= x0) cb(y, x0, x1);
        }
    }
}

// 一段水平 span 写进活动层(支持擦除/alphaLock)
void writeSpan(Canvas& c, int y, int x0, int x1, uint32_t argb, bool erase) {
    if (x0 > x1) return;
    const int T = Canvas::TILE;
    const int cw = c.tilesX();
    const bool opaque = erase || ((argb >> 24) & 255) == 255;
    const bool lock = c.alphaLock() && !erase;
    int x = x0;
    while (x <= x1) {
        const int tx = x >> Canvas::TILE_SHIFT;
        const int segEnd = std::min(x1, ((tx + 1) << Canvas::TILE_SHIFT) - 1);
        uint32_t* t = c.tileForWrite(c.active(), (y >> Canvas::TILE_SHIFT) * cw + tx);
        uint32_t* rp = t + (((size_t)(y & (T - 1)) << Canvas::TILE_SHIFT) + (x & (T - 1)));
        const int n = segEnd - x + 1;
        if (erase) {
            memset(rp, 0, (size_t)n * 4);
        } else if (lock) {
            for (int i = 0; i < n; i++)
                if (rp[i] >> 24) overPixel(rp[i], argb);
        } else if (opaque) {
            for (int i = 0; i < n; i++) rp[i] = argb;
        } else {
            for (int i = 0; i < n; i++) overPixel(rp[i], argb);
        }
        x = segEnd + 1;
    }
}

} // namespace

void paintPixel(Canvas& c, int layer, int x, int y, uint32_t argb, bool erase) {
    if ((unsigned)x >= (unsigned)c.width() || (unsigned)y >= (unsigned)c.height()) return;
    const bool lock = c.alphaLock() && !erase;
    if (lock) {
        const uint32_t old = c.getPixelL(layer, x, y);
        if (!(old >> 24)) return;
    }
    uint32_t* t = c.tileForWrite(layer, (y >> Canvas::TILE_SHIFT) * c.tilesX() + (x >> Canvas::TILE_SHIFT));
    uint32_t& d = t[((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) + (x & (Canvas::TILE - 1))];
    if (erase) d = 0;
    else overPixel(d, argb);
    c.markDirtyRect(x, y, x, y);
}

void setPixelRaw(Canvas& c, int layer, int x, int y, uint32_t argb) {
    if ((unsigned)x >= (unsigned)c.width() || (unsigned)y >= (unsigned)c.height()) return;
    uint32_t* t = c.tileForWrite(layer, (y >> Canvas::TILE_SHIFT) * c.tilesX() + (x >> Canvas::TILE_SHIFT));
    t[((size_t)(y & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT) + (x & (Canvas::TILE - 1))] = argb;
    c.markDirtyRect(x, y, x, y);
}

void fillPolygon(Canvas& c, const std::vector<int>& pts, uint32_t argb, bool erase) {
    int minX = INT_MAX, minY = INT_MAX, maxX = INT_MIN, maxY = INT_MIN;
    for (size_t i = 0; i + 1 < pts.size(); i += 2) {
        minX = std::min(minX, pts[i]); maxX = std::max(maxX, pts[i]);
        minY = std::min(minY, pts[i + 1]); maxY = std::max(maxY, pts[i + 1]);
    }
    if (minX > maxX) return;
    polySpans(pts, c.width(), c.height(), [&](int y, int x0, int x1) {
        writeSpan(c, y, x0, x1, argb, erase);
    });
    c.markDirtyRect(minX, minY, maxX, maxY);
}

void strokePolygonOutline(Canvas& c, const std::vector<int>& pts, uint32_t argb, bool erase) {
    const int n = (int)pts.size() / 2;
    if (n < 3) return;
    int minX = INT_MAX, minY = INT_MAX, maxX = INT_MIN, maxY = INT_MIN;
    for (int i = 0; i < n; i++) {
        minX = std::min(minX, pts[(size_t)i * 2]); maxX = std::max(maxX, pts[(size_t)i * 2]);
        minY = std::min(minY, pts[(size_t)i * 2 + 1]); maxY = std::max(maxY, pts[(size_t)i * 2 + 1]);
    }
    minX = std::max(0, minX); minY = std::max(0, minY);
    maxX = std::min(c.width() - 1, maxX); maxY = std::min(c.height() - 1, maxY);
    const long long bw = (long long)maxX - minX + 1, bh = (long long)maxY - minY + 1;
    if (bw <= 0 || bh <= 0) return;
    if (bw * bh > 32ll * 1024 * 1024) {
        for (int i = 0; i < n; i++)
            stampLine(c, pts[(size_t)i * 2], pts[(size_t)i * 2 + 1],
                      pts[(size_t)((i + 1) % n) * 2], pts[(size_t)((i + 1) % n) * 2 + 1], 0, argb, erase);
        return;
    }
    std::vector<uint8_t> mask((size_t)(bw * bh), 0);
    polySpans(pts, c.width(), c.height(), [&](int y, int x0, int x1) {
        if (y < minY || y > maxY) return;
        const int a = std::max(x0, minX), b = std::min(x1, maxX);
        if (b < a) return;
        memset(mask.data() + (size_t)(y - minY) * bw + (a - minX), 1, (size_t)(b - a + 1));
    });
    for (long long yy = 0; yy < bh; yy++) {
        const uint8_t* row = mask.data() + (size_t)yy * bw;
        const uint8_t* up = yy > 0 ? row - bw : nullptr;
        const uint8_t* dn = yy + 1 < bh ? row + bw : nullptr;
        for (long long xx = 0; xx < bw; xx++) {
            if (!row[xx]) continue;
            const bool edge = (xx == 0 || !row[xx - 1]) || (xx + 1 >= bw || !row[xx + 1]) ||
                              (!up || !up[xx]) || (!dn || !dn[xx]);
            if (edge) paintPixel(c, c.active(), (int)(minX + xx), (int)(minY + yy), argb, erase);
        }
    }
    c.markDirtyRect(minX, minY, maxX, maxY);
}

void leafPath(int x0, int y0, int x1, int y1, float widthRatio, std::vector<int>& out) {
    out.clear();
    const float dx = (float)(x1 - x0), dy = (float)(y1 - y0);
    const float len = std::sqrt(dx * dx + dy * dy);
    if (len < 1.0f) return;
    const float nx = -dy / len, ny = dx / len;          // 法线
    const float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f;
    const float w = std::max(0.05f, std::min(widthRatio, 1.0f)) * len;
    const float c1x = mx + nx * w, c1y = my + ny * w;
    const float c2x = mx - nx * w, c2y = my - ny * w;
    const int N = std::max(8, std::min(512, (int)std::ceil(len * 0.5f) + 8));
    out.reserve((size_t)(2 * (N + 1)) * 2);
    auto quad = [&](float ax, float ay, float ccx, float ccy, float bx, float by, float t) {
        const float u = 1.0f - t;
        const float px = u * u * ax + 2.0f * u * t * ccx + t * t * bx;
        const float py = u * u * ay + 2.0f * u * t * ccy + t * t * by;
        out.push_back((int)std::lround(px));
        out.push_back((int)std::lround(py));
    };
    for (int i = 0; i <= N; i++) {
        const float t = (float)i / (float)N;
        quad((float)x0, (float)y0, c1x, c1y, (float)x1, (float)y1, t);
    }
    for (int i = N; i >= 0; i--) {
        const float t = (float)i / (float)N;
        quad((float)x0, (float)y0, c2x, c2y, (float)x1, (float)y1, t);
    }
}

void drawLeaf(Canvas& c, int x0, int y0, int x1, int y1, float widthRatio,
              bool filled, uint32_t argb, bool erase, int brushThickness) {
    std::vector<int> pts;
    leafPath(x0, y0, x1, y1, widthRatio, pts);
    if (pts.size() < 6) return;
    if (filled) {
        fillPolygon(c, pts, argb, erase);
    } else if (brushThickness >= 2) {
        // 空心: 按画笔宽度描边(低缩放下 1 画布像素的边会看不见)
        // 低缩放时 brushRadius 可达上千画布像素, 逐段 stampLine 会爆(F×圆面积);
        // 笔宽接近叶宽时直接填充, 否则抽稀段数并封顶笔宽
        const int n = (int)pts.size() / 2;
        int bx0 = pts[0], by0 = pts[1], bx1 = pts[0], by1 = pts[1];
        for (int i = 0; i < n; i++) {
            bx0 = std::min(bx0, pts[(size_t)i * 2]); bx1 = std::max(bx1, pts[(size_t)i * 2]);
            by0 = std::min(by0, pts[(size_t)i * 2 + 1]); by1 = std::max(by1, pts[(size_t)i * 2 + 1]);
        }
        const int bt = std::min(brushThickness, 256);
        const int br = std::max(0, bt / 2);          // 圆形笔刷半径→实际厚度≈2r+1
        const int leafMin = std::max(1, std::min(bx1 - bx0, by1 - by0));
        if (bt >= leafMin) {
            fillPolygon(c, pts, argb, erase);
            return;
        }
        const int stride = std::max(1, n / 256);
        for (int i = 0; i < n; i += stride) {
            const int j = (i + stride) % n;
            const int ax = pts[(size_t)i * 2], ay = pts[(size_t)i * 2 + 1];
            const int bx = pts[(size_t)j * 2], by = pts[(size_t)j * 2 + 1];
            if (ax == bx && ay == by) continue;
            stampLine(c, ax, ay, bx, by, br, argb, erase);
        }
    } else {
        strokePolygonOutline(c, pts, argb, erase);
    }
}

void clearLayer(Canvas& c, int layer) {
    // 稀疏存储: 直接释放 tile, 不能逐个分配再清零(16383² 一层会吃掉 1GB)
    for (int ti = 0; ti < c.tilesX() * c.tilesY(); ti++) {
        c.releaseTile(layer, ti);
    }
    c.markDirtyRect(0, 0, c.width() - 1, c.height() - 1);
}

int wandFillFast(const Canvas& c, int layer, int x, int y, std::vector<uint64_t>& mask) {
    const int w = c.width(), h = c.height();
    mask.assign(((size_t)w * h + 63) / 64, 0);
    if ((unsigned)x >= (unsigned)w || (unsigned)y >= (unsigned)h) return 0;
    const uint32_t seed = c.getPixel(x, y);
    auto has = [&](size_t j) { return (mask[j >> 6] >> (j & 63)) & 1ULL; };
    auto set = [&](size_t j) { mask[j >> 6] |= 1ULL << (j & 63); };
    const int cw = c.tilesX();

    // 行级 tile 缓存: 同一行只在跨 tile 时重查指针
    struct Row {
        const Canvas& c; int layer, cw, yy, lastTx = -1;
        const uint32_t* base = nullptr;
        uint32_t at(int xx) {
            const int tx = xx >> Canvas::TILE_SHIFT;
            if (tx != lastTx) {
                lastTx = tx;
                const uint32_t* t = c.tile(layer, (yy >> Canvas::TILE_SHIFT) * cw + tx);
                base = t ? t + (((yy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT)) : nullptr;
            }
            return base ? base[xx & (Canvas::TILE - 1)] : 0u;
        }
    };

    std::vector<int> stack;
    stack.reserve(1024);
    stack.push_back(y * w + x);
    int count = 0;
    while (!stack.empty()) {
        const int idx = stack.back(); stack.pop_back();
        const int yy = idx / w, xx = idx % w;
        const size_t base = (size_t)yy * w;
        if (has((size_t)idx)) continue;
        Row row{ c, layer, cw, yy };
        if (row.at(xx) != seed) continue;
        int left = xx, right = xx;
        while (left > 0 && row.at(left - 1) == seed) left--;
        while (right < w - 1 && row.at(right + 1) == seed) right++;
        for (int i = left; i <= right; i++) { set(base + i); count++; }
        for (int ny = yy - 1; ny <= yy + 1; ny += 2) {
            if (ny < 0 || ny >= h) continue;
            const size_t nbase = (size_t)ny * w;
            Row nrow{ c, layer, cw, ny };
            bool inRun = false;
            for (int i = left; i <= right; i++) {
                const size_t j = nbase + i;
                const bool ok = (nrow.at(i) == seed) && !has(j);
                if (ok && !inRun) { stack.push_back((int)j); inRun = true; }
                else if (!ok) inRun = false;
            }
        }
    }
    return count;
}

raster::FillResult floodFill(Canvas& c, int layer, int x, int y, uint32_t color,
                             long long maxFlatPixels, std::vector<Canvas::Span>* spans,
                             int tolerance) {
    FillResult out;
    const int w = c.width(), h = c.height();
    if ((unsigned)x >= (unsigned)w || (unsigned)y >= (unsigned)h) return out;
    const uint32_t seed = c.getPixelL(layer, x, y);
    if (seed == color) return out;
    // 容差: 每通道最大差 <= tolerance(含 alpha)
    auto same = [&](uint32_t v) -> bool {
        if (tolerance <= 0) return v == seed;
        const int dr = std::abs((int)(v & 255) - (int)(seed & 255));
        const int dg = std::abs((int)((v >> 8) & 255) - (int)((seed >> 8) & 255));
        const int db = std::abs((int)((v >> 16) & 255) - (int)((seed >> 16) & 255));
        const int da = std::abs((int)(v >> 24) - (int)(seed >> 24));
        return dr <= tolerance && dg <= tolerance && db <= tolerance && da <= tolerance;
    };
    const int cw = c.tilesX();
    const int TS = Canvas::TILE, SH = Canvas::TILE_SHIFT;

    std::vector<long long> stack;
    stack.reserve(4096);
    long long flat = 0, area = 0;

    // 把 [xa,xb] 行中的种子 run(连续段)起点入栈
    auto pushRowRuns = [&](int ny, int xa, int xb) {
        if (ny < 0 || ny >= h) return;
        const int rowTi = (ny >> SH) * cw;
        bool inRun = false;
        int i = xa;
        while (i <= xb) {
            const int tx = i >> SH;
            const int ti = rowTi + tx;
            const int te = std::min(xb, std::min(w - 1, ((tx + 1) << SH) - 1));
            if (c.isSolidTile(layer, ti) || !c.tile(layer, ti)) {
                const uint32_t col = c.isSolidTile(layer, ti) ? c.solidTileColor(layer, ti) : 0u;
                const bool ok = (col == seed);
                if (ok && !inRun) { stack.push_back((long long)ny * w + i); inRun = true; }
                else if (!ok) inRun = false;
            } else {
                for (int j = i; j <= te; j++) {
                    const bool ok = same(c.getPixelL(layer, j, ny));
                    if (ok && !inRun) { stack.push_back((long long)ny * w + j); inRun = true; }
                    else if (!ok) inRun = false;
                }
            }
            i = te + 1;
        }
    };

    // 整块同色: 直接转 solid(不分配像素); 再把该块上下边界行入栈, 保证跨块传播
    auto convertTile = [&](int ti) {
        const int tx = ti % cw, ty = ti / cw;
        const int x0 = tx << SH, x1 = std::min(w - 1, ((tx + 1) << SH) - 1);
        const int y0 = ty << SH, y1 = std::min(h - 1, y0 + TS - 1);
        c.setSolidTile(layer, ti, color);
        area += (long long)(x1 - x0 + 1) * (long long)(y1 - y0 + 1);
        pushRowRuns(y0 - 1, x0, x1);
        pushRowRuns(y1 + 1, x0, x1);
    };

    // 行内扩展: 整块同色/空块直接跨整块, 混合块逐像素
    auto runEdges = [&](int yy, int xx, int* Lp, int* Rp) {
        int l = xx, r = xx;
        const int rowTi = (yy >> SH) * cw;
        while (l > 0) {
            const int tx = (l - 1) >> SH;
            const int ti = rowTi + tx;
            const int ts = tx << SH;
            if (c.isSolidTile(layer, ti)) {
                if (same(c.solidTileColor(layer, ti))) { l = ts; continue; }
                break;
            }
            if (!c.tile(layer, ti)) {
                if (same(0u)) { l = ts; continue; }
                break;
            }
            while (l > ts && same(c.getPixelL(layer, l - 1, yy))) l--;
            if (l > ts) break;
        }
        while (r < w - 1) {
            const int tx = (r + 1) >> SH;
            const int ti = rowTi + tx;
            const int te = std::min(w - 1, ((tx + 1) << SH) - 1);
            if (c.isSolidTile(layer, ti)) {
                if (same(c.solidTileColor(layer, ti))) { r = te; continue; }
                break;
            }
            if (!c.tile(layer, ti)) {
                if (same(0u)) { r = te; continue; }
                break;
            }
            while (r < te && same(c.getPixelL(layer, r + 1, yy))) r++;
            if (r < te) break;
        }
        *Lp = l; *Rp = r;
    };

    auto writeRun = [&](int yy, int l, int r) {
        int xx = l;
        const int rowTi = (yy >> SH) * cw;
        while (xx <= r) {
            const int tx = xx >> SH;
            const int ti = rowTi + tx;
            const int te = std::min(w - 1, ((tx + 1) << SH) - 1);
            const int xe = std::min(r, te);
            if (c.isSolidTile(layer, ti) || !c.tile(layer, ti)) {
                convertTile(ti);
            } else {
                uint32_t* t = c.tileForWrite(layer, ti);
                uint32_t* p = t + ((size_t)(yy & (TS - 1)) << SH) + (xx & (TS - 1));
                const int n = xe - xx + 1;
                for (int i = 0; i < n; i++) p[i] = color;
                c.markDirtyTile(ti);
                if (spans) spans->push_back({ yy, xx, xe });
                flat += n;
                area += n;
            }
            xx = xe + 1;
        }
    };

    stack.push_back((long long)y * w + x);
    while (!stack.empty()) {
        const long long idx = stack.back(); stack.pop_back();
        const int yy = (int)(idx / w), xx = (int)(idx % w);
        if (!same(c.getPixelL(layer, xx, yy))) continue;
        int L, R;
        runEdges(yy, xx, &L, &R);
        if (L > R) continue;
        writeRun(yy, L, R);
        if (flat > maxFlatPixels) {
            out.capped = true;
            out.pixels = area;
            return out;
        }
        pushRowRuns(yy - 1, L, R);
        pushRowRuns(yy + 1, L, R);
    }
    out.pixels = area;
    return out;
}


// ---- 形状像素集合: 预览和落层都直接用这些像素(不再用独立预览图形) ----
void linePathPixels(int x0, int y0, int x1, int y1, std::vector<int>& out) {
    out.clear();
    const bool swap = std::abs(y1 - y0) > std::abs(x1 - x0);
    if (swap) { std::swap(x0, y0); std::swap(x1, y1); }
    const int w = std::abs(x1 - x0) + 1;
    const int h = std::abs(y1 - y0) + 1;
    const int sx = x1 >= x0 ? 1 : -1;
    const int sy = y1 >= y0 ? 1 : -1;
    int e = 0, y = y0;
    out.reserve((size_t)w * 2);
    for (int i = 0; i < w; i++) {
        const int x = x0 + i * sx;
        if (swap) { out.push_back(y); out.push_back(x); }
        else { out.push_back(x); out.push_back(y); }
        e += h;
        if (e >= w) { y += sy; e -= w; }
    }
}

void ellipsePathPixels(int x0, int y0, int x1, int y1, std::vector<int>& out) {
    out.clear();
    if (x0 > x1) std::swap(x0, x1);
    if (y0 > y1) std::swap(y0, y1);
    const float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
    const float a = std::max(0.5f, (x1 - x0) * 0.5f), b = std::max(0.5f, (y1 - y0) * 0.5f);
    for (int yy = y0; yy <= y1; yy++) {
        const float dyo = std::max(0.0f, std::abs((float)yy - cy) - 0.5f);
        if (dyo > b) continue;
        const float ny = dyo / b;
        const float half = a * std::sqrt(std::max(0.0f, 1.0f - ny * ny));
        const int xl = (int)std::lround(cx - half), xr = (int)std::lround(cx + half);
        out.push_back(xl); out.push_back(yy);
        if (xr != xl) { out.push_back(xr); out.push_back(yy); }
    }
}

void polylinePixels(const std::vector<int>& pts, bool closed, std::vector<int>& out) {
    out.clear();
    const int n = (int)pts.size() / 2;
    if (n < 2) return;
    const int segs = closed ? n : n - 1;
    for (int s = 0; s < segs; s++) {
        const int i = s * 2, j = ((s + 1) % n) * 2;
        int x0 = pts[(size_t)i], y0 = pts[(size_t)i + 1];
        const int x1 = pts[(size_t)j], y1 = pts[(size_t)j + 1];
        int dx = std::abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -std::abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        for (;;) {
            out.push_back(x0); out.push_back(y0);
            if (x0 == x1 && y0 == y1) break;
            const int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }
}

void polygonFillPixels(const std::vector<int>& pts, int W, int H, std::vector<int>& out) {
    out.clear();
    if (W <= 0 || H <= 0) return;
    polySpans(pts, W, H, [&](int y, int a, int b) {
        for (int x = a; x <= b; x++) { out.push_back(x); out.push_back(y); }
    });
}

// 椭圆像素集合: 填充=整行 span; 空心=按线宽 t 的行内环带(与旧 drawShape 同一几何)。
// 之前用"每行左右两点当多边形"做填充/加粗, 多边形自交会出现中间中空/乱线, 已废弃。
void ellipseFillPixels(int x0, int y0, int x1, int y1, std::vector<int>& out) {
    out.clear();
    if (x0 > x1) std::swap(x0, x1);
    if (y0 > y1) std::swap(y0, y1);
    const float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
    const float a = std::max(0.5f, (x1 - x0) * 0.5f), b = std::max(0.5f, (y1 - y0) * 0.5f);
    for (int yy = y0; yy <= y1; yy++) {
        const float dyo = std::max(0.0f, std::abs((float)yy - cy) - 0.5f);
        if (dyo > b) continue;
        const float ny = dyo / b;
        const float half = a * std::sqrt(std::max(0.0f, 1.0f - ny * ny));
        const int xl = (int)std::lround(cx - half), xr = (int)std::lround(cx + half);
        for (int x = xl; x <= xr; x++) { out.push_back(x); out.push_back(yy); }
    }
}

void ellipseOutlinePixels(int x0, int y0, int x1, int y1, int thickness, std::vector<int>& out) {
    out.clear();
    if (x0 > x1) std::swap(x0, x1);
    if (y0 > y1) std::swap(y0, y1);
    const int t = std::max(1, thickness);
    const float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
    const float a = std::max(0.5f, (x1 - x0) * 0.5f), b = std::max(0.5f, (y1 - y0) * 0.5f);
    const float ia = a - (float)t, ib = b - (float)t;
    for (int yy = y0; yy <= y1; yy++) {
        const float dyo = std::max(0.0f, std::abs((float)yy - cy) - 0.5f);
        if (dyo > b) continue;
        const float ny = dyo / b;
        const float half = a * std::sqrt(std::max(0.0f, 1.0f - ny * ny));
        const int xl = (int)std::lround(cx - half), xr = (int)std::lround(cx + half);
        if (ia <= 0.5f || ib <= 0.5f) {
            for (int x = xl; x <= xr; x++) { out.push_back(x); out.push_back(yy); }
            continue;
        }
        const float dyi = std::max(0.0f, std::abs((float)yy - cy) - 0.5f);
        if (dyi >= ib) {
            for (int x = xl; x <= xr; x++) { out.push_back(x); out.push_back(yy); }
            continue;
        }
        const float ny2 = dyi / ib;
        const float ihalf = ia * std::sqrt(std::max(0.0f, 1.0f - ny2 * ny2));
        const int il = (int)std::lround(cx - ihalf) + 1, ir = (int)std::lround(cx + ihalf) - 1;
        for (int x = xl; x < il; x++) { out.push_back(x); out.push_back(yy); }
        for (int x = ir + 1; x <= xr; x++) { out.push_back(x); out.push_back(yy); }
    }
}

// 粗直线 = 两条平行边之间的平行四边形带(butt 平切端头)。
// 像素(x,y)在带内 iff |cross(P-A,d)| <= r*|d| 且 0 <= dot(P-A,d) <= |d|²;
// 逐行解出 x 区间整段收集, 不再用圆点盖章/半圆端头:
// 大线宽下边缘平直, 端头止于起终点, 不会鼓出拖拽框外(参考粗离散线=两条平行 naive line 之间填充)。
void thickLinePixels(int x0, int y0, int x1, int y1, int thickness, std::vector<int>& out) {
    out.clear();
    const int t = std::max(1, thickness);
    if (t <= 1) { linePathPixels(x0, y0, x1, y1, out); return; }
    const double r = (double)(t / 2);
    const double dx = (double)x1 - x0, dy = (double)y1 - y0;
    const double L2 = dx * dx + dy * dy;
    if (L2 < 1e-12) {   // 起终点重合: 方形笔端
        const int rr = std::max(0, t / 2);
        for (int yy = -rr; yy <= rr; yy++)
            for (int xx = -rr; xx <= rr; xx++) { out.push_back(x0 + xx); out.push_back(y0 + yy); }
        return;
    }
    const double hw = r * std::sqrt(L2);   // |cross| 上限
    const int yLo = std::min(y0, y1) - (int)std::ceil(r);
    const int yHi = std::max(y0, y1) + (int)std::ceil(r);
    for (int y = yLo; y <= yHi; y++) {
        double lo = -1e18, hi = 1e18;
        // 带宽约束(距无限长直线 <= r)
        if (std::abs(dy) < 1e-12) {
            // 水平线: 只约束行(y0-r..y0+r)
            if (std::abs((double)(y - y0) * dx) > hw + 1e-9) continue;
        } else {
            const double c = (double)(y - y0) * dx;
            double a = x0 + (c - hw) / dy, b = x0 + (c + hw) / dy;
            if (a > b) std::swap(a, b);
            lo = std::max(lo, a); hi = std::min(hi, b);
        }
        // 端头约束(投影落在起终点之间), 平切成 butt 端
        if (std::abs(dx) < 1e-12) {
            const double v = (double)(y - y0) * dy;
            if (v < -1e-9 || v > L2 + 1e-9) continue;
        } else {
            const double c = (double)(y - y0) * dy;
            double a = x0 - c / dx, b = x0 + (L2 - c) / dx;
            if (a > b) std::swap(a, b);
            lo = std::max(lo, a); hi = std::min(hi, b);
        }
        if (lo > hi) continue;
        const int a = (int)std::ceil(lo - 1e-9), b = (int)std::floor(hi + 1e-9);
        for (int x = a; x <= b; x++) { out.push_back(x); out.push_back(y); }
    }
}

// 矩形边框: 四条轴对齐边带(全部落在矩形内, 厚度向内), 方角、无外扩。
// 与旧项目 GetRectPixels/drawShape 同构; 2*t 达到短边时退化成实心矩形。
void rectOutlinePixels(int x0, int y0, int x1, int y1, int thickness, std::vector<int>& out) {
    out.clear();
    if (x0 > x1) std::swap(x0, x1);
    if (y0 > y1) std::swap(y0, y1);
    const int t = std::max(1, thickness);
    const int w = x1 - x0 + 1, h = y1 - y0 + 1;
    if (2 * t >= std::min(w, h)) {
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) { out.push_back(x); out.push_back(y); }
        return;
    }
    for (int y = y0; y < y0 + t; y++)
        for (int x = x0; x <= x1; x++) { out.push_back(x); out.push_back(y); }
    for (int y = y1 - t + 1; y <= y1; y++)
        for (int x = x0; x <= x1; x++) { out.push_back(x); out.push_back(y); }
    for (int y = y0 + t; y <= y1 - t; y++) {
        for (int x = x0; x < x0 + t; x++) { out.push_back(x); out.push_back(y); }
        for (int x = x1 - t + 1; x <= x1; x++) { out.push_back(x); out.push_back(y); }
    }
}

void thickenPathPixels(const std::vector<int>& xy, int radius, std::vector<int>& out) {
    out.clear();
    if (radius <= 0) { out = xy; return; }
    const int r = std::min(radius, 512);
    out.reserve(xy.size() * (size_t)((2 * r + 1) * (2 * r + 1) / 2 + 1));
    for (size_t i = 0; i + 1 < xy.size(); i += 2) {
        const int cx = xy[i], cy = xy[i + 1];
        for (int dy = -r; dy <= r; dy++)
            for (int dx = -r; dx <= r; dx++)
                if (dx * dx + dy * dy <= r * r) { out.push_back(cx + dx); out.push_back(cy + dy); }
    }
}

} // namespace endpix::raster
