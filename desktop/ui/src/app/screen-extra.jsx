/* ============================================================================
   SGS · SİNYAL + AYARLAR EKRANLARI
   ========================================================================== */
function SinyalScreen({ symbol }) {
  const D = window.SGS;
  const inds = React.useMemo(() => D.indicatorDetails(symbol), [symbol]);
  const co = React.useMemo(() => D.consensus(symbol), [symbol]);
  const buy = inds.filter(x => x.score > 0).length;
  const sell = inds.filter(x => x.score < 0).length;
  const neu = inds.length - buy - sell;

  return (
    <div className="screen">
      <Card title={`${symbol} · KONSENSÜS ÖZETİ`} accent={SIG_COLOR[co.final]}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.6em' }}>
          <SignalPill signal={co.final} />
          <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 800 }}>%{co.confidence} güven</span>
          <span style={{ flex: 1 }} />
          <span className="mono-dim" style={{ fontSize: '0.72em' }}>{buy} AL · {neu} NÖTR · {sell} SAT</span>
        </div>
      </Card>

      <Card title="15 İNDİKATÖR DETAYI">
        <div className="ind-list">
          {inds.map(x => {
            const c = x.score > 0 ? 'var(--up)' : x.score < 0 ? 'var(--down)' : 'var(--muted)';
            return (
              <div key={x.name} className="ind-row">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 700, fontSize: '0.92em' }}>{x.name}</div>
                  <div className="mono-dim" style={{ fontSize: '0.68em' }}>{x.desc}</div>
                </div>
                <div style={{ textAlign: 'right', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.25em' }}>
                  <SignalPill signal={x.signal} small />
                  <span className="mono-dim" style={{ fontSize: '0.66em' }}>ağ. {x.weight.toFixed(1)} · <span style={{ color: c, fontWeight: 700 }}>{x.weighted > 0 ? '+' : ''}{x.weighted.toFixed(2)}</span></span>
                </div>
              </div>
            );
          })}
        </div>
      </Card>
    </div>
  );
}

/* ── Ayarlar ──────────────────────────────────────────────────────────── */
function Toggle({ on, onChange }) {
  return (
    <button type="button" className="toggle" data-on={on ? '1' : '0'} onClick={() => onChange(!on)}>
      <span className="toggle-knob" />
    </button>
  );
}
function StepRow({ label, value, onDec, onInc }) {
  return (
    <div className="step-row">
      <span style={{ flex: 1, fontSize: '0.9em' }}>{label}</span>
      <button type="button" className="step-btn" onClick={onDec}><Icon name="minus" size={14} /></button>
      <span className="step-val">{value}</span>
      <button type="button" className="step-btn" onClick={onInc}><Icon name="plus" size={14} /></button>
    </div>
  );
}

function AyarlarScreen() {
  const [conf, setConf] = React.useState(65);
  const [trade, setTrade] = React.useState(75);
  const [regime, setRegime] = React.useState(true);
  const [surv, setSurv] = React.useState(50);
  const [par, setPar] = React.useState(8);

  return (
    <div className="screen">
      {/* görünüm stüdyosu — gerçek ürün özelliği */}
      <Card title="GÖRÜNÜM & TEMA STÜDYOSU">
        <p className="reason" style={{ marginTop: 0, marginBottom: 'calc(var(--gap)*0.8)' }}>
          Uygulama, hazır temaların ötesinde tamamen sizin tercihinize göre şekillenir. Renkler, yazı boyutu, kontrast, grafik tipi ve daha fazlası anlık uygulanır.
        </p>
        <ThemeStudio layout="inapp" />
      </Card>

      {/* analiz ayarları */}
      <Card title="ANALİZ ALGORİTMASI AYARLARI">
        <div className="set-row">
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: '0.9em' }}>Dinamik Rejim Matrisi (ADX)</div>
            <div className="mono-dim" style={{ fontSize: '0.68em' }}>Trend gücüne göre indikatör ağırlıkları</div>
          </div>
          <Toggle on={regime} onChange={setRegime} />
        </div>
        <StepRow label="Min Konsensüs Eşiği" value={conf + '%'} onDec={() => setConf(Math.max(10, conf - 5))} onInc={() => setConf(Math.min(90, conf + 5))} />
        <StepRow label="Min İşlem Eşiği" value={trade + '%'} onDec={() => setTrade(Math.max(15, trade - 5))} onInc={() => setTrade(Math.min(95, trade + 5))} />
      </Card>

      {/* tarama ayarları */}
      <Card title="TARAMA (SCANNER) AYARLARI">
        <div className="mono-dim" style={{ fontSize: '0.72em', marginBottom: '0.4em' }}>Faz 2 Aday Sayısı</div>
        <div className="chip-scroll" style={{ marginBottom: '0.7em' }}>
          {[30, 50, 75].map(n => <Chip key={n} active={n === surv} color="var(--accent)" onClick={() => setSurv(n)}>{n}</Chip>)}
        </div>
        <div className="mono-dim" style={{ fontSize: '0.72em', marginBottom: '0.4em' }}>Eşzamanlı İstek Limiti</div>
        <div className="chip-scroll">
          {[4, 8, 12].map(n => <Chip key={n} active={n === par} color="var(--accent)" onClick={() => setPar(n)}>{n}</Chip>)}
        </div>
      </Card>

      {/* hakkında */}
      <Card title="HAKKINDA">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.7em', marginBottom: 'calc(var(--gap)*0.8)' }}>
          <SGSMark size={40} />
          <div>
            <div style={{ fontFamily: 'var(--font-mono)', fontWeight: 800, fontSize: '1.05em' }}>Such A Good Scanner</div>
            <div className="mono-dim" style={{ fontSize: '0.72em' }}>SGS · konsensüs tarama motoru</div>
          </div>
        </div>
        {[['Uygulama', 'SGS — iOS & Android'], ['Versiyon', '2.0.0'], ['Veri kaynağı', 'Binance USDT-M Futures'], ['İndikatörler', '15 (konsensüs motoru)'], ['Zaman dilimi', '12']].map(([k, v]) => (
          <div key={k} className="about-row"><span className="mono-dim">{k}</span><span style={{ fontFamily: 'var(--font-mono)', fontWeight: 700 }}>{v}</span></div>
        ))}
      </Card>
    </div>
  );
}
Object.assign(window, { SinyalScreen, AyarlarScreen });
