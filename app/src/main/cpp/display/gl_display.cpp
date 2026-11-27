#include "gl_display.h"
#include <EGL/eglext.h>
#include <android/api-level.h>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <cstdio>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "endpix", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "endpix", __VA_ARGS__)

namespace endpix {

typedef EGLClientBuffer (*PFN_getNativeClientBufferANDROID)(AHardwareBuffer*);
typedef void (*PFN_EGLImageTargetTexture2DOES)(GLenum, void*);
typedef EGLImageKHR (*PFN_eglCreateImageKHR)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*);

static PFN_getNativeClientBufferANDROID pGetCb = nullptr;
static PFN_EGLImageTargetTexture2DOES pImgTarget = nullptr;
static PFN_eglCreateImageKHR pCreateImg = nullptr;
static PFNEGLDESTROYIMAGEKHRPROC pDestroyImg = nullptr;

static const char* VS =
    "#version 300 es\n"
    "layout(location=0) in vec2 aPos;\n"
    "uniform vec2 uScale, uOffset, uUvScale, uUvOffset;\n"
    "out vec2 vUv;\n"
    "void main(){ vUv = aPos * uUvScale + uUvOffset; gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); }\n";

static const char* FS =
    "#version 300 es\n"
    "precision highp float;\n"
    "uniform sampler2D uTex;\n"
    "uniform vec4 uCanvasRect;\n"   // x=left y=bottom z=right w=top (frag coords, for clipping)
    "uniform vec2 uOrigin;\n"       // exact frag coords of canvas pixel (0,0)
    "uniform float uZoom;\n"        // canvas->screen scale
    "uniform float uRot;\n"         // 2.5D 视角旋转(弧度)
    "uniform vec2 uCenter;\n"       // 画布中心 frag 坐标
    "uniform vec2 uCanvasSize;\n"   // 画布宽高(像素)
    "uniform float uChkOn;\n"
    "uniform float uGridOn;\n"
    "uniform float uGridW, uGridH, uGridAlpha, uGridLineW, uChkTile;\n"
    "uniform vec3 uGridColor;\n"
    "uniform vec4 uLoupeRect;\n"    // 放大镜窗口(frag 坐标 x=left,y=bottom,z=right,w=top)
    "uniform float uLoupeOn;\n"
    "uniform vec3 uChkA, uChkB, uBgColor;\n"
    "uniform float uGray;\n"
    "in vec2 vUv; out vec4 o;\n"
    "void main(){\n"
    "  vec4 c = texture(uTex, vUv);\n"
    "  vec3 crgb = mix(c.rgb, vec3(dot(c.rgb, vec3(0.299, 0.587, 0.114))), uGray);\n"
    "  vec2 q = gl_FragCoord.xy - uCenter;\n"
    "  float rc = cos(uRot), rs = sin(uRot);\n"
    "  vec2 rq = vec2(rc*q.x - rs*q.y, rs*q.x + rc*q.y);\n"
    "  vec2 fc = uCenter + rq;\n"
    "  float cx = (fc.x - 0.5 - uOrigin.x) / uZoom;\n"
    "  float cy = (uOrigin.y - fc.y - 0.5) / uZoom;\n"
    // 棋盘格: 画布外观里的格子大小(画布px), 屏幕小于4px时冻结防摩尔纹
    "  float chkCell = max(uChkTile * uZoom, 4.0);\n"
    "  vec2 cq = vec2(cx, cy) * (uZoom / chkCell);\n"
    "  float chk = mod(floor(cq.x) + floor(cq.y), 2.0);\n"
    "  vec3 checker = mix(uChkA, uChkB, chk);\n"
    // 网格: 横/纵间距各自可调(画布px), 线宽按屏幕px换算
    "  vec2 gCell = max(vec2(uGridW, uGridH) * uZoom, vec2(4.0));\n"
    "  vec2 gq = vec2(cx * uZoom / gCell.x, cy * uZoom / gCell.y);\n"
    "  vec2 gf = fract(gq);\n"
    "  vec2 glw = max(vec2(uGridLineW), vec2(0.5)) / gCell;\n"
    "  float line = min(step(gf.x, glw.x) + step(gf.y, glw.y), 1.0) * uGridOn * step(6.0, min(gCell.x, gCell.y));\n"
    "  float insideAxis = step(uCanvasRect.x, gl_FragCoord.x) * step(gl_FragCoord.x, uCanvasRect.z)\n"
    "               * step(uCanvasRect.y, gl_FragCoord.y) * step(gl_FragCoord.y, uCanvasRect.w);\n"
    "  float insideRot = step(0.0, cx) * step(cx, uCanvasSize.x)\n"
    "                  * step(0.0, cy) * step(cy, uCanvasSize.y);\n"
    "  float inside = mix(insideAxis, insideRot, step(0.0001, abs(uRot)));\n"
    // 放大镜窗口内不画屏幕网格: 镜面里的网格由 CPU 按放大后的位置画
    "  float inLoupe = uLoupeOn\n"
    "    * step(uLoupeRect.x, gl_FragCoord.x) * step(gl_FragCoord.x, uLoupeRect.z)\n"
    "    * step(uLoupeRect.y, gl_FragCoord.y) * step(gl_FragCoord.y, uLoupeRect.w);\n"
    "  vec3 bg = mix(uBgColor, checker, inside * uChkOn);\n"
    "  vec3 col = mix(bg, crgb, c.a);\n"
    "  col = mix(col, uGridColor, uGridAlpha * line * inside * (1.0 - inLoupe));\n"
    "  o = vec4(col, 1.0);\n"
    "}\n";

