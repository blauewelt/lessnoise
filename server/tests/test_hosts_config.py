"""The API answers on api.lessnoise.ch and api.stadtlaerm.ch (Caddyfile + compose.yaml), and the
health check of the automatic deployment still goes through a host Caddy serves.

Text checks only: Caddy itself is not available in the test environment, so the Caddyfile is
not parsed here (see WORKLOG.md).
"""

from __future__ import annotations

import re
from pathlib import Path

SERVER = Path(__file__).resolve().parents[1]


def test_caddy_serves_every_host_of_api_hosts():
    caddy = (SERVER / "Caddyfile").read_text()
    sites = [ln for ln in caddy.splitlines() if ln and not ln.startswith(("#", "\t", " ", "}"))]
    assert sites == ["{$API_HOSTS} {"]  # one site block for all hosts


def test_compose_default_lists_both_hosts():
    compose = (SERVER / "compose.yaml").read_text()
    m = re.search(r'API_HOSTS: "\$\{API_HOSTS:-([^}]*)\}"', compose)
    assert m is not None
    assert [h.strip() for h in m.group(1).split(",")] == ["api.lessnoise.ch", "api.stadtlaerm.ch"]
    env = (SERVER / ".env.example").read_text()
    assert "API_HOSTS=api.lessnoise.ch, api.stadtlaerm.ch" in env.splitlines()


def test_deploy_health_check_uses_a_served_host():
    deploy = (SERVER / "scripts" / "deploy.sh").read_text()
    m = re.search(r"HEALTH_URLS=\$\{HEALTH_URL:-([^}]+)\}", deploy)
    assert m is not None
    hosts = re.findall(r"https://([^/]+)/healthz", m.group(1))
    # the project's host first, then the old one; both are served (API_HOSTS above)
    assert hosts == ["api.lessnoise.ch", "api.stadtlaerm.ch"]
