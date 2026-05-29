@echo off
REM ============================================================
REM Trading Bot v1 - Developer Mode (without building the .exe)
REM ============================================================
REM This file runs the launcher directly with the Python interpreter.
REM There is NO need to build the .exe. It is meant for quick testing.
REM ============================================================

setlocal enabledelayedexpansion
cd /d "%~dp0\.."

where python >nul 2>&1
if errorlevel 1 (
    echo [ERROR E001] Python not found.
    pause
    exit /b 1
)

if not exist ".venv\" (
    echo [+] No virtual environment, creating one...
    python -m venv .venv
)

call .venv\Scripts\activate.bat

REM Check whether dependencies are installed
python -c "import fastapi, uvicorn, pandas, yaml" >nul 2>&1
if errorlevel 1 (
    echo [+] Installing dependencies...
    python -m pip install -r app\requirements.txt --quiet
)

echo.
echo  ============================================================
echo   Trading Bot v1 - Developer Mode
echo  ============================================================
echo.

python launcher\tbv1_launcher.py
endlocal
