import random
from diveintocrypto_desktop.scan import divergence as dv
from diveintocrypto_desktop.scan import structure as st

# Every check here drives production code. The Kotlin ZLEMA and multimodal z-score
# replicas that used to live in this file only tested their own copies, so they are
# gone; the Kotlin side has its own unit tests in android/app/src/commonTest.

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


# ==============================================================================
# SECTION 2: ZLEMA Boundary Behavior & Stability Verification
# ==============================================================================

def test_zlema_boundary_windows():
    """Test ZLEMA with window w <= 1 (w = 1, w = 0, w = -5).
    Should handle gracefully without throwing exception, and act as a no-op or copy of input.
    """
    data = [10.0, 15.0, 12.0, 18.0, 20.0]

    # w = 1
    assert dv._zlema(data, 1) == data

    # w = 0
    assert dv._zlema(data, 0) == data

    # w = -5
    assert dv._zlema(data, -5) == data


def test_zlema_empty_and_single_element():
    """Test ZLEMA with empty input arrays and single-element arrays."""
    # Empty
    assert dv._zlema([], 5) == []

    # Single element
    assert dv._zlema([42.0], 5) == [42.0]


def test_zlema_negative_values():
    """Test ZLEMA with negative input values. Should execute cleanly."""
    data = [-10.0, -15.0, -12.0, -18.0, -20.0]
    out = dv._zlema(data, 5)
    assert len(out) == 5
    assert all(isinstance(v, float) for v in out)


def test_zlema_floating_point_extremes():
    """Test ZLEMA under extreme floating-point inputs (overflow, underflow, NaN, Inf).
    Ensure the functions themselves run without crashes/exceptions.
    Note: Inputs containing NaN/Inf are sanitized at high level (e.g. _sanitize in Python),
    but the raw math function must not throw exception.
    """
    # Overflows / large numbers
    data_large = [1e308, 1.5e308, 1.2e308]
    out_large = dv._zlema(data_large, 3)
    assert len(out_large) == 3
    # Check if underflow/extremely small numbers work
    data_small = [1e-308, 1.5e-308, 1.2e-308]
    out_small = dv._zlema(data_small, 3)
    assert len(out_small) == 3

    # Test that no NaN/Inf input causes internal crashes (e.g., if NaN values bypass sanitization)
    data_nan = [float('nan'), float('inf'), -float('inf')]
    out_nan = dv._zlema(data_nan, 3)
    assert len(out_nan) == 3


# ==============================================================================
# SECTION 3: Bessel's Correction Stability (production sample standard deviation)
# ==============================================================================

def test_bessel_small_sizes_are_undefined_not_nan():
    """Sample SD needs at least two readings: N = 0 and N = 1 return None (no NaN, no
    division by zero), which callers map to an honest null."""
    assert st._std([]) is None
    assert st._std([100.0]) is None


def test_bessel_identical_values_zero_variance():
    """Flat inputs have zero sample SD exactly, never NaN."""
    assert st._std([5.5] * 10) == 0.0
