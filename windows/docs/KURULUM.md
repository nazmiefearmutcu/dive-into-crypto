# Trading Bot v1 - Windows Kurulum Kilavuzu

Bu belge, Trading Bot v1'i bir Windows bilgisayara nasil kuracaginizi adim adim anlatir.

> **Onkosul**: Windows 10 veya 11 (64-bit), en az 4 GB RAM, 500 MB bos disk.

---

## Yontem 1: Onceden Derlenmis .exe (Onerilen)

Eger size hazir `TradingBotV1` klasoru verildiyse:

1. Klasoru istediginiz yere kopyalayin. **Onerilen: `C:\Users\<sizinad>\Trading Bot v1\`**
   (`C:\Program Files\` altina koymayin — yazma izni problemi `E010` cikarir.)

2. Klasore girin. Icinde `TradingBotV1.exe` ve baska binary dosyalar olacaktir.

3. **TradingBotV1.exe**'yi cift-tiklayin.

4. Ilk acilista **Windows SmartScreen** uyarisi cikabilir:
   - "PC'nizi korudu" yazar.
   - `Ek bilgi` -> `Yine de calistir` tiklayin.
   - Bu, imzalanmamis exe'lerde her kullanicida gorulen normal uyaridir.

5. Tkinter penceresi acilir, `Botu Calistir` butonuna basin.

6. ~10 saniye icinde varsayilan tarayicinizda
   `http://127.0.0.1:8080` adresinde dashboard acilir.

7. **Masaustu kisayolu** olusturmak icin: `packaging\create_shortcut.vbs` dosyasina cift-tiklayin. Masaustunde `Trading Bot v1.lnk` olusur.

---

## Yontem 2: Kaynak Koddan Derleyerek

Eger size yalnizca proje klasoru verildiyse `.exe` urettirmeniz gerekir.

### Adim 1: Python kurulumu

1. https://www.python.org/downloads/ adresine gidin.
2. En son **Python 3.11** veya **3.12** surumunu indirin.
3. Kurulum baslarken **mutlaka** `Add Python to PATH` kutucugunu isaretleyin.
4. Komut Istemi (cmd) acin ve `python --version` yazarak surumun gorundugunden emin olun.

### Adim 2: Projeyi cikarip build alma

1. `TBV1_Windows` projesini hedef bilgisayara kopyalayin (ornek: `C:\Trading Bot v1\`).
2. Proje klasorune girin.
3. `packaging\build_windows.bat` dosyasina **cift-tiklayin**.
4. Build script otomatik olarak su isleri yapar (5-10 dakika):
   - Sanal ortam (`.venv\`) olusturur
   - Tum bagimliliklari (`requirements.txt`) kurar
   - PyInstaller kurar
   - `dist\TradingBotV1\TradingBotV1.exe` uretir

5. Bittiginde script "BASARILI!" yazar. Enter'a basip kapatabilirsiniz.

6. `dist\TradingBotV1\` klasoru artik tasinabilir. Istediginiz yere kopyalayin ve `TradingBotV1.exe`'yi cift-tiklayin.

---

## Yontem 3: Gelistirici Modu (Build almadan)

`.exe` build etmek istemiyorsaniz, dogrudan Python ile calistirabilirsiniz:

1. Yukaridaki Adim 1'i tamamlayin (Python kurulumu).
2. Proje klasorunde `packaging\run_dev.bat` dosyasina cift-tiklayin.
3. Ilk acilista bagimliliklar kurulur (1-2 dakika).
4. Sonra her acilista launcher penceresi gelir ve cift-tiklamayla calisir.

---

## Konfigurasyon

### Botun davranisini ayarlamak

Bot'un agirliklarini, indikatorlerini ve risk parametrelerini `app/config/default.yaml` dosyasinda tanimlanir.

Bu dosyayi sag tikla > `Notepad ile ac` ile duzenleyebilirsiniz. Degisiklikleri dashboard'un **Ayarlar** sekmesinden de yapabilirsiniz (otomatik kaydedilir).

### Binance API anahtarlarini ekleme (canli trading icin)

1. Proje klasorunde `app\.env` adli bir dosya olusturun (Notepad'de "Yeni metin belgesi" > `.env` olarak yeniden adlandirin).
2. Icine sunlari yazin:

```
BINANCE_API_KEY=buraya_anahtariniz
BINANCE_API_SECRET=buraya_secret_anahtariniz
```

3. Binance'ten anahtar uretmek icin: https://www.binance.com/en/my/settings/api-management

> **Guvenlik**: API anahtarlariniz `.env` dosyasinda **dusuk yuk** sahibi olsun (yalnizca "Read" + "Spot Trading" yetkileri). "Withdrawals" KESINLIKLE acilmamalidir.

`.env` dosyasi yoksa bot **paper-trading** (sahte para) modunda calismaya devam eder. Uyari verir ama durmaz (kod: `E006`).

---

## Sik Karsilasilan Sorunlar

| Sorun                          | Cozum                                  |
|--------------------------------|----------------------------------------|
| SmartScreen `Yine de calistir` cikmiyor | exe'ye sag tikla > Ozellikler > "Engellemeyi kaldir" |
| `python` komutu bulunamadi      | Python'u yeniden kurun, `Add to PATH`'i isaretleyin |
| Port 8080 zaten kullanimda      | Gorev Yoneticisi > python.exe surec'leri kapatin |
| Bagimliliklar kurulamiyor       | `pip install` hatasi internet/firewall kaynakli olabilir |
| Antivirus exe'yi siliyor        | "Whitelist" / "Exclusion" listesine ekleyin |
| Tarayici acilmadi               | Manuel: `http://127.0.0.1:8080` adresini yazin |

Tum hata kodlari ve cozumleri icin: **[HATA_KODLARI.md](HATA_KODLARI.md)**

---

## Kaldirma

1. `dist\TradingBotV1\` klasorunu silin.
2. Kaynak proje klasorunu silin.
3. Masaustu kisayolunu silin.
4. Python'un kendisini silmek isterseniz: Ayarlar > Uygulamalar > Python 3.x > Kaldir.

Trading Bot v1 **hicbir** kayit defteri girisi veya sistem dosyasi olusturmaz. Tum veriler kendi klasoru icinde tutulur.
