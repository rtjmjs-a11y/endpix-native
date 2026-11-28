#pragma once
#include <atomic>
#include <string>
#include "../canvas/canvas.h"

namespace endpix {

struct PsdResult {
    bool ok = false;
    long long bytes = 0;
    long ms = 0;
    std::string err;
};

// 当前帧全部图层导出为 PSD(8bit RGBA, RLE); 画布为预乘 alpha, 导出前反预乘。
// abort: 可选中止标志(应用退出时别让后台导出拖住 join)
PsdResult exportPsd(const Canvas& c, const std::string& path,
                    const std::atomic<bool>* abort = nullptr);

} // namespace endpix
