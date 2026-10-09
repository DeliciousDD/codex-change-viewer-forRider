# Codex Change Viewer for Rider

在 Rider 中审阅 Codex 修改的代码。插件只接收 Codex 授权的修改，普通程序或其他工具修改文件时不会自动进入审阅队列。

支持以下功能：

- Codex 修改后自动打开文件。
- 在编辑器中显示行内代码差异。
- 新增文件也会进入审阅，并以空文件作为修改前基线。
- 每个文件显示“当前代码块/总代码块”以及上一块、下一块导航按钮。
- 应用或取消单个代码块、整个文件或全部文件。
- 待审阅文件显示绿色标签。
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

然后完全退出并重新打开 Codex 或 ChatGPT 桌面应用。也可以在 **Plugins Directory → Codex Change Viewer Plugins** 中确认 **Codex Change Viewer** 已安装。

安装脚本会备份并合并 `%USERPROFILE%\.codex\hooks.json`，不会覆盖其他 Hook。Hook 安装在当前 Windows 用户目录，使用系统自带 PowerShell，不依赖 Python。

### Hook 是否需要每次启动时重新安装？

不需要。Hook 是用户级配置，安装后保存在：

- `%USERPROFILE%\.codex\hooks.json`
- `%USERPROFILE%\.codex\hooks\rider-change-review\authorize_apply_patch.ps1`

Codex 每次启动都会读取该用户配置。只有以下情况需要重新运行安装命令：

- 更新插件后需要同步新版 Hook；
- Hook 被手动卸载或用户目录被清理；
- 更换 Windows 用户，或使用了不同的 `CODEX_HOME`。

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

不会。只有 Codex 的 `apply_patch` 工具在修改前创建的不可变批次才会进入审阅队列。每个批次都保存修改前快照；Rider 即使稍后才刷新，也能还原正确基线。普通编辑器、构建工具和其他程序不会创建这个批次。

## 从源码构建 Rider 插件

需要 JDK 25。首次构建会下载目标 Rider：

```powershell
.\gradlew.bat buildPlugin
```

如果本机已经安装 Rider，可避免下载：

```powershell
.\gradlew.bat buildPlugin -PlocalRiderPath="C:\Program Files\JetBrains\JetBrains Rider 2026.2.1"
```

安装包生成在 `build/distributions/`。

## 项目结构

- `src/`：Rider 插件源码。
- `plugins/rider-change-review/`：Codex 插件源码。
- `.agents/plugins/marketplace.json`：Codex Git marketplace 配置。

## License

MIT
