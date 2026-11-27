# Contributing

感谢参与 Endpix Native。

## 开发环境

见 [README.md](README.md) 的「构建」一节。提交前至少运行：

```bash
./gradlew compileDebugKotlin
./gradlew assembleDebug
```

涉及 `app/src/main/cpp` 的修改请同时在 arm64 真机上验证，模拟器的 arm 转译不保证 GLES/NEON 路径一致。

## 提交约定

- 一个 PR 只做一件事；大改先开 issue 说明方案。
- 不提交 `local.properties`、keystore、APK、构建日志、截图测试产物。
- 不提交真实代理节点、API Key、Cookie、账号密码或第三方付费资源。
- 新资源请附上来源与许可证，并更新 [NOTICE.md](NOTICE.md)。
- Kotlin 遵循现有 Compose 风格；C++ 保持 `snake_case` 成员 / `CamelCase` 方法的现状，避免无关格式化。

## 贡献许可

提交代码即表示你同意以本仓库的 PolyForm Noncommercial 1.0.0 许可证授权你的贡献（仅限非商业用途）。第三方代码 / 资源请单独标明许可证。
