@echo off
rem Installer for the downloaded source ZIP: double-click to put the kavunOS.apk next to this file on the TV.
rem The real installer is launcher\kavunOS-kur.cmd; extra arguments go to launcher\install.ps1 (e.g. -TvHost 192.168.1.50).
rem No ( ) blocks: a folder like "kavunOS-main (1)" puts a ) into %~dp0 and ends the block early.
if not exist "%~dp0kavunOS.apk" goto latest
call "%~dp0launcher\kavunOS-kur.cmd" -Apk "%~dp0kavunOS.apk" %*
goto :eof
:latest
call "%~dp0launcher\kavunOS-kur.cmd" %*