static GLuint compile(GLenum t, const char* src) {
    GLuint s = glCreateShader(t);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(s, 512, nullptr, log); LOGE("shader: %s", log); }
    return s;
}

bool GlDisplay::init(ANativeWindow* win, int canvasW, int canvasH) {
    win_ = win; cw_ = canvasW; ch_ = canvasH;
    texW_ = canvasW; texH_ = canvasH;   // 屏幕缓冲尺寸在 surface 查询后确定

    dpy_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy_ == EGL_NO_DISPLAY || !eglInitialize(dpy_, nullptr, nullptr)) { LOGE("egl init fail"); return false; }
    const EGLint cfgAttr[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLint n = 0;
    if (!eglChooseConfig(dpy_, cfgAttr, &cfg_, 1, &n) || n < 1) { LOGE("egl config fail"); return false; }
    const EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    ctx_ = eglCreateContext(dpy_, cfg_, EGL_NO_CONTEXT, ctxAttr);
    surf_ = eglCreateWindowSurface(dpy_, cfg_, win_, nullptr);
    if (ctx_ == EGL_NO_CONTEXT || surf_ == EGL_NO_SURFACE || !eglMakeCurrent(dpy_, surf_, surf_, ctx_)) {
        LOGE("egl surface/ctx fail"); return false;
    }
    eglSwapInterval(dpy_, 1);
    {
        const char* renderer = (const char*)glGetString(GL_RENDERER);
        const char* version = (const char*)glGetString(GL_VERSION);
        GLint maxTex = 0, maxRb = 0, units = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
        glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &maxRb);
        glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &units);
        LOGI("GL renderer=%s version=%s maxTex=%d maxRb=%d units=%d",
             renderer ? renderer : "?", version ? version : "?", maxTex, maxRb, units);
    }
    {
        EGLint sw = 0, sh = 0;
        eglQuerySurface(dpy_, surf_, EGL_WIDTH, &sw);
        eglQuerySurface(dpy_, surf_, EGL_HEIGHT, &sh);
        LOGI("egl surface %dx%d (java said %dx%d)", sw, sh, cw_, ch_);
        if (sw > 0 && sh > 0) { vw_ = sw; vh_ = sh; }
        dw_ = vw_; dh_ = vh_; texW_ = dw_; texH_ = dh_;
    }

    pGetCb = (PFN_getNativeClientBufferANDROID)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    pImgTarget = (PFN_EGLImageTargetTexture2DOES)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    pCreateImg = (PFN_eglCreateImageKHR)eglGetProcAddress("eglCreateImageKHR");
    pDestroyImg = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");

    if (!createTexture()) return false;

    GLuint vs = compile(GL_VERTEX_SHADER, VS), fs = compile(GL_FRAGMENT_SHADER, FS);
    prog_ = glCreateProgram();
    glAttachShader(prog_, vs); glAttachShader(prog_, fs); glLinkProgram(prog_);
    glDeleteShader(vs); glDeleteShader(fs);
    uScale_ = glGetUniformLocation(prog_, "uScale");
    uOffset_ = glGetUniformLocation(prog_, "uOffset");
    uTex_ = glGetUniformLocation(prog_, "uTex");
    uUvScale_ = glGetUniformLocation(prog_, "uUvScale");
    uUvOffset_ = glGetUniformLocation(prog_, "uUvOffset");
    uCanvasRect_ = glGetUniformLocation(prog_, "uCanvasRect");
    uOrigin_ = glGetUniformLocation(prog_, "uOrigin");
    uZoom_ = glGetUniformLocation(prog_, "uZoom");
    uRot_ = glGetUniformLocation(prog_, "uRot");
    uCenter_ = glGetUniformLocation(prog_, "uCenter");
    uCanvasSize_ = glGetUniformLocation(prog_, "uCanvasSize");
    uChkOn_ = glGetUniformLocation(prog_, "uChkOn");
    uGridOn_ = glGetUniformLocation(prog_, "uGridOn");
    uGridW_ = glGetUniformLocation(prog_, "uGridW");
    uGridH_ = glGetUniformLocation(prog_, "uGridH");
    uGridColor_ = glGetUniformLocation(prog_, "uGridColor");
    uGridAlpha_ = glGetUniformLocation(prog_, "uGridAlpha");
    uGridLineW_ = glGetUniformLocation(prog_, "uGridLineW");
    uChkTile_ = glGetUniformLocation(prog_, "uChkTile");
    uChkA_ = glGetUniformLocation(prog_, "uChkA");
    uChkB_ = glGetUniformLocation(prog_, "uChkB");
    uBgColor_ = glGetUniformLocation(prog_, "uBgColor");
    uGray_ = glGetUniformLocation(prog_, "uGray");
    uLoupeRect_ = glGetUniformLocation(prog_, "uLoupeRect");
    uLoupeOn_ = glGetUniformLocation(prog_, "uLoupeOn");

    // 蚂蚁线程序
    static const char* ANTS_VS =
        "#version 300 es\n"
        "layout(location=0) in vec2 aPos;\n"
        "uniform vec2 uScale, uOffset;\n"
        "void main(){ gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); }\n";
    static const char* ANTS_FS =
        "#version 300 es\n"
        "precision mediump float;\n"
        "uniform float uPhase;\n"
        "out vec4 o;\n"
        "void main(){\n"
        "  float d = mod(floor((gl_FragCoord.x + gl_FragCoord.y) * 0.125 + uPhase), 2.0);\n"
        "  o = vec4(vec3(step(1.0, d)), 1.0);\n"
        "}\n";
    {
        GLuint av = compile(GL_VERTEX_SHADER, ANTS_VS), af = compile(GL_FRAGMENT_SHADER, ANTS_FS);
        antsProg_ = glCreateProgram();
        glAttachShader(antsProg_, av); glAttachShader(antsProg_, af); glLinkProgram(antsProg_);
        glDeleteShader(av); glDeleteShader(af);
        antsScale_ = glGetUniformLocation(antsProg_, "uScale");
        antsOffset_ = glGetUniformLocation(antsProg_, "uOffset");
        antsPhase_ = glGetUniformLocation(antsProg_, "uPhase");
        glGenBuffers(1, &antsVbo_);
    }

    // 浮起内容专用 program: 直出纹理 alpha
    {
        static const char* FLOAT_FS = R"SHADER(#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 o;
void main(){ o = texture(uTex, vUv); }
)SHADER";
        GLuint fv = compile(GL_VERTEX_SHADER, VS), ff = compile(GL_FRAGMENT_SHADER, FLOAT_FS);
        floatProg_ = glCreateProgram();
        glAttachShader(floatProg_, fv); glAttachShader(floatProg_, ff); glLinkProgram(floatProg_);
        glDeleteShader(fv); glDeleteShader(ff);
        fScale_ = glGetUniformLocation(floatProg_, "uScale");
        fOffset_ = glGetUniformLocation(floatProg_, "uOffset");
        fUvScale_ = glGetUniformLocation(floatProg_, "uUvScale");
        fUvOffset_ = glGetUniformLocation(floatProg_, "uUvOffset");
        fTex_ = glGetUniformLocation(floatProg_, "uTex");
        GLint ok = 0; glGetProgramiv(floatProg_, GL_LINK_STATUS, &ok);
        LOGI("floatProg link=%d err=0x%x", ok, glGetError());
    }

    const float verts[] = { 0, 0, 1, 0, 0, 1, 1, 1 };
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof verts, verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glClearColor(0.05f, 0.067f, 0.09f, 1.0f);
    LOGI("display init %dx%d canvas %dx%d ahb=%d tex=%u", vw_, vh_, cw_, ch_, (int)useAhb_, tex_);
    return true;
}

