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
foreach ($phase in @('PreToolUse', 'PostToolUse')) {
    $phaseArgument = if ($phase -eq 'PreToolUse') { 'Pre' } else { 'Post' }
    $command = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$commandScript`" -Phase $phaseArgument"
    $entry = [pscustomobject]@{
        matcher = 'apply_patch|Bash'
        hooks = @(
            [pscustomobject]@{
                type = 'command'
                command = $command
                timeout = 15
            }
        )
    }
    $existing = if ($config.hooks.PSObject.Properties[$phase]) { @($config.hooks.$phase) } else { @() }
    $kept = @($existing | Where-Object {
        $commands = @($_.hooks | ForEach-Object { $_.command })
        -not ($commands -match 'hooks[/\\]rider-change-review[/\\]authorize_apply_patch\.(py|ps1)')
    })
    $updated = @($kept) + @($entry)
    if ($config.hooks.PSObject.Properties[$phase]) {
        $config.hooks.$phase = $updated
    } else {
        $config.hooks | Add-Member -NotePropertyName $phase -NotePropertyValue $updated
    }
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
    $testEvent | & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installedScript -Phase Pre
    if ($LASTEXITCODE -ne 0) { throw "Installed PreToolUse Hook self-test failed with exit code $LASTEXITCODE." }
    $testEvent | & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installedScript -Phase Post
    if ($LASTEXITCODE -ne 0) { throw "Installed PostToolUse Hook self-test failed with exit code $LASTEXITCODE." }
    $manifest = Get-ChildItem (Join-Path $testWorkspace '.codex-review\batches') -Filter 'manifest.json' -Recurse |
        Select-Object -First 1
    if (-not $manifest) {
        throw 'Installed hook self-test did not create a review batch.'
    }
    $testManifest = Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json
    $addedEntry = @($testManifest.entries | Where-Object path -eq 'added.txt') | Select-Object -First 1
    $probeEntry = @($testManifest.entries | Where-Object path -eq 'probe.txt') | Select-Object -First 1
    $moveEntry = @($testManifest.entries | Where-Object path -eq 'move-target.txt') | Select-Object -First 1
    $appliedMarker = Join-Path $manifest.Directory.FullName 'applied.json'
    if ($testManifest.schemaVersion -ne 3 -or -not (Test-Path -LiteralPath $appliedMarker -PathType Leaf) -or
        -not $addedEntry -or $addedEntry.existed -or $addedEntry.beforeFile -or
        -not $probeEntry -or -not $moveEntry -or $moveEntry.operation -ne 'move' -or
        $moveEntry.sourcePath -ne 'move-source.txt') {
        throw 'Installed hook self-test created an invalid review batch.'
    }
    $probeBefore = [IO.File]::ReadAllText((Join-Path $manifest.Directory.FullName $probeEntry.beforeFile))
    $moveBefore = [IO.File]::ReadAllText((Join-Path $manifest.Directory.FullName $moveEntry.beforeFile))
    if ($probeBefore -ne 'before' -or $moveBefore -ne 'move-before') {
        throw 'Installed hook self-test did not preserve the expected before snapshots.'
    }

    $windowsPathPatch = "*** Begin Patch`n*** Add File: src\\new\\File.kt`n+content`n*** End Patch"
    $jsonPatch = $windowsPathPatch | ConvertTo-Json -Compress
    $execEvent = [pscustomobject]@{
        tool_name = 'Bash'
        tool_input = [pscustomobject]@{ code = "const patch = $jsonPatch; await tools.apply_patch(patch);" }
        cwd = $testWorkspace
    } | ConvertTo-Json -Depth 5 -Compress
    $execEvent | & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installedScript -Phase Pre
    if ($LASTEXITCODE -ne 0) { throw "Exec parser self-test failed with exit code $LASTEXITCODE." }
    $execManifest = Get-ChildItem (Join-Path $testWorkspace '.codex-review\batches') -Filter 'manifest.json' -Recurse |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json } |
        Where-Object { @($_.entries.path) -contains 'src/new/File.kt' } |
        Select-Object -First 1
    if (-not $execManifest) { throw 'Exec parser self-test corrupted a Windows-style path.' }

    $failedEventObject = [pscustomobject]@{
        tool_name = 'apply_patch'
        tool_use_id = 'failed-self-test'
        tool_input = [pscustomobject]@{ command = "*** Begin Patch`n*** Add File: failed.txt`n+never applied`n*** End Patch" }
        cwd = $testWorkspace
    }
    ($failedEventObject | ConvertTo-Json -Depth 5 -Compress) |
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installedScript -Phase Pre
    $failedEventObject | Add-Member -NotePropertyName tool_response -NotePropertyValue 'apply_patch verification failed: invalid patch'
    ($failedEventObject | ConvertTo-Json -Depth 5 -Compress) |
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installedScript -Phase Post
    $failedManifest = Get-ChildItem (Join-Path $testWorkspace '.codex-review\batches') -Filter 'manifest.json' -Recurse |
        ForEach-Object {
            $candidate = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
            if ($candidate.requestId -eq 'failed-self-test') { $_ }
        } | Select-Object -First 1
    if (-not $failedManifest -or (Test-Path -LiteralPath (Join-Path $failedManifest.Directory.FullName 'applied.json'))) {
        throw 'Failed-tool self-test incorrectly confirmed a review batch.'
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
Write-Host 'Restart Codex, run /hooks once, and trust the new PreToolUse/PostToolUse definitions.'
