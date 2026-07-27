@echo off
cd /d "%~dp0"
echo ==========================================================
echo   CERVEXA - AUTOMATIC TFLITE TRAINING LAUNCHER
echo ==========================================================
echo Menggunakan Python 3.12 (yang sudah terinstal TensorFlow)...
echo.

py -3.12 train_multitype_dataset.py

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Gagal menjalankan script dengan 'py -3.12'.
    echo Mencoba dengan full path Python 3.12...
    "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" train_multitype_dataset.py
)

echo.
pause
