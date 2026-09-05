@echo off
title Cervexa Print Bridge Server
color 0B
cls
echo =======================================================
echo          CERVEXA MEDICAL PRINT BRIDGE SERVER
echo =======================================================
echo.

where python >nul 2>&1
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] Python tidak ditemukan di sistem ini.
    echo Silakan install Python 3 dari https://www.python.org/
    echo Pastikan mencentang "Add Python to PATH" saat instalasi.
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0"
python server.py
if %errorlevel% neq 0 (
    echo.
    echo Server berhenti dengan error code %errorlevel%.
    pause
)
