# Google Play mağaza girişi: Türkçe (tr-TR)

Bu dosya Play Console'a kopyalanacak Türkçe metinleri içerir. Sınırlar: uygulama adı 30 karakter, kısa açıklama 80 karakter, tam açıklama 4000 karakter. Sayımlar aşağıdaki "Karakter sayımı" bölümündedir. `<OWNER: ...>` ile işaretli alanlar yayından önce doldurulmalıdır.

Adlandırma şeması `docs/google-play-release.md` dosyasındadır (proje: Dive into Crypto, uygulama: DVCRY);
İngilizce başlık ve kısa açıklamanın kaynağı `fastlane/metadata/android/en-US` klasörüdür. Buradaki Türkçe
başlık ve kısa açıklama o değerlerin çevirisidir.

## Uygulama adı

```text
DVCRY: Kripto Piyasa Monitörü
```

## Kısa açıklama

```text
DVCRY Derin Kripto Piyasa İzleme Aracı
```

## Tam açıklama

```text
DVCRY, Binance USDT-M vadeli işlem piyasasının herkese açık piyasa verilerini (fiyat, hacim, fonlama oranı, açık pozisyon ve benzeri) 60 teknik göstergeyle analiz eder ve her sembol için sonuçları tek bir analitik özet olarak gösterir.

Neler yapar
• Çok sayıda USDT-M vadeli işlem paritesini tek listede tarar ve analitik özetleri sıralar.
• Seçtiğiniz sembol için gösterge, zaman dilimi ve ayrıntıları panelde açıklar.
• Fiyat, güven skoru ve açık pozisyon eşikleri için uyarı kuralları oluşturur; uyarılar cihazınızda yerel bildirim olarak gelir.
• İsteğe bağlı arka plan taraması yapar; ana ekran widget'ında son taramanın en üst sonuçlarını gösterir.
• Portföy kayıtlarınızı (sembol, giriş fiyatı, miktar, yön) yalnızca cihazınızda tutar.
• Sonuç kartlarını PNG görüntü olarak paylaşabilirsiniz.
• Türkçe ve İngilizce arayüz.

Önemli uyarılar
• Bu uygulama yatırım danışmanlığı, portföy yönetimi veya alım-satım aracı değildir. Emir iletmez, hesabınıza erişmez, para yatırma veya çekme işlemi yapmaz ve kripto varlık saklamaz.
• Gösterilen analizler teknik göstergelerin matematiksel bir özetidir; gelecekteki fiyatlar hakkında bir tahmin değildir. Geçmiş sonuçlar gelecek için bir güvence değildir.
• Kaldıraçlı vadeli işlemler yüksek risk taşır ve yatırdığınız tutarın tamamını kaybetmenize yol açabilir.
• Veriler üçüncü taraf borsanın herkese açık API'lerinden alınır; gecikme, eksik veri veya hata olabilir.

Gizlilik
Hesap gerekmez. Uygulama yalnızca herkese açık piyasa verisi ister ve geliştiriciye kişisel veri göndermez. Gizlilik politikası: <OWNER: gizlilik politikası URL'si>

DVCRY bağımsızdır; Binance ile bağlantılı, onaylı veya sponsorlu değildir. Binance adı yalnızca veri kaynağını belirtmek için kullanılır.

İletişim: <OWNER: destek e-postası>
```

## Karakter sayımı

Sayım yöntemi: Python `len()` ile, Unicode kod noktası başına bir karakter (boşluk ve satır sonları dahil). Sayılan metin yukarıdaki ```text blokları içindeki metindir.

| Alan | Sınır | Sayım | Durum |
| --- | --- | --- | --- |
| Uygulama adı | 30 | 29 | Uygun |
| Kısa açıklama | 80 | 38 | Uygun |
| Tam açıklama | 4000 | 1768 | Uygun (yer tutucular dolunca birkaç karakter artar) |

## Notlar (yayınlanmaz)

- Ticari marka: "Binance" yalnızca veri kaynağı olarak, nominatif kullanılır. Binance logosu, simgesi veya ekran görüntüsü kullanılmaz. Kullanmadan önce Binance marka koşullarını kontrol edin.
- "Deribit" bu sürümde ağ üzerinden kullanılmadığı için metinde yer almaz.
- Yasaklı kelimeler: "sinyal", "kâr", "garanti", "en güvenilir", isabet oranı yüzdeleri. Bunlar uygulama içi metinlerle ve EN metniyle tutarlı tutulmalıdır.
- Aynı "Önemli uyarılar" bölümü uygulamanın Hakkında ekranı ve gizlilik politikasıyla tutarlı olmalıdır.
