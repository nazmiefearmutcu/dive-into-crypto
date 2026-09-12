/* ============================================================================
   DIVE INTO CRYPTO — DESKTOP · TR/EN i18n catalog (LANE-7)
   Loaded BEFORE desktop-app.jsx in the bundle order (see build.mjs FILES).
   Exposes on the global object:
     - window.DIVE_I18N  { tr: {...}, en: {...} }   — raw catalogs
     - window.diveLang()                            — active code, "tr"|"en"
     - window.setDiveLang(code)                     — persist + notify listeners
     - window.L(key, ...args)                       — translate; {0},{1}… replaced
   The language persists under localStorage "dive_lang"; default is "tr" and the
   TR values are today's on-screen strings VERBATIM, so TR rendering is unchanged.
   ========================================================================== */
(function () {
  const G = (typeof window !== "undefined") ? window : globalThis;
  const KEY = "dive_lang";

  const TR = {
    /* ── nav rail / strip ── */
    nav_scan: "TARA", nav_panel: "PANEL", nav_flow: "OI·L/S", nav_sig: "SİNYAL",
    nav_evidence: "KANIT", nav_compare: "KIYAS", nav_map: "HARİTA", nav_portfolio: "PORTFÖY",
    nav_structure: "YAPI", nav_logs: "LOG", nav_settings: "AYAR",
    strip_live: "CANLI", strip_indicators: "İNDİKATÖR", strip_tf: "TF",
    strip_scan: "TARAMA", search_ph: "Sembol ara (örn. SOL)…",
    a11y_refresh: "Yenile", a11y_palette: "Komut paleti (Ctrl+K)", a11y_palette_open: "Komut paleti aç (Ctrl+K)",

    /* ── TARA (scan) view ── */
    scan_kicker: "MANUEL TARAMA", scan_h1: "Piyasa Süpürmesi",
    scan_meta1: "TÜM BINANCE USDT-M · 12 TF", scan_meta2: "KONSENSÜS · 60 İNDİKATÖR + 3 OVERLAY",
    scan_universe: "EVREN", scan_deep: "DERİN TARAMA (async)", scan_compare: "KIYASLA",
    scan_presets: "ÖN AYARLAR", scan_save: "KAYDET", scan_rename: "YENİDEN ADLANDIR",
    scan_export: "DIŞA AKTAR", scan_import: "İÇE AKTAR",
    scan_results: "SIRALANMIŞ SONUÇLAR", scan_survivors: "HAYATTA KALAN", scan_scanned: "TARANDI",
    scan_col_sym: "SEMBOL", scan_col_verdict: "KARAR", scan_col_ch: "FİYAT·24S",
    scan_col_conf: "GÜVEN", scan_col_score: "PUAN",
    scan_agree: "UYUM", scan_stability: "KARARLILIK", scan_12tf: "12 ZAMAN DİLİMİ",
    scan_running: "Tarama çalışıyor…",

    /* ── PANEL view ── */
    panel_kicker: "SEMBOL DERİNLİK", panel_meta1: "1H BİRİNCİL · 12 TF KONSENSÜS", panel_risk: "RİSK:",
    panel_consensus: "KONSENSÜS KARARI", panel_indtable: "İNDİKATÖR TABLOSU",
    panel_micro: "FUTURES MİKROYAPI", panel_regime: "REJİM & KONFLUENS",

    /* ── KANIT (evidence) view ── */
    ev_kicker: "MOTOR ÖZ-DERECELENDİRME", ev_h1: "Kanıt Panosu",
    ev_meta: "RAPOR-ONLY · AĞIRLIKLAR OTOMATİK DEĞİŞMEZ",
    ev_horizon: "UFUK", ev_window: "PENCERE",
    ev_grade_now: "ŞİMDİ NOTALA", ev_grading: "NOTALANIYOR",
    ev_win_7d: "7G", ev_win_30d: "30G", ev_win_all: "TÜMÜ", ev_neutral: "NÖTR",

    /* ── stats gate label (data.js · sgsHitLabel) ── */
    sgs_gate_small: "yetersiz örnek (n={0} < {1})",

    /* ── KIYAS (compare) view ── */
    cmp_kicker: "SENKRON KIYAS", cmp_h1: "Kıyas",
    cmp_meta1: "2–4 SEMBOL · ORTAK ZAMAN EKSENİ", cmp_meta2: "HİZALAMA SONDAN İNDEKSLE",
    cmp_add: "SEMBOL EKLE", cmp_open: "PANELİ AÇ", cmp_conf: "GÜVEN",

    /* ── HARİTA (map) view ── */
    map_kicker: "PİYASA MOZAİĞİ", map_h1: "Harita", map_meta: "RENK=KARAR · YOĞUNLUK=GÜVEN",
    map_mode_scan: "TARAMA", map_mode_universe: "EVREN", map_cluster: "KÜME KONTURU",

    /* ── PORTFÖY view ── */
    pf_kicker: "YEREL DEFTER", pf_h1: "Portföy",
    pf_caption: "giriş fiyatı cihazında saklanır · borsa bağlantısı yok",
    pf_add: "KONUM EKLE", pf_edit: "KONUM DÜZENLE", pf_positions: "KONUMLAR",
    pf_add_btn: "EKLE", pf_update_btn: "GÜNCELLE", pf_cancel: "VAZGEÇ",
    pf_sym: "SEMBOL", pf_dir: "YÖN", pf_entry: "GİRİŞ", pf_size: "MİKTAR", pf_now: "ŞİMDİ",
    pf_pnl: "K/Z %", pf_verdict: "KARAR", pf_action: "İŞLEM",
    pf_rows: "SATIR", pf_live: "CANLI FİYATLI",

    /* ── YAPI (structure) view ── */
    st_kicker: "PİYASA YAPISI", st_h1: "Yapı", st_meta: "BTC BETA · KORELASYON KÜMELERİ",
    st_verified: "İNDEKS DOĞRULANDI", st_unverified: "ETİKET DOĞRULANMADI",
    st_unclustered: "kümelenmemiş", st_symbols: "SEMBOL", st_beta_avg: "β-ort",

    /* ── deep-scan progress panel ── */
    sp_title: "DERİN TARAMA · İZLEME", sp_retry: "TEKRAR DENE",
    sp_reload: "SONUCU YENİDEN YÜKLE", sp_back: "SENKRON TARAMAYA DÖN", sp_cancel: "VAZGEÇ",

    /* ── settings (AYAR) view ── */
    set_kicker: "AYARLAR", set_h1: "Görünüm", set_theme: "TEMA", set_engine: "MOTOR",
    set_lang: "DİL",

    /* ── command palette ── */
    pal_scan: "TARA · tarayıcı", pal_panel: "PANEL · sembol derinliği", pal_flow: "OI·L/S · akış",
    pal_sig: "SİNYAL · kısa liste", pal_evidence: "KANIT · öz-derecelendirme",
    pal_compare: "KIYAS · yan yana", pal_map: "HARİTA · mozaik", pal_portfolio: "PORTFÖY · yerel defter",
    pal_structure: "YAPI · kümeler", pal_logs: "LOG · bağlantı", pal_settings: "AYAR · görünüm",
    pal_kind_lang: "DİL", pal_lang_tr: "dil: TR", pal_lang_en: "dil: EN",
  };

  const EN = {
    nav_scan: "SCAN", nav_panel: "PANEL", nav_flow: "OI·L/S", nav_sig: "SIGNAL",
    nav_evidence: "EVIDENCE", nav_compare: "COMPARE", nav_map: "MAP", nav_portfolio: "PORTFOLIO",
    nav_structure: "STRUCTURE", nav_logs: "LOG", nav_settings: "SETTINGS",
    strip_live: "LIVE", strip_indicators: "INDICATORS", strip_tf: "TF",
    strip_scan: "SCANNING", search_ph: "Search symbol (e.g. SOL)…",
    a11y_refresh: "Refresh", a11y_palette: "Command palette (Ctrl+K)", a11y_palette_open: "Open command palette (Ctrl+K)",

    scan_kicker: "MANUAL SCAN", scan_h1: "Market Sweep",
    scan_meta1: "ALL BINANCE USDT-M · 12 TF", scan_meta2: "CONSENSUS · 60 INDICATORS + 3 OVERLAYS",
    scan_universe: "UNIVERSE", scan_deep: "DEEP SCAN (async)", scan_compare: "COMPARE",
    scan_presets: "PRESETS", scan_save: "SAVE", scan_rename: "RENAME",
    scan_export: "EXPORT", scan_import: "IMPORT",
    scan_results: "RANKED RESULTS", scan_survivors: "SURVIVORS", scan_scanned: "SCANNED",
    scan_col_sym: "SYMBOL", scan_col_verdict: "VERDICT", scan_col_ch: "PRICE·24H",
    scan_col_conf: "CONF", scan_col_score: "SCORE",
    scan_agree: "AGREE", scan_stability: "STABILITY", scan_12tf: "12 TIMEFRAMES",
    scan_running: "Scan running…",

    panel_kicker: "SYMBOL DEPTH", panel_meta1: "1H PRIMARY · 12 TF CONSENSUS", panel_risk: "RISK:",
    panel_consensus: "CONSENSUS VERDICT", panel_indtable: "INDICATOR TABLE",
    panel_micro: "FUTURES MICROSTRUCTURE", panel_regime: "REGIME & CONFLUENCE",

    ev_kicker: "ENGINE SELF-GRADING", ev_h1: "Evidence Board",
    ev_meta: "REPORT-ONLY · WEIGHTS NEVER AUTO-CHANGE",
    ev_horizon: "HORIZON", ev_window: "WINDOW",
    ev_grade_now: "GRADE NOW", ev_grading: "GRADING",
    ev_win_7d: "7D", ev_win_30d: "30D", ev_win_all: "ALL", ev_neutral: "NEUTRAL",

    sgs_gate_small: "insufficient sample (n={0} < {1})",

    cmp_kicker: "SYNC COMPARE", cmp_h1: "Compare",
    cmp_meta1: "2–4 SYMBOLS · SHARED TIME AXIS", cmp_meta2: "ALIGNED FROM THE END",
    cmp_add: "ADD SYMBOL", cmp_open: "OPEN PANEL", cmp_conf: "CONF",

    map_kicker: "MARKET MOSAIC", map_h1: "Map", map_meta: "COLOR=VERDICT · INTENSITY=CONF",
    map_mode_scan: "SCAN", map_mode_universe: "UNIVERSE", map_cluster: "CLUSTER OUTLINE",

    pf_kicker: "LOCAL LEDGER", pf_h1: "Portfolio",
    pf_caption: "entries live on this device · no exchange connection",
    pf_add: "ADD POSITION", pf_edit: "EDIT POSITION", pf_positions: "POSITIONS",
    pf_add_btn: "ADD", pf_update_btn: "UPDATE", pf_cancel: "CANCEL",
    pf_sym: "SYMBOL", pf_entry: "ENTRY", pf_size: "SIZE", pf_now: "NOW",
    pf_pnl: "P&L %", pf_verdict: "VERDICT", pf_action: "ACTION",
    pf_rows: "ROWS", pf_live: "LIVE-PRICED",

    st_kicker: "MARKET STRUCTURE", st_h1: "Structure", st_meta: "BTC BETA · CORRELATION CLUSTERS",
    st_verified: "INDEX VERIFIED", st_unverified: "LABEL UNVERIFIED",
    st_unclustered: "unclustered", st_symbols: "SYMBOLS", st_beta_avg: "β-avg",

    sp_title: "DEEP SCAN · MONITOR", sp_retry: "RETRY",
    sp_reload: "RELOAD RESULT", sp_back: "BACK TO SYNC SCAN", sp_cancel: "CANCEL",

    set_kicker: "SETTINGS", set_h1: "Appearance", set_theme: "THEME", set_engine: "ENGINE",
    set_lang: "LANGUAGE",

    pal_scan: "SCAN · scanner", pal_panel: "PANEL · symbol depth", pal_flow: "OI·L/S · flow",
    pal_sig: "SIGNAL · shortlist", pal_evidence: "EVIDENCE · self-grading",
    pal_compare: "COMPARE · side by side", pal_map: "MAP · mosaic", pal_portfolio: "PORTFOLIO · local ledger",
    pal_structure: "STRUCTURE · clusters", pal_logs: "LOG · connection", pal_settings: "SETTINGS · view",
    pal_kind_lang: "LANG", pal_lang_tr: "language: TR", pal_lang_en: "language: EN",
  };

  G.DIVE_I18N = { tr: TR, en: EN };

  G.diveLang = function () {
    try {
      return (G.localStorage && G.localStorage.getItem(KEY)) === "en" ? "en" : "tr";
    } catch (e) { return "tr"; }
  };

  G.setDiveLang = function (code) {
    try { G.localStorage.setItem(KEY, code === "en" ? "en" : "tr"); } catch (e) { /* storage unavailable → session-only */ }
    if (typeof G.__diveOnLang === "function") G.__diveOnLang();
  };

  /** Translate `key` in the active language; {0}…{n} placeholders via extra args.
      Missing keys fall back to TR, then to the key itself (never undefined). */
  G.L = function (key) {
    const lang = G.diveLang();
    const table = G.DIVE_I18N[lang] || G.DIVE_I18N.tr;
    let s = table[key];
    if (s == null) s = G.DIVE_I18N.tr[key];
    if (s == null) return key;
    for (let i = 1; i < arguments.length; i++) {
      s = s.split("{" + (i - 1) + "}").join(String(arguments[i]));
    }
    return s;
  };
})();
