import pytest
import math
import random
from diveintocrypto_desktop.scan import divergence as dv

# Replicated Kotlin ZLEMA logic exactly as in WhaleDivergence.kt:
# internal fun zlema(x: DoubleArray, w: int): DoubleArray
def kotlin_zlema(x: list[float], w: int) -> list[float]:
    if w <= 1:
        return list(x)
    lag = (w - 1) // 2
    alpha = 2.0 / (w + 1.0)
    out = [0.0] * len(x)
    if not x:
        return out
    out[0] = x[0]
    for i in range(len(x)):
        if i == 0:
            continue
        x_prime = 2.0 * x[i] - x[max(0, i - lag)]
        out[i] = alpha * x_prime + (1.0 - alpha) * out[i - 1]
    return out

# Replicated Kotlin ConsensusEngine evaluateMultimodal calculations exactly as in ConsensusEngine.kt:
def calc_multimodal_std_dev_variance_z_scores(
    candles_close: list[float],
    oi_values: list[float],
    acc_ratios: list[float],
    global_ratios: list[float],
    funding_rates: list[float],
    taker_buy_vols: list[float],
    taker_sell_vols: list[float],
    pos_ratios: list[float]
):
    n = len(candles_close)
    results = []
    
    # Check aligned sizes
    assert len(oi_values) == n
    assert len(acc_ratios) == n
    assert len(global_ratios) == n
    assert len(funding_rates) == n
    assert len(taker_buy_vols) == n
    assert len(taker_sell_vols) == n
    assert len(pos_ratios) == n

    for i in range(n):
        # 1. Price Vol-Normalized Return
        ret = (candles_close[i] - candles_close[i-1]) / candles_close[i-1] if (i > 0 and candles_close[i-1] != 0.0) else 0.0
        startIdx = max(0, i - 14)
        windowReturns = []
        for j in range(startIdx, i):
            if j > 0 and candles_close[j-1] != 0.0:
                windowReturns.append((candles_close[j] - candles_close[j-1]) / candles_close[j-1])
        
        mean = sum(windowReturns) / len(windowReturns) if windowReturns else 0.0
        # Bessel's Correction: denominator is size - 1
        variance = sum((x - mean) ** 2 for x in windowReturns) / (len(windowReturns) - 1) if len(windowReturns) > 1 else 0.0
        stdDev = math.sqrt(variance)
        stdDevRegularized = max(stdDev, 0.002) # coerceAtLeast(0.002)
        volNormalizedReturn = ret / stdDevRegularized
        
        # 2. OI Z-Score
        oiPct = (oi_values[i] - oi_values[i-1]) / oi_values[i-1] if (i > 0 and oi_values[i-1] != 0.0) else 0.0
        oiStart = max(0, i - 20)
        oiHistory = []
        for j in range(oiStart, i + 1):
            if j > 0 and oi_values[j-1] != 0.0:
                oiHistory.append((oi_values[j] - oi_values[j-1]) / oi_values[j-1])
        
        oiMean = sum(oiHistory) / len(oiHistory) if oiHistory else 0.0
        oiVar = sum((x - oiMean) ** 2 for x in oiHistory) / (len(oiHistory) - 1) if len(oiHistory) > 1 else 0.0
        oiStdDev = max(math.sqrt(oiVar), 0.005) if len(oiHistory) > 1 else 0.0
        oizScore = (oiPct - oiMean) / oiStdDev if oiStdDev > 0.0 else 0.0
        
        # 3. ACC, GLOBAL, FUNDING Z-Scores
        yStart = max(0, i - 30)
        accHistory = acc_ratios[yStart:i+1]
        globalHistory = global_ratios[yStart:i+1]
        fundingHistory = funding_rates[yStart:i+1]
        
        accMean = sum(accHistory) / len(accHistory) if accHistory else 0.0
        accStd = max(math.sqrt(sum((x - accMean) ** 2 for x in accHistory) / (len(accHistory) - 1)), 0.02) if len(accHistory) > 1 else 0.0
        accZ = (acc_ratios[i] - accMean) / accStd if accStd > 0.0 else 0.0
        
        globalMean = sum(globalHistory) / len(globalHistory) if globalHistory else 0.0
        globalStd = max(math.sqrt(sum((x - globalMean) ** 2 for x in globalHistory) / (len(globalHistory) - 1)), 0.02) if len(globalHistory) > 1 else 0.0
        globalZ = (global_ratios[i] - globalMean) / globalStd if globalStd > 0.0 else 0.0
        
        fundingMean = sum(fundingHistory) / len(fundingHistory) if fundingHistory else 0.0
        fundingStd = max(math.sqrt(sum((x - fundingMean) ** 2 for x in fundingHistory) / (len(fundingHistory) - 1)), 0.0001) if len(fundingHistory) > 1 else 0.0
        fundingZ = (funding_rates[i] - fundingMean) / fundingStd if fundingStd > 0.0 else 0.0
        
        yCombinedZ = (accZ + globalZ + fundingZ) / 3.0
        
        # 4. Net Taker Z-Score
        buyVol = taker_buy_vols[i]
        sellVol = taker_sell_vols[i]
        netTakerPct = (buyVol - sellVol) / (buyVol + sellVol) if (buyVol + sellVol > 0.0) else 0.0
        
        zStart = max(0, i - 30)
        netTakerHistory = []
        for pt_buy, pt_sell in zip(taker_buy_vols[zStart:i+1], taker_sell_vols[zStart:i+1]):
            if pt_buy + pt_sell > 0.0:
                netTakerHistory.append((pt_buy - pt_sell) / (pt_buy + pt_sell))
            else:
                netTakerHistory.append(0.0)
                
        netTakerMean = sum(netTakerHistory) / len(netTakerHistory) if netTakerHistory else 0.0
        netTakerStd = max(math.sqrt(sum((x - netTakerMean) ** 2 for x in netTakerHistory) / (len(netTakerHistory) - 1)), 0.05) if len(netTakerHistory) > 1 else 0.0
        netTakerZ = (netTakerPct - netTakerMean) / netTakerStd if netTakerStd > 0.0 else 0.0
        
        # 5. Whale Z-Score
        whaleHistory = pos_ratios[yStart:i+1]
        whaleMean = sum(whaleHistory) / len(whaleHistory) if whaleHistory else 0.0
        whaleStd = max(math.sqrt(sum((x - whaleMean) ** 2 for x in whaleHistory) / (len(whaleHistory) - 1)), 0.02) if len(whaleHistory) > 1 else 0.0
        whaleZ = (pos_ratios[i] - whaleMean) / whaleStd if whaleStd > 0.0 else 0.0
        
        results.append({
            "index": i,
            "volNormalizedReturn": volNormalizedReturn,
            "oizScore": oizScore,
            "yCombinedZ": yCombinedZ,
            "netTakerZ": netTakerZ,
            "whaleZ": whaleZ
        })
    return results


