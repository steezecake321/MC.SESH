@ECHO OFF
title MC.SESH - Setup
cd /d "%~dp0"
echo.
echo  MC.SESH setup
echo  -----------------------
echo  Opening the setup in your web browser...
echo  Keep this window open until setup finishes. Close it to cancel.
echo  (No browser? Run Install-Text.bat for the plain text setup instead.)
echo.
if not exist "%~dp0runtime\bin\java.exe" goto notfound
"%~dp0runtime\bin\java.exe" -Xmx128m -jar "%~dp0app\mcmanager.jar" --setup
if errorlevel 1 (
  echo.
  echo  The browser setup could not start. Run Install-Text.bat instead.
  echo.
  pause
)
exit /b
:notfound
echo  Setup needs the files from the unzipped MC.SESH folder (runtime\bin\java.exe is missing).
echo  Unzip the whole download first, then run Install.bat from the unzipped folder.
echo.
pause
