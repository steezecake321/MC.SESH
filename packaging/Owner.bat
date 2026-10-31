@ECHO OFF
rem Owner tools for MC.SESH accounts. For the person who owns this PC: no manager password is needed.
rem   Owner.bat                         show the commands
rem   Owner.bat list                    list accounts
rem   Owner.bat reset-password alice    set a new password for alice
rem   Owner.bat create-admin bob        create a manager administrator (OP5)
rem   Owner.bat wipe-all                delete every account and start over
cd /d "%~dp0"
if exist "%~dp0runtime\bin\java.exe" (set "J=%~dp0runtime\bin\java.exe") else (set "J=java")
"%J%" -cp "%~dp0app\mcmanager.jar" Main --owner %*
if "%~1"=="" pause