# Codex Pocket

[![Android and UI checks](https://github.com/Tombears/codex-pocket/actions/workflows/android.yml/badge.svg)](https://github.com/Tombears/codex-pocket/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

面向安卓的独立 Codex SSH 客户端。手机通过 SSH 启动 Linux 服务器上的 `codex app-server`，以聊天形式发送任务、接收流式消息和处理审批。非 OpenAI 官方应用。

## 界面预览

以下为使用测试数据生成的界面预览；实际 APK 初次启动为空工作区。

<p>
  <img src="docs/images/mobile-home.png" width="280" alt="深色主页预览">
  <img src="docs/images/mobile-chat.png" width="280" alt="Codex 对话与命令输出预览">
</p>

![大屏分组侧栏预览](docs/images/desktop-layout.png)

## 首版功能

- 每台虚拟机/云服务器作为一级工作区，分别保存所属会话。
- SSH 密码认证、OpenSSH/PEM 私钥认证及私钥口令。
- 首次连接显示 SHA-256 主机指纹；固定密钥后，变化会阻止连接。
- 新建聊天时选择虚拟机和 Linux 绝对工作目录。
- 聊天记录搜索、重命名、删除本地记录。
- 按服务器导入 Codex 远程会话，按需读取远程历史，继续原 thread。
- 流式回答、代码块、命令执行输出、文件改动、执行审批和问题回答。
- 停止当前任务、断开单台或全部服务器连接。
- 深色默认，可切换浅色；手机抽屉侧栏，大屏双栏布局。
- Android 前台服务维持连接并提示待审批事项。
- 密码、私钥和聊天记录统一使用 Android Keystore 生成的 AES-GCM 密钥加密保存；不进入网页初始化数据。关闭系统备份。

## 安装与使用

支持 Android 8.0（API 26）及以上，需要较新的 Android System WebView。可以自行构建 APK，或在 [GitHub Actions](https://github.com/Tombears/codex-pocket/actions/workflows/android.yml) 成功构建页面的 Artifacts 下载 `codex-pocket-debug-and-reports`，解压后安装 APK。下载构建产物可能需要登录 GitHub。

本地交付的测试包名为 `CodexPocket-0.1.0-debug.apk`。当前是 debug 签名测试版，未发布应用商店；GitHub Actions 每次构建可能生成不同的 debug 签名，覆盖安装需使用相同签名。更新前请注意手机本地记录的保留。

首次使用前，在你的 Linux 服务器中以将来 SSH 登录的**同一用户**完成准备：

```bash
curl -fsSL https://chatgpt.com/codex/install.sh | sh
codex --version
codex login --device-auth
```

按照命令输出，在手机浏览器中完成账号登录。账号需要具备 Codex 使用权限；设备代码登录可能需要在 ChatGPT 安全设置中启用。API key 登录也可以事先在服务器上配置；应用不要求手机再保存 OpenAI API key。

准备一个真实存在的工作目录，例如 `/home/ubuntu/project`。建议先用普通 SSH 客户端确认用户名、端口和凭据可正常登录。

打开应用 → 添加虚拟机 → 填写名称、地址、端口、用户名、认证方式、工作目录、Codex 路径 → 保存 → 连接。首次连接先在云控制台核对主机指纹，确认后信任。

连接使用 `bash -lc` 启动 Codex，自动将 `$HOME/.local/bin`、`$HOME/.npm-global/bin` 和 `/usr/local/bin` 加入 PATH。如果通过 nvm 安装，且登录 shell 找不到 Codex，请填写完整可执行路径。启动脚本不要向 stdout 打印欢迎横幅；stdout 用于 JSON 协议。

不需要开放除 SSH 之外的 Codex HTTP/WebSocket 端口，不需要安装额外桥接服务。SSH 使用自定义端口时同步修改配置。

在左侧选择虚拟机，新建聊天、选择目录并输入任务。首次可以输入：

> 查看当前项目结构，介绍如何启动，先不要修改文件。

电脑上已有的 Codex 聊天可以在顶部虚拟机信息中选择「导入」，随后点击对应记录。聊天选项中的「刷新远程记录」会以服务器记录替换手机缓存中的消息。

## 使用边界

- 应用操作的是服务器上的 Codex 会话、文件和命令。Linux 图形桌面的鼠标操作不包含在首版内。
- 服务器需已安装、登录 Codex。应用不会自行安装服务器软件或代替账号登录。
- 同一服务器一次只运行一个由本应用发起的任务；可以同时连接多台服务器。外部终端或其他客户端的并行任务不受应用约束。
- 使用 `on-request` 审批、`workspace-write` 沙箱和用户审批路由，遵循服务器托管策略；不提供跳过审批开关。
- 不支持的交互请求会被明确拒绝，并显示说明。首次支持命令/文件审批及 `item/tool/requestUserInput` 问题回答；复杂 MCP 表单和额外权限请求不提供授权界面。
- 前台服务可降低后台断连概率，但系统回收、网络切换、省电策略、强制停止应用仍可能关闭 SSH。**不保证断网后任务继续运行，也不自动重发任务。**
- 断连后聊天上下文可使用远程 thread ID 恢复；已执行的文件改动不会因为断连而撤销。继续操作前先刷新记录并核对服务器状态。
- 首版无离线任务队列、自动重试、SFTP 文件浏览、跳板机、端口转发配置、跨手机历史同步或凭据导出。
- 删除手机记录不会删除服务器文件或归档远程会话。卸载应用会失去本地密钥和记录；服务器上的 Codex 会话仍在。
- 私钥编辑时留空保留原值。要完全更换认证凭据或清除保存的私钥口令，可删除该连接再添加。

## 构建

这是 Java 17 + Android WebView 界面 + 原生 SSH/前台服务项目。界面资源随 APK 离线打包，WebView 仅加载应用资源域名，禁止跳转及外部资源访问，不提供公网网页调用原生 SSH 的入口。

依赖：Gradle 8.9、Android Gradle Plugin 8.7.3、Android SDK Platform 35、Build Tools 35.0.0、完整 JDK 17+（含 jlink）。

用 Android Studio 打开目录并配置 SDK，或创建 `local.properties`：

```properties
sdk.dir=你的AndroidSDK目录
```

执行：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。若需后续覆盖安装，请使用同一个签名密钥；源码压缩包不含私钥或签名文件。正式发布前应配置自己的 release 签名并进行真机联调。

界面测试在 `tools/` 运行：

```bash
npm ci
npx playwright install chromium
npm test
```

Linux CI 安装浏览器时使用 `npx playwright install --with-deps chromium`。测试使用原生桥接替身，不需要服务器或 OpenAI 凭据。

主要实现：

- `MainActivity.java`：受限本地资源 WebView、原生桥接和生命周期。
- `CodexService.java`：前台服务、连接管理与操作分发。
- `CodexEngine.java`：SSH、指纹固定、Codex 会话、历史、审批。
- `Rpc.java`：JSON-lines 请求关联、流式通知、断线失败处理。
- `Store.java`：Android Keystore/AES-GCM 持久化及凭据脱敏。
- `app/src/main/assets/ui/`：深浅色响应式界面。

## 验证记录

已完成 debug APK 构建；JSON-RPC 回包关联、通知与审批流不阻塞读取、错误响应、EOF 中断、远程启动命令参数校验共 5 项单元测试。浏览器以原生桥接替身验证了 11 类界面流程，覆盖 360/412 像素手机布局、分组/搜索、新建会话的服务器和目录归属、发送目标、消息 HTML 转义、审批归属、主题、凭据表单、绝对路径校验及主机信任取消。

Android Lint 无错误，剩余两项提示为首版 targetSdk 34 兼容目标及本地界面必须启用 JavaScript；JavaScript 仅在受限本地资源域使用，远程消息均转义后渲染。

协议字段对照本机 Codex CLI 0.159.0 生成的稳定 JSON Schema，并参考 OpenAI Docs。不同服务器版本可能存在协议差异。

**没有使用你的真实服务器或 SSH 凭据，尚未完成 Android 真机上的端到端 SSH/Codex 联调。** 浏览器预览中的地址和聊天标题为测试数据，APK 首次安装为空工作区。

## 上游资料与依赖

- [OpenAI Docs：Codex App Server](https://learn.chatgpt.com/docs/app-server)
- [OpenAI Docs：Codex CLI](https://learn.chatgpt.com/docs/cli)
- [OpenAI Docs：Authentication](https://learn.chatgpt.com/docs/auth)
- [mwiede/JSch](https://github.com/mwiede/jsch)：SSH 实现，BSD 风格许可。
- [Bouncy Castle](https://www.bouncycastle.org/licence.html)：现代 SSH 密钥算法支持，Bouncy Castle 许可。
- [Android desugar libraries](https://github.com/google/desugar_jdk_libs)：较旧 Android 的 Java API 支持，采用上游许可证。

版本：0.1.0；日期：2026-10-29（Asia/Shanghai）。

## 开源许可

项目代码以 [MIT](LICENSE) 许可证开源，版权归 Tombears。第三方依赖不改用本项目许可证，请保留 [随附第三方声明](app/src/main/assets/THIRD_PARTY_NOTICES.txt) 及 [desugar_jdk_libs 许可和 Classpath 例外](licenses/desugar_jdk_libs-LICENSE.txt)。参与方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。