bool GlDisplay::createTexture() {
    glGenTextures(1, &tex_);
    glBindTexture(GL_TEXTURE_2D, tex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    bool canAhb = pGetCb && pImgTarget && pCreateImg && android_get_device_api_level() >= 29;
    if (canAhb) {
        AHardwareBuffer_Desc d{};
        d.width = dw_; d.height = dh_; d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (AHardwareBuffer_allocate(&d, &ahb_) == 0) {
            EGLClientBuffer cb = pGetCb(ahb_);
            const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, 1, EGL_NONE };
            img_ = pCreateImg(dpy_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
            if (img_ != EGL_NO_IMAGE_KHR) {
                pImgTarget(GL_TEXTURE_2D, img_);
                GLenum err = glGetError();
                if (err == GL_NO_ERROR) {
                    useAhb_ = true;
                    AHardwareBuffer_Desc info{};
                    AHardwareBuffer_describe(ahb_, &info);
                    ahbStride_ = (int)info.stride;
                    // 清零: 优先用 GPU(FBO glClear) 清, 比 CPU memset 1GB 快一个量级
                    const auto t0 = std::chrono::steady_clock::now();
                    bool cleared = false;
                    GLuint fbo = 0;
                    glGenFramebuffers(1, &fbo);
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex_, 0);
                    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
                        glClear(GL_COLOR_BUFFER_BIT);
                        cleared = (glGetError() == GL_NO_ERROR);
                    }
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    glDeleteFramebuffers(1, &fbo);
                    glClearColor(0.05f, 0.067f, 0.09f, 1.0f);
                    const double clearMs = std::chrono::duration<double, std::milli>(
                                               std::chrono::steady_clock::now() - t0).count();
                    if (!cleared) {
                        // 兜底: CPU 清零
                        void* p = nullptr;
                        if (AHardwareBuffer_lock(ahb_, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &p) == 0) {
                            memset(p, 0, (size_t)ahbStride_ * dh_ * 4);
                            AHardwareBuffer_unlock(ahb_, nullptr);
                        }
                    }
                    LOGI("AHB screen %dx%d stride=%d clear=%.1fms(%s)",
                         dw_, dh_, ahbStride_, clearMs, cleared ? "gpu" : "cpu");
                } else {
                    LOGE("EGLImage -> texture err 0x%x", err);
                }
            } else {
                LOGE("eglCreateImageKHR fail");
            }
        } else {
            LOGE("AHardwareBuffer_allocate fail");
        }
    }
    if (!useAhb_) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, dw_, dh_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        LOGI("plain texture %dx%d", dw_, dh_);
    }
    // staging 不再按整画布分配(16383² ≈ 1GB, 每次启动清零要几百 ms), 改成按上传矩形复用
    staging_.assign((size_t)dw_ * dh_, 0u);
    return tex_ != 0;
}

void GlDisplay::shutdown() {
    if (dpy_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (img_ != EGL_NO_IMAGE_KHR && pDestroyImg) { pDestroyImg(dpy_, img_); img_ = EGL_NO_IMAGE_KHR; }
        if (surf_ != EGL_NO_SURFACE) eglDestroySurface(dpy_, surf_);
        if (ctx_ != EGL_NO_CONTEXT) eglDestroyContext(dpy_, ctx_);
        eglTerminate(dpy_);
    }
    surf_ = EGL_NO_SURFACE; ctx_ = EGL_NO_CONTEXT; dpy_ = EGL_NO_DISPLAY;
    if (ahb_) { AHardwareBuffer_release(ahb_); ahb_ = nullptr; }
    tex_ = prog_ = vbo_ = 0;
    win_ = nullptr;
}

