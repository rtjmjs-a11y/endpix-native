#include <string>
#include <vector>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <unistd.h>
#include <fstream>
#include <thread>
#include <atomic>
#include "../canvas/canvas.h"
#include "../raster/raster.h"
#include "../canvas/mip.h"

#define LOGB(...) __android_log_print(ANDROID_LOG_INFO, "endpixbench", __VA_ARGS__)

namespace endpix {

using Clk = std::chrono::steady_clock;
static double ms(Clk::time_point t) { return std::chrono::duration<double, std::milli>(Clk::now() - t).count(); }

static long rssKb() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind("VmRSS:", 0) == 0) return atol(line.c_str() + 6);
    }
    return 0;
}

static void appendSize(std::string& out, int n, int layers) {
    char buf[512];
    size_t px = (size_t)n * n;
    double mb = px * 4.0 * layers / (1024.0 * 1024.0);

    auto t0 = Clk::now();
    Canvas c;
    if (!c.create(n, n, layers)) { snprintf(buf, sizeof buf, "%d: create FAIL\n", n); out += buf; return; }
    double createMs = ms(t0);

    // 2) 1000 点笔迹
    t0 = Clk::now();
    for (int i = 0; i < 1000; i++) {
        int x = (int)(i * 7) % (n - 12) + 6;
        int y = (int)(i * 13) % (n - 12) + 6;
        raster::stampCircle(c, x, y, 6, 0xFFFFFFFFu);
    }
    double strokeMs = ms(t0);

    // 3) staging + 整帧合成
    std::vector<uint32_t> staging(px, 0u);
    double allocStagingMs = 0;
    (void)allocStagingMs;
    t0 = Clk::now();
    raster::compositeRect(c, layers, staging.data(), n, 0, 0, n, n);
    double fullMs = ms(t0);

    // 4) 脏 16 tile 合成 (4x4 tile 区域)
    t0 = Clk::now();
    for (int ty = 0; ty < 4; ty++)
        for (int tx = 0; tx < 4; tx++)
            raster::compositeRect(c, layers, staging.data() + (size_t)(ty * 256) * n + tx * 256,
                                  n, tx * 256, ty * 256, 256, 256);
    double dirtyMs = ms(t0);

    // 5) AHB: 分配 + 整帧 memcpy
    double ahbAllocMs = -1, ahbCopyMs = -1, ahbMBs = 0;
    AHardwareBuffer* ahb = nullptr;
    if (true) {
        AHardwareBuffer_Desc d{};
        d.width = n; d.height = n; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        t0 = Clk::now();
        int r = AHardwareBuffer_allocate(&d, &ahb);
        ahbAllocMs = ms(t0);
        if (r != 0) { ahb = nullptr; }
    }
    if (ahb) {
        AHardwareBuffer_Desc info{};
        AHardwareBuffer_describe(ahb, &info);
        int stride = (int)info.stride;
        t0 = Clk::now();
        void* p = nullptr;
        if (AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &p) == 0) {
            const uint8_t* src = (const uint8_t*)staging.data();
            for (int y = 0; y < n; y++)
                memcpy((uint8_t*)p + (size_t)y * stride * 4, src + (size_t)y * n * 4, (size_t)n * 4);
            AHardwareBuffer_unlock(ahb, nullptr);
            ahbCopyMs = ms(t0);
            ahbMBs = px * 4.0 / ahbCopyMs / 1000.0;
        }
        AHardwareBuffer_release(ahb);
    }

    snprintf(buf, sizeof buf,
             "%dx%d L%d\n  create %.0fms (%.0fMB) stroke1k %.1fms\n  fullComp %.0fms (%.0f MP/s) dirty16 %.2fms\n  ahb alloc %.1fms copy %.1fms (%.0f MB/s)\n  rss %ldMB\n",
             n, n, layers, createMs, mb, strokeMs,
             fullMs, (double)px * layers / fullMs / 1000.0, dirtyMs,
             ahbAllocMs, ahbCopyMs, ahbMBs, rssKb() / 1024);
    out += buf;
    LOGB("%s", buf);
}

