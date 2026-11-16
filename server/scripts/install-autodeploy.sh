#!/usr/bin/env bash
# One-time setup of the automatic deployment (server/README.md → «Automatic deployment»).
#
#   sudo /opt/stadtlaerm/server/scripts/install-autodeploy.sh [BRANCH]     (default: main)
#
# Installs a systemd timer that runs scripts/deploy.sh every 5 minutes. Run it again to change
# the branch; `sudo systemctl disable --now stadtlaerm-deploy.timer` switches it off.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }

BRANCH=${1:-main}
[[ "$BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || { echo "invalid branch '$BRANCH'" >&2; exit 1; }
REPO=$(cd "$(dirname "$0")/../.." && pwd)
command -v flock curl git docker >/dev/null || { echo "needs flock, curl, git, docker" >&2; exit 1; }

printf 'BRANCH=%s\n' "$BRANCH" > /etc/stadtlaerm-deploy.conf

cat > /etc/systemd/system/stadtlaerm-deploy.service <<UNIT
[Unit]
Description=Stadtlärm: deploy new commits of $BRANCH from GitHub
Wants=network-online.target
After=network-online.target docker.service

[Service]
Type=oneshot
Environment=REPO=$REPO
ExecStart=$REPO/server/scripts/deploy.sh
TimeoutStartSec=20min
UNIT

cat > /etc/systemd/system/stadtlaerm-deploy.timer <<UNIT
[Unit]
Description=Stadtlärm: check GitHub for new commits every 5 minutes

[Timer]
OnBootSec=3min
OnUnitActiveSec=5min
RandomizedDelaySec=30

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now stadtlaerm-deploy.timer
echo "Timer installed (branch $BRANCH). First run now:"
systemctl start stadtlaerm-deploy.service || true
journalctl -u stadtlaerm-deploy --no-pager -n 20
curl -fsS https://api.lessnoise.ch/healthz && echo
