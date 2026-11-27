#include "png_export.h"
#include <zlib.h>
#include <chrono>
#include <cstdio>
#include "../raster/raster.h"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "endpix", __VA_ARGS__)

namespace endpix {
namespace {

struct ChunkWriter {
    FILE* f = nullptr;
    bool ok = true;
    void u32be(uint32_t v) {
        uint8_t b[4] = { (uint8_t)(v >> 24), (uint8_t)(v >> 16), (uint8_t)(v >> 8), (uint8_t)v };
        if (fwrite(b, 1, 4, f) != 4) ok = false;
    }
    void chunk(const char* type, const uint8_t* data, size_t len) {
        u32be((uint32_t)len);
        if (fwrite(type, 1, 4, f) != 4) ok = false;
        if (len && fwrite(data, 1, len, f) != len) ok = false;
        uLong crc = crc32(0L, (const Bytef*)type, 4);
        if (len) crc = crc32(crc, data, (uInt)len);
        u32be((uint32_t)crc);
    }
};

void put32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24); p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);  p[3] = (uint8_t)v;
}

// 剪切蒙版: 紧邻上方(可连续)可见蒙版层的 alpha 相乘(与画布/显示合成同语义)
int exportClipAlpha(const Canvas& c, int l, int x, int y) {
    const int nl = c.layers();
    if (l + 1 >= nl || !c.layerVisible(l + 1) || c.layerMode(l + 1) != 2) return 255;
    int ma = 255;
    for (int m = l + 1; m < nl; m++) {
        if (!c.layerVisible(m) || c.layerMode(m) != 2) break;
        ma = ma * (int)((c.getPixelL(m, x, y) >> 24) & 255u) / 255;
        if (ma == 0) return 0;
    }
    return ma;
}

uint32_t exportCompositePixel(const Canvas& c, int x, int y) {
    const int nl = c.layers();
    uint32_t acc = 0;
    for (int l = 0; l < nl; l++) {
        if (!c.layerVisible(l) || c.layerMode(l) == 2) continue;
        uint32_t v = c.getPixelL(l, x, y);
        if (!v) continue;
        const float op = c.layerOpacity(l);
        if (op < 0.999f) v = raster::applyLayerOpacity(v, op);
        const int ma = exportClipAlpha(c, l, x, y);
        if (ma <= 0) continue;
        if (ma < 255) {
            const uint32_t a = ((v >> 24) & 255u) * (uint32_t)ma / 255u;
            const uint32_t r = (v & 255u) * (uint32_t)ma / 255u;
            const uint32_t g = ((v >> 8) & 255u) * (uint32_t)ma / 255u;
            const uint32_t b = ((v >> 16) & 255u) * (uint32_t)ma / 255u;
            v = (a << 24) | (b << 16) | (g << 8) | r;
        }
        acc = raster::blendPixel(acc, v, c.layerBlend(l));
    }
    return acc;
}

} // namespace

