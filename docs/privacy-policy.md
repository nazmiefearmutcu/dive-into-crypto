# DVCRY (Dive into Crypto): Gizlilik Politikası / Privacy Policy

**Yürürlük tarihi / Effective date:** 2026-11-08
**Sürüm / Version:** 1.0 (Android uygulaması 0.4.0 ve sonrası / Android app 0.4.0 and later)
**Geliştirici / Developer:** <OWNER: developer name>
**İletişim / Contact:** <OWNER: support email>

Bu belge iki dildedir. Türkçe bölüm önce gelir; İngilizce bölüm aşağıdadır. İki metin arasında bir çelişki olursa Türkçe metin esas alınır.
This document is available in two languages. The Turkish section comes first, followed by the English section. If the two texts conflict, the Turkish text prevails.

---

# TÜRKÇE

## 1. Özet
- DVCRY uygulamasında hesap açmanız, giriş yapmanız veya API anahtarı girmeniz gerekmez.
- Uygulama geliştiricinin işlettiği bir sunucuya kişisel veri göndermez.
- Uygulamada reklam SDK'sı, analitik SDK'sı ve çökme raporlama SDK'sı yoktur.
- Uygulama emir iletmez, bir borsa hesabına bağlanmaz, para veya kripto varlık tutmaz.
- Uygulama yalnızca herkese açık piyasa verisi ister ve kayıtlarınızı cihazınızda tutar.

## 2. Uygulamanın bağlandığı sunucular
Uygulama aşağıdaki Binance sunucularına bağlanır. Bunların dışında hiçbir sunucuya bağlanmaz.

| Sunucu | Ne için kullanılır |
| --- | --- |
| `fapi.binance.com` | USDT-M vadeli işlem verileri: mumlar, açık pozisyon, fonlama oranı, fiyat ticker'ı, uzun/kısa oranları |
| `api.binance.com` | Spot piyasa verileri; yalnızca Ayarlar'dan spot veri kaynağını seçerseniz |
| `fstream.binance.com` | Canlı vadeli işlem fiyat ve mum akışı (WebSocket) |
| `stream.binance.com:9443` | Canlı spot fiyat akışı (WebSocket); yalnızca Ayarlar'dan spot kaynağı seçerseniz |

- Bu istekler herkese açık ve kimlik içermeyen GET ve WebSocket isteklerdir. İstek parametreleri yalnızca sembol, zaman aralığı ve kayıt sayısı gibi piyasa verisi parametreleridir.
- Her internet isteğinde olduğu gibi Binance, isteği yapan cihazın IP adresini görür. Binance'in kendi gizlilik politikası onun hizmetleri için geçerlidir: <OWNER: Binance privacy policy URL, verify before publishing>.
- Bazı bölgelerde Binance piyasa verisini sunmayabilir ve HTTP 451 gibi bir yanıt dönebilir. Bu durumda uygulama bir hata mesajı gösterir ve veri göstermez.
- Arka plan taraması yalnızca Ayarlar'da açtığınızda çalışır. Canlı fiyat akışları, uygulama canlı fiyat gösterirken veya fiyat uyarıları için gerektiğinde açılır.

## 3. Cihazınızda saklanan veriler
Aşağıdaki veriler yalnızca cihazınızda, uygulamaya özel alanda saklanır. Bu veriler geliştiriciye veya üçüncü taraflara gönderilmez.

| Veri | Amaç |
| --- | --- |
| Portföy kayıtları (sembol, giriş fiyatı, miktar, yön, oluşturma zamanı) | Kendi kayıtlarınızı uygulamada görmek ve analiz etmek |
| Uyarı kuralları ve tetiklenen uyarı geçmişi | Sizin belirlediğiniz eşikler için yerel bildirim göndermek |
| Analiz kanıt arşivi (karar kayıtları) | Uygulamadaki geçmiş analiz sonuçlarını göstermek |
| Ana ekran widget'ı durumu (son tarama sonuçları) | Widget'ta son taramanın özetini göstermek |
| Favoriler, tema, dil, tarama evreni ve veri kaynağı ayarları | Uygulamayı tercihlerinize göre çalıştırmak |
| Bildirim izni için tek bir "soruldu" işareti | Bildirim iznini tekrar tekrar sormamak |
| Paylaşım için oluşturulan kart görüntüleri (PNG) | Paylaş'a bastığınızda bir görüntü dosyası olarak paylaşmak; Android önbelleğinde tutulur |

