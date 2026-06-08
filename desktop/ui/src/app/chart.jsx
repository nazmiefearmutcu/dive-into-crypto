/* ============================================================================
   SGS — Chart renderer. candle | line | area | heikin.  Canvas, DPR-aware,
   reads colors from CSS vars resolved at draw time. Optional crosshair.
   ========================================================================== */

function sgsCssVar(el, name, fallback) {
  const v = getComputedStyle(el).getPropertyValue(name).trim();
  return v || fallback;
}

/* robustly convert a hex or rgb() color string to rgba with given alpha */
function sgsToRgba(c, a) {
  if (!c) return `rgba(91,141,239,${a})`;
  c = c.trim();
  if (c[0] === "#") {
    let h = c.slice(1);
    if (h.length === 3) h = h.split("").map((x) => x + x).join("");
    const n = parseInt(h, 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
  }
  if (c.startsWith("rgb(")) return c.replace("rgb(", "rgba(").replace(")", `,${a})`);
  if (c.startsWith("rgba(")) return c.replace(/,[^,]+\)$/, `,${a})`);
  return c;
}

function sgsHeikin(candles) {
  const out = [];
  let prevO = candles[0].o, prevC = candles[0].c;
  for (const k of candles) {
    const close = (k.o + k.h + k.l + k.c) / 4;
    const open = (prevO + prevC) / 2;
    const high = Math.max(k.h, open, close);
    const low = Math.min(k.l, open, close);
    out.push({ t: k.t, o: open, h: high, l: low, c: close, v: k.v });
    prevO = open; prevC = close;
  }
  return out;
}

function SGSChart({ candles, type = "candle", height = 168, showAxis = true, interactive = true, count = 60 }) {
  const canvasRef = React.useRef(null);
  const wrapRef = React.useRef(null);
  const [hover, setHover] = React.useState(null);
  const data = React.useMemo(() => {
    const slice = candles.slice(-count);
    return type === "heikin" ? sgsHeikin(slice) : slice;
  }, [candles, type, count]);

  const draw = React.useCallback(() => {
    const cv = canvasRef.current, wrap = wrapRef.current;
    if (!cv || !wrap) return;
    const dpr = window.devicePixelRatio || 1;
    const W = wrap.clientWidth, H = height;
    cv.width = W * dpr; cv.height = H * dpr;
    cv.style.width = W + "px"; cv.style.height = H + "px";
    const ctx = cv.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);

    const up = sgsCssVar(cv, "--up", "#22C55E");
    const down = sgsCssVar(cv, "--down", "#EF4444");
    const chart = sgsCssVar(cv, "--chart", "#5B8DEF");
    const border = sgsCssVar(cv, "--border", "#2A2D3A");
    const dim = sgsCssVar(cv, "--dim", "#5A5E72");
    const elev = sgsCssVar(cv, "--bg2", "#0F1117");
    const fontMono = sgsCssVar(cv, "--font-mono", "monospace");

    const padR = showAxis ? 52 : 6, padL = 6, padT = 8, padB = showAxis ? 18 : 6;
    const cw = W - padL - padR, chh = H - padT - padB;
    const hi = Math.max(...data.map((d) => d.h));
    const lo = Math.min(...data.map((d) => d.l));
    const span = (hi - lo) || 1;
    const x = (i) => padL + (i / (data.length - 1)) * cw;
    const y = (p) => padT + (1 - (p - lo) / span) * chh;

    // grid
    ctx.strokeStyle = border; ctx.lineWidth = 1; ctx.globalAlpha = 0.5;
    ctx.font = `10px ${fontMono}`; ctx.fillStyle = dim; ctx.textBaseline = "middle";
    for (let g = 0; g <= 4; g++) {
      const gy = padT + (g / 4) * chh;
      ctx.beginPath(); ctx.moveTo(padL, gy); ctx.lineTo(padL + cw, gy); ctx.stroke();
      if (showAxis) {
        const val = hi - (g / 4) * span;
        ctx.globalAlpha = 0.9;
        ctx.fillText(val >= 1000 ? (val / 1000).toFixed(1) + "K" : val >= 1 ? val.toFixed(2) : val.toFixed(4), padL + cw + 6, gy);
        ctx.globalAlpha = 0.5;
      }
    }
    ctx.globalAlpha = 1;

    if (type === "line" || type === "area") {
      if (type === "area") {
        const grd = ctx.createLinearGradient(0, padT, 0, padT + chh);
        grd.addColorStop(0, sgsToRgba(chart, 0.33));
        grd.addColorStop(1, sgsToRgba(chart, 0));
        ctx.beginPath();
        ctx.moveTo(x(0), y(data[0].c));
        data.forEach((d, i) => ctx.lineTo(x(i), y(d.c)));
        ctx.lineTo(x(data.length - 1), padT + chh);
        ctx.lineTo(x(0), padT + chh);
        ctx.closePath(); ctx.fillStyle = grd; ctx.fill();
      }
      ctx.beginPath();
      data.forEach((d, i) => (i ? ctx.lineTo(x(i), y(d.c)) : ctx.moveTo(x(i), y(d.c))));
      ctx.strokeStyle = chart; ctx.lineWidth = 2; ctx.lineJoin = "round";
      ctx.stroke();
      // last dot
      const last = data[data.length - 1];
      ctx.beginPath(); ctx.arc(x(data.length - 1), y(last.c), 3, 0, 7); ctx.fillStyle = chart; ctx.fill();
    } else {
      // candles / heikin
      const cwid = Math.max(1.5, Math.min(10, (cw / data.length) * 0.62));
      data.forEach((d, i) => {
        const isUp = d.c >= d.o;
        const col = isUp ? up : down;
        const cx = x(i);
        ctx.strokeStyle = col; ctx.fillStyle = col; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(cx, y(d.h)); ctx.lineTo(cx, y(d.l)); ctx.stroke();
        const yo = y(d.o), yc = y(d.c);
        const top = Math.min(yo, yc), bh = Math.max(1.2, Math.abs(yc - yo));
        if (isUp) { ctx.fillStyle = col; ctx.fillRect(cx - cwid / 2, top, cwid, bh); }
        else { ctx.fillStyle = col; ctx.fillRect(cx - cwid / 2, top, cwid, bh); }
      });
    }

    // crosshair
    if (hover != null && data[hover]) {
      const hx = x(hover), d = data[hover];
      ctx.strokeStyle = dim; ctx.globalAlpha = 0.7; ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      ctx.beginPath(); ctx.moveTo(hx, padT); ctx.lineTo(hx, padT + chh); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(padL, y(d.c)); ctx.lineTo(padL + cw, y(d.c)); ctx.stroke();
      ctx.setLineDash([]); ctx.globalAlpha = 1;
      ctx.beginPath(); ctx.arc(hx, y(d.c), 3.5, 0, 7); ctx.fillStyle = chart; ctx.fill();
    }
  }, [data, type, height, showAxis, hover]);

  React.useEffect(() => { draw(); }, [draw]);
  React.useEffect(() => {
    const ro = new ResizeObserver(() => draw());
    if (wrapRef.current) ro.observe(wrapRef.current);
    const h = () => draw();
    window.addEventListener("sgs-theme", h);
    return () => { ro.disconnect(); window.removeEventListener("sgs-theme", h); };
  }, [draw]);

  const onMove = (e) => {
    if (!interactive) return;
    const wrap = wrapRef.current, rect = wrap.getBoundingClientRect();
    const px = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
    const padL = 6, padR = showAxis ? 52 : 6;
    const cw = rect.width - padL - padR;
    const i = Math.round(((px - padL) / cw) * (data.length - 1));
    setHover(Math.max(0, Math.min(data.length - 1, i)));
  };

  const hv = hover != null ? data[hover] : null;
  return (
    <div ref={wrapRef} style={{ position: "relative", width: "100%" }}
      onMouseMove={onMove} onMouseLeave={() => setHover(null)}
      onTouchStart={onMove} onTouchMove={onMove} onTouchEnd={() => setHover(null)}>
      <canvas ref={canvasRef} style={{ display: "block", width: "100%", touchAction: "none" }} />
      {hv && (
        <div style={{
          position: "absolute", top: 6, left: 8, display: "flex", gap: 10,
          fontFamily: "var(--font-mono)", fontSize: 9.5, color: "var(--muted)",
          background: "var(--bg2)", border: "1px solid var(--border)",
          borderRadius: "var(--radius-sm)", padding: "3px 7px", pointerEvents: "none",
        }}>
          <span>O <b style={{ color: "var(--text)" }}>{sgsFmtPrice(hv.o)}</b></span>
          <span>H <b style={{ color: "var(--up)" }}>{sgsFmtPrice(hv.h)}</b></span>
          <span>L <b style={{ color: "var(--down)" }}>{sgsFmtPrice(hv.l)}</b></span>
          <span>C <b style={{ color: "var(--text)" }}>{sgsFmtPrice(hv.c)}</b></span>
        </div>
      )}
    </div>
  );
}

