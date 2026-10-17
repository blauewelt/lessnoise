"""Request bodies of DESIGN.md §4 with the limits of §8.

Unknown fields are rejected (`extra="forbid"`): the server accepts exactly what the design
lists, so nothing else (e.g. raw AudioSet labels) can be stored by accident.
"""

from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, ValidationInfo, field_validator, model_validator

from .lv95 import cell_in_switzerland, lv95_cell, lv95_point_in_switzerland, point_in_switzerland

DB_MIN, DB_MAX = 0.0, 140.0
CELL_PATTERN = r"^h\d{5}_\d{5}$"
CATEGORY_PATTERN = r"^[a-z][a-z0-9_]{0,31}$"
# Categories of a manual report (§4.7), as the app offers them.
REPORT_CATEGORIES = ("motorrad", "auto", "lastwagen", "poser", "andere")
# Since app 0.7.0 a report is sent at whatever accuracy the phone's fix has (owner, 2026-10-17);
# this bound only rejects nonsense, it is no quality gate. Until then it was 200 m.
MAX_REPORT_ACCURACY_M = 10_000.0
# Direction of travel of a report (§4.7, app 0.6.7): the compass's accuracy class as Android
# reports it, and whether the bearing is referred to true or to magnetic north.
DIRECTION_ACCURACIES = ("high", "medium", "low", "unreliable")
DIRECTION_NORTHS = ("true", "magnetic")
# The app's setting «Standort verschieben» (§2 rule 2, §4.3): σ of the location noise in metres.
POINT_SIGMAS_M = (25, 50, 100, 200)

Level = Annotated[float, Field(ge=DB_MIN, le=DB_MAX, allow_inf_nan=False)]
Share = Annotated[float, Field(ge=0.0, le=1.0, allow_inf_nan=False)]
CalibrationOffset = Annotated[float, Field(ge=50.0, le=200.0, allow_inf_nan=False)]
Category = Annotated[str, Field(pattern=CATEGORY_PATTERN)]
ShortText = Annotated[str, Field(max_length=64)]
Cell = Annotated[str, Field(pattern=CELL_PATTERN)]


def _check_cell(v: str | None) -> str | None:
    if v is not None and not cell_in_switzerland(v):
        raise ValueError("cell is not an LV95 hectare in Switzerland")
    return v


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class PointIn(Strict):
    """The shifted LV95 point of a shared measurement (§4.3, schema v4), whole metres.

    The app moved the true point by a Gaussian offset with standard deviation `sigma_m` before
    sending (§2 rule 2). The server keeps it with the record and shows the record in the hectare
    that contains it (`cell`, the display cell); it never publishes the point or σ.
    """

    e: StrictInt
    n: StrictInt
    sigma_m: StrictInt

    @field_validator("sigma_m")
    @classmethod
    def _sigma(cls, v: int) -> int:
        if v not in POINT_SIGMAS_M:
            raise ValueError(f"sigma_m must be one of {', '.join(map(str, POINT_SIGMAS_M))}")
        return v

    @model_validator(mode="after")
    def _in_switzerland(self) -> PointIn:
        if not lv95_point_in_switzerland(self.e, self.n):
            raise ValueError("point is not in the LV95 box of Switzerland")
        return self

    @property
    def cell(self) -> str:
        """The display cell: the hectare that contains the shifted point."""
        return lv95_cell(self.e, self.n)


def _one_place(v: PointIn | None, info: ValidationInfo) -> PointIn | None:
    if v is not None and info.data.get("cell") is not None:
        raise ValueError("send either cell or point, not both")
    return v


class DeviceIn(Strict):
    app_version: Annotated[str, Field(min_length=1, max_length=32)]
    app_build: Annotated[int, Field(ge=0, le=10_000_000)]


class DeviceOut(BaseModel):
    device_id: str
    token: str


class SiteIn(Strict):
    # Exactly one of the two: `cell` (apps up to 0.5.1: the hectare the contributor picked) or
    # `point` (app 0.6.2: the shifted point of the newest shared measurement, §4.2).
    cell: Cell | None = None
    point: PointIn | None = None
    placement: Literal["open_window", "balcony", "behind_glass", "other"]
    floor: Annotated[int, Field(ge=-3, le=100)] | None = None
    street_facing: bool | None = None
    device_model: ShortText | None = None
    audio_source: Literal["UNPROCESSED", "VOICE_RECOGNITION"]
    calibrated: bool
    calibration_offset_db: CalibrationOffset | None = None
    note: Annotated[str, Field(max_length=200)] | None = None

    _in_switzerland = field_validator("cell")(_check_cell)
    _one_place = field_validator("point")(_one_place)

    @model_validator(mode="after")
    def _has_place(self) -> SiteIn:
        if self.cell is None and self.point is None:
            raise ValueError("site needs a cell or a point")
        return self

    @property
    def display_cell(self) -> str:
        return self.point.cell if self.point is not None else self.cell  # type: ignore[return-value]


def parse_start(v: str, name: str = "start") -> datetime:
    """ISO-8601 with a zone offset (the app's format); naive times are refused."""
    try:
        t = datetime.fromisoformat(v)
    except ValueError as e:
        raise ValueError(f"{name} is not ISO-8601") from e
    if t.tzinfo is None or t.utcoffset() is None:
        raise ValueError(f"{name} has no zone offset")
    return t


