@echo off
rem Double-click installer: finds the TV on the network and installs the latest kavunOS release on it.
rem Next to a checkout's install.ps1 it runs that one; on its own (e.g. downloaded from the release) it fetches it.
rem Extra arguments go to install.ps1, e.g.  kavunOS-kur.cmd -TvHost 192.168.1.50 -Reinstall
rem No ( ) blocks: a folder like "kavunOS-main (1)" puts a ) into %~dp0 and ends the block early.
title kavunOS
if not exist "%~dp0install.ps1" goto web
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -HomeKeyService %*
goto done
:web
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = 'Tls12'; & ([scriptblock]::Create((irm https://raw.githubusercontent.com/batuhankul61-hub/kavunOS/main/launcher/install.ps1))) -HomeKeyService %*"
:done
echo.
pause
