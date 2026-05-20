"""Trading Bot v1 - Hata Kodlari Katalogu.

Her hata kodu uc bilesenden olusur:
  - code:     E001..E099 araliginda benzersiz tanitici
  - title:    Kisa Turkce baslik (UI'da gosterilir)
  - cause:    Neden olusabilecegine dair aciklama
  - remedy:   Kullanicinin yapmasi gereken adimlar
  - severity: 'fatal' | 'warning' | 'info'

`raise LauncherError(code='E007', ...)` veya `ErrorCatalog.get('E007')` ile kullanilir.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional


@dataclass(frozen=True)
class ErrorEntry:
    code: str
    title: str
    cause: str
    remedy: str
    severity: str  # 'fatal' | 'warning' | 'info'


_CATALOG: Dict[str, ErrorEntry] = {
    "E001": ErrorEntry(
        code="E001",
        title="Python surumu uyumsuz",
        cause="Trading Bot v1 en az Python 3.10 gerektirir. Mevcut surum cok eski.",
        remedy="https://www.python.org/downloads/ adresinden Python 3.11+ kurun ve 'Add to PATH' secenegini isaretleyin.",
        severity="fatal",
    ),
    "E002": ErrorEntry(
        code="E002",
        title="Gerekli paket bulunamadi",
        cause="Bir veya birden fazla bagimlilik kurulu degil (fastapi, uvicorn, pandas, vb.).",
        remedy="Komut satirinda: pip install -r requirements.txt komutunu calistirin. Paketlenmis .exe sururken bu hata gormezsiniz; sadece kaynak koddan calistirirken cikar.",
        severity="fatal",
    ),
    "E003": ErrorEntry(
        code="E003",
        title="Port 8080 baskasi tarafindan kullaniliyor",
        cause="Bilgisayarda baska bir program 8080 numarali portu acmis. (Onceki TBV1 hala calisiyor olabilir.)",
        remedy="Gorev Yoneticisi > Ayrintilar sekmesinden 'python.exe' veya 'TradingBotV1.exe' surecini bitirin. Sonra tekrar deneyin. Alternatif: launcher penceresindeki Port alanini 8081 vb. yapin.",
        severity="fatal",
    ),
    "E004": ErrorEntry(
        code="E004",
        title="Konfigurasyon dosyasi yok",
        cause="config/default.yaml bulunamadi. Bu dosya botun davranisini tanimlar.",
        remedy="Uygulama klasoru bozulmus olabilir. Kurulumu yeniden yapin veya yedek default.yaml dosyasini yerine kopyalayin.",
        severity="fatal",
    ),
    "E005": ErrorEntry(
        code="E005",
        title="Konfigurasyon dosyasi okunamiyor",
        cause="default.yaml dosyasinda gecersiz YAML sozdizimi var (girinti hatasi, eksik iki nokta vb.).",
        remedy="Dosyayi bir text editor (Notepad++, VS Code) ile acin ve hatali satiri duzeltin. Detayli mesaj log dosyasinda.",
        severity="fatal",
    ),
    "E006": ErrorEntry(
        code="E006",
        title=".env dosyasi yok",
        cause="API anahtarlarini iceren .env dosyasi bulunamadi. Paper-trading modda calismaya devam edilebilir.",
        remedy="Canli alim-satim icin: Uygulama klasorunde .env adli dosya olusturun ve icine BINANCE_API_KEY=... ve BINANCE_API_SECRET=... satirlarini ekleyin.",
        severity="warning",
    ),
    "E007": ErrorEntry(
        code="E007",
        title="Dashboard sunucusu baslatilamadi",
        cause="Uvicorn alt sureci baslar baslamaz cikti. Antivirus engellemis veya kritik dosya silinmis olabilir.",
        remedy="(1) Windows Defender > 'Uygulama izin verilenler' listesine TradingBotV1.exe ekleyin. (2) packaging/launcher.log dosyasini acip son satirlardaki Python hata izini paylasin.",
        severity="fatal",
    ),
    "E008": ErrorEntry(
        code="E008",
        title="Dashboard yanit vermiyor",
        cause="Sunucu basladi ama 30 saniye icinde HTTP yaniti uretmedi. Yavas disk, bellek yetersizligi veya sonsuz dongu olabilir.",
        remedy="Once bilgisayari yeniden baslatip tekrar deneyin. Devam ederse: Gorev Yoneticisi > Performans sekmesinden RAM kullanimini kontrol edin (>%90 ise diger programlari kapatin).",
        severity="fatal",
    ),
    "E009": ErrorEntry(
        code="E009",
        title="Tarayici acilmadi",
        cause="webbrowser modulu varsayilan tarayiciyi calistiramadi. Tarayici yuklu olmayabilir veya kayit defteri bozuk olabilir.",
        remedy="Tarayicinizi (Chrome/Edge/Firefox) manuel olarak acin ve adres cubuguna yazin: http://127.0.0.1:8080  -- Bot zaten arka planda calisiyor, sadece otomatik tarayici acma basarisiz oldu.",
        severity="warning",
    ),
    "E010": ErrorEntry(
        code="E010",
        title="Klasore yazma izni yok",
        cause="Uygulama runtime/ klasorune log/state yazamiyor. Bu klasor read-only veya antivirus tarafindan kilitli olabilir.",
        remedy="(1) TradingBotV1.exe dosyasini sag tiklayip 'Yonetici olarak calistir' secin. (2) Uygulama klasorunu C:\\Program Files yerine kullanicininizin Documents klasorune tasiyin.",
        severity="fatal",
    ),
    "E011": ErrorEntry(
        code="E011",
        title="Disk dolu",
        cause="Disk'te yeterli alan kalmadi. Trading bot dakikada bir log yazar; ~10MB serbest alan gerekir.",
        remedy="Disk Temizleme aracini calistirin. C:\\Users\\<sizinad>\\AppData\\Local\\Temp icindeki dosyalari silin. En az 500MB serbest alan brakin.",
        severity="fatal",
    ),
    "E012": ErrorEntry(
        code="E012",
        title="Binance API anahtari gecersiz",
        cause="Binance HTTP 401/403 dondu. Anahtar yanlis, suresi dolmus veya IP whitelisting aktif.",
        remedy=".env dosyasindaki BINANCE_API_KEY ve BINANCE_API_SECRET degerlerini Binance hesabinizdaki Yeni anahtar uretip kopyalayarak guncelleyin. IP kisitlamasi varsa devre disi birakin veya genel IP'nizi ekleyin.",
        severity="warning",
    ),
    "E013": ErrorEntry(
        code="E013",
        title="Binance baglantisi yok",
        cause="api.binance.com adresine ulasilamiyor. Internet kesik, DNS sorunu veya Turkiye'de Binance erisimi kisitli olabilir.",
        remedy="Tarayicidan https://api.binance.com/api/v3/ping adresini acin. JSON donmuyorsa: (1) VPN deneyin (yasal sinirlar dahilinde). (2) Modeminizi yeniden baslatin. (3) Antivirus/firewall'da Python.exe'ye internet izni verin.",
        severity="warning",
    ),
    "E014": ErrorEntry(
        code="E014",
        title="Beklenmedik ic hata",
        cause="Yakalanmamis bir Python istisnasi olustu. Bu bir hata raporu olarak iletilmelidir.",
        remedy="packaging/launcher.log dosyasinin SON 100 satirini kopyalayip gelistirici ile paylasin. Bot durdurulup yeniden baslatilabilir, ancak ayni hata tekrarlayabilir.",
        severity="fatal",
    ),
    "E015": ErrorEntry(
        code="E015",
        title="Birden fazla kopya tespit edildi",
        cause="TradingBotV1 zaten calisiyor (lockfile mevcut). Iki kopya ayni anda calisirsa veriler bozulur.",
        remedy="Mevcut kopyaya gecip onu kullanin. Eski bir kopya kilitli kalmissa: Gorev Yoneticisi'nde TradingBotV1.exe'yi bitirin ve runtime/.launcher.lock dosyasini silin.",
        severity="fatal",
    ),
    "E016": ErrorEntry(
        code="E016",
        title="Windows Firewall engelliyor",
        cause="Windows Guvenlik Duvari, dashboard'un kendine baglanmasini engelliyor (loopback 127.0.0.1).",
        remedy="Windows Defender Firewall > 'Uygulama veya ozellik izin ver' menusunden TradingBotV1.exe icin hem 'Ozel' hem 'Genel' sutununu isaretleyin.",
        severity="fatal",
    ),
    "E017": ErrorEntry(
        code="E017",
        title="Bellek yetersiz",
        cause="Sistemde 200MB'tan az kullanilabilir bellek var. Bot baslatilirsa Windows takilabilir.",
        remedy="Diger programlari kapatin (ozellikle tarayicilar, Discord, Slack). Sistemi yeniden baslatip tekrar deneyin. Cok eski (4GB RAM altinda) PC'lerde sik gorulen sorundur.",
        severity="warning",
    ),
    "E018": ErrorEntry(
        code="E018",
        title="Saat ayari bozuk",
        cause="Windows sistem saati Binance sunucu saatinden 1 dakikadan fazla farkli. Binance imza dogrulamasi reddediyor.",
        remedy="Windows Saat ayarlari > 'Saati otomatik ayarla' seceneklerini acin ve 'Hemen senkronize et' butonuna basin. NTP sunucu erisimi engelliyse zaman senkronizasyonu icin time.windows.com kullanin.",
        severity="warning",
    ),
    "E019": ErrorEntry(
        code="E019",
        title="State dosyasi bozuk",
        cause="runtime/state.json gecersiz JSON. Onceki cikiste yarim kalmis olabilir.",
        remedy="Otomatik kurtarma denenir. Basarisizsa: runtime/state.json dosyasini silin (varolan pozisyonlar paper modunda ise kaybolur, canli modda sadece izleme metadatasi kaybedilir).",
        severity="warning",
    ),
    "E020": ErrorEntry(
        code="E020",
        title="Calistirma izni reddedildi",
        cause="Windows SmartScreen veya antivirus, imzasiz .exe'yi engelliyor.",
        remedy="SmartScreen uyari ekraninda 'Ek bilgi' > 'Yine de calistir' tiklayin. Antivirus icin: TradingBotV1.exe yi 'Guvenli uygulamalar' (whitelist / exclusion) listesine ekleyin. Cogu false-positive'dir.",
        severity="fatal",
    ),
}


class LauncherError(Exception):
    """Hata kodlu launcher istisnasi.

    Kullanim:
        raise LauncherError("E003", detail="Port 8080 zaten dolu")
    """

    def __init__(self, code: str, detail: Optional[str] = None) -> None:
        self.code = code
        self.entry = _CATALOG.get(code)
        self.detail = detail
        if self.entry is None:
            super().__init__(f"[{code}] Bilinmeyen hata: {detail or ''}")
        else:
            super().__init__(f"[{code}] {self.entry.title}: {detail or self.entry.cause}")


class ErrorCatalog:
    """Statik erisim noktasi - launcher ve dashboard tarafindan ortak kullanilir."""

    @staticmethod
    def get(code: str) -> Optional[ErrorEntry]:
        return _CATALOG.get(code)

    @staticmethod
    def all() -> Dict[str, ErrorEntry]:
        return dict(_CATALOG)

    @staticmethod
    def render_markdown() -> str:
        """Tum hatalari Markdown tablosu olarak don (HATA_KODLARI.md icin)."""
        rows = ["# Trading Bot v1 - Hata Kodlari", "", "| Kod | Baslik | Onem | Cozum |", "|---|---|---|---|"]
        for code in sorted(_CATALOG.keys()):
            e = _CATALOG[code]
            rows.append(f"| **{e.code}** | {e.title} | {e.severity} | {e.remedy[:80]}... |")
        rows.append("")
        rows.append("## Detayli Aciklamalar")
        rows.append("")
        for code in sorted(_CATALOG.keys()):
            e = _CATALOG[code]
            rows.append(f"### {e.code} - {e.title}")
            rows.append(f"")
            rows.append(f"**Onem:** `{e.severity}`")
            rows.append(f"")
            rows.append(f"**Olasi Neden:** {e.cause}")
            rows.append(f"")
            rows.append(f"**Cozum:** {e.remedy}")
            rows.append(f"")
        return "\n".join(rows)


if __name__ == "__main__":
    # Komut satirindan calistirilirsa Markdown ciktisi bas
    print(ErrorCatalog.render_markdown())
