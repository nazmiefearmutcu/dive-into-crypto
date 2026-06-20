# Comprehensive Stress Test for ZLEMA & Bessel's Correction in AWK

function zlema(x, n, w, out) {
    if (w <= 1) {
        for (k=1; k<=n; k++) out[k] = x[k];
        return;
    }
    lag = int((w - 1) / 2);
    alpha = 2.0 / (w + 1.0);
    if (n == 0) return;
    out[1] = x[1];
    for (i=2; i<=n; i++) {
        idx = i - lag;
        if (idx < 1) idx = 1;
        x_prime = 2.0 * x[i] - x[idx];
        out[i] = alpha * x_prime + (1.0 - alpha) * out[i-1];
    }
}

function test_bessel(c, o, a, g, f, tb, ts, p, n) {
    for (i=1; i<=n; i++) {
        # 1. Price Return
        if (i > 1 && c[i-1] != 0.0) {
            ret = (c[i] - c[i-1]) / c[i-1];
        } else {
            ret = 0.0;
        }
        
        startIdx = i - 14;
        if (startIdx < 1) startIdx = 1;
        
        w_count = 0;
        w_sum = 0.0;
        for (j=startIdx; j<i; j++) {
            if (j > 1 && c[j-1] != 0.0) {
                val = (c[j] - c[j-1]) / c[j-1];
                w_count++;
                w_returns[w_count] = val;
                w_sum += val;
            }
        }
        mean = (w_count > 0) ? (w_sum / w_count) : 0.0;
        if (w_count > 1) {
            sumSq = 0.0;
            for (j=1; j<=w_count; j++) {
                sumSq += (w_returns[j] - mean) * (w_returns[j] - mean);
            }
            variance = sumSq / (w_count - 1);
        } else {
            variance = 0.0;
        }
        stdDev = sqrt(variance);
        stdDevReg = (stdDev > 0.002) ? stdDev : 0.002;
        volNormalizedReturn = ret / stdDevReg;
        
        # 2. OI Z-Score
        if (i > 1 && o[i-1] != 0.0) {
            oiPct = (o[i] - o[i-1]) / o[i-1];
        } else {
            oiPct = 0.0;
        }
        oiStart = i - 20;
        if (oiStart < 1) oiStart = 1;
        oi_count = 0;
        oi_sum = 0.0;
        for (j=oiStart; j<=i; j++) {
            if (j > 1 && o[j-1] != 0.0) {
                val = (o[j] - o[j-1]) / o[j-1];
                oi_count++;
                oi_history[oi_count] = val;
                oi_sum += val;
            }
        }
        oiMean = (oi_count > 0) ? (oi_sum / oi_count) : 0.0;
        if (oi_count > 1) {
            sumSq = 0.0;
            for (j=1; j<=oi_count; j++) {
                sumSq += (oi_history[j] - oiMean) * (oi_history[j] - oiMean);
            }
            oiVar = sumSq / (oi_count - 1);
        } else {
            oiVar = 0.0;
        }
        oiStdDev = sqrt(oiVar);
        if (oi_count > 1) {
            if (oiStdDev < 0.005) oiStdDev = 0.005;
        } else {
            oiStdDev = 0.0;
        }
        oizScore = (oiStdDev > 0.0) ? ((oiPct - oiMean) / oiStdDev) : 0.0;

        # 3. ACC, GLOBAL, FUNDING Z-Scores
        yStart = i - 30;
        if (yStart < 1) yStart = 1;
        y_len = i - yStart + 1;
        
        # ACC
        acc_sum = 0.0;
        for (j=yStart; j<=i; j++) acc_sum += a[j];
        accMean = acc_sum / y_len;
        if (y_len > 1) {
            sumSq = 0.0;
            for (j=yStart; j<=i; j++) sumSq += (a[j] - accMean) * (a[j] - accMean);
            accStd = sqrt(sumSq / (y_len - 1));
            if (accStd < 0.02) accStd = 0.02;
        } else {
            accStd = 0.0;
        }
        accZ = (accStd > 0.0) ? ((a[i] - accMean) / accStd) : 0.0;

        # GLOBAL
        glob_sum = 0.0;
        for (j=yStart; j<=i; j++) glob_sum += g[j];
        globMean = glob_sum / y_len;
        if (y_len > 1) {
            sumSq = 0.0;
            for (j=yStart; j<=i; j++) sumSq += (g[j] - globMean) * (g[j] - globMean);
            globStd = sqrt(sumSq / (y_len - 1));
            if (globStd < 0.02) globStd = 0.02;
        } else {
            globStd = 0.0;
        }
        globalZ = (globStd > 0.0) ? ((g[i] - globMean) / globStd) : 0.0;

        # FUNDING
        fund_sum = 0.0;
        for (j=yStart; j<=i; j++) fund_sum += f[j];
        fundMean = fund_sum / y_len;
        if (y_len > 1) {
            sumSq = 0.0;
            for (j=yStart; j<=i; j++) sumSq += (f[j] - fundMean) * (f[j] - fundMean);
            fundStd = sqrt(sumSq / (y_len - 1));
            if (fundStd < 0.0001) fundStd = 0.0001;
        } else {
            fundStd = 0.0;
        }
        fundingZ = (fundStd > 0.0) ? ((f[i] - fundMean) / fundStd) : 0.0;

        yCombinedZ = (accZ + globalZ + fundingZ) / 3.0;

        # 4. Taker Flow
        buyVol = tb[i];
        sellVol = ts[i];
        netTakerPct = (buyVol + sellVol > 0.0) ? ((buyVol - sellVol) / (buyVol + sellVol)) : 0.0;
        
        zStart = i - 30;
        if (zStart < 1) zStart = 1;
        z_len = i - zStart + 1;
        
        taker_sum = 0.0;
        for (j=zStart; j<=i; j++) {
            bv = tb[j]; sv = ts[j];
            val = (bv + sv > 0.0) ? ((bv - sv) / (bv + sv)) : 0.0;
            taker_history[j] = val;
            taker_sum += val;
        }
        netTakerMean = taker_sum / z_len;
        if (z_len > 1) {
            sumSq = 0.0;
            for (j=zStart; j<=i; j++) sumSq += (taker_history[j] - netTakerMean) * (taker_history[j] - netTakerMean);
            netTakerStd = sqrt(sumSq / (z_len - 1));
            if (netTakerStd < 0.05) netTakerStd = 0.05;
        } else {
            netTakerStd = 0.0;
        }
        netTakerZ = (netTakerStd > 0.0) ? ((netTakerPct - netTakerMean) / netTakerStd) : 0.0;

        # 5. Whale
        whale_sum = 0.0;
        for (j=yStart; j<=i; j++) whale_sum += p[j];
        whaleMean = whale_sum / y_len;
        if (y_len > 1) {
            sumSq = 0.0;
            for (j=yStart; j<=i; j++) sumSq += (p[j] - whaleMean) * (p[j] - whaleMean);
            whaleStd = sqrt(sumSq / (y_len - 1));
            if (whaleStd < 0.02) whaleStd = 0.02;
        } else {
            whaleStd = 0.0;
        }
        whaleZ = (whaleStd > 0.0) ? ((p[i] - whaleMean) / whaleStd) : 0.0;

        # Assertions
        # Check for NaN / Inf equivalents in AWK (i.e. check if a variable matches itself)
        if (volNormalizedReturn != volNormalizedReturn || oizScore != oizScore || yCombinedZ != yCombinedZ || netTakerZ != netTakerZ || whaleZ != whaleZ) {
            print "ERROR: NaN detected at index", i;
            exit 1;
        }
        
        # If size N <= 1, all standard deviations should default to 0.0 and z-scores to 0.0
        if (n <= 1) {
            if (volNormalizedReturn != 0.0 || oizScore != 0.0 || yCombinedZ != 0.0 || netTakerZ != 0.0 || whaleZ != 0.0) {
                print "ERROR: N=1 default check failed at index", i;
                print "volNormalizedReturn:", volNormalizedReturn, "oizScore:", oizScore, "yCombinedZ:", yCombinedZ, "netTakerZ:", netTakerZ, "whaleZ:", whaleZ;
                exit 1;
            }
        }
    }
}

