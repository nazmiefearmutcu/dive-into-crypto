# Trading Bot v1 - Windows Surumu

Tek tikla baslayan, ikonlu, native Windows uygulamasi olarak paketlenmis Trading Bot v1.

> **Mevcut TBV1 kaynagi**: `/Users/nazmi/Desktop/Projeler/proje/yedek/TBV1_backup/` (degistirilmedi)
> **Bu paket**: `~/Desktop/Projeler/proje/TBV1_Windows/` (Windows .exe icin yeni paketleme katmani)

## Hizli Bakis

```
TBV1_Windows/
├── app/                    Trading bot kaynak kodu (TBV1_backup'tan kopyalandi)
│   ├── src/                15+ indikatorlu consensus engine
│   ├── dashboard/          FastAPI dashboard (HTML/CSS/JS)
│   ├── config/             default.yaml
│   ├── runtime/            (calistirildiginda log/state buraya)
│   └── requirements.txt
├── launcher/
│   ├── tbv1_launcher.py    Tkinter splash + preflight + uvicorn + browser
│   └── error_codes.py      E001..E020 hata katalogu (Turkce)
├── packaging/
│   ├── TradingBotV1.spec   PyInstaller spec (Windows .exe icin)
│   ├── build_windows.bat   1-tik build script
│   ├── run_dev.bat         Gelistirici modu (build'siz)
│   ├── create_shortcut.vbs Masaustu kisayol olusturucu
│   ├── tbv1.ico            Windows ikonu (7 resolutionlu)
│   ├── tbv1_256.png        256x256 PNG (Tkinter fallback)
│   └── icon_source.png     Orijinal kaynak gorsel
└── docs/
    ├── KURULUM.md          Adim-adim Windows kurulum kilavuzu
    └── HATA_KODLARI.md     E001..E020 Turkce aciklamalar
```

## Calistirma

### Windows'ta hazir .exe varsa
1. `dist\TradingBotV1\TradingBotV1.exe` cift-tikla
2. `Botu Calistir` butonuna bas
3. Tarayici otomatik acilir, dashboard hazir

### Windows'ta kaynaktan derlemek
1. `packaging\build_windows.bat` cift-tikla (5-10 dakika)
2. `dist\TradingBotV1\TradingBotV1.exe` cift-tikla

### Gelistirici modu (build'siz)
1. `packaging\run_dev.bat` cift-tikla

Detayli kurulum: **[docs/KURULUM.md](docs/KURULUM.md)**

## Ozellikler

- ✓ **Tek-tik calistirma** - native Windows uygulamasi gibi davranir
- ✓ **TB ikonu** - tum boyutlarda (16/24/32/48/64/128/256 px), task bar'da net
- ✓ **Otomatik tarayici acma** - varsayilan tarayicinizda yeni sekme
- ✓ **Tarayici uyari ekrani** - "Dashboard tarayicinizda acildi" bilgilendirme dialog
- ✓ **Tkinter status penceresi** - Calisiyor / Durduruldu / Hata durumu canli
- ✓ **20 farkli hata kodu** - her hata icin Turkce baslik + neden + cozum
- ✓ **Tek-instance kilidi** - ayni anda iki kopya acilirsa `E015` ile uyarir
- ✓ **Stale-lock kurtarma** - 3 dakikadan eski kilitler otomatik temizlenir
- ✓ **Crash supervisor** - dashboard cokerse otomatik restart
- ✓ **Yazilabilir runtime** - log/state .exe'nin yaninda kullanici-yazilabilir klasorde
- ✓ **launcher.log** - tum olaylar timestamp + level ile loglanir

## Hata Yonetimi

Hata olustugunda kullaniciya gosterilen ekran:

```
┌─────────────────────────────────────────────┐
│ ▓▓▓ E003 ▓▓▓                                 │  ← kirmizi (fatal) / sari (warning)
├─────────────────────────────────────────────┤
│ Port 8080 baskasi tarafindan kullaniliyor   │
│                                              │
│ Olasi neden:                                 │
│ Bilgisayarda baska bir program 8080 numarali │
│ portu acmis. (Onceki TBV1 hala calisiyor.)   │
│                                              │
│ Cozum:                                       │
│ Gorev Yoneticisi > Ayrintilar sekmesinden    │
│ 'python.exe' veya 'TradingBotV1.exe'        │
│ surecini bitirin. Sonra tekrar deneyin.     │
│                                              │
│ Detay:                                       │
│ 127.0.0.1:8080                              │
│                                              │
│              [Logu Ac]      [Tamam]         │
└─────────────────────────────────────────────┘
```

Tum hata kodlari listesi: **[docs/HATA_KODLARI.md](docs/HATA_KODLARI.md)**

## Sistem Mimarisi

```
Cift-tik (.exe)
    │
    ▼
TradingBotV1.exe         (PyInstaller --onedir bundle)
    │
    ├── tbv1_launcher.py (Tkinter ana surec, GUI)
    │       │
    │       ├── Preflight checks (Python, port, config, izin, RAM, lock)
    │       │       ↓ herhangi biri basarisiz → LauncherError(E0xx)
    │       │
    │       ├── uvicorn (worker thread, ayni surecte)
    │       │       └── dashboard.app:app (FastAPI)
    │       │
    │       └── webbrowser.open("http://127.0.0.1:8080")
    │
    ▼
Varsayilan tarayici → Dashboard UI
```

## Sinirlamalar

- macOS'tan Windows .exe **build edilemez** (PyInstaller cross-compile desteklemiyor). Build islemi Windows'ta yapilmalidir.
- `--onedir` modunda toplam boyut ~250-350 MB (pandas/numpy dahil). Bu PyInstaller ile pratik minimum.
- Imzalanmamis .exe ilk acilista SmartScreen uyarisi alir; kullanici "Yine de calistir" tiklamalidir. (Kod imzalama sertifikasi ile bu uyari kaldirilabilir.)

## Lisans ve Sorumluluk

Bu paket Trading Bot v1'in Windows .exe formatina paketlenmis halidir. Trading kararlarinin sorumluluk kullanicidadir. Paper-trading modunda gelistirici tarafindan test edilmistir, canli trading kullanicinin riski altinda yapilir.
