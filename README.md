# Codex Change Viewer for Rider

在 Rider 中审阅 Codex 修改的代码。插件只接收 Codex 授权的修改，普通程序或其他工具修改文件时不会自动进入审阅队列。

支持以下功能：

- Codex 修改后自动打开文件。
- 在编辑器中显示行内代码差异。
- 新增文件也会进入审阅，并以空文件作为修改前基线。
- 删除和移动文件也会进入审阅；取消移动会恢复原路径，取消删除会恢复文件。
- 每个文件显示“当前代码块/总代码块”以及上一块、下一块导航按钮。
- 应用或取消单个代码块、整个文件或全部文件。
- 待审阅文件显示彩色标签，并可在 Rider 设置中自定义颜色。
- 使用 Rider 原生 Diff 查看完整差异。

## 安装前准备

请先确认：

1. 已安装 JetBrains Rider 2026.2。
2. 已安装 Codex 桌面版或 Codex CLI。
3. Windows PowerShell 5.1 或 PowerShell 7 可用；不需要安装 Python。

## 安装

Rider 插件和 Codex 插件必须都安装。

### 第一步：安装 Rider 插件

1. 打开 [最新版本下载页面](https://github.com/DeliciousDD/codex-change-viewer-forRider/releases/latest)。
2. 在页面底部的 **Assets** 中下载名称以 `codex-change-viewer-` 开头的最新 ZIP。不要解压。
3. 打开 Rider，进入 **设置 → 插件**。
4. 点击插件页面右上角的齿轮，选择 **从磁盘安装插件**。
5. 选择刚下载的 ZIP，然后重启 Rider。

### 第二步：安装 Codex 插件和用户 Hook

打开 PowerShell，依次运行：

```powershell
codex plugin marketplace add DeliciousDD/codex-change-viewer-forRider --ref main
$plugin = codex plugin add rider-change-review@codex-change-viewer --json | ConvertFrom-Json
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $plugin.installedPath 'scripts\install-user-hook.ps1')
```

最后一条命令应输出：

```text
Hook self-test: passed
```

然后完全退出并重新打开 Codex 或 ChatGPT 桌面应用。在 Codex 中运行一次 `/hooks`，检查并信任新安装的 `PreToolUse`、`PostToolUse` 定义；Codex 出于安全原因不会自动信任非托管 Hook。也可以在 **Plugins Directory → Codex Change Viewer Plugins** 中确认 **Codex Change Viewer** 已安装。

安装脚本会备份并合并 `%USERPROFILE%\.codex\hooks.json`，不会覆盖其他 Hook。它同时注册 `PreToolUse` 和 `PostToolUse`：执行前保存快照，执行成功后才确认该批次可供 Rider 审阅。Hook 安装在当前 Windows 用户目录，使用系统自带 PowerShell，不依赖 Python。

### Hook 是否需要每次启动时重新安装？

不需要。Hook 是用户级配置，安装后保存在：

- `%USERPROFILE%\.codex\hooks.json`
- `%USERPROFILE%\.codex\hooks\rider-change-review\authorize_apply_patch.ps1`

Codex 每次启动都会读取该用户配置；完成一次信任后，普通重启不需要额外操作。只有以下情况需要重新运行安装命令：

- 更新插件后需要同步新版 Hook；
- Hook 被手动卸载或用户目录被清理；
- 更换 Windows 用户，或使用了不同的 `CODEX_HOME`。

如果 Hook 定义在升级时发生变化，Codex 会按新哈希要求重新信任。可运行 `/hooks` 查看状态；这不是弹窗，通常会在启动警告或 Hook 浏览器中显示。

可以运行以下命令检查安装状态：

```powershell
codex plugin list --json | Select-String 'rider-change-review'
Test-Path "$env:USERPROFILE\.codex\hooks\rider-change-review\authorize_apply_patch.ps1"
```

第二条命令应输出 `True`。OpenAI 的 [插件打包与 marketplace 文档](https://developers.openai.com/plugins/build/plugins) 也说明了 marketplace 注册、插件缓存与重启加载流程。

## 使用

1. 在 Rider 和 Codex 中打开同一个项目文件夹。
2. 让 Codex 修改代码。
3. Rider 会自动打开被修改的文件，并用绿色标签标记。
4. 在行内代码块或编辑器顶部点击 **应用** / **取消**；也可以点击 **应用全部文件** / **取消全部文件**。
5. 如需完整对比，打开 Rider 的 **Codex Changes** 工具窗口并查看 Diff。

### 设置待审阅标签页颜色

打开 **设置 → 工具 → Codex Change Viewer**，在“待审阅标签页颜色”中选择颜色并点击“应用”。已打开的待审阅标签页会立即刷新；“恢复默认”会重新选择默认绿色。

## 更新

更新 Rider 插件：从 [Releases](https://github.com/DeliciousDD/codex-change-viewer-forRider/releases) 下载最新 ZIP，再次从磁盘安装。

更新 Codex 插件和用户 Hook：

```powershell
codex plugin marketplace upgrade codex-change-viewer
$plugin = codex plugin add rider-change-review@codex-change-viewer --json | ConvertFrom-Json
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $plugin.installedPath 'scripts\install-user-hook.ps1')
```

更新后完全退出并重新打开 Codex。

## 常见问题

### Codex 修改后 Rider 没有反应

- 确认用户 Hook 已安装：`%USERPROFILE%\.codex\hooks.json` 中应包含 `rider-change-review`，并且 `%USERPROFILE%\.codex\hooks\rider-change-review\authorize_apply_patch.ps1` 存在。仅安装 Codex 插件不会自动注册 Hook。
- 确认 Rider 和 Codex 打开的是同一个文件夹。
- 确认 Rider 插件和 Codex 插件都已安装。
- 在 Rider 中同步或刷新项目文件。
- 检查项目下的 `.codex-review\last-hook-error.txt`；存在时里面会记录最近一次 Hook 错误。

### 如何卸载用户 Hook

安装器会把卸载脚本复制到用户 Hook 目录，因此可以在任意目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\.codex\hooks\rider-change-review\uninstall-user-hook.ps1"
```

卸载后重启 Codex。卸载 Hook 不会自动卸载 Codex 插件；如需同时卸载插件，再运行 `codex plugin remove rider-change-review@codex-change-viewer`。

### 第三方工具修改文件会进入审阅吗？

不会。Codex 的 `apply_patch` 工具会在修改前创建不可变快照，并在工具成功完成后写入确认标记；Rider 只消费已确认的批次。失败或未完成的工具调用不会进入审阅队列。普通编辑器、构建工具和其他程序不会创建这个批次。

### 为什么保留 10 MiB 文件限制？

这是 Rider 侧的单文件审阅安全上限，用于避免一次性读取、行级比较和渲染超大文本时占满内存或阻塞界面。直接删除限制会把风险转移到编辑器线程，因此当前版本保留该保护；超过上限的文件不会进入行内审阅。若以后需要支持大文件，更稳妥的方式是流式差异或只显示文件级提示，而不是无限制加载全文。

## 从源码构建 Rider 插件

### Windows 推荐构建方式

仓库提供了稳定构建脚本，会自动使用本机 Rider 自带的 JBR、已缓存的 Gradle 与依赖，并避免 Gradle/Kotlin 后台进程的回环通信：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-plugin.ps1
```

默认使用离线模式，执行 `test` 和 `buildPlugin`。也可以只编译 Kotlin：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-plugin.ps1 -Tasks compileKotlin
```

多个任务既可以按 PowerShell 数组传入，也可以使用逗号分隔；脚本会统一拆分，避免 Gradle 把 `test,buildPlugin` 误认为一个任务名：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-plugin.ps1 -Tasks test,buildPlugin
```

如果是新机器、Gradle 或依赖尚未缓存，首次运行时增加 `-Online`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-plugin.ps1 -Online
```

脚本只会在代理值明确等于 `http://127.0.0.1:9` 时，为当前构建进程忽略该不可用代理；不会修改系统或用户环境变量。安装包生成在 `build/distributions/`。

在普通 PowerShell 中无需额外权限。若由 Codex 的受限执行环境首次联网下载，或访问用户目录下的 Gradle 缓存，需要在权限提示中允许该次构建；这是宿主沙箱的访问控制，不是 Gradle 配置问题。缓存准备完成后，日常构建使用默认离线命令即可。

### 为什么以前会失败？

这是两个相互独立的问题：

1. 当前受限执行环境把 `HTTP_PROXY`、`HTTPS_PROXY` 和 `ALL_PROXY` 指向 `127.0.0.1:9`。该地址没有代理服务，Gradle Wrapper 和 Maven 仓库请求会等待到超时。增加 `networkTimeout` 只能延迟失败，不能恢复网络。默认离线构建完全绕过网络；首次下载则用 `-Online` 在允许联网的 PowerShell 中运行。
2. `--no-daemon` 不保证绝不创建进程。如果 Gradle 客户端 JVM 的内存、区域设置或其他不可变参数与构建 JVM 不一致，Gradle 仍会启动 single-use Daemon。受限环境禁止它通过本机回环套接字连接，于是出现 `Unable to establish loopback connection`。构建脚本把客户端参数与 Gradle 默认构建参数对齐，并同时使用 `--no-daemon`、`--no-configuration-cache` 和 `kotlin.compiler.execution.strategy=in-process`，整个编译留在当前 JVM 中。

相关说明见 Gradle 官方文档：[Daemon 与 single-use Daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html)、[依赖离线模式](https://docs.gradle.org/current/userguide/dependency_caching.html#sec:offline-mode)、[Wrapper 网络配置](https://docs.gradle.org/current/userguide/gradle_wrapper.html)。

## 项目结构

- `src/`：Rider 插件源码。
- `plugins/rider-change-review/`：Codex 插件源码。
- `scripts/build-plugin.ps1`：Windows 稳定构建入口。
- `.agents/plugins/marketplace.json`：Codex Git marketplace 配置。

## License

MIT
