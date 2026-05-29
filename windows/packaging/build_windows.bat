@echo off
REM ============================================================
REM Trading Bot v1 - Windows Build Script
REM ============================================================
REM Double-click this file on Windows. It performs:
REM   1) Python check (>= 3.10)
REM   2) Virtual environment (venv) creation
REM   3) Dependency installation (pip install -r ...)
REM   4) .exe build with PyInstaller
REM   5) Result in the dist\TradingBotV1\ folder
REM ============================================================

setlocal enabledelayedexpansion
cd /d "%~dp0\.."

echo.
echo  ============================================================
echo   Trading Bot v1 - Windows .exe Build
echo  ============================================================
echo.

REM ---- 1) Python check ----
where python >nul 2>&1
if errorlevel 1 (
    echo [ERROR E001] Python not found.
    echo Please install Python 3.11+ from https://www.python.org/downloads/
    echo and check the "Add Python to PATH" option.
    pause
    exit /b 1
)

for /f "tokens=2 delims= " %%v in ('python --version') do set PYVER=%%v
echo [+] Python version: %PYVER%

REM ---- 2) create venv ----
if not exist ".venv\" (
    echo [+] Creating virtual environment (.venv\)...
    python -m venv .venv
    if errorlevel 1 (
        echo [ERROR] Could not create virtual environment.
        pause
        exit /b 1
    )
)

call .venv\Scripts\activate.bat
if errorlevel 1 (
    echo [ERROR] Could not activate virtual environment.
    pause
    exit /b 1
)

REM ---- 3) dependencies ----
echo [+] Updating pip...
python -m pip install --upgrade pip --quiet

echo [+] Installing trading bot dependencies (may take 1-2 minutes)...
python -m pip install -r app\requirements.txt --quiet
if errorlevel 1 (
    echo [ERROR E002] Dependencies could not be installed. Check your internet connection.
    pause
    exit /b 1
)

echo [+] Installing PyInstaller...
python -m pip install pyinstaller --quiet
if errorlevel 1 (
    echo [ERROR] PyInstaller could not be installed.
    pause
    exit /b 1
)

REM ---- 4) build ----
echo.
echo [+] Cleaning old build (build\, dist\)...
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist

echo [+] Running PyInstaller (may take 5-10 minutes)...
pyinstaller packaging\TradingBotV1.spec --clean --noconfirm
if errorlevel 1 (
    echo.
    echo [ERROR] PyInstaller failed. See details above.
    pause
    exit /b 1
)

REM ---- 5) result ----
if exist "dist\TradingBotV1\TradingBotV1.exe" (
    echo.
    echo  ============================================================
    echo   SUCCESS! Build complete.
    echo  ============================================================
    echo.
    echo   Executable file:
    echo     dist\TradingBotV1\TradingBotV1.exe
    echo.
    echo   USAGE:
    echo     1. Copy the ENTIRE dist\TradingBotV1\ folder
    echo        (example: to the Desktop as Trading Bot v1^)
    echo     2. Double-click the TradingBotV1.exe inside it
    echo     3. It will open automatically in the browser
    echo.
    echo   To create a desktop shortcut:
    echo     Double-click the packaging\create_shortcut.vbs file
    echo.
) else (
    echo [ERROR] Build completed but TradingBotV1.exe was not found.
    echo Check the spec file and the logs.
    pause
    exit /b 1
)

pause
endlocal
