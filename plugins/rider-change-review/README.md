# Codex Change Viewer

这是 Codex Change Viewer 的 Codex 端插件。它在 Codex 修改文件前创建不可变快照，并在工具成功结束后确认审阅批次，让 Rider 端只审阅真正完成的 Codex 修改。并发修改使用独立批次，不会互相覆盖。

请通过仓库根目录的 Git marketplace 安装，并同时安装配套的 Rider 插件。完整步骤见仓库根目录 `README.md`。

安装插件后，还需要运行一次用户 Hook 安装器。通过 CLI 安装时可以直接使用返回的插件路径：

```powershell
$plugin = codex plugin add rider-change-review@codex-change-viewer --json | ConvertFrom-Json
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $plugin.installedPath 'scripts\install-user-hook.ps1')
```

脚本会备份并合并用户目录下的 `.codex/hooks.json`，把 PowerShell Hook 和卸载器复制到稳定路径，并自动执行隔离自检。它不依赖 Python，也不会覆盖其他 Hook。看到 `Hook self-test: passed` 后重启 Codex，并运行一次 `/hooks` 信任新安装的 Pre/Post Hook。该 Hook 是用户级配置，完成信任后每次启动都会自动加载；升级插件中的 Hook 时再运行一次安装器即可。

卸载 Hook：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\.codex\hooks\rider-change-review\uninstall-user-hook.ps1"
```