ExportResult PngExporter::begin(const Canvas& c, const std::string& path, int scale,
                                int compressMode, bool fullCanvas) {
    t0_ = std::chrono::steady_clock::now();
    res_ = ExportResult();
    path_ = path;
    if (scale < 1) scale = 1;
    if (scale > 8) scale = 8;
    scale_ = scale;
    quant_ = (compressMode == 2);

    int x0, y0, x1, y1;
    if (fullCanvas) {
        x0 = 0; y0 = 0;
        x1 = c.width() - 1; y1 = c.height() - 1;
        if (x1 < 0 || y1 < 0) { res_.err = "empty"; done_ = true; return res_; }
    } else if (!c.inkBounds(&x0, &y0, &x1, &y1)) {
        res_.err = "empty"; done_ = true; return res_;
    }
    x0_ = x0; y0_ = y0;
    w_ = (long long)(x1 - x0 + 1) * scale;
    h_ = (long long)(y1 - y0 + 1) * scale;
    if (w_ > 65535 || h_ > 65535) { res_.err = "too large"; done_ = true; return res_; }
    nl_ = c.layers();
    vis_.clear(); visOp_.clear();
    hasClip_ = false;
    hasBlend_ = false;
    for (int l = 0; l < nl_; l++) {
        if (c.layerVisible(l)) {
            vis_.push_back(l); visOp_.push_back(c.layerOpacity(l));
            if (c.layerBlend(l) != 0) hasBlend_ = true;
        }
        if (c.layerVisible(l) && c.layerMode(l) == 2) hasClip_ = true;
    }

    f_ = fopen(path.c_str(), "wb");
    if (!f_) { res_.err = "open failed"; done_ = true; return res_; }
    static const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    if (fwrite(sig, 1, 8, f_) != 8) { res_.err = "write failed"; abort(); return res_; }

    ChunkWriter cw{ f_ };
    uint8_t ihdr[13];
    put32(ihdr, (uint32_t)w_);
    put32(ihdr + 4, (uint32_t)h_);
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;  // 8bit RGBA, 无隔行
    cw.chunk("IHDR", ihdr, 13);

    // 压缩级别按画布大小自适应: 超大画布仍无损, 但不再用 level9 把导出拖到几十分钟
    int zlevel;
    if (compressMode == 0) zlevel = 1;
    else {
        const long long px = w_ * h_;
        if (px > 16ll * 1000 * 1000) zlevel = 1;   // 大图优先速度(仍无损)
        else zlevel = 9;
    }
    if (deflateInit2(&z_, zlevel, Z_DEFLATED, 15, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
        res_.err = "zlib init"; abort(); return res_;
    }
    zInit_ = true;
    idat_.reserve(1u << 20);
    tmp_.resize(1u << 16);
    row_.assign((size_t)w_ * 4 + 1, 0);
    res_.ok = true;   // 头部已写好, 可以进入分帧导出
    return res_;
}

bool PngExporter::step(const Canvas& c, int msBudget) {
    if (done_) return true;
    if (!f_) { done_ = true; return true; }
    const auto start = std::chrono::steady_clock::now();
    int rowsThisStep = 0;
    while (oy_ < h_) {
        const int sy = y0_ + (int)(oy_ / scale_);
        row_[0] = 0;   // filter: None
        uint8_t* p = row_.data() + 1;
        const int tw = c.tilesX();
        const int ty = sy >> Canvas::TILE_SHIFT;
        const int rowInTile = (sy & (Canvas::TILE - 1)) << Canvas::TILE_SHIFT;
        if (scale_ == 1) {
            for (long long ox = 0; ox < w_; ) {
                const int sx = x0_ + (int)ox;
                const int run = (int)std::min<long long>(
                    Canvas::TILE - (sx & (Canvas::TILE - 1)), w_ - ox);
                // 每层在本 run 内的行指针(缺块为 nullptr), 避免逐像素查 tile
                const uint32_t* srcs[32];
                float ops[32];
                int nv = 0;
                for (size_t vi = 0; vi < vis_.size() && nv < 32; vi++) {
                    if (vis_[vi] >= 32) continue;
                    const uint32_t* t = c.tile(vis_[vi], ty * tw + (sx >> Canvas::TILE_SHIFT));
                    srcs[nv] = t ? (t + rowInTile + (sx & (Canvas::TILE - 1))) : nullptr;
                    ops[nv] = visOp_[vi];
                    nv++;
                }
                for (int i = 0; i < run; i++) {
                    uint32_t acc = 0;
                    if (hasClip_ || hasBlend_) {
                        acc = exportCompositePixel(c, sx + i, sy);
                    } else {
                        for (int k = 0; k < nv; k++) {
                            const uint32_t* src = srcs[k];
                            if (!src) continue;
                            uint32_t v = src[i];
                            if (ops[k] < 0.999f) v = raster::applyLayerOpacity(v, ops[k]);
                            raster::overPixel(acc, v);
                        }
                    }
                    uint8_t rr = (uint8_t)(acc & 0xFF);
                    uint8_t gg = (uint8_t)((acc >> 8) & 0xFF);
                    uint8_t bb = (uint8_t)((acc >> 16) & 0xFF);
                    if (quant_) { rr &= 0xF8; gg &= 0xF8; bb &= 0xF8; }
                    *p++ = rr; *p++ = gg; *p++ = bb; *p++ = (uint8_t)(acc >> 24);
                }
                ox += run;
            }
        } else {
            for (long long ox = 0; ox < w_; ox++) {
                const int sx = x0_ + (int)(ox / scale_);
                uint32_t acc = 0;
                if (hasClip_ || hasBlend_) {
                    acc = exportCompositePixel(c, sx, sy);
                } else {
                    for (size_t vi = 0; vi < vis_.size(); vi++) {
                        const uint32_t* t = c.tile(vis_[vi], ty * tw + (sx >> Canvas::TILE_SHIFT));
                        if (!t) continue;
                        uint32_t v = t[rowInTile + (sx & (Canvas::TILE - 1))];
                        raster::overPixel(acc, visOp_[vi] < 0.999f ?
                            raster::applyLayerOpacity(v, visOp_[vi]) : v);
                    }
                }
                uint8_t rr = (uint8_t)(acc & 0xFF);
                uint8_t gg = (uint8_t)((acc >> 8) & 0xFF);
                uint8_t bb = (uint8_t)((acc >> 16) & 0xFF);
                if (quant_) { rr &= 0xF8; gg &= 0xF8; bb &= 0xF8; }
                *p++ = rr; *p++ = gg; *p++ = bb; *p++ = (uint8_t)(acc >> 24);
            }
        }
        z_.next_in = row_.data();
        z_.avail_in = (uInt)row_.size();
        while (z_.avail_in) {
            z_.next_out = tmp_.data();
            z_.avail_out = (uInt)tmp_.size();
            int ret = deflate(&z_, Z_NO_FLUSH);
            if (ret != Z_OK && ret != Z_STREAM_END) {
                res_.err = "deflate"; abort(); done_ = true; return true;
            }
            size_t n = tmp_.size() - z_.avail_out;
            idat_.insert(idat_.end(), tmp_.data(), tmp_.data() + n);
            if (idat_.size() >= (1u << 20)) {
                ChunkWriter cw{ f_ };
                cw.chunk("IDAT", idat_.data(), idat_.size());
                idat_.clear();
                if (!cw.ok) { res_.err = "write failed"; abort(); done_ = true; return true; }
            }
        }
        oy_++;
        rowsThisStep++;
        if ((oy_ & 1023) == 0) LOGI("png export row %lld/%lld", oy_, h_);
        if (msBudget > 0 && rowsThisStep >= 4) {
            const auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::milliseconds>(now - start).count() >= msBudget)
                break;
        }
    }
    if (oy_ >= h_) return finalize();
    return false;
}