Uygulama bu verileri uygulamanın kendi tercihleri ve önbelleği içinde tutar. Diğer uygulamalar bu alana erişemez.

## 4. Yedekleme ve cihaz aktarımı
- Uygulamanın ayar dosyası (`dive_settings`) ve bildirim izni dosyası (`dive_permissions`), Android Otomatik Yedekleme (Google hesabınızdaki bulut yedeği) ve cihazdan cihaza aktarım kapsamı dışındadır. Portföy kayıtlarınız, uyarılarınız ve kanıt arşiviniz de bu dosyada olduğu için yedeklere veya yeni cihaza aktarılmaz.
- Bu nedenle yeni bir cihaza geçtiğinizde dil, tema, favoriler ve ayarlar yeniden seçilmelidir.
- Paylaşım kart görüntüleri önbellekte tutulur. Android önbelleği varsayılan olarak yedeklemeye dahil etmez.
- Bu davranış uygulamanın yedekleme kuralları değiştirilirse güncellenir.

## 5. Bildirimler
- Uyarılar yalnızca cihazınızda oluşturulan yerel bildirimlerdir. Uygulama bir bildirim sunucusu kullanmaz.
- Android 13 ve sonrasında bildirim izni isteğe bağlıdır. İzni Android sistem ayarlarından dilediğiniz zaman kapatabilirsiniz.

## 6. Paylaşım
Bir kartı paylaştığınızda görüntü, Android paylaşım menüsünde seçtiğiniz uygulamaya gönderilir. Bu işlem sizin eyleminizdir; geliştirici bu görüntüyü almaz.

## 7. Güvenlik
- Uygulama yalnızca HTTPS ve WSS (şifreli) bağlantılar kullanır. Şifresiz (cleartext) trafik kapalıdır.
- Veriler uygulamaya özel alanda saklanır. Ek bir uygulama düzeyi şifreleme yapılmaz; cihazınızın kilidi ve Android'in cihaz şifrelemesi geçerlidir.

## 8. Saklama ve silme
- Veriler siz silene veya uygulamayı kaldırana kadar cihazınızda kalır.
- Tüm yerel verileri silmek için uygulamanın Ayarlar ekranındaki "Tüm yerel verileri temizle" işlemini kullanın. Bu işlem ayarları, favorileri, alarmları, portföyü ve önbelleği cihazdan siler.
- Alternatif olarak Android Ayarlar > Uygulamalar > DVCRY > Depolama > Verileri temizle yolunu izleyebilir veya uygulamayı kaldırabilirsiniz.
- Geliştiricinin sunucusunda sizinle ilgili bir veri bulunmadığı için geliştiriciden ayrıca silme talebinde bulunulacak bir veri yoktur.

## 9. Çocuklar
Uygulama çocuklara yönelik değildir. Uygulama 18 yaş ve üzeri kullanıcılara yöneliktir; 18 yaşından küçük kişilere yönelik değildir.

## 10. Yatırım tavsiyesi değildir
DVCRY eğitim ve araştırma amaçlı bir analiz aracıdır. Yatırım, alım-satım veya finansal danışmanlık değildir. Kişiye özel tavsiye vermez, emir iletmez ve hesabınıza erişmez. Kaldıraçlı kripto vadeli işlemler yüksek risk taşır ve yatırdığınız tutarın tamamını kaybetmenize yol açabilir.

## 11. Bağımsızlık
DVCRY bağımsızdır; Binance ile bağlantılı, onaylı veya sponsorlu değildir. Binance adı yalnızca veri kaynağını belirtmek için kullanılır.

## 12. Haklarınız (KVKK ve GDPR)
Geliştirici sunucusunda veri tutmadığı için, geliştiricinin elinde erişilecek, düzeltilecek veya silinecek bir kişisel veri yoktur. 6698 sayılı KVKK veya GDPR kapsamındaki haklarınızla ilgili sorularınız için <OWNER: support email> adresine yazabilirsiniz. Binance'e ilişkin haklar için Binance'in gizlilik politikasına bakın.

## 13. Değişiklikler
Bu politika değiştiğinde yeni yürürlük tarihi ve sürüm numarası bu sayfada yayımlanır. Uygulamanın veri davranışı değişirse bu politika da güncellenir.

## 14. İletişim
<OWNER: support email>

---

# ENGLISH

## 1. Summary
- You do not need to create an account, sign in, or enter an API key in DVCRY.
- The app does not send personal data to any server operated by the developer.
- The app contains no advertising SDK, no analytics SDK and no crash-reporting SDK.
- The app places no orders, does not connect to an exchange account, and does not hold money or crypto assets.
- The app requests only public market data, and it keeps your records on your device.

