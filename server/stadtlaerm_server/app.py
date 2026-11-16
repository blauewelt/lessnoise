"""HTTP API (DESIGN.md §4), the static map (§6) and the background jobs, in one process.

No `from __future__ import annotations` here: FastAPI must resolve the dependency
annotations defined inside create_app at runtime.
"""

import asyncio
import base64
import contextlib
import hashlib
import hmac
import json
import logging
import os
import secrets
import sqlite3
import threading
import time
import uuid
import zlib
from collections.abc import AsyncIterator, Callable, Iterator
from datetime import UTC, datetime, timedelta
from email.utils import formatdate, parsedate_to_datetime
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ValidationError

from . import db, publish, retention
from .config import MAP_CATEGORY_SET, Settings
from .ratelimit import TokenBuckets
from .schemas import (
    MAX_EVENTS,
    MAX_MINUTES,
    MAX_REPORTS,
    DeviceIn,
    DeviceOut,
    EventIn,
    MinuteIn,
    NewsletterIn,
    ReportIn,
    SiteIn,
    UploadResult,
)

log = logging.getLogger("stadtlaerm")


class ApiError(HTTPException):
    pass


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _reasons(err: ValidationError) -> list[dict[str, str]]:
    return [
        {"field": ".".join(str(p) for p in e["loc"]) or "body", "reason": e["msg"].removeprefix("Value error, ")}
        for e in err.errors(include_url=False)
    ]


# Cache-Control max-age of the map files (DESIGN.md §6, §8): the map page polls every 30 s.
MAP_MAX_AGE_S = 15


