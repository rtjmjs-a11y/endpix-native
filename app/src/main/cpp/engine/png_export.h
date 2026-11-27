#pragma once
#include <string>
#include <vector>
#include <chrono>
#include <zlib.h>
#include "../canvas/canvas.h"

namespace endpix {

struct ExportResult {
    bool ok = false;
    int w = 0, h = 0;
    long long bytes = 0;
    long ms = 0;
    std::string err;
};

// 增量 PNG 导出器: 渲染线程每帧只处理一小段时间, 大画布导出不再卡死画面/输入。
class PngExporter {
public:
    ExportResult begin(const Canvas& c, const std::string& path, int scale, int compressMode,
                       bool fullCanvas);
    // 渲染线程调用: 处理约 msBudget 毫秒后返回; true=已结束(成功或失败)
    bool step(const Canvas& c, int msBudget);
    bool done() const { return done_; }
    ExportResult result() const { return res_; }
    void abort();
    ~PngExporter() { abort(); }

private:
    bool finalize();
    FILE* f_ = nullptr;
    z_stream z_{};
    bool zInit_ = false;
    std::vector<uint8_t> idat_, tmp_, row_;
    long long w_ = 0, h_ = 0;
    int x0_ = 0, y0_ = 0, scale_ = 1;
    bool quant_ = false;
    long long oy_ = 0;
    int nl_ = 0;
    std::vector<int> vis_;        // 可见层索引
    std::vector<float> visOp_;    // 对应层不透明度
    bool hasClip_ = false;        // 存在可见剪切蒙版层(合成需按蒙版 alpha 裁剪)
    bool hasBlend_ = false;       // 存在非正常混合模式层(需逐像素走完整合成)
    bool done_ = false;
    std::string path_;
    ExportResult res_;
    std::chrono::steady_clock::time_point t0_;
};

// 同步导出(小图/侧车缩略图用): 内部循环 step 直到完成
ExportResult exportPng(const Canvas& c, const std::string& path, int scale, int compressMode = 1,
                       bool fullCanvas = false);

// 从已合成好的像素缓冲直接写 PNG(精灵图/叠层图/逐帧导出用)
// px: 预乘 ABGR, 行主序自上而下; compressMode 同 Canvas 导出(0快/1中/2高)
ExportResult writePngPixels(const uint32_t* px, int w, int h, const std::string& path,
                            int compressMode = 1);

} // namespace endpix
