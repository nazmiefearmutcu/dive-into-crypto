/**
 * Trading Bot Dashboard - Auto-refresh logic.
 * Uses AJAX on dashboard page (no full reload), full reload on other pages.
 */

(function () {
    "use strict";

    var REFRESH_INTERVAL = 5; // seconds
    var countdown = REFRESH_INTERVAL;
    var countdownEl = document.getElementById("refresh-countdown");
    var timer = null;

    // Pages that use AJAX updates — no full reload
    var path = window.location.pathname;
    var isDashboard = path === "/" || path === "";
    var isScan = path === "/scan";
    var isAjaxPage = isDashboard || isScan;

    function tick() {
        // Pause auto-refresh while scanner is running
        if (window._scannerActive) {
            if (countdownEl) countdownEl.textContent = "scan";
            countdown = REFRESH_INTERVAL;
            return;
        }
        countdown--;
        if (countdownEl) {
            countdownEl.textContent = countdown;
        }
        if (countdown <= 0) {
            countdown = REFRESH_INTERVAL;
            if (isDashboard) {
                refreshDashboard();
            } else if (isScan) {
                // Scanner page: no reload, JS polling handles updates
            } else {
                window.location.reload();
            }
        }
    }

    function refreshDashboard() {
        fetch("/api/status")
            .then(function (r) { return r.json(); })
            .then(function (s) {
                // Update last-update time
                var luEl = document.getElementById("last-update");
                if (luEl) {
                    var lu = s.last_update;
                    if (lu) {
                        var ago = Math.round((Date.now() - new Date(lu).getTime()) / 1000);
                        var agoText = ago < 60 ? ago + "s ago" : Math.floor(ago/60) + "m ago";
                        luEl.textContent = "Last update: " + agoText;
                    }
                }

                // Price
                var priceEl = document.getElementById("live-price");
                if (priceEl && s.latest_decision) {
                    priceEl.textContent = "$" + Number(s.latest_decision.price || 0).toFixed(4);
                }

                // Symbol
                var symEl = document.getElementById("live-symbol");
                if (symEl) symEl.textContent = s.active_symbol || "";

                // Balance
                var balEl = document.getElementById("live-balance");
                if (balEl) balEl.textContent = "$" + Number(s.balance || 0).toFixed(2);

                // Daily PnL
                var dpEl = document.getElementById("live-daily-pnl");
                if (dpEl) {
                    var dp = s.daily_pnl || 0;
                    dpEl.textContent = "$" + Number(dp).toFixed(4);
                    dpEl.className = dp >= 0 ? "positive" : "negative";
                }

                // Total PnL
                var tpEl = document.getElementById("live-total-pnl");
                if (tpEl) {
                    var tp = s.total_pnl || 0;
                    tpEl.textContent = "$" + Number(tp).toFixed(4);
                    tpEl.className = tp >= 0 ? "positive" : "negative";
                }

                // Unrealized PnL
                var upEl = document.getElementById("live-unrealized-pnl");
                if (upEl) {
                    var up = s.unrealized_pnl || 0;
                    upEl.textContent = "$" + Number(up).toFixed(4);
                    upEl.className = up >= 0 ? "positive" : "negative";
                }

                // Open positions count
                var posEl = document.getElementById("live-positions");
                if (posEl) posEl.textContent = s.open_positions_count || 0;

                // Cycle count
                var cycleEl = document.getElementById("live-cycle");
                if (cycleEl) cycleEl.textContent = "#" + (s.cycle_count || 0);

                // Leverage
                var levEl = document.getElementById("live-leverage");
                if (levEl && s.latest_decision) levEl.textContent = (s.latest_decision.leverage || 1) + "x";

                // Signal
                var dec = s.latest_decision || {};
                var sigEl = document.getElementById("live-signal");
                if (sigEl) {
                    sigEl.textContent = dec.signal || "N/A";
                    sigEl.className = "signal-badge signal-" + (dec.signal || "neutral").toLowerCase();
                }

                // Confidence
                var confEl = document.getElementById("live-confidence");
                if (confEl) confEl.textContent = (dec.confidence || 0) + "%";
                var confBar = document.getElementById("live-conf-bar");
                if (confBar) confBar.style.width = (dec.confidence || 0) + "%";

                // Risk
                var riskEl = document.getElementById("live-risk");
                if (riskEl) {
                    var rl = dec.risk_level || "N/A";
                    riskEl.textContent = rl;
                    riskEl.className = "risk-badge risk-" + rl.toLowerCase();
                }

                // Action
                var actEl = document.getElementById("live-action");
                if (actEl) actEl.textContent = dec.action || "N/A";

                // Reason
                var reasonEl = document.getElementById("live-reason");
                if (reasonEl) reasonEl.textContent = dec.reason || "";

                // Signal distribution
                var dist = s.signal_distribution || {};
                var total = (dist.buy || 0) + (dist.sell || 0) + (dist.neutral || 0) || 1;
                var dbuy = document.getElementById("live-dist-buy");
                var dneu = document.getElementById("live-dist-neutral");
                var dsell = document.getElementById("live-dist-sell");
                if (dbuy) { dbuy.style.width = Math.round((dist.buy||0)/total*100)+"%"; dbuy.textContent = "BUY " + (dist.buy||0); }
                if (dneu) { dneu.style.width = Math.round((dist.neutral||0)/total*100)+"%"; dneu.textContent = "NEUTRAL " + (dist.neutral||0); }
                if (dsell) { dsell.style.width = Math.round((dist.sell||0)/total*100)+"%"; dsell.textContent = "SELL " + (dist.sell||0); }
            })
            .catch(function () {});
    }

    function startAutoRefresh() {
        countdown = REFRESH_INTERVAL;
        if (countdownEl) countdownEl.textContent = countdown;
        timer = setInterval(tick, 1000);
    }

    // Don't auto-refresh on settings page
    if (window.location.pathname === "/settings") {
        if (countdownEl) countdownEl.textContent = "off";
        return;
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", startAutoRefresh);
    } else {
        startAutoRefresh();
    }
})();