bool PngExporter::finalize() {
    int ret;
    do {
        z_.next_out = tmp_.data();
        z_.avail_out = (uInt)tmp_.size();
        ret = deflate(&z_, Z_FINISH);
        size_t n = tmp_.size() - z_.avail_out;
        idat_.insert(idat_.end(), tmp_.data(), tmp_.data() + n);
        if (idat_.size() >= (1u << 20)) {
            ChunkWriter cw{ f_ };
            cw.chunk("IDAT", idat_.data(), idat_.size());
            idat_.clear();
            if (!cw.ok) { res_.err = "write failed"; abort(); done_ = true; return true; }
        }
    } while (ret == Z_OK);
    {
        ChunkWriter cw{ f_ };
        if (!idat_.empty()) { cw.chunk("IDAT", idat_.data(), idat_.size()); idat_.clear(); }
        cw.chunk("IEND", nullptr, 0);
        if (!cw.ok) res_.err = "write failed";
    }
    if (ret != Z_STREAM_END && res_.err.empty()) res_.err = "finish";
    deflateEnd(&z_);
    zInit_ = false;
    const long pos = ftell(f_);
    fclose(f_);
    f_ = nullptr;
    if (!res_.err.empty()) {
        remove(path_.c_str());
        done_ = true;
        return true;
    }
    res_.ok = true;
    res_.w = (int)w_; res_.h = (int)h_;
    res_.bytes = (long long)pos;
    res_.ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0_).count();
    done_ = true;
    return true;
}

