@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 non trovato. Installa o rendi disponibile Java nel PATH.
  pause
  exit /b 1
)
java -jar "%~dp0migration-workbench.jar"
if errorlevel 1 pause
