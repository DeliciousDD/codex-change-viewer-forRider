[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$reviewRoot = $null
$stagingDirectory = $null

function Get-PatchSource {
    param([object]$Event)

    $toolName = [string]$Event.tool_name
    $toolInput = $Event.tool_input
    if ($toolInput -is [string]) {
        $source = $toolInput
    } elseif ($null -ne $toolInput) {
        $source = if ($toolInput.command) { [string]$toolInput.command } else { [string]$toolInput.code }
    } else {
        return $null
    }

    if ($toolName -eq 'apply_patch') {
        return $source
    }
    if ($toolName -ne 'exec' -or $source -notmatch '\btools\.apply_patch\s*\(') {
        return $null
    }
    return $source.Replace('\r\n', "`n").Replace('\n', "`n")
}

function Resolve-WorkspaceFile {
    param(
        [string]$Workspace,
        [string]$RawPath
    )

    $candidate = $RawPath.Trim()
    if (-not $candidate) {
        return $null
    }
    $fullPath = if ([IO.Path]::IsPathRooted($candidate)) {
        [IO.Path]::GetFullPath($candidate)
    } else {
        [IO.Path]::GetFullPath((Join-Path $Workspace $candidate))
    }
    $workspacePrefix = $Workspace.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        return $null
    }
    $relativePath = $fullPath.Substring($workspacePrefix.Length).Replace('\', '/')
    if (-not $relativePath -or $relativePath -eq '.codex-review.paths' -or $relativePath.StartsWith('.codex-review/')) {
        return $null
    }
    return [pscustomobject]@{
        FullPath = $fullPath
        RelativePath = $relativePath
    }
}

try {
    $eventText = [Console]::In.ReadToEnd()
    $event = $eventText | ConvertFrom-Json
    $patchSource = Get-PatchSource -Event $event
    if (-not $patchSource -or -not ($event.cwd -is [string])) {
        exit 0
    }

    $workspace = [IO.Path]::GetFullPath([string]$event.cwd)
    $matches = [regex]::Matches(
        $patchSource,
        '^\*\*\* (Add|Update|Delete) File: (.+?)\s*$',
        [Text.RegularExpressions.RegexOptions]::Multiline
    )
    if ($matches.Count -eq 0) {
        exit 0
    }

    $moveTargets = @{}
    $moveMatches = [regex]::Matches(
        $patchSource,
        '^\*\*\* Update File: (?<source>[^\r\n]+)\r?\n\*\*\* Move to: (?<target>[^\r\n]+)\r?$',
        [Text.RegularExpressions.RegexOptions]::Multiline
    )
    foreach ($moveMatch in $moveMatches) {
        $moveTargets[$moveMatch.Groups['source'].Value.Trim()] = $moveMatch.Groups['target'].Value.Trim()
    }

    $reviewRoot = Join-Path $workspace '.codex-review'
    $batchesRoot = Join-Path $reviewRoot 'batches'
    New-Item -ItemType Directory -Path $batchesRoot -Force | Out-Null

    $createdAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $batchId = "$createdAt-$([guid]::NewGuid().ToString('N'))"
    $stagingDirectory = Join-Path $batchesRoot ".tmp-$batchId"
    $finalDirectory = Join-Path $batchesRoot $batchId
    $beforeDirectory = Join-Path $stagingDirectory 'before'
    New-Item -ItemType Directory -Path $beforeDirectory -Force | Out-Null

    $entries = [Collections.Generic.List[object]]::new()
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($match in $matches) {
        $sourcePath = $match.Groups[2].Value.Trim()
        $operation = $match.Groups[1].Value.ToLowerInvariant()
        $entryPath = $sourcePath
        if ($operation -eq 'update' -and $moveTargets.ContainsKey($sourcePath)) {
            $entryPath = [string]$moveTargets[$sourcePath]
            $operation = 'move'
        }

        $resolved = Resolve-WorkspaceFile -Workspace $workspace -RawPath $entryPath
        if ($null -eq $resolved -or -not $seen.Add($resolved.RelativePath)) {
            continue
        }

        $snapshotSource = Resolve-WorkspaceFile -Workspace $workspace -RawPath $sourcePath
        $existed = $null -ne $snapshotSource -and (Test-Path -LiteralPath $snapshotSource.FullPath -PathType Leaf)
        $beforeFile = $null
        if ($existed) {
            $snapshotName = '{0:D6}.bin' -f $entries.Count
            Copy-Item -LiteralPath $snapshotSource.FullPath -Destination (Join-Path $beforeDirectory $snapshotName)
            $beforeFile = "before/$snapshotName"
        }
        $entries.Add([pscustomobject]@{
            path = $resolved.RelativePath
            operation = $operation
            existed = $existed
            beforeFile = $beforeFile
        })
    }

    if ($entries.Count -eq 0) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
        exit 0
    }

    $manifest = [pscustomobject]@{
        schemaVersion = 2
        batchId = $batchId
        createdAtUnixMillis = $createdAt
        entries = @($entries)
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText(
        (Join-Path $stagingDirectory 'manifest.json'),
        $manifestJson,
        [Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $stagingDirectory -Destination $finalDirectory
    $stagingDirectory = $null
} catch {
    if ($stagingDirectory -and (Test-Path -LiteralPath $stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
    if ($reviewRoot) {
        try {
            New-Item -ItemType Directory -Path $reviewRoot -Force | Out-Null
            $message = "$(Get-Date -Format o) $($_.Exception.Message)"
            [IO.File]::WriteAllText(
                (Join-Path $reviewRoot 'last-hook-error.txt'),
                $message,
                [Text.UTF8Encoding]::new($false)
            )
        } catch {
        }
    }
    exit 0
}