# ==============================================================================
# SECTION 1: Look-Ahead Bias / Causality Elimination Verification
# ==============================================================================

def test_zlema_look_ahead_bias_python():
    """Verify look-ahead bias elimination in Python ZLEMA:
    Altering any input values at indices k > i does not change the ZLEMA output at index i.
    """
    random.seed(42)
    # Generate 100 random price points
    base_data = [random.uniform(10.0, 100.0) for _ in range(100)]
    w = 14
    
    # Compute base ZLEMA
    base_output = dv._zlema(base_data, w)
    
    # Test for every index i
    for i in range(len(base_data) - 1):
        # Alter the input at indices k > i
        altered_data = list(base_data)
        for k in range(i + 1, len(base_data)):
            altered_data[k] = random.uniform(1000.0, 5000.0)
            
        altered_output = dv._zlema(altered_data, w)
        
        # Assert that the value at index i is exactly identical (no look-ahead bias)
        assert altered_output[i] == base_output[i], f"Look-ahead bias detected at index {i} with k > {i} changed."


def test_zlema_look_ahead_bias_kotlin():
    """Verify look-ahead bias elimination in Kotlin ZLEMA:
    Altering any input values at indices k > i does not change the ZLEMA output at index i.
    """
    random.seed(42)
    # Generate 100 random price points
    base_data = [random.uniform(10.0, 100.0) for _ in range(100)]
    w = 14
    
    # Compute base ZLEMA
    base_output = kotlin_zlema(base_data, w)
    
    # Test for every index i
    for i in range(len(base_data) - 1):
        # Alter the input at indices k > i
        altered_data = list(base_data)
        for k in range(i + 1, len(base_data)):
            altered_data[k] = random.uniform(1000.0, 5000.0)
            
        altered_output = kotlin_zlema(altered_data, w)
        
        # Assert that the value at index i is exactly identical (no look-ahead bias)
        assert altered_output[i] == base_output[i], f"Look-ahead bias detected in Kotlin ZLEMA at index {i} with k > {i} changed."


# ==============================================================================
# SECTION 2: ZLEMA Boundary Behavior & Stability Verification
# ==============================================================================

@pytest.mark.parametrize("zlema_func", [dv._zlema, kotlin_zlema])
def test_zlema_boundary_windows(zlema_func):
    """Test ZLEMA with window w <= 1 (w = 1, w = 0, w = -5).
    Should handle gracefully without throwing exception, and act as a no-op or copy of input.
    """
    data = [10.0, 15.0, 12.0, 18.0, 20.0]
    
    # w = 1
    assert zlema_func(data, 1) == data
    
    # w = 0
    assert zlema_func(data, 0) == data
    
    # w = -5
    assert zlema_func(data, -5) == data


@pytest.mark.parametrize("zlema_func", [dv._zlema, kotlin_zlema])
def test_zlema_empty_and_single_element(zlema_func):
    """Test ZLEMA with empty input arrays and single-element arrays."""
    # Empty
    assert zlema_func([], 5) == []
    
    # Single element
    assert zlema_func([42.0], 5) == [42.0]


