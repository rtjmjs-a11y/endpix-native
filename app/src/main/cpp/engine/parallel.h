#pragma once
#include <algorithm>
#include <atomic>
#include <functional>
#include <thread>
#include <vector>

namespace endpix {

// 导出用的小型 parallel-for: fn(i) 处理互不重叠的下标 i=0..n-1。
// 主线程也参与; 默认最多 4 线程(8200 的 4 大核), 避免和渲染/编码抢太多小核。
inline void parallelFor(int n, const std::function<void(int)>& fn, int maxThreads = 4) {
    if (n <= 0) return;
    if (n == 1) { fn(0); return; }
    int hw = (int)std::thread::hardware_concurrency();
    if (hw <= 0) hw = 4;
    const int workers = std::max(1, std::min({n, maxThreads, hw}));
    if (workers <= 1) {
        for (int i = 0; i < n; i++) fn(i);
        return;
    }
    std::atomic<int> next{0};
    auto run = [&]() {
        for (;;) {
            const int i = next.fetch_add(1, std::memory_order_relaxed);
            if (i >= n) break;
            fn(i);
        }
    };
    std::vector<std::thread> th;
    th.reserve((size_t)(workers - 1));
    for (int t = 1; t < workers; t++) th.emplace_back(run);
    run();
    for (auto& t : th) t.join();
}

} // namespace endpix