// 层数扩展 + 多线程脏区合成: 决定"能开多少层还流畅"
static void benchLayers(std::string& out) {
    const int n = 4096;
    const int maxL = 16;
    Canvas c;
    if (!c.create(n, n, maxL)) { out += "layers: create fail\n"; return; }
    for (int l = 0; l < maxL; l++) {
        // 每层都铺点内容, 保证合成不是全透明快速路径
        for (int i = 0; i < 2000; i++) {
            int x = (i * 37 + l * 11) % (n - 20) + 10;
            int y = (i * 53 + l * 17) % (n - 20) + 10;
            c.setActive(l);
            raster::stampCircle(c, x, y, 20, 0x40FFFFFFu | (uint32_t)(l * 16) << 24);
        }
    }
    c.clearDirty();
    std::vector<uint32_t> staging((size_t)n * n, 0u);
    char buf[320];
    snprintf(buf, sizeof buf, "layers@4096 dirty16 (single thread):");
    out += buf;
    for (int L : {1, 2, 4, 8, 16}) {
        auto t0 = Clk::now();
        for (int rep = 0; rep < 3; rep++)
            for (int ty = 0; ty < 4; ty++)
                for (int tx = 0; tx < 4; tx++)
                    raster::compositeRect(c, L, staging.data() + (size_t)(ty * 256) * n + tx * 256,
                                          n, tx * 256, ty * 256, 256, 256);
        snprintf(buf, sizeof buf, "  L%d=%.2fms", L, ms(t0) / 3.0);
        out += buf;
    }
    out += "\n";


    // 4 线程脏区合成 (L=8)
    snprintf(buf, sizeof buf, "dirty16 L8 threads:");
    out += buf;
    for (int th : {1, 2, 4}) {
        auto t0 = Clk::now();
        for (int rep = 0; rep < 3; rep++) {
            std::atomic<int> next(0);
            auto worker = [&]() {
                for (;;) {
                    int t = next.fetch_add(1);
                    if (t >= 16) break;
                    int tx = t % 4, ty = t / 4;
                    raster::compositeRect(c, 8, staging.data() + (size_t)(ty * 256) * n + tx * 256,
                                          n, tx * 256, ty * 256, 256, 256);
                }
            };
            std::vector<std::thread> ts;
            for (int i = 0; i < th - 1; i++) ts.emplace_back(worker);
            worker();
            for (auto& t : ts) t.join();
        }
        snprintf(buf, sizeof buf, "  T%d=%.2fms", th, ms(t0) / 3.0);
        out += buf;
    }
    out += "\n";
    LOGB("layers bench done");
}

// 魔棒: 朴素逐像素压栈 vs 连续段只压一次
static void benchWand(std::string& out) {
    const int n = 4096;
    Canvas c;
    if (!c.create(n, n, 1)) { out += "wand: create fail\n"; return; }
    raster::fillRect(c, 0, 0, n / 2, n / 2, 0xFF0000FFu);
    raster::fillRect(c, n / 2, 0, n / 2, n / 2, 0xFF00FF00u);
    raster::fillRect(c, 0, n / 2, n / 2, n / 2, 0xFF0000FFu);
    raster::fillRect(c, n / 2, n / 2, n / 2, n / 2, 0xFFFF0000u);

    // 朴素 span flood fill (每像素压栈)
    std::vector<uint64_t> mask((size_t)n * n / 64, 0);
    auto has = [&](size_t j) { return (mask[j >> 6] >> (j & 63)) & 1ULL; };
    auto set = [&](size_t j) { mask[j >> 6] |= 1ULL << (j & 63); };
    std::vector<uint32_t> img((size_t)n * n);
    for (int yy = 0; yy < n; yy++)
        for (int xx = 0; xx < n; xx++)
            img[(size_t)yy * n + xx] = c.getPixel(xx, yy);
    const uint32_t* plane = img.data();
    uint32_t seed = plane[(size_t)10 * n + 10];
    auto t0 = Clk::now();
    {
        std::vector<int> st;
        st.push_back(10 * n + 10);
        while (!st.empty()) {
            int idx = st.back(); st.pop_back();
            int yy = idx / n, xx = idx % n;
            size_t base = (size_t)yy * n, j = (size_t)idx;
            if (has(j) || plane[j] != seed) continue;
            int left = xx, right = xx;
            while (left > 0 && plane[base + left - 1] == seed) left--;
            while (right < n - 1 && plane[base + right + 1] == seed) right++;
            for (int i = left; i <= right; i++) set(base + i);
            for (int ny = yy - 1; ny <= yy + 1; ny += 2) {
                if (ny < 0 || ny >= n) continue;
                for (int i = left; i <= right; i++) {
                    size_t k = (size_t)ny * n + i;
                    if (!has(k) && plane[k] == seed) st.push_back((int)k);
                }
            }
        }
    }
    double naiveMs = ms(t0);

    std::vector<uint64_t> mask2;
    t0 = Clk::now();
    int cnt = raster::wandFillFast(c, 0, 10, 10, mask2);
    double fastMs = ms(t0);

    char buf[320];
    snprintf(buf, sizeof buf, "wand 4096: naive %.1fms / fast %.1fms (%.1fx) pixels=%d\n",
             naiveMs, fastMs, naiveMs / fastMs, cnt);
    out += buf;
}


