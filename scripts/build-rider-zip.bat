@echo off
setlocal EnableExtensions

rem Build the Rider plugin ZIP from this repository. Extra arguments are passed
rem through to build-plugin.ps1, for example: build-rider-zip.bat -Online
for %%I in ("%~dp0..") do set "REPOSITORY_ROOT=%%~fI"
set "BUILD_SCRIPT=%~dp0build-plugin.ps1"

if not exist "%BUILD_SCRIPT%" (
    echo ERROR: Build script was not found: "%BUILD_SCRIPT%"
    exit /b 1
)

echo Building Rider plugin ZIP from "%REPOSITORY_ROOT%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%BUILD_SCRIPT%" -Tasks buildPlugin %*
if errorlevel 1 (
    echo.
    echo ERROR: Rider plugin ZIP build failed. Review the Gradle output above.
    exit /b 1
)

set "PLUGIN_VERSION="
for /f "tokens=1,2 delims==" %%A in ('findstr /r /c:"^[ ]*pluginVersion[ ]*=" "%REPOSITORY_ROOT%\gradle.properties"') do set "PLUGIN_VERSION=%%B"
set "PLUGIN_VERSION=%PLUGIN_VERSION: =%"
if not defined PLUGIN_VERSION (
    echo ERROR: pluginVersion is missing from gradle.properties.
    exit /b 1
)

set "PLUGIN_ZIP=%REPOSITORY_ROOT%\build\distributions\codex-change-viewer-%PLUGIN_VERSION%.zip"
if not exist "%PLUGIN_ZIP%" (
    echo ERROR: Gradle completed but the expected ZIP was not found: "%PLUGIN_ZIP%"
    exit /b 1
)

echo.
echo SUCCESS: "%PLUGIN_ZIP%"
exit /b 0
