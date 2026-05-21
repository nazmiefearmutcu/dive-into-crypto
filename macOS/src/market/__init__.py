"""Market data transport layer.

This package owns the live-price seam — the boundary between 'signal price'
(the close of the candle the indicators just evaluated) and 'display price'
(the freshest tick the user sees on the dashboard).

See `live_price_service` for the public API.
"""