def not_modified(request: Request, etag: str, mtime_s: int) -> bool:
    """RFC 9110 §13.1: If-None-Match (weak comparison) wins; If-Modified-Since only without it."""
    inm = request.headers.get("if-none-match")
    if inm is not None:
        tags = [t.strip().removeprefix("W/") for t in inm.split(",")]
        # A proxy that compresses (Caddy's `encode gzip`) may hand the browser our tag with the
        # coding appended ("…-gzip"); the browser sends that back. Same file, same answer.
        coded = {f'{etag[:-1]}-{c}"' for c in ("gzip", "zstd", "br")}
        return "*" in tags or any(t == etag or t in coded for t in tags)
    ims = request.headers.get("if-modified-since")
    if ims:
        try:
            since = parsedate_to_datetime(ims)
        except (TypeError, ValueError):
            return False
        if since.tzinfo is None:
            return False  # not an HTTP date
        return mtime_s <= since.timestamp()
    return False


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    registrations = TokenBuckets(settings.registrations_per_ip_per_day, 86_400)
    device_requests = TokenBuckets(settings.requests_per_device_per_hour, 3_600)
    newsletter_requests = TokenBuckets(settings.newsletter_per_ip_per_hour, 3_600)

    with db.open_db(settings.db_path) as conn:
        before = db.schema_version(conn)
        after = db.migrate(conn)
    # Migration v5 deleted stored events of other categories: rewrite every published file before
    # serving (below), whether or not the background loop runs. A new database has none to rewrite.
    purged = 0 < before < db.PURGE_VERSION <= after

    # The change marker (publish.Changes): the handlers note what they committed; the publish
    # cycle takes the notes and rebuilds only when something changed (DESIGN.md §6).
    changes = publish.Changes()
    publisher = publish.Publisher(settings.map_dir, settings.min_devices_per_cell, settings.loud_event_db)
    publish_lock = threading.Lock()

    def _publishing(run: Callable[[sqlite3.Connection, publish.Pending], str]) -> str:
        with publish_lock:
            pending = changes.take()  # before reading the database: a later note waits for the next run
            try:
                with db.open_db(settings.db_path) as conn:
                    return run(conn, pending)
            except BaseException:
                changes.put_back(pending)
                raise

    def publish_once(now: datetime | None = None) -> None:
        """A full rebuild now (after expiry; tests)."""

        def run(conn: sqlite3.Connection, _: publish.Pending) -> str:
            publisher.full(conn, now or datetime.now(UTC))
            return "full"

        _publishing(run)

    def publish_cycle(now: datetime | None = None) -> str:
        """One background cycle: "full", "incremental" or "skipped" (publish.Publisher.run)."""
        return _publishing(lambda conn, pending: publisher.run(conn, now or datetime.now(UTC), pending))

    def retention_once() -> int:
        """Retention and expiry (DESIGN.md §5); returns the number of expired devices."""
        with db.open_db(settings.db_path) as conn:
            log.info("retention: %s", retention.run_retention(conn, retention_days=settings.retention_days))
            return retention.expire_inactive(conn, inactive_days=settings.inactive_delete_days)

    if purged:
        publish_once()
        log.info("migration v5: published files rebuilt")

    async def background() -> None:
        last_retention: datetime | None = None
        while True:
            started = time.monotonic()
            try:
                result = await asyncio.to_thread(publish_cycle)
                if result != "skipped":
                    log.debug("publish: %s in %.1f s", result, time.monotonic() - started)
                now = datetime.now(UTC)
                if last_retention is None or now - last_retention >= timedelta(days=1):
                    if await asyncio.to_thread(retention_once):
                        # Expired devices leave the map now, not only at the next regular run.
                        await asyncio.to_thread(publish_once)
                    last_retention = now
                registrations.prune()
                device_requests.prune()
                newsletter_requests.prune()
            except Exception:  # keep the loop alive; the next run tries again (a full one)
                log.exception("background job failed")
            # Every PUBLISH_INTERVAL_S from start to start; a run that took longer is followed at once.
            await asyncio.sleep(max(0.0, settings.publish_interval_s - (time.monotonic() - started)))

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        task = asyncio.create_task(background()) if settings.background_jobs else None
        yield
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task

    app = FastAPI(title="Weniger Lärm contribution API", version="1", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.state.settings = settings
    app.state.publish_once = publish_once
    app.state.publish_cycle = publish_cycle
    app.state.changes = changes
    app.state.publisher = publisher
    app.state.retention_once = retention_once
    app.state.registrations = registrations
    app.state.device_requests = device_requests
    app.state.newsletter_requests = newsletter_requests

    # ---- helpers ------------------------------------------------------------------------

    def get_conn() -> Iterator[sqlite3.Connection]:
        conn = db.connect(settings.db_path)
        try:
            yield conn
        finally:
            conn.close()

    Conn = Annotated[sqlite3.Connection, Depends(get_conn)]

    def client_ip(request: Request) -> str:
        if settings.trust_proxy:
            fwd = request.headers.get("x-forwarded-for")
            if fwd:
                # The proxy (Caddy) appends the address it saw; the rightmost entry is the one we trust.
                return fwd.split(",")[-1].strip()
        return request.client.host if request.client else "unknown"

    def limited(buckets: TokenBuckets, key: str, what: str) -> None:
        wait = buckets.take(key)
        if wait > 0:
            raise ApiError(429, f"rate limit: {what}", headers={"Retry-After": str(int(wait) + 1)})

    async def read_body(request: Request) -> Any:
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > settings.max_body_bytes:
            raise ApiError(413, f"request body larger than {settings.max_body_bytes} bytes")
        raw = bytearray()
        async for chunk in request.stream():
            raw += chunk
            if len(raw) > settings.max_body_bytes:
                raise ApiError(413, f"request body larger than {settings.max_body_bytes} bytes")
        encoding = request.headers.get("content-encoding", "identity").strip().lower()
        if encoding == "gzip":
            d = zlib.decompressobj(16 + zlib.MAX_WBITS)
            try:
                data = d.decompress(bytes(raw), settings.max_decompressed_bytes)
            except zlib.error as e:
                raise ApiError(400, "body is not valid gzip") from e
            if d.unconsumed_tail or not d.eof:
                raise ApiError(413 if d.unconsumed_tail else 400, "gzip body too large or truncated")
        elif encoding in ("identity", ""):
            data = bytes(raw)
        else:
            raise ApiError(415, f"unsupported Content-Encoding: {encoding}")
        try:
            return json.loads(data)
        except (UnicodeDecodeError, json.JSONDecodeError) as e:
            raise ApiError(400, "body is not valid JSON") from e

    def validate[M: BaseModel](model: type[M], body: Any) -> M:
        try:
            return model.model_validate(body)
        except ValidationError as e:
            raise ApiError(422, _reasons(e)) from e

    def authenticate(
        device_id: str, conn: sqlite3.Connection, authorization: str | None, *, rate_limit: bool = True
    ) -> None:
        scheme, _, token = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise ApiError(401, "missing bearer token", headers={"WWW-Authenticate": "Bearer"})
        row = conn.execute("SELECT token_hash FROM devices WHERE id = ?", (device_id,)).fetchone()
        # Unknown device and wrong token look the same from outside.
        if row is None or not hmac.compare_digest(row[0], hash_token(token.strip())):
            raise ApiError(401, "invalid device or token", headers={"WWW-Authenticate": "Bearer"})
        if rate_limit:
            limited(device_requests, device_id, f"{settings.requests_per_device_per_hour} requests per device per hour")
        with conn:
            conn.execute("UPDATE devices SET last_seen_at = ? WHERE id = ?", (db.utc_now_iso(), device_id))

    Auth = Annotated[str | None, Header()]

    def check_time(start: datetime, now: datetime, name: str = "start") -> str | None:
        if start > now + timedelta(seconds=settings.clock_skew_s):
            return f"{name} is in the future"
        if start < now - timedelta(days=settings.max_record_age_days):
            return f"{name} is more than {settings.max_record_age_days} days old"
        return None

    def upload(
        body: Any, model: type[MinuteIn] | type[EventIn] | type[ReportIn], limit: int, to_row, time_key: str = "start"
    ) -> tuple[list[dict], list[dict]]:
        """Validates each record; a bad one is reported in `rejected` as {index, <time_key>, reasons}."""
        if not isinstance(body, list):
            raise ApiError(422, "body must be a JSON array of records")
        if len(body) > limit:
            raise ApiError(413, f"at most {limit} records per request")
        now = datetime.now(UTC)
        rows, rejected = [], []
        for i, item in enumerate(body):
            start = item.get(time_key) if isinstance(item, dict) else None
            try:
                rec = model.model_validate(item)
            except ValidationError as e:
                rejected.append({"index": i, time_key: start, "reasons": _reasons(e)})
                continue
            problems = rec.problems() if isinstance(rec, ReportIn) else []
            if (why := check_time(rec.start_dt, now, time_key)) is not None:
                problems.insert(0, {"field": time_key, "reason": why})
            if problems:
                rejected.append({"index": i, time_key: start, "reasons": problems})
                continue
            rows.append(to_row(rec))
        return rows, rejected

    # ---- endpoints ----------------------------------------------------------------------

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse({"detail": exc.detail}, status_code=exc.status_code, headers=exc.headers)

    @app.get("/healthz")
    def healthz(conn: Conn) -> dict:
        conn.execute("SELECT 1").fetchone()
        # The deployed git commit (set by scripts/deploy.sh): anyone can see which public code runs.
        return {"ok": True, "commit": os.environ.get("GIT_COMMIT") or "unknown"}

    @app.post("/v1/devices", status_code=201, response_model=DeviceOut)
    async def register(request: Request, conn: Conn) -> DeviceOut:
        limited(
            registrations, client_ip(request), f"{settings.registrations_per_ip_per_day} registrations per IP per day"
        )
        body = validate(DeviceIn, await read_body(request))
        device_id = str(uuid.uuid4())
        token = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
        with conn:
            conn.execute(
                "INSERT INTO devices (id, token_hash, app_version, app_build, created_at, last_seen_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (device_id, hash_token(token), body.app_version, body.app_build, db.utc_now_iso(), db.utc_now_iso()),
            )
        changes.registered()  # stats.json counts devices
        return DeviceOut(device_id=device_id, token=token)

    @app.put("/v1/devices/{device_id}/site", status_code=204)
    async def put_site(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> Response:
        authenticate(device_id, conn, authorization)
        s = validate(SiteIn, await read_body(request))
        before = conn.execute("SELECT cell, device_model FROM sites WHERE device_id = ?", (device_id,)).fetchone()
        with conn:
            conn.execute(
                "INSERT INTO sites (device_id, cell, point_e, point_n, sigma_m, placement, floor, street_facing, "
                "device_model, audio_source, calibrated, calibration_offset_db, note, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (device_id) DO UPDATE SET cell = excluded.cell, point_e = excluded.point_e, "
                "point_n = excluded.point_n, sigma_m = excluded.sigma_m, placement = excluded.placement, "
                "floor = excluded.floor, street_facing = excluded.street_facing, device_model = excluded.device_model, "
                "audio_source = excluded.audio_source, calibrated = excluded.calibrated, "
                "calibration_offset_db = excluded.calibration_offset_db, note = excluded.note, "
                "updated_at = excluded.updated_at",
                (
                    device_id, s.display_cell,
                    s.point.e if s.point else None, s.point.n if s.point else None,
                    s.point.sigma_m if s.point else None,
                    s.placement, s.floor,
                    None if s.street_facing is None else int(s.street_facing),
                    s.device_model, s.audio_source, int(s.calibrated), s.calibration_offset_db, s.note,
                    db.utc_now_iso(),
                ),
            )  # fmt: skip
        # The map uses the site's cell (for records without their own) and its phone model; a new or
        # changed one moves data between cells, which only a full rebuild gets right. The other
        # fields are not published.
        if before is None or tuple(before) != (s.display_cell, s.device_model):
            changes.structural()
        return Response(status_code=204)

    @app.post("/v1/devices/{device_id}/minutes", response_model=UploadResult)
    async def post_minutes(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> UploadResult:
        authenticate(device_id, conn, authorization)
        received = db.utc_now_iso()
        rows, rejected = upload(
            await read_body(request), MinuteIn, MAX_MINUTES, lambda m: db.minute_row(device_id, m, received)
        )
        with conn:
            accepted, duplicates = db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, rows)
        changes.records(device_id, (r["start_utc"] for r in rows))
        return UploadResult(accepted=accepted, duplicates=duplicates, rejected=rejected)

    @app.post("/v1/devices/{device_id}/events", response_model=UploadResult)
    async def post_events(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> UploadResult:
        authenticate(device_id, conn, authorization)
        received = db.utc_now_iso()
        rows, rejected = upload(
            await read_body(request), EventIn, MAX_EVENTS, lambda e: db.event_row(device_id, e, received)
        )
        # Only machine and motor noise goes on the map (DESIGN.md §4.4): any other category
        # (voices, music, unclassified, wind, an unknown id) is dropped here, not stored, and
        # answered with 2xx so that apps 0.5.x–0.6.3, which send every category, do not retry.
        kept = [r for r in rows if r["category"] in MAP_CATEGORY_SET]
        dropped = [r["start_utc"] for r in rows if r["category"] not in MAP_CATEGORY_SET]
        with conn:
            accepted, duplicates = db.upsert_rows(conn, "events", db.EVENT_COLUMNS, kept)
            # A dropped record replaces a stored one with its start, like any upload (§4.4): that
            # stored event goes too (e.g. re-sent with another category).
            # (Within one batch a kept record with the same start wins.)
            starts = {r["start_utc"] for r in kept}
            replaced = db.delete_rows(conn, "events", device_id, [t for t in dropped if t not in starts])
        if dropped:
            log.info("events: dropped_by_category=%d", len(dropped))
        changes.records(device_id, [r["start_utc"] for r in kept] + (dropped if replaced else []))
        return UploadResult(
            accepted=accepted, duplicates=duplicates, rejected=rejected, dropped_by_category=len(dropped)
        )

    @app.post("/v1/devices/{device_id}/reports", response_model=UploadResult)
    async def post_reports(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> UploadResult:
        """Manual reports (DESIGN.md §4.7); idempotent on (device, time), the later one wins."""
        authenticate(device_id, conn, authorization)
        received = db.utc_now_iso()
        rows, rejected = upload(
            await read_body(request), ReportIn, MAX_REPORTS, lambda r: db.report_row(device_id, r, received), "time"
        )
        with conn:
            accepted, duplicates = db.upsert_rows(conn, "reports", db.REPORT_COLUMNS, rows, key="time_utc")
        if rows:
            changes.reports()
        return UploadResult(accepted=accepted, duplicates=duplicates, rejected=rejected)

    @app.delete("/v1/devices/{device_id}", status_code=204)
    def delete(device_id: str, conn: Conn, authorization: Auth = None) -> Response:
        # Never rate limited: deleting one's data must always work (DESIGN.md §2.4).
        authenticate(device_id, conn, authorization, rate_limit=False)
        db.delete_device(conn, device_id)
        changes.structural()  # gone from every file on the next cycle, ≤ PUBLISH_INTERVAL_S (§2 rule 4)
        return Response(status_code=204)

    # ---- e-mail addresses for update notices (DESIGN.md §4.8) -------------------------------
    # Called by the website's form (docs/js/newsletter.js), so this route — and only this one —
    # answers CORS preflights, for the configured website origins only. Stored: the address and
    # the time, nothing else; the client IP is used only as the in-memory rate-limit key.

    def newsletter_cors(request: Request) -> dict[str, str]:
        origin = request.headers.get("origin", "").rstrip("/")
        if origin and origin in settings.cors_origins:
            return {"Access-Control-Allow-Origin": origin, "Vary": "Origin"}
        return {"Vary": "Origin"}

    def newsletter_error(e: ApiError, cors: dict[str, str]) -> JSONResponse:
        # With the CORS header, so the page can tell a refusal from an unreachable server.
        return JSONResponse({"detail": e.detail}, status_code=e.status_code, headers={**(e.headers or {}), **cors})

    async def newsletter_email(request: Request) -> str:
        limited(
            newsletter_requests, client_ip(request), f"{settings.newsletter_per_ip_per_hour} requests per IP per hour"
        )
        return validate(NewsletterIn, await read_body(request)).email

    @app.options("/v1/newsletter")
    def newsletter_preflight(request: Request) -> Response:
        headers = newsletter_cors(request)
        if "Access-Control-Allow-Origin" in headers:
            headers |= {
                "Access-Control-Allow-Methods": "POST, DELETE",
                "Access-Control-Allow-Headers": "Content-Type",
                "Access-Control-Max-Age": "600",
            }
        return Response(status_code=204, headers=headers)

    @app.post("/v1/newsletter")
    async def newsletter_add(request: Request, conn: Conn) -> Response:
        # A new and an already stored address get the same answer: nobody can find out
        # whether an address is on the list.
        headers = newsletter_cors(request)
        try:
            db.add_newsletter(conn, await newsletter_email(request))
        except ApiError as e:
            return newsletter_error(e, headers)
        return JSONResponse({"ok": True}, headers=headers)

    @app.delete("/v1/newsletter")
    async def newsletter_remove(request: Request, conn: Conn) -> Response:
        # 204 whether or not the address was stored (no enumeration).
        headers = newsletter_cors(request)
        try:
            db.remove_newsletter(conn, await newsletter_email(request))
        except ApiError as e:
            return newsletter_error(e, headers)
        return Response(status_code=204, headers=headers)

    # ---- the public map files -------------------------------------------------------------

    def map_headers(request: Request) -> dict[str, str]:
        # Several website origins may read the map (wenigerlaerm.ch, and stadtlaerm.ch while it
        # redirects): answer with the requesting origin if it is allowed, else with the first one.
        # The same answer on every API host (api.lessnoise.ch, api.stadtlaerm.ch).
        origin = request.headers.get("origin", "")
        allowed = settings.cors_origins
        return {
            # Short, because the files change every 30 s while people measure; a browser then asks
            # again with If-None-Match / If-Modified-Since and mostly gets an empty 304.
            "Cache-Control": f"max-age={MAP_MAX_AGE_S}",
            "Access-Control-Allow-Origin": origin if origin in allowed else allowed[0],
            "Vary": "Origin",
        }

    @app.get("/v1/map/{path:path}")
    def map_file(path: str, request: Request) -> Response:
        root = settings.map_dir.resolve()
        target = (root / path).resolve()
        headers = map_headers(request)
        if root not in target.parents or target.suffix != ".json" or not target.is_file():
            return JSONResponse({"detail": "not found"}, status_code=404, headers=headers)
        try:
            # Open first, then stat the open file: the publisher replaces files atomically, so the
            # validators always describe exactly the bytes sent.
            with open(target, "rb") as f:
                st = os.fstat(f.fileno())
                headers["ETag"] = f'"{st.st_mtime_ns:x}-{st.st_size:x}"'
                headers["Last-Modified"] = formatdate(st.st_mtime, usegmt=True)
                if not_modified(request, headers["ETag"], int(st.st_mtime)):
                    return Response(status_code=304, headers=headers)
                body = f.read()
        except FileNotFoundError:  # removed by the publisher just now
            return JSONResponse({"detail": "not found"}, status_code=404, headers=headers)
        return Response(body, media_type="application/json", headers=headers)

    return app


def app_from_env() -> FastAPI:
    """Factory for uvicorn: `uvicorn --factory stadtlaerm_server.app:app_from_env`."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app()
