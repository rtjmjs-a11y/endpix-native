#pragma once
#include <cstdint>
#include <vector>
#include "../canvas/canvas.h"

namespace endpix::raster {

void over8(uint8_t* dst, const uint8_t* src);
void overPixel(uint32_t& dst, uint32_t src);
// 图层混合: dst=底色, src=上层色(都是预乘 ABGR), mode 0正常 1正片叠底 2滤色 3叠加
// 4变暗 5变亮 6差值 7相加 8柔光 9强光; 返回合成后的预乘像素
uint32_t blendPixel(uint32_t dst, uint32_t src, int mode);

// 图层不透明度: 画布像素是预乘 alpha, 整体乘 alpha 即可
inline uint32_t applyLayerOpacity(uint32_t v, float o) {
    if (!v || o >= 0.999f) return v;
    if (o <= 0.001f) return 0;
    const uint32_t a = (uint32_t)(((v >> 24) & 255) * o + 0.5f);
    const uint32_t b = (uint32_t)(((v >> 16) & 255) * o + 0.5f);
    const uint32_t g = (uint32_t)(((v >> 8) & 255) * o + 0.5f);
    const uint32_t r = (uint32_t)((v & 255) * o + 0.5f);
    return (a << 24) | (b << 16) | (g << 8) | r;
}
inline uint32_t layerPixel(const Canvas& c, int l, int x, int y) {
    return applyLayerOpacity(c.getPixelL(l, x, y), c.layerOpacity(l));
}

// 2.5D 叠层(旧项目 _mode25D/_depth25D/_yaw25D): 底层不动, 上层沿视角方向逐层错开 depth 像素
struct Mode25D {
    bool on = false;
    int depth = 5;
    int dir = 0;          // 旧存档兼容, 新逻辑用 yawDeg
    float yawDeg = 0.f;   // 视角旋转角(度)
    float offX = 0.f, offY = -1.f;   // 每层位移方向(画布/屏幕坐标, y向下); yaw=0 时向上
};
Mode25D& mode25D();
void setYaw25D(float deg);   // 更新 yawDeg + offX/offY(避免逐像素 sin/cos)
// 完整图层合成: 处理 正常/平铺(网格)/剪切蒙版 + 2.5D 挤出
uint32_t composeLayersPixel(const Canvas& c, int x, int y);
// 同上, 可关掉平铺网格与 2.5D(导出用: 网格只是辅助线, 2.5D 只是查看模式, 都不该进文件)
// applyCut: 是否应用"剪切矩形只挖空被剪层"的蒙版(mip 构建必须 false, 否则浮层采样会读到空)
uint32_t composeLayersPixelEx(const Canvas& c, int x, int y, bool showGrid, bool view25d,
                              bool applyCut = true);
// 普通层快路径: 无蒙版/无2.5D/无平铺网格/不透明度100%/正常混合/不应用裁剪时可用
bool composeLayersFastEligible(const Canvas& c, bool showGrid, bool view25d, bool applyCut);
uint32_t composeLayersPixelFast(const Canvas& c, int x, int y);
void setFastComposeEnabled(bool on);   // 调试 A/B 对比用

// 合成矩形: 目标 dst 行距 dstStride, 起点为 (x,y) 在目标缓冲中的位置
void compositeRect(const Canvas& c, int nlayers, uint32_t* dst, int dstStride,
                   int x, int y, int w, int h);

// 把 src 中非透明像素 over 到 dst 上(透明像素跳过, 不动目标已有内容)
void blitOverRow(uint32_t* dst, const uint32_t* src, int n);
// 直接覆盖行(保留源 alpha, 含半透明): 非 0 源像素原样写入, 0 跳过不擦目标
void blitReplaceRow(uint32_t* dst, const uint32_t* src, int n);

// 单层合成专用: 等价于 dst=0 后 over(src), 但省掉一次 memset
void blitOverZeroRow(uint8_t* dst, const uint8_t* src, int n);

void stampCircle(Canvas& c, int cx, int cy, int radius, uint32_t argb, bool erase = false, bool buildup = false);
// 基准对比用: 旧的逐像素圆 / 新的行 span 圆
void stampCircleLegacy(Canvas& c, int cx, int cy, int radius, uint32_t argb, bool erase = false, bool buildup = false);
void stampCircleSpan(Canvas& c, int cx, int cy, int radius, uint32_t argb, bool erase = false, bool buildup = false);
void stampLine(Canvas& c, int x0, int y0, int x1, int y1, int radius, uint32_t argb, bool erase = false, bool buildup = false);
// 笔刷系统: 可配置形状(0圆/1方)、硬度(0..100)、长宽比/角度、流量(0..100, alpha 乘数)
// spacingPct=沿笔画直径的间距百分比; scatterPct=随机偏移(直径的百分比)
void stampBrushDab(Canvas& c, float cx, float cy, float radius, uint32_t argb,
                   int shape, int hardness, int aspectPct, int angleDeg, int flowPct,
                   float scatterPct, bool erase, bool buildup);
void stampBrushLine(Canvas& c, float x0, float y0, float x1, float y1, float radius, uint32_t argb,
                    int shape, int hardness, int spacingPct, float scatterPct,
                    int aspectPct, int angleDeg, int flowPct, bool erase, bool buildup);
void fillRect(Canvas& c, int x, int y, int w, int h, uint32_t argb);
// 图形工具: 0=直线 1=矩形 2=椭圆; thickness=画布线宽(画布像素)
void drawShape(Canvas& c, int type, bool filled, int x0, int y0, int x1, int y1,
               int thickness, uint32_t argb);
// 预览命中测试(和 drawShape 同一几何), 供屏幕合成用
bool shapeHit(int type, bool filled, int x0, int y0, int x1, int y1,
              int px, int py, int thickness);
void clearLayer(Canvas& c, int layer);

// 单像素写入(完美线条 / 轮廓提取用): over 混合; erase 直接清 0; raw 精确覆盖(回滚)
void paintPixel(Canvas& c, int layer, int x, int y, uint32_t argb, bool erase);
void setPixelRaw(Canvas& c, int layer, int x, int y, uint32_t argb);

// 多边形 even-odd 扫描线填充(像素中心采样, 自动闭合); pts 为 x0,y0,x1,y1...
void fillPolygon(Canvas& c, const std::vector<int>& pts, uint32_t argb, bool erase);
// 形状像素集合(画布坐标 x,y 交替): 供"画布像素预览=落层"同源绘制(旧项目 DrawShapePreview 机制)
void linePathPixels(int x0, int y0, int x1, int y1, std::vector<int>& out);
// 1 画布像素直线命中(与 linePathPixels 同一 Aseprite 算法); 放大镜里画测距红线用
bool linePathHit(int x0, int y0, int x1, int y1, int px, int py);
void ellipsePathPixels(int x0, int y0, int x1, int y1, std::vector<int>& out);
void ellipseFillPixels(int x0, int y0, int x1, int y1, std::vector<int>& out);
void ellipseOutlinePixels(int x0, int y0, int x1, int y1, int thickness, std::vector<int>& out);
void polylinePixels(const std::vector<int>& pts, bool closed, std::vector<int>& out);
void polygonFillPixels(const std::vector<int>& pts, int W, int H, std::vector<int>& out);
void thickenPathPixels(const std::vector<int>& xy, int radius, std::vector<int>& out);
// 粗直线: 两条平行边之间的"平行四边形带"(butt 平切端头), 逐行精确 x 区间。
// 参考粗离散线=两条平行 naive line 之间的像素, 端头在起终点处平切, 不超出拖拽框。
void thickLinePixels(int x0, int y0, int x1, int y1, int thickness, std::vector<int>& out);
// 矩形边框: 四条轴对齐边带(全部在矩形内, 厚度向内), 方角; 与旧项目 GetRectPixels 同构。
void rectOutlinePixels(int x0, int y0, int x1, int y1, int thickness, std::vector<int>& out);
// 多边形轮廓: 填充掩码后取 4 邻接边缘(1 像素线); 超大 bbox 退化为逐边 stampLine
void strokePolygonOutline(Canvas& c, const std::vector<int>& pts, uint32_t argb, bool erase);
// 柳叶(旧 type4): 两端尖, 轴线 A->B, widthRatio 默认 0.33, 二次贝塞尔上下边界
void leafPath(int x0, int y0, int x1, int y1, float widthRatio, std::vector<int>& out);
void drawLeaf(Canvas& c, int x0, int y0, int x1, int y1, float widthRatio,
              bool filled, uint32_t argb, bool erase, int brushRadius = 0);

// 魔棒(精确色): 每个相邻行的连续段只压一次栈
int wandFillFast(const Canvas& c, int layer, int x, int y, std::vector<uint64_t>& mask);
// 油漆桶: 连续同色区域填充(4 邻接, 逐行扫描线), 直接写 active 层, 供撤销记录捕获。
// 超过 maxPixels 返回 capped=true(已写的部分由调用方用撤销记录回滚)。
struct FillResult { long long pixels = 0; bool capped = false; };
// spans: 可选输出, 记录被改写的平坦行段(撤销用)
FillResult floodFill(Canvas& c, int layer, int x, int y, uint32_t color, long long maxFlatPixels,
                     std::vector<Canvas::Span>* spans = nullptr, int tolerance = 0);

} // namespace endpix::raster
