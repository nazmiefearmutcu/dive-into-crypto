/* ============================================================================
   SGS · OI · L/S EKRANI — açık ilgi, long/short oranları, quant bias
   ========================================================================== */
function OILSScreen({ symbol, setSymbol }) {
  const t = useTheme();
  const D = window.SGS;
  const [period, setPeriod] = React.useState('1h');
  const periods = ['5m', '15m', '1h', '4h', '1d'];
  const S = React.useMemo(() => D.positionSeries(symbol, period), [symbol, period]);
  const i = S.prices.length - 1;
  const quant = S.quant[i];
  const favs = D.FAVORITES;

  const gaugeLabel = quant >= 60 ? 'GÜÇLÜ BOĞA / GÜÇLÜ AL' : quant >= 20 ? 'BOĞA EĞİLİMİ / AL'
    : quant > -20 ? 'DENGELİ / NÖTR' : quant > -60 ? 'AYI EĞİLİMİ / SAT' : 'GÜÇLÜ AYI / GÜÇLÜ SAT';
  const gaugeCol = quant >= 20 ? 'var(--up)' : quant <= -20 ? 'var(--down)' : 'var(--muted)';

  function delta(arr) { const a = arr[i], b = arr[i - 1] || a; return b ? ((a - b) / b) * 100 : 0; }
  const metrics = [
    { l: 'FİYAT', v: '$' + D.fmtPrice(S.prices[i]), d: delta(S.prices), c: 'var(--chart-line)' },
    { l: 'AÇIK İLGİ', v: '$' + D.fmtBig(S.oi[i]), d: delta(S.oi), c: 'var(--info)' },
    { l: 'QUANT BIAS', v: (quant >= 0 ? '+' : '') + quant.toFixed(1) + '%', d: quant - S.quant[i - 1], c: gaugeCol },
    { l: 'BALİNA L/S', v: S.position[i].toFixed(2), d: delta(S.position), c: 'var(--accent)' },
    { l: 'HESAP L/S', v: S.account[i].toFixed(2), d: delta(S.account), c: 'var(--warn)' },
    { l: 'GLOBAL L/S', v: S.global[i].toFixed(2), d: delta(S.global), c: 'var(--up)' },
    { l: 'TAKER L/S', v: S.takerR[i].toFixed(2), d: delta(S.takerR), c: 'var(--info)' },
    { l: 'NET TAKER H.', v: '$' + D.fmtBig(S.netTaker[i]), d: delta(S.netTaker), c: 'var(--accent)' },
    { l: 'FONLAMA', v: (S.funding[i] * 100).toFixed(4) + '%', d: delta(S.funding), c: 'var(--warn)' },
  ];

  const posLong = S.position[i] / (1 + S.position[i]) * 100;
  const accLong = S.account[i] / (1 + S.account[i]) * 100;

  const series = [
    { l: 'FİYAT', arr: S.prices, c: 'var(--chart-line)' },
    { l: 'AÇIK İLGİ', arr: S.oi, c: 'var(--info)' },
    { l: 'BALİNA L/S', arr: S.position, c: 'var(--accent)' },
    { l: 'QUANT BIAS', arr: S.quant, c: gaugeCol, base: true },
  ];

  return (
    <div className="screen">
      <Card title={`${symbol} · ${period}`}>
        <div className="chip-scroll" style={{ marginBottom: '0.5em' }}>
          {favs.map(s => <Chip key={s} active={s === symbol} color="var(--accent)" onClick={() => setSymbol(s)}>{s.replace('USDT', '')}</Chip>)}
        </div>
        <div className="chip-scroll">
          {periods.map(p => <Chip key={p} active={p === period} color="var(--accent)" onClick={() => setPeriod(p)}>{p}</Chip>)}
        </div>
      </Card>

      {/* quant gauge */}
      <Card title="KANTİTATİF PİYASA YÖNÜ SKORU (QUANT BIAS)" accent={gaugeCol}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', marginBottom: '0.55em' }}>
          <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 800, color: gaugeCol, fontSize: '0.92em' }}>{gaugeLabel}</span>
          <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 800, color: gaugeCol, fontSize: '1.35em' }}>{quant >= 0 ? '+' : ''}{quant.toFixed(1)}%</span>
        </div>
        <GaugeBar score={quant} />
        <div className="gauge-scale"><span>-100 AYI</span><span>0</span><span>BOĞA +100</span></div>
      </Card>

      {/* metrik ızgarası */}
      <Card title="KANTİTATİF GÖSTERGELER">
        <div className="metric-grid">
          {metrics.map(m => (
            <div key={m.l} className="metric">
              <div className="metric-l"><span className="metric-dot" style={{ background: m.c }} />{m.l}</div>
              <div className="metric-v">{m.v}</div>
              <div className="metric-d"><Delta v={m.d} digits={2} /></div>
            </div>
          ))}
        </div>
      </Card>

      {/* seriler */}
      <Card title="KANTİTATİF SERİLER (SON 30 NOKTA)">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'calc(var(--gap) * 0.9)' }}>
          {series.map(s => (
            <div key={s.l}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.2em' }}>
                <span className="mono-dim" style={{ fontSize: '0.66em', letterSpacing: '0.08em' }}>{s.l}</span>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: '0.72em', fontWeight: 700, color: s.c }}>
                  {s.base ? (s.arr[i] >= 0 ? '+' : '') + s.arr[i].toFixed(1) : (s.l === 'FİYAT' ? '$' + D.fmtPrice(s.arr[i]) : s.l === 'AÇIK İLGİ' ? '$' + D.fmtBig(s.arr[i]) : s.arr[i].toFixed(2))}
                </span>
              </div>
              <Sparkline values={s.arr} color={s.c} baseline={s.base} height={s.base ? 38 : 30} />
            </div>
          ))}
        </div>
      </Card>

      {/* L/S dağılımı */}
      <Card title="LONG / SHORT DAĞILIMI (GÜNCEL)">
        <LSRow label="Balina L/S (pozisyon ağırlıklı)" long={posLong} />
        <div style={{ height: 'var(--gap)' }} />
        <LSRow label="Hesap L/S (kullanıcı sayısı)" long={accLong} />
      </Card>
    </div>
  );
}

function LSRow({ label, long }) {
  const short = 100 - long;
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.3em' }}>
        <span className="mono-dim" style={{ fontSize: '0.7em' }}>{label}</span>
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '0.72em', fontWeight: 700 }}>
          <span style={{ color: 'var(--up)' }}>L {long.toFixed(1)}%</span> · <span style={{ color: 'var(--down)' }}>S {short.toFixed(1)}%</span>
        </span>
      </div>
      <div className="ls-bar">
        <div style={{ width: long + '%', background: 'var(--up)' }} />
        <div style={{ width: short + '%', background: 'var(--down)' }} />
      </div>
    </div>
  );
}
Object.assign(window, { OILSScreen });
