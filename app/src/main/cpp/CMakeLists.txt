cmake_minimum_required(VERSION 3.22)
project(endpixcore LANGUAGES CXX)

add_library(endpixcore SHARED
    jni_bridge.cpp
    canvas/canvas.cpp
    canvas/mip.cpp
    raster/raster.cpp
    display/gl_display.cpp
    engine/engine.cpp
    engine/selection.cpp
    engine/png_export.cpp
    engine/psd_export.cpp
    engine/gif_writer.cpp
    bench/canvas_bench.cpp)

target_compile_options(endpixcore PRIVATE -std=c++20 -O3 -funroll-loops -march=armv8.2-a+dotprod)
target_link_libraries(endpixcore EGL GLESv2 android log z)