// 画笔: 逐像素圆 vs 行 span 圆
static void benchStamp(std::string& out) {
    const int n = 4096;
    Canvas c;
    if (!c.create(n, n, 1)) { out += "stamp: create fail\n"; return; }
    char buf[256];
    for (int r : {6, 24, 91}) {
        const int reps = 120;
        for (int impl = 0; impl < 2; impl++) {
            auto t0 = Clk::now();
            for (int i = 0; i < reps; i++) {
                int x = 100 + (i * 23) % (n - 200);
                int y = 100 + (i * 37) % (n - 200);
                if (impl == 0) raster::stampCircleLegacy(c, x, y, r, 0xFFFFFFFFu, false);
                else raster::stampCircleSpan(c, x, y, r, 0xFFFFFFFFu, false);
            }
            double dms = ms(t0);
            double mp = (double)reps * (2.0 * r + 1) * (2.0 * r + 1) / dms / 1000.0;
            snprintf(buf, sizeof buf, "stamp r%d %s %.2fms (%.0f MP/s)\n",
                     r, impl == 0 ? "legacy" : "span  ", dms, mp);
            out += buf;
        }
    }
}

// 合成: 16 脏 tile, L2/L8, 单线程/4线程
static void benchComposeAB(std::string& out) {
    const int n = 4096;
    Canvas c;
    if (!c.create(n, n, 8)) { out += "compose: create fail\n"; return; }
    for (int l = 0; l < 8; l++) {
        c.setActive(l);
        for (int i = 0; i < 800; i++) {
            int x = (i * 37 + l * 11) % (n - 20) + 10;
            int y = (i * 53 + l * 17) % (n - 20) + 10;
            raster::stampCircle(c, x, y, 6, 0xFF40FFFFFFu | (uint32_t)(l * 16) << 24, false);
        }
    }
    c.clearDirty();
    std::vector<uint32_t> st((size_t)n * n);
    char buf[256];
    for (int L : {2, 8}) {
        for (int th : {1, 4}) {
            auto t0 = Clk::now();
            for (int rep = 0; rep < 3; rep++) {
                if (th == 1) {
                    for (int ty = 0; ty < 4; ty++)
                        for (int tx = 0; tx < 4; tx++)
                            raster::compositeRect(c, L, st.data() + (size_t)(ty * 256) * n + tx * 256,
                                                  n, tx * 256, ty * 256, 256, 256);
                } else {
                    std::atomic<int> next(0);
                    auto worker = [&]() {
                        for (;;) {
                            int t = next.fetch_add(1);
                            if (t >= 16) break;
                            int tx = t % 4, ty = t / 4;
                            raster::compositeRect(c, L, st.data() + (size_t)(ty * 256) * n + tx * 256,
                                                  n, tx * 256, ty * 256, 256, 256);
                        }
                    };
                    std::vector<std::thread> ts;
                    for (int i = 0; i < th - 1; i++) ts.emplace_back(worker);
                    worker();
                    for (auto& t : ts) t.join();
                }
            }
            snprintf(buf, sizeof buf, "compose16 L%d T%d %.2fms\n", L, th, ms(t0) / 3.0);
            out += buf;
        }
    }
}


