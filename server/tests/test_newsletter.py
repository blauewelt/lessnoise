"""E-mail addresses for update notices (DESIGN.md §4.8): `POST` / `DELETE /v1/newsletter`, the
CORS answer for the website, the rate limit and `python -m stadtlaerm_server export-newsletter`."""

from __future__ import annotations

import sqlite3

import pytest

from stadtlaerm_server import __main__ as cli
from stadtlaerm_server.config import Settings
from stadtlaerm_server.schemas import normalize_email

URL = "/v1/newsletter"
SITE = "https://wenigerlaerm.ch"


def rows(settings, sql, *args):
    with sqlite3.connect(settings.db_path) as c:
        return c.execute(sql, args).fetchall()


def test_accept_stores_the_normalised_address_and_the_time_only(client, settings):
    r = client.post(URL, json={"email": "  Anna.Muster+laerm@Example.CH "}, headers={"Origin": SITE})
    assert r.status_code == 200 and r.json() == {"ok": True}
    assert r.headers["access-control-allow-origin"] == SITE
    stored = rows(settings, "SELECT * FROM newsletter")
    assert len(stored) == 1 and stored[0][0] == "anna.muster+laerm@example.ch"
    assert stored[0][1].endswith("+00:00")  # ISO UTC, seconds
    # Exactly two columns; nothing else about the request is kept anywhere.
    assert [c[1] for c in rows(settings, "PRAGMA table_info(newsletter)")] == ["email", "created_at"]


def test_nothing_else_is_stored(client, settings):
    ua = "Mozilla/5.0 (Linux; Android 14) unique-agent-1234"
    r = client.post(URL, json={"email": "a@b.ch"}, headers={"User-Agent": ua, "Origin": SITE})
    assert r.status_code == 200
    # No other table got a row, and neither the IP nor the user agent appears in the database.
    for (table,) in rows(settings, "SELECT name FROM sqlite_master WHERE type = 'table'"):
        if table in ("newsletter", "schema_version", "meta"):
            continue
        assert rows(settings, f"SELECT COUNT(*) FROM {table}") == [(0,)], table
    raw = settings.db_path.read_bytes()
    wal = settings.db_path.with_name(settings.db_path.name + "-wal")
    if wal.exists():
        raw += wal.read_bytes()
    assert b"unique-agent-1234" not in raw and b"testclient" not in raw
    # Extra fields are refused, not stored.
    r = client.post(URL, json={"email": "c@d.ch", "name": "Anna"})
    assert r.status_code == 422
    assert rows(settings, "SELECT email FROM newsletter") == [("a@b.ch",)]


@pytest.mark.parametrize(
    "email",
    [
        "", "   ", "anna", "anna@", "@example.ch", "anna@example", "anna@@example.ch", "anna@exa mple.ch",
        "an na@example.ch", ".anna@example.ch", "anna.@example.ch", "an..na@example.ch", "anna@-example.ch",
        "anna@example-.ch", "anna@example.c", "anna@example.ch.", "<anna@example.ch>", "anna@example.ch, b@c.ch",
        "a" * 65 + "@example.ch", "a@" + "b" * 250 + ".ch",
    ],
)  # fmt: skip
def test_invalid_addresses_are_refused(client, settings, email):
    r = client.post(URL, json={"email": email}, headers={"Origin": SITE})
    assert r.status_code == 422
    assert r.headers["access-control-allow-origin"] == SITE  # the page can read the refusal
    assert rows(settings, "SELECT COUNT(*) FROM newsletter") == [(0,)]


@pytest.mark.parametrize("body", [{}, {"email": 3}, {"email": None}, ["a@b.ch"], "a@b.ch"])
def test_wrong_bodies_are_refused(client, body):
    assert client.post(URL, json=body).status_code == 422
    assert client.post(URL, content=b"not json", headers={"Content-Type": "application/json"}).status_code == 400


def test_length_limit_254():
    local = "a" * 64
    domain = ".".join(["b" * 63, "c" * 63, "d" * 58, "ch"])
    assert len(f"{local}@{domain}") == 254
    assert normalize_email(f"{local}@{domain}") == f"{local}@{domain}"
    with pytest.raises(ValueError, match="254"):
        normalize_email(f"{local}@x{domain}")


def test_valid_shapes():
    for ok in ["a@b.ch", "x_y-z.w+tag@sub.example.co.uk", "o'brien@example.ie", "a@xn--lrm-lla.ch", "1@2.com"]:
        assert normalize_email(ok) == ok


