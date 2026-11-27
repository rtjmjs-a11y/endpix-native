#include "psd_export.h"
#include "../raster/raster.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

namespace endpix {

namespace {

void be16(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back((uint8_t)((v >> 8) & 255));
    b.push_back((uint8_t)(v & 255));
}
void be32(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back((uint8_t)((v >> 24) & 255));
    b.push_back((uint8_t)((v >> 16) & 255));
    b.push_back((uint8_t)((v >> 8) & 255));
    b.push_back((uint8_t)(v & 255));
}
void ascii(std::vector<uint8_t>& b, const char* s) {
    while (*s) b.push_back((uint8_t)*s++);
}

std::vector<uint8_t> rleRow(const uint8_t* src, int len) {
    std::vector<uint8_t> out;
    out.reserve((size_t)len);
    int i = 0;
    while (i < len) {
        int run = 1;
        while (i + run < len && run < 128 && src[i + run] == src[i]) run++;
        if (run >= 3) {
            out.push_back((uint8_t)(257 - run));
            out.push_back(src[i]);
            i += run;
        } else {
            int lit = 0, j = i;
            while (j < len && lit < 128) {
                int r2 = 1;
                while (j + r2 < len && r2 < 3 && src[j + r2] == src[j]) r2++;
                if (r2 >= 3) break;
                j++; lit++;
            }
            if (lit <= 0) lit = 1;
            out.push_back((uint8_t)(lit - 1));
            for (int k = 0; k < lit; k++) out.push_back(src[i + k]);
            i += lit;
        }
    }
    return out;
}

inline uint8_t unpremul(uint32_t c, uint32_t a) {
    if (a == 0) return 0;
    uint32_t v = (c * 255u + a / 2) / a;
    return (uint8_t)std::min(255u, v);
}

struct LayerData {
    std::vector<uint8_t> plane[4];
    bool visible = true;
    float opacity = 1.f;
};

} // namespace

PsdResult exportPsd(const Canvas& c, const std::string& path) {
    PsdResult r;
    const int W = c.width(), H = c.height(), n = c.layers();
    if (W <= 0 || H <= 0 || n <= 0) { r.err = "empty"; return r; }
    const long long px = (long long)W * H;
    if (px > 4096ll * 4096ll) { r.err = "canvas too large for PSD (max 4096x4096)"; return r; }
    if (n > 32) { r.err = "too many layers"; return r; }

    std::vector<LayerData> layers((size_t)n);
    std::vector<uint32_t> comp((size_t)px, 0u);
    for (int l = 0; l < n; l++) {
        layers[(size_t)l].visible = c.layerVisible(l);
        layers[(size_t)l].opacity = c.layerOpacity(l);
        for (int k = 0; k < 4; k++) layers[(size_t)l].plane[k].resize((size_t)px);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                const uint32_t p = c.getPixelL(l, x, y);
                const uint32_t a = (p >> 24) & 255u;
                const uint8_t rr = unpremul(p & 255u, a);
                const uint8_t gg = unpremul((p >> 8) & 255u, a);
                const uint8_t bb = unpremul((p >> 16) & 255u, a);
                const size_t o = (size_t)y * W + x;
                layers[(size_t)l].plane[0][o] = rr;
                layers[(size_t)l].plane[1][o] = gg;
                layers[(size_t)l].plane[2][o] = bb;
                layers[(size_t)l].plane[3][o] = (uint8_t)a;
                if (layers[(size_t)l].visible && a) {
                    uint32_t src = (a << 24) | (bb << 16) | (gg << 8) | rr;
                    src = raster::applyLayerOpacity(src, layers[(size_t)l].opacity);
                    raster::overPixel(comp[o], src);
                }
            }
        }
    }

    // 每层每通道: compression=1(RLE) + 行长度表 + 数据
    std::vector<std::vector<uint8_t>> packed;
    packed.reserve((size_t)n * 4);
    for (int l = 0; l < n; l++) {
        for (int ch = 0; ch < 4; ch++) {
            std::vector<std::vector<uint8_t>> rows((size_t)H);
            std::vector<uint8_t> chBuf;
            chBuf.reserve((size_t)px + (size_t)H * 4);
            be16(chBuf, 1);
            size_t tablePos = chBuf.size();
            chBuf.resize(chBuf.size() + (size_t)H * 2);
            for (int y = 0; y < H; y++) {
                rows[(size_t)y] = rleRow(layers[(size_t)l].plane[ch].data() + (size_t)y * W, W);
                const size_t len = rows[(size_t)y].size();
                chBuf[tablePos + (size_t)y * 2] = (uint8_t)((len >> 8) & 255);
                chBuf[tablePos + (size_t)y * 2 + 1] = (uint8_t)(len & 255);
            }
            for (int y = 0; y < H; y++) {
                const auto& row = rows[(size_t)y];
                chBuf.insert(chBuf.end(), row.begin(), row.end());
            }
            packed.push_back(std::move(chBuf));
        }
    }

    // 图层信息段
    std::vector<uint8_t> li;
    be16(li, (uint32_t)n);
    int pk = 0;
    for (int l = 0; l < n; l++) {
        be32(li, 0); be32(li, 0);
        be32(li, (uint32_t)H); be32(li, (uint32_t)W);
        be16(li, 4);
        const int ids[4] = {0, 1, 2, -1};
        for (int ch = 0; ch < 4; ch++) {
            be16(li, (uint32_t)ids[ch]);
            be32(li, (uint32_t)packed[(size_t)pk + ch].size());
        }
        pk += 4;
        ascii(li, "8BIM");
        ascii(li, "norm");
        li.push_back((uint8_t)std::lround(std::max(0.f, std::min(1.f, layers[(size_t)l].opacity)) * 255.f)); // opacity
        li.push_back(0);                         // clipping
        li.push_back(layers[(size_t)l].visible ? 0 : 2);
        li.push_back(0);
        char name[64];
        snprintf(name, sizeof name, "Layer %d", l + 1);
        const int nlen = (int)strlen(name);
        const int padded = (1 + nlen + 3) & ~3;
        be32(li, (uint32_t)(4 + 4 + padded));    // extra len
        be32(li, 0);                             // mask
        be32(li, 0);                             // blend ranges
        li.push_back((uint8_t)nlen);
        for (int i = 0; i < nlen; i++) li.push_back((uint8_t)name[i]);
        for (int pad = 1 + nlen; pad < padded; pad++) li.push_back(0);
    }
    for (const auto& p : packed) li.insert(li.end(), p.begin(), p.end());

    std::vector<uint8_t> out;
    out.reserve((size_t)px * 5 + 4096);
    ascii(out, "8BPS");
    be16(out, 1);
    for (int i = 0; i < 6; i++) out.push_back(0);
    be16(out, 4);
    be32(out, (uint32_t)H);
    be32(out, (uint32_t)W);
    be16(out, 8);
    be16(out, 3);
    be32(out, 0);                                // color mode data
    be32(out, 0);                                // image resources
    const uint32_t globalMaskLen = 0;
    be32(out, (uint32_t)(4 + li.size() + 4 + globalMaskLen));
    be32(out, (uint32_t)li.size());
    out.insert(out.end(), li.begin(), li.end());
    be32(out, globalMaskLen);

    // 合成图
    std::vector<uint8_t> compPlane[4];
    for (int ch = 0; ch < 4; ch++) compPlane[ch].resize((size_t)px);
    for (size_t i = 0; i < (size_t)px; i++) {
        const uint32_t p = comp[i];
        const uint32_t a = (p >> 24) & 255u;
        compPlane[0][i] = unpremul(p & 255u, a);
        compPlane[1][i] = unpremul((p >> 8) & 255u, a);
        compPlane[2][i] = unpremul((p >> 16) & 255u, a);
        compPlane[3][i] = (uint8_t)a;
    }
    be16(out, 1);
    std::vector<std::vector<uint8_t>> cRows[4];
    for (int ch = 0; ch < 4; ch++) {
        cRows[ch].resize((size_t)H);
        for (int y = 0; y < H; y++) {
            cRows[ch][(size_t)y] = rleRow(compPlane[ch].data() + (size_t)y * W, W);
            be16(out, (uint32_t)cRows[ch][(size_t)y].size());
        }
    }
    for (int ch = 0; ch < 4; ch++)
        for (int y = 0; y < H; y++) {
            const auto& row = cRows[ch][(size_t)y];
            out.insert(out.end(), row.begin(), row.end());
        }

    FILE* f = fopen(path.c_str(), "wb");
    if (!f) { r.err = "open failed"; return r; }
    const size_t wrote = fwrite(out.data(), 1, out.size(), f);
    fclose(f);
    if (wrote != out.size()) { r.err = "write failed"; return r; }
    r.ok = true;
    r.bytes = (long long)out.size();
    return r;
}

} // namespace endpix
