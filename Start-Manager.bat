@ECHO OFF
rem Builds (if needed) and starts the MC Server Manager with no console window.
rem Usage: Start-Manager.bat           -> start detached (double-click / shortcut)
rem        Start-Manager.bat service   -> stay in the foreground until the manager exits (used by the boot task)
cd /d "%~dp0"
if not exist build mkdir build
javac -Xlint:-options -encoding UTF-8 -d build src\*.java > build\build.log 2>&1
if errorlevel 1 (
  if not exist build\Main.class (
    echo Build failed - see "%~dp0build\build.log"
    pause
    exit /b 1
  )
)
set "JOPTS=-Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"
if /i "%~1"=="service" (
  javaw %JOPTS% -cp build Main
) else (
  start "" javaw %JOPTS% -cp build Main
)