def test_duplicate_answers_like_a_new_address_and_keeps_the_first_time(client, settings):
    assert client.post(URL, json={"email": "a@b.ch"}).status_code == 200
    first = rows(settings, "SELECT created_at FROM newsletter")
    r = client.post(URL, json={"email": " A@B.CH"})
    assert r.status_code == 200 and r.json() == {"ok": True}
    assert rows(settings, "SELECT COUNT(*) FROM newsletter") == [(1,)]
    assert rows(settings, "SELECT created_at FROM newsletter") == first


def test_delete_removes_and_answers_the_same_for_an_unknown_address(client, settings):
    client.post(URL, json={"email": "a@b.ch"})
    client.post(URL, json={"email": "c@d.ch"})
    r = client.request("DELETE", URL, json={"email": " A@b.ch "}, headers={"Origin": SITE})
    assert r.status_code == 204 and r.headers["access-control-allow-origin"] == SITE
    assert rows(settings, "SELECT email FROM newsletter") == [("c@d.ch",)]
    # Not (or no longer) stored: the same answer.
    assert client.request("DELETE", URL, json={"email": "a@b.ch"}).status_code == 204
    assert client.request("DELETE", URL, json={"email": "nobody@example.ch"}).status_code == 204
    assert client.request("DELETE", URL, json={"email": "not an address"}).status_code == 422
    assert rows(settings, "SELECT email FROM newsletter") == [("c@d.ch",)]


def test_rate_limit_per_ip_covers_post_and_delete(make_client, settings):
    client = make_client(newsletter_per_ip_per_hour=3)
    assert client.post(URL, json={"email": "a@b.ch"}).status_code == 200
    assert client.post(URL, json={"email": "c@d.ch"}).status_code == 200
    assert client.request("DELETE", URL, json={"email": "a@b.ch"}).status_code == 204
    r = client.post(URL, json={"email": "e@f.ch"}, headers={"Origin": SITE})
    assert r.status_code == 429 and int(r.headers["retry-after"]) > 0
    assert r.headers["access-control-allow-origin"] == SITE
    assert client.request("DELETE", URL, json={"email": "c@d.ch"}).status_code == 429
    assert rows(settings, "SELECT email FROM newsletter") == [("c@d.ch",)]
    # Its own budget: device registrations are not affected.
    assert client.post("/v1/devices", json={"app_version": "0.7.0", "app_build": 23}).status_code == 201


def test_rate_limit_setting():
    assert Settings.from_env({}).newsletter_per_ip_per_hour == 10
    assert Settings.from_env({"NEWSLETTER_PER_IP_PER_HOUR": "30"}).newsletter_per_ip_per_hour == 30


def test_cors_preflight_only_for_the_website_origins(client):
    for origin in ("https://wenigerlaerm.ch", "https://stadtlaerm.ch"):
        r = client.options(
            URL,
            headers={
                "Origin": origin,
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "content-type",
            },
        )
        assert r.status_code == 204
        assert r.headers["access-control-allow-origin"] == origin
        assert set(r.headers["access-control-allow-methods"].replace(" ", "").split(",")) == {"POST", "DELETE"}
        assert r.headers["access-control-allow-headers"].lower() == "content-type"
    r = client.options(URL, headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in r.headers
    r = client.post(URL, json={"email": "a@b.ch"}, headers={"Origin": "https://evil.example"})
    assert "access-control-allow-origin" not in r.headers
    # The device API still has no CORS at all.
    r = client.options("/v1/devices", headers={"Origin": SITE, "Access-Control-Request-Method": "POST"})
    assert "access-control-allow-origin" not in r.headers


def test_export_newsletter_cli(client, settings, monkeypatch, capsys):
    for e in ("b@c.ch", "a@b.ch"):
        assert client.post(URL, json={"email": e}).status_code == 200
    monkeypatch.setenv("DB_PATH", str(settings.db_path))
    monkeypatch.setenv("MAP_DIR", str(settings.map_dir))
    assert cli.main(["export-newsletter"]) == 0
    out = capsys.readouterr()
    assert set(out.out.splitlines()) == {"b@c.ch", "a@b.ch"} and len(out.out.splitlines()) == 2
    assert out.err.strip() == "2 addresses"
    assert rows(settings, "SELECT COUNT(*) FROM newsletter") == [(2,)]  # nothing changed