void GlDisplay::onSize(int w, int h) {
    if (w <= 0 || h <= 0) return;
    const bool resized = (w != dw_ || h != dh_);
    const bool wasReady = viewReady_ && zoom_ > 0.f;
    // 横竖屏: 保留旧视口中心对应的画布点, 缩放不变(旧项目旋转不丢视角)
    float centerX = 0.f, centerY = 0.f;
    if (wasReady) {
        const float oldAw = (float)std::max(1, vw_ - inL_ - inR_);
        const float oldAh = (float)std::max(1, vh_ - inT_ - inB_);
        centerX = panX_ + oldAw * 0.5f / zoom_;
        centerY = panY_ + oldAh * 0.5f / zoom_;
    }
    vw_ = w; vh_ = h;
    if (dpy_ != EGL_NO_DISPLAY && surf_ != EGL_NO_SURFACE) {
        eglMakeCurrent(dpy_, surf_, surf_, ctx_);
    }
    if (resized) {
        // 横竖屏切换: 屏幕缓冲/纹理/AHB 必须按新尺寸重建, 否则仍按旧宽高采样=强制拉伸
        if (img_ != EGL_NO_IMAGE_KHR && pDestroyImg) { pDestroyImg(dpy_, img_); img_ = EGL_NO_IMAGE_KHR; }
        if (ahb_) { AHardwareBuffer_release(ahb_); ahb_ = nullptr; }
        if (tex_) { glDeleteTextures(1, &tex_); tex_ = 0; }
        useAhb_ = false;
        dw_ = w; dh_ = h; texW_ = w; texH_ = h;
        if (dpy_ != EGL_NO_DISPLAY) createTexture();
        haveDrawFrame_ = false;
        if (wasReady) {
            const float aw = (float)std::max(1, w - inL_ - inR_);
            const float ah = (float)std::max(1, h - inT_ - inB_);
            panX_ = centerX - aw * 0.5f / zoom_;
            panY_ = centerY - ah * 0.5f / zoom_;
            viewReady_ = true;
        } else {
            viewReady_ = false;
        }
        LOGI("display resize buffer %dx%d", dw_, dh_);
    }
    if (!viewReady_) resetView();
    clampPan();
    if (dpy_ != EGL_NO_DISPLAY && surf_ != EGL_NO_SURFACE) glViewport(0, 0, w, h);
}

void GlDisplay::setInsets(int l, int t, int r, int b) {
    const bool wasReady = viewReady_ && zoom_ > 0.f;
    float centerX = 0.f, centerY = 0.f;
    if (wasReady && vw_ > 0 && vh_ > 0) {
        const float oldAw = (float)std::max(1, vw_ - inL_ - inR_);
        const float oldAh = (float)std::max(1, vh_ - inT_ - inB_);
        centerX = panX_ + oldAw * 0.5f / zoom_;
        centerY = panY_ + oldAh * 0.5f / zoom_;
    }
    inL_ = std::max(0, l); inT_ = std::max(0, t);
    inR_ = std::max(0, r); inB_ = std::max(0, b);
    if (vw_ > 0 && vh_ > 0) {
        if (wasReady) {
            const float aw = (float)std::max(1, vw_ - inL_ - inR_);
            const float ah = (float)std::max(1, vh_ - inT_ - inB_);
            panX_ = centerX - aw * 0.5f / zoom_;
            panY_ = centerY - ah * 0.5f / zoom_;
            clampPan();
        } else {
            resetView();
        }
    }
}

void GlDisplay::resetView() {
    const float aw = (float)std::max(1, vw_ - inL_ - inR_);
    const float ah = (float)std::max(1, vh_ - inT_ - inB_);
    float fx = aw / cw_, fy = ah / ch_;
    zoom_ = fx < fy ? fx : fy;
    panX_ = (cw_ - aw / zoom_) * 0.5f;
    panY_ = (ch_ - ah / zoom_) * 0.5f;
    viewReady_ = true;
}

void GlDisplay::setView(float zoom, float panX, float panY) {
    if (zoom <= 0.0f) return;
    zoom_ = zoom;
    panX_ = panX;
    panY_ = panY;
    clampPan();
    viewReady_ = true;
}

void GlDisplay::clampPan() {
    const float aw = (float)std::max(1, vw_ - inL_ - inR_);
    const float ah = (float)std::max(1, vh_ - inT_ - inB_);
    const float viewW = aw / zoom_, viewH = ah / zoom_;
    // 对齐旧项目 ClampPan(pm395): 视口中心相对画布中心的偏移上限 = 画布尺寸 + 视口尺寸。
    // 画布可以整个甩出屏幕(向四周看画布外背景), 只有回中心(resetView)兜底。
    // 旧实现按 panX 直接限 ±(cw+0.75cw), 缩远时视口巨大→可拖范围明显偏小, 主人报"拖不出去看不了外边"。
    const float ccx = cw_ * 0.5f, ccy = ch_ * 0.5f;
    const float limX = (float)cw_ + viewW;
    const float limY = (float)ch_ + viewH;
    const float offX = std::max(-limX, std::min(panX_ + viewW * 0.5f - ccx, limX));
    const float offY = std::max(-limY, std::min(panY_ + viewH * 0.5f - ccy, limY));
    panX_ = ccx + offX - viewW * 0.5f;
    panY_ = ccy + offY - viewH * 0.5f;
}

// 旧项目 MinZoom=0.8×适配: 允许缩到适配的 80%, 画布四周露出背景(看画布外)。
// 最大放大保持现有 64px/画布px(不比旧项目浅), 不动。
static void applyZoomLimits(float fit, float* nz) {
    const float minZ = fit * 0.8f;
    const float maxZ = std::max(64.0f, fit);
    if (*nz < minZ) *nz = minZ;
    if (*nz > maxZ) *nz = maxZ;
}

