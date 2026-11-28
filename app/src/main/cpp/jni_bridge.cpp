#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <zlib.h>
#include <atomic>
#include <chrono>
#include <string>
#include <unistd.h>
#include "engine/engine.h"

namespace endpix { std::string runCanvasBench(); }

static endpix::Engine* gEngine = nullptr;
static ANativeWindow* gWindow = nullptr;
// Surface 销毁(如打开 SAF 选文件)后引擎短暂为 null, 返回时 ActivityResult 回调
// 可能早于 surfaceCreated。导出类 JNI 入口统一等待引擎重建, 避免 "err no engine"。
static std::atomic<bool> gEngineReady{false};

static endpix::Engine* waitEngine(int timeoutMs) {
    if (!gEngineReady.load(std::memory_order_acquire)) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
        while (!gEngineReady.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < deadline) {
            usleep(20000);
        }
    }
    return gEngineReady.load(std::memory_order_acquire) ? gEngine : nullptr;
}

static bool gFreshEngine = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeStart(JNIEnv* env, jobject, jobject surface, jint vw, jint vh, jint cw, jint ch, jint layers) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    // 同一个 Surface 且引擎还活着: 直接复用
    if (gEngine && gWindow == win && gEngine->isRunning()) {
        ANativeWindow_release(win);
        return JNI_TRUE;
    }
    // Surface 变了, 或渲染线程已经退出(长时间后台 GL context 丢失/初始化失败): 整机重建
    gFreshEngine = false;
    gEngineReady.store(false, std::memory_order_release);
    if (gEngine) {
        gEngine->stop();
        delete gEngine;
        gEngine = nullptr;
    }
    if (gWindow) { ANativeWindow_release(gWindow); gWindow = nullptr; }
    gWindow = win;
    gEngine = new endpix::Engine();
    gFreshEngine = true;
    if (!gEngine->start(gWindow, vw, vh, cw, ch, layers)) {
        delete gEngine; gEngine = nullptr;
        ANativeWindow_release(gWindow); gWindow = nullptr;
        return JNI_FALSE;
    }
    gEngineReady.store(true, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeIsAlive(JNIEnv*, jobject) {
    return (gEngine && gEngine->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeStop(JNIEnv*, jobject) {
    gFreshEngine = false;
    gEngineReady.store(false, std::memory_order_release);
    if (gEngine) {
        gEngine->stop();
        delete gEngine;
        gEngine = nullptr;
    }
    if (gWindow) { ANativeWindow_release(gWindow); gWindow = nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGetView(JNIEnv* env, jobject) {
    jfloatArray arr = env->NewFloatArray(3);
    if (!arr) return nullptr;
    float v[3] = {0.f, 0.f, 0.f};
    if (gEngine) gEngine->viewState(&v[0], &v[1], &v[2]);
    env->SetFloatArrayRegion(arr, 0, 3, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetView(JNIEnv*, jobject, jfloat z, jfloat px, jfloat py) {
    if (gEngine) gEngine->requestSetView(z, px, py);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeResetView(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestResetView();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeResize(JNIEnv*, jobject, jint vw, jint vh) {
    if (gEngine) gEngine->resize(vw, vh);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeFrameCount(JNIEnv*, jobject) {
    return gEngine ? (jint)gEngine->frameCountNow() : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLayerCount(JNIEnv*, jobject) {
    return gEngine ? (jint)gEngine->layerCountNow() : 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCanvasSize(JNIEnv* env, jobject) {
    jintArray arr = env->NewIntArray(4);
    if (!arr) return nullptr;
    jint v[4] = { 0, 0, 0, 0 };
    if (gEngine) gEngine->canvasSize(&v[0], &v[1], &v[2]);
    env->SetIntArrayRegion(arr, 0, 4, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeRequestRedraw(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestRedraw();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeTouch(JNIEnv*, jobject, jint action, jfloat x, jfloat y,
                                                   jlong timeMs) {
    if (gEngine) gEngine->touch(action, x, y, (int64_t)timeMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeZoom(JNIEnv*, jobject, jfloat factor, jfloat fx, jfloat fy) {
    if (gEngine) gEngine->zoom(factor, fx, fy);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeBeginZoom(JNIEnv*, jobject, jfloat fx, jfloat fy) {
    if (gEngine) gEngine->beginZoomGesture(fx, fy);
}
extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeZoomGesture(JNIEnv*, jobject, jfloat factor, jfloat fx, jfloat fy) {
    if (gEngine) gEngine->zoomGesture(factor, fx, fy);
}
extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeEndZoom(JNIEnv*, jobject) {
    if (gEngine) gEngine->endZoomGesture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCancelSelectionGesture(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestCancelSelectionGesture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCancelFirstStroke(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestCancelFirstStroke();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativePan(JNIEnv*, jobject, jfloat dx, jfloat dy) {
    if (gEngine) gEngine->pan(dx, dy);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetMode(JNIEnv*, jobject, jint m) {
    if (gEngine) gEngine->setMode(m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetInsets(JNIEnv*, jobject, jint l, jint t, jint r, jint b) {
    if (gEngine) gEngine->setInsets((int)l, (int)t, (int)r, (int)b);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetTool(JNIEnv*, jobject, jint t) {
    if (gEngine) gEngine->setTool((int)t);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetBrushBuildup(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setBrushBuildup(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetBrush(JNIEnv*, jobject, jfloat px, jint argb) {
    if (gEngine) gEngine->setBrush((float)px, (uint32_t)argb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetBrushStyle(JNIEnv*, jobject,
        jint shape, jint hardness, jint spacing, jint scatter, jint aspect, jint angle, jint flow) {
    if (gEngine) gEngine->setBrushStyle((int)shape, (int)hardness, (int)spacing,
        (int)scatter, (int)aspect, (int)angle, (int)flow);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLayerOp(JNIEnv*, jobject, jint op, jint arg) {
    if (gEngine) gEngine->layerOp((int)op, (int)arg);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLayerMeta(JNIEnv* env, jobject) {
    std::string s = gEngine ? gEngine->layerMetaJoined() : std::string();
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetLayerName(JNIEnv* env, jobject, jint idx, jstring name) {
    if (!gEngine || !name) return;
    const char* p = env->GetStringUTFChars(name, nullptr);
    if (p) {
        gEngine->requestSetLayerName((int)idx, std::string(p));
        env->ReleaseStringUTFChars(name, p);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetLayerOpacity(JNIEnv*, jobject, jint idx, jint permille) {
    if (gEngine) gEngine->requestSetLayerOpacity((int)idx, (float)permille / 1000.f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeUndo(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestUndo();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeRedo(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestRedo();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetFillTolerance(JNIEnv*, jobject, jint t) {
    if (gEngine) gEngine->setFillTolerance((int)t);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetEraseMode(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setEraseMode(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeDeselect(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestDeselect();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelectAll(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestSelectAll();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeZoomFit(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestZoomFit();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeFlip(JNIEnv*, jobject, jint horizontal) {
    if (gEngine) gEngine->requestFlip(horizontal != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGrayscale(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestGrayscale();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetGrayView(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setGrayView(on != 0);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCanvasRect(JNIEnv* env, jobject) {
    int r[4] = {0, 0, 0, 0};
    if (gEngine) gEngine->canvasScreenRect(r);
    jintArray out = env->NewIntArray(4);
    if (out) env->SetIntArrayRegion(out, 0, 4, r);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetShapeSize(JNIEnv*, jobject, jfloat px) {
    if (gEngine) gEngine->setShapeSize(px);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetSelAllLayers(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setSelAllLayers(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetSelShape(JNIEnv*, jobject, jint shape) {
    if (gEngine) gEngine->setSelShape((int)shape);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetOnion(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setOnion(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeNewDocument(JNIEnv*, jobject, jint w, jint h) {
    if (gEngine) gEngine->requestNewDocument((int)w, (int)h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeResizeCanvas(JNIEnv*, jobject, jint w, jint h, jint ax, jint ay) {
    if (gEngine) gEngine->requestResizeCanvas((int)w, (int)h, (int)ax, (int)ay);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeScaleSel(JNIEnv*, jobject, jint pw, jint ph) {
    if (gEngine) gEngine->requestScaleSel(pw, ph);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSnapshotDirty(JNIEnv*, jobject) {
    return (gEngine && gEngine->snapshotDirty()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeIsStrokeDown(JNIEnv*, jobject) {
    return (gEngine && gEngine->buttonDownNow()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativePreviewActive(JNIEnv*, jobject) {
    return (gEngine && gEngine->previewActiveNow()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeInputIdle(JNIEnv*, jobject, jint ms) {
    return (gEngine && gEngine->inputIdleMs((int)ms)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSaveSnapshot(JNIEnv* env, jobject, jstring path) {
    if (!gEngine || !path) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        gEngine->requestSaveSnapshot(p, true);
        env->ReleaseStringUTFChars(path, p);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSaveSnapshotAsync(JNIEnv* env, jobject, jstring path) {
    if (!gEngine || !path) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        gEngine->requestSaveSnapshot(p, false);   // 自动保存: 不阻塞 UI, 写完原子 rename
        env->ReleaseStringUTFChars(path, p);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeForceSaveSnapshot(JNIEnv* env, jobject, jstring path) {
    if (!gEngine) return;
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    gEngine->requestSaveSnapshotForce(p ? p : "");
    if (p) env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLoadSnapshot(JNIEnv* env, jobject, jstring path) {
    if (!gEngine || !path) return;   // 标签切换也要能随时载入
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        gEngine->requestLoadSnapshot(p);
        env->ReleaseStringUTFChars(path, p);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeDebugScript(JNIEnv* env, jobject, jstring script, jstring logPath) {
    if (!gEngine || !script || !logPath) return JNI_FALSE;
    const char* sc = env->GetStringUTFChars(script, nullptr);
    const char* lp = env->GetStringUTFChars(logPath, nullptr);
    if (sc && lp) gEngine->debugScript(sc, lp);
    if (sc) env->ReleaseStringUTFChars(script, sc);
    if (lp) env->ReleaseStringUTFChars(logPath, lp);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelRect(JNIEnv* env, jobject) {
    jintArray arr = env->NewIntArray(18);
    if (!arr) return nullptr;
    int v[18] = {0};
    if (gEngine) gEngine->selectionUiRect(v);
    env->SetIntArrayRegion(arr, 0, 18, v);
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeMeasUi(JNIEnv* env, jobject) {
    jintArray arr = env->NewIntArray(12);
    if (!arr) return nullptr;
    int v[12] = {0};
    if (gEngine) gEngine->measureUi(v);
    env->SetIntArrayRegion(arr, 0, 12, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetRecord(JNIEnv* env, jobject, jint on, jstring path) {
    if (!gEngine || !path) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        gEngine->requestSetRecord(on != 0, p);
        env->ReleaseStringUTFChars(path, p);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeResumeRecord(JNIEnv* env, jobject, jstring path, jint count, jint elapsedMs) {
    if (!gEngine || !path) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        gEngine->requestResumeRecord(p, (int)count, (int)elapsedMs);
        env->ReleaseStringUTFChars(path, p);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeRecordUiEvent(JNIEnv* env, jobject, jint kind, jstring label) {
    if (!gEngine || !label) return;
    const char* p = env->GetStringUTFChars(label, nullptr);
    if (p) {
        gEngine->recordUiAction((int)kind, std::string(p));
        env->ReleaseStringUTFChars(label, p);
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeFrameThumb(JNIEnv* env, jobject, jint frame, jint layer) {
    const int N = 48 * 48;
    jintArray arr = env->NewIntArray(N);
    if (!arr) return nullptr;
    static uint32_t buf[N];
    bool ok = false;
    if (gEngine) ok = gEngine->frameThumbApi((int)frame, (int)layer, buf, N);
    if (!ok) memset(buf, 0, sizeof buf);
    env->SetIntArrayRegion(arr, 0, N, (const jint*)buf);
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeThumbRevision(JNIEnv*, jobject) {
    return gEngine ? (jint)gEngine->thumbRevision() : 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCompositeThumb(JNIEnv* env, jobject) {
    const int N = 48 * 48;
    jintArray arr = env->NewIntArray(N);
    if (!arr) return nullptr;
    static uint32_t buf[N];
    bool ok = false;
    if (gEngine) ok = gEngine->compositeThumbApi(buf, N);
    if (!ok) return nullptr;
    env->SetIntArrayRegion(arr, 0, N, (const jint*)buf);
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeExportThumb(JNIEnv* env, jobject) {
    const int N = 384;   // 导出预览用的高分辨率合成缩略图(48² 放大太糊)
    jintArray arr = env->NewIntArray(N * N);
    if (!arr) return nullptr;
    static std::vector<uint32_t> buf;
    if (!gEngine) return nullptr;
    gEngine->makeCompositeThumbN(N, buf);
    if ((int)buf.size() < N * N) return nullptr;
    env->SetIntArrayRegion(arr, 0, N * N, (const jint*)buf.data());
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeResizeThumb(JNIEnv* env, jobject) {
    const int N = 192;   // 调整画布预览用的高分辨率合成缩略图
    jintArray arr = env->NewIntArray(N * N);
    if (!arr) return nullptr;
    static std::vector<uint32_t> buf;
    if (!gEngine) return nullptr;
    gEngine->makeCompositeThumbN(N, buf);
    if ((int)buf.size() < N * N) return nullptr;
    env->SetIntArrayRegion(arr, 0, N * N, (const jint*)buf.data());
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeMinimapThumb(JNIEnv* env, jobject) {
    const int N = 192 * 192;
    jintArray arr = env->NewIntArray(N);
    if (!arr) return nullptr;
    static uint32_t buf[N];
    bool ok = false;
    if (gEngine) ok = gEngine->minimapThumbApi(buf, N);
    if (!ok) return nullptr;   // 还没生成: 让 Kotlin 回退到 48 时间轴缩略图
    env->SetIntArrayRegion(arr, 0, N, (const jint*)buf);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetMinimapActive(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->requestSetMinimapActive(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeMinimapViewport(JNIEnv* env, jobject, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 4) return;
    float v[4] = {0.f, 0.f, 1.f, 1.f};
    if (gEngine) gEngine->minimapViewportNormalized(v);
    env->SetFloatArrayRegion(out, 0, 4, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeMinimapPoint(JNIEnv* env, jobject, jfloat nx, jfloat ny, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 2) return;
    float v[2] = {nx, ny};
    if (gEngine) gEngine->minimapPointToCanvas(nx, ny, v);
    env->SetFloatArrayRegion(out, 0, 2, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeAnimateViewCenter(JNIEnv*, jobject, jfloat cx, jfloat cy, jint animate) {
    if (gEngine) gEngine->requestAnimateViewCenter(cx, cy, animate != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeReorderFrame(JNIEnv*, jobject, jint from, jint to) {
    if (gEngine) gEngine->requestReorderFrame((int)from, (int)to);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetLoopRange(JNIEnv*, jobject, jint start, jint end) {
    if (gEngine) gEngine->requestSetLoopRange((int)start, (int)end);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSwapFrames(JNIEnv*, jobject, jint a, jint b) {
    if (gEngine) gEngine->requestSwapFrames((int)a, (int)b);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeReorderLayer(JNIEnv*, jobject, jint from, jint to) {
    if (gEngine) gEngine->requestReorderLayer((int)from, (int)to);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetPlayBrush(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->requestSetPlayBrush(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeAnimateViewState(JNIEnv*, jobject, jfloat z, jfloat px, jfloat py) {
    if (gEngine) gEngine->requestAnimateViewState(z, px, py);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeFrameOp(JNIEnv*, jobject, jint op, jint arg) {
    if (gEngine) gEngine->requestFrameOp((int)op, (int)arg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetStretch(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->requestSetStretch(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetPerspective(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->requestSetPerspective(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelReset(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestSelReset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCopySel(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestCopySel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeDuplicateSel(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestDuplicateSel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeDeleteSel(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestDeleteSel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeConfirmSel(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestConfirmSel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelAdjust(JNIEnv*, jobject, jfloat h, jfloat s, jfloat b, jfloat a, jfloat blur, jboolean commit) {
    if (gEngine) gEngine->requestSelAdjust(h, s, b, a, blur, commit == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelStroke(JNIEnv*, jobject, jint mode, jint size, jint dir, jboolean outer, jfloat depth, jint color, jboolean commit) {
    if (gEngine) gEngine->requestSelStroke(mode, size, dir, outer == JNI_TRUE, depth, (uint32_t)color, commit == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelRestore(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestSelRestore();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSelCancelPreview(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestSelCancelPreview();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeFloatToViewCenter(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestFloatToViewCenter();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetLayerProps(JNIEnv*, jobject,
        jint layer, jint mode, jint tw, jint th, jint ox, jint oy,
        jboolean showGrid, jint gridColor, jboolean indep, jint lockFrame, jint blend) {
    if (gEngine) gEngine->requestSetLayerProps(layer, mode, tw, th, ox, oy,
        showGrid == JNI_TRUE, (uint32_t)gridColor, indep == JNI_TRUE, lockFrame, blend);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLayerProps(JNIEnv* env, jobject) {
    std::vector<int> v = gEngine ? gEngine->layerPropsCopy() : std::vector<int>();
    jintArray arr = env->NewIntArray((jsize)v.size());
    if (arr && !v.empty()) env->SetIntArrayRegion(arr, 0, (jsize)v.size(), (const jint*)v.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSet25D(JNIEnv*, jobject, jboolean on, jint depth, jint dir) {
    if (gEngine) gEngine->requestSet25D(on == JNI_TRUE, depth, dir);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeReset25DView(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestReset25DView();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGet25D(JNIEnv* env, jobject) {
    int st[3] = {0, 5, 0};
    if (gEngine) gEngine->mode25DState(st);
    jintArray arr = env->NewIntArray(3);
    if (arr) env->SetIntArrayRegion(arr, 0, 3, (const jint*)st);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCancelSel(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestCancelSel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativePasteLastCut(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestPasteLastCut();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeRotateSel(JNIEnv*, jobject, jint quarter) {
    if (gEngine) gEngine->requestRotateSel(quarter);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeFlipSel(JNIEnv*, jobject, jint horizontal) {
    if (gEngine) gEngine->requestFlipSel(horizontal != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetMeasureFixed(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->requestMeasureFixed(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeMeasureCenterStart(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestMeasureCenterStart();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeMeasureSetStartPending(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestMeasureSetStartPending();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativePolyOp(JNIEnv*, jobject, jint op) {
    if (gEngine) gEngine->requestPolyOp((int)op);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCancelShapePreview(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestCancelShapePreview();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeParkTabHistory(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestParkTabHistory();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetTabHistory(JNIEnv*, jobject, jint id) {
    if (gEngine) gEngine->requestSetTabHistory((int)id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeDropTabHistory(JNIEnv*, jobject, jint id) {
    if (gEngine) gEngine->requestDropTabHistory((int)id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeImportTextImage(JNIEnv* env, jobject, jint w, jint h, jintArray px) {
    if (!gEngine || !px) return;
    const jsize n = env->GetArrayLength(px);
    if (n < w * h) return;
    std::vector<uint32_t> buf((size_t)w * h);
    jint* src = env->GetIntArrayElements(px, nullptr);
    if (!src) return;
    for (jsize i = 0; i < n && i < (jsize)buf.size(); i++) buf[(size_t)i] = (uint32_t)src[i];
    env->ReleaseIntArrayElements(px, src, JNI_ABORT);
    gEngine->setImportTextImage(w, h, buf);
}

// 只读项目快照头(含 PNS2 zlib 头段)拿画布尺寸: 加载项目时先建同尺寸标签(与模板一致)
static bool readSnapDims(const char* path, int* w, int* h, int* lc, int* count) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    uint8_t hdr[28];
    size_t got = fread(hdr, 1, sizeof hdr, f);
    auto le32 = [](const uint8_t* p) {
        return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    };
    bool ok = false;
    if (got >= 12 && hdr[0] == 'P' && hdr[1] == 'N' && hdr[2] == 'S' && hdr[3] == '2') {
        uint32_t raw = le32(hdr + 4), csz = le32(hdr + 8);
        if (raw >= 28 && csz > 0 && csz < (700u << 20)) {
            fseek(f, 12, SEEK_SET);   // 压缩流从 PNS2 头(12字节)之后开始; 上面已多读了 16 字节
            z_stream z{};
            if (inflateInit(&z) == Z_OK) {
                std::vector<uint8_t> in(64 * 1024);
                uint8_t out[32];
                z.next_out = out; z.avail_out = sizeof out;
                size_t total = 0;
                while (total < csz && z.avail_out > 0) {
                    size_t want = std::min(in.size(), (size_t)csz - total);
                    size_t rd = fread(in.data(), 1, want, f);
                    if (!rd) break;
                    total += rd;
                    z.next_in = in.data(); z.avail_in = (uInt)rd;
                    while (z.avail_in > 0 && z.avail_out > 0) {
                        int r = inflate(&z, Z_NO_FLUSH);
                        if (r != Z_OK && r != Z_STREAM_END) break;
                        if (r == Z_STREAM_END) break;
                    }
                }
                inflateEnd(&z);
                if (z.avail_out == 0) {
                    uint32_t magic = le32(out), ver = le32(out + 4);
                    uint32_t ww = le32(out + 8), hh = le32(out + 12), l = le32(out + 16);
                    uint32_t cnt = le32(out + 24);
                    if (magic == 0x534E5058u && ver >= 1 && ver <= 7 && ww >= 1 && hh >= 1 &&
                        ww <= 32767 && hh <= 32767) {
                        *w = (int)ww; *h = (int)hh; *lc = (int)l;
                        if (count) *count = (int)cnt;
                        ok = true;
                    }
                }
            }
        }
    } else if (got >= 28) {
        uint32_t magic = le32(hdr), ver = le32(hdr + 4);
        uint32_t ww = le32(hdr + 8), hh = le32(hdr + 12), l = le32(hdr + 16);
        uint32_t cnt = le32(hdr + 24);
        if (magic == 0x534E5058u && ver >= 1 && ver <= 7 && ww >= 1 && hh >= 1 &&
            ww <= 32767 && hh <= 32767) {
            *w = (int)ww; *h = (int)hh; *lc = (int)l;
            if (count) *count = (int)cnt;
            ok = true;
        }
    }
    fclose(f);
    return ok;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSnapshotDims(JNIEnv* env, jobject, jstring jpath) {
    if (!jpath) return nullptr;
    jintArray arr = env->NewIntArray(4);
    if (!arr) return nullptr;
    jint v[4] = { 0, 0, 0, 0 };
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    if (p) {
        int w = 0, h = 0, lc = 0, count = 0;
        if (readSnapDims(p, &w, &h, &lc, &count)) { v[0] = w; v[1] = h; v[2] = lc; v[3] = count; }
        env->ReleaseStringUTFChars(jpath, p);
    }
    env->SetIntArrayRegion(arr, 0, 4, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeImportImage(JNIEnv* env, jobject, jint w, jint h,
                                                         jintArray px) {
    if (!gEngine || !px || w <= 0 || h <= 0) return;
    const jsize n = env->GetArrayLength(px);
    if (n < w * h) return;
    std::vector<uint32_t> buf((size_t)w * h);
    jint* src = env->GetIntArrayElements(px, nullptr);
    if (!src) return;
    for (jsize i = 0; i < n && i < (jsize)buf.size(); i++) buf[(size_t)i] = (uint32_t)src[i];
    env->ReleaseIntArrayElements(px, src, JNI_ABORT);
    gEngine->setImportRegion(0, 0, w, h, buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeReplaySetFrame(JNIEnv* env, jobject, jint w, jint h,
                                                            jintArray px) {
    if (!gEngine || !px || w <= 0 || h <= 0) return;
    const jsize n = env->GetArrayLength(px);
    if (n < w * h) return;
    std::vector<uint32_t> buf((size_t)w * h);
    jint* src = env->GetIntArrayElements(px, nullptr);
    if (!src) return;
    for (jsize i = 0; i < n && i < (jsize)buf.size(); i++) buf[(size_t)i] = (uint32_t)src[i];
    env->ReleaseIntArrayElements(px, src, JNI_ABORT);
    gEngine->setReplayFrame(w, h, buf.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeReplayClear(JNIEnv*, jobject) {
    if (gEngine) gEngine->clearReplayFrame();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeImportRegion(JNIEnv* env, jobject, jint x, jint y,
                                                          jint w, jint h, jintArray px) {
    if (!gEngine || !px) return;
    const jsize n = env->GetArrayLength(px);
    if (n < w * h) return;
    std::vector<uint32_t> buf((size_t)w * h);
    jint* src = env->GetIntArrayElements(px, nullptr);
    if (!src) return;
    for (jsize i = 0; i < n && i < (jsize)buf.size(); i++) buf[(size_t)i] = (uint32_t)src[i];
    env->ReleaseIntArrayElements(px, src, JNI_ABORT);
    gEngine->setImportRegion(x, y, w, h, buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeImportSlices(JNIEnv* env, jobject, jint w, jint frameH,
                                                          jint layers, jint frames,
                                                          jobjectArray names, jintArray layerW,
                                                          jintArray layerH, jintArray px) {
    if (!gEngine || !names || !layerW || !layerH || !px) return;
    if (w <= 0 || frameH <= 0 || layers <= 0 || frames <= 0) return;
    if (env->GetArrayLength(names) < layers ||
        env->GetArrayLength(layerW) < layers ||
        env->GetArrayLength(layerH) < layers) return;
    std::vector<std::string> nm((size_t)layers);
    for (int i = 0; i < layers; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(names, i);
        if (s) {
            const char* cs = env->GetStringUTFChars(s, nullptr);
            if (cs) { nm[(size_t)i] = cs; env->ReleaseStringUTFChars(s, cs); }
            env->DeleteLocalRef(s);
        }
    }
    std::vector<int> lw((size_t)layers), lh((size_t)layers);
    env->GetIntArrayRegion(layerW, 0, layers, lw.data());
    env->GetIntArrayRegion(layerH, 0, layers, lh.data());
    const jsize n = env->GetArrayLength(px);
    std::vector<uint32_t> buf((size_t)n);
    env->GetIntArrayRegion(px, 0, n, (jint*)buf.data());
    gEngine->requestSliceImport(w, frameH, layers, frames, std::move(nm),
                                std::move(lw), std::move(lh), std::move(buf));
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeImportSheet(JNIEnv* env, jobject, jint sheetW, jint sheetH,
                                                         jint cellW, jint cellH, jint cols, jint rows,
                                                         jboolean cropFrames, jboolean framesAcross,
                                                         jboolean layered, jint depth, jintArray px) {
    if (!gEngine || !px) return;
    if (sheetW <= 0 || sheetH <= 0 || cellW <= 0 || cellH <= 0 || cols <= 0 || rows <= 0) return;
    const jsize n = env->GetArrayLength(px);
    if (n < (jlong)sheetW * sheetH) return;
    std::vector<uint32_t> buf((size_t)n);
    env->GetIntArrayRegion(px, 0, n, (jint*)buf.data());
    gEngine->requestSheetImport(sheetW, sheetH, cellW, cellH, cols, rows,
                                cropFrames == JNI_TRUE, framesAcross == JNI_TRUE,
                                layered == JNI_TRUE, depth, std::move(buf));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeReadRegion(JNIEnv* env, jobject, jint x, jint y,
                                                        jint w, jint h) {
    if (!gEngine || w <= 0 || h <= 0) return nullptr;
    std::vector<uint32_t> px = gEngine->readRegion(x, y, w, h);
    if ((int)px.size() < w * h) return nullptr;
    jintArray arr = env->NewIntArray(w * h);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, w * h, (const jint*)px.data());
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLastCutPixels(JNIEnv* env, jobject) {
    if (!gEngine) return nullptr;
    std::vector<uint32_t> px;
    int w = 0, h = 0;
    if (!gEngine->lastCutPixels(px, w, h)) return nullptr;
    if (w <= 0 || h <= 0 || px.size() < (size_t)w * h) return nullptr;
    const jsize n = (jsize)(2 + (size_t)w * h);
    jintArray arr = env->NewIntArray(n);
    if (!arr) return nullptr;
    std::vector<jint> buf((size_t)n);
    buf[0] = w; buf[1] = h;
    std::copy(px.begin(), px.begin() + (size_t)w * h, buf.begin() + 2);
    env->SetIntArrayRegion(arr, 0, n, buf.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetPerfectLine(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setPerfectLine(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetLeafWidth(JNIEnv*, jobject, jfloat w) {
    if (gEngine) gEngine->setLeafWidth(w);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetShapeRatio(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setShapeRatio(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetAlphaLock(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setAlphaLock(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetSelectionAlphaOverlay(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setSelectionAlphaOverlay(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetSelHoldMs(JNIEnv*, jobject, jint ms) {
    if (gEngine) gEngine->setSelectionHoldMs((int)ms);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetShape(JNIEnv*, jobject, jint type, jint filled) {
    if (gEngine) gEngine->setShape((int)type, filled != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetPickMode(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setPickMode(on);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeExportPng(JNIEnv* env, jobject, jstring jpath, jint scale) {
    endpix::Engine* eng = waitEngine(6000);
    if (!eng) return env->NewStringUTF("err no engine");
    const char* p = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    std::string r = eng->exportPngFile(p ? p : "", (int)scale);
    if (p) env->ReleaseStringUTFChars(jpath, p);
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeExportMode(JNIEnv* env, jobject, jstring jpath,
        jint kind, jint scale, jint cols, jint delayMs) {
    endpix::Engine* eng = waitEngine(6000);
    if (!eng) return env->NewStringUTF("err no engine");
    const char* p = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    std::string r = eng->exportModeFile(p ? p : "", (int)kind, (int)scale, (int)cols, (int)delayMs);
    if (p) env->ReleaseStringUTFChars(jpath, p);
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeExportInfo(JNIEnv* env, jobject) {
    int st[6] = {0, 0, 1, 0, 0, 1};
    if (gEngine) gEngine->exportInfo(st);
    jintArray arr = env->NewIntArray(6);
    if (arr) env->SetIntArrayRegion(arr, 0, 6, (const jint*)st);
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeExportPsd(JNIEnv* env, jobject, jstring jpath) {
    endpix::Engine* eng = waitEngine(6000);
    if (!eng) return env->NewStringUTF("err no engine");
    const char* p = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    std::string r = eng->exportPsdFile(p ? p : "");
    if (p) env->ReleaseStringUTFChars(jpath, p);
    return env->NewStringUTF(r.c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeLevelsHistogram(JNIEnv* env, jobject, jint channel) {
    jintArray arr = env->NewIntArray(256);
    if (!arr) return nullptr;
    int h[256] = {};
    if (gEngine) gEngine->levelsHistogram((int)channel, h);
    env->SetIntArrayRegion(arr, 0, 256, h);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetLevelsPreview(JNIEnv* env, jobject, jboolean on, jintArray luts) {
    if (!gEngine) return;
    if (!on || !luts || env->GetArrayLength(luts) < 1024) {
        gEngine->setLevelsPreview(false, nullptr);
        return;
    }
    jint tmp[1024];
    env->GetIntArrayRegion(luts, 0, 1024, tmp);
    gEngine->setLevelsPreview(true, tmp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeApplyLevelsAll(JNIEnv* env, jobject, jintArray luts) {
    if (!gEngine || !luts || env->GetArrayLength(luts) < 1024) return;
    jint tmp[1024];
    env->GetIntArrayRegion(luts, 0, 1024, tmp);
    uint8_t out[4][256];
    for (int ch = 0; ch < 4; ch++)
        for (int v = 0; v < 256; v++)
            out[ch][v] = (uint8_t)std::max(0, std::min(255, (int)tmp[ch * 256 + v]));
    gEngine->requestApplyLevelsAll(out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeApplyLevels(JNIEnv*, jobject,
        jint channel, jint black, jint white, jfloat gamma, jint outBlack, jint outWhite) {
    if (gEngine) gEngine->requestApplyLevels(channel, black, white, gamma, outBlack, outWhite);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetCanvasStyle(JNIEnv*, jobject, jint gw, jint gh, jint gcol,
                                                            jint gal, jint lw100, jint chk, jint ca, jint cb, jint bg) {
    if (gEngine) gEngine->setCanvasStyle((int)gw, (int)gh, (int)gcol, (int)gal, (int)lw100,
                                         (int)chk, (int)ca, (int)cb, (int)bg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetPngCompress(JNIEnv*, jobject, jint mode) {
    if (gEngine) gEngine->setPngCompress((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetMagnifier(JNIEnv*, jobject, jint on) {
    if (gEngine) gEngine->setMagnifier(on != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetOverlay(JNIEnv*, jobject, jint chk, jint grid) {
    if (gEngine) gEngine->requestOverlay(chk != 0, grid != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCrop(JNIEnv*, jobject) {
    if (gEngine) gEngine->cropToSelection();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradPreset(JNIEnv*, jobject, jint p) {
    if (gEngine) gEngine->requestGradPreset((int)p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradApply(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestGradApply();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradCancel(JNIEnv*, jobject) {
    if (gEngine) gEngine->requestGradCancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradRange(JNIEnv*, jobject, jint pct) {
    if (gEngine) gEngine->setGradRange((int)pct);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradAngle(JNIEnv*, jobject, jint deg) {
    if (gEngine) gEngine->setGradAngle((int)deg);
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradPos(JNIEnv*, jobject, jint pct) {
    if (gEngine) gEngine->setGradPos((int)pct);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeGradUi(JNIEnv* env, jobject) {
    jintArray arr = env->NewIntArray(7);
    if (!arr) return nullptr;
    int v[7] = {0};
    if (gEngine) gEngine->gradUi(v);
    env->SetIntArrayRegion(arr, 0, 7, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeSetStress(JNIEnv*, jobject, jboolean on) {
    if (gEngine) gEngine->setStress(on == JNI_TRUE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeStats(JNIEnv* env, jobject) {
    std::string s = gEngine ? gEngine->stats() : std::string("engine stopped");
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_endpix_nativeapp_MainActivity_nativeCanvasBench(JNIEnv* env, jobject) {
    std::string s = endpix::runCanvasBench();
    return env->NewStringUTF(s.c_str());
}
