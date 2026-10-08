# Codex Change Viewer for Rider

在 Rider 中审阅 Codex 修改的代码。插件只接收 Codex 授权的修改，普通程序或其他工具修改文件时不会自动进入审阅队列。

支持以下功能：

- Codex 修改后自动打开文件。
- 在编辑器中显示行内代码差异。
- 应用或取消单个代码块、整个文件或全部文件。
- 待审阅文件显示绿色标签。
- 使用 Rider 原生 Diff 查看完整差异。

## 安装前准备

请先确认：

1. 已安装 JetBrains Rider 2026.2。
2. 已安装 Codex 桌面版或 Codex CLI。
3. 已安装 Python 3。在 PowerShell 中运行下面的命令，应能看到 Python 版本：

```powershell
py -3 --version
```

## 安装

Rider 插件和 Codex 插件必须都安装。

### 第一步：安装 Rider 插件

1. 打开 [最新版本下载页面](https://github.com/DeliciousDD/codex-change-viewer-forRider/releases/latest)。
2. 在页面底部的 **Assets** 中下载 `codex-change-viewer-0.3.5.zip`。不要解压。
3. 打开 Rider，进入 **设置 → 插件**。
4. 点击插件页面右上角的齿轮，选择 **从磁盘安装插件**。
5. 选择刚下载的 ZIP，然后重启 Rider。

### 第二步：安装 Codex 插件

1. 打开 PowerShell，复制并运行：

```powershell
codex plugin marketplace add DeliciousDD/codex-change-viewer-forRider --ref main
```

2. 重启 Codex 或 ChatGPT 桌面应用。
3. 打开 **Plugins Directory**，选择 **Codex Change Viewer Plugins**。
4. 安装 **Codex Change Viewer**。
5. 当 Codex 提示审阅 hook 时，确认内容来自本仓库后选择信任。

## 使用

1. 在 Rider 和 Codex 中打开同一个项目文件夹。
2. 让 Codex 修改代码。
3. Rider 会自动打开被修改的文件，并用绿色标签标记。
4. 在行内代码块或编辑器顶部点击 **应用** / **取消**；也可以点击 **应用全部文件** / **取消全部文件**。
5. 如需完整对比，打开 Rider 的 **Codex Changes** 工具窗口并查看 Diff。

## 更新

更新 Rider 插件：从 [Releases](https://github.com/DeliciousDD/codex-change-viewer-forRider/releases) 下载最新 ZIP，再次从磁盘安装。

更新 Codex 插件：

```powershell
codex plugin marketplace upgrade codex-change-viewer
```

更新后重启 Codex。

## 常见问题

### Codex 修改后 Rider 没有反应

- 确认 Rider 和 Codex 打开的是同一个文件夹。
- 确认 Rider 插件和 Codex 插件都已安装。
- 确认已经信任 Codex 插件的 hook。
- 在 Rider 中同步或刷新项目文件。

### PowerShell 提示找不到 `py`

安装 Python 3 后重新打开 PowerShell，再运行 `py -3 --version`。

### 第三方工具修改文件会进入审阅吗？

不会。只有 Codex 在修改前授权的文件才会进入审阅队列。

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
