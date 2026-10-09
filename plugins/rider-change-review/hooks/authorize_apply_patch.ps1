[CmdletBinding()]
param(
    [ValidateSet('Pre', 'Post')]
    [string]$Phase = 'Pre'
)

$ErrorActionPreference = 'Stop'
$reviewRoot = $null
$stagingDirectory = $null

function Read-JavaScriptStringLiteral {
    param(
        [string]$Source,
        [int]$StartIndex
    )

    if ($StartIndex -lt 0 -or $StartIndex -ge $Source.Length) { return $null }
    $quote = $Source[$StartIndex]
    if ($quote -ne '"' -and $quote -ne "'" -and $quote -ne '`') { return $null }

    $builder = [Text.StringBuilder]::new()
    for ($index = $StartIndex + 1; $index -lt $Source.Length; $index++) {
        $character = $Source[$index]
        if ($character -eq $quote) {
            return [pscustomobject]@{ Value = $builder.ToString(); EndIndex = $index }
        }
        if ($quote -eq '`' -and $character -eq '$' -and $index + 1 -lt $Source.Length -and $Source[$index + 1] -eq '{') {
            throw 'Template interpolation is not supported in an apply_patch argument.'
        }
        if ($character -ne '\') {
            [void]$builder.Append($character)
            continue
        }

        $index++
        if ($index -ge $Source.Length) { throw 'Unterminated JavaScript escape sequence.' }
        $escaped = $Source[$index]
        switch ($escaped) {
            'n' { [void]$builder.Append("`n") }
            'r' { [void]$builder.Append("`r") }
            't' { [void]$builder.Append("`t") }
            'b' { [void]$builder.Append([char]8) }
            'f' { [void]$builder.Append([char]12) }
            'v' { [void]$builder.Append([char]11) }
            '0' { [void]$builder.Append([char]0) }
            "'" { [void]$builder.Append("'") }
            '"' { [void]$builder.Append('"') }
            '`' { [void]$builder.Append('`') }
            '\' { [void]$builder.Append('\') }
            'x' {
                if ($index + 2 -ge $Source.Length) { throw 'Incomplete JavaScript hexadecimal escape.' }
                $hex = $Source.Substring($index + 1, 2)
                [void]$builder.Append([char][Convert]::ToInt32($hex, 16))
                $index += 2
            }
            'u' {
                if ($index + 4 -ge $Source.Length) { throw 'Incomplete JavaScript Unicode escape.' }
                $hex = $Source.Substring($index + 1, 4)
                [void]$builder.Append([char][Convert]::ToInt32($hex, 16))
                $index += 4
            }
            "`n" { }
            "`r" {
                if ($index + 1 -lt $Source.Length -and $Source[$index + 1] -eq "`n") { $index++ }
            }
            default { [void]$builder.Append($escaped) }
        }
    }
    throw 'Unterminated JavaScript string literal.'
}

function Get-ExecPatchSource {
    param([string]$Source)

    $call = [regex]::Match($Source, '\btools\.apply_patch\s*\(\s*(?<argument>[^\s,)]+)')
    if (-not $call.Success) { return $null }
    $argument = $call.Groups['argument'].Value
    $argumentIndex = $call.Groups['argument'].Index
    if ($argument[0] -eq '"' -or $argument[0] -eq "'" -or $argument[0] -eq '`') {
        return (Read-JavaScriptStringLiteral -Source $Source -StartIndex $argumentIndex).Value
    }

    if ($argument -notmatch '^[A-Za-z_$][A-Za-z0-9_$]*$') {
        throw 'The apply_patch argument must be a string literal or a variable initialized from one.'
    }
    $declaration = [regex]::Match($Source, "(?s)\b(?:const|let|var)\s+$([regex]::Escape($argument))\s*=\s*")
    if (-not $declaration.Success) {
        throw "Cannot resolve the apply_patch argument variable '$argument'."
    }
    return (Read-JavaScriptStringLiteral -Source $Source -StartIndex ($declaration.Index + $declaration.Length)).Value
}

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

    if ($toolName -eq 'apply_patch') { return $source }
    if ($toolName -ne 'exec' -and $toolName -ne 'Bash') { return $null }
    return Get-ExecPatchSource -Source $source
}

function Resolve-WorkspaceFile {
    param(
        [string]$Workspace,
        [string]$RawPath
    )

    $candidate = $RawPath.Trim()
    if (-not $candidate) { return $null }
    $fullPath = if ([IO.Path]::IsPathRooted($candidate)) {
        [IO.Path]::GetFullPath($candidate)
    } else {
        [IO.Path]::GetFullPath((Join-Path $Workspace $candidate))
    }
    $workspacePrefix = $Workspace.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    $relativePath = $fullPath.Substring($workspacePrefix.Length).Replace('\', '/')
    if (-not $relativePath -or $relativePath -eq '.codex-review.paths' -or $relativePath.StartsWith('.codex-review/')) {
        return $null
    }
    return [pscustomobject]@{ FullPath = $fullPath; RelativePath = $relativePath }
}

function Get-RequestHash {
    param(
        [string]$Workspace,
        [string]$ToolName,
        [string]$PatchSource
    )
    $payload = "$($Workspace.ToLowerInvariant())`n$ToolName`n$PatchSource"
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload)))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Test-ToolSucceeded {
    param([object]$Event)

    foreach ($name in @('tool_error', 'error')) {
        if ($Event.PSObject.Properties[$name] -and $Event.$name) { return $false }
    }
    if ($Event.PSObject.Properties['is_error'] -and [bool]$Event.is_error) { return $false }
    if ($Event.PSObject.Properties['success'] -and -not [bool]$Event.success) { return $false }
    if ($Event.PSObject.Properties['tool_response'] -and $null -ne $Event.tool_response) {
        $response = $Event.tool_response
        if ($response -isnot [string]) {
            foreach ($name in @('isError', 'is_error')) {
                if ($response.PSObject.Properties[$name] -and [bool]$response.$name) { return $false }
            }
            if ($response.PSObject.Properties['success'] -and -not [bool]$response.success) { return $false }
            if ($response.PSObject.Properties['error'] -and $response.error) { return $false }
            $response = $response | ConvertTo-Json -Depth 16 -Compress
        }
        if ([string]$response -match '(?i)apply_patch verification failed|invalid patch|patch failed|tool execution failed') {
            return $false
        }
    }
    return $true
}

