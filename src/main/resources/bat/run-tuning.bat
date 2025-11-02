@echo off
setlocal

REM --- project root ---
cd /d "C:\Development\Chess-Engine"

set "CHESSENGINE_SYZYGY_NATIVE=C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll"
set "CHESSENGINE_SYZYGY_PATHS=C:\Syzygy"

REM --- newest chess-engine-*-uci.jar ---
set "JARFILE="
for /f "usebackq delims=" %%F in (`dir /b /a:-d /o:-d "target\chess-engine-*-uci.jar" 2^>nul`) do (
  set "JARFILE=target\%%F"
  goto :havejar
)
:havejar
if "%JARFILE%"=="" (
  echo [ERROR] No JAR matching target\chess-engine-*-uci.jar found. Build first.
  exit /b 1
)

REM --- inputs ---
set "SEED=src\main\resources\tuning\seed-population.yaml"
set "MATCHES=%CD%\match-log.csv"
if not exist "%SEED%" (
  echo [ERROR] Seed file not found: "%SEED%"
  exit /b 2
)

echo [INFO] Using JAR : "%JARFILE%"
echo [INFO] Seed      : "%SEED%"
echo [INFO] Matches   : "%MATCHES%"
echo.

REM ---------------------------------------------------------------------------
REM IMPORTANT:
REM To select a different main in a Spring Boot fat JAR, you must run the
REM PropertiesLauncher explicitly. Then -Dloader.main=<FQN> is honored.
REM ---------------------------------------------------------------------------

java ^
  "-Dchessengine.syzygy.nativeLibrary=%CHESSENGINE_SYZYGY_NATIVE%" ^
  "-Dchessengine.syzygy.paths=%CHESSENGINE_SYZYGY_PATHS%" ^
  "-Dchessengine.searchThreads=1" ^
  "-Dchessengine.lazySmpThreads=1" ^
  "-Dloader.main=julius.game.chessengine.tuning.GeneticTuningMain" ^
  -cp "%JARFILE%" ^
  org.springframework.boot.loader.launch.PropertiesLauncher ^
  --seed "%SEED%" ^
  --generations 6 ^
  --population 16 ^
  --matches-per-pair 2 ^
  --move-time 2000 ^
  --match-threads 20 ^
  --matches "%MATCHES%"

set "ERR=%ERRORLEVEL%"
echo.
if not "%ERR%"=="0" (
  echo [ERROR] Exited with code %ERR%.
) else (
  echo [INFO] Finished successfully.
)
pause
endlocal
