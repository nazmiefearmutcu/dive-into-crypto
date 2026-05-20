@echo off
REM ============================================================
REM Trading Bot v1 - Windows Build Script
REM ============================================================
REM Bu dosyayi Windows'ta cift-tiklayin. Yapilan isler:
REM   1) Python kontrolu (>= 3.10)
REM   2) Sanal ortam (venv) olusturma
REM   3) Bagimliliklarin kurulumu (pip install -r ...)
REM   4) PyInstaller ile .exe build
REM   5) dist\TradingBotV1\ klasoru ile sonuc
REM ============================================================

setlocal enabledelayedexpansion
cd /d "%~dp0\.."

echo.
echo  ============================================================
echo   Trading Bot v1 - Windows .exe Build
echo  ============================================================
echo.

REM ---- 1) Python sorgusu ----
where python >nul 2>&1
if errorlevel 1 (
    echo [HATA E001] Python bulunamadi.
    echo Lutfen https://www.python.org/downloads/ adresinden Python 3.11+ kurun
    echo ve "Add Python to PATH" secenegini isaretleyin.
    pause
    exit /b 1
)

for /f "tokens=2 delims= " %%v in ('python --version') do set PYVER=%%v
echo [+] Python surumu: %PYVER%

REM ---- 2) venv olustur ----
if not exist ".venv\" (
    echo [+] Sanal ortam olusturuluyor (.venv\)...
    python -m venv .venv
    if errorlevel 1 (
        echo [HATA] Sanal ortam olusturulamadi.
        pause
        exit /b 1
    )
)

call .venv\Scripts\activate.bat
if errorlevel 1 (
    echo [HATA] Sanal ortam aktif edilemedi.
    pause
    exit /b 1
)

REM ---- 3) bagimliliklar ----
echo [+] pip guncelleniyor...
python -m pip install --upgrade pip --quiet

echo [+] Trading bot bagimliliklari kuruluyor (1-2 dakika surebilir)...
python -m pip install -r app\requirements.txt --quiet
if errorlevel 1 (
    echo [HATA E002] Bagimliliklar kurulamadi. Internet baglantisini kontrol edin.
    pause
    exit /b 1
)

echo [+] PyInstaller kuruluyor...
python -m pip install pyinstaller --quiet
if errorlevel 1 (
    echo [HATA] PyInstaller kurulamadi.
    pause
    exit /b 1
)

REM ---- 4) build ----
echo.
echo [+] Eski build temizleniyor (build\, dist\)...
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist

echo [+] PyInstaller calistiriliyor (5-10 dakika surebilir)...
pyinstaller packaging\TradingBotV1.spec --clean --noconfirm
if errorlevel 1 (
    echo.
    echo [HATA] PyInstaller basarisiz oldu. Detay yukarida.
    pause
    exit /b 1
)

REM ---- 5) sonuc ----
if exist "dist\TradingBotV1\TradingBotV1.exe" (
    echo.
    echo  ============================================================
    echo   BASARILI! Build tamamlandi.
    echo  ============================================================
    echo.
    echo   Calistirilabilir dosya:
    echo     dist\TradingBotV1\TradingBotV1.exe
    echo.
    echo   KULLANIM:
    echo     1. dist\TradingBotV1\ klasorunun TAMAMINI kopyalayin
    echo        (ornek: Masaustune Trading Bot v1 olarak^)
    echo     2. Icindeki TradingBotV1.exe dosyasini cift-tiklayin
    echo     3. Otomatik tarayicida acilacaktir
    echo.
    echo   Masaustu kisayolu olusturmak icin:
    echo     packaging\create_shortcut.vbs dosyasina cift-tiklayin
    echo.
) else (
    echo [HATA] Build tamamlandi ama TradingBotV1.exe bulunamadi.
    echo Spec dosyasini ve loglari kontrol edin.
    pause
    exit /b 1
)

pause
endlocal
