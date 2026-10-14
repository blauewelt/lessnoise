"""WGS84 → Swiss LV95 and hectare cell ids (DESIGN.md §3). Pure functions.

swisstopo's approximate formulas (≈ 1 m inside Switzerland), the same arithmetic as
`docs/map/lv95.js` and the app's `geo/Lv95.kt`. The server needs it for the points of manual
reports (§4.7), which arrive as WGS84 coordinates, and for the shifted LV95 points of shared
measurements (§4.3, schema v4), whose display cell is the hectare that contains them.
"""

from __future__ import annotations

import math

# LV95 hectare ids inside a generous box around Switzerland (E 2 480 000–2 840 000,
# N 1 070 000–1 300 000): the same box for site cells, record cells and report points.
CELL_E_RANGE = (24800, 28400)
CELL_N_RANGE = (10700, 13000)
# A coarse WGS84 box first (the map's bounds), so the polynomial is only used where it is valid.
LAT_RANGE = (45.8, 47.85)
LON_RANGE = (5.9, 10.55)


def wgs84_to_lv95(lat: float, lon: float) -> tuple[float, float]:
    """Decimal degrees → (E, N) in metres."""
    phi = (lat * 3600 - 169028.66) / 10000
    lam = (lon * 3600 - 26782.5) / 10000
    e = 2600072.37 + 211455.93 * lam - 10938.51 * lam * phi - 0.36 * lam * phi**2 - 44.54 * lam**3
    n = (
        1200147.07 + 308807.95 * phi + 3745.25 * lam**2 + 76.63 * phi**2
        - 194.56 * lam**2 * phi + 119.79 * phi**3
    )  # fmt: skip
    return e, n


# The same box in whole metres, for the shifted points of measurements (§4.3): E 2 480 000–2 839 999,
# N 1 070 000–1 299 999 — exactly the points whose hectare passes `cell_in_switzerland`.
POINT_E_RANGE = (CELL_E_RANGE[0] * 100, CELL_E_RANGE[1] * 100 - 1)
POINT_N_RANGE = (CELL_N_RANGE[0] * 100, CELL_N_RANGE[1] * 100 - 1)


def lv95_cell(e: float, n: float) -> str:
    """LV95 (E, N) in metres → id of the hectare that contains the point."""
    return f"h{math.floor(e / 100)}_{math.floor(n / 100)}"


def lv95_point_in_switzerland(e: int, n: int) -> bool:
    return POINT_E_RANGE[0] <= e <= POINT_E_RANGE[1] and POINT_N_RANGE[0] <= n <= POINT_N_RANGE[1]


def cell_of(lat: float, lon: float) -> str:
    e, n = wgs84_to_lv95(lat, lon)
    return lv95_cell(e, n)


def cell_in_switzerland(cell: str) -> bool:
    """`cell` must already match ^h\\d{5}_\\d{5}$."""
    e, n = (int(x) for x in cell[1:].split("_"))
    return CELL_E_RANGE[0] <= e < CELL_E_RANGE[1] and CELL_N_RANGE[0] <= n < CELL_N_RANGE[1]


def point_in_switzerland(lat: float, lon: float) -> bool:
    if not (math.isfinite(lat) and math.isfinite(lon)):
        return False
    if not (LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]):
        return False
    return cell_in_switzerland(cell_of(lat, lon))
