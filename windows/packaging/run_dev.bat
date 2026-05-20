@echo off
REM ============================================================
REM Trading Bot v1 - Gelistirici Modu (.exe build etmeden)
REM ============================================================
REM Bu dosya Python yorumlayicisi ile dogrudan launcher'i calistirir.
REM .exe build etmeye gerek YOK. Hizli test icindir.
REM ============================================================

setlocal enabledelayedexpansion
cd /d "%~dp0\.."

where python >nul 2>&1
if errorlevel 1 (
    echo [HATA E001] Python bulunamadi.
    pause
    exit /b 1
)

if not exist ".venv\" (
    echo [+] Sanal ortam yok, olusturuluyor...
    python -m venv .venv
)

call .venv\Scripts\activate.bat

REM Bagimliliklar yuklu mu kontrol et
python -c "import fastapi, uvicorn, pandas, yaml" >nul 2>&1
if errorlevel 1 (
    echo [+] Bagimliliklar yukleniyor...
    python -m pip install -r app\requirements.txt --quiet
)

echo.
echo  ============================================================
echo   Trading Bot v1 - Gelistirici Modu
echo  ============================================================
echo.

python launcher\tbv1_launcher.py
endlocal