void GlDisplay::zoomAt(float factor, float sx, float sy) {
    const float aw = (float)std::max(1, vw_ - inL_ - inR_);
    const float ah = (float)std::max(1, vh_ - inT_ - inB_);
    float fit = std::min(aw / cw_, ah / ch_);
    float nz = zoom_ * factor;
    applyZoomLimits(fit, &nz);
    if (nz == zoom_) return;
    // 保持手指下的画布点不动
    float cx = (sx - inL_) / zoom_ + panX_;
    float cy = (sy - inT_) / zoom_ + panY_;
    zoom_ = nz;
    panX_ = cx - (sx - inL_) / zoom_;
    panY_ = cy - (sy - inT_) / zoom_;
    clampPan();
}

void GlDisplay::beginZoomGesture(float sx, float sy) {
    zoomGesture_ = true;
    zgBaseZoom_ = zoom_;
    zgCanvasX_ = (sx - inL_) / zoom_ + panX_;
    zgCanvasY_ = (sy - inT_) / zoom_ + panY_;
}

void GlDisplay::zoomGestureTo(float totalFactor, float sx, float sy) {
    if (!zoomGesture_) { beginZoomGesture(sx, sy); return; }
    const float aw = (float)std::max(1, vw_ - inL_ - inR_);
    const float ah = (float)std::max(1, vh_ - inT_ - inB_);
    const float fit = std::min(aw / cw_, ah / ch_);
    float nz = zgBaseZoom_ * std::max(0.01f, totalFactor);
    applyZoomLimits(fit, &nz);
    zoom_ = nz;
    // 手势起点手指下的画布点始终跟住当前（平滑后的）双指中点
    panX_ = zgCanvasX_ - (sx - inL_) / zoom_;
    panY_ = zgCanvasY_ - (sy - inT_) / zoom_;
    clampPan();
}

void GlDisplay::endZoomGesture() { zoomGesture_ = false; }

void GlDisplay::panBy(float dxPx, float dyPx) {
    panX_ -= dxPx / zoom_;
    panY_ -= dyPx / zoom_;
    clampPan();
}

uint32_t* GlDisplay::beginUpdate(int x, int y, int w, int h, int* stridePx) {
    if (w <= 0 || h <= 0) return nullptr;
    upX_ = x; upY_ = y; upW_ = w; upH_ = h;
    // 屏幕缓冲: staging 固定屏幕大小, 按屏幕矩形写, 再整块拷进屏幕 AHB
    if ((size_t)dw_ * dh_ != staging_.size()) staging_.assign((size_t)dw_ * dh_, 0u);
    upPtr_ = staging_.data() + (size_t)y * dw_ + x;
    *stridePx = dw_;
    updating_ = true;
    return upPtr_;
}

bool GlDisplay::endUpdate() {
    if (!updating_) return false;
    updating_ = false;
    if (useAhb_) {
        ARect r{ upX_, upY_, upX_ + upW_, upY_ + upH_ };
        void* p = nullptr;
        if (AHardwareBuffer_lock(ahb_, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, &r, &p) != 0) {
            // 横竖屏重建 AHB 的那几帧, 合成器可能还占着缓冲: 锁失败不能把"已重绘"标记清掉,
            // 否则屏幕会一直停在空白 AHB 上, 直到下一次输入才重绘。
            static int lockFailLog = 0;
            if (lockFailLog < 3) { LOGI("AHB lock fail, retry next frame"); lockFailLog++; }
            return false;
        }
        const uint8_t* src = (const uint8_t*)upPtr_;
        // lock(rect) 返回缓冲区基址, 目标要自己加矩形偏移
        uint8_t* dst = (uint8_t*)p + ((size_t)upY_ * ahbStride_ + upX_) * 4;
        const int strideBytes = ahbStride_ * 4;
        const int rowBytes = upW_ * 4;
        for (int y = 0; y < upH_; y++)
            memcpy(dst + (size_t)y * strideBytes, src + (size_t)y * dw_ * 4, rowBytes);
        AHardwareBuffer_unlock(ahb_, nullptr);
    } else {
        if (!tex_) return false;
        glBindTexture(GL_TEXTURE_2D, tex_);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, dw_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, upX_, upY_, upW_, upH_, GL_RGBA, GL_UNSIGNED_BYTE, upPtr_);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    }
    // 这一帧已真正进入屏幕缓冲; 棋盘格/网格跟随这一帧的变换, 不跟正在变化的 zoom_/pan_
    drawZoom_ = zoom_;
    drawPanX_ = panX_;
    drawPanY_ = panY_;
    drawRot25_ = rot25Deg_;
    drawBaked_ = bakeChecker_;
    haveDrawFrame_ = true;
    return true;
}

void GlDisplay::computeFit(float* scaleX, float* offsetX, float* scaleY, float* offsetY) const {
    // screen = (canvas - pan) * zoom ; aPos 是画布比例 [0,1]
    *scaleX = 2.0f * cw_ * zoom_ / vw_;
    *offsetX = (2.0f * inL_ - 2.0f * panX_ * zoom_) / vw_ - 1.0f;
    // GL 的 NDC y 向上, 画布 y 向下: 取负号
    *scaleY = -2.0f * ch_ * zoom_ / vh_;
    *offsetY = (2.0f * panY_ * zoom_ - 2.0f * inT_) / vh_ + 1.0f;
}

void GlDisplay::setOverlay(bool checker, bool grid) { chkOn_ = checker; gridOn_ = grid; }

