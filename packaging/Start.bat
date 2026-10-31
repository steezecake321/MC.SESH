@ECHO OFF
rem Starts MC.SESH with no console window.
rem   Start.bat           start in the background, then open the page in your browser
rem   Start.bat service   stay in the foreground (used by the boot task)
rem   Start.bat quiet     start without opening the browser
cd /d "%~dp0"
set "JOPTS=-Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"
if exist "%~dp0runtime\bin\javaw.exe" (set "JW=%~dp0runtime\bin\javaw.exe") else (set "JW=javaw")
if /i "%~1"=="service" (
  "%JW%" %JOPTS% -jar "%~dp0app\mcmanager.jar"
  exit /b
)
start "" "%JW%" %JOPTS% -jar "%~dp0app\mcmanager.jar"
if /i "%~1"=="quiet" exit /b
ping -n 4 127.0.0.1 >nul
call "%~dp0Open.bat"
