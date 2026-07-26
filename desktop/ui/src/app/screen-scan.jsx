/* ============================================================================
   SGS · TARAMA EKRANI — manuel scanner, canlı ilerleme, sonuç kartları
   ========================================================================== */
function TaramaScreen({ setSymbol, goto }) {
  const t = useTheme();
  const D = window.SGS;
  const [scanning, setScanning] = React.useState(false);
  const [done, setDone] = React.useState(false);
  const [prog, setProg] = React.useState(0);
  const [phase, setPhase] = React.useState('');
  const [cur, setCur] = React.useState('');
  const timer = React.useRef(null);

  const rows = React.useMemo(() => D.crossRanking(5), []);
  const total = D.UNIVERSE.length;

  const phases = ['EVREN ALINIYOR', 'FAZ 1 · 1d/12h/8h', 'FAZ 2 · 9 TF', 'SIRALAMA'];

  function start() {
    setScanning(true); setDone(false); setProg(0);
    let i = 0;
    clearInterval(timer.current);
    timer.current = setInterval(() => {
      i += 1;
      const pct = Math.min(100, (i / total) * 100);
      setProg(pct);
      setPhase(phases[Math.min(phases.length - 1, Math.floor((pct / 100) * phases.length))]);
      setCur(D.UNIVERSE[Math.min(total - 1, i)][0]);
      if (i >= total) { clearInterval(timer.current); setScanning(false); setDone(true); }
    }, 90);
  }
  function stop() { clearInterval(timer.current); setScanning(false); }
  React.useEffect(() => () => clearInterval(timer.current), []);

  return (
    <div className="screen">
      {/* hero / kontrol */}
      <div className="card hero">
        <div className="card-accent" style={{ background: 'var(--accent)' }} />
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.7em' }}>
          <div style={{ flex: 1 }}>
            <SecLabel>MANUEL TARAMA</SecLabel>
            <div className="hero-sub">{scanning ? phase : done ? `${total} sembol · 12 TF · İlk 5 gösteriliyor` : 'Tüm Binance USDT-M · 12 zaman dilimi'}</div>
            <div className="mono-dim" style={{ marginTop: '0.25em', fontSize: '0.72em' }}>Konsensüs motoru · 15 indikatör</div>
          </div>
          <SGSMark size={34} />
        </div>
        <button type="button" className={`cta ${scanning ? 'cta-stop' : ''}`} onClick={scanning ? stop : start}>
          <Icon name={scanning ? 'stop' : 'play'} size={17} stroke={scanning ? 'var(--accent-ink)' : 'var(--accent-ink)'} />
          {scanning ? 'Durdur' : 'Taramaya Başla'}
        </button>
      </div>

      {/* ilerleme */}
      {scanning && (
        <Card>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.6em' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: '0.4em', color: 'var(--info)', fontFamily: 'var(--font-mono)', fontSize: '0.7em', fontWeight: 700, letterSpacing: '0.08em' }}>
              <span className="pulse-dot" style={{ background: 'var(--info)' }} />{phase}
            </span>
            <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 800 }}>{Math.round(prog)}%</span>
          </div>
          <ProgressBar value={prog} color="var(--info)" glow={t.fx.glow} h={8} />
          {cur && <div style={{ marginTop: '0.6em', fontFamily: 'var(--font-mono)', fontSize: '0.78em' }}><span className="mono-dim">ŞU AN </span><b>{cur}</b></div>}
        </Card>
      )}

      {/* boş durum */}
      {!scanning && !done && (
        <Card>
          <div className="empty">
            <div className="empty-ico"><Icon name="scan" size={30} stroke="var(--dim)" /></div>
            <div style={{ fontWeight: 700, marginTop: '0.5em' }}>Henüz Tarama Çalıştırılmadı</div>
            <div className="mono-dim" style={{ marginTop: '0.2em' }}>Yukarıdaki “Taramaya Başla” düğmesine bas.</div>
          </div>
        </Card>
      )}

      {/* sonuç kartları */}
      {done && rows.map((r, idx) => {
        const all = r.countHit >= r.totalTfs;
        const near = r.countHit >= r.totalTfs - 1;
        const col = r.net > 0 ? 'var(--up)' : r.net < 0 ? 'var(--down)' : 'var(--muted)';
        return (
          <div key={r.symbol} className="card result-card" style={{ borderColor: all ? 'var(--up-line)' : near ? 'var(--warn-soft)' : 'var(--border)' }}>
            <div className="card-accent" style={{ background: col }} />
            {/* satır 1 */}
            <div className="rc-head">
              <span className="rank">#{idx + 1}</span>
              {all && <Icon name="star" size={15} stroke="var(--up)" />}
              <span className="rc-sym">{r.symbol}</span>
              <SignalPill signal={r.dir} small />
              <span style={{ flex: 1 }} />
              <span className="mono-dim" style={{ color: 'var(--text)', fontWeight: 700 }}>${D.fmtPrice(r.price)}</span>
            </div>
            {/* güven + risk */}
            <div className="rc-row">
              <span className="mono-dim">GÜVEN</span>
              <b style={{ fontFamily: 'var(--font-mono)' }}>%{r.confidence}</b>
              <div style={{ flex: 1 }}><ProgressBar value={r.confidence} color={col} glow={t.fx.glow} h={6} /></div>
              <span className="risk-chip" style={{ color: r.risk === 'DÜŞÜK' ? 'var(--up)' : r.risk === 'ORTA' ? 'var(--warn)' : 'var(--down)', borderColor: 'var(--border)' }}>{r.risk}</span>
            </div>
            {/* puan + uyum */}
            <div className="rc-row">
              <span className="mono-dim">PUAN</span>
              <b style={{ fontFamily: 'var(--font-mono)', color: 'var(--warn)' }}>{r.netNss >= 1000 ? (r.netNss / 1000).toFixed(1) + 'K' : Math.round(r.netNss)}</b>
              <span style={{ flex: 1 }} />
              <span className="agree-chip" style={{ background: r.countHit >= 9 ? 'var(--up-soft)' : 'var(--warn-soft)', color: r.countHit >= 9 ? 'var(--up)' : 'var(--warn)' }}>{r.countHit}/{r.totalTfs} TF UYUM</span>
            </div>
            {/* 12 TF mini ızgara */}
            <SecLabel style={{ display: 'block', margin: '0.5em 0 0.4em', fontSize: '0.62em' }}>TÜM 12 ZAMAN DİLİMİ</SecLabel>
            <div className="tf-grid tf-grid-mini">
              {r.sigs.map(s => {
                const u = s.score > 0, dn = s.score < 0;
                const c = u ? 'var(--up)' : dn ? 'var(--down)' : 'var(--muted)';
                const bg = u ? 'var(--up-soft)' : dn ? 'var(--down-soft)' : 'var(--neutral-soft)';
                return <div key={s.tf} className="tf-cell" style={{ background: bg, borderColor: c + '44' }}>
                  <span className="tf-cell-tf" style={{ color: c }}>{s.tf}</span>
                  <span className="tf-cell-cf" style={{ color: c }}>%{s.confidence}</span>
                </div>;
              })}
            </div>
            <button type="button" className="rc-cta" onClick={() => { setSymbol(r.symbol); goto('panel'); }}>SEÇ →</button>
          </div>
        );
      })}
      {done && <div className="mono-dim" style={{ fontSize: '0.7em', textAlign: 'center' }}>Son tarama: bugün 12:09:44</div>}
    </div>
  );
}
Object.assign(window, { TaramaScreen });