void GlDisplay::setStyle(float gridW, float gridH, float gr, float gg, float gb, float ga,
                         float lineW, float chkTile, float ar, float ag, float ab,
                         float br, float bg, float bb, float bgr, float bgg, float bgb) {
    gridW_ = std::max(1.0f, gridW); gridH_ = std::max(1.0f, gridH);
    gridR_ = gr; gridG_ = gg; gridB_ = gb; gridA_ = std::max(0.0f, std::min(1.0f, ga));
    gridLineW_ = std::max(0.25f, std::min(20.0f, lineW));
    chkTile_ = std::max(1.0f, std::min(64.0f, chkTile));
    chkAR_ = ar; chkAG_ = ag; chkAB_ = ab;
    chkBR_ = br; chkBG_ = bg; chkBB_ = bb;
    bgR_ = bgr; bgG_ = bgg; bgB_ = bgb;
}

void GlDisplay::setSelection(int x0, int y0, int x1, int y1, bool active) {
    selActive_ = active;
    selX0_ = x0; selY0_ = y0; selX1_ = x1; selY1_ = y1;
    if (!antsVbo_) return;
    const float fx0 = (float)x0 / cw_, fx1 = (float)(x1 + 1) / cw_;
    const float fy0 = (float)y0 / ch_, fy1 = (float)(y1 + 1) / ch_;
    const float v[] = {
        fx0, fy0, fx1, fy0,
        fx1, fy0, fx1, fy1,
        fx1, fy1, fx0, fy1,
        fx0, fy1, fx0, fy0
    };
    glBindBuffer(GL_ARRAY_BUFFER, antsVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof v, v, GL_DYNAMIC_DRAW);
    antsVertCount_ = 8;
}

// 套索/魔棒轮廓: 直接把线段的画布坐标归一化后喂给蚂蚁线 shader
// (顶点着色器: aPos * uScale + uOffset, aPos 是画布比例 [0,1])
void GlDisplay::setSelectionOutline(const std::vector<int>& segs) {
    if (!antsVbo_ || !selActive_) return;
    if (segs.size() < 4) {
        setSelection(selX0_, selY0_, selX1_, selY1_, true);   // 没轮廓就回到 bbox
        return;
    }
    std::vector<float> v;
    v.reserve(segs.size());
    for (size_t i = 0; i + 1 < segs.size(); i += 2) {
        v.push_back((float)segs[i] / (float)cw_);
        v.push_back((float)segs[i + 1] / (float)ch_);
    }
    glBindBuffer(GL_ARRAY_BUFFER, antsVbo_);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)(v.size() * sizeof(float)), v.data(), GL_DYNAMIC_DRAW);
    antsVertCount_ = (GLsizei)(v.size() / 2);
}

bool GlDisplay::resizeCanvas(int w, int h) {
    if (w <= 0 || h <= 0) return false;
    // 纹理保持初始尺寸(texW_/texH_), 只改逻辑尺寸; 绘制时用 uUvScale 取左上子区域。
    // 这样裁剪不触发任何显存重分配(16383² 的 AHB 重建会失败并黑屏)。
    cw_ = w; ch_ = h;
    resetView();
    return true;
}

bool GlDisplay::setFloat(const uint32_t* px, int w, int h, int x, int y) {
    clearFloat();
    if (!px || w <= 0 || h <= 0) return false;
    if ((double)w * h > 64.0e6) return false;          // 太大走 CPU 回退

    // 贴图分辨率按当前缩放取: 适应窗口时 6千² 只需几百 px,
    // 否则一次性上传 150MB 会卡住几百毫秒。
    // 贴图分辨率按当前缩放取(最多 2 倍超采样): 适应窗口时 6千² 只需 ~800px,
    // 否则一次性上传 150MB 会卡住几百毫秒。
    float scale = std::min(1.0f, zoom_ * 2.0f);
    if (scale < 0.02f) scale = 0.02f;
    const int tw2 = std::max(1, (int)(w * scale));
    const int th2 = std::max(1, (int)(h * scale));

    std::vector<uint32_t> tmp((size_t)tw2 * th2);
    if (scale >= 0.999f) {
        memcpy(tmp.data(), px, (size_t)w * h * 4);
    } else {
        // 最近邻降采样
        for (int ty = 0; ty < th2; ty++) {
            const int sy = (int)((float)ty / scale);
            const uint32_t* srow = px + (size_t)std::min(sy, h - 1) * w;
            uint32_t* drow = tmp.data() + (size_t)ty * tw2;
            for (int tx = 0; tx < tw2; tx++) {
                drow[tx] = srow[(int)((float)tx / scale)];
            }
        }
    }

    const int T = 1024;
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    for (int ty = 0; ty < th2; ty += T) {
        const int th = std::min(T, th2 - ty);
        for (int tx = 0; tx < tw2; tx += T) {
            const int tw = std::min(T, tw2 - tx);
            GLuint tex = 0;
            glGenTextures(1, &tex);
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, tw2);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                         tmp.data() + (size_t)ty * tw2 + tx);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            if (glGetError() != GL_NO_ERROR) { glDeleteTextures(1, &tex); clearFloat(); return false; }
            // 记录"纹理 tile 相对于浮起区域的画布尺寸"
            floatTiles_.push_back({ tex, (int)(tx / scale), (int)(ty / scale),
                                    (int)std::ceil(tw / scale), (int)std::ceil(th / scale) });
        }
    }
    floatW_ = w; floatH_ = h; floatX_ = x; floatY_ = y;
    haveFloat_ = !floatTiles_.empty();
    return haveFloat_;
}

void GlDisplay::moveFloat(int x, int y) {
    floatX_ = x; floatY_ = y;
}

