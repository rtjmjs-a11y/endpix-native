# Endpix Native

像素画编辑器 **endpix** 的原生 Android 重写版。UI 使用 Kotlin + Jetpack Compose，画布与渲染使用 C++20 / NEON / OpenGL ES 3.0，目标平台为 arm64 Android。

> **仅限非商业使用，不收取费用。** 源码以 [PolyForm Noncommercial 1.0.0](LICENSE) 发布：个人学习、研究、爱好项目、教育/慈善/政府等非商业用途可免费使用，**禁止商业使用**。第三方组件与资源见 [NOTICE.md](NOTICE.md)。仓库已移除线上代理节点、第三方二进制和授权不明确的字体。

## 功能

- 像素画笔 / 橡皮 / 油漆桶，笔刷形状、硬度、间距、散射、长宽比、角度、流量
- 图层：正常、平铺、剪切蒙版、2.5D 叠层、透明度 / 混合模式 / 锁定 / 显隐
- 框选：矩形 / 套索 / 魔棒、浮层移动、拉伸旋转、四角透视变形、裁剪 / 复制 / 导入
- 动画：多帧、时间轴、洋葱皮、播放、帧标签
- 过程回放：项目画布内播放，步骤 / 轨迹模式
- 导出：PNG / WebP / GIF / PSD / 精灵表 / 逐帧 / 2.5D 叠层，带水印和实时预览
- 调色板：6–60 色、槽位编辑、预设导入导出、HSV、取色器、两色混合
- 大画布：上限 16834×16834（受设备内存限制）
- 标签页、自动保存 / 快照恢复、缓存项目、模板、参考图、小地图
- 可选：内置浏览器、PDF、翻译、代理 / 网络工具、应用内更新检查

## 工程结构

```
app/src/main/java/com/endpix/nativeapp/
  MainActivity.kt          SurfaceView + 触摸路由 + JNI + 偏好持久化
  ui/                      Compose UI（画布控件、面板、对话框、回放等）
app/src/main/cpp/
  canvas/                  稀疏 tile 画布、mip、裁剪 / 移动 / 撤销记录
  raster/                  NEON 混合、笔刷 stamp、洪水填充、蒙版合成
  display/                 EGL + AHardwareBuffer + GLES3 主渲染
  engine/                  渲染线程、输入队列、选区、导出、回放、JNI 桥
  bench/                   画布 / 魔棒基准
app/src/main/assets/       默认设置、模板、字体（见下方可选资源）
```

## 构建

环境：

- JDK 17+
- Android SDK Platform 36 / Build-Tools 36.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Gradle Wrapper 8.13（仓库自带）

```bash
git clone https://github.com/rtjmjs-a11y/endpix-native.git
cd endpix-native

# 创建 local.properties，写入本机 SDK 路径（不要提交）
# sdk.dir=C:/Users/you/AppData/Local/Android/Sdk

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

只构建 `arm64-v8a`。真机或支持 arm64 的模拟器均可运行。

## 可选资源（不随仓库分发）

以下文件因凭据、体积或授权原因未提交，缺失时应用会降级运行：

| 文件 | 用途 | 说明 |
| --- | --- | --- |
| `app/src/main/jniLibs/arm64-v8a/libxray.so` | 代理内核 | Xray-core，MPL-2.0；需自行编译或获取，重命名为 `libxray.so` |
| `app/src/main/assets/default_proxy_good.txt` | 默认代理节点 | 含真实节点凭据，未提交；缺失时代理面板为空 |
| `app/src/main/assets/default_proxy_auto_sources.txt` | 代理订阅源 | 未提交，可自行添加公开订阅 |
| `app/src/main/assets/adhosts.txt` | 浏览器广告域名表 | 未提交，缺失时不启用内置规则 |
| `app/src/main/assets/fonts/DingMao9.ttf` 等 | 三套像素字体 | 授权不明确，未提交；缺失时文字工具回退系统字体 |

`fonts/ArkPixel12.otf` 以 SIL Open Font License 1.1 分发，已包含。

## 隐私与网络

- 仓库不包含任何 API Key、代理节点密码、签名证书或账号凭据。
- 应用内的浏览器、代理、Pinterest 登录助手、更新检查等功能会产生网络访问；请自行确认所在地区法律与第三方服务条款。
- 应用不上传画布内容；录制、缓存和导出默认写入应用私有目录或用户选择的文件。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。提交前请确认没有把 `local.properties`、keystore、代理节点或 API Key 带进 diff。

## 许可证

代码以 [PolyForm Noncommercial 1.0.0](LICENSE) 发布：允许个人学习、研究、实验、爱好项目，以及教育、慈善、公共研究、公共安全/健康、环保和政府机构等非商业用途；**不允许商业使用**。第三方组件与资源的许可证见 [NOTICE.md](NOTICE.md)。

> 由于禁止商业使用，本仓库属于 **source-available（源码公开）**，不是 OSI 定义的开源许可证。
