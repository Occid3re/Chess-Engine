@echo off
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "PROFILE_DIR=%SCRIPT_DIR%\profiles"
if not exist "%PROFILE_DIR%" (
    mkdir "%PROFILE_DIR%"
    if errorlevel 1 (
        echo [ERROR] Failed to create profile output directory at "%PROFILE_DIR%".
        exit /b 1
    )
)

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java runtime not found. Please ensure Java 17 or later is installed and available on PATH.
    exit /b 1
)

set "MVNW_CMD=%SCRIPT_DIR%\mvnw.cmd"
if not exist "%MVNW_CMD%" (
    echo [ERROR] Unable to locate mvnw.cmd at "%MVNW_CMD%".
    exit /b 1
)

if not defined PROFILE_MOVE_TIME_MS set "PROFILE_MOVE_TIME_MS=2000"
if not defined PROFILE_PLY_COUNT set "PROFILE_PLY_COUNT=80"
if not defined PROFILE_JFR_DURATION set "PROFILE_JFR_DURATION=180s"

pushd "%SCRIPT_DIR%" >nul
call "%MVNW_CMD%" -DskipTests package
if errorlevel 1 (
    echo [ERROR] Maven build failed. See the log above for details.
    popd >nul
    exit /b 1
)

set "UCI_JAR="
for /f "delims=" %%F in ('dir /b /o:-n "target\chess-engine-*-uci.jar" 2^>nul') do (
    set "UCI_JAR=%SCRIPT_DIR%\target\%%F"
    goto :JarFound
)

echo [ERROR] Unable to locate the chess engine UCI jar in target\.
popd >nul
exit /b 1

:JarFound
popd >nul

echo ------------------------------------------------------------
echo Launching self-play profiling session...
echo   Engine jar   : %UCI_JAR%
echo   Move time    : %PROFILE_MOVE_TIME_MS% ms
echo   Ply count    : %PROFILE_PLY_COUNT%
echo   JFR duration : %PROFILE_JFR_DURATION%
echo Output will be written under %PROFILE_DIR%
echo ------------------------------------------------------------

powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\scripts\profile-engine.ps1" ^
    -JarPath "%UCI_JAR%" ^
    -ProfileDir "%PROFILE_DIR%" ^
    -MoveTimeMs %PROFILE_MOVE_TIME_MS% ^
    -PlyCount %PROFILE_PLY_COUNT% ^
    -JfrDuration "%PROFILE_JFR_DURATION%"
if errorlevel 1 (
    echo [ERROR] Profiling session failed.
    exit /b 1
)

echo Profiling artifacts generated in: %PROFILE_DIR%
exit /b 0