void GlDisplay::clearFloat() {
    for (auto& t : floatTiles_) if (t.tex) glDeleteTextures(1, &t.tex);
    floatTiles_.clear();
    floatFromCanvas_ = false;
    haveFloat_ = false;
    floatW_ = floatH_ = 0;
}

bool GlDisplay::setFloatScaled(const uint32_t* px, int w, int h, int logicalW, int logicalH, int dx, int dy) {
    clearFloat();
    if (!px || w <= 0 || h <= 0) return false;
    const int T = 1024;
    for (int ty = 0; ty < h; ty += T) {
        const int th = std::min(T, h - ty);
        for (int tx = 0; tx < w; tx += T) {
            const int tw = std::min(T, w - tx);
            GLuint tex = 0;
            glGenTextures(1, &tex);
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            std::vector<uint32_t> tile((size_t)tw * th);
            for (int r = 0; r < th; r++)
                memcpy(&tile[(size_t)r * tw], px + (size_t)(ty + r) * w + tx, (size_t)tw * 4);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, tw, th, 0, GL_RGBA, GL_UNSIGNED_BYTE, tile.data());
            if (glGetError() != GL_NO_ERROR) { glDeleteTextures(1, &tex); clearFloat(); return false; }
            floatTiles_.push_back({ tex, tx * logicalW / w, ty * logicalH / h,
                                    (int)std::ceil((float)tw * logicalW / w),
                                    (int)std::ceil((float)th * logicalH / h) });
        }
    }
    floatW_ = logicalW; floatH_ = logicalH; floatX_ = dx; floatY_ = dy;
    haveFloat_ = !floatTiles_.empty();
    LOGI("floatScaled %dx%d logical %dx%d tiles=%zu have=%d err=0x%x",
         w, h, logicalW, logicalH, floatTiles_.size(), (int)haveFloat_, glGetError());
    return haveFloat_;
}