void PngExporter::abort() {
    if (zInit_) { deflateEnd(&z_); zInit_ = false; }
    if (f_) { fclose(f_); f_ = nullptr; }
}

ExportResult exportPng(const Canvas& c, const std::string& path, int scale, int compressMode,
                       bool fullCanvas) {
    PngExporter ex;
    ExportResult r = ex.begin(c, path, scale, compressMode, fullCanvas);
    if (ex.done()) return r;
    while (!ex.step(c, 1000000)) { }
    ExportResult out = ex.result();
    if (!out.ok) remove(path.c_str());
    return out;
}

ExportResult writePngPixels(const uint32_t* px, int w, int h, const std::string& path,
                            int compressMode) {
    ExportResult res;
    const auto t0 = std::chrono::steady_clock::now();
    if (!px || w <= 0 || h <= 0) { res.err = "empty"; return res; }
    if ((long long)w > 65535 || (long long)h > 65535) { res.err = "too large"; return res; }
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) { res.err = "open failed"; return res; }
    static const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    fwrite(sig, 1, 8, f);
    ChunkWriter cw{ f };
    uint8_t ihdr[13];
    put32(ihdr, (uint32_t)w);
    put32(ihdr + 4, (uint32_t)h);
    ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    cw.chunk("IHDR", ihdr, 13);
    const int zlevel = compressMode == 0 ? 1 : (compressMode == 2 ? 9 : 6);
    z_stream z{};
    if (deflateInit(&z, zlevel) != Z_OK) {
        fclose(f); remove(path.c_str());
        res.err = "zlib init"; return res;
    }
    std::vector<uint8_t> row((size_t)w * 4 + 1), tmp(1 << 16), idat;
    bool fail = false;
    for (int y = 0; y < h && !fail; y++) {
        row[0] = 0;   // filter: none
        const uint32_t* src = px + (size_t)y * w;
        uint8_t* dst = row.data() + 1;
        for (int x = 0; x < w; x++) {
            const uint32_t v = src[x];
            *dst++ = (uint8_t)(v & 0xFF);
            *dst++ = (uint8_t)((v >> 8) & 0xFF);
            *dst++ = (uint8_t)((v >> 16) & 0xFF);
            *dst++ = (uint8_t)((v >> 24) & 0xFF);
        }
        z.next_in = row.data();
        z.avail_in = (uInt)row.size();
        while (z.avail_in) {
            z.next_out = tmp.data();
            z.avail_out = (uInt)tmp.size();
            int ret = deflate(&z, Z_NO_FLUSH);
            if (ret != Z_OK && ret != Z_STREAM_END) { fail = true; break; }
            const size_t n = tmp.size() - z.avail_out;
            idat.insert(idat.end(), tmp.data(), tmp.data() + n);
            if (idat.size() >= (1u << 20)) {
                cw.chunk("IDAT", idat.data(), idat.size());
                idat.clear();
                if (!cw.ok) { fail = true; break; }
            }
        }
    }
    int ret = Z_OK;
    if (!fail) {
        do {
            z.next_out = tmp.data();
            z.avail_out = (uInt)tmp.size();
            ret = deflate(&z, Z_FINISH);
            const size_t n = tmp.size() - z.avail_out;
            idat.insert(idat.end(), tmp.data(), tmp.data() + n);
        } while (ret == Z_OK);
    }
    deflateEnd(&z);
    if (!fail && !idat.empty()) cw.chunk("IDAT", idat.data(), idat.size());
    if (!fail) cw.chunk("IEND", nullptr, 0);
    if (!cw.ok) fail = true;
    const long pos = ftell(f);
    fclose(f);
    if (fail) {
        remove(path.c_str());
        res.err = "write failed";
        return res;
    }
    res.ok = true;
    res.w = w; res.h = h;
    res.bytes = (long long)pos;
    res.ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0).count();
    return res;
}

} // namespace endpix
