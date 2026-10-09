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

$hooksConfigPath = Join-Path $codexRoot 'hooks.json'
$installedHookDir = Join-Path $codexRoot 'hooks\rider-change-review'

if (Test-Path -LiteralPath $hooksConfigPath -PathType Leaf) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    Copy-Item -LiteralPath $hooksConfigPath -Destination "$hooksConfigPath.backup-$timestamp"
    $config = Get-Content -LiteralPath $hooksConfigPath -Raw | ConvertFrom-Json
    if ($config.PSObject.Properties['hooks']) {
        foreach ($phase in @('PreToolUse', 'PostToolUse')) {
            if (-not $config.hooks.PSObject.Properties[$phase]) { continue }
            $kept = @($config.hooks.$phase | Where-Object {
                $commands = @($_.hooks | ForEach-Object { $_.command })
                -not ($commands -match 'hooks[/\\]rider-change-review[/\\]authorize_apply_patch\.(py|ps1)')
            })
            $config.hooks.$phase = $kept
        }
        $json = $config | ConvertTo-Json -Depth 32
        [IO.File]::WriteAllText($hooksConfigPath, $json, [Text.UTF8Encoding]::new($false))
    }
}

if (Test-Path -LiteralPath $installedHookDir -PathType Container) {
    Remove-Item -LiteralPath $installedHookDir -Recurse -Force
}

Write-Host "Removed Rider review Hook from: $codexRoot"
Write-Host 'Restart Codex to unload the removed Hook.'
