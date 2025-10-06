@echo off
setlocal
title Chess Engine × Lichess Bot (Prod)

rem =========================
rem   USER SWITCHES
rem =========================
rem Choose your time control preset: bullet | blitz | rapid | classical
if not defined BOT_TC set "BOT_TC=blitz"

rem (Recommended: store LICHESS_TOKEN in your user env, not here)

rem Workdir where lichess_bot.py lives
set "WORKDIR=C:\Development\Chess-Engine\src\main\resources\py"

rem =========================
rem   JVM / ENGINE PRESETS
rem   (tuned for 24 physical cores)
rem =========================
rem GC: prefer ZGC for tiny pauses; fallback to G1 by changing JAVA_GC
set "JAVA_GC=zgc"

rem Default heap (blitz). Bullet a bit smaller, rapid a bit larger — see TC blocks.
set "JAVA_XMS=10g"
set "JAVA_XMX=10g"

rem ---- Time-control presets ----
if /I "%BOT_TC%"=="bullet" (
    rem Ultra-low latency
    set "CHESSENGINE_THREADS=6"
    set "CHESSENGINE_LAZY_THREADS=1"
    set "CHESSENGINE_ROOT_PAR_LIMIT=72"
    set "CHESSENGINE_TT_MB=512"
    set "JAVA_XMS=8g"
    set "JAVA_XMX=8g"
) else if /I "%BOT_TC%"=="blitz" (
    rem Great all-round default
    set "CHESSENGINE_THREADS=16"
    set "CHESSENGINE_LAZY_THREADS=1"
    set "CHESSENGINE_ROOT_PAR_LIMIT=120"
    set "CHESSENGINE_TT_MB=1024"
    set "JAVA_XMS=10g"
    set "JAVA_XMX=10g"
) else if /I "%BOT_TC%"=="rapid" (
    rem Deeper search; still leaves OS headroom
    set "CHESSENGINE_THREADS=20"
    set "CHESSENGINE_LAZY_THREADS=1"
    set "CHESSENGINE_ROOT_PAR_LIMIT=144"
    set "CHESSENGINE_TT_MB=1536"
    set "JAVA_XMS=12g"
    set "JAVA_XMX=12g"
) else if /I "%BOT_TC%"=="classical" (
    rem Similar to rapid; adjust to your RAM if wanted
    set "CHESSENGINE_THREADS=24"
    set "CHESSENGINE_LAZY_THREADS=1"
    set "CHESSENGINE_ROOT_PAR_LIMIT=144"
    set "CHESSENGINE_TT_MB=1536"
    set "JAVA_XMS=12g"
    set "JAVA_XMX=12g"
) else (
    echo [!] Unknown BOT_TC value "%BOT_TC%". Using blitz defaults.
    set "BOT_TC=blitz"
    set "CHESSENGINE_THREADS=10"
    set "CHESSENGINE_LAZY_THREADS=1"
    set "CHESSENGINE_ROOT_PAR_LIMIT=120"
    set "CHESSENGINE_TT_MB=1024"
    set "JAVA_XMS=10g"
    set "JAVA_XMX=10g"
)

rem =========================
rem   EXTRA JVM / ENGINE OPTS
rem   (single line; no carets)
rem =========================
rem - ZGC: no need for MaxGCPauseMillis; AlwaysPreTouch stabilizes latency
rem - Opening book off during games to avoid random disk stalls; enable only if preloaded
rem - Trim UCI chatter a bit; shorter PV reduces tiny allocs
set "JAVA_EXTRA_OPTS=-XX:+AlwaysPreTouch -Dchessengine.tt.mb=%CHESSENGINE_TT_MB% -Dchessengine.openingbook.enabled=false -Dchessengine.uci.info.minIntervalMs=200 -Dchessengine.uci.info.maxPvLen=10"

rem Ponder off is safest on Lichess; MoveOverhead set in Python (120 bullet / 200 blitz+)
set "BOT_ENABLE_PONDER=0"

rem =========================
rem   LAUNCH
rem =========================
pushd "%WORKDIR%" || ( echo [!] Cannot cd to "%WORKDIR%" & pause & exit /b 1 )

echo.
echo =========================
echo   Lichess Bot Launch
echo   TC preset      : %BOT_TC%
echo   Threads        : search=%CHESSENGINE_THREADS% lazy=%CHESSENGINE_LAZY_THREADS%
echo   Root fanout    : %CHESSENGINE_ROOT_PAR_LIMIT%
echo   TT (MB)        : %CHESSENGINE_TT_MB%
echo   GC / Heap      : %JAVA_GC%  Xms=%JAVA_XMS%  Xmx=%JAVA_XMX%
echo =========================
echo.

rem Pass env to Python; your script reads these via os.environ
where py >nul 2>nul && (py lichess_bot.py) || (python lichess_bot.py)
set "EXITCODE=%ERRORLEVEL%"

popd
echo.
echo Bot exited with code %EXITCODE%.
pause
exit /b %EXITCODE%



