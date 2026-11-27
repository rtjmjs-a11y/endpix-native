#include "gif_writer.h"
#include <unordered_map>
#include <algorithm>

namespace endpix {

namespace {
inline int nearestColor(const std::vector<uint32_t>& pal, int r, int g, int b) {
    int best = 0x7FFFFFFF, bestIdx = 0;
    for (size_t i = 1; i < pal.size(); i++) {
        const uint32_t c = pal[i];
        const int dr = r - (int)(c & 255);
        const int dg = g - (int)((c >> 8) & 255);
        const int db = b - (int)((c >> 16) & 255);
        const int d = dr * dr + dg * dg + db * db;
        if (d < best) { best = d; bestIdx = (int)i; }
    }
    return bestIdx;
}
} // namespace

GifWriter::~GifWriter() { if (f_) fclose(f_); }

void GifWriter::u8(uint8_t v) { if (ok_ && fwrite(&v, 1, 1, f_) != 1) ok_ = false; }
void GifWriter::u16(uint16_t v) { u8((uint8_t)(v & 255)); u8((uint8_t)(v >> 8)); }
bool GifWriter::writeBytes(const uint8_t* p, size_t n) {
    if (!ok_) return false;
    if (n && fwrite(p, 1, n, f_) != n) ok_ = false;
    return ok_;
}

bool GifWriter::begin(const std::string& path, int w, int h, int delayCentiSec) {
    if (w <= 0 || h <= 0 || w > 65535 || h > 65535) return false;
    f_ = fopen(path.c_str(), "wb");
    if (!f_) return false;
    w_ = w; h_ = h; delay_ = std::max(2, delayCentiSec); ok_ = true;
    static const char sig[] = "GIF89a";
    writeBytes((const uint8_t*)sig, 6);
    u16((uint16_t)w); u16((uint16_t)h);
    u8(0x80 | 0x00);   // 全局色表 2 色占位
    u8(0);
    u8(0);
    u8(0); u8(0); u8(0);
    u8(255); u8(255); u8(255);
    // NETSCAPE2.0 无限循环
    u8(0x21); u8(0xFF); u8(11);
    static const char app[] = "NETSCAPE2.0";
    writeBytes((const uint8_t*)app, 11);
    u8(3); u8(1); u16(0); u8(0);
    return ok_;
}

bool GifWriter::addFrame(const uint32_t* px, int w, int h) {
    if (!ok_ || !f_ || !px || w != w_ || h != h_) return false;
    writeFrame(px);
    return ok_;
}

void GifWriter::writeFrame(const uint32_t* px) {
    std::vector<uint32_t> pal;   // 0x00BBGGRR (premultiplied-free)
    pal.reserve(256);
    pal.push_back(0);            // 索引0: 全透明
    std::unordered_map<uint32_t, uint8_t> cmap;
    // 注意: 不能预置 cmap[0]=0。纯黑 RGB(0,0,0) 的 key 也是 0, 会命中透明索引 0,
    // 导致不透明黑色像素整片消失。透明像素走下面的 a<128 分支, 不需要 map 记录。
    std::vector<uint8_t> idx((size_t)w_ * h_);
    bool hasTransparent = false;
    for (size_t i = 0; i < idx.size(); i++) {
        const uint32_t v = px[i];
        const int a = (int)(v >> 24);
        if (a < 128) { idx[i] = 0; hasTransparent = true; continue; }
        int r = (int)(v & 255), g = (int)((v >> 8) & 255), b = (int)((v >> 16) & 255);
        if (a < 255 && a > 0) {   // 反预乘, 半透明颜色不偏暗
            r = std::min(255, r * 255 / a);
            g = std::min(255, g * 255 / a);
            b = std::min(255, b * 255 / a);
        }
        const uint32_t key = (uint32_t)((r << 16) | (g << 8) | b);
        auto it = cmap.find(key);
        if (it != cmap.end()) { idx[i] = it->second; continue; }
        if (pal.size() < 256) {
            const uint8_t ni = (uint8_t)pal.size();
            cmap[key] = ni;
            pal.push_back((uint32_t)((b << 16) | (g << 8) | r));
            idx[i] = ni;
        } else {
            idx[i] = (uint8_t)nearestColor(pal, r, g, b);
        }
    }
    int bits = 1;
    while ((1 << bits) < (int)pal.size()) bits++;
    if (bits < 2) bits = 2;
    const int realPalSize = 1 << bits;
    // Graphic Control Extension
    u8(0x21); u8(0xF9); u8(4);
    u8(hasTransparent ? 0x09 : 0x00);
    u16((uint16_t)delay_);
    u8(0);
    u8(0);
    // Image Descriptor
    u8(0x2C); u16(0); u16(0); u16((uint16_t)w_); u16((uint16_t)h_);
    u8((uint8_t)(0x80 | (bits - 1)));   // 局部色表
    for (int i = 0; i < realPalSize; i++) {
        if (i < (int)pal.size()) {
            const uint32_t c = pal[(size_t)i];
            u8((uint8_t)(c & 255)); u8((uint8_t)((c >> 8) & 255)); u8((uint8_t)((c >> 16) & 255));
        } else {
            u8(0); u8(0); u8(0);
        }
    }
    u8((uint8_t)bits);   // LZW minimum code size
    std::vector<uint8_t> lzw;
    lzwCompress(idx, bits, lzw);
    size_t pos = 0;
    while (pos < lzw.size()) {
        const size_t chunk = std::min<size_t>(255, lzw.size() - pos);
        u8((uint8_t)chunk);
        writeBytes(lzw.data() + pos, chunk);
        pos += chunk;
    }
    u8(0);
}

void GifWriter::lzwCompress(const std::vector<uint8_t>& indices, int minBits,
                            std::vector<uint8_t>& out) {
    const int clearCode = 1 << minBits;
    const int endCode = clearCode + 1;
    int nextCode = endCode + 1;
    int curBits = minBits + 1;
    int maxCode = (1 << curBits) - 1;
    uint32_t bitBuf = 0;
    int bitCount = 0;
    out.clear();
    out.reserve(indices.size() + indices.size() / 2 + 16);
    std::unordered_map<int, int> dict;
    auto putCode = [&](int code) {
        bitBuf |= (uint32_t)code << bitCount;
        bitCount += curBits;
        while (bitCount >= 8) { out.push_back((uint8_t)(bitBuf & 255)); bitBuf >>= 8; bitCount -= 8; }
    };
    putCode(clearCode);
    if (!indices.empty()) {
        int prefix = indices[0];
        for (size_t i = 1; i < indices.size(); i++) {
            const int key = (prefix << 8) | indices[i];
            auto it = dict.find(key);
            if (it != dict.end()) { prefix = it->second; continue; }
            putCode(prefix);
            if (nextCode <= 4095) {
                if (nextCode > maxCode && curBits < 12) { curBits++; maxCode = (1 << curBits) - 1; }
                dict[key] = nextCode++;
            } else {
                putCode(clearCode);
                dict.clear();
                nextCode = endCode + 1;
                curBits = minBits + 1;
                maxCode = (1 << curBits) - 1;
            }
            prefix = indices[i];
        }
        putCode(prefix);
    }
    putCode(endCode);
    if (bitCount > 0) out.push_back((uint8_t)(bitBuf & 255));
}

bool GifWriter::finish() {
    if (f_ && ok_) u8(0x3B);
    const bool ok = ok_;
    if (f_) { fclose(f_); f_ = nullptr; }
    return ok;
}

} // namespace endpix
