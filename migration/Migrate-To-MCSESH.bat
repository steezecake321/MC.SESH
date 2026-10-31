@echo off
rem Runs the migration from the old "MC Server Manager" install to MC.SESH. It asks Windows for Administrator approval, and the window stays open so you can read the result.
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Asking Windows for permission to continue...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%~dp0Migrate-To-MCSESH.ps1" %*
