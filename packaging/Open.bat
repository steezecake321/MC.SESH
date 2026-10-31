@ECHO OFF
rem Opens the manager's web page in your default browser.
cd /d "%~dp0"
set "PORT=8585"
if exist "data\port.txt" set /p PORT=<"data\port.txt"
start "" "http://localhost:%PORT%/"
