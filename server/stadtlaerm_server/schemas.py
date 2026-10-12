"""Request bodies of DESIGN.md §4 with the limits of §8.

Unknown fields are rejected (`extra="forbid"`): the server accepts exactly what the design
lists, so nothing else (e.g. raw AudioSet labels) can be stored by accident.
"""

from __future__ import annotations

import math
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .lv95 import cell_in_switzerland, point_in_switzerland

DB_MIN, DB_MAX = 0.0, 140.0
CELL_PATTERN = r"^h\d{5}_\d{5}$"
CATEGORY_PATTERN = r"^[a-z][a-z0-9_]{0,31}$"
# Categories of a manual report (§4.7), as the app offers them.
REPORT_CATEGORIES = ("motorrad", "auto", "lastwagen", "poser", "andere")
MAX_REPORT_ACCURACY_M = 200.0

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


class DeviceIn(Strict):
    app_version: Annotated[str, Field(min_length=1, max_length=32)]
    app_build: Annotated[int, Field(ge=0, le=10_000_000)]


class DeviceOut(BaseModel):
    device_id: str
    token: str


class SiteIn(Strict):
    cell: Cell
    placement: Literal["open_window", "balcony", "behind_glass", "other"]
    floor: Annotated[int, Field(ge=-3, le=100)] | None = None
    street_facing: bool | None = None
    device_model: ShortText | None = None
    audio_source: Literal["UNPROCESSED", "VOICE_RECOGNITION"]
    calibrated: bool
    calibration_offset_db: CalibrationOffset | None = None
    note: Annotated[str, Field(max_length=200)] | None = None

    _in_switzerland = field_validator("cell")(_check_cell)


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
    # The hectare the record was measured in (§4.3, §4.4). Absent (older apps): the device's
    # current site cell is used when the map is built.
    cell: Cell | None = None
    _in_switzerland = field_validator("cell")(_check_cell)

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


class ReportIn(Strict):
    """A manual report (§4.7): «here and now something loud passed», with its category."""

    time: Annotated[str, Field(max_length=40)]
    lat: Annotated[float, Field(ge=-90, le=90, allow_inf_nan=False)]
    lon: Annotated[float, Field(ge=-180, le=180, allow_inf_nan=False)]
    accuracy_m: Annotated[float, Field(ge=0, le=MAX_REPORT_ACCURACY_M, allow_inf_nan=False)]
    category: Literal[REPORT_CATEGORIES]  # type: ignore[valid-type]

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


MAX_MINUTES = 1440
MAX_EVENTS = 2000
MAX_REPORTS = 50
