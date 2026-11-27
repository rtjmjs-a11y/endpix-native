#pragma once
#include <string>
#include "../canvas/canvas.h"

namespace endpix {

struct PsdResult {
    bool ok = false;
    long long bytes = 0;
    std::string err;
};

// 当前帧全部图层导出为 PSD(8bit RGBA, RLE); 画布为预乘 alpha, 导出前反预乘。
PsdResult exportPsd(const Canvas& c, const std::string& path);

} // namespace endpix
