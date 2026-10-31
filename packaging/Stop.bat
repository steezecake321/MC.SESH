@ECHO OFF
rem Stops MC.SESH. Your Minecraft servers keep running.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Stop-Manager.ps1" -InstallDir "%~dp0."
ping -n 3 127.0.0.1 >nul
