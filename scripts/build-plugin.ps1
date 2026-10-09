[CmdletBinding()]
param(
    [string]$RiderPath,
    [string[]]$Tasks = @('test', 'buildPlugin'),
    [switch]$Online
)

$ErrorActionPreference = 'Stop'

$normalizedTasks = @(
    $Tasks |
        ForEach-Object { $_ -split ',' } |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }
)
if ($normalizedTasks.Count -eq 0) {
    throw 'Specify at least one Gradle task.'
}

function Get-PropertyValue {
    param(
        [string]$Path,
        [string]$Name
    )

    $line = Get-Content -LiteralPath $Path | Where-Object {
        $_ -match ('^\s*' + [regex]::Escape($Name) + '\s*=')
    } | Select-Object -First 1
    if (-not $line) {
        throw "Missing property '$Name' in $Path"
    }
    return ($line -split '=', 2)[1].Trim()
}

function Remove-BlackholeProxy {
    $proxyNames = @('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'GIT_HTTP_PROXY', 'GIT_HTTPS_PROXY')
    foreach ($name in $proxyNames) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($value -match '^https?://127\.0\.0\.1:9/?$') {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
            Write-Host "Ignoring unavailable process proxy: $name=$value"
        }
    }
}

function Get-JavaPropertyValue {
    param(
        [string[]]$Lines,
        [string]$Name,
        [string]$Fallback = ''
    )

    $match = $Lines | Where-Object {
        $_ -match ('^\s*' + [regex]::Escape($Name) + '\s*=\s*(.*)$')
    } | Select-Object -First 1
    if (-not $match) {
        return $Fallback
    }
    return ([regex]::Match($match, ('^\s*' + [regex]::Escape($Name) + '\s*=\s*(.*)$'))).Groups[1].Value.Trim()
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gradleProperties = Join-Path $repositoryRoot 'gradle.properties'
$wrapperProperties = Join-Path $repositoryRoot 'gradle\wrapper\gradle-wrapper.properties'
$riderVersion = Get-PropertyValue -Path $gradleProperties -Name 'riderVersion'
$distributionUrl = Get-PropertyValue -Path $wrapperProperties -Name 'distributionUrl'
$gradleVersionMatch = [regex]::Match($distributionUrl, 'gradle-([0-9]+(?:\.[0-9]+)+)-bin\.zip')
if (-not $gradleVersionMatch.Success) {
    throw "Cannot determine the Gradle version from: $distributionUrl"
}
$gradleVersion = $gradleVersionMatch.Groups[1].Value

if (-not $RiderPath) {
    $expectedRider = Join-Path $env:ProgramFiles "JetBrains\JetBrains Rider $riderVersion"
    if (Test-Path -LiteralPath $expectedRider -PathType Container) {
        $RiderPath = $expectedRider
    } else {
        $RiderPath = Get-ChildItem (Join-Path $env:ProgramFiles 'JetBrains') -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like 'JetBrains Rider *' |
            Sort-Object Name -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $RiderPath -or -not (Test-Path -LiteralPath $RiderPath -PathType Container)) {
    throw "Rider $riderVersion was not found. Pass -RiderPath with the installed Rider directory."
}
$RiderPath = [IO.Path]::GetFullPath($RiderPath)
$javaHome = Join-Path $RiderPath 'jbr'
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe') -PathType Leaf)) {
    throw "Rider JBR was not found under: $javaHome"
}

$cachedDistributionRoot = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$gradleVersion-bin"
$gradleExecutable = Get-ChildItem $cachedDistributionRoot -Filter 'gradle.bat' -File -Recurse -ErrorAction SilentlyContinue |
    Where-Object FullName -Match ([regex]::Escape("gradle-$gradleVersion\bin\gradle.bat") + '$') |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $gradleExecutable) {
    if (-not $Online) {
        throw "Gradle $gradleVersion is not cached. Run this script once with -Online from a network-enabled PowerShell."
    }
    $gradleExecutable = Join-Path $repositoryRoot 'gradlew.bat'
    Remove-BlackholeProxy
}

$env:JAVA_HOME = $javaHome
$javaExecutable = Join-Path $javaHome 'bin\java.exe'
$javaPropertyProcess = [Diagnostics.Process]::new()
$javaPropertyProcess.StartInfo = [Diagnostics.ProcessStartInfo]@{
    FileName = $javaExecutable
    Arguments = '-XshowSettings:properties -version'
    UseShellExecute = $false
    RedirectStandardOutput = $false
    RedirectStandardError = $true
    CreateNoWindow = $true
}
if (-not $javaPropertyProcess.Start()) {
    throw "Unable to start Rider JBR: $javaExecutable"
}
$javaPropertyError = $javaPropertyProcess.StandardError.ReadToEnd()
$javaPropertyProcess.WaitForExit()
if ($javaPropertyProcess.ExitCode -ne 0) {
    throw "Rider JBR exited with code $($javaPropertyProcess.ExitCode): $javaPropertyError"
}
$javaProperties = @($javaPropertyError -split '\r?\n')
$fileEncoding = Get-JavaPropertyValue -Lines $javaProperties -Name 'file.encoding' -Fallback 'UTF-8'
$userCountry = Get-JavaPropertyValue -Lines $javaProperties -Name 'user.country'
$userLanguage = Get-JavaPropertyValue -Lines $javaProperties -Name 'user.language' -Fallback 'en'
$userVariant = Get-JavaPropertyValue -Lines $javaProperties -Name 'user.variant'
$jvmOptions = @(
    '-Xms256m'
    '-Xmx512m'
    '-XX:MaxMetaspaceSize=384m'
    '-XX:+HeapDumpOnOutOfMemoryError'
    "-Dfile.encoding=$fileEncoding"
    "-Duser.country=$userCountry"
    "-Duser.language=$userLanguage"
    $(if ($userVariant) { "-Duser.variant=$userVariant" } else { '-Duser.variant' })
)
$env:JAVA_OPTS = $jvmOptions -join ' '
Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
if ($Online) {
    Remove-BlackholeProxy
}

$arguments = [Collections.Generic.List[string]]::new()
$normalizedTasks | ForEach-Object { $arguments.Add($_) }
$arguments.Add('--no-daemon')
$arguments.Add('--no-configuration-cache')
$arguments.Add('-Pkotlin.compiler.execution.strategy=in-process')
$arguments.Add("-PlocalRiderPath=$RiderPath")
if (-not $Online) {
    $arguments.Add('--offline')
}

Write-Host "Repository: $repositoryRoot"
Write-Host "Rider:     $RiderPath"
Write-Host "Java:      $javaHome"
Write-Host "Gradle:    $gradleExecutable"
Write-Host "Mode:      $(if ($Online) { 'online fallback' } else { 'offline/cache only' })"
Write-Host "Tasks:     $($normalizedTasks -join ', ')"

Push-Location $repositoryRoot
try {
    & $gradleExecutable @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle exited with code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
