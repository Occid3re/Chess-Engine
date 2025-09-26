@echo off
setlocal
title Chess Engine × Lichess Bot

rem ---- engine threads: larger root, modest lazy ----
set "CHESSENGINE_THREADS=24"
set "CHESSENGINE_LAZY_THREADS=8"
set "CHESSENGINE_ROOT_PAR_LIMIT=48"

rem ---- heap & GC ----
set "JAVA_XMS=8g"
set "JAVA_XMX=8g"
set "JAVA_GC=g1"
set "JAVA_ACTIVE_PROCESSORS=24"

rem ---- extra JVM/engine opts (ONE LINE; no carets) ----
set "JAVA_EXTRA_OPTS=-XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA -Dchessengine.tt.mb=256 -Dchessengine.uci.info.minIntervalMs=120 -Dchessengine.uci.info.maxPvLen=12"

rem ---- bot options ----
set "BOT_ENABLE_PONDER=0"
rem (tip) prefer: set LICHESS_TOKEN in your user env instead of hardcoding here
set "LICHESS_TOKEN="

set "WORKDIR=C:\Development\Chess-Engine\src\main\resources\py"
pushd "%WORKDIR%" || ( echo [!] Cannot cd & pause & exit /b 1 )
where py >nul 2>nul && (py lichess_bot.py) || (python lichess_bot.py)
set "EXITCODE=%ERRORLEVEL%"
popd
echo.
echo Bot exited with code %EXITCODE%.
pause
exit /b %EXITCODE%
