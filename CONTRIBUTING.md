# 参与贡献

欢迎通过 Issue 描述复现步骤和改进建议，或提交 Pull Request。

## 本地验证

1. 使用完整 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。
2. 按 README 配置本机 SDK 路径。
3. 运行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。
4. 修改界面时，在 `tools/` 执行 `npm ci`、`npx playwright install chromium`、`npm test`。

UI 测试使用桥接替身和保留地址段内的虚拟服务器数据，不需要真实 SSH 凭据。测试截图写入被 Git 忽略的 `tools/test-results/`。可以设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 使用已有 Chromium 浏览器。

提交中不要包含账号凭据、真实服务器地址、私钥、签名文件、`local.properties` 或构建缓存。问题日志请先脱敏。

涉及 SSH、Keystore、会话恢复或后台服务的改动，请说明 Android 版本和实际联调范围。浏览器 UI 测试不能替代 Android 真机与 Linux 服务器的端到端测试。

贡献的项目代码沿用 MIT 许可证；第三方内容保留各自的许可。