function Write-HookError {
    param([Exception]$Exception)
    if (-not $reviewRoot) { return }
    try {
        New-Item -ItemType Directory -Path $reviewRoot -Force | Out-Null
        $message = "$(Get-Date -Format o) [$Phase] $($Exception.Message)"
        [IO.File]::WriteAllText((Join-Path $reviewRoot 'last-hook-error.txt'), $message, [Text.UTF8Encoding]::new($false))
    } catch { }
}

try {
    $eventText = [Console]::In.ReadToEnd()
    $event = $eventText | ConvertFrom-Json
    $patchSource = Get-PatchSource -Event $event
    if (-not $patchSource -or -not ($event.cwd -is [string])) { exit 0 }

    $workspace = [IO.Path]::GetFullPath([string]$event.cwd)
    $reviewRoot = Join-Path $workspace '.codex-review'
    $batchesRoot = Join-Path $reviewRoot 'batches'
    $requestHash = Get-RequestHash -Workspace $workspace -ToolName ([string]$event.tool_name) -PatchSource $patchSource
    $requestId = if ($event.PSObject.Properties['tool_use_id']) { [string]$event.tool_use_id } else { '' }

    if ($Phase -eq 'Post') {
        if (-not (Test-ToolSucceeded -Event $event) -or -not (Test-Path -LiteralPath $batchesRoot -PathType Container)) { exit 0 }
        $candidate = Get-ChildItem -LiteralPath $batchesRoot -Directory |
            Where-Object { -not (Test-Path -LiteralPath (Join-Path $_.FullName 'applied.json')) } |
            ForEach-Object {
                $manifestPath = Join-Path $_.FullName 'manifest.json'
                if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
                    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
                    $idMatches = $requestId -and $manifest.requestId -eq $requestId
                    $hashMatches = -not $requestId -and $manifest.requestHash -eq $requestHash
                    if ($manifest.schemaVersion -eq 3 -and ($idMatches -or $hashMatches)) { $_ }
                }
            } | Sort-Object Name | Select-Object -First 1
        if (-not $candidate) { exit 0 }
        $applied = [pscustomobject]@{ appliedAtUnixMillis = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
        $temporaryMarker = Join-Path $candidate.FullName '.applied.tmp'
        [IO.File]::WriteAllText($temporaryMarker, ($applied | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporaryMarker -Destination (Join-Path $candidate.FullName 'applied.json') -Force
        exit 0
    }

    $matches = [regex]::Matches($patchSource, '^\*\*\* (Add|Update|Delete) File: (.+?)\s*$', [Text.RegularExpressions.RegexOptions]::Multiline)
    if ($matches.Count -eq 0) { exit 0 }

    $moveTargets = @{}
    $moveMatches = [regex]::Matches(
        $patchSource,
        '^\*\*\* Update File: (?<source>[^\r\n]+)\r?\n\*\*\* Move to: (?<target>[^\r\n]+)\r?$',
        [Text.RegularExpressions.RegexOptions]::Multiline
    )
    foreach ($moveMatch in $moveMatches) {
        $moveTargets[$moveMatch.Groups['source'].Value.Trim()] = $moveMatch.Groups['target'].Value.Trim()
    }

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
        $snapshotSource = Resolve-WorkspaceFile -Workspace $workspace -RawPath $sourcePath
        if ($null -eq $resolved -or $null -eq $snapshotSource -or -not $seen.Add($resolved.RelativePath)) { continue }

        $existed = Test-Path -LiteralPath $snapshotSource.FullPath -PathType Leaf
        $beforeFile = $null
        if ($existed) {
            $snapshotName = '{0:D6}.bin' -f $entries.Count
            Copy-Item -LiteralPath $snapshotSource.FullPath -Destination (Join-Path $beforeDirectory $snapshotName)
            $beforeFile = "before/$snapshotName"
        }
        $entries.Add([pscustomobject]@{
            path = $resolved.RelativePath
            sourcePath = $snapshotSource.RelativePath
            operation = $operation
            existed = $existed
            beforeFile = $beforeFile
        })
    }

    if ($entries.Count -eq 0) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
        $stagingDirectory = $null
        exit 0
    }

    $manifest = [pscustomobject]@{
        schemaVersion = 3
        batchId = $batchId
        createdAtUnixMillis = $createdAt
        requestId = $requestId
        requestHash = $requestHash
        entries = @($entries)
    }
    [IO.File]::WriteAllText(
        (Join-Path $stagingDirectory 'manifest.json'),
        ($manifest | ConvertTo-Json -Depth 8),
        [Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $stagingDirectory -Destination $finalDirectory
    $stagingDirectory = $null
} catch {
    if ($stagingDirectory -and (Test-Path -LiteralPath $stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-HookError -Exception $_.Exception
    if ($Phase -eq 'Pre') { exit 2 }
    exit 1
}
