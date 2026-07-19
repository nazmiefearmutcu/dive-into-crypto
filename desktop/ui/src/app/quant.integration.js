global.window = global;
const Q = require("./quant.cjs");   // must load BEFORE data.js (data.js calls SGS_QUANT.decision)
require("./data.js");
const uni = global.SGS_DATA;
const dist = { confirm: 0, adverse: 0, neutral: 0 };
uni.forEach((d) => dist[d.whaleRegime]++);
console.log("regime distribution:", dist, "of", uni.length);
let correct = 0, dangerous = 0;
uni.forEach((d) => {
  const v = Q.divergence(d), dir = Q.indicatorDir(d);
  const expect = d.whaleRegime === "adverse" ? "ADVERSE" : d.whaleRegime === "confirm" ? "CONFIRM" : "NEUTRAL";
  const match = v.verdict === expect;
  if (match) correct++;
  // dangerous = adverse coin that the gate would KEEP, or confirm/neutral wrongly eliminated
  if (d.whaleRegime === "adverse" && !v.adverse) dangerous++;
  if (d.whaleRegime !== "adverse" && v.adverse) dangerous++;
  console.log(
    d.s.padEnd(9), "reg=" + d.whaleRegime.padEnd(8), "dir=" + (dir >= 0 ? "+" + dir : dir),
    "wf=" + v.wf.toFixed(2).padStart(5), "persist=" + v.persistence.toFixed(2),
    "=> " + v.verdict.padEnd(8), match ? "" : "  <-- soft-mismatch"
  );
});
console.log(`\nverdict↔regime recovery: ${correct}/${uni.length} | dangerous misclass (kept-adverse or false-elim): ${dangerous}`);
const res = Q.runScan(uni, { size: 5 });
console.log("\nrunScan size=5:");
console.log("  scanned markets:", res.scanned, "| universe:", res.universeCount, "| kept:", res.keptCount, "| eliminated:", res.eliminated.length);
console.log("  survivors:", res.survivors.map((x) => x.d.s.replace("USDT", "") + "(" + Math.round(x.score) + "/" + x.div.verdict[0] + ")").join("  "));
console.log("  eliminated:", res.eliminated.map((x) => x.d.s.replace("USDT", "")).join(", ") || "none");
