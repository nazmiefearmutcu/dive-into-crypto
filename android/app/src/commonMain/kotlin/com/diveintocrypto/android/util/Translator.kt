package com.diveintocrypto.android.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalLanguage = compositionLocalOf { "en" }

object Translator {
    private val trMap = mapOf(
        "Panel" to "Panel",
        "Scanner" to "Tarayıcı",
        "OI · L/S" to "Açık Pozisyon",
        "Signals" to "Sinyaller",
        "Leaders" to "Liderler",
        "Network Log" to "Ağ Günlüğü",
        "Appearance" to "Görünüm",
        "Settings" to "Ayarlar",
        "MORE" to "DAHA FAZLA",
        "More" to "Daha Fazla",
        
        "LANGUAGE / DİL" to "DİL",
        "English" to "İngilizce",
        "Türkçe" to "Türkçe",
        "THEME" to "TEMA",
        "ABOUT" to "HAKKINDA",
        "App" to "Uygulama",
        "Version" to "Sürüm",
        "Build" to "Derleme",
        "Mode" to "Mod",
        "Dark" to "Karanlık",
        "Dive Into Crypto brand — fixed" to "Dive Into Crypto markası — sabit",
        
        "FAVORITE COINS" to "FAVORİ KOİNLER",
        "Manage your quick-access list on the Panel tab." to "Hızlı erişim listenizi Panel sekmesinden yönetin.",
        "No favorite coins added yet." to "Henüz favori koin eklenmedi.",
        "Add Favorite" to "Favori Ekle",
        "+ Add" to "+ Ekle",
        "Search symbol..." to "Sembol ara...",
        
        "SCANNER SETTINGS" to "TARAYICI AYARLARI",
        "Survivors (Phase 2 max output)" to "Kalanlar (Aşama 2 maks çıktı)",
        "Parallel Request Batch Size" to "Paralel İstek Boyutu",
        
        "ANALYSIS ALGORITHM SETTINGS" to "ANALİZ ALGORİTMASI AYARLARI",
        "Dynamic Regime Matrix (ADX-aware)" to "Dinamik Rejim Matrisi (ADX Uyumlu)",
        "Minimum Confidence to Score" to "Puanlamak İçin Minimum Güven",
        "Minimum Confidence to Trade" to "İşlem İçin Minimum Güven",

        "INDICATOR CONSENSUS WEIGHTS" to "İNDİKATÖR KONSENSÜS AĞIRLIKLARI",
        
        "QUANTITATIVE DATA & CHART SETTINGS" to "KANTİTATİF VERİ & GRAFİK AYARLARI",
        "WebSocket Live Feed" to "WebSocket Canlı Veri",
        "Chart Candlestick Count" to "Grafik Mum Sayısı",
        
        "QUANT BIAS FORMULA WEIGHTS" to "KANTİTATİF EĞİLİM FORMÜL AĞIRLIKLARI",
        
        "CONFIDENCE" to "GÜVEN",
        "PRICE" to "FİYAT",
        "LONG" to "UZUN",
        "SHORT" to "KISA",
        "NEUTRAL" to "NÖTR",
        "STRONG_BUY" to "GÜÇLÜ AL",
        "BUY" to "AL",
        "SELL" to "SAT",
        "STRONG_SELL" to "GÜÇLÜ SAT",
        "No favorites yet. Add some!" to "Henüz favori yok. Eklemeye başla!",
        "Select a coin to start." to "Başlamak için bir koin seç."
    )

    fun tr(text: String, lang: String): String {
        if (lang != "tr") return text
        return trMap[text] ?: text
    }
}

@Composable
fun String.tr(): String {
    return Translator.tr(this, LocalLanguage.current)
}