void GlDisplay::draw() {
    if (surf_ == EGL_NO_SURFACE) return;
    glViewport(0, 0, vw_, vh_);
    glClear(GL_COLOR_BUFFER_BIT);
    float sx, ox, sy, oy;
    computeFit(&sx, &ox, &sy, &oy);
    glUseProgram(prog_);
    // 主 quad 现在显示"屏幕缓冲"(1:1), 画布变换已由 CPU 视口合成完成
    glUniform2f(uScale_, 2.0f, 2.0f);
    glUniform2f(uOffset_, -1.0f, -1.0f);
    // AHB 行序是屏幕自上而下, GL 纹理 v=0 在窗口底部 → 采样 V 必须翻转, 否则整屏上下颠倒
    glUniform2f(uUvScale_, 1.0f, -1.0f);
    glUniform2f(uUvOffset_, 0.0f, 1.0f);
    // 棋盘格/网格裁剪框必须和 CPU 合成真正写过的像素范围一致:
    // 合成按屏幕像素的"左上角"映射画布坐标(cx=(sx-inL)/zoom+panX), 所以画布覆盖的屏幕像素是
    // ceil(left)..ceil(right)-1 / ceil(top)..ceil(bottom)-1。之前用 floor/ceil 外扩,
    // 画布上边/左边会多出一像素: 该像素合成是透明的(0), 着色器却判 inside 画出棋盘格 → "棋盘格的缝"。
    // 棋盘格/网格/画布边界使用"已上传屏幕帧"的变换; 未上传过帧时才用实时变换。
    const float dz = haveDrawFrame_ ? drawZoom_ : zoom_;
    const float dpx = haveDrawFrame_ ? drawPanX_ : panX_;
    const float dpy = haveDrawFrame_ ? drawPanY_ : panY_;
    const float drot = haveDrawFrame_ ? drawRot25_ : rot25Deg_;
    const float cl = (0.0f - dpx) * dz + (float)inL_;
    const float ct = (0.0f - dpy) * dz + (float)inT_;
    const float cr = ((float)cw_ - dpx) * dz + (float)inL_;
    const float cb = ((float)ch_ - dpy) * dz + (float)inT_;
    const int ix0 = (int)std::ceil(cl);
    const int iy0 = (int)std::ceil(ct);
    const int ix1 = (int)std::ceil(cr) - 1;
    const int iy1 = (int)std::ceil(cb) - 1;
    // 传给着色器的界用像素中心坐标(+0.5), 与 gl_FragCoord 一致
    glUniform4f(uCanvasRect_, (float)ix0 + 0.5f, (float)vh_ - (float)iy1 - 0.5f,
                (float)ix1 + 0.5f, (float)vh_ - (float)iy0 - 0.5f);
    const float orgX = (0.0f - dpx) * dz + (float)inL_;
    const float orgY = (0.0f - dpy) * dz + (float)inT_;
    glUniform2f(uOrigin_, orgX, (float)vh_ - orgY);
    glUniform1f(uZoom_, dz);
    // 2.5D 视角旋转: 棋盘格/网格/画布边界跟着内容一起转
    glUniform1f(uRot_, drot * 3.14159265358979f / 180.0f);
    glUniform2f(uCenter_, orgX + cw_ * 0.5f * dz, (float)vh_ - orgY - ch_ * 0.5f * dz);
    glUniform2f(uCanvasSize_, (float)cw_, (float)ch_);
    glUniform1f(uChkOn_, (chkOn_ && !drawBaked_) ? 1.0f : 0.0f);
    glUniform1f(uGridOn_, gridOn_ ? 1.0f : 0.0f);
    glUniform1f(uGridW_, gridW_);
    glUniform1f(uGridH_, gridH_);
    glUniform3f(uGridColor_, gridR_, gridG_, gridB_);
    glUniform1f(uGridAlpha_, gridA_);
    glUniform1f(uGridLineW_, gridLineW_);
    glUniform1f(uChkTile_, chkTile_);
    glUniform3f(uChkA_, chkAR_, chkAG_, chkAB_);
    glUniform3f(uChkB_, chkBR_, chkBG_, chkBB_);
    glUniform3f(uBgColor_, bgR_, bgG_, bgB_);
    glUniform1f(uGray_, grayView_ ? 1.0f : 0.0f);
    glUniform1f(uLoupeOn_, loupeOn_ ? 1.0f : 0.0f);
    // 屏幕坐标(y 向下) → frag 坐标(y 向上)
    glUniform4f(uLoupeRect_, (float)loupeX0_, (float)(vh_ - loupeY1_),
                (float)loupeX1_, (float)(vh_ - loupeY0_));
    glUniform1i(uTex_, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    if (haveFloat_ && floatFromCanvas_) {
        glUseProgram(prog_);
        glUniform1i(uTex_, 0);
        const float cx0 = (float)floatX_, cy0 = (float)floatY_;
        const float cx1 = cx0 + floatW_, cy1 = cy0 + floatH_;
        const float nx0 = 2.0f * ((cx0 - panX_) * zoom_ + inL_) / vw_ - 1.0f;
        const float nx1 = 2.0f * ((cx1 - panX_) * zoom_ + inL_) / vw_ - 1.0f;
        const float ny0 = 1.0f - 2.0f * ((cy0 - panY_) * zoom_ + inT_) / vh_;
        const float ny1 = 1.0f - 2.0f * ((cy1 - panY_) * zoom_ + inT_) / vh_;
        glUniform2f(uScale_, nx1 - nx0, ny1 - ny0);
        glUniform2f(uOffset_, nx0, ny0);
        glUniform2f(uUvScale_, (float)floatW_ / texW_, (float)floatH_ / texH_);
        glUniform2f(uUvOffset_, (float)floatSrcX_ / texW_, (float)floatSrcY_ / texH_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex_);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisable(GL_BLEND);
    } else if (haveFloat_ && !floatTiles_.empty()) {
        glUseProgram(floatProg_);
        glUniform1i(fTex_, 0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        for (const auto& t : floatTiles_) {
            const float cx0 = (float)(floatX_ + t.ox), cy0 = (float)(floatY_ + t.oy);
            const float cx1 = cx0 + t.w, cy1 = cy0 + t.h;
            const float nx0 = 2.0f * ((cx0 - panX_) * zoom_ + inL_) / vw_ - 1.0f;
            const float nx1 = 2.0f * ((cx1 - panX_) * zoom_ + inL_) / vw_ - 1.0f;
            const float ny0 = 1.0f - 2.0f * ((cy0 - panY_) * zoom_ + inT_) / vh_;
            const float ny1 = 1.0f - 2.0f * ((cy1 - panY_) * zoom_ + inT_) / vh_;
            glUniform2f(fScale_, nx1 - nx0, ny1 - ny0);
            glUniform2f(fOffset_, nx0, ny0);
            glUniform2f(fUvScale_, 1.0f, 1.0f);
            glUniform2f(fUvOffset_, 0.0f, 0.0f);
            glBindTexture(GL_TEXTURE_2D, t.tex);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        glDisable(GL_BLEND);
    }

    if (selActive_ && antsProg_ && antsVbo_) {
        phase_ += 0.035f;
        glUseProgram(antsProg_);
        glUniform2f(antsScale_, sx, sy);
        glUniform2f(antsOffset_, ox, oy);
        glUniform1f(antsPhase_, phase_);
        glBindBuffer(GL_ARRAY_BUFFER, antsVbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glDrawArrays(GL_LINES, 0, antsVertCount_);
    }
    eglSwapBuffers(dpy_, surf_);
}

void GlDisplay::canvasRectToScreen(int x0, int y0, int x1, int y1,
                                   int* sx0, int* sy0, int* sx1, int* sy1) const {
    if (!rotation25Active()) {
        *sx0 = (int)std::floor((x0 - panX_) * zoom_ + inL_);
        *sy0 = (int)std::floor((y0 - panY_) * zoom_ + inT_);
        *sx1 = (int)std::ceil((x1 + 1 - panX_) * zoom_ + inL_);
        *sy1 = (int)std::ceil((y1 + 1 - panY_) * zoom_ + inT_);
        return;
    }
    // 旋转后取四角屏幕包围盒
    const float xs[4] = {(float)x0, (float)(x1 + 1), (float)x0, (float)(x1 + 1)};
    const float ys[4] = {(float)y0, (float)y0, (float)(y1 + 1), (float)(y1 + 1)};
    float mnx = 1e9f, mny = 1e9f, mxx = -1e9f, mxy = -1e9f;
    for (int i = 0; i < 4; i++) {
        float sx = 0.f, sy = 0.f;
        canvasToScreenF(xs[i], ys[i], &sx, &sy);
        mnx = std::min(mnx, sx); mxx = std::max(mxx, sx);
        mny = std::min(mny, sy); mxy = std::max(mxy, sy);
    }
    *sx0 = (int)std::floor(mnx); *sy0 = (int)std::floor(mny);
    *sx1 = (int)std::ceil(mxx); *sy1 = (int)std::ceil(mxy);
}

bool GlDisplay::screenToCanvas(float sx, float sy, int* cx, int* cy) const {
    if (vw_ <= 0 || vh_ <= 0 || zoom_ <= 0) return false;
    float fx = 0.f, fy = 0.f;
    screenToCanvasF(sx, sy, &fx, &fy);
    int x = (int)fx;
    int y = (int)fy;
    *cx = x; *cy = y;
    return x >= 0 && y >= 0 && x < cw_ && y < ch_;
}

} // namespace endpix
