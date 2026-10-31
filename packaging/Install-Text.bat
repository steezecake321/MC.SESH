@ECHO OFF
title MC.SESH - Setup (text)
cd /d "%~dp0"
echo.
echo  MC.SESH setup (text version)
echo  -----------------------
echo  This asks where to install, where new servers go, and whether to start with Windows.
echo  For each question: press Enter for the suggested answer, or type your own
echo  (for example a different folder such as D:\Apps\MC.SESH) and press Enter.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup.ps1" %*
echo.
if errorlevel 1 (
  echo  Setup did not finish. Read the message above, then run Install-Text.bat again.
) else (
  echo  Setup finished.
)
echo.
pause