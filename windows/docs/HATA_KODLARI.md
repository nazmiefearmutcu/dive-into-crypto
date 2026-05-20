# Trading Bot v1 - Hata Kodlari

| Kod | Baslik | Onem | Cozum |
|---|---|---|---|
| **E001** | Python surumu uyumsuz | fatal | https://www.python.org/downloads/ adresinden Python 3.11+ kurun ve 'Add to PATH'... |
| **E002** | Gerekli paket bulunamadi | fatal | Komut satirinda: pip install -r requirements.txt komutunu calistirin. Paketlenmi... |
| **E003** | Port 8080 baskasi tarafindan kullaniliyor | fatal | Gorev Yoneticisi > Ayrintilar sekmesinden 'python.exe' veya 'TradingBotV1.exe' s... |
| **E004** | Konfigurasyon dosyasi yok | fatal | Uygulama klasoru bozulmus olabilir. Kurulumu yeniden yapin veya yedek default.ya... |
| **E005** | Konfigurasyon dosyasi okunamiyor | fatal | Dosyayi bir text editor (Notepad++, VS Code) ile acin ve hatali satiri duzeltin.... |
| **E006** | .env dosyasi yok | warning | Canli alim-satim icin: Uygulama klasorunde .env adli dosya olusturun ve icine BI... |
| **E007** | Dashboard sunucusu baslatilamadi | fatal | (1) Windows Defender > 'Uygulama izin verilenler' listesine TradingBotV1.exe ekl... |
| **E008** | Dashboard yanit vermiyor | fatal | Once bilgisayari yeniden baslatip tekrar deneyin. Devam ederse: Gorev Yoneticisi... |
| **E009** | Tarayici acilmadi | warning | Tarayicinizi (Chrome/Edge/Firefox) manuel olarak acin ve adres cubuguna yazin: h... |
| **E010** | Klasore yazma izni yok | fatal | (1) TradingBotV1.exe dosyasini sag tiklayip 'Yonetici olarak calistir' secin. (2... |
| **E011** | Disk dolu | fatal | Disk Temizleme aracini calistirin. C:\Users\<sizinad>\AppData\Local\Temp icindek... |
| **E012** | Binance API anahtari gecersiz | warning | .env dosyasindaki BINANCE_API_KEY ve BINANCE_API_SECRET degerlerini Binance hesa... |
| **E013** | Binance baglantisi yok | warning | Tarayicidan https://api.binance.com/api/v3/ping adresini acin. JSON donmuyorsa: ... |
| **E014** | Beklenmedik ic hata | fatal | packaging/launcher.log dosyasinin SON 100 satirini kopyalayip gelistirici ile pa... |
| **E015** | Birden fazla kopya tespit edildi | fatal | Mevcut kopyaya gecip onu kullanin. Eski bir kopya kilitli kalmissa: Gorev Yoneti... |
| **E016** | Windows Firewall engelliyor | fatal | Windows Defender Firewall > 'Uygulama veya ozellik izin ver' menusunden TradingB... |
| **E017** | Bellek yetersiz | warning | Diger programlari kapatin (ozellikle tarayicilar, Discord, Slack). Sistemi yenid... |
| **E018** | Saat ayari bozuk | warning | Windows Saat ayarlari > 'Saati otomatik ayarla' seceneklerini acin ve 'Hemen sen... |
| **E019** | State dosyasi bozuk | warning | Otomatik kurtarma denenir. Basarisizsa: runtime/state.json dosyasini silin (varo... |
| **E020** | Calistirma izni reddedildi | fatal | SmartScreen uyari ekraninda 'Ek bilgi' > 'Yine de calistir' tiklayin. Antivirus ... |

## Detayli Aciklamalar

### E001 - Python surumu uyumsuz

**Onem:** `fatal`

**Olasi Neden:** Trading Bot v1 en az Python 3.10 gerektirir. Mevcut surum cok eski.

**Cozum:** https://www.python.org/downloads/ adresinden Python 3.11+ kurun ve 'Add to PATH' secenegini isaretleyin.

### E002 - Gerekli paket bulunamadi

**Onem:** `fatal`

**Olasi Neden:** Bir veya birden fazla bagimlilik kurulu degil (fastapi, uvicorn, pandas, vb.).

**Cozum:** Komut satirinda: pip install -r requirements.txt komutunu calistirin. Paketlenmis .exe sururken bu hata gormezsiniz; sadece kaynak koddan calistirirken cikar.

### E003 - Port 8080 baskasi tarafindan kullaniliyor

**Onem:** `fatal`

**Olasi Neden:** Bilgisayarda baska bir program 8080 numarali portu acmis. (Onceki TBV1 hala calisiyor olabilir.)

**Cozum:** Gorev Yoneticisi > Ayrintilar sekmesinden 'python.exe' veya 'TradingBotV1.exe' surecini bitirin. Sonra tekrar deneyin. Alternatif: launcher penceresindeki Port alanini 8081 vb. yapin.

### E004 - Konfigurasyon dosyasi yok

**Onem:** `fatal`

**Olasi Neden:** config/default.yaml bulunamadi. Bu dosya botun davranisini tanimlar.

**Cozum:** Uygulama klasoru bozulmus olabilir. Kurulumu yeniden yapin veya yedek default.yaml dosyasini yerine kopyalayin.

### E005 - Konfigurasyon dosyasi okunamiyor

**Onem:** `fatal`

**Olasi Neden:** default.yaml dosyasinda gecersiz YAML sozdizimi var (girinti hatasi, eksik iki nokta vb.).

**Cozum:** Dosyayi bir text editor (Notepad++, VS Code) ile acin ve hatali satiri duzeltin. Detayli mesaj log dosyasinda.

### E006 - .env dosyasi yok

**Onem:** `warning`

**Olasi Neden:** API anahtarlarini iceren .env dosyasi bulunamadi. Paper-trading modda calismaya devam edilebilir.

**Cozum:** Canli alim-satim icin: Uygulama klasorunde .env adli dosya olusturun ve icine BINANCE_API_KEY=... ve BINANCE_API_SECRET=... satirlarini ekleyin.

### E007 - Dashboard sunucusu baslatilamadi

**Onem:** `fatal`

**Olasi Neden:** Uvicorn alt sureci baslar baslamaz cikti. Antivirus engellemis veya kritik dosya silinmis olabilir.

**Cozum:** (1) Windows Defender > 'Uygulama izin verilenler' listesine TradingBotV1.exe ekleyin. (2) packaging/launcher.log dosyasini acip son satirlardaki Python hata izini paylasin.

### E008 - Dashboard yanit vermiyor

**Onem:** `fatal`

**Olasi Neden:** Sunucu basladi ama 30 saniye icinde HTTP yaniti uretmedi. Yavas disk, bellek yetersizligi veya sonsuz dongu olabilir.

**Cozum:** Once bilgisayari yeniden baslatip tekrar deneyin. Devam ederse: Gorev Yoneticisi > Performans sekmesinden RAM kullanimini kontrol edin (>%90 ise diger programlari kapatin).

### E009 - Tarayici acilmadi

**Onem:** `warning`

**Olasi Neden:** webbrowser modulu varsayilan tarayiciyi calistiramadi. Tarayici yuklu olmayabilir veya kayit defteri bozuk olabilir.

**Cozum:** Tarayicinizi (Chrome/Edge/Firefox) manuel olarak acin ve adres cubuguna yazin: http://127.0.0.1:8080  -- Bot zaten arka planda calisiyor, sadece otomatik tarayici acma basarisiz oldu.

### E010 - Klasore yazma izni yok

**Onem:** `fatal`

**Olasi Neden:** Uygulama runtime/ klasorune log/state yazamiyor. Bu klasor read-only veya antivirus tarafindan kilitli olabilir.

**Cozum:** (1) TradingBotV1.exe dosyasini sag tiklayip 'Yonetici olarak calistir' secin. (2) Uygulama klasorunu C:\Program Files yerine kullanicininizin Documents klasorune tasiyin.

### E011 - Disk dolu

**Onem:** `fatal`

**Olasi Neden:** Disk'te yeterli alan kalmadi. Trading bot dakikada bir log yazar; ~10MB serbest alan gerekir.

**Cozum:** Disk Temizleme aracini calistirin. C:\Users\<sizinad>\AppData\Local\Temp icindeki dosyalari silin. En az 500MB serbest alan brakin.

### E012 - Binance API anahtari gecersiz

**Onem:** `warning`

**Olasi Neden:** Binance HTTP 401/403 dondu. Anahtar yanlis, suresi dolmus veya IP whitelisting aktif.

**Cozum:** .env dosyasindaki BINANCE_API_KEY ve BINANCE_API_SECRET degerlerini Binance hesabinizdaki Yeni anahtar uretip kopyalayarak guncelleyin. IP kisitlamasi varsa devre disi birakin veya genel IP'nizi ekleyin.

### E013 - Binance baglantisi yok

**Onem:** `warning`

**Olasi Neden:** api.binance.com adresine ulasilamiyor. Internet kesik, DNS sorunu veya Turkiye'de Binance erisimi kisitli olabilir.

**Cozum:** Tarayicidan https://api.binance.com/api/v3/ping adresini acin. JSON donmuyorsa: (1) VPN deneyin (yasal sinirlar dahilinde). (2) Modeminizi yeniden baslatin. (3) Antivirus/firewall'da Python.exe'ye internet izni verin.

### E014 - Beklenmedik ic hata

**Onem:** `fatal`

**Olasi Neden:** Yakalanmamis bir Python istisnasi olustu. Bu bir hata raporu olarak iletilmelidir.

**Cozum:** packaging/launcher.log dosyasinin SON 100 satirini kopyalayip gelistirici ile paylasin. Bot durdurulup yeniden baslatilabilir, ancak ayni hata tekrarlayabilir.

### E015 - Birden fazla kopya tespit edildi

**Onem:** `fatal`

**Olasi Neden:** TradingBotV1 zaten calisiyor (lockfile mevcut). Iki kopya ayni anda calisirsa veriler bozulur.

**Cozum:** Mevcut kopyaya gecip onu kullanin. Eski bir kopya kilitli kalmissa: Gorev Yoneticisi'nde TradingBotV1.exe'yi bitirin ve runtime/.launcher.lock dosyasini silin.

### E016 - Windows Firewall engelliyor

**Onem:** `fatal`

**Olasi Neden:** Windows Guvenlik Duvari, dashboard'un kendine baglanmasini engelliyor (loopback 127.0.0.1).

**Cozum:** Windows Defender Firewall > 'Uygulama veya ozellik izin ver' menusunden TradingBotV1.exe icin hem 'Ozel' hem 'Genel' sutununu isaretleyin.

### E017 - Bellek yetersiz

**Onem:** `warning`

**Olasi Neden:** Sistemde 200MB'tan az kullanilabilir bellek var. Bot baslatilirsa Windows takilabilir.

**Cozum:** Diger programlari kapatin (ozellikle tarayicilar, Discord, Slack). Sistemi yeniden baslatip tekrar deneyin. Cok eski (4GB RAM altinda) PC'lerde sik gorulen sorundur.

### E018 - Saat ayari bozuk

**Onem:** `warning`

**Olasi Neden:** Windows sistem saati Binance sunucu saatinden 1 dakikadan fazla farkli. Binance imza dogrulamasi reddediyor.

**Cozum:** Windows Saat ayarlari > 'Saati otomatik ayarla' seceneklerini acin ve 'Hemen senkronize et' butonuna basin. NTP sunucu erisimi engelliyse zaman senkronizasyonu icin time.windows.com kullanin.

### E019 - State dosyasi bozuk

**Onem:** `warning`

**Olasi Neden:** runtime/state.json gecersiz JSON. Onceki cikiste yarim kalmis olabilir.

**Cozum:** Otomatik kurtarma denenir. Basarisizsa: runtime/state.json dosyasini silin (varolan pozisyonlar paper modunda ise kaybolur, canli modda sadece izleme metadatasi kaybedilir).

### E020 - Calistirma izni reddedildi

**Onem:** `fatal`

**Olasi Neden:** Windows SmartScreen veya antivirus, imzasiz .exe'yi engelliyor.

**Cozum:** SmartScreen uyari ekraninda 'Ek bilgi' > 'Yine de calistir' tiklayin. Antivirus icin: TradingBotV1.exe yi 'Guvenli uygulamalar' (whitelist / exclusion) listesine ekleyin. Cogu false-positive'dir.