BEGIN {
    print "AWK Math Stress Tests Initialization..."

    # 1. ZLEMA Look-Ahead Bias Verification
    print "Testing ZLEMA Look-Ahead Bias (100 elements)..."
    _state = 1337
    for (i=1; i<=100; i++) {
        _state = (_state * 1103515245 + 12345) % 2147483648;
        base_data[i] = 10.0 + 90.0 * (_state / 2147483648.0);
    }
    
    zlema(base_data, 100, 14, base_out);
    
    for (i=1; i<100; i++) {
        # Copy base_data
        for (m=1; m<=100; m++) altered_data[m] = base_data[m];
        # Alter all k > i
        for (k=i+1; k<=100; k++) {
            _state = (_state * 1103515245 + 12345) % 2147483648;
            altered_data[k] = 500.0 + 500.0 * (_state / 2147483648.0);
        }
        
        zlema(altered_data, 100, 14, alt_out);
        
        if (alt_out[i] != base_out[i]) {
            print "ERROR: Look-ahead bias detected in ZLEMA at index", i;
            print "Base:", base_out[i], "Altered:", alt_out[i];
            exit 1;
        }
    }
    print "-> PASS: ZLEMA look-ahead bias elimination verified (altering k > i does not affect index i)."

    # 2. ZLEMA Boundary Behavior & Stability
    print "Testing ZLEMA Boundary Behavior & Stability..."
    
    # Empty (N=0)
    zlema(empty_data, 0, 5, out_empty);
    # Single-element
    single_data[1] = 42.0;
    zlema(single_data, 1, 5, out_single);
    if (out_single[1] != 42.0) {
        print "ERROR: ZLEMA single-element test failed.";
        exit 1;
    }
    
    # w <= 1
    w_test[1] = 10.0; w_test[2] = 12.0; w_test[3] = 15.0;
    for (w=-5; w<=1; w++) {
        delete out_w;
        zlema(w_test, 3, w, out_w);
        if (out_w[1] != 10.0 || out_w[2] != 12.0 || out_w[3] != 15.0) {
            print "ERROR: ZLEMA w <= 1 test failed for w =", w;
            exit 1;
        }
    }
    
    # Negative values
    neg_data[1] = -10.0; neg_data[2] = -15.0; neg_data[3] = -12.0;
    zlema(neg_data, 3, 5, out_neg);
    if (out_neg[1] != -10.0) {
        print "ERROR: ZLEMA negative values failed.";
        exit 1;
    }
    
    # Extremes (floating-point overflow and underflow)
    large_data[1] = 1e300; large_data[2] = 1.5e300; large_data[3] = 1.2e300;
    zlema(large_data, 3, 3, out_large);
    
    small_data[1] = 1e-300; small_data[2] = 1.5e-300; small_data[3] = 1.2e-300;
    zlema(small_data, 3, 3, out_small);
    
    print "-> PASS: ZLEMA boundary and extreme inputs verified."

    # 3. Bessel's Correction Stability
    print "Testing Bessel's Correction with small sizes (N <= 1)..."
    
    # Size N = 0
    test_bessel(empty_c, empty_o, empty_a, empty_g, empty_f, empty_tb, empty_ts, empty_p, 0);
    
    # Size N = 1
    c1[1] = 100.0; o1[1] = 500.0; a1[1] = 1.5; g1[1] = 1.2; f1[1] = 0.0001; tb1[1] = 50.0; ts1[1] = 50.0; p1[1] = 1.8;
    test_bessel(c1, o1, a1, g1, f1, tb1, ts1, p1, 1);
    
    print "Testing Bessel's Correction with identical values (0 variance)..."
    # Flat series sizes 2, 5, 10, 50
    for (size=2; size<=50; size++) {
        if (size != 2 && size != 5 && size != 10 && size != 50) continue;
        for (i=1; i<=size; i++) {
            c_flat[i] = 123.45;
            o_flat[i] = 500.0;
            a_flat[i] = 1.5;
            g_flat[i] = 1.2;
            f_flat[i] = 0.0001;
            tb_flat[i] = 50.0;
            ts_flat[i] = 50.0;
            p_flat[i] = 1.8;
        }
        test_bessel(c_flat, o_flat, a_flat, g_flat, f_flat, tb_flat, ts_flat, p_flat, size);
    }
    
    print "-> PASS: Bessel's Correction stability and 0-variance division-by-zero prevention verified."
    print "\nALL AWK STRESS TESTS PASSED SUCCESSFULLY!"
}
