/* Offline demo market — populates SGS_DATA_MAP / SGS_SCAN with the REAL contract
   shape (assemble() output) so the UI renders without the backend. Activated by
   window.DIVE_MOCK() when a live fetch fails. No effect when the backend is up. */
(function(){
  const TFS=["1m","3m","5m","15m","30m","1h","2h","4h","6h","8h","12h","1d"];
  const NAMES="ema_cross sma_cross macd ichimoku psar adx_di supertrend vortex aroon_oscillator schaff_trend_cycle trix kst coppock_curve kalman_trend donchian_breakout keltner_breakout elder_ray roc awesome_oscillator relative_vigor_index cmo tsi qstick rsi stochastic williams_r cci connors_rsi stoch_rsi ultimate_oscillator fisher_transform wavetrend obv mfi cmf vwap chaikin_oscillator klinger_oscillator accum_dist_line force_index vwma_cross bollinger bollinger_percent_b squeeze choppiness atr_filter atr_percentile hist_vol_percentile mass_index range_expansion hurst balance_of_power zscore_reversion linreg_slope half_life_reversion rolling_sharpe".split(" ");
  const SIGS=["STRONG_BUY","BUY","NEUTRAL","SELL","STRONG_SELL"];
  function rng(seed){let s=seed>>>0;return()=>{s=(s*1664525+1013904223)>>>0;return s/4294967296;};}
  function multiTf(r,bias){return TFS.map((tf,i)=>{const v=Math.sin(i*1.3+bias)+bias*0.4+(r()-0.5)*0.4;
    const sig=v>0.35?"BUY":v<-0.35?"SELL":"NEUTRAL";return{tf,signal:sig,confidence:Math.min(98,Math.round(Math.abs(v)*46)+42)};});}
  function inds(r,bias){return NAMES.map(name=>{const v=Math.sin((name.length)*0.7+bias)+(r()-0.5)*1.4;
    const sig=v>0.9?"STRONG_BUY":v>0.25?"BUY":v<-0.9?"STRONG_SELL":v<-0.25?"SELL":"NEUTRAL";
    return{name,signal:sig,weight:1.2,value:+(v*20).toFixed(2)};});}
  function sym(s,name,price,ch,sig,conf,risk,net,bias,wr,ms,rg,mtf,reason){const r=rng(s.length*97+Math.round(price));
    return{s,name,price,ch,finalSignal:sig,confidence:conf,risk,netNss:net,quantBias:net>0?42.1:-38.5,
      multiTf:multiTf(r,bias),indicators:inds(r,bias),whaleRegime:wr,
      divergence:{score:wr==="adverse"?-61.2:wr==="confirm"?58.4:12.1,tf:"4h",coverage:wr==="neutral"?1:3},
      microstructure:ms,regime:rg,mtfConfluence:mtf,reason};}

  const M={
   BTCUSDT:sym("BTCUSDT","BITCOIN",68240.5,2.14,"STRONG_BUY",84,"DÜŞÜK",1820,1.1,"confirm",
     {score:53.4,direction:1,label:"BUY",active:6,signals:[{name:"oi_price_divergence",score:.42},{name:"oi_breakout_confirm",score:.61},{name:"funding_fade",score:-.18},{name:"taker_aggression",score:.55},{name:"ls_crowding_fade",score:-.12},{name:"smart_dumb_spread",score:.33}]},
     {regime:"TREND",adx:31.4,chop:29.8,adaptive_score:0.94},{score:71.2,direction:1,gate:true,htf_agree:0.86,label:"STRONG"},
     "Balina akışı indikatör yönünü teyit ediyor (WF +58.4). Yüksek TF yığını hizalı."),
   WIFUSDT:sym("WIFUSDT","DOGWIFHAT",2.884,8.11,"STRONG_BUY",76,"YÜKSEK",1990,1.2,"confirm",
     {score:59,direction:1,label:"STRONG_BUY",active:6,signals:[{name:"oi_price_divergence",score:.5},{name:"oi_breakout_confirm",score:.8},{name:"funding_fade",score:-.3},{name:"taker_aggression",score:.72},{name:"ls_crowding_fade",score:-.05},{name:"smart_dumb_spread",score:.55}]},
     {regime:"TREND",adx:34,chop:27,adaptive_score:1.12},{score:78,direction:1,gate:true,htf_agree:0.9,label:"STRONG"},
     "Momentum patlaması: OI + taker + smart-money hepsi long. Yüksek volatilite riski."),
   AVAXUSDT:sym("AVAXUSDT","AVALANCHE",38.91,4.72,"STRONG_BUY",79,"DÜŞÜK",1510,1.0,"confirm",
     {score:48.1,direction:1,label:"BUY",active:6,signals:[{name:"oi_price_divergence",score:.38},{name:"oi_breakout_confirm",score:.72},{name:"taker_aggression",score:.6},{name:"smart_dumb_spread",score:.41}]},
     {regime:"TREND",adx:28.9,chop:33.1,adaptive_score:0.88},{score:64,direction:1,gate:true,htf_agree:0.83,label:"STRONG"},
     "OI genişlemesi kırılımı teyit ediyor. Taker alım baskısı güçlü."),
   ETHUSDT:sym("ETHUSDT","ETHEREUM",3512.8,1.05,"BUY",67,"ORTA",940,0.6,"neutral",
     {score:18.9,direction:1,label:"NEUTRAL",active:5,signals:[{name:"oi_price_divergence",score:.12},{name:"funding_fade",score:-.31},{name:"taker_aggression",score:.28},{name:"smart_dumb_spread",score:.19}]},
     {regime:"MIXED",adx:22.1,chop:48,adaptive_score:0.41},{score:33,direction:1,gate:false,htf_agree:0.5,label:"WEAK"},
     "Belirgin balina uyumsuzluğu yok. Orta rejim: trend teyidi zayıf."),
   LINKUSDT:sym("LINKUSDT","CHAINLINK",17.84,2.9,"BUY",64,"DÜŞÜK",870,0.5,"confirm",
     {score:31.2,direction:1,label:"BUY",active:6,signals:[{name:"oi_price_divergence",score:.28},{name:"oi_breakout_confirm",score:.35},{name:"taker_aggression",score:.4},{name:"smart_dumb_spread",score:.22}]},
     {regime:"TREND",adx:26,chop:36.5,adaptive_score:0.66},{score:52,direction:1,gate:true,htf_agree:0.75,label:"WEAK"},
     "Balina teyidi + orta güçte trend. Düşük risk."),
   ARBUSDT:sym("ARBUSDT","ARBITRUM",0.9124,-0.62,"SELL",58,"ORTA",-680,-0.5,"neutral",
     {score:-22.4,direction:-1,label:"SELL",active:5,signals:[{name:"oi_price_divergence",score:-.2},{name:"funding_fade",score:.25},{name:"taker_aggression",score:-.3},{name:"smart_dumb_spread",score:-.14}]},
     {regime:"RANGE",adx:17.5,chop:62,adaptive_score:-0.34},{score:-40,direction:-1,gate:false,htf_agree:0.55,label:"WEAK"},
     "Zayıf satış. Aralık rejimi: osilatörlere ağırlık verildi."),
   SOLUSDT:sym("SOLUSDT","SOLANA",171.42,-3.28,"SELL",71,"ORTA",-1120,-0.7,"adverse",
     {score:-47.7,direction:-1,label:"SELL",active:6,signals:[{name:"oi_price_divergence",score:-.55},{name:"oi_breakout_confirm",score:-.4},{name:"funding_fade",score:.6},{name:"taker_aggression",score:-.44},{name:"ls_crowding_fade",score:.5},{name:"smart_dumb_spread",score:-.28}]},
     {regime:"RANGE",adx:16.2,chop:66.4,adaptive_score:-0.58},{score:-52.4,direction:-1,gate:true,htf_agree:0.71,label:"WEAK"},
     "İndikatör yönüne karşı balina akışı (WF -61.2). Kalabalık long → fade."),
   SUIUSDT:sym("SUIUSDT","SUI",4.118,-1.94,"NEUTRAL",41,"YÜKSEK",120,-0.1,"neutral",
     {score:-8.2,direction:0,label:"NEUTRAL",active:4,signals:[{name:"oi_price_divergence",score:-.08},{name:"funding_fade",score:.14},{name:"taker_aggression",score:-.11}]},
     {regime:"MIXED",adx:19,chop:55.2,adaptive_score:-0.06},{score:8,direction:1,gate:false,htf_agree:0.4,label:"NEUTRAL"},
     "Çatışan sinyaller nötrlüğe zorluyor. Yüksek risk."),
  };
  window.DIVE_MOCK=function(){
    const order=["WIFUSDT","BTCUSDT","AVAXUSDT","LINKUSDT","ETHUSDT","ARBUSDT","SOLUSDT","SUIUSDT"];
    Object.keys(M).forEach(k=>{window.SGS_DATA_MAP[k]=M[k];});
    window.SGS_DATA=order.map(k=>M[k]);
    window.SGS_SCAN={survivors:order.map((k,i)=>({d:M[k],score:M[k].netNss,rank:i+1})),eliminated:[],scanned:60,universeCount:437};
    window.SGS_LOGS=[{t:"12:09:44",msg:"GET /fapi/v1/klines BTCUSDT 1h → 200 (300 mum)"},{t:"12:09:44",msg:"GET /futures/data/openInterestHist → 200"},{t:"12:09:43",msg:"scan(15,24) → 8 survivor / 437 evren"}];
    if(typeof window.__diveOnData==="function") window.__diveOnData();
  };
})();
