"""scripts/deploy.sh (automatic pull-based deployment) against a local git "origin".

`docker` and `curl` are replaced by stubs: the docker stub remembers which commit was started
(the GIT_COMMIT it was given), the curl stub answers /healthz with that commit unless the commit
is listed as broken. Nothing touches the network, systemd or the real Docker.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "deploy.sh"
pytestmark = pytest.mark.skipif(not shutil.which("flock") or not shutil.which("git"), reason="needs flock and git")

DOCKER_STUB = """#!/usr/bin/env bash
echo "docker $*" >> "$STUB_DIR/calls"
if [ "$1" = compose ] && [ "$2" = up ]; then echo "$GIT_COMMIT" > "$STUB_DIR/running"; fi
exit 0
"""
CURL_STUB = """#!/usr/bin/env bash
url="${@: -1}"
echo "$url" >> "$STUB_DIR/curl_urls"
# a host listed in `down` does not answer (no certificate yet, say)
host=$(echo "$url" | sed -E 's#^https://([^/:]+).*#\\1#')
grep -qx "$host" "$STUB_DIR/down" 2>/dev/null && exit 7
running=$(cat "$STUB_DIR/running" 2>/dev/null || echo none)
grep -qx "$running" "$STUB_DIR/broken" 2>/dev/null && exit 22
printf '{"ok":true,"commit":"%s"}' "$running"
"""


def git(cwd: Path, *args: str) -> str:
    env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
           "GIT_COMMITTER_EMAIL": "t@t"}  # fmt: skip
    return subprocess.run(["git", *args], cwd=cwd, env=env, check=True, capture_output=True, text=True).stdout.strip()


class Setup:
    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.work = tmp / "work"  # where "developers" commit
        self.origin = tmp / "origin.git"
        self.repo = tmp / "server-clone"  # what the server has in /opt/stadtlaerm
        self.stubs = tmp / "stubs"
        self.state = tmp / "state"
        self.run = tmp / "run"
        for d in (self.work, self.stubs, self.run):
            d.mkdir()
        (self.stubs / "docker").write_text(DOCKER_STUB)
        (self.stubs / "curl").write_text(CURL_STUB)
        for f in ("docker", "curl"):
            (self.stubs / f).chmod(0o755)
        git(self.work, "init", "-q", "-b", "main")
        self.commit("first", {"server/compose.yaml": "services: {}\n", "server/Caddyfile": "a\n"})
        git(tmp, "clone", "-q", "--bare", str(self.work), str(self.origin))
        git(self.work, "remote", "add", "origin", str(self.origin))
        git(tmp, "clone", "-q", str(self.origin), str(self.repo))
        self.conf = tmp / "deploy.conf"
        self.conf.write_text("BRANCH=main\n")

    def commit(self, msg: str, files: dict[str, str | None]) -> str:
        for name, text in files.items():
            p = self.work / name
            if text is None:
                p.unlink()
            else:
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
        git(self.work, "add", "-A")
        git(self.work, "commit", "-q", "-m", msg)
        return git(self.work, "rev-parse", "HEAD")

    def push(self) -> None:
        git(self.work, "push", "-q", "origin", "main")

    def deploy(self) -> subprocess.CompletedProcess:
        env = {
            **os.environ,
            "PATH": f"{self.stubs}:{os.environ['PATH']}",
            "STUB_DIR": str(self.stubs),
            "REPO": str(self.repo),
            "RUN_DIR": str(self.run),
            "STATE": str(self.state),
            "CONF": str(self.conf),
            "HEALTH_TRIES": "2",
            "HEALTH_WAIT_S": "0",
        }
        return subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)

    def running(self) -> str:
        return (self.stubs / "running").read_text().strip()

    def calls(self) -> list[str]:
        p = self.stubs / "calls"
        return p.read_text().splitlines() if p.exists() else []


@pytest.fixture
def s(tmp_path: Path) -> Setup:
    return Setup(tmp_path)


def test_first_run_starts_current_commit_then_does_nothing(s):
    r = s.deploy()
    assert r.returncode == 0, r.stderr
    head = git(s.repo, "rev-parse", "HEAD")
    assert s.running() == head[:12]
    assert (s.state / "running").read_text().strip() == head
    n = len(s.calls())
    r = s.deploy()
    assert r.returncode == 0 and len(s.calls()) == n  # nothing new: no docker call


def test_new_commit_is_deployed(s):
    s.deploy()
    new = s.commit("change", {"server/app.txt": "x\n"})
    s.push()
    r = s.deploy()
    assert r.returncode == 0, r.stderr
    assert git(s.repo, "rev-parse", "HEAD") == new
    assert s.running() == new[:12]
    assert "deployed" in r.stdout
    assert not any("restart caddy" in c for c in s.calls())  # Caddyfile unchanged


def test_changed_caddyfile_restarts_caddy(s):
    s.deploy()
    s.commit("caddy", {"server/Caddyfile": "b\n"})
    s.push()
    assert s.deploy().returncode == 0
    assert any(c.endswith("compose restart caddy") for c in s.calls())


def test_unhealthy_commit_is_rolled_back_and_not_retried(s):
    s.deploy()
    old = git(s.repo, "rev-parse", "HEAD")
    bad = s.commit("broken", {"server/app.txt": "bad\n"})
    s.push()
    (s.stubs / "broken").write_text(bad[:12] + "\n")
    r = s.deploy()
    assert r.returncode == 1
    assert "going back" in r.stderr and "rolled back" in r.stderr
    assert git(s.repo, "rev-parse", "HEAD") == old
    assert s.running() == old[:12]
    n = len(s.calls())
    assert s.deploy().returncode == 0 and len(s.calls()) == n  # the failed commit is skipped
    fixed = s.commit("fix", {"server/app.txt": "good\n"})
    s.push()
    assert s.deploy().returncode == 0
    assert s.running() == fixed[:12]
    assert not (s.state / "failed").exists()


def test_branch_without_server_folder_is_refused(s):
    s.deploy()
    old = git(s.repo, "rev-parse", "HEAD")
    s.commit("no server", {"server/compose.yaml": None, "server/Caddyfile": None, "x.txt": "x\n"})
    s.push()
    r = s.deploy()
    assert r.returncode == 1 and "no server/compose.yaml" in r.stderr
    assert git(s.repo, "rev-parse", "HEAD") == old


def test_invalid_branch_in_config_is_refused(s):
    s.conf.write_text("BRANCH='main; rm -rf /'\n")
    r = s.deploy()
    assert r.returncode == 1 and "invalid BRANCH" in r.stderr
    assert s.calls() == []


def test_other_branch_from_config(s):
    git(s.work, "checkout", "-q", "-b", "editions")
    ed = s.commit("on editions", {"server/app.txt": "e\n"})
    git(s.work, "push", "-q", "origin", "editions")
    s.conf.write_text("BRANCH=editions\n")
    assert s.deploy().returncode == 0
    assert git(s.repo, "rev-parse", "HEAD") == ed
    assert git(s.repo, "branch", "--show-current") == "editions"


def curl_urls(s: Setup) -> list[str]:
    p = s.stubs / "curl_urls"
    return p.read_text().splitlines() if p.exists() else []


def test_health_check_asks_api_lessnoise_ch_first(s):
    assert s.deploy().returncode == 0
    assert curl_urls(s) == ["https://api.lessnoise.ch/healthz"]


def test_health_check_falls_back_to_the_old_host(s):
    """While api.lessnoise.ch does not answer on this machine, api.stadtlaerm.ch (still in
    API_HOSTS) is enough; a deployment is not rolled back for that."""
    (s.stubs / "down").write_text("api.lessnoise.ch\n")
    r = s.deploy()
    assert r.returncode == 0, r.stderr
    assert curl_urls(s)[:2] == ["https://api.lessnoise.ch/healthz", "https://api.stadtlaerm.ch/healthz"]


def test_health_url_from_config_wins(s):
    s.conf.write_text("BRANCH=main\nHEALTH_URL=https://example.invalid/healthz\n")
    assert s.deploy().returncode == 0
    assert set(curl_urls(s)) == {"https://example.invalid/healthz"}