@pytest.mark.parametrize("zlema_func", [dv._zlema, kotlin_zlema])
def test_zlema_negative_values(zlema_func):
    """Test ZLEMA with negative input values. Should execute cleanly."""
    data = [-10.0, -15.0, -12.0, -18.0, -20.0]
    out = zlema_func(data, 5)
    assert len(out) == 5
    assert all(isinstance(v, float) for v in out)


@pytest.mark.parametrize("zlema_func", [dv._zlema, kotlin_zlema])
def test_zlema_floating_point_extremes(zlema_func):
    """Test ZLEMA under extreme floating-point inputs (overflow, underflow, NaN, Inf).
    Ensure the functions themselves run without crashes/exceptions.
    Note: Inputs containing NaN/Inf are sanitized at high level (e.g. _sanitize in Python),
    but the raw math function must not throw exception.
    """
    # Overflows / large numbers
    data_large = [1e308, 1.5e308, 1.2e308]
    out_large = zlema_func(data_large, 3)
    assert len(out_large) == 3
    # Check if underflow/extremely small numbers work
    data_small = [1e-308, 1.5e-308, 1.2e-308]
    out_small = zlema_func(data_small, 3)
    assert len(out_small) == 3
    
    # Test that no NaN/Inf input causes internal crashes (e.g., if NaN values bypass sanitization)
    data_nan = [float('nan'), float('inf'), -float('inf')]
    out_nan = zlema_func(data_nan, 3)
    assert len(out_nan) == 3


# ==============================================================================
# SECTION 3: Bessel's Correction Stability Verification
# ==============================================================================

def test_bessel_small_sizes():
    """Verify that ConsensusEngine standard deviation / variance calculations with N <= 1
    do not throw division by zero or NaN, and return stable output (0.0 or regularized values).
    """
    # Test size N = 0
    results_0 = calc_multimodal_std_dev_variance_z_scores([], [], [], [], [], [], [], [])
    assert results_0 == []
    
    # Test size N = 1
    results_1 = calc_multimodal_std_dev_variance_z_scores(
        candles_close=[100.0],
        oi_values=[500.0],
        acc_ratios=[1.5],
        global_ratios=[1.2],
        funding_rates=[0.0001],
        taker_buy_vols=[50.0],
        taker_sell_vols=[50.0],
        pos_ratios=[1.8]
    )
    assert len(results_1) == 1
    # Check that they returned stable outputs (no nan or infinite)
    res = results_1[0]
    for key, val in res.items():
        if key != "index":
            assert not math.isnan(val), f"{key} should not be NaN for N=1"
            assert not math.isinf(val), f"{key} should not be Inf for N=1"
            # Since N=1, variance of window returns / histories has size <= 1, so stdDev/variance defaults to 0.0,
            # resulting in z-scores defaulting to 0.0. Let's verify this:
            assert val == 0.0, f"{key} should be exactly 0.0 for N=1"


def test_bessel_identical_values_zero_variance():
    """Test with identical values (0 variance) to check if any Z-score calculation
    causes division-by-zero.
    """
    size = 10
    # Create identical inputs (flat series)
    candles_close = [100.0] * size
    oi_values = [500.0] * size
    acc_ratios = [1.5] * size
    global_ratios = [1.2] * size
    funding_rates = [0.0001] * size
    taker_buy_vols = [50.0] * size
    taker_sell_vols = [50.0] * size
    pos_ratios = [1.8] * size

    results = calc_multimodal_std_dev_variance_z_scores(
        candles_close=candles_close,
        oi_values=oi_values,
        acc_ratios=acc_ratios,
        global_ratios=global_ratios,
        funding_rates=funding_rates,
        taker_buy_vols=taker_buy_vols,
        taker_sell_vols=taker_sell_vols,
        pos_ratios=pos_ratios
    )
    
    assert len(results) == size
    for res in results:
        for key, val in res.items():
            if key != "index":
                assert not math.isnan(val), f"{key} should not be NaN for flat inputs"
                assert not math.isinf(val), f"{key} should not be Inf for flat inputs"
                # Volnormalized return should be 0.0 because ret is 0.0, and stdDevRegularized is 0.002
                # oizScore should be 0.0 because oiPct is 0.0, and oiStdDev is 0.005
                # accZ, globalZ, fundingZ, netTakerZ, whaleZ should be 0.0 because:
                # - at index 0: history size 1 => std is 0.0 => Z-score is 0.0
                # - at index > 0: history size > 1, all values identical => variance is 0.0 => std is regularized to minimum (e.g., 0.02, 0.02, 0.0001, 0.05, 0.02) => numerator is 0.0 (value - mean = 0.0) => Z-score is 0.0
                assert abs(val) < 1e-9, f"{key} should be close to 0.0 for flat inputs, got {val}"
