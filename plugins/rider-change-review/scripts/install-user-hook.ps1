[CmdletBinding()]
param(
    [string]$CodexRoot
)

$ErrorActionPreference = 'Stop'

$codexRoot = if ($CodexRoot) {
    [IO.Path]::GetFullPath($CodexRoot)
} elseif ($env:CODEX_HOME) {
    [IO.Path]::GetFullPath($env:CODEX_HOME)
} else {
    [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.codex'))
}

$sourceScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\hooks\authorize_apply_patch.ps1'))
$sourceUninstaller = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'uninstall-user-hook.ps1'))
$installedHookDir = Join-Path $codexRoot 'hooks\rider-change-review'
$installedScript = Join-Path $installedHookDir 'authorize_apply_patch.ps1'
$installedUninstaller = Join-Path $installedHookDir 'uninstall-user-hook.ps1'
$hooksConfigPath = Join-Path $codexRoot 'hooks.json'

if (-not (Test-Path -LiteralPath $sourceScript -PathType Leaf)) {
    throw "Hook source not found: $sourceScript"
}
if (-not (Test-Path -LiteralPath $sourceUninstaller -PathType Leaf)) {
    throw "Hook uninstaller not found: $sourceUninstaller"
}

New-Item -ItemType Directory -Path $installedHookDir -Force | Out-Null
Copy-Item -LiteralPath $sourceScript -Destination $installedScript -Force
Copy-Item -LiteralPath $sourceUninstaller -Destination $installedUninstaller -Force
$legacyScript = Join-Path $installedHookDir 'authorize_apply_patch.py'
if (Test-Path -LiteralPath $legacyScript -PathType Leaf) {
    Remove-Item -LiteralPath $legacyScript -Force
}

if (Test-Path -LiteralPath $hooksConfigPath -PathType Leaf) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    Copy-Item -LiteralPath $hooksConfigPath -Destination "$hooksConfigPath.backup-$timestamp"
    $config = Get-Content -LiteralPath $hooksConfigPath -Raw | ConvertFrom-Json
} else {
    $config = [pscustomobject]@{}
}

if (-not $config.PSObject.Properties['hooks']) {
    $config | Add-Member -NotePropertyName hooks -NotePropertyValue ([pscustomobject]@{})
}

$commandScript = $installedScript.Replace('\', '/')
$command = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$commandScript`""
$entry = [pscustomobject]@{
    matcher = 'apply_patch|exec'
    hooks = @(
        [pscustomobject]@{
            type = 'command'
            command = $command
            timeout = 15
        }
    )
}

$existing = if ($config.hooks.PSObject.Properties['PreToolUse']) {
    @($config.hooks.PreToolUse)
} else {
    @()
}

$kept = @($existing | Where-Object {
    $commands = @($_.hooks | ForEach-Object { $_.command })
    -not ($commands -match 'hooks[/\\]rider-change-review[/\\]authorize_apply_patch\.(py|ps1)')
})
$updated = @($kept) + @($entry)

if ($config.hooks.PSObject.Properties['PreToolUse']) {
    $config.hooks.PreToolUse = $updated
} else {
    $config.hooks | Add-Member -NotePropertyName PreToolUse -NotePropertyValue $updated
}

$json = $config | ConvertTo-Json -Depth 32
[IO.File]::WriteAllText($hooksConfigPath, $json, [Text.UTF8Encoding]::new($false))

$testWorkspace = Join-Path ([IO.Path]::GetTempPath()) "rider-change-review-hook-$([guid]::NewGuid().ToString('N'))"
try {
    New-Item -ItemType Directory -Path $testWorkspace | Out-Null
    [IO.File]::WriteAllText((Join-Path $testWorkspace 'probe.txt'), 'before', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $testWorkspace 'move-source.txt'), 'move-before', [Text.UTF8Encoding]::new($false))
    $testEvent = [pscustomobject]@{
        tool_name = 'apply_patch'
        tool_input = [pscustomobject]@{
            command = "*** Begin Patch`n*** Add File: added.txt`n+new file`n*** Update File: probe.txt`n@@`n-before`n+after`n*** Update File: move-source.txt`n*** Move to: move-target.txt`n@@`n-move-before`n+move-after`n*** End Patch"
        }
        cwd = $testWorkspace
    } | ConvertTo-Json -Depth 5 -Compress
    $testEvent | & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installedScript
    $manifest = Get-ChildItem (Join-Path $testWorkspace '.codex-review\batches') -Filter 'manifest.json' -Recurse |
        Select-Object -First 1
    if (-not $manifest) {
        throw 'Installed hook self-test did not create a review batch.'
    }
    $testManifest = Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json
    $addedEntry = @($testManifest.entries | Where-Object path -eq 'added.txt') | Select-Object -First 1
    $probeEntry = @($testManifest.entries | Where-Object path -eq 'probe.txt') | Select-Object -First 1
    $moveEntry = @($testManifest.entries | Where-Object path -eq 'move-target.txt') | Select-Object -First 1
    if ($testManifest.schemaVersion -ne 2 -or -not $addedEntry -or $addedEntry.existed -or $addedEntry.beforeFile -or
        -not $probeEntry -or -not $moveEntry -or $moveEntry.operation -ne 'move') {
        throw 'Installed hook self-test created an invalid review batch.'
    }
    $probeBefore = [IO.File]::ReadAllText((Join-Path $manifest.Directory.FullName $probeEntry.beforeFile))
    $moveBefore = [IO.File]::ReadAllText((Join-Path $manifest.Directory.FullName $moveEntry.beforeFile))
    if ($probeBefore -ne 'before' -or $moveBefore -ne 'move-before') {
        throw 'Installed hook self-test did not preserve the expected before snapshots.'
    }
} finally {
    if (Test-Path -LiteralPath $testWorkspace) {
        Remove-Item -LiteralPath $testWorkspace -Recurse -Force
    }
}

Write-Host "Installed Rider review hook: $installedScript"
Write-Host "Installed Hook uninstaller: $installedUninstaller"
Write-Host "Updated Codex hooks config: $hooksConfigPath"
Write-Host 'Hook self-test: passed'
Write-Host 'Restart Codex to load the installed user Hook.'
