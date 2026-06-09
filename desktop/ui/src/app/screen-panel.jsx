/* ============================================================================
   SGS · PANEL EKRANI — aktif coin, mum grafik, 12-TF konsensüs, son karar
   ========================================================================== */
function PanelScreen({ symbol, setSymbol }) {
  const t = useTheme();
  const D = window.SGS;
  const [q, setQ] = React.useState('');
  const [tf, setTf] = React.useState('1h');
  const co = React.useMemo(() => D.consensus(symbol), [symbol]);
  const candles = React.useMemo(() => D.genCandles(symbol, tf, 56), [symbol, tf]);
  const price = candles[candles.length - 1].c;
  const prev = candles[Math.max(0, candles.length - 14)].c;
  const chgPct = ((price - prev) / prev) * 100;

  const results = q ? D.symbols.filter(s => s.includes(q.toUpperCase())).slice(0, 6) : [];
  const favs = D.FAVORITES;

  return (
    <div className="screen">
      {/* arama + favori coinler */}
      <Card title="COİN SEÇİMİ & ARAMA">
        <div className="search">
          <Icon name="search" size={15} stroke="var(--dim)" />
          <input className="search-input" placeholder="Örn: BTCUSDT, SOLUSDT…" value={q} onChange={e => setQ(e.target.value)} />
          {q && <button type="button" className="icon-btn" onClick={() => setQ('')}><Icon name="x" size={14} /></button>}
        </div>
        {q ? (
          <div className="result-list">
            {results.length ? results.map(s => (
              <button type="button" key={s} className="result-row" onClick={() => { setSymbol(s); setQ(''); }}>
                <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 700, color: s === symbol ? 'var(--accent)' : 'var(--text)' }}>{s}</span>
                <span className="mono-dim">${D.fmtPrice(D.priceFor(s))}</span>
              </button>
            )) : <div className="mono-dim" style={{ padding: '0.4em 0' }}>Eşleşen coin bulunamadı.</div>}
          </div>
        ) : (
          <div className="chip-scroll">
            {favs.map(s => <Chip key={s} active={s === symbol} color="var(--accent)" onClick={() => setSymbol(s)}>{s.replace('USDT', '')}</Chip>)}
          </div>
        )}
      </Card>

      {/* durum satırı */}
      <Card>
        <div className="statusrow">
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.4em' }}>
              <span className="sym-big">{symbol}</span>
              <SignalPill signal={co.final} small />
            </div>
            <div className="mono-dim" style={{ marginTop: '0.25em' }}>Binance USDT-M · Perp</div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div className="price-big" style={{ color: chgPct >= 0 ? 'var(--up)' : 'var(--down)' }}>${D.fmtPrice(price)}</div>
            <div style={{ marginTop: '0.2em', fontSize: '0.8em' }}><Delta v={chgPct} /></div>
          </div>
        </div>
      </Card>

      {/* mum grafik */}
      <Card title={`${symbol} · ${tf.toUpperCase()} GRAFİK`} right={
        <span className="chart-type-tag">{({ candle: 'MUM', line: 'ÇİZGİ', area: 'ALAN', heikin: 'HEIKIN' })[t.chartType]}</span>
      }>
        <div style={{ margin: '0.2em 0 0.6em' }}>
          <PriceChart candles={candles} type={t.chartType} motion={t.motion} height={172} />
        </div>
        <div className="chip-scroll tf-row">
          {D.TF.map(x => <Chip key={x} active={x === tf} color="var(--accent)" onClick={() => setTf(x)} style={{ fontSize: '0.66em' }}>{x}</Chip>)}
        </div>
      </Card>

      {/* 12-TF konsensüs ızgarası */}
      <Card title={`${symbol} · 12 ZAMAN DİLİMİ KONSENSÜS GÜVENİ`}>
        <div className="tf-grid">
          {co.sigs.map(s => {
            const isUp = s.score > 0, isDn = s.score < 0;
            const col = isUp ? 'var(--up)' : isDn ? 'var(--down)' : 'var(--muted)';
            const bg = isUp ? 'var(--up-soft)' : isDn ? 'var(--down-soft)' : 'var(--neutral-soft)';
            return (
              <div key={s.tf} className="tf-cell" style={{ background: bg, borderColor: col + '44' }}>
                <span className="tf-cell-tf" style={{ color: col }}>{s.tf}</span>
                <span className="tf-cell-cf" style={{ color: col }}>%{s.confidence}</span>
              </div>
            );
          })}
        </div>
      </Card>

      {/* son karar */}
      <Card title="SON KARAR (KONSENSÜS)" accent={SIG_COLOR[co.final]}>
        <div className="decision">
          <div className="decision-action" style={{ color: SIG_COLOR[co.final] }}>{co.action}</div>
          <div style={{ textAlign: 'right' }}>
            <div className="mono-dim" style={{ fontSize: '0.62em', letterSpacing: '0.1em' }}>RİSK</div>
            <div style={{ fontFamily: 'var(--font-mono)', fontWeight: 800, color: co.risk === 'DÜŞÜK' ? 'var(--up)' : co.risk === 'ORTA' ? 'var(--warn)' : 'var(--down)' }}>{co.risk}</div>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.7em', margin: '0.6em 0 0.5em' }}>
          <span className="mono-dim" style={{ fontSize: '0.66em' }}>GÜVEN</span>
          <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 800, fontSize: '1.05em' }}>%{co.confidence}</span>
          <div style={{ flex: 1 }}><ProgressBar value={co.confidence} color={SIG_COLOR[co.final]} glow={t.fx.glow} /></div>
        </div>
        <p className="reason">{co.reason}</p>
      </Card>

      {/* sinyal dağılımı */}
      <Card title="İNDİKATÖR SİNYAL DAĞILIMI">
        <div className="dist-bar">
          <div style={{ flex: co.buy || 0.4, background: 'var(--up)' }} />
          <div style={{ flex: co.neutral || 0.4, background: 'var(--muted)' }} />
          <div style={{ flex: co.sell || 0.4, background: 'var(--down)' }} />
        </div>
        <div className="dist-legend">
          <span><i style={{ background: 'var(--up)' }} />Alış · {co.buy}</span>
          <span><i style={{ background: 'var(--muted)' }} />Nötr · {co.neutral}</span>
          <span><i style={{ background: 'var(--down)' }} />Satış · {co.sell}</span>
        </div>
      </Card>
    </div>
  );
}
Object.assign(window, { PanelScreen });
