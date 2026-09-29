@echo off
setlocal EnableDelayedExpansion
title Cervexa Companion and Wi-Fi Info
set "SCRIPT_DIR=%~dp0"
set "TARGET_SCRIPT=%SCRIPT_DIR%.agents\skills\brainstorming\scripts\start-server.sh"

:: Cari Git Bash jika ada
if exist "C:\Program Files\Git\bin\bash.exe" (
    "C:\Program Files\Git\bin\bash.exe" "%TARGET_SCRIPT%" %*
    goto :done
)

if exist "C:\Program Files\Git\usr\bin\bash.exe" (
    "C:\Program Files\Git\usr\bin\bash.exe" "%TARGET_SCRIPT%" %*
    goto :done
)

where bash >nul 2>&1
if %errorlevel% equ 0 (
    bash "%TARGET_SCRIPT%" %*
    goto :done
)

echo [ERROR] Git Bash tidak ditemukan di lokasi standar.
echo Silakan buka Git Bash dan jalankan: ./start-server.sh
echo.
pause

:done
endlocal
