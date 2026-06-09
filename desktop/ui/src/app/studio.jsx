/* ============================================================================
   SGS · GÖRÜNÜM STÜDYOSU — tam kişiselleştirme paneli
   Hem telefon içi (sheet) hem sahne kenarında (rail) kullanılır.
   ========================================================================== */
function StudioBlock({ title, children }) {
  return (
    <div className="studio-block">
      <div className="studio-block-title">{title}</div>
      {children}
    </div>
  );
}

function Seg({ value, options, onChange }) {
  return (
    <div className="seg">
      {options.map(o => (
        <button type="button" key={o.v} className="seg-btn" data-active={value === o.v ? '1' : '0'} onClick={() => onChange(o.v)}>{o.l}</button>
      ))}
    </div>
  );
}

function SwatchRow({ colors, value, onPick, big }) {
  return (
    <div className="swatch-row">
      {colors.map(c => (
        <button type="button" key={c} className="swatch" data-active={value === c ? '1' : '0'}
          onClick={() => onPick(c)} style={{ '--sw': c, width: big ? 34 : 28, height: big ? 34 : 28 }} aria-label={c}>
          {value === c && <Icon name="check" size={big ? 16 : 13} stroke="#fff" sw={2.6} />}
        </button>
      ))}
    </div>
  );
}

function ThemeStudio({ layout = 'sheet' }) {
  const t = useTheme();
  const { presets, presetId, setPreset, custom, update, reset } = t;
  const families = ['Fütüristik', 'Klasik', 'Gece / Terminal'];
  const current = presets.find(p => p.id === presetId);
  const [fam, setFam] = React.useState(current ? current.family : 'Fütüristik');

  const updownVal = window.SGS.UPDOWN_OPTIONS.find(o =>
    (custom.up || current.base.up) === o.up && (custom.down || current.base.down) === o.down);

  return (
    <div className={`studio studio-${layout}`}>
      {/* ── hazır temalar ── */}
      <StudioBlock title="HAZIR TEMA">
        <div className="seg seg-fam">
          {families.map(f => (
            <button type="button" key={f} className="seg-btn" data-active={fam === f ? '1' : '0'} onClick={() => setFam(f)}>
              {f === 'Gece / Terminal' ? 'Terminal' : f}
            </button>
          ))}
        </div>
        <div className="preset-grid">
          {presets.filter(p => p.family === fam).map(p => (
            <button type="button" key={p.id} className="preset-card" data-active={p.id === presetId ? '1' : '0'} onClick={() => setPreset(p.id)}>
              <div className="preset-swatches">
                {[p.base.bg, p.base.card, p.base.accent, p.base.up, p.base.down].map((c, i) => (
                  <span key={i} style={{ background: c }} />
                ))}
              </div>
              <div className="preset-name">{p.name}</div>
              <div className="preset-blurb">{p.blurb}</div>
              {p.id === presetId && <div className="preset-check"><Icon name="check" size={12} stroke="#fff" sw={3} /></div>}
            </button>
          ))}
        </div>
      </StudioBlock>

      {/* ── yazı & yoğunluk ── */}
      <StudioBlock title="YAZI BOYUTU">
        <div className="slider-row">
          <Icon name="type" size={15} stroke="var(--muted)" />
          <input type="range" min="0.85" max="1.3" step="0.05" value={custom.sizeScale}
            onChange={e => update('sizeScale', parseFloat(e.target.value))} className="slider" />
          <span className="slider-val">{Math.round(custom.sizeScale * 100)}%</span>
        </div>
      </StudioBlock>

      <StudioBlock title="KONTRAST">
        <Seg value={custom.contrast} onChange={v => update('contrast', v)}
          options={[{ v: 'normal', l: 'Normal' }, { v: 'high', l: 'Yüksek Kontrast' }]} />
      </StudioBlock>

      <StudioBlock title="YOĞUNLUK">
        <Seg value={custom.density} onChange={v => update('density', v)}
          options={[{ v: 'compact', l: 'Kompakt' }, { v: 'comfortable', l: 'Ferah' }, { v: 'spacious', l: 'Geniş' }]} />
      </StudioBlock>

      <StudioBlock title="YAZI TİPİ">
        <Seg value={custom.font || current.base.font} onChange={v => update('font', v)}
          options={[{ v: 'mono', l: 'Monospace' }, { v: 'sans', l: 'Sans-serif' }]} />
      </StudioBlock>

      <StudioBlock title="HAREKET / ANİMASYON">
        <Seg value={custom.motion} onChange={v => update('motion', v)}
          options={[{ v: 'off', l: 'Kapalı' }, { v: 'subtle', l: 'Hafif' }, { v: 'full', l: 'Tam' }]} />
      </StudioBlock>

      {/* ── renkler ── */}
      <StudioBlock title="VURGU RENGİ">
        <SwatchRow colors={window.SGS.ACCENT_OPTIONS} value={custom.accent || current.base.accent} onPick={c => update('accent', c)} big />
      </StudioBlock>

      <StudioBlock title="MUM RENKLERİ (YÜKSELİŞ / DÜŞÜŞ)">
        <div className="updown-row">
          {window.SGS.UPDOWN_OPTIONS.map(o => (
            <button type="button" key={o.id} className="updown-card" data-active={updownVal && updownVal.id === o.id ? '1' : '0'}
              onClick={() => { update('up', o.up); update('down', o.down); }}>
              <span className="updown-bars"><i style={{ background: o.up }} /><i style={{ background: o.down }} /></span>
              <span className="updown-lbl">{o.label}</span>
            </button>
          ))}
        </div>
      </StudioBlock>

      <StudioBlock title="GRAFİK TİPİ">
        <Seg value={custom.chartType} onChange={v => update('chartType', v)}
          options={[{ v: 'candle', l: 'Mum' }, { v: 'line', l: 'Çizgi' }, { v: 'area', l: 'Alan' }, { v: 'heikin', l: 'Heikin' }]} />
      </StudioBlock>

      <StudioBlock title="GRAFİK ÇİZGİ / DOLGU RENGİ">
        <SwatchRow colors={window.SGS.CHART_COLORS} value={custom.chartColor || current.base.chartLine} onPick={c => update('chartColor', c)} />
      </StudioBlock>

      <button type="button" className="studio-reset" onClick={reset}>
        <Icon name="refresh" size={14} /> Temanın varsayılanına sıfırla
      </button>
    </div>
  );
}

Object.assign(window, { ThemeStudio });