// 合成金字塔: 各级构建耗时/内存 + 抽样校验
static void benchMip(std::string& out) {
    const int n = 4096;
    Canvas c;
    if (!c.create(n, n, 8)) { out += "mip: create fail\n"; return; }
    for (int l = 0; l < 8; l++) {
        c.setActive(l);
        for (int i = 0; i < 600; i++) {
            int x = (i * 37 + l * 11) % (n - 20) + 10;
            int y = (i * 53 + l * 17) % (n - 20) + 10;
            raster::stampCircle(c, x, y, 12, 0xFF40FFFFFFu | (uint32_t)(l * 16) << 24, false);
        }
    }
    MipPyramid mp;
    mp.reset(&c);
    char buf[256];
    size_t lastMem = 0;
    for (int L = 0; L <= 5; L++) {
        const int tx = mp.tilesX(L), ty = mp.tilesY(L);
        auto t0 = Clk::now();
        size_t cnt = 0;
        for (int y = 0; y < ty; y++)
            for (int x = 0; x < tx; x++)
                if (mp.tile(L, x, y)) cnt++;
        double dms = ms(t0);
        size_t mem = mp.bytes();
        snprintf(buf, sizeof buf, "mip L%d %dx%d build %.1fms mem %.1fMB (+%.1f)\n",
                 L, tx, ty, dms, mem / 1e6, (mem - lastMem) / 1e6);
        out += buf;
        lastMem = mem;
    }
    // 抽样校验: 各级随机像素 == 画布对应 2^L 步长采样点的逐层合成
    for (int L = 0; L <= 5; L++) {
        int checked = 0, bad = 0, nonZero = 0;
        const int tx = mp.tilesX(L), ty = mp.tilesY(L);
        for (int y = 0; y < ty && checked < 400; y++)
            for (int x = 0; x < tx && checked < 400; x++) {
                const uint32_t* t = mp.tile(L, x, y);
                if (!t) continue;
                for (int sy = 3; sy < 256 && checked < 400; sy += 53)
                    for (int sx = 3; sx < 256 && checked < 400; sx += 47) {
                        const int stride = 1 << L;
                        const int cx = x * 256 * stride + sx * stride;
                        const int cy = y * 256 * stride + sy * stride;
                        if (cx >= n || cy >= n) continue;
                        uint32_t exp = 0;
                        for (int l = 0; l < 8; l++) {
                            uint32_t v = c.getPixelL(l, cx, cy);
                            if (v) raster::overPixel(exp, v);
                        }
                        const uint32_t got = t[sy * 256 + sx];
                        checked++;
                        if (exp) nonZero++;
                        if (got != exp) bad++;
                    }
            }
        snprintf(buf, sizeof buf, "mip verify L%d checked=%d nonzero=%d bad=%d %s\n",
                 L, checked, nonZero, bad, bad == 0 ? "OK" : "MISMATCH");
        out += buf;
    }
    LOGB("%s", out.c_str());
}


// 框选浮起路径对比: 当前预览采样(zoom*2 封顶1) vs 全分辨率拷贝 vs 屏幕分辨率浮层
static void benchSelection(std::string& out) {
    Canvas c;
    c.create(2048, 2048, 1);
    for (int y = 0; y < 2048; y++)
        for (int x = 0; x < 2048; x++) c.setPixel(x, y, (x & 1) ? 0xFF3366FFu : 0);

    const int sizes[] = { 1000, 3000, 6000 };
    const float zooms[] = { 0.066f, 0.5f, 1.0f };
    char buf[320];
    out += "float extract: preview vs full vs screen-layer\n";
    for (int s : sizes) {
        for (float z : zooms) {
            const float sc = std::max(0.02f, std::min(1.0f, z * 2.0f));
            const int pw = (int)(s * sc);
            uint32_t acc = 0;
            auto t0 = Clk::now();
            for (int ty = 0; ty < pw; ty++) {
                const int sy = std::min(2047, (int)(ty / sc));
                for (int tx = 0; tx < pw; tx++) {
                    const int sx = std::min(2047, (int)(tx / sc));
                    uint32_t a = 0;
                    raster::overPixel(a, c.getPixelL(0, sx, sy));
                    acc += a;
                }
            }
            const double pms = ms(t0);
            auto t1 = Clk::now();
            for (int ty = 0; ty < s; ty++) {
                const int sy = std::min(2047, ty);
                for (int tx = 0; tx < s; tx++) {
                    uint32_t a = 0;
                    raster::overPixel(a, c.getPixelL(0, std::min(2047, tx), sy));
                    acc += a;
                }
            }
            const double fms = ms(t1);
            const long long ptx = (long long)pw * pw, ftx = (long long)s * s;
            const int sfw = std::min(1080, (int)(s * z)), sfh = std::min(2388, (int)(s * z));
            snprintf(buf, sizeof buf,
                     "sel %d z=%.3f: preview %d^2 %.1fMB %.1fms | full %.1fMB %.1fms | screen %.1fMB\n",
                     s, z, pw, ptx * 4.0 / 1048576.0, pms,
                     ftx * 4.0 / 1048576.0, fms,
                     (double)sfw * sfh * 4.0 / 1048576.0);
            out += buf;
        }
    }
}

std::string runCanvasBench() {
    std::string out = "== canvas bench ==\n";
    benchSelection(out);
    benchStamp(out);
    benchComposeAB(out);
    benchWand(out);
    benchMip(out);
    out += "== done ==\n";
    LOGB("%s", out.c_str());
    return out;
}

} // namespace endpix
