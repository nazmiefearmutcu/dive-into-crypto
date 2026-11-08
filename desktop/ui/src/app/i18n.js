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

    /* ── divergence reason (data.js · _divReason; {0} = signed WF score) ── */
    div_adverse: "İndikatör yönüne karşı balina akışı (WF {0}).",
    div_confirm: "Balina akışı indikatör yönünü teyit ediyor (WF {0}).",
    div_none: "Belirgin balina uyumsuzluğu yok.",

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
    set_engine_desc: "60 indikatör · 12 zaman dilimi · konsensüs + balina-uyumsuzluk filtresi + 3 overlay (futures-mikroyapı, rejim-adaptif ağırlık, çoklu-TF konfluens). Yalnızca public Binance verisi.",

    /* ── LOG (connection) view ── */
    log_kicker: "AĞ GÜNLÜĞÜ", log_h1: "Bağlantı", log_recent: "SON İSTEKLER", log_empty: "Günlük boş.",

    /* ── TARA caption / empty states / progress errors ── */
    scan_cap_busy: "arka plan taraması çalışıyor — senkron otomatik tarama duraklatıldı",
    scan_cap_idle: "varsayılan: senkron otomatik tarama · 15 sembol / 24 evren",
    scan_cap_held: "arka plan taraması bitti · senkron otomatik tarama duraklatıldı — SENKRON TARAMAYA DÖN ile sürdür",
    scan_none: "Hayatta kalan yok · bu taramada sinyal geçmedi",
    sp_detach: "İZLEMEYİ BIRAK", sp_gone: "tarama sunucuda bulunamadı · arka uç yeniden başlamış olabilir",
    sp_lost: "ilerleme alınamıyor",

    /* ── panel card titles / annotations / map+structure freshness ── */
    card_funding: "FONLAMA", card_basis: "BASİS", card_cascade: "KASKAD",
    ann_save_fail: "çizgi kaydedilemedi (depolama yazılamadı)",
    map_age: "veri {0} dk önce alındı · son başarılı çekim", map_age_none: "veri yaşı — · henüz çekilmedi",
    st_snapshot: "oturumda bir kez alınır · canlı değildir",

    /* ── command palette ── */
    pal_scan: "TARA · tarayıcı", pal_panel: "PANEL · sembol derinliği", pal_flow: "OI·L/S · akış",
    pal_sig: "SİNYAL · kısa liste", pal_evidence: "KANIT · öz-derecelendirme",
    pal_compare: "KIYAS · yan yana", pal_map: "HARİTA · mozaik", pal_portfolio: "PORTFÖY · yerel defter",
    pal_structure: "YAPI · kümeler", pal_logs: "LOG · bağlantı", pal_settings: "AYAR · görünüm",
    pal_kind_lang: "DİL", pal_lang_tr: "dil: TR", pal_lang_en: "dil: EN",

    /* ── shared card / evidence labels ── */
    unav: "kullanılamıyor", no_block: "sembol verisinde {0} yok", no_cvd: "sembol verisinde cvd alanı yok",
    act_long: "LONG eğilim", act_short: "SHORT eğilim", act_none: "Belirgin eğilim yok",
    err_region: "Bu veri kaynağı bulunduğunuz bölgeden erişime kapalı (bölgesel kısıtlama). Uygulama veri uydurmaz.",
    err_forbidden: "Veri kaynağı isteği reddetti — bu bölgesel bir kısıtlamadan kaynaklanabilir.",
    err_generic: "Veri kaynağı isteğe yanıt vermedi",
    demo_no_symbol: "Bu sembol demo verisinde yok · demo modu yalnızca sabit örnek sembolleri gösterir",
    sec_ago: "{0} SN ÖNCE", min_ago: "{0} DK ÖNCE", ev_loading: "Kanıt arşivi okunuyor…",
    ev_karar: "{0} karar", ev_hit: "İSABET", ev_avg_fwd: "ORT. İLERİ GETİRİ", ev_med_fwd: "MEDYAN İLERİ",
    ev_gated: "örneklem küçük — oran gürültülü, kapıya takıldı",
    cmp_invalid: "geçersiz yanıt",

    /* ── KARŞIT KANIT (opposing evidence) ── */
    op_title: "KARŞIT KANIT", op_scored: "|SKOR×AĞIRLIK| · İLK 3", op_unscored: "TABLO SIRASI · İLK 3 · SKOR VERİSİ YOK",
    op_none_k: "KARŞIT İNDİKATÖR", op_none_v: "— yönü tersine bakan indikatör yok",
    op_hist_t: "bu indikatörün geçmişte karşit durduğu kararların isabeti", op_no_arch: "arşiv ilişkisi yok",
    op_rel_dash: "ilişki —",
    op_caption: "karşıt isabet = indikatörün karara ters döndüğü geçmiş kararların isabet oranı · ilişki, nedensellik değildir · report-only",

    /* ── FONLAMA / BASİS / KASKAD / EMİR DEFTERİ / L/S / SPOT–PERP / PLANLAMA cards ── */
    fund_rt: "VEKİL DEĞİL · PREMIUM INDEX", fund_next: "SONRAKİ FONLAMA", fund_pred: "TAHMİNİ (taşıyor)",
    fund_last: "SON SETTLE", fund_apr: "YILLIK (APR)", fund_regime: "REJİM:",
    fr_long: "LONG KALABALIK", fr_ext_long: "AŞIRI LONG KALABALIK", fr_short: "SHORT KALABALIK",
    fr_ext_short: "AŞIRI SHORT KALABALIK", fr_balanced: "DENGELİ",
    basis_rt: "PERP · VADE EĞRİSİ", basis_bps: "BPS (PERP−ENDEKS)", basis_z: "Z-SKOR", basis_ann: "YILLIK FONLAMA",
    basis_aria: "basis eğrisi: perp, çeyrek, next çeyrek", basis_partial: "PARSİNEL",
    basis_partial_t: "vade bacakları eksik — perp-only çiftlerde normal",
    casc_none: "OI serisi yok — blok üretilmedi", casc_below: "eşik altı · skor {0}", casc_gauge: "KASKAD SKORU",
    casc_proxy_t: "bileşenler serilerden türetildi — gerçek likidasyon akışı değil", casc_proxy: "VEKİL/PROXY",
    book_title: "EMİR DEFTERİ", book_k: "DEFTER", book_notional: "±%1 DEFTER DEĞERİ",
    book_cap: "imbalance = (alıcı−satıcı)/(toplam) · bant: mid'den ±uzaklık · MID {0}",
    ls_title: "L/S TERM YAPISI", ls_no_periods: "periyot verisi yok", ls_glob: "PERAKENDE HESAP",
    ls_acc: "ÜST HESAP", ls_pos: "ÜST POZİSYON", ls_taker: "TAKER AL/SAT", ls_cells: "{0} HÜCRE YOK",
    ls_grid: "4 AİLE × {0} PERİYOT",
    ls_cap: "≥1 long baskın · ≤1 short baskın · kalabalık ucu tersine okunur (fade)",
    spp_lead_spot: "SPOT ÖNDE", spp_lead_perp: "PERP ÖNDE", spp_lead_mixed: "KARIŞIK", spp_lead_none: "LİDER —",
    spp_perp_only: "perp-only çift · spot piyasası yok", spp_window: "48S PENCERE", spp_premium: "PREMİUM",
    spp_ret: "GETİRİ FARKI 48S",
    spp_cap: "lag-1 çapraz-korelasyon asimetrisinden; ≥10 çift getiri gerekir",
    plan_title: "PLANLAMA", plan_title_geo: "PLANLAMA · ATR GEOMETRİSİ", plan_k: "PLAN", plan_sl: "ATR MESAFESİ",
    plan_rt: "1.5×ATR% · İLLÜSTRATİF", plan_tp1r: "ATR 1× SEVİYE", plan_tp2r: "ATR 2× SEVİYE", plan_tp3r: "ATR 3× SEVİYE",
    plan_env1: "ZARF 1S", plan_env4: "ZARF 4S", plan_env24: "ZARF 24S",
    plan_note: "yalnızca bilgi · ATR tabanlı örnek seviyeler · emir kurulmaz",

    /* ── CVD card ── */
    cvd_title: "CVD · KÜMÜLATİF HACİM DELTASI", cvd_trades: "{0} İŞLEM", cvd_window: "{0} DK PENCERE",
    cvd_window_none: "PENCERE —", cvd_buy: "AL {0} · %{1}", cvd_sell: "%{0} SAT {1}",
    cvd_aria: "alım %{0} · satım %{1}", cvd_bars_t: "örnek başına delta (delta_series farkı); ≤60 örnek",
    cvd_series: "DELTA SERİSİ",
    cvd_cap: "CVD = Σ(alıcı-başlatan − satıcı-başlatan hacim) · son ≤1000 public aggTrade · {0} dk pencere · {1} örnek",

    /* ── KAYITLI İDDİALAR (claims) ── */
    claim_title: "KAYITLI İDDİALAR", claim_rt: "{0} KAYIT · TESCİL SONRASI NOTALARLA DEĞERLENDİRİLİR",
    claim_pending: "BEKLİYOR", claim_confirmed: "DOĞRULANDI", claim_refuted: "YALANLANDI", claim_k: "İDDİA",
    claim_none: "— kayıtlı iddia yok · aşağıdaki formla ön-kayıt bırak",
    claim_h_status: "DURUM", claim_h_claim: "İDDİA", claim_h_value: "DEĞER", claim_h_thr: "EŞİK", claim_h_reg: "TESCİL",
    claim_nofilter: "filtre yok", claim_all: "tüm kararlar",
    claim_empty: "iddia metni boş — kayıt yapılmadı",
    claim_minn: "min_n pozitif tamsayı olmalı — kayıt yapılmadı",
    claim_thr_num: "eşik sayı olmalı — kayıt yapılmadı",
    claim_thr_range: "isabet eşiği 0–1 arasında olmalı (örn. 0.55, yüzde değil) — kayıt yapılmadı",
    claim_saved: "İDDİA KAYDEDİLDİ · {0} — değerlendirme yalnız tescil sonrası notalarla",
    claim_rejected: "KAYIT REDDEDİLDİ · {0}", claim_form: "İDDİA KAYDET", claim_busy: "KAYDEDİLİYOR…",
    claim_ph: "iddia metni (örn. <metrik> <ufuk> için <eşik>)…",
    claim_a_metric: "metrik", claim_a_filter: "filtre", claim_a_horizon: "ufuk", claim_a_minn: "min n",
    claim_a_op: "eşik operatörü", claim_a_thr: "eşik değeri", claim_minn_t: "minimum örnek — altında PENDING",
    claim_cap: "iddialar ön-kayıt disiplinidir: değerlendirme yalnızca tescil zamanından SONRAKI notalanmış kararlar üzerinden yapılır · kayıt dosyası değiştirilemez (immutable)",

    /* ── ZAMAN MAKİNESİ (replay scrub) & REPLAY grid ── */
    rs_chip: "ZAMAN MAKİNESİ", rs_chip_t: "karar arşivi üzerinde zaman yolculuğu",
    rs_err: "ARŞİV OKUNAMADI · {0}", rs_title: "ZAMAN MAKİNESİ · \"O ZAMAN NE SÖYLEDİ\"", rs_k: "ARŞİV",
    rs_empty: "bu aralıkta kayıt yok · {0} için arşivlenmiş karar bulunamadı",
    rs_rt: "{0} KAYIT · EN YENİ {1} · GEÇMİŞ GÖRÜNÜM", rs_aria: "arşiv kaydı seç", rs_conf: "güven %{0}",
    rs_score: "puan {0}", rs_tfagree: "TF uyum {0}", rs_div: "diverjans {0}",
    rs_static: "ana grafik değişmez (kart yalnızca T anını gösterir)",
    rs_gap_cap: "karar arşivi kayıtlar arası yorum yapmaz — boşluklar boş kalır · ghost kart rapor amaçlıdır",
    rp_title: "REPLAY · TERSİNE-EŞİK IZGARASI",
    rp_rt: "{0} TEKRARLANABİLİR · {1} KAT · BOOTSTRAP n={2}/seed {3}", rp_k: "IZGARA", rp_busy: "hesaplanıyor…",
    rp_empty: "— henüz çalıştırılmadı · arşiv×notalar üzerinde eşik karşı-factual'ı",
    rp_running: "ÇALIŞTIRILIYOR…", rp_run: "ÇALIŞTIR", rp_fail: "REPLAY BAŞARISIZ · {0}",
    rp_skipped: "atlanan: {0} ağırlık-hash uyuşmaz · {1} sinyalsiz · gönderilen hücre: buy {2} / strong {3} / conflict {4}",
    rp_h_n: "N YÖNLÜ", rp_h_hit: "İSABET", rp_h_fold: "KAT YAYILIMI",
    rp_cap: "REPORT-ONLY · hiçbir eşik motoru değiştirmez · hücre rengi shipped'e göre deltadan · boot win% = bootstrap'ta shipped'i yenme payı · kat yayılımı = {0} kat isabet max−min",
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
    pf_sym: "SYMBOL", pf_dir: "DIRECTION", pf_entry: "ENTRY", pf_size: "SIZE", pf_now: "NOW",
    pf_pnl: "P&L %", pf_verdict: "VERDICT", pf_action: "ACTION",
    pf_rows: "ROWS", pf_live: "LIVE-PRICED",

    div_adverse: "Whale flow against the indicator direction (WF {0}).",
    div_confirm: "Whale flow confirms the indicator direction (WF {0}).",
    div_none: "No notable whale divergence.",

    st_kicker: "MARKET STRUCTURE", st_h1: "Structure", st_meta: "BTC BETA · CORRELATION CLUSTERS",
    st_verified: "INDEX VERIFIED", st_unverified: "LABEL UNVERIFIED",
    st_unclustered: "unclustered", st_symbols: "SYMBOLS", st_beta_avg: "β-avg",

    sp_title: "DEEP SCAN · MONITOR", sp_retry: "RETRY",
    sp_reload: "RELOAD RESULT", sp_back: "BACK TO SYNC SCAN", sp_cancel: "CANCEL",

    set_kicker: "SETTINGS", set_h1: "Appearance", set_theme: "THEME", set_engine: "ENGINE",
    set_lang: "LANGUAGE",
    set_engine_desc: "60 indicators · 12 timeframes · consensus + whale-divergence filter + 3 overlays (futures microstructure, regime-adaptive weights, multi-TF confluence). Public Binance data only.",

    log_kicker: "NETWORK LOG", log_h1: "Connection", log_recent: "RECENT REQUESTS", log_empty: "Log is empty.",

    scan_cap_busy: "background scan running — automatic sync scan paused",
    scan_cap_idle: "default: automatic sync scan · 15 symbols / 24 universe",
    scan_cap_held: "background scan finished · automatic sync scan paused — resume with BACK TO SYNC SCAN",
    scan_none: "No survivors · no signal passed this scan",
    sp_detach: "STOP WATCHING", sp_gone: "scan not found on the server · the backend may have restarted",
    sp_lost: "progress unavailable",

    card_funding: "FUNDING", card_basis: "BASIS", card_cascade: "CASCADE",
    ann_save_fail: "line not saved (storage unavailable)",
    map_age: "data pulled {0} min ago · last successful fetch", map_age_none: "data age — · not fetched yet",
    st_snapshot: "fetched once per session · not live",

    pal_scan: "SCAN · scanner", pal_panel: "PANEL · symbol depth", pal_flow: "OI·L/S · flow",
    pal_sig: "SIGNAL · shortlist", pal_evidence: "EVIDENCE · self-grading",
    pal_compare: "COMPARE · side by side", pal_map: "MAP · mosaic", pal_portfolio: "PORTFOLIO · local ledger",
    pal_structure: "STRUCTURE · clusters", pal_logs: "LOG · connection", pal_settings: "SETTINGS · view",
    pal_kind_lang: "LANG", pal_lang_tr: "language: TR", pal_lang_en: "language: EN",

    unav: "unavailable", no_block: "no {0} in symbol data", no_cvd: "no cvd field in symbol data",
    act_long: "LONG bias", act_short: "SHORT bias", act_none: "No clear bias",
    err_region: "This data source is not accessible from your region (regional restriction). The app does not invent data.",
    err_forbidden: "The data source refused the request — this can be a regional restriction.",
    err_generic: "The data source did not answer the request",
    demo_no_symbol: "This symbol is not in the demo data · demo mode shows only its fixed sample symbols",
    sec_ago: "{0} S AGO", min_ago: "{0} MIN AGO", ev_loading: "Reading evidence archive…",
    ev_karar: "{0} verdicts", ev_hit: "HIT RATE", ev_avg_fwd: "AVG FORWARD RETURN", ev_med_fwd: "MEDIAN FORWARD",
    ev_gated: "small sample — rate is noisy, gated",
    cmp_invalid: "invalid response",

    op_title: "OPPOSING EVIDENCE", op_scored: "|SCORE×WEIGHT| · TOP 3", op_unscored: "TABLE ORDER · TOP 3 · NO SCORE DATA",
    op_none_k: "OPPOSING INDICATOR", op_none_v: "— no indicator points the other way",
    op_hist_t: "hit rate of past verdicts this indicator stood against", op_no_arch: "no archive relation",
    op_rel_dash: "relation —",
    op_caption: "opposing hit rate = hit rate of past verdicts the indicator flipped against · relation is not causation · report-only",

    fund_rt: "NOT A PROXY · PREMIUM INDEX", fund_next: "NEXT FUNDING", fund_pred: "PREDICTED (carried)",
    fund_last: "LAST SETTLED", fund_apr: "ANNUAL (APR)", fund_regime: "REGIME:",
    fr_long: "LONG CROWDING", fr_ext_long: "EXTREME LONG CROWDING", fr_short: "SHORT CROWDING",
    fr_ext_short: "EXTREME SHORT CROWDING", fr_balanced: "BALANCED",
    basis_rt: "PERP · TERM CURVE", basis_bps: "BPS (PERP−INDEX)", basis_z: "Z-SCORE", basis_ann: "ANNUAL FUNDING",
    basis_aria: "basis curve: perp, quarter, next quarter", basis_partial: "PARTIAL",
    basis_partial_t: "term legs missing — normal for perp-only pairs",
    casc_none: "no OI series — block not produced", casc_below: "below threshold · score {0}", casc_gauge: "CASCADE SCORE",
    casc_proxy_t: "components derived from series — not a real liquidation feed", casc_proxy: "PROXY",
    book_title: "ORDER BOOK", book_k: "BOOK", book_notional: "±1% BOOK VALUE",
    book_cap: "imbalance = (bid−ask)/(total) · band: distance from mid · MID {0}",
    ls_title: "L/S TERM STRUCTURE", ls_no_periods: "no period data", ls_glob: "RETAIL ACCOUNTS",
    ls_acc: "TOP ACCOUNTS", ls_pos: "TOP POSITIONS", ls_taker: "TAKER BUY/SELL", ls_cells: "{0} CELLS MISSING",
    ls_grid: "4 FAMILIES × {0} PERIODS",
    ls_cap: "≥1 long-dominant · ≤1 short-dominant · read the crowded side inverted (fade)",
    spp_lead_spot: "SPOT LEADS", spp_lead_perp: "PERP LEADS", spp_lead_mixed: "MIXED", spp_lead_none: "LEADER —",
    spp_perp_only: "perp-only pair · no spot market", spp_window: "48H WINDOW", spp_premium: "PREMIUM",
    spp_ret: "RETURN SPREAD 48H",
    spp_cap: "from lag-1 cross-correlation asymmetry; needs ≥10 paired returns",
    plan_title: "PLANNING", plan_title_geo: "PLANNING · ATR GEOMETRY", plan_k: "PLAN", plan_sl: "ATR DISTANCE",
    plan_rt: "1.5×ATR% · ILLUSTRATIVE", plan_tp1r: "ATR 1× LEVEL", plan_tp2r: "ATR 2× LEVEL", plan_tp3r: "ATR 3× LEVEL",
    plan_env1: "ENVELOPE 1H", plan_env4: "ENVELOPE 4H", plan_env24: "ENVELOPE 24H",
    plan_note: "information only · ATR-based illustrative levels · no order is placed",

    cvd_title: "CVD · CUMULATIVE VOLUME DELTA", cvd_trades: "{0} TRADES", cvd_window: "{0} MIN WINDOW",
    cvd_window_none: "WINDOW —", cvd_buy: "BUY {0} · %{1}", cvd_sell: "%{0} SELL {1}",
    cvd_aria: "buy %{0} · sell %{1}", cvd_bars_t: "delta per sample (delta_series difference); ≤60 samples",
    cvd_series: "DELTA SERIES",
    cvd_cap: "CVD = Σ(buyer-initiated − seller-initiated volume) · last ≤1000 public aggTrades · {0} min window · {1} samples",

    claim_title: "REGISTERED CLAIMS", claim_rt: "{0} RECORDS · EVALUATED ONLY WITH GRADES AFTER REGISTRATION",
    claim_pending: "PENDING", claim_confirmed: "CONFIRMED", claim_refuted: "REFUTED", claim_k: "CLAIM",
    claim_none: "— no registered claims · pre-register one with the form below",
    claim_h_status: "STATUS", claim_h_claim: "CLAIM", claim_h_value: "VALUE", claim_h_thr: "THRESHOLD", claim_h_reg: "REGISTERED",
    claim_nofilter: "no filter", claim_all: "all verdicts",
    claim_empty: "claim text empty — nothing saved",
    claim_minn: "min_n must be a positive integer — nothing saved",
    claim_thr_num: "threshold must be a number — nothing saved",
    claim_thr_range: "hit-rate threshold must be 0–1 (e.g. 0.55, not a percent) — nothing saved",
    claim_saved: "CLAIM REGISTERED · {0} — evaluation uses only grades made after registration",
    claim_rejected: "REGISTRATION REJECTED · {0}", claim_form: "REGISTER CLAIM", claim_busy: "SAVING…",
    claim_ph: "claim text (e.g. <metric> <horizon> <threshold>)…",
    claim_a_metric: "metric", claim_a_filter: "filter", claim_a_horizon: "horizon", claim_a_minn: "min n",
    claim_a_op: "threshold operator", claim_a_thr: "threshold value", claim_minn_t: "minimum sample — PENDING below this",
    claim_cap: "claims are pre-registration discipline: evaluation uses only graded verdicts made AFTER registration · the record file is immutable",

    rs_chip: "TIME MACHINE", rs_chip_t: "time travel over the decision archive",
    rs_err: "ARCHIVE UNREADABLE · {0}", rs_title: "TIME MACHINE · \"WHAT IT SAID THEN\"", rs_k: "ARCHIVE",
    rs_empty: "no records in this range · no archived verdict for {0}",
    rs_rt: "{0} RECORDS · NEWEST {1} · PAST VIEW", rs_aria: "select archive record", rs_conf: "confidence %{0}",
    rs_score: "score {0}", rs_tfagree: "TF agreement {0}", rs_div: "divergence {0}",
    rs_static: "main chart unchanged (card shows only moment T)",
    rs_gap_cap: "the decision archive does not interpolate between records — gaps stay empty · the ghost card is for reporting",
    rp_title: "REPLAY · COUNTERFACTUAL THRESHOLD GRID",
    rp_rt: "{0} REPLAYABLE · {1} FOLDS · BOOTSTRAP n={2}/seed {3}", rp_k: "GRID", rp_busy: "computing…",
    rp_empty: "— not run yet · counterfactual threshold check on archive × grades",
    rp_running: "RUNNING…", rp_run: "RUN", rp_fail: "REPLAY FAILED · {0}",
    rp_skipped: "skipped: {0} weight-hash mismatch · {1} without signal · shipped cell: buy {2} / strong {3} / conflict {4}",
    rp_h_n: "N DIRECTIONAL", rp_h_hit: "HIT RATE", rp_h_fold: "FOLD SPREAD",
    rp_cap: "REPORT-ONLY · no threshold changes the engine · cell colour from delta vs shipped · boot win% = share of bootstraps that beat shipped · fold spread = {0}-fold hit-rate max−min",
  };

  G.DIVE_I18N = { tr: TR, en: EN };

  G.diveLang = function () {
    try {
      return (G.localStorage && G.localStorage.getItem(KEY)) === "en" ? "en" : "tr";
    } catch (e) { return "tr"; }
  };

  /* Keep <html lang> in step with the active language so screen readers and hyphenation
     use the right profile (index.html ships lang="tr" as the default). */
  function applyDocLang() {
    try {
      if (G.document && G.document.documentElement) G.document.documentElement.lang = G.diveLang();
    } catch (e) { /* no document (non-browser test host) → nothing to sync */ }
  }

  G.setDiveLang = function (code) {
    try { G.localStorage.setItem(KEY, code === "en" ? "en" : "tr"); } catch (e) { /* storage unavailable → session-only */ }
    applyDocLang();
    if (typeof G.__diveOnLang === "function") G.__diveOnLang();
  };
  applyDocLang();   // first paint already matches the stored language

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