## 2. Servers the app connects to
The app connects to the Binance servers below and to no other server.

| Server | Used for |
| --- | --- |
| `fapi.binance.com` | USDT-M futures market data: candles, open interest, funding rate, price ticker, long/short ratios |
| `api.binance.com` | Spot market data; used only if you choose the spot data source in Settings |
| `fstream.binance.com` | Live futures price and candle stream (WebSocket) |
| `stream.binance.com:9443` | Live spot price stream (WebSocket); used only if you choose the spot data source in Settings |

- These are public, unauthenticated GET and WebSocket requests. The request parameters are market-data parameters only, such as symbol, interval and record limit.
- As with any internet request, Binance sees the IP address of the device that makes the request. Binance's own privacy policy governs its services: <OWNER: Binance privacy policy URL, verify before publishing>.
- In some regions Binance may not serve market data and may answer with HTTP 451 or a similar error. In that case the app shows an error message and no data.
- Background scanning runs only when you turn it on in Settings. Live price streams are opened while the app shows live prices or needs them for price alerts.

## 3. Data stored on your device
The data below is stored only on your device, in the app's private storage. It is not sent to the developer or to any third party.

| Data | Purpose |
| --- | --- |
| Portfolio entries (symbol, entry price, size, direction, creation time) | Showing and analysing your own entries in the app |
| Alert rules and fired-alert history | Sending local notifications for thresholds you set |
| Analysis evidence archive (verdict records) | Showing past analysis results in the app |
| Home-screen widget state (latest scan results) | Showing a summary of the latest scan in the widget |
| Favourites, theme, language, scan universe and data source settings | Running the app according to your preferences |
| A single "already asked" flag for the notification permission | Avoiding repeated permission prompts |
| Share-card images (PNG) created when you share a result | Passing an image file to the app you choose; kept in the Android cache |

The app keeps this data in its own preferences and cache. Other apps cannot access this storage.

## 4. Backup and device transfer
- The app's settings file (`dive_settings`) and notification-permission file (`dive_permissions`) are excluded from Android Auto Backup (cloud backup to your Google account) and from device-to-device transfer. Because your portfolio entries, alert rules and evidence archive are stored in the settings file, they are not uploaded to backups and are not copied to a new device.
- After moving to a new device, you must set your language, theme, favourites and settings again.
- Share-card images are kept in the cache. Android does not include the cache in backups by default.
- If the app's backup rules change, this section will be updated.

## 5. Notifications
- Alerts are local notifications created on your device. The app does not use a notification server.
- On Android 13 and later, the notification permission is optional. You can turn it off at any time in Android system settings.

## 6. Sharing
When you share a card, the image is handed to the app you pick in the Android share sheet. That is your action. The developer does not receive the image.

## 7. Security
- The app uses only HTTPS and WSS (encrypted) connections. Cleartext traffic is disabled.
- Data is stored in app-private storage. The app adds no encryption of its own beyond what Android provides, so your device lock screen and device encryption matter.

## 8. Retention and deletion
- Data stays on your device until you delete it or uninstall the app.
- To delete all local data, use "Clear all local data" in the app's Settings screen. It deletes settings, favourites, alarms, portfolio entries and cache from this device, stops background scans, and then restarts the app. Only a flag that records whether the notification permission was already requested is kept.
- You can also go to Android Settings > Apps > DVCRY > Storage > Clear storage, or uninstall the app.
- The developer holds no server-side data about you, so there is nothing to request from the developer for deletion.

## 9. Children
The app is not directed at children. It is intended for users aged 18 and over and is not intended for anyone under 18.

## 10. Not investment advice
DVCRY is an educational and research analytics tool. It is not investment, trading or financial advice. It gives no personalised advice, places no orders and has no access to any account. Leveraged crypto derivatives are high risk and you can lose your entire deposit.

## 11. Independence
DVCRY is independent and is not affiliated with, endorsed by or sponsored by Binance. The name Binance is used only to identify the data source.

## 12. Your rights (GDPR and KVKK)
The developer does not store personal data on any server, so there is no developer-held personal data to access, correct or erase. For questions about rights under the GDPR or Turkey's KVKK (Law No. 6698), write to <OWNER: support email>. For Binance, see Binance's privacy policy.

## 13. Changes
When this policy changes, the new effective date and version are published on this page. If the app's data behaviour changes, this policy is updated too.

## 14. Contact
<OWNER: support email>
