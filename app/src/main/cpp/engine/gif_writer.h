#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace endpix {

// GIF89a 动画写入器(移植旧项目 MainUIController.Export.cs 的 GIF 编码器):
// 每帧独立局部色表(透明色索引0, 其余最多255色, 超出量化到最近色), LZW 压缩, 无限循环。
class GifWriter {
public:
    bool begin(const std::string& path, int w, int h, int delayCentiSec);
    bool addFrame(const uint32_t* px, int w, int h);   // px: 预乘 ABGR, 行主序
    bool finish();
    ~GifWriter();

private:
    void u8(uint8_t v);
    void u16(uint16_t v);
    bool writeBytes(const uint8_t* p, size_t n);
    void writeFrame(const uint32_t* px);
    void lzwCompress(const std::vector<uint8_t>& indices, int minBits,
                     std::vector<uint8_t>& out);
    FILE* f_ = nullptr;
    bool ok_ = true;
    int w_ = 0, h_ = 0, delay_ = 0;
    // LZW 字典: key=(prefix<<8)|next (20 bit), 用"代号"代际数组代替每帧清空/哈希表
    std::vector<int32_t> lzwDict_;
    std::vector<uint32_t> lzwGen_;
    uint32_t lzwGenCur_ = 0;
};

} // namespace endpix