/* compact sparkline for cards / rows */
function SGSSpark({ values, color = "var(--chart)", height = 28, fill = true, width = "100%" }) {
  const ref = React.useRef(null), wrap = React.useRef(null);
  const draw = React.useCallback(() => {
    const cv = ref.current, w = wrap.current; if (!cv || !w) return;
    const dpr = window.devicePixelRatio || 1, W = w.clientWidth, H = height;
    cv.width = W * dpr; cv.height = H * dpr; cv.style.width = W + "px"; cv.style.height = H + "px";
    const ctx = cv.getContext("2d"); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, W, H);
    const col = sgsCssVar(cv, color.replace("var(", "").replace(")", ""), color) || color;
    const c = color.startsWith("var") ? sgsCssVar(cv, color.slice(4, -1), "#5B8DEF") : color;
    const hi = Math.max(...values), lo = Math.min(...values), span = (hi - lo) || 1;
    const x = (i) => 2 + (i / (values.length - 1)) * (W - 4);
    const y = (v) => 3 + (1 - (v - lo) / span) * (H - 6);
    if (fill) {
      const g = ctx.createLinearGradient(0, 0, 0, H);
      g.addColorStop(0, sgsToRgba(c, 0.27)); g.addColorStop(1, sgsToRgba(c, 0));
      ctx.beginPath(); ctx.moveTo(x(0), y(values[0]));
      values.forEach((v, i) => ctx.lineTo(x(i), y(v)));
      ctx.lineTo(x(values.length - 1), H); ctx.lineTo(x(0), H); ctx.closePath();
      ctx.fillStyle = g; ctx.fill();
    }
    ctx.beginPath(); values.forEach((v, i) => (i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))));
    ctx.strokeStyle = c; ctx.lineWidth = 1.6; ctx.lineJoin = "round"; ctx.stroke();
  }, [values, color, height, fill]);
  React.useEffect(() => { draw(); }, [draw]);
  React.useEffect(() => {
    const ro = new ResizeObserver(() => draw());
    if (wrap.current) ro.observe(wrap.current);
    const h = () => draw();
    window.addEventListener("sgs-theme", h);
    return () => { ro.disconnect(); window.removeEventListener("sgs-theme", h); };
  }, [draw]);
  return <div ref={wrap} style={{ width, height }}><canvas ref={ref} style={{ display: "block" }} /></div>;
}

Object.assign(window, { SGSChart, SGSSpark });