class _Timed(Strict):
    start: Annotated[str, Field(max_length=40)]
    # Where the record was measured (§4.3, §4.4), at most one of the two: `point` (schema v4, app
    # 0.6.2: the shifted LV95 point; shown in the hectare that contains it) or `cell` (schema v3:
    # a hectare id). Neither (apps up to 0.5.1): the device's current site cell is used when the
    # map is built.
    cell: Cell | None = None
    point: PointIn | None = None
    _in_switzerland = field_validator("cell")(_check_cell)
    _one_place = field_validator("point")(_one_place)

    @property
    def display_cell(self) -> str | None:
        """The hectare the record is shown in: the one of its point, else its own cell, else None."""
        return self.point.cell if self.point is not None else self.cell

    @field_validator("start")
    @classmethod
    def _iso(cls, v: str) -> str:
        parse_start(v)
        return v

    @property
    def start_dt(self) -> datetime:
        return parse_start(self.start)

    @property
    def start_ms(self) -> int:
        return math.floor(self.start_dt.timestamp() * 1000 + 0.5)


class MinuteIn(_Timed):
    duration_s: Annotated[float, Field(gt=0, le=61.0)]
    valid_s: Annotated[float, Field(ge=0, le=61.0)]
    coverage: Share
    laeq_db: Level | None
    lafmax_db: Level | None = None
    lafmin_db: Level | None = None
    l1_db: Level | None = None
    l10_db: Level | None = None
    l50_db: Level | None = None
    l90_db: Level | None = None
    event_count: Annotated[int, Field(ge=0, le=600)]
    dominant_category: Category | None = None
    category_shares: Annotated[dict[Category, Share], Field(max_length=32)] = Field(default_factory=dict)
    calibration_id: ShortText | None = None
    calibration_offset_db: CalibrationOffset | None = None
    calibrated: bool
    audio_source: Literal["UNPROCESSED", "VOICE_RECOGNITION"] | None = None
    clock_corrections: Annotated[int, Field(ge=0, le=1000)] = 0

    @model_validator(mode="after")
    def _consistent(self) -> MinuteIn:
        if self.valid_s > self.duration_s + 1e-6:
            raise ValueError("valid_s is larger than duration_s")
        if self.l10_db is not None and self.l90_db is not None and self.l10_db < self.l90_db - 1e-6:
            raise ValueError("l10_db is below l90_db")
        return self


class EventIn(_Timed):
    duration_s: Annotated[float, Field(gt=0, le=3600.0)]
    lafmax_db: Level
    sel_db: Level | None = None
    background_db: Level | None = None
    threshold_db: Annotated[float, Field(ge=0, le=60.0)] | None = None
    min_level_db: Level | None = None
    category: Category
    category_score: Share | None = None
    calibration_id: ShortText | None = None
    calibrated: bool


class DirectionIn(Strict):
    """Where the reported vehicle drove (§4.7, app 0.6.7, optional): the compass bearing the phone
    pointed at when the reporter tapped «Richtung übernehmen», in whole degrees clockwise from north.
    Never a sensor stream, only this one value and how far to trust it."""

    bearing_deg: Annotated[StrictInt, Field(ge=0, le=359)]
    north: Literal[DIRECTION_NORTHS]  # type: ignore[valid-type]
    accuracy: Literal[DIRECTION_ACCURACIES]  # type: ignore[valid-type]


class ReportIn(Strict):
    """A manual report (§4.7): «here and now something loud passed», with its category and,
    since app 0.6.7, optionally the direction it drove."""

    time: Annotated[str, Field(max_length=40)]
    lat: Annotated[float, Field(ge=-90, le=90, allow_inf_nan=False)]
    lon: Annotated[float, Field(ge=-180, le=180, allow_inf_nan=False)]
    accuracy_m: Annotated[float, Field(ge=0, le=MAX_REPORT_ACCURACY_M, allow_inf_nan=False)]
    category: Literal[REPORT_CATEGORIES]  # type: ignore[valid-type]
    direction: DirectionIn | None = None

    @field_validator("time")
    @classmethod
    def _iso(cls, v: str) -> str:
        parse_start(v, "time")
        return v

    def problems(self) -> list[dict[str, str]]:
        """Checks across fields, reported per field like the schema errors (app.upload)."""
        if not point_in_switzerland(self.lat, self.lon):
            return [{"field": "lat", "reason": "the point (lat, lon) is not in Switzerland"}]
        return []

    @property
    def start_dt(self) -> datetime:
        return parse_start(self.time, "time")

    @property
    def start_ms(self) -> int:
        return math.floor(self.start_dt.timestamp() * 1000 + 0.5)


class UploadResult(BaseModel):
    accepted: int
    duplicates: int
    rejected: list[dict] = Field(default_factory=list)
    # Events only (§4.4): valid records of a category that is not on the map, not stored.
    dropped_by_category: int = 0


MAX_MINUTES = 1440
MAX_EVENTS = 2000
MAX_REPORTS = 50
